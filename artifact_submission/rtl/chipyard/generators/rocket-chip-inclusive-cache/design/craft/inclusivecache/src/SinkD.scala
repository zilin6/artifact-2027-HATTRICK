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
import freechips.rocketchip.util.PlusArg
import TLMessages._

class SinkDResponse(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val last   = Bool()
  val opcode = UInt(3.W)
  val param  = UInt(3.W)
  val source = UInt(params.outer.bundle.sourceBits.W)
  val sink   = UInt(params.outer.bundle.sinkBits.W)
  val denied = Bool()
  val isCounter = Bool()
}

class SinkD(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val resp = Valid(new SinkDResponse(params)) // Grant / GrantData / AccessAck* / ReleaseAck
    val d = Flipped(Decoupled(new TLBundleD(params.outer.bundle)))
    // Lookup the set+way from MSHRs
    val source = UInt(params.outer.bundle.sourceBits.W)
    val way    = Flipped(UInt(params.wayBits.W))
    val set    = Flipped(UInt(params.setBits.W))
    // Banked Store port
    val bs_adr = Decoupled(new BankedStoreOuterAddress(params))
    val bs_dat = new BankedStoreOuterPoison(params)
    val counter_write = Decoupled(new CounterSidecarWrite(params))
    // WaR hazard
    val grant_req = new SourceDHazard(params)
    val grant_safe = Flipped(Bool())
    val counter_grant_req = new SourceDHazard(params)
    val counter_grant_safe = Flipped(Bool())
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

  // No restrictions on buffer
  val d = params.micro.outerBuf.d(io.d)
  val debugLogEnable = PlusArg("inclusive_cache_debug_log", default = 0, width = 1) =/= 0.U
  val debugDataWatchStart = "h0000000080002000".U(64.W)
  val debugDataWatchEnd = "h0000000080006300".U(64.W)

  val (first, last, _, beat) = params.outer.count(d)
  val hasData = params.outer.hasData(d.bits)
  val refillMeta = d.bits.user.lift(CacheCryptoRefillMeta)
  val dCryptoLine = WireDefault(false.B)
  refillMeta.foreach { m => dCryptoLine := m.cryptoLine }
  when (debugLogEnable && d.valid) {
    printf(p"[SINKD-TRACE] source=0x${Hexadecimal(d.bits.source)} opcode=0x${Hexadecimal(d.bits.opcode)} first=${first} last=${last} beat=${beat} isCounter=${isCounterResp} crypto=${dCryptoLine} ready=${d.ready} fire=${d.fire} sink=0x${Hexadecimal(d.bits.sink)} denied=${d.bits.denied}\n")
  }

  // outer 返回响应沿用 Scheduler 的 source 编码：
  //   [mshr_select | source_type]
  // rawSource 是稳定版 outer source 视图：D.valid 时直接取当前拍 source，
  // 否则保持最近一次有效 source，避免后续在 d.valid 拉低时失去这笔返回的归属信息。
  // 然后按统一编码格式 [mshr_select | source_type] 解码：
  //   1. 低位 source_type 判断这笔返回是 data 还是 counter
  //   2. 高位 mshr_select 恢复这笔返回属于哪个 MSHR
  val rawSource = Mux(d.valid, d.bits.source, RegEnable(d.bits.source, d.valid))
  val isCounterResp = OuterRequestSourceType.isCounter(rawSource)
  val isCounterAckData = isCounterResp && d.bits.opcode === AccessAckData
  val isCounterAck = isCounterResp && d.bits.opcode === AccessAck
  val counterAckDataCanCommit = isCounterAckData && io.counter_grant_safe
  io.source := OuterRequestSourceType.mshr(rawSource)
  io.grant_req.way := io.way
  io.grant_req.set := io.set
  // counter grant 也复用相同的 set/way hazard 描述，交给 SourceD 判断
  // 这笔 committed sidecar write 此刻是否安全。
  io.counter_grant_req.way := io.way
  io.counter_grant_req.set := io.set

  // Also send Grant(NoData) to BS to ensure correct data ordering
  io.resp.valid := (first || last) && d.fire
  // data grant 受 grant_safe 保护，counter grant 受 counter_grant_safe 保护；
  // 两者都要求 first beat 在 committed store 侧安全后才允许真正进入写路径。
  // 但 counterPut 的返回是 AccessAck，不会写 sidecar，因此必须与 counterGet 的
  // AccessAckData 区分开处理，避免无意义地被 counter_write.ready / counter_grant_safe 卡住。
  d.ready := Mux(isCounterAckData,
    io.counter_write.ready && io.counter_grant_safe,
    Mux(isCounterAck,
      true.B,
      io.bs_adr.ready && (!first || io.grant_safe)))
  io.bs_adr.valid := !isCounterResp && (!first || (d.valid && io.grant_safe))
  params.ccover(d.valid && first && !isCounterResp && !io.grant_safe, "SINKD_HAZARD", "Prevented Grant data hazard with backpressure")
  params.ccover(d.valid && first && isCounterResp && !io.counter_grant_safe, "SINKD_COUNTER_HAZARD", "Prevented counter grant hazard with backpressure")
  params.ccover(io.bs_adr.valid && !io.bs_adr.ready, "SINKD_SRAM_STALL", "Data SRAM busy")
  assertOnlyWatchdog(
    d.valid && dCryptoLine && first && !isCounterResp && !io.grant_safe,
    2048,
    "SinkD Grant blocked by SourceD data hazard too long")
  assertOnlyWatchdog(
    d.valid && first && isCounterResp && !io.counter_grant_safe,
    2048,
    "SinkD counter Grant blocked by SourceD counter hazard too long")
  assertOnlyWatchdog(
    dCryptoLine && io.bs_adr.valid && !io.bs_adr.ready,
    2048,
    "SinkD data bank write blocked too long")

  io.resp.bits.last   := last
  io.resp.bits.opcode := d.bits.opcode
  io.resp.bits.param  := d.bits.param
  io.resp.bits.source := OuterRequestSourceType.mshr(d.bits.source)
  io.resp.bits.sink   := d.bits.sink
  io.resp.bits.denied := d.bits.denied
  io.resp.bits.isCounter := isCounterResp

  io.counter_write.valid := d.valid && counterAckDataCanCommit
  io.counter_write.bits.set := io.set
  io.counter_write.bits.way := io.way
  io.counter_write.bits.counter := d.bits.data
  assertOnlyWatchdog(
    d.valid && isCounterAckData && io.counter_grant_safe && !io.counter_write.ready,
    2048,
    "SinkD counter write blocked too long")

  when (d.valid && isCounterAckData) {
    assert(!l2CryptoAssertEnable || first && last,
      "SinkD counter AccessAckData should be single beat")
  }
  assert(!l2CryptoAssertEnable || !io.counter_write.fire || d.fire,
    "SinkD committed counter write without consuming outer D beat")
  when (io.counter_write.fire) {
    assert(!l2CryptoAssertEnable || io.counter_grant_safe,
      "SinkD committed counter grant while SourceD counter hazard said unsafe")
  }
  when (isCounterAckData && d.valid && !d.ready) {
    assert(!l2CryptoAssertEnable || !io.counter_write.fire,
      "SinkD counter write fired while D beat was backpressured")
  }

  io.bs_adr.bits.noop := !d.valid || !hasData
  io.bs_adr.bits.way  := io.way
  io.bs_adr.bits.set  := io.set
  io.bs_adr.bits.beat := Mux(d.valid, beat, RegEnable(beat + io.bs_adr.ready.asUInt, d.valid))
  io.bs_adr.bits.mask := ~0.U(params.outerMaskBits.W)
  io.bs_dat.data      := d.bits.data
  val bsAddr = params.expandAddress(0.U, io.set, 0.U)
  val bsAddr64 =
    if (params.outer.bundle.addressBits < 64) {
      Cat(0.U((64 - params.outer.bundle.addressBits).W), bsAddr)
    } else {
      bsAddr
    }
  val debugDataRangeResp =
    bsAddr64 >= debugDataWatchStart &&
    bsAddr64 < debugDataWatchEnd

  assert (!(d.valid && d.bits.corrupt && !d.bits.denied), "Data poisoning unsupported")
}
