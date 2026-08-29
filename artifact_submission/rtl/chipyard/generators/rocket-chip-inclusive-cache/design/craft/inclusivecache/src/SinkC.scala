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
import freechips.rocketchip.rocket.CacheCryptoWritebackMeta
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

class SinkCResponse(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val last   = Bool()
  val set    = UInt(params.setBits.W)
  val tag    = UInt(params.tagBits.W)
  val source = UInt(params.inner.bundle.sourceBits.W)
  val param  = UInt(3.W)
  val data   = Bool()
  val cryptoLine = Bool()
  val counter = UInt(params.outer.bundle.dataBits.W)
}

class PutBufferCEntry(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val data = UInt(params.inner.bundle.dataBits.W)
  val corrupt = Bool()
  // C 路 payload 同时携带 line-level counter；同一 transaction 的各个 beat 共享同一份值。
  val counter = UInt(params.outer.bundle.dataBits.W)
  val counterValid = Bool()
}

class SinkC(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    // 承载的是一笔完整 cache 事务的元信息，不是 data beat 本身
    val req = Decoupled(new FullRequest(params)) // Release
    val resp = Valid(new SinkCResponse(params)) // ProbeAck
    val c = Flipped(Decoupled(new TLBundleC(params.inner.bundle)))
    // Find 'way' via MSHR CAM lookup
    val set = UInt(params.setBits.W)
    val way = Flipped(UInt(params.wayBits.W))
    // ProbeAck write-back
    val bs_adr = Decoupled(new BankedStoreInnerAddress(params))
    val bs_dat = new BankedStoreInnerPoison(params)
    val counter_write = Decoupled(new CounterSidecarWrite(params))
    // SourceD sideband
    val rel_pop  = Flipped(Decoupled(new PutBufferPop(params)))
    val rel_beat = new PutBufferCEntry(params)
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

  if (params.firstLevel) {
    // Tie off unused ports
    io.req.valid := false.B
    io.req.bits := DontCare
    io.resp.valid := false.B
    io.resp.bits := DontCare
    io.c.ready := true.B
    io.set := 0.U
    io.bs_adr.valid := false.B
    io.bs_adr.bits := DontCare
    io.bs_dat := DontCare
    io.counter_write.valid := false.B
    io.counter_write.bits := DontCare
    io.rel_pop.ready := true.B
    io.rel_beat := DontCare
  } else {
    // No restrictions on the type of buffer
    val c = params.micro.innerBuf.c(io.c)
    val debugLogEnable = PlusArg("inclusive_cache_debug_log", default = 0, width = 1) =/= 0.U
    val debugWatchTagValue = BigInt("1000", 16)
    val debugWatchSetValue = BigInt("05c", 16)
    def truncToWidth(x: BigInt, width: Int): UInt = (x & ((BigInt(1) << width) - 1)).U(width.W)
    val debugWatchTag = truncToWidth(debugWatchTagValue, params.tagBits)
    val debugWatchSet = truncToWidth(debugWatchSetValue, params.setBits)
    val debugDataWatchStart = "h0000000080002000".U(64.W)
    val debugDataWatchEnd = "h0000000080006300".U(64.W)

    val (tag, set, offset) = params.parseAddress(c.bits.address)
    val cAddr64 =
      if (params.inner.bundle.addressBits < 64) {
        Cat(0.U((64 - params.inner.bundle.addressBits).W), c.bits.address)
      } else {
        c.bits.address
      }
    val (first, last, _, beat) = params.inner.count(c)
    val hasData = params.inner.hasData(c.bits)
    val debugDataRangeReq =
      cAddr64 >= debugDataWatchStart &&
      cAddr64 < debugDataWatchEnd
    val raw_resp = c.bits.opcode === TLMessages.ProbeAck || c.bits.opcode === TLMessages.ProbeAckData
    val traceReleaseSet0 = !raw_resp && set === 0.U
    val resp = Mux(c.valid, raw_resp, RegEnable(raw_resp, c.valid))

    // 尝试从 C 通道 user 域里解出 cache crypto writeback 附带元数据；
    // 若这条消息没有携带该扩展，就回退到“非 crypto line / counter=0”的默认语义。
    val writebackMeta = c.bits.user.lift(CacheCryptoWritebackMeta)
    val cCounter = WireDefault(0.U(params.outer.bundle.dataBits.W))
    val cCryptoLine = WireDefault(false.B)
    writebackMeta.foreach { m =>
      cCounter := m.counter
      cCryptoLine := m.cryptoLine
    }
    // Handling of C is broken into two cases:
    //   ProbeAck
    //     if hasData, must be written to BankedStore
    //     if last beat, trigger resp
    //   Release
    //     if first beat, trigger req
    //     if hasData, go to putBuffer
    //     if hasData && first beat, must claim a list

    assert (!(c.valid && c.bits.corrupt), "Data poisoning unavailable")

    io.set := Mux(c.valid, set, RegEnable(set, c.valid)) // finds us the way

    // Cut path from inner C to the BankedStore SRAM setup
    //   ... this makes it easier to layout the L2 data banks far away
    val bs_adr = Wire(chiselTypeOf(io.bs_adr))
    io.bs_adr <> Queue(bs_adr, 1, pipe=true)
    // counter committed-write 路径也插入一层同风格的 1-entry pipe queue：
    // 作用和 bs_adr 侧一致，主要用于切 SinkC 到 sidecar write 口的长组合路径，
    // 而不是恢复旧的语义级 staging queue。
    val counter_write = Wire(chiselTypeOf(io.counter_write))
    io.counter_write <> Queue(counter_write, 1, pipe=true)
    io.bs_dat.data   := RegEnable(c.bits.data,    bs_adr.fire)
    val rawCounterWriteBeat = raw_resp && c.valid && hasData && first && cCryptoLine
    val rawDataReady = !hasData || bs_adr.ready
    val rawCounterReady = !rawCounterWriteBeat || counter_write.ready
    bs_adr.valid     := resp && (!first || (c.valid && hasData)) && rawCounterReady
    bs_adr.bits.noop := !c.valid
    bs_adr.bits.way  := io.way
    bs_adr.bits.set  := io.set
    bs_adr.bits.beat := Mux(c.valid, beat, RegEnable(beat + bs_adr.ready.asUInt, c.valid))
    bs_adr.bits.mask := ~0.U(params.innerMaskBits.W)
    params.ccover(bs_adr.valid && !bs_adr.ready, "SINKC_SRAM_STALL", "Data SRAM busy")

    io.resp.valid := resp && c.valid && (first || last) && rawDataReady && rawCounterReady
    io.resp.bits.last   := last
    io.resp.bits.set    := set
    io.resp.bits.tag    := tag
    io.resp.bits.source := c.bits.source
    io.resp.bits.param  := c.bits.param
    io.resp.bits.data   := hasData
    io.resp.bits.cryptoLine := cCryptoLine
    io.resp.bits.counter := cCounter

    val putbuffer = Module(new ListBuffer(ListBufferParameters(new PutBufferCEntry(params), params.relLists, params.relBeats, false)))
    val lists = RegInit(0.U(params.relLists.W))

    val lists_set = WireInit(init = 0.U(params.relLists.W))
    val lists_clr = WireInit(init = 0.U(params.relLists.W))
    lists := (lists | lists_set) & ~lists_clr

    val free = !lists.andR
    val freeOH = ~(leftOR(~lists) << 1) & ~lists
    val freeIdx = OHToUInt(freeOH)

    //  req_block / buf_block / set_block 是 Release/ReleaseData 分支内部 的阻塞原因

    // Release 首拍的 request 入口阻塞
    // io.req 是把一整笔 Release/ReleaseData 事务登记给 Scheduler/MSHR 的入口。
    // 首拍必须先成功提交这笔 request，后端才能为这条 line 建立事务上下文、
    // 分配/匹配 MSHR，并确定后续 data/counter payload 共享的 transaction/index。
    // 因此只有首拍会因为 io.req.ready 不满足而被挡住。
    val req_block = first && !io.req.ready
    // ReleaseData 的 putbuffer 入口阻塞。
    // 这不是首拍专属条件：只要当前 beat 带 data，而 putbuffer 这一拍不能接收，
    // 这拍 payload 就没有落点，必须直接回压 C。
    val buf_block = hasData && !putbuffer.io.push.ready
    // ReleaseData 首拍的 transaction/list-index 分配阻塞。
    // 只有首拍才需要为整笔事务申请一个新的 put/list slot；如果当前没有空闲 slot，
    // 这笔 ReleaseData 还无法建立自己的 transaction/index，因此首拍必须被挡住。
    // 这里的 lists 是 slot 占用位图，用来管理 putbuffer 承载的 transaction
    // 索引生命周期；它不是 putbuffer 本体。
    val set_block = hasData && first && !free
    // ProbeAckData 的 counter 直接写 committed sidecar；和 data 一样，写口没准备好
    // 时直接回压这拍 C，而不是额外经过本地 staging queue。
    // raw_resp : 这条 C 是 ProbeAck/ProbeAckData 路径，不是 Release/ReleaseData。
    // 只有 ProbeAckData 的 counter 现在走“直接写 committed sidecar”；
    // ReleaseData 的 counter 跟随 payload beat 一起进入 putbuffer。
    // c.valid
      // - 含义：当前这拍 C 通道上真的有一条有效消息
    params.ccover(c.valid && !raw_resp && req_block, "SINKC_REQ_STALL", "No MSHR available to sink request")
    params.ccover(c.valid && !raw_resp && buf_block, "SINKC_BUF_STALL", "No space in putbuffer for beat")
    params.ccover(c.valid && !raw_resp && set_block, "SINKC_SET_STALL", "No space in putbuffer for request")
    assertOnlyWatchdog(
      c.valid && cCryptoLine && !raw_resp && req_block,
      2048,
      "SinkC Release request blocked too long")
    assertOnlyWatchdog(
      c.valid && cCryptoLine && !raw_resp && buf_block,
      2048,
      "SinkC ReleaseData putbuffer push blocked too long")
    assertOnlyWatchdog(
      c.valid && cCryptoLine && !raw_resp && set_block,
      2048,
      "SinkC ReleaseData list allocation blocked too long")

    // ProbeAck/ProbeAckData 只受 data bank 与 counter committed write 口约束。
    // Release/ReleaseData 继续受请求入口、putbuffer 和 list/index 分配约束；
    // counter 已经并入 putbuffer entry，因此不再有单独的 counter ready/block；
    // 首拍是否拿到 transaction/index 已经由 set_block 覆盖。
    c.ready := Mux(raw_resp,
      rawDataReady && rawCounterReady,
      !req_block && !buf_block && !set_block)
    // Keep a complete trace for the incoming L1 ReleaseData transaction.  The
    // previous source filter only covered small local IDs, while the L1 source
    // in this configuration is 0x22.  This is diagnostic-only.
    when (debugLogEnable && c.valid && !raw_resp &&
          (c.bits.source === "h22".U || cCryptoLine || set === 0.U)) {
      printf(p"[SINKC-REL-TRACE] source=0x${Hexadecimal(c.bits.source)} opcode=0x${Hexadecimal(c.bits.opcode)} addr=0x${Hexadecimal(c.bits.address)} set=0x${Hexadecimal(set)} tag=0x${Hexadecimal(tag)} first=${first} last=${last} beat=${beat} hasData=${hasData} crypto=${cCryptoLine} counter=0x${Hexadecimal(cCounter)} c_ready=${c.ready} c_fire=${c.fire} req_v=${io.req.valid} req_r=${io.req.ready} req_fire=${io.req.fire} push_v=${putbuffer.io.push.valid} push_r=${putbuffer.io.push.ready} push_fire=${putbuffer.io.push.fire} free=${free} set_block=${set_block} buf_block=${buf_block} resp=${resp}\n")
    }

    // 只有 ProbeAckData 的首拍才在 SinkC 这里直接把 counter 送进 committed
    // sidecar；ReleaseData 的 latest counter 跟随每个 payload beat 一起进入 putbuffer。
    counter_write.valid := rawCounterWriteBeat && rawDataReady
    counter_write.bits.set := io.set
    counter_write.bits.way := io.way
    counter_write.bits.counter := cCounter
    assertOnlyWatchdog(
      raw_resp && c.valid && cCryptoLine && hasData && !bs_adr.ready,
      2048,
      "SinkC ProbeAckData data write blocked too long")
    assertOnlyWatchdog(
      rawCounterWriteBeat && rawDataReady && !counter_write.ready,
      2048,
      "SinkC ProbeAckData counter write blocked too long")

    assert(!l2CryptoAssertEnable || !counter_write.fire || c.fire,
      "SinkC committed ProbeAckData counter without consuming C beat")
    when (raw_resp && c.valid && hasData && first && cCryptoLine && !c.ready) {
      assert(!l2CryptoAssertEnable || !counter_write.fire,
        "SinkC counter side effect fired while C beat was backpressured")
      assert(!l2CryptoAssertEnable || !bs_adr.fire,
        "SinkC data side effect fired while C beat was backpressured")
    }

    // Release/ReleaseData 的 transaction/index 生命周期不再被 ProbeAck counter
    // committed write 口牵连。
    io.req.valid := !resp && c.valid && first && !buf_block && !set_block
    putbuffer.io.push.valid := !resp && c.valid && hasData && !req_block && !set_block
    when (!resp && c.valid && first && hasData && !req_block && !buf_block) { lists_set := freeOH }

    val put = Mux(first, freeIdx, RegEnable(freeIdx, first))

    // 这里填的是这笔 Release/ReleaseData 事务的控制信息，而不是某一拍 data beat 本身。
    // 后续 MSHR/Scheduler 会基于这些字段决定该事务归属哪个 set/tag、使用哪个 put/index，
    // 以及后面的 data/counter payload 应如何继续推进。
    io.req.bits.prio   := VecInit(4.U(3.W).asBools)
    io.req.bits.control:= false.B
    io.req.bits.opcode := c.bits.opcode
    io.req.bits.param  := c.bits.param
    io.req.bits.size   := c.bits.size
    io.req.bits.source := c.bits.source
    io.req.bits.offset := offset
    io.req.bits.set    := set
    io.req.bits.tag    := tag
    io.req.bits.put    := put
    io.req.bits.cryptoLine := cCryptoLine
    when (debugLogEnable && io.req.fire && cCryptoLine) {
      printf(p"[SINKC-REQ-FIRE] source=0x${Hexadecimal(c.bits.source)} opcode=0x${Hexadecimal(c.bits.opcode)} set=0x${Hexadecimal(set)} tag=0x${Hexadecimal(tag)} put=0x${Hexadecimal(put)}\n")
    }

    when (!resp && c.fire && first && hasData && cCryptoLine) {
      chisel3.printf("[SINKC-CTR-SET] source=%d set=0x%x put=0x%x relIdx=0x%x first=%d last=%d opcode=0x%x size=%d counter=0x%x\n",
        c.bits.source,
        set,
        put,
        put(log2Ceil(params.relLists)-1, 0),
        first,
        last,
        c.bits.opcode,
        c.bits.size,
        cCounter)
    }
    putbuffer.io.push.bits.index := put
    putbuffer.io.push.bits.data.data    := c.bits.data
    putbuffer.io.push.bits.data.corrupt := c.bits.corrupt
    putbuffer.io.push.bits.data.counter := cCounter
    putbuffer.io.push.bits.data.counterValid := cCryptoLine

    // Grant access to pop the data
    putbuffer.io.pop.bits := io.rel_pop.bits.index
    putbuffer.io.pop.valid := io.rel_pop.fire
    io.rel_pop.ready := putbuffer.io.valid(io.rel_pop.bits.index(log2Ceil(params.relLists)-1,0))
    assertOnlyWatchdog(
      io.rel_pop.valid && putbuffer.io.data.counterValid && !io.rel_pop.ready,
      2048,
      "SinkC ReleaseData pop blocked too long")

    val relIdx = io.rel_pop.bits.index(log2Ceil(params.relLists)-1, 0)
    val relPopLast = io.rel_pop.fire && io.rel_pop.bits.last
    io.rel_beat := putbuffer.io.data

    when (!resp && c.fire && first && hasData && cCryptoLine && relPopLast) {
      chisel3.printf("[SINKC-CTR-SET-WITH-POP] source=%d set=0x%x put=0x%x putRelIdx=0x%x rel_pop_idx=0x%x relIdx=0x%x sameIdx=%d ctr_valid_set=%d ctr_valid_rel=%d counter_set=0x%x counter_rel=0x%x\n",
        c.bits.source,
        set,
        put,
        put(log2Ceil(params.relLists)-1, 0),
        io.rel_pop.bits.index,
        relIdx,
        relPopLast && relIdx === put(log2Ceil(params.relLists)-1, 0),
        cCryptoLine,
        putbuffer.io.data.counterValid,
        cCounter,
        putbuffer.io.data.counter)
    }

    when (io.rel_pop.fire && io.rel_pop.bits.last) {
      chisel3.printf("[SINKC-CTR-CLR] pop_idx=0x%x relIdx=0x%x last=%d ctr_valid_before=%d counter=0x%x\n",
        io.rel_pop.bits.index,
        relIdx,
        io.rel_pop.bits.last,
        putbuffer.io.data.counterValid,
        putbuffer.io.data.counter)
      lists_clr := UIntToOH(io.rel_pop.bits.index, params.relLists)
    }
  }
}
