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
import freechips.rocketchip.rocket.CacheCryptoRefillMeta
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.util.PlusArg
import TLMessages._
import TLAtomics._
import TLPermissions._

class SourceDRequest(params: InclusiveCacheParameters) extends FullRequest(params)
{
  val sink = UInt(params.inner.bundle.sinkBits.W)
  val way  = UInt(params.wayBits.W)
  val bad  = Bool()
}

class SourceDHazard(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(params.setBits.W)
  val way = UInt(params.wayBits.W)
}

class PutBufferACEntry(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val data = UInt(params.inner.bundle.dataBits.W)
  val mask = UInt((params.inner.bundle.dataBits/8).W)
  val corrupt = Bool()
}

class SourceD(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new SourceDRequest(params)))
    val ctr_radr = Decoupled(new CounterSidecarAddress(params))
    val ctr_rdat = Input(UInt(params.outer.bundle.dataBits.W))
    val d = Decoupled(new TLBundleD(params.inner.bundle))
    // Put data from SinkA
    val pb_pop = Decoupled(new PutBufferPop(params))
    val pb_beat = Flipped(new PutBufferAEntry(params))
    // Release data from SinkC
    val rel_pop  = Decoupled(new PutBufferPop(params))
    val rel_beat = Flipped(new PutBufferCEntry(params))
    // Access to the BankedStore
    val bs_radr = Decoupled(new BankedStoreInnerAddress(params))
    val bs_rdat = Flipped(new BankedStoreInnerDecoded(params))
    val bs_wadr = Decoupled(new BankedStoreInnerAddress(params))
    val bs_wdat = new BankedStoreInnerPoison(params)
    val counter_write = Decoupled(new CounterSidecarWrite(params))
    // Is it safe to evict/replace this way?
    val evict_req  = Flipped(new SourceDHazard(params))
    val evict_safe = Bool()
    val counter_grant_req = Flipped(new SourceDHazard(params))
    val counter_grant_safe = Bool()
    val grant_req  = Flipped(new SourceDHazard(params))
    val grant_safe = Bool()
    val l2_crypto_assert_enable = Input(Bool())
  })
  private val l2CryptoAssertEnable = io.l2_crypto_assert_enable
  private def assertOnlyWatchdog(waiting: Bool, limit: Int, message: String): Unit = {
    val enabledWaiting = l2CryptoAssertEnable && waiting
    val cycles = RegInit(0.U(log2Ceil(limit + 1).W))
    when (!enabledWaiting) {
      cycles := 0.U
    } .elsewhen (cycles =/= limit.U) {
      cycles := cycles + 1.U
    }
    assert(!l2CryptoAssertEnable || cycles =/= limit.U, message)
  }

  val beatBytes = params.inner.manager.beatBytes
  val writeBytes = params.micro.writeBytes
  val debugLogEnable = PlusArg("inclusive_cache_debug_log", default = 0, width = 1) =/= 0.U
  val debugDataWatchStart = "h0000000080002000".U(64.W)
  val debugDataWatchEnd = "h0000000080006300".U(64.W)

  val s1_valid = Wire(Bool())
  val s2_valid = Wire(Bool())
  val s3_valid = Wire(Bool())
  val s2_ready = Wire(Bool())
  val s3_ready = Wire(Bool())
  val s4_ready = Wire(Bool())
  ////////////////////////////////////// STAGE 1 //////////////////////////////////////
  // Reform the request beats

  val busy = RegInit(false.B)
  // 记录“上一拍已经向 data bank 发起过读请求，但结果还没被 s2 接住”的阻塞状态。
  val s1_block_r = RegInit(false.B)
  // counter committed reread 需要与 data reread 拆开记 in-flight 状态；
  // 否则会出现 data 这拍已经 fire、但 counter 这拍还没 fire，下一拍却被统一 block 位
  // 错当成“整拍 reread 已经处理过”的情况。
  val s1_block_ctr_r = RegInit(false.B)
  // 当前这笔请求已经推进到第几个 inner beat；用于多拍事务逐拍展开。
  val s1_counter = RegInit(0.U(params.innerBeatBits.W))
  // 在入口接收新请求时，把请求内容锁存下来，供 busy 期间后续拍继续使用。
  val s1_req_reg = RegEnable(io.req.bits, !busy && io.req.valid)
  // s1 当前真正处理的请求：空闲时直接看入口 io.req.bits，busy 时改看锁存的 s1_req_reg。
  val s1_req = Mux(!busy, io.req.bits, s1_req_reg)
  val traceReleaseSet0 = (busy || io.req.valid) && !s1_req.prio(0) && s1_req.set === 0.U
  // val s1LineAddress = params.restoreAddress(params.expandAddress(s1_req.tag, s1_req.set, 0.U))
  // val s1LineAddress64 =
  //   if (params.inner.bundle.addressBits < 64) {
  //     Cat(0.U((64 - params.inner.bundle.addressBits).W), s1LineAddress)
  //   } else {
  //     s1LineAddress
  //   }
  // 当前拍根据更年轻流水状态算出来的 data bypass 掩码，按 writeBytes 粒度指示哪些字节可直接前递。
  val s1_x_bypass = Wire(UInt((beatBytes/writeBytes).W)) // might go from high=>low during stall
  // 标记这一拍是否允许重新采样新的 bypass 掩码；若 s2 卡住，则要保持上一拍的 bypass 结果不抖动。
  val s1_latch_bypass = RegNext(!(busy || io.req.valid) || s2_ready)
  // s1 最终使用的稳定 bypass 掩码：可重采样时直接用 s1_x_bypass，否则保持上一拍锁存值。
  val s1_bypass = Mux(s1_latch_bypass, s1_x_bypass, RegEnable(s1_x_bypass, s1_latch_bypass))
  // 当前 beat 需要从 data bank 读取的字节掩码；已经能通过 bypass 拿到的字节会在这里被清掉。
  val s1_mask = MaskGen(s1_req.offset, s1_req.size, beatBytes, writeBytes) & ~s1_bypass
  // 这笔请求是否是 grant 型操作：AcquireBlock(BtoT) 或 AcquirePerm；这类请求在返回语义上是单拍 grant。
  val s1_grant = (s1_req.opcode === AcquireBlock && s1_req.param === BtoT) || s1_req.opcode === AcquirePerm
  // 当前 beat 是否真的需要访问 data bank：
  //   1. 仍有未被 bypass 覆盖的字节
  //   2. 这是 inner A channel 请求(prio(0)=1)，也就是来自 SinkA、在 SourceD 中走主 transaction 路径的请求
  //   3. 不是 Hint，也不是 grant 型快捷路径
  //   4. 对 PutFullData 大小达到 writeBytes 的整拍覆盖场景，不必再读旧 data
  val s1_need_r = s1_mask.orR && s1_req.prio(0) && s1_req.opcode =/= Hint && !s1_grant &&
                  (s1_req.opcode =/= PutFullData || s1_req.size < log2Ceil(writeBytes).U )
  // 这里刻意让 counter 跟随 data 的 reread 决策：
  //   1. 只要当前这拍 data 需要走 committed reread，crypto line 的 counter 也必须同步 reread
  //   2. 不允许出现“data 走 read-side、counter 单独走 bypass-only”的分叉语义
  // 这就是我们想要的效果：优先保证 data/counter 在 SourceD 主路径上的时序和语义同步。
  val s1_need_ctr_r = s1_need_r && s1_req.cryptoLine
   
  // 当前拍是否形成一个有效的 data-bank 读请求：
  //   - 当前确实有请求在处理
  //   - 这拍语义上需要读
  //   - 没有被前一拍尚未消化的读结果(s1_block_r)挡住
  val s1_valid_r = (busy || io.req.valid) && s1_need_r && !s1_block_r

   // s1_valid_ctr_r 则进一步要求：当前 s1 上确实有一笔有效请求存在。
  // busy 表示 pipeline 里已经挂着这笔请求，io.req.valid 表示入口这拍新来了请求；
  // 两者取或后，再与 need 条件相与，才表示“这次 counter read 判定当前真正有效”。
  val s1_valid_ctr_r = (busy || io.req.valid) && s1_need_ctr_r && !s1_block_ctr_r
  // 这笔请求是否需要从 payload buffer 取数据：
  //   - A 路看 opcode 是否带 data
  //   - C 路看是否是 ReleaseData(opcode(0)=1)
  val s1_need_pb = Mux(s1_req.prio(0), !s1_req.opcode(2), s1_req.opcode(0)) // hasData
  // 这笔事务是否在协议上只占单拍：
  //   - A 路的 Hint 或 grant 型响应算单拍
  //   - C 路纯 Release(不带 data)算单拍
  val s1_single = Mux(s1_req.prio(0), s1_req.opcode === Hint || s1_grant, s1_req.opcode === Release)
  // 这笔事务是否会在后续流水形成“可退休并进入 younger-state 链”的数据更新。
  // 单拍事务不形成这条 retired/bypass 链，多拍或带 data 的事务才会。
  val s1_retires = !s1_single // retire all operations with data in s3 for bypass (saves energy)
  // Alternatively: val s1_retires = s1_need_pb // retire only updates for bypass (less backpressure from WB)
  // 这笔事务总共有多少个 beat 减一；和 s1_counter 配合判断 first/last。
  val s1_beats1 = Mux(s1_single, 0.U, UIntToOH1(s1_req.size, log2Up(params.cache.blockBytes)) >> log2Ceil(beatBytes))
  // 当前拍真正对应的 beat 编号：请求起始 offset 对应的 beat，再加上多拍展开计数 s1_counter。
  val s1_beat = (s1_req.offset >> log2Ceil(beatBytes)) | s1_counter
  val s1_last = s1_counter === s1_beats1
  val s1_first = s1_counter === 0.U

  params.ccover(s1_block_r, "SOURCED_1_SRAM_HOLD", "SRAM read-out successful, but stalled by stage 2")
  params.ccover(!s1_latch_bypass, "SOURCED_1_BYPASS_HOLD", "Bypass match successful, but stalled by stage 2")
  params.ccover((busy || io.req.valid) && !s1_need_r, "SOURCED_1_NO_MODIFY", "Transaction servicable without SRAM")

  io.bs_radr.valid     := s1_valid_r
  io.bs_radr.bits.noop := false.B
  io.bs_radr.bits.way  := s1_req.way
  io.bs_radr.bits.set  := s1_req.set
  io.bs_radr.bits.beat := s1_beat
  io.bs_radr.bits.mask := s1_mask
  
  io.ctr_radr.valid := s1_valid_ctr_r
  io.ctr_radr.bits.set := s1_req.set
  io.ctr_radr.bits.way := s1_req.way

  params.ccover(io.bs_radr.valid && !io.bs_radr.ready, "SOURCED_1_READ_STALL", "Data readout stalled")
  assertOnlyWatchdog(
    s1_req.cryptoLine && io.bs_radr.valid && !io.bs_radr.ready,
    2048,
    "SourceD data read address blocked too long")
  assertOnlyWatchdog(
    io.ctr_radr.valid && !io.ctr_radr.ready,
    2048,
    "SourceD counter read address blocked too long")

  // Make a queue to catch BS readout during stalls
  val queue = Module(new Queue(chiselTypeOf(io.bs_rdat), 3, flow=true))
  queue.io.enq.valid := RegNext(RegNext(io.bs_radr.fire))
  queue.io.enq.bits := io.bs_rdat
  assert (!queue.io.enq.valid || queue.io.enq.ready)

  params.ccover(!queue.io.enq.ready, "SOURCED_1_QUEUE_FULL", "Filled SRAM skidpad queue completely")

  // counter 版 readout skid queue。
  // 这一路专门承接 committed counter read 的返回值；之所以也做成和 data 一样的
  // Queue(..., depth=3, flow=true)，是为了让 data/counter 在“读返回先入本地缓冲、
  // 后级再消费”的结构上保持一致，避免后级 stall 时丢失 counter readout。
  val ctrQueue = Module(new Queue(UInt(params.outer.bundle.dataBits.W), 3, flow=true))
  ctrQueue.io.enq.valid := RegNext(RegNext(io.ctr_radr.fire))
  ctrQueue.io.enq.bits := io.ctr_rdat
  assert (!ctrQueue.io.enq.valid || ctrQueue.io.enq.ready)

  params.ccover(!ctrQueue.io.enq.ready, "SOURCED_1_COUNTER_QUEUE_FULL", "Filled counter readout skidpad queue completely")

  when (io.bs_radr.fire) { s1_block_r := true.B }
  when (io.ctr_radr.fire) { s1_block_ctr_r := true.B }
  when (io.req.valid) { busy := true.B }
  when (s1_valid && s2_ready) {
    s1_counter := s1_counter + 1.U
    s1_block_r := false.B
    s1_block_ctr_r := false.B
    when (s1_last) {
      s1_counter := 0.U
      busy := false.B
    }
  }

  params.ccover(s1_valid && !s2_ready, "SOURCED_1_STALL", "Stage 1 pipeline blocked")
  assertOnlyWatchdog(
    s1_req.cryptoLine && s1_valid && !s2_ready,
    6144,
    "SourceD stage 1 blocked too long")

  io.req.ready := !busy
  // s1_valid 不是“有请求就成立”，而是“这拍可以真正把请求接纳进 s1”：
  //   1. 先要求当前确实有请求来源（入口新请求 io.req.valid，或 busy 表示已有请求在继续）
  //   2. 如果这拍需要发 data bank read，则 bs_radr.ready 必须为 1
  //   3. 如果这拍需要发 committed counter read，则 ctr_radr.ready 也必须为 1
  // 只有当本拍所需的前置读口都准备好时，s1 才真正有效；否则不能让流水先前进、
  // 再补发缺失的 data/counter read。
  s1_valid := (busy || io.req.valid) && (!s1_valid_r || io.bs_radr.ready) && (!s1_valid_ctr_r || io.ctr_radr.ready)

  ////////////////////////////////////// STAGE 2 //////////////////////////////////////
  // Fetch the request data

  val s2_latch = s1_valid && s2_ready
  val s2_full = RegInit(false.B)
  // 标记 s2 当前是否还在等待 payload buffer/pop 侧真正把这一拍的数据交付出来。
  val s2_valid_pb = RegInit(false.B)
  // 锁存当前请求在 s2 对应的 beat 编号，供后续 payload pop / bypass / 响应路径使用。
  val s2_beat = RegEnable(s1_beat, s2_latch)
  // 锁存 s1 算好的稳定 data bypass 掩码；当前只看 BOOM DCache 主链时可先忽略其细节。
  val s2_bypass = RegEnable(s1_bypass, s2_latch)
  // 锁存当前正在 s2 推进的请求元数据，供后续 s3/s4 响应和写回路径使用。
  val s2_req = RegEnable(s1_req, s2_latch)
  // 锁存这拍是否是当前事务的最后一个 beat，用于 payload pop.last 和多拍结束判断。
  val s2_last = RegEnable(s1_last, s2_latch)
  // 锁存当前这拍语义上是否需要 data bank 读；后续 s3 会据此决定是否消费读返回队列。
  val s2_need_r = RegEnable(s1_need_r, s2_latch)
  // 锁存当前这拍是否需要从 payload buffer 取数据；决定走 pb_pop 还是 rel_pop。
  val s2_need_pb = RegEnable(s1_need_pb, s2_latch)
  // 锁存这笔事务是否会在后续形成 retired younger-state；供 s3/s4 判断是否进入更新链。
  val s2_retires = RegEnable(s1_retires, s2_latch)
  // 这拍推进到 s3 时，是否需要真的对外发一个 D 响应 beat
  val s2_need_d = RegEnable(!s1_need_pb || s1_first, s2_latch)
  // s2_counter_payload_raw 和 s2_pdata_raw 对齐：
  //   - A 路从 SinkA 的 transaction-owned counter buffer 取
  //   - C 路从 SinkC 的 transaction-owned counter buffer 取
  // 两路都共享与 data 相同的 put/index。
  val s2_counter_payload_raw = Wire(UInt(params.outer.bundle.dataBits.W))
  val s2_counter_payload_valid_now = Wire(Bool())
  s2_counter_payload_raw := Mux(s2_req.prio(0), io.pb_beat.counter, io.rel_beat.counter)
  s2_counter_payload_valid_now := Mux(s2_req.prio(0), io.pb_beat.counterValid, io.rel_beat.counterValid)
  // s2 只有一套 unified payload in-flight 状态：s2_valid_pb。
  // counter payload 是否参与，不再单独保留一套状态寄存器，而是由“这拍在等 unified payload”
  // 且“事务本身是 crypto line”共同派生出来。
  val s2_counter_payload_valid = s2_valid_pb && s2_req.cryptoLine
  val s2_counter_payload_value = s2_counter_payload_raw holdUnless s2_counter_payload_valid
  // 只有当 s2 当前这拍真的在消费 live payload entry 时，
  // 才要求同一个 payload entry 里的 counter 也同时有效。
  // 这里必须同时要求 s2_valid_pb=1；否则这笔事务虽然仍停留在 s2，
  // 但 payload 已在更早一拍被锁进本地寄存，不应再去检查当前 live pb/rel_beat。
  when (s2_valid && s2_valid_pb && s2_req.cryptoLine && !s2_counter_payload_valid_now) {
    chisel3.printf("[SOURCED-CTR-MISS] s2_valid=%d s2_valid_pb=%d path=%d opcode=0x%x param=0x%x size=%d source=%d set=0x%x way=%d put=0x%x beat=%d last=%d need_pb=%d need_r=%d pb_ready=%d rel_ready=%d pb_ctr_valid=%d rel_ctr_valid=%d pb_ctr=0x%x rel_ctr=0x%x\n",
      s2_valid,
      s2_valid_pb,
      s2_req.prio(0),
      s2_req.opcode,
      s2_req.param,
      s2_req.size,
      s2_req.source,
      s2_req.set,
      s2_req.way,
      s2_req.put,
      s2_beat,
      s2_last,
      s2_need_pb,
      s2_need_r,
      io.pb_pop.ready,
      io.rel_pop.ready,
      io.pb_beat.counterValid,
      io.rel_beat.counterValid,
      io.pb_beat.counter,
      io.rel_beat.counter)
  }
  assert(!l2CryptoAssertEnable || !s2_valid || !s2_counter_payload_valid || s2_counter_payload_valid_now,
    "SourceD expected a transaction-owned counter payload but the payload entry was invalid")
  // when (s2_valid && s2_valid_pb && s2_req.cryptoLine) {
  //   assert(Mux(s2_req.prio(0), io.pb_beat.counterValid, io.rel_beat.counterValid),
  //     "SourceD payload-path crypto beat must carry a valid counter payload")
  // }
  // s2_pdata_raw 是“当前组合拍看到的 payload 数据候选”：
  //   - 若这笔请求来自 A 路，则取自 SinkA putbuffer 弹出的 pb_beat
  //   - 若这笔请求来自 C 路，则取自 SinkC release sideband 的 rel_beat
  // 它只是一个组合 wire，本身不会在后级回压时自动保值。
  val s2_pdata_raw = Wire(new PutBufferACEntry(params))
  // s2_pdata 是给后续阶段真正使用的稳定 payload 视图。
  // holdUnless(s2_valid_pb) 的意思是：
  //   - 当这一拍 payload 分支有效时，允许 s2_pdata 更新为最新的 s2_pdata_raw
  //   - 当 payload 分支因为后级/弹出握手停住时，继续保持上一拍的值不变
  // 可把它近似理解成下面的伪代码：
  //   if (s2_valid_pb) {
  //     s2_pdata = s2_pdata_raw
  //   } else {
  //      s2_pdata 保持不变
  //   }
  // 这样可以避免 pb_beat / rel_beat 在 stall 时变化，导致同一笔 s2 payload 数据被覆盖。
  val s2_pdata = s2_pdata_raw holdUnless s2_valid_pb
  val s2LineAddress = params.restoreAddress(params.expandAddress(s2_req.tag, s2_req.set, 0.U))
  val s2LineAddress64 =
    if (params.inner.bundle.addressBits < 64) {
      Cat(0.U((64 - params.inner.bundle.addressBits).W), s2LineAddress)
    } else {
      s2LineAddress
    }
  val s2DebugDataRange =
    s2LineAddress64 >= debugDataWatchStart &&
    s2LineAddress64 < debugDataWatchEnd

  // 当前请求自己的原始 payload 来源在这里选：
  //   - A 路径（prio(0)=1）来自 SinkA putbuffer 的 pb_beat
  //   - C 路径（prio(0)=0）来自 SinkC ReleaseData sideband 的 rel_beat
  // 后面如果这笔请求又变成了“更年轻流水里的旁路来源”，它的 data 会继续以 s4/s5/s6/s7
  // 这些 stage data 的形式参与 s3_bypass_data，而不再以原始 io.rel_beat/io.pb_beat 名字出现。
  s2_pdata_raw.data    := Mux(s2_req.prio(0), io.pb_beat.data, io.rel_beat.data)
  s2_pdata_raw.mask    := Mux(s2_req.prio(0), io.pb_beat.mask, ~0.U(params.inner.manager.beatBytes.W))
  s2_pdata_raw.corrupt := Mux(s2_req.prio(0), io.pb_beat.corrupt, io.rel_beat.corrupt)

  io.pb_pop.valid := s2_valid_pb && s2_req.prio(0)
  io.pb_pop.bits.index := s2_req.put
  io.pb_pop.bits.last  := s2_last
  io.rel_pop.valid := s2_valid_pb && !s2_req.prio(0)
  io.rel_pop.bits.index := s2_req.put
  io.rel_pop.bits.last  := s2_last

  params.ccover(io.pb_pop.valid && !io.pb_pop.ready, "SOURCED_2_PUTA_STALL", "Channel A put buffer was not ready in time")
  if (!params.firstLevel)
    params.ccover(io.rel_pop.valid && !io.rel_pop.ready, "SOURCED_2_PUTC_STALL", "Channel C put buffer was not ready in time")
  assertOnlyWatchdog(
    s2_req.cryptoLine && io.pb_pop.valid && !io.pb_pop.ready,
    2048,
    "SourceD SinkA payload pop blocked too long")
  if (!params.firstLevel) {
    assertOnlyWatchdog(
      s2_req.cryptoLine && io.rel_pop.valid && !io.rel_pop.ready,
      2048,
      "SourceD SinkC payload pop blocked too long")
  }

  val pb_ready = Mux(s2_req.prio(0), io.pb_pop.ready, io.rel_pop.ready)
  when (pb_ready) { s2_valid_pb := false.B }
  when (s2_valid && s3_ready) { s2_full := false.B }
  when (s2_latch) { s2_valid_pb := s1_need_pb }
  when (s2_latch) { s2_full := true.B }

  params.ccover(s2_valid && !s3_ready, "SOURCED_2_STALL", "Stage 2 pipeline blocked")
  assertOnlyWatchdog(
    s2_req.cryptoLine && s2_valid && !s3_ready,
    6144,
    "SourceD stage 2 blocked too long")

  s2_valid := s2_full && (!s2_valid_pb || pb_ready)
  s2_ready := !s2_full || (s3_ready && (!s2_valid_pb || pb_ready))

  ////////////////////////////////////// STAGE 3 //////////////////////////////////////
  // Send D response

  val s3_latch = s2_valid && s3_ready
  val s3_full = RegInit(false.B)
  val s3_valid_d = RegInit(false.B)
  val s3_beat = RegEnable(s2_beat, s3_latch)
  val s3_bypass = RegEnable(s2_bypass, s3_latch)

  val s3_req = RegEnable(s2_req, s3_latch)
  val s3_adjusted_opcode = Mux(s3_req.bad, Get, s3_req.opcode) // kill update when denied
  val s3_last = RegEnable(s2_last, s3_latch)
  val s3_pdata = RegEnable(s2_pdata, s3_latch)
  val s3_need_pb = RegEnable(s2_need_pb, s3_latch)
  val s3_retires = RegEnable(s2_retires, s3_latch)
  val s3_need_r = RegEnable(s2_need_r, s3_latch)
  // 要不要写回 BankedStore”本质上等价于“这笔事务有没有携带要提交的新 payload data
  val s3_need_bs = s3_need_pb
  val s3_acq = s3_req.opcode === AcquireBlock || s3_req.opcode === AcquirePerm
  val s3_counter_payload_value = RegEnable(s2_counter_payload_value, s3_latch)
  // counter source 跟随 data 这拍的 beat-owner 语义：
  //   - 只要 data 这拍是 payload beat，counter 必须也走 payload
  //   - 只有在非 payload 且需要 reread 的 crypto beat 上，counter 才能走 reread 域
  val s3_counter_payload_path = s3_need_pb && s3_req.cryptoLine
  val s3_counter_needs_committed = !s3_need_pb && s3_need_r && s3_req.cryptoLine

  val s3_counter_pipe_available = Wire(Bool())
  val s3_counter_pipe_value = Wire(UInt(params.outer.bundle.dataBits.W))
  val s3_counter_committed_value = Wire(UInt(params.outer.bundle.dataBits.W))
  val s3_counter_reread_value = Wire(UInt(params.outer.bundle.dataBits.W))
  val s3_counter_for_resp = Wire(UInt(params.outer.bundle.dataBits.W))

  // 和 data queue 一样，counter queue 的可用性应在“当前已经位于 s3 的这拍”检查，
  // 而不是在刚 s2->s3 latch 的当拍检查；后者对 data/counter 都可能合法地还没看到返回值。
  assert(!l2CryptoAssertEnable || !s3_full || !s3_counter_needs_committed || ctrQueue.io.deq.valid,
    "SourceD expected a committed counter read response but ctrQueue was empty")

  // Collect s3's data from either the BankedStore or bypass
  // NOTE: we use the s3_bypass passed down from s1_bypass, because s2-s4 were guarded by the hazard checks and not stale
  // s3_bypass_data 及其下面的拼接逻辑当前只看主链时可先忽略其细节。
  val s3_bypass_data = Wire(UInt())
  def chunk(x: UInt): Seq[UInt] = Seq.tabulate(beatBytes/writeBytes) { i => x((i+1)*writeBytes*8-1, i*writeBytes*8) }
  def chop (x: UInt): Seq[Bool] = Seq.tabulate(beatBytes/writeBytes) { i => x(i) }
  def bypass(sel: UInt, x: UInt, y: UInt) =
    (chop(sel) zip (chunk(x) zip chunk(y))) .map { case (s, (x, y)) => Mux(s, x, y) } .asUInt
  // 伪代码：
  //   for each chunk i:
  //     if (s3_bypass(i) == 1) rdata[i] = s3_bypass_data[i]
  //     else                   rdata[i] = queue.io.deq.bits.data[i]
  // 也就是：对每个 writeBytes 粒度的 data chunk，若更年轻流水已经给出最新值，就取 bypass；
  // 否则退回 committed data reread 的返回值。
  val s3_rdata = bypass(s3_bypass, s3_bypass_data, queue.io.deq.bits.data)
  s3_counter_committed_value := ctrQueue.io.deq.bits

  s3_counter_reread_value :=
    Mux(s3_counter_pipe_available, s3_counter_pipe_value, s3_counter_committed_value)
  // 发给 inner D 的 counter 必须与 d.bits.data := s3_rdata 同域：
  // 这里只允许使用 reread/bypass 域的 counter，不使用 payload counter。
  s3_counter_for_resp :=
    Mux(s3_counter_needs_committed, s3_counter_reread_value, 0.U)
  val s3LineAddress = params.restoreAddress(params.expandAddress(s3_req.tag, s3_req.set, 0.U))
  val s3LineAddress64 =
    if (params.inner.bundle.addressBits < 64) {
      Cat(0.U((64 - params.inner.bundle.addressBits).W), s3LineAddress)
    } else {
      s3LineAddress
    }
  val s3DebugDataRange =
    s3LineAddress64 >= debugDataWatchStart &&
    s3LineAddress64 < debugDataWatchEnd

  // Lookup table for response codes
  val grant = Mux(s3_req.param === BtoT, Grant, GrantData)
  val resp_opcode = VecInit(Seq(AccessAck, AccessAck, AccessAckData, AccessAckData, AccessAckData, HintAck, grant, Grant))

  // No restrictions on the type of buffer used here
  val d = Wire(chiselTypeOf(io.d))
  // 不存在，无需考虑
  io.d <> params.micro.innerBuf.d(d)

  d.valid := s3_valid_d
  d.bits.opcode  := Mux(s3_req.prio(0), resp_opcode(s3_req.opcode), ReleaseAck)
  d.bits.param   := Mux(s3_req.prio(0) && s3_acq, Mux(s3_req.param =/= NtoB, toT, toB), 0.U)
  d.bits.size    := s3_req.size
  d.bits.source  := s3_req.source
  d.bits.sink    := s3_req.sink
  d.bits.denied  := s3_req.bad
  d.bits.data    := s3_rdata
  d.bits.corrupt := s3_req.bad && d.bits.opcode(0)
  d.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
    u.counter := Mux(s3_req.cryptoLine, s3_counter_for_resp, 0.U)
    u.cryptoLine := s3_req.cryptoLine
  }
  queue.io.deq.ready := s3_valid && s4_ready && s3_need_r

  // counter committed read 的 dequeue 点和 data 一样，放在 s3 真正推进到 s4 的时候。
  ctrQueue.io.deq.ready := s3_valid && s4_ready && s3_counter_needs_committed

  assert (!s3_full || !s3_need_r || queue.io.deq.valid)

  when (d.ready) { s3_valid_d := false.B }
  when (s3_valid && s4_ready) { s3_full := false.B }
  when (s3_latch) { s3_valid_d := s2_need_d }
  when (s3_latch) { s3_full := true.B }

  params.ccover(s3_valid && !s4_ready, "SOURCED_3_STALL", "Stage 3 pipeline blocked")
  assertOnlyWatchdog(
    s3_req.cryptoLine && s3_valid && !s4_ready,
    6144,
    "SourceD stage 3 blocked too long")

  s3_valid := s3_full && (!s3_valid_d || d.ready)
  s3_ready := !s3_full || (s4_ready && (!s3_valid_d || d.ready))

  ////////////////////////////////////// STAGE 4 //////////////////////////////////////
  // Writeback updated data

  val s4_latch = s3_valid && s3_retires && s4_ready
  val s4_full = RegInit(false.B)
  val s4_ctr_done = RegInit(false.B)
  val s4_beat = RegEnable(s3_beat, s4_latch)
  val s4_need_r = RegEnable(s3_need_r, s4_latch)
  val s4_need_bs = RegEnable(s3_need_bs, s4_latch)
  val s4_need_pb = RegEnable(s3_need_pb, s4_latch)
  val s4_req = RegEnable(s3_req, s4_latch)
  val s4_adjusted_opcode = RegEnable(s3_adjusted_opcode, s4_latch)
  val s4_pdata = RegEnable(s3_pdata, s4_latch)
  val s4_rdata = RegEnable(s3_rdata, s4_latch)
  // counter 在 s4 按 data 的风格拆成 payload 侧与 read-side 侧两条寄存信号，
  // 再由 s4_need_pb 决定本拍最终采用哪一路。
  val s4_counter_payload_value = RegEnable(s3_counter_payload_value, s4_latch)
  val s4_counter_payload_path = s4_need_pb && s4_req.cryptoLine
  val s4_counter_pipe_available = RegEnable(s3_counter_pipe_available, s4_latch)
  val s4_counter_pipe_value = RegEnable(s3_counter_pipe_value, s4_latch)
  val s4_counter_committed_value = RegEnable(s3_counter_committed_value, s4_latch)
  val s4_counter_needs_committed = !s4_need_pb && s4_need_r && s4_req.cryptoLine
  val s4_counter_reread_value = Wire(UInt(params.outer.bundle.dataBits.W))
  val s4_counter = Wire(UInt(params.outer.bundle.dataBits.W))
  s4_counter_reread_value :=
    Mux(s4_counter_pipe_available, s4_counter_pipe_value, s4_counter_committed_value)
  s4_counter :=
    Mux(s4_counter_payload_path, s4_counter_payload_value,
    Mux(s4_counter_needs_committed, s4_counter_reread_value, 0.U))
  val s4_counter_valid = s4_full && s4_req.cryptoLine
  val s4LineAddress = params.restoreAddress(params.expandAddress(s4_req.tag, s4_req.set, 0.U))
  val s4LineAddress64 =
    if (params.inner.bundle.addressBits < 64) {
      Cat(0.U((64 - params.inner.bundle.addressBits).W), s4LineAddress)
    } else {
      s4LineAddress
    }
  val s4DebugDataRange =
    s4LineAddress64 >= debugDataWatchStart &&
    s4LineAddress64 < debugDataWatchEnd

  // atomics 的 AMO 语义细节当前可先忽略，只需先把它看成 data 合成器。
  val atomics = Module(new Atomics(params.inner.bundle))
  atomics.io.write     := s4_req.prio(2)
  atomics.io.a.opcode  := s4_adjusted_opcode
  atomics.io.a.param   := s4_req.param
  atomics.io.a.size    := 0.U
  atomics.io.a.source  := 0.U
  atomics.io.a.address := 0.U
  atomics.io.a.mask    := s4_pdata.mask
  atomics.io.a.data    := s4_pdata.data
  atomics.io.a.corrupt := DontCare
  atomics.io.a.user.lift(freechips.rocketchip.rocket.CacheCryptoWritebackMeta).foreach { u =>
    u.counter := 0.U
    u.cryptoLine := false.B
  }
  atomics.io.data_in   := s4_rdata

  io.bs_wadr.valid := s4_full && s4_need_bs
  io.bs_wadr.bits.noop := false.B
  io.bs_wadr.bits.way  := s4_req.way
  io.bs_wadr.bits.set  := s4_req.set
  io.bs_wadr.bits.beat := s4_beat
  io.bs_wadr.bits.mask := Cat(s4_pdata.mask.asBools.grouped(writeBytes).map(_.reduce(_||_)).toList.reverse)
  io.bs_wdat.data := atomics.io.data_out
  assert (!(s4_full && s4_need_pb && s4_pdata.corrupt), "Data poisoning unsupported")
  val s4_releaseCounterWrite = s4_counter_valid && s4_need_bs && !s4_req.prio(0)
  io.counter_write.valid := s4_releaseCounterWrite && !s4_ctr_done
  io.counter_write.bits.set := s4_req.set
  io.counter_write.bits.way := s4_req.way
  io.counter_write.bits.counter := s4_counter
  params.ccover(io.bs_wadr.valid && !io.bs_wadr.ready, "SOURCED_4_WRITEBACK_STALL", "Data writeback stalled")
  assertOnlyWatchdog(
    s4_req.cryptoLine && io.bs_wadr.valid && !io.bs_wadr.ready,
    2048,
    "SourceD data writeback blocked too long")
  assertOnlyWatchdog(
    io.counter_write.valid && !io.counter_write.ready,
    2048,
    "SourceD counter writeback blocked too long")
  params.ccover(s4_req.prio(0) && s4_req.opcode === ArithmeticData && s4_req.param === MIN,  "SOURCED_4_ATOMIC_MIN",  "Evaluated a signed minimum atomic")
  params.ccover(s4_req.prio(0) && s4_req.opcode === ArithmeticData && s4_req.param === MAX,  "SOURCED_4_ATOMIC_MAX",  "Evaluated a signed maximum atomic")
  params.ccover(s4_req.prio(0) && s4_req.opcode === ArithmeticData && s4_req.param === MINU, "SOURCED_4_ATOMIC_MINU", "Evaluated an unsigned minimum atomic")
  params.ccover(s4_req.prio(0) && s4_req.opcode === ArithmeticData && s4_req.param === MAXU, "SOURCED_4_ATOMIC_MAXU", "Evaluated an unsigned minimum atomic")
  params.ccover(s4_req.prio(0) && s4_req.opcode === ArithmeticData && s4_req.param === ADD,  "SOURCED_4_ATOMIC_ADD",  "Evaluated an addition atomic")
  params.ccover(s4_req.prio(0) && s4_req.opcode === LogicalData    && s4_req.param === XOR,  "SOURCED_4_ATOMIC_XOR",  "Evaluated a bitwise XOR atomic")
  params.ccover(s4_req.prio(0) && s4_req.opcode === LogicalData    && s4_req.param === OR,   "SOURCED_4_ATOMIC_OR",   "Evaluated a bitwise OR atomic")
  params.ccover(s4_req.prio(0) && s4_req.opcode === LogicalData    && s4_req.param === AND,  "SOURCED_4_ATOMIC_AND",  "Evaluated a bitwise AND atomic")
  params.ccover(s4_req.prio(0) && s4_req.opcode === LogicalData    && s4_req.param === SWAP, "SOURCED_4_ATOMIC_SWAP", "Evaluated a bitwise SWAP atomic")

  when (io.counter_write.fire) { s4_ctr_done := true.B }
  when (s4_latch) { s4_ctr_done := !(s3_need_bs && s3_req.cryptoLine && !s3_req.prio(0)) }

  when ((io.bs_wadr.ready || !s4_need_bs) && s4_ctr_done) { s4_full := false.B }
  when (s4_latch) { s4_full := true.B }

  s4_ready := !s3_retires || !s4_full || ((io.bs_wadr.ready || !s4_need_bs) && s4_ctr_done)

  ////////////////////////////////////// RETIRED //////////////////////////////////////
  
  // Record for bypass the last three retired writebacks
  // We need 3 slots to collect what was in s2, s3, s4 when the request was in s1
  // ... you can't rely on s4 being full if bubbles got introduced between s1 and s2
  // s5/s6/s7 retired chain 当前只看主链时可先忽略其细节。
  // retire 表示：当前 s4 这笔更新已经整体完成，可以正式进入 retired chain。
  // data 路仍沿用原先 ready 语义；counter 路额外引入 done bit，避免 counter write
  // 在 data 路尚未完成时重复 fire。
  val retire = s4_full && (io.bs_wadr.ready || !s4_need_bs) && s4_ctr_done

  val s5_counter_valid = RegInit(false.B)
  val s5_req  = RegEnable(s4_req,  retire)
  val s5_beat = RegEnable(s4_beat, retire)
  val s5_dat  = RegEnable(atomics.io.data_out, retire)
  val s5_counter_value = RegEnable(s4_counter, retire)

  val s6_counter_valid = RegInit(false.B)
  val s6_req  = RegEnable(s5_req,  retire)
  val s6_beat = RegEnable(s5_beat, retire)
  val s6_dat  = RegEnable(s5_dat,  retire)
  val s6_counter_value = RegEnable(s5_counter_value, retire)

  val s7_counter_valid = RegInit(false.B)
  val s7_counter_value = RegEnable(s6_counter_value, retire)
  val s7_dat  = RegEnable(s6_dat,  retire)

  // 爲什麼沒有 s4_counter_valid ？
  when (retire) {
    s5_counter_valid := s4_counter_valid
    s6_counter_valid := s5_counter_valid
    s7_counter_valid := s6_counter_valid
  }

  ////////////////////////////////////// BYPASSS //////////////////////////////////////

  // Manually retime this circuit to pull a register stage forward
  // 下面这整段 bypass 实现当前只看主链时可先忽略其细节。
  val pre_s3_req  = Mux(s3_latch, s2_req,  s3_req)
  val pre_s4_req  = Mux(s4_latch, s3_req,  s4_req)
  val pre_s5_req  = Mux(retire,   s4_req,  s5_req)
  val pre_s6_req  = Mux(retire,   s5_req,  s6_req)
  val pre_s3_beat = Mux(s3_latch, s2_beat, s3_beat)
  val pre_s4_beat = Mux(s4_latch, s3_beat, s4_beat)
  val pre_s5_beat = Mux(retire,   s4_beat, s5_beat)
  val pre_s6_beat = Mux(retire,   s5_beat, s6_beat)
  val pre_s5_dat  = Mux(retire,   atomics.io.data_out, s5_dat)
  val pre_s6_dat  = Mux(retire,   s5_dat,  s6_dat)
  val pre_s7_dat  = Mux(retire,   s6_dat,  s7_dat)
  val pre_s5_counter = Mux(retire, s4_counter, s5_counter_value)
  val pre_s6_counter = Mux(retire, s5_counter_value, s6_counter_value)
  val pre_s7_counter = Mux(retire, s6_counter_value, s7_counter_value)
  val pre_s5_counter_valid = Mux(retire, s4_counter_valid, s5_counter_valid)
  val pre_s6_counter_valid = Mux(retire, s5_counter_valid, s6_counter_valid)
  val pre_s4_full = s4_latch || (!(io.bs_wadr.ready || !s4_need_bs) && s4_full)

  val pre_s3_4_match  = pre_s4_req.set === pre_s3_req.set && pre_s4_req.way === pre_s3_req.way && pre_s4_beat === pre_s3_beat && pre_s4_full
  val pre_s3_5_match  = pre_s5_req.set === pre_s3_req.set && pre_s5_req.way === pre_s3_req.way && pre_s5_beat === pre_s3_beat
  val pre_s3_6_match  = pre_s6_req.set === pre_s3_req.set && pre_s6_req.way === pre_s3_req.way && pre_s6_beat === pre_s3_beat

  val pre_s3_4_bypass = Mux(pre_s3_4_match, MaskGen(pre_s4_req.offset, pre_s4_req.size, beatBytes, writeBytes), 0.U)
  val pre_s3_5_bypass = Mux(pre_s3_5_match, MaskGen(pre_s5_req.offset, pre_s5_req.size, beatBytes, writeBytes), 0.U)
  val pre_s3_6_bypass = Mux(pre_s3_6_match, MaskGen(pre_s6_req.offset, pre_s6_req.size, beatBytes, writeBytes), 0.U)

  // s3_bypass_data 组织的是“更年轻流水阶段已经持有的 data 值”，而不是当前拍的原始 payload 输入。
  // 因此对 C 路径来说，rel_beat.data 并没有缺席，只是它在更早阶段已经被锁进 s2/s3/s4，
  // 到这里会以 atomics.io.data_out / s5_dat / s6_dat / s7_dat 这些后继形态参与旁路优先级选择。
  s3_bypass_data :=
    bypass(RegNext(pre_s3_4_bypass), atomics.io.data_out, RegNext(
    bypass(pre_s3_5_bypass, pre_s5_dat,
    bypass(pre_s3_6_bypass, pre_s6_dat,
                            pre_s7_dat))))

  val pre_s3_4_ctr_match = pre_s4_full && s4_counter_valid && pre_s3_4_match
  val pre_s3_5_ctr_match = pre_s5_counter_valid && pre_s3_5_match
  val pre_s3_6_ctr_match = pre_s6_counter_valid && pre_s3_6_match
  s3_counter_pipe_available :=
    RegNext(pre_s3_4_ctr_match, false.B) ||
    RegNext(pre_s3_5_ctr_match, false.B) ||
    RegNext(pre_s3_6_ctr_match, false.B)
  s3_counter_pipe_value :=
    Mux(RegNext(pre_s3_4_ctr_match, false.B), s4_counter, RegNext(
    Mux(pre_s3_5_ctr_match, pre_s5_counter,
    Mux(pre_s3_6_ctr_match, pre_s6_counter,
                            pre_s7_counter))))

  // Detect which parts of s1 will be bypassed from later pipeline stages (s1-s4)
  // Note: we also bypass from reads ahead in the pipeline to save power
  val s1_2_match  = s2_req.set === s1_req.set && s2_req.way === s1_req.way && s2_beat === s1_beat && s2_full && s2_retires
  val s1_3_match  = s3_req.set === s1_req.set && s3_req.way === s1_req.way && s3_beat === s1_beat && s3_full && s3_retires
  val s1_4_match  = s4_req.set === s1_req.set && s4_req.way === s1_req.way && s4_beat === s1_beat && s4_full

  for (i <- 0 until 8) {
    val cover = 1.U
    val s2 = s1_2_match === cover(0)
    val s3 = s1_3_match === cover(1)
    val s4 = s1_4_match === cover(2)
    params.ccover(io.req.valid && s2 && s3 && s4, "SOURCED_BYPASS_CASE_" + i, "Bypass data from all subsets of pipeline stages")
  }

  val s1_2_bypass = Mux(s1_2_match, MaskGen(s2_req.offset, s2_req.size, beatBytes, writeBytes), 0.U)
  val s1_3_bypass = Mux(s1_3_match, MaskGen(s3_req.offset, s3_req.size, beatBytes, writeBytes), 0.U)
  val s1_4_bypass = Mux(s1_4_match, MaskGen(s4_req.offset, s4_req.size, beatBytes, writeBytes), 0.U)
  val s1_req_bypass_mask = MaskGen(s1_req.offset, s1_req.size, beatBytes, writeBytes)

  // s1_x_bypass 汇总来自 s2/s3/s4 的逐字节 bypass 掩码：
  // 哪些字节已经能从更年轻的 pipeline stage 直接拿到，就在这里标成 1，
  // 后面 s1_mask 会把这些字节从 SRAM 读请求里剔掉，避免无意义地再读一次旧 data。
  // 也就是说，data 路径在 s1 先做的是“哪些字节以后需要旁路”的早期判定，
  // 真正的数据值要到 s3 再和 queue 读回值做 byte-mask merge。
  s1_x_bypass := s1_2_bypass | s1_3_bypass | s1_4_bypass
 
                       
  ////////////////////////////////////// HAZARDS //////////////////////////////////////

  // SinkC, SourceC, and SinkD can never interfer with each other because their operation
  // is fully contained with an execution plan of an MSHR. That MSHR owns the entire set, so
  // there is no way for a data race.

  // However, SourceD is special. We allow it to run ahead after the MSHR and scheduler have
  // released control of a set+way. This is necessary to allow single cycle occupancy for
  // hits. Thus, we need to be careful about data hazards between SourceD and the other ports
  // of the BankedStore. We can at least compare to registers 's1_req_reg', because the first
  // cycle of SourceD falls within the occupancy of the MSHR's plan.

  // Must ReleaseData=> be interlocked? RaW hazard
  // 防 SourceC 太早去读 old victim data
  io.evict_safe :=
    (!busy    || io.evict_req.way =/= s1_req_reg.way || io.evict_req.set =/= s1_req_reg.set) &&
    (!s2_full || io.evict_req.way =/= s2_req.way     || io.evict_req.set =/= s2_req.set) &&
    (!s3_full || io.evict_req.way =/= s3_req.way     || io.evict_req.set =/= s3_req.set) &&
    (!s4_full || io.evict_req.way =/= s4_req.way     || io.evict_req.set =/= s4_req.set)

  // counter_grant_safe 是 counter 版 grant_safe：
  // 只要同 set/way 的最新 counter 还留在 SourceD chain 里，就不允许新 refill
  // counter 抢先写入 cc_counters。
  val counter_chain_grant_safe =
    (!busy    || !s1_req_reg.cryptoLine || io.counter_grant_req.way =/= s1_req_reg.way || io.counter_grant_req.set =/= s1_req_reg.set) &&
    (!s2_full || !s2_req.cryptoLine     || io.counter_grant_req.way =/= s2_req.way     || io.counter_grant_req.set =/= s2_req.set) &&
    (!s3_full || !s3_req.cryptoLine     || io.counter_grant_req.way =/= s3_req.way     || io.counter_grant_req.set =/= s3_req.set) &&
    (!s4_full || !s4_req.cryptoLine     || io.counter_grant_req.way =/= s4_req.way     || io.counter_grant_req.set =/= s4_req.set)
  io.counter_grant_safe := counter_chain_grant_safe

  // 这些 coverage 用来显式标记：
  // SourceD 自己的 younger counter state 阻止 overwrite。
  params.ccover(!counter_chain_grant_safe && !io.counter_grant_safe, "SOURCED_COUNTER_GRANT_BLOCK_PIPE", "Incoming counter grant blocked by younger counter state in SourceD pipeline")

  // Must =>GrantData be interlocked? WaR hazard
  // 防 SinkD 太早去写 new refill data
  io.grant_safe :=
    (!busy    || io.grant_req.way =/= s1_req_reg.way || io.grant_req.set =/= s1_req_reg.set) &&
    (!s2_full || io.grant_req.way =/= s2_req.way     || io.grant_req.set =/= s2_req.set) &&
    (!s3_full || io.grant_req.way =/= s3_req.way     || io.grant_req.set =/= s3_req.set) &&
    (!s4_full || io.grant_req.way =/= s4_req.way     || io.grant_req.set =/= s4_req.set)

  // SourceD cannot overlap with SinkC b/c the only way inner caches could become
  // dirty such that they want to put data in via SinkC is if we Granted them permissions,
  // which must flow through the SourecD pipeline.
}
