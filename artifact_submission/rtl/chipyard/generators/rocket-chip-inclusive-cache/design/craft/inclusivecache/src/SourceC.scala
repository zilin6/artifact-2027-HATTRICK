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

class SourceCRequest(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val opcode = UInt(3.W)
  val param  = UInt(3.W)
  val source = UInt(params.outer.bundle.sourceBits.W)
  val tag    = UInt(params.tagBits.W)
  val set    = UInt(params.setBits.W)
  val way    = UInt(params.wayBits.W)
  val dirty  = Bool()
  val cryptoLine = Bool()
}

class SourceC(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new SourceCRequest(params)))
    val c = Decoupled(new TLBundleC(params.outer.bundle))
    // BankedStore port
    val bs_adr = Decoupled(new BankedStoreOuterAddress(params))
    val bs_dat = Flipped(new BankedStoreOuterDecoded(params))
    val ctr_radr = Decoupled(new CounterSidecarAddress(params))
    val ctr_rdat = Input(UInt(params.outer.bundle.dataBits.W))
    val ctr_snapshot_idx = Input(UInt(log2Ceil(params.mshrs).W))
    val ctr_snapshot_data = Output(UInt(params.outer.bundle.dataBits.W))
    val ctr_snapshot_valid = Output(Bool())
    val ctr_snapshot_pop = Input(Bool())
    // RaW hazard
    val evict_req = new SourceDHazard(params)
    val evict_safe = Flipped(Bool())
  })

  // We ignore the depth and pipe is useless here (we have to provision for worst-case=stall)
  require (!params.micro.outerBuf.c.pipe)

  val beatBytes = params.outer.manager.beatBytes
  val beats = params.cache.blockBytes / beatBytes
  val flow = params.micro.outerBuf.c.flow
  val queue = Module(new Queue(chiselTypeOf(io.c.bits), beats + 3 + (if (flow) 0 else 1), flow = flow))
  val debugLogEnable = PlusArg("inclusive_cache_debug_log", default = 0, width = 1) =/= 0.U
  private val debugWatchTagValue = BigInt("1000", 16)
  private val debugWatchSetValue = BigInt("05c", 16)
  private def truncToWidth(x: BigInt, width: Int): UInt = (x & ((BigInt(1) << width) - 1)).U(width.W)
  val debugWatchTag = truncToWidth(debugWatchTagValue, params.tagBits)
  val debugWatchSet = truncToWidth(debugWatchSetValue, params.setBits)
  val debugDataWatchStart = "h0000000080002000".U(64.W)
  val debugDataWatchEnd = "h0000000080006300".U(64.W)

  // queue.io.count is far too slow
  val fillBits = log2Up(beats + 4)
  val fill = RegInit(0.U(fillBits.W))
  val room = RegInit(true.B)
  when (queue.io.enq.fire =/= queue.io.deq.fire) {
    fill := fill + Mux(queue.io.enq.fire, 1.U, ~0.U(fillBits.W))
    room := fill === 0.U || ((fill === 1.U || fill === 2.U) && !queue.io.enq.fire)
  }
  assert (room === queue.io.count <= 1.U)

  val busy = RegInit(false.B)
  val beat = RegInit(0.U(params.outerBeatBits.W))
  val snapshotIssued = RegInit(false.B)
  val last = if (params.cache.blockBytes == params.outer.manager.beatBytes) true.B else (beat === ~(0.U(params.outerBeatBits.W)))
  val req  = Mux(!busy, io.req.bits, RegEnable(io.req.bits, !busy && io.req.valid))
  val want_data = busy || (io.req.valid && room && io.req.bits.dirty)
  // old victim counter snapshot 需要按 MSHR 号分槽保存：
  // SourceC 是所有 MSHR 共享的生产者，后续 SourceA 会按 mshrIdx 取走对应那一格；
  // 因此这里必须是 Vec，而不是单个 Reg，避免不同 MSHR 的 snapshot 互相覆盖。
  // 同时按当前控制流，一个 MSHR 任意时刻最多只应挂着一份 old victim snapshot，
  // 所以每个 MSHR 只需要一格即可，不需要为单个 MSHR 再额外扩成多槽。
  val ctrSnapBuf = Reg(Vec(params.mshrs, UInt(params.outer.bundle.dataBits.W)))
  val ctrSnapBufValid = RegInit(VecInit(Seq.fill(params.mshrs)(false.B)))
  val ctrSnapIdxBits = log2Ceil(params.mshrs)
  val reqMshrIdx = OuterRequestSourceType.mshr(req.source)(ctrSnapIdxBits - 1, 0)
  val need_ctr_snapshot = req.dirty && req.cryptoLine

  io.req.ready := !busy && room

  io.evict_req.set := req.set
  io.evict_req.way := req.way

  io.ctr_radr.valid := !beat.orR && io.evict_safe && want_data && need_ctr_snapshot && !snapshotIssued
  io.ctr_radr.bits.set := req.set
  io.ctr_radr.bits.way := req.way
  // data bank 访问在首拍和后续拍的放行条件不同：
  //   1. 后续 beat（beat.orR=1）只要 want_data，就可以继续顺着 data 路径推进
  //   2. 首拍（beat.orR=0）除了要求 evict_safe 之外，还要保证：
  //      - 若这条 old victim 不需要 counter snapshot，可以直接开始读 data
  //      - 若需要 counter snapshot，则必须先等 ctr_radr.ready=1，确认这拍 committed
  //        counter read 请求已经被真正接受，再允许 data 首拍进入 bank
  // 这样可以保证 crypto victim 的 eviction 起点上，counter snapshot 与 data read
  // 的先后关系稳定：先把 snapshot 请求发出去，再开始 data bank 路径。
  io.bs_adr.valid := (beat.orR || (io.evict_safe && (!need_ctr_snapshot || snapshotIssued || io.ctr_radr.fire))) && want_data
  io.bs_adr.bits.noop := false.B
  io.bs_adr.bits.way  := req.way
  io.bs_adr.bits.set  := req.set
  io.bs_adr.bits.beat := beat
  io.bs_adr.bits.mask := ~0.U(params.outerMaskBits.W)

  params.ccover(io.req.valid && io.req.bits.dirty && room && !io.evict_safe, "SOURCEC_HAZARD", "Prevented Eviction data hazard with backpressure")
  params.ccover(io.bs_adr.valid && !io.bs_adr.ready, "SOURCEC_SRAM_STALL", "Data SRAM busy")

  when (io.req.valid && room && io.req.bits.dirty) {
    busy := true.B
    snapshotIssued := false.B
  }
  when (io.ctr_radr.fire) {
    snapshotIssued := true.B
  }
  when (io.bs_adr.fire) {
    beat := beat + 1.U
    when (last) {
      busy := false.B
      beat := 0.U
      snapshotIssued := false.B
    }
  }

  // Counter snapshot path follows the same two-stage return convention as the
  // data path: fire in stage 0, SRAM return alignment in stage 1, consume in
  // stage 2. This keeps ctr_snapshot sampling aligned with BankedStore's
  // counterReadAData timing.
  val ctrReadS1Valid = RegNext(io.ctr_radr.fire, false.B)
  val ctrReadS1Idx = RegEnable(reqMshrIdx, io.ctr_radr.fire)
  val ctrReadS2Valid = RegNext(ctrReadS1Valid, false.B)
  val ctrReadS2Idx = RegEnable(ctrReadS1Idx, ctrReadS1Valid)
  when (ctrReadS2Valid) {
    when (debugLogEnable) {
      printf(p"[SOURCEC-CTR-S2] idx=0x${Hexadecimal(ctrReadS2Idx)} valid_before=${ctrSnapBufValid(ctrReadS2Idx)} data=0x${Hexadecimal(io.ctr_rdat)} snapshot_idx=0x${Hexadecimal(io.ctr_snapshot_idx)} snapshot_valid=${io.ctr_snapshot_valid}\n")
    }
    when (debugLogEnable && ctrSnapBufValid(ctrReadS2Idx)) {
      printf(p"[SOURCEC-CTR-OVERWRITE] idx=0x${Hexadecimal(ctrReadS2Idx)} old=0x${Hexadecimal(ctrSnapBuf(ctrReadS2Idx))} new=0x${Hexadecimal(io.ctr_rdat)} snapshot_idx=0x${Hexadecimal(io.ctr_snapshot_idx)} pop=${io.ctr_snapshot_pop}\n")
    }
    // 同一个 MSHR 的 old victim snapshot 在被 SourceA pop 掉之前，不应再次写入新值；
    // 如果这里命中有效槽位，说明控制流已经允许“旧 snapshot 未消费，新 snapshot 又覆盖”。
    assert(!ctrSnapBufValid(ctrReadS2Idx), "SourceC overwrote a live counter snapshot for the same MSHR")
    ctrSnapBuf(ctrReadS2Idx) := io.ctr_rdat
    ctrSnapBufValid(ctrReadS2Idx) := true.B
  }
  when (io.ctr_snapshot_pop) {
    when (debugLogEnable) {
      printf(p"[SOURCEC-CTR-POP] idx=0x${Hexadecimal(io.ctr_snapshot_idx)} valid_before=${ctrSnapBufValid(io.ctr_snapshot_idx)} data=0x${Hexadecimal(ctrSnapBuf(io.ctr_snapshot_idx))}\n")
    }
    ctrSnapBufValid(io.ctr_snapshot_idx) := false.B
  }
  val ctrSnapBypassHit =
    ctrReadS2Valid && (io.ctr_snapshot_idx === ctrReadS2Idx)
  val ctrSnapVisibleData =
    Mux(ctrSnapBypassHit, io.ctr_rdat, ctrSnapBuf(io.ctr_snapshot_idx))
  val ctrSnapVisibleValid =
    Mux(ctrSnapBypassHit, true.B, ctrSnapBufValid(io.ctr_snapshot_idx))
  io.ctr_snapshot_data := ctrSnapVisibleData
  io.ctr_snapshot_valid := ctrSnapVisibleValid

  val s2_latch = Mux(want_data, io.bs_adr.fire, io.req.fire)
  val s2_valid = RegNext(s2_latch)
  val s2_req = RegEnable(req, s2_latch)
  val s2_beat = RegEnable(beat, s2_latch)
  val s2_last = RegEnable(last, s2_latch)

  val s3_latch = s2_valid
  val s3_valid = RegNext(s3_latch)
  val s3_req = RegEnable(s2_req, s3_latch)
  val s3_beat = RegEnable(s2_beat, s3_latch)
  val s3_last = RegEnable(s2_last, s3_latch)

  val c = Wire(chiselTypeOf(io.c))
  c.valid        := s3_valid
  c.bits.opcode  := s3_req.opcode
  c.bits.param   := s3_req.param
  c.bits.size    := params.offsetBits.U
  c.bits.source  := s3_req.source
  c.bits.address := params.expandAddress(s3_req.tag, s3_req.set, 0.U)
  c.bits.data    := io.bs_dat.data
  c.bits.corrupt := false.B
  val cAddr64 =
    if (params.outer.bundle.addressBits < 64) {
      Cat(0.U((64 - params.outer.bundle.addressBits).W), c.bits.address)
    } else {
      c.bits.address
    }
  val debugDataRangeReq =
    cAddr64 >= debugDataWatchStart &&
    cAddr64 < debugDataWatchEnd

  when (debugLogEnable && io.ctr_radr.fire) {
    printf(p"[SOURCEC-CTR-REQ] source=0x${Hexadecimal(req.source)} mshr=0x${Hexadecimal(reqMshrIdx)} set=0x${Hexadecimal(req.set)} way=0x${Hexadecimal(req.way)} dirty=${req.dirty} crypto=${req.cryptoLine} beat=0x${Hexadecimal(beat)} busy=${busy}\n")
  }

  // We never accept at the front-end unless we're sure things will fit
  assert(!c.valid || c.ready)
  params.ccover(!c.ready, "SOURCEC_QUEUE_FULL", "Eviction queue fully utilized")

  queue.io.enq <> c
  io.c <> queue.io.deq

}
