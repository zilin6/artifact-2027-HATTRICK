/*
 * Copyright 2019 SiFive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You should have received a copy of LICENSE.Apache2 along with
 * this software. If not, you may obtain a copy at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.PlusArg

class SourceARequest(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val tag    = UInt(params.tagBits.W)
  val set    = UInt(params.setBits.W)
  val way    = UInt(params.wayBits.W)
  val put    = UInt(params.putBits.W)
  val param  = UInt(3.W)
  val source = UInt(params.outer.bundle.sourceBits.W)
  // 仅对普通 data A 请求有效：true 走 AcquireBlock，false 走 AcquirePerm。
  val block  = Bool()
  // 这笔 SourceA 请求是否走 counter 地址空间，而不是普通 cache line 地址空间。
  val isCounter = Bool()
  // 在 isCounter=1 的前提下，区分这是 counter get 还是 old victim counter put。
  val isCounterWrite = Bool()
}

class SourceA(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new SourceARequest(params)))
    val cus_base_address = Input(UInt(64.W))
    val l2_crypto_assert_enable = Input(Bool())
    // 送给 SourceC 的 snapshot 索引：指出 SourceA 当前要消费哪个 MSHR 的 old victim counter snapshot。
    val ctr_snapshot_idx = Output(UInt(log2Ceil(params.mshrs).W))
    // SourceC 保存在 snapshot buffer 里的 old victim counter 值。
    val ctr_snapshot_data = Input(UInt(params.outer.bundle.dataBits.W))
    // SourceC 是否已经把 ctr_snapshot_idx 指向的 snapshot 准备好了。
    val ctr_snapshot_valid = Input(Bool())
    // SourceA 成功发出 outer counterPut 后，通知 SourceC 释放对应 snapshot 槽位。
    val ctr_snapshot_pop = Output(Bool())
    val a = Decoupled(new TLBundleA(params.outer.bundle))
  })

  // ready must be a register, because we derive valid from ready
  require (!params.micro.outerBuf.a.pipe && params.micro.outerBuf.a.isDefined)
  require(params.outer.bundle.addressBits <= 64)
  require(params.outer.manager.beatBytes >= 8)

  val a = Wire(chiselTypeOf(io.a))
  io.a <> params.micro.outerBuf.a(a)
  private val l2CryptoAssertEnable = io.l2_crypto_assert_enable
  val debugLogEnable = PlusArg("inclusive_cache_debug_log", default = 0, width = 1) =/= 0.U
  private val debugWatchTagValue = BigInt("1000", 16)
  private val debugWatchSetValue = BigInt("05c", 16)
  private def truncToWidth(x: BigInt, width: Int): UInt = (x & ((BigInt(1) << width) - 1)).U(width.W)
  val debugWatchTag = truncToWidth(debugWatchTagValue, params.tagBits)
  val debugWatchSet = truncToWidth(debugWatchSetValue, params.setBits)
  val debugDataWatchStart = "h0000000080002000".U(64.W)
  val debugDataWatchEnd = "h0000000080006300".U(64.W)

  // SourceA 本地暂存“待发的 old victim counterPut 请求”是否有效。
  // 这笔请求进入 staging 后，要等 SourceC 的 snapshot valid 才真正出包。
  val putStageValid = RegInit(false.B)
  // 暂存的 old victim counterPut 请求本体；保存地址/来源/source 等发包所需字段。
  val putStageReq = Reg(new SourceARequest(params))

  // 当前入口 req 是否就是一笔 old victim counterPut。
  val directCounterPutReq = io.req.bits.isCounter && io.req.bits.isCounterWrite
  // snapshot 索引宽度；SourceC 的 snapshot buffer 以 MSHR 号索引。
  val ctrSnapIdxBits = log2Ceil(params.mshrs)
  // 如果本地 staging 里已经挂着一笔 counterPut，就用暂存请求的 source 去取 snapshot；
  // 否则直接看当前入口请求。
  val snapSource = Mux(putStageValid, putStageReq.source, io.req.bits.source)
  io.ctr_snapshot_idx := OuterRequestSourceType.mshr(snapSource)(ctrSnapIdxBits - 1, 0)
  io.ctr_snapshot_pop := false.B

  when (io.req.fire && directCounterPutReq) {
    when (debugLogEnable) {
      printf(p"[SOURCEA-CTR-STAGE] source=0x${Hexadecimal(io.req.bits.source)} mshr=0x${Hexadecimal(OuterRequestSourceType.mshr(io.req.bits.source)(ctrSnapIdxBits - 1, 0))} set=0x${Hexadecimal(io.req.bits.set)} tag=0x${Hexadecimal(io.req.bits.tag)} way=0x${Hexadecimal(io.req.bits.way)} data=0x${Hexadecimal(io.ctr_snapshot_data)} snapshot_valid=${io.ctr_snapshot_valid}\n")
    }
    putStageValid := true.B
    putStageReq := io.req.bits
  }
  when (a.fire && putStageValid) {
    // outer Put 发出后，释放这笔 old victim counter snapshot。
    when (debugLogEnable) {
      printf(p"[SOURCEA-CTR-POP] source=0x${Hexadecimal(putStageReq.source)} mshr=0x${Hexadecimal(OuterRequestSourceType.mshr(putStageReq.source)(ctrSnapIdxBits - 1, 0))} data=0x${Hexadecimal(io.ctr_snapshot_data)} snapshot_valid=${io.ctr_snapshot_valid}\n")
    }
    putStageValid := false.B
    io.ctr_snapshot_pop := true.B
  }
  assert(!l2CryptoAssertEnable || !io.ctr_snapshot_pop || a.fire,
    "SourceA popped counter snapshot without firing outer counterPut")

  // 对外发包时真正采用的请求视图：
  //   1. 若本地 staging 挂着 old victim counterPut，则优先发暂存请求
  //   2. 否则直接发当前入口请求
  val reqBits = Mux(putStageValid, putStageReq, io.req.bits)
  // 对外 a.valid 的真实来源：
  //   1. 若在等 old victim counterPut，则必须等到对应 snapshot valid 才能发
  //   2. 否则就是普通入口握手
  val reqValid = Mux(putStageValid, io.ctr_snapshot_valid, io.req.valid && !directCounterPutReq)

  // 让调度器可见的 ready 与本地 old victim snapshot staging 解耦，避免反向形成组合依赖。
  io.req.ready := !putStageValid && a.ready
  a.valid := reqValid
  when (debugLogEnable && putStageValid && !io.ctr_snapshot_valid) {
    printf(p"[SOURCEA-CTR-WAIT] source=0x${Hexadecimal(putStageReq.source)} mshr=0x${Hexadecimal(io.ctr_snapshot_idx)} snapshot_valid=${io.ctr_snapshot_valid}\n")
  }
  params.ccover(a.valid && !a.ready, "SOURCEA_STALL", "Backpressured when issuing an Acquire")
  params.ccover(putStageValid && !io.ctr_snapshot_valid, "SOURCEA_COUNTER_SNAPSHOT_WAIT", "SourceA counterPut waiting for old victim counter snapshot from SourceC")

  // 普通 cache line 地址；data acquire 仍按 line address 发往 outer。
  val lineAddress = params.restoreAddress(params.expandAddress(reqBits.tag, reqBits.set, 0.U))
  val lineAddress64 =
    if (params.outer.bundle.addressBits < 64) {
      Cat(0.U((64 - params.outer.bundle.addressBits).W), lineAddress)
    } else {
      lineAddress
    }
  // counter sidecar 地址推导：
  // 先把 lineAddress 折算成“第几条 cache line”，再映射到 baseAddress 起始的
  // 8-byte counter slot 空间中。
  val counterAddress = io.cus_base_address + ((lineAddress64 >> params.offsetBits) << 3)
  val counterAddressTrunc = counterAddress(params.outer.bundle.addressBits - 1, 0)

  // 是否发 outer counter Get。
  val useCounterGet = reqBits.isCounter && !reqBits.isCounterWrite
  // 是否发 outer counter Put（也就是 old victim counter writeback）。
  val useCounterPut = reqBits.isCounter && reqBits.isCounterWrite
  // 统一表示这笔请求是否走 counter 地址空间。
  val useCounterReq = useCounterGet || useCounterPut
  // 0=data acquire, 1=counter get, 2=counter put
  val debugReqType = Mux(useCounterPut, 2.U(2.W), Mux(useCounterGet, 1.U(2.W), 0.U(2.W)))
  // 调试用：把普通 data A 请求中落在 backing-counter 区域附近的访问也打出来，
  // 用来观察软件最终对 backing slot 的普通 load/store 是否重新走了 outer data 路径。
  val backingRegionDataReq =
    !useCounterReq &&
    lineAddress64 >= io.cus_base_address &&
    lineAddress64 < (io.cus_base_address + 0x4000.U)
  val debugDataRangeReq =
    !useCounterReq &&
    lineAddress64 >= debugDataWatchStart &&
    lineAddress64 < debugDataWatchEnd

  // outer counter get 包模板。
  val counterGet = params.outer.Get(reqBits.source, counterAddressTrunc, 3.U)._2
  // counterPut 使用的值直接来自 SourceC 预先抓好的 old victim snapshot。
  val counter_value = io.ctr_snapshot_data
  // outer counter put 包模板。
  val counterPut = params.outer.Put(reqBits.source, counterAddressTrunc, 3.U, counter_value)._2

  a.bits.opcode := Mux(useCounterGet, counterGet.opcode,
                   Mux(useCounterPut, counterPut.opcode,
                     Mux(reqBits.block, TLMessages.AcquireBlock, TLMessages.AcquirePerm)))
  a.bits.param := Mux(useCounterGet, counterGet.param,
                  Mux(useCounterPut, counterPut.param, reqBits.param))
  a.bits.size := Mux(useCounterGet, counterGet.size,
                 Mux(useCounterPut, counterPut.size, params.offsetBits.U))
  a.bits.source := reqBits.source
  a.bits.address := Mux(useCounterReq, counterAddressTrunc, lineAddress)
  a.bits.mask := Mux(useCounterGet, counterGet.mask,
                 Mux(useCounterPut, counterPut.mask, ~0.U(params.outer.manager.beatBytes.W)))
  a.bits.data := Mux(useCounterPut, counterPut.data, 0.U)
  a.bits.corrupt := false.B

}
