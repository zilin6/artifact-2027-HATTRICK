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
import chisel3.experimental.SourceInfo
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.PlusArg
import TLPermissions._
import TLMessages._
import MetaData._
import chisel3.PrintableHelper
import chisel3.experimental.dataview._

class ScheduleRequest(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val a = Valid(new SourceARequest(params))
  val b = Valid(new SourceBRequest(params))
  val c = Valid(new SourceCRequest(params))
  val d = Valid(new SourceDRequest(params))
  val e = Valid(new SourceERequest(params))
  val x = Valid(new SourceXRequest(params))
  val dir = Valid(new DirectoryWrite(params))
  val reload = Bool() // get next request via allocate (if any)
}

class MSHRStatus(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(params.setBits.W)
  val tag = UInt(params.tagBits.W)
  val way = UInt(params.wayBits.W)
  // 记录这笔 MSHR 在 reload/allocate 交界时基线 line 是否是 crypto line。
  val reload_base_crypto_line = Bool()
  val blockB = Bool()
  val nestB  = Bool()
  val blockC = Bool()
  val nestC  = Bool()
}

class NestedWriteback(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(params.setBits.W)
  val tag = UInt(params.tagBits.W)
  val b_toN       = Bool() // nested Probes may unhit us
  val b_toB       = Bool() // nested Probes may demote us
  val b_clr_dirty = Bool() // nested Probes clear dirty
  val c_set_dirty = Bool() // nested Releases MAY set dirty
}

sealed trait CacheState
{
  val code = CacheState.index.U
  CacheState.index = CacheState.index + 1
}

object CacheState
{
  var index = 0
}

case object S_INVALID  extends CacheState
case object S_BRANCH   extends CacheState
case object S_BRANCH_C extends CacheState
case object S_TIP      extends CacheState
case object S_TIP_C    extends CacheState
case object S_TIP_CD   extends CacheState
case object S_TIP_D    extends CacheState
case object S_TRUNK_C  extends CacheState
case object S_TRUNK_CD extends CacheState

class MSHR(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val allocate  = Flipped(Valid(new AllocateRequest(params))) // refills MSHR for next cycle
    val directory = Flipped(Valid(new DirectoryResult(params))) // triggers schedule setup
    val status    = Valid(new MSHRStatus(params))
    val schedule  = Decoupled(new ScheduleRequest(params))
    val sinkc     = Flipped(Valid(new SinkCResponse(params)))
    val sinkd     = Flipped(Valid(new SinkDResponse(params)))
    val sinke     = Flipped(Valid(new SinkEResponse(params)))
    val nestedwb  = Flipped(new NestedWriteback(params))
    val l2_crypto_assert_enable = Input(Bool())
  })
  private val l2CryptoAssertEnable = io.l2_crypto_assert_enable
  val debugLogEnable = PlusArg("inclusive_cache_debug_log", default = 0, width = 1) =/= 0.U
  private val debugWatchTagValue = BigInt("1000", 16)
  private val debugWatchSetValue = BigInt("05c", 16)
  private def truncToWidth(x: BigInt, width: Int): UInt = (x & ((BigInt(1) << width) - 1)).U(width.W)
  val debugWatchTag = truncToWidth(debugWatchTagValue, params.tagBits)
  val debugWatchSet = truncToWidth(debugWatchSetValue, params.setBits)
  val debugDataWatchStart = "h0000000080002000".U(64.W)
  val debugDataWatchEnd = "h0000000080006300".U(64.W)

  // victimMeta：当前这笔 MSHR 正在处理的 resident/victim line 视图
  // - refillMeta：这笔事务最终准备提交的新 line 视图
  // - meta：victimMeta 的别名，为了少改旧代码
  // - new_meta：建下一轮执行计划时使用的“临时输入视图”

  val request_valid = RegInit(false.B)
  val request = Reg(new FullRequest(params))
  val meta_valid = RegInit(false.B)
  // victimMeta 表示当前 resident/victim line 的目录元数据视图。
  val victimMeta = RegInit(0.U.asTypeOf(new DirectoryResult(params)))
  // refillMeta 表示本次事务结束后将被 refill/提交的新 line 元数据视图。
  val refillMeta = RegInit(0.U.asTypeOf(new DirectoryResult(params)))
  // 保留原有 meta 写法，当前统一指向 victim 侧视图。
  val meta = victimMeta

  // Define which states are valid
  when (meta_valid) {
    when (victimMeta.state === INVALID) {
      assert (!victimMeta.clients.orR)
      assert (!victimMeta.dirty)
    }
    when (victimMeta.state === BRANCH) {
      assert (!victimMeta.dirty)
    }
    when (victimMeta.state === TRUNK) {
      assert (victimMeta.clients.orR)
      assert ((victimMeta.clients & (victimMeta.clients - 1.U)) === 0.U) // at most one
    }
    when (victimMeta.state === TIP) {
      // noop
    }
  }

  // Completed transitions (s_ = scheduled), (w_ = waiting)
  val s_rprobe         = RegInit(true.B) // B
  val w_rprobeackfirst = RegInit(true.B)
  val w_rprobeacklast  = RegInit(true.B)
  val s_release        = RegInit(true.B) // CW w_rprobeackfirst
  val w_releaseack     = RegInit(true.B)
  val s_pprobe         = RegInit(true.B) // B
  val s_acquire        = RegInit(true.B) // A  s_release, s_pprobe [1]
  val s_flush          = RegInit(true.B) // X  w_releaseack
  val w_grantfirst     = RegInit(true.B)
  val w_grantlast      = RegInit(true.B)
  val w_grant          = RegInit(true.B) // first | last depending on wormhole
  val w_pprobeackfirst = RegInit(true.B)
  val w_pprobeacklast  = RegInit(true.B)
  val w_pprobeack      = RegInit(true.B) // first | last depending on wormhole
  val s_probeack       = RegInit(true.B) // C  w_pprobeackfirst (mutually exclusive with next two s_*)
  val s_grantack       = RegInit(true.B) // E  w_grantfirst ... CAN require both outE&inD to service outD
  val s_execute        = RegInit(true.B) // D  w_pprobeack, w_grant
  val w_grantack       = RegInit(true.B)
  val s_writeback      = RegInit(true.B) // W  w_*
  // counter get 是否已经被调度发出；为 false 表示还欠一笔 counter fetch。
  val s_counter_acquire = RegInit(true.B)
  // 是否已经等到 counter get 的 AccessAckData 返回。
  val w_countergrant    = RegInit(true.B)
  // counter put 是否已经被调度发出；为 false 表示还欠一笔 old victim counter writeback。
  val s_counter_put     = RegInit(true.B)
  // 是否已经等到 counter put 的 AccessAck 返回。
  val w_counterput_ack  = RegInit(true.B)

  // [1]: We cannot issue outer Acquire while holding blockB (=> outA can stall)
  // However, inB and outC are higher priority than outB, so s_release and s_pprobe
  // may be safely issued while blockB. Thus we must NOT try to schedule the
  // potentially stuck s_acquire with either of them (scheduler is all or none).

  // Meta-data that we discover underway
  val sink = Reg(UInt(params.outer.bundle.sinkBits.W))
  val gotT = Reg(Bool())
  val bad_grant = Reg(Bool())
  val probes_done = Reg(UInt(params.clientBits.W))
  val probes_toN = Reg(UInt(params.clientBits.W))
  val probes_noT = Reg(Bool())

  // When a nested transaction completes, update our meta data
  when (meta_valid && victimMeta.state =/= INVALID &&
        io.nestedwb.set === request.set && io.nestedwb.tag === victimMeta.tag) {
    when (io.nestedwb.b_clr_dirty) { victimMeta.dirty := false.B }
    when (io.nestedwb.c_set_dirty) { victimMeta.dirty := true.B }
    when (io.nestedwb.b_toB) { victimMeta.state := BRANCH }
    when (io.nestedwb.b_toN) { victimMeta.hit := false.B }
  }

  // Scheduler status
  io.status.valid := request_valid
  io.status.bits.set    := request.set
  io.status.bits.tag    := request.tag
  io.status.bits.way    := meta.way
  io.status.bits.reload_base_crypto_line := victimMeta.cryptoLine
  io.status.bits.blockB := !meta_valid || ((!w_releaseack || !w_rprobeacklast || !w_pprobeacklast) && !w_grantfirst)
  io.status.bits.nestB  := meta_valid && w_releaseack && w_rprobeacklast && w_pprobeacklast && !w_grantfirst
  // The above rules ensure we will block and not nest an outer probe while still doing our
  // own inner probes. Thus every probe wakes exactly one MSHR.
  io.status.bits.blockC := !meta_valid
  io.status.bits.nestC  := meta_valid && (!w_rprobeackfirst || !w_pprobeackfirst || !w_grantfirst)
  // The w_grantfirst in nestC is necessary to deal with:
  //   acquire waiting for grant, inner release gets queued, outer probe -> inner probe -> deadlock
  // ... this is possible because the release+probe can be for same set, but different tag

  // We can only demand: block, nest, or queue
  assert (!io.status.bits.nestB || !io.status.bits.blockB)
  assert (!io.status.bits.nestC || !io.status.bits.blockC)

  // Scheduler requests
  // 这笔请求是否需要在 data 之外再走一笔 counter fetch。
  val request_needs_counter = request.cryptoLine
  // 只有需要 counter 的请求，才要求 counter get 已发出且返回。
  val counter_fetch_done =
    Mux(request_needs_counter, s_counter_acquire && w_countergrant, true.B)
  // 当前 victim 是否需要额外做 old victim counter writeback。
  val need_counter_put =
    meta_valid && victimMeta.dirty && victimMeta.cryptoLine && victimMeta.counterValid
  // 只有需要 counter put 的事务，才要求该 put 已发出且收到 ack。
  val counter_put_done =
    Mux(need_counter_put, s_counter_put && w_counterput_ack, true.B)
  val no_wait = w_rprobeacklast && w_releaseack && w_grantlast && w_pprobeacklast && w_grantack &&
    counter_fetch_done && counter_put_done

  // 普通 data A 请求，指的是“新请求那条 line”的 AcquireBlock/AcquirePerm。
  // 如果当前事务还欠一笔 old victim counter put，那么这拍 A 通道应优先拿去发
  // old victim 的 counter writeback，而不是提前去发新 line 的 data acquire。
  val issue_data_a =
    !s_acquire && s_release && s_pprobe && Mux(need_counter_put, s_counter_put, true.B)
  // counter get A 请求；只有 data acquire 已经发出后才允许补发。
  val issue_counter_get_a =
    !s_counter_acquire && s_acquire && s_release && s_pprobe && request_needs_counter
  // counter put A 请求；表示把 old victim counter 往外存。
  val issue_counter_put_a =
    !s_counter_put && s_release && s_pprobe && need_counter_put
  // old victim 的 C 通道 release / releaseData 是否在这一拍真正发出。
  // 恢复旧语义：当 ReleaseData 真的发出时，再根据此刻已经被 SinkC 修正过的
  // victimMeta/need_counter_put 去决定是否 arm counterPut。
  val issue_release_c = !s_release && w_rprobeackfirst
  val issue_release_data_c = issue_release_c && meta_valid && victimMeta.dirty
  // 表示这拍 MSHR 想占用 A 通道
  io.schedule.bits.a.valid := issue_data_a || issue_counter_get_a || issue_counter_put_a
  io.schedule.bits.b.valid := !s_rprobe || !s_pprobe
  io.schedule.bits.c.valid := (!s_release && w_rprobeackfirst) || (!s_probeack && w_pprobeackfirst)
  io.schedule.bits.d.valid := !s_execute && w_pprobeack && w_grant && counter_fetch_done
  io.schedule.bits.e.valid := !s_grantack && w_grantfirst
  io.schedule.bits.x.valid := !s_flush && w_releaseack
  io.schedule.bits.dir.valid := (!s_release && w_rprobeackfirst) || (!s_writeback && no_wait)
  io.schedule.bits.reload := no_wait
  io.schedule.valid := io.schedule.bits.a.valid || io.schedule.bits.b.valid || io.schedule.bits.c.valid ||
                       io.schedule.bits.d.valid || io.schedule.bits.e.valid || io.schedule.bits.x.valid ||
                       io.schedule.bits.dir.valid
  when (debugLogEnable && request_valid && request.source === "h22".U) {
    printf(p"[MSHR-REL-TRACE] source=0x${Hexadecimal(request.source)} set=0x${Hexadecimal(request.set)} tag=0x${Hexadecimal(request.tag)} crypto=${request.cryptoLine} meta_valid=${meta_valid} meta_hit=${meta.hit} meta_dirty=${meta.dirty} meta_crypto=${meta.cryptoLine} s_rel=${s_release} s_acq=${s_acquire} s_probeack=${s_probeack} s_exec=${s_execute} s_wb=${s_writeback} w_relack=${w_releaseack} w_rpfirst=${w_rprobeackfirst} w_ppfirst=${w_pprobeackfirst} w_grant=${w_grant} counter_get=${s_counter_acquire} counter_grant=${w_countergrant} counter_put=${s_counter_put} counter_ack=${w_counterput_ack} need_ctr_put=${need_counter_put} issue_a=${io.schedule.bits.a.valid} issue_c=${io.schedule.bits.c.valid} issue_d=${io.schedule.bits.d.valid} issue_dir=${io.schedule.bits.dir.valid} sched_v=${io.schedule.valid} sched_r=${io.schedule.ready} sched_fire=${io.schedule.fire} reload=${io.schedule.bits.reload}\n")
  }
  when (debugLogEnable && io.allocate.valid && io.allocate.bits.source === "h22".U) {
    printf(p"[MSHR-REL-ALLOC] source=0x${Hexadecimal(io.allocate.bits.source)} set=0x${Hexadecimal(io.allocate.bits.set)} tag=0x${Hexadecimal(io.allocate.bits.tag)} crypto=${io.allocate.bits.cryptoLine} repeat=${io.allocate.bits.repeat}\n")
  }
  when (debugLogEnable && request_valid && (request.set === 0.U || request.source === "h22".U)) {
    printf(p"[MSHR-WATCH-TRACE] source=0x${Hexadecimal(request.source)} set=0x${Hexadecimal(request.set)} tag=0x${Hexadecimal(request.tag)} prio=0x${Hexadecimal(request.prio.asUInt)} valid=${request_valid} meta_valid=${meta_valid} s_rel=${s_release} s_acq=${s_acquire} s_rprobe=${s_rprobe} s_pprobe=${s_pprobe} s_probeack=${s_probeack} s_exec=${s_execute} s_wb=${s_writeback} s_grantack=${s_grantack} s_flush=${s_flush} w_relack=${w_releaseack} w_rpfirst=${w_rprobeackfirst} w_ppfirst=${w_pprobeackfirst} w_grantfirst=${w_grantfirst} w_grant=${w_grant} w_grantack=${w_grantack} counter_get=${s_counter_acquire} counter_grant=${w_countergrant} counter_put=${s_counter_put} counter_ack=${w_counterput_ack}\n")
  }
  when (debugLogEnable && request_valid && request.set === "h040".U && request.tag === "h1000".U &&
        (io.schedule.valid || io.schedule.fire || io.sinkd.valid || io.sinke.valid)) {
    printf(p"[MSHR-BOUNDARY-WATCH] source=0x${Hexadecimal(request.source)} set=0x${Hexadecimal(request.set)} tag=0x${Hexadecimal(request.tag)} valid=${request_valid} meta_valid=${meta_valid} sched_v=${io.schedule.valid} sched_r=${io.schedule.ready} sched_fire=${io.schedule.fire} d_v=${io.schedule.bits.d.valid} d_opcode=0x${Hexadecimal(io.schedule.bits.d.bits.opcode)} d_source=0x${Hexadecimal(io.schedule.bits.d.bits.source)} d_sink=0x${Hexadecimal(io.schedule.bits.d.bits.sink)} e_v=${io.schedule.bits.e.valid} e_sink=0x${Hexadecimal(io.schedule.bits.e.bits.sink)} sinkd_v=${io.sinkd.valid} sinkd_opcode=0x${Hexadecimal(io.sinkd.bits.opcode)} sinkd_source=0x${Hexadecimal(io.sinkd.bits.source)} sinkd_sink=0x${Hexadecimal(io.sinkd.bits.sink)} sinke_v=${io.sinke.valid} sinke_sink=0x${Hexadecimal(io.sinke.bits.sink)} s_acq=${s_acquire} w_grant=${w_grant} s_grantack=${s_grantack} w_grantack=${w_grantack} w_grantfirst=${w_grantfirst} w_grantlast=${w_grantlast}\n")
  }
  // Schedule completions
  when (io.schedule.ready) {
    when (issue_release_data_c && need_counter_put) {
      s_counter_put := false.B
    }
                                    s_rprobe     := true.B
    when (w_rprobeackfirst)       { s_release    := true.B }
                                    s_pprobe     := true.B
    // s_acquire 只表示“新 line 的 data acquire 是否已经发出”。
    // 因为现在 A 通道同时承载三类请求：
    //   1. 新 line 的 data acquire
    //   2. 新 line 的 counter get
    //   3. old victim 的 counter put
    // 所以这里必须用 issue_data_a 来判定“这拍真正发出去的是不是 data acquire”。
    // 不能只看 s_release && s_pprobe；否则当这拍实际发出去的是 counter get/counter put
    // 时，会把 s_acquire 误标成已完成。
    when (issue_data_a)           { s_acquire    := true.B }
    when (w_releaseack)           { s_flush      := true.B }
    when (w_pprobeackfirst)       { s_probeack   := true.B }
    when (w_grantfirst)           { s_grantack   := true.B }
    when (issue_data_a && request_needs_counter) {
      s_counter_acquire := false.B
    }
    when (issue_counter_get_a) {
      s_counter_acquire := true.B
      w_countergrant := false.B
    }
    when (issue_counter_put_a) {
      s_counter_put := true.B
      w_counterput_ack := false.B
    }
    when (w_pprobeack && w_grant && counter_fetch_done) { s_execute    := true.B }
    when (no_wait)                { s_writeback  := true.B }
    // Await the next operation
    when (no_wait) {
      request_valid := false.B
      meta_valid := false.B
    }
  }

  // Resulting meta-data
  val final_meta_writeback = WireInit(meta)

  val req_clientBit = params.clientBit(request.source)
  val req_needT = needT(request.opcode, request.param)
  val req_acquire = request.opcode === AcquireBlock || request.opcode === AcquirePerm
  val meta_no_clients = !meta.clients.orR
  val req_promoteT = req_acquire && Mux(meta.hit, meta_no_clients && meta.state === TIP, gotT)
  // 同 tag 但 crypto/non-crypto 模式不同，表示这次命中了“同地址不同模式”的切换窗口。
  val same_tag_mode_mismatch =
    (request.tag === meta.tag) &&
    (request.cryptoLine =/= meta.cryptoLine)

  when (request.prio(2) && (!params.firstLevel).B) { // always a hit
    final_meta_writeback.dirty   := meta.dirty || request.opcode(0)
    final_meta_writeback.state   := Mux(request.param =/= TtoT && meta.state === TRUNK, TIP, meta.state)
    final_meta_writeback.clients := meta.clients & ~Mux(isToN(request.param), req_clientBit, 0.U)
    final_meta_writeback.hit     := true.B // chained requests are hits
    when (request.opcode(0)) {
      final_meta_writeback.cryptoLine := request.cryptoLine
      final_meta_writeback.counterValid := request.cryptoLine
    }
  } .elsewhen (request.control && params.control.B) { // request.prio(0)
    when (meta.hit) {
      final_meta_writeback.dirty   := false.B
      final_meta_writeback.state   := INVALID
      final_meta_writeback.clients := meta.clients & ~probes_toN
      final_meta_writeback.cryptoLine := false.B
      final_meta_writeback.counterValid := false.B
    }
    final_meta_writeback.hit := false.B
  } .otherwise {
    final_meta_writeback.dirty := (meta.hit && meta.dirty) || !request.opcode(2)
    final_meta_writeback.state := Mux(req_needT,
                                    Mux(req_acquire, TRUNK, TIP),
                                    Mux(!meta.hit, Mux(gotT, Mux(req_acquire, TRUNK, TIP), BRANCH),
                                      MuxLookup(meta.state, 0.U(2.W))(Seq(
                                        INVALID -> BRANCH,
                                        BRANCH  -> BRANCH,
                                        TRUNK   -> TIP,
                                        TIP     -> Mux(meta_no_clients && req_acquire, TRUNK, TIP)))))
    final_meta_writeback.clients := Mux(meta.hit, meta.clients & ~probes_toN, 0.U) |
                                    Mux(req_acquire, req_clientBit, 0.U)
    final_meta_writeback.tag := request.tag
    final_meta_writeback.hit := true.B
    final_meta_writeback.cryptoLine := Mux(meta.hit, meta.cryptoLine, request.cryptoLine)
    final_meta_writeback.counterValid := Mux(meta.hit, meta.counterValid, request.cryptoLine)
  }

  when (bad_grant) {
    when (meta.hit) {
      // upgrade failed (B -> T)
      assert (!meta_valid || meta.state === BRANCH)
      final_meta_writeback.hit     := true.B
      final_meta_writeback.dirty   := false.B
      final_meta_writeback.state   := BRANCH
      final_meta_writeback.clients := meta.clients & ~probes_toN
    } .otherwise {
      // failed N -> (T or B)
      final_meta_writeback.hit     := false.B
      final_meta_writeback.dirty   := false.B
      final_meta_writeback.state   := INVALID
      final_meta_writeback.clients := 0.U
      final_meta_writeback.cryptoLine := false.B
      final_meta_writeback.counterValid := false.B
    }
  }

  val invalid = Wire(new DirectoryEntry(params))
  invalid.dirty   := false.B
  invalid.state   := INVALID
  invalid.clients := 0.U
  invalid.tag     := 0.U
  invalid.cryptoLine := false.B
  invalid.counterValid := false.B

  // Just because a client says BtoT, by the time we process the request he may be N.
  // Therefore, we must consult our own meta-data state to confirm he owns the line still.
  val honour_BtoT = meta.hit && (meta.clients & req_clientBit).orR

  // The client asking us to act is proof they don't have permissions.
  val excluded_client = Mux(meta.hit && request.prio(0) && skipProbeN(request.opcode, params.cache.hintsSkipProbe), req_clientBit, 0.U)
  io.schedule.bits.a.bits.tag     := Mux(issue_counter_put_a, meta.tag, request.tag)
  io.schedule.bits.a.bits.set     := request.set
  io.schedule.bits.a.bits.param   := Mux(req_needT, Mux(meta.hit, BtoT, NtoT), NtoB)
  // block 只给普通 data A 请求用，用来区分发 AcquireBlock 还是 AcquirePerm。
  // counter get/put 虽然也会把这个字段填上，但实际 opcode 走的是专门的 Get/Put 路径。
  io.schedule.bits.a.bits.block   := Mux(issue_counter_get_a || issue_counter_put_a, true.B,
                                     request.size =/= log2Ceil(params.cache.blockBytes).U ||
                                     !(request.opcode === PutFullData || request.opcode === AcquirePerm))
  io.schedule.bits.a.bits.way     := meta.way
  io.schedule.bits.a.bits.put     := request.put
  io.schedule.bits.a.bits.isCounter := issue_counter_get_a || issue_counter_put_a
  io.schedule.bits.a.bits.isCounterWrite := issue_counter_put_a
  io.schedule.bits.a.bits.source  := 0.U
  io.schedule.bits.b.bits.param   := Mux(!s_rprobe, toN, Mux(request.prio(1), request.param, Mux(req_needT, toN, toB)))
  io.schedule.bits.b.bits.tag     := Mux(!s_rprobe, meta.tag, request.tag)
  io.schedule.bits.b.bits.set     := request.set
  io.schedule.bits.b.bits.clients := meta.clients & ~excluded_client
  io.schedule.bits.c.bits.opcode  := Mux(meta.dirty, ReleaseData, Release)
  io.schedule.bits.c.bits.param   := Mux(meta.state === BRANCH, BtoN, TtoN)
  io.schedule.bits.c.bits.source  := 0.U
  io.schedule.bits.c.bits.tag     := meta.tag
  io.schedule.bits.c.bits.set     := request.set
  io.schedule.bits.c.bits.way     := meta.way
  io.schedule.bits.c.bits.dirty   := meta.dirty
  io.schedule.bits.c.bits.cryptoLine := meta.cryptoLine
  io.schedule.bits.d.bits.viewAsSupertype(chiselTypeOf(request)) := request
  io.schedule.bits.d.bits.param   := Mux(!req_acquire, request.param,
                                       MuxLookup(request.param, request.param)(Seq(
                                         NtoB -> Mux(req_promoteT, NtoT, NtoB),
                                         BtoT -> Mux(honour_BtoT,  BtoT, NtoT),
                                         NtoT -> NtoT)))
  io.schedule.bits.d.bits.sink    := 0.U
  io.schedule.bits.d.bits.way     := refillMeta.way
  io.schedule.bits.d.bits.bad     := bad_grant
  io.schedule.bits.d.bits.cryptoLine := refillMeta.cryptoLine
  when (io.schedule.bits.d.valid && request.cryptoLine) {
    assert(!l2CryptoAssertEnable || refillMeta.counterValid,
      "MSHR scheduled crypto refill without valid counter owner")
  }
  io.schedule.bits.e.bits.sink    := sink
  io.schedule.bits.x.bits.fail    := false.B
  io.schedule.bits.dir.bits.set   := request.set
  io.schedule.bits.dir.bits.way   := refillMeta.way
  io.schedule.bits.dir.bits.data  := Mux(!s_release, invalid, WireInit(new DirectoryEntry(params), init = final_meta_writeback))

  // Coverage of state transitions
  def cacheState(entry: DirectoryEntry, hit: Bool) = {
    val out = WireDefault(0.U)
    val c = entry.clients.orR
    val d = entry.dirty
    switch (entry.state) {
      is (BRANCH)  { out := Mux(c, S_BRANCH_C.code, S_BRANCH.code) }
      is (TRUNK)   { out := Mux(d, S_TRUNK_CD.code, S_TRUNK_C.code) }
      is (TIP)     { out := Mux(c, Mux(d, S_TIP_CD.code, S_TIP_C.code), Mux(d, S_TIP_D.code, S_TIP.code)) }
      is (INVALID) { out := S_INVALID.code }
    }
    when (!hit) { out := S_INVALID.code }
    out
  }

  val p = !params.lastLevel  // can be probed
  val c = !params.firstLevel // can be acquired
  val m = params.inner.client.clients.exists(!_.supports.probe)   // can be written (or read)
  val r = params.outer.manager.managers.exists(!_.alwaysGrantsT) // read-only devices exist
  val f = params.control     // flush control register exists
  val cfg = (p, c, m, r, f)
  val b = r || p // can reach branch state (via probe downgrade or read-only device)

  // The cache must be used for something or we would not be here
  require(c || m)

  val evict = cacheState(meta, !meta.hit)
  val before = cacheState(meta, meta.hit)
  val after  = cacheState(final_meta_writeback, true.B)

  def eviction(from: CacheState, cover: Boolean)(implicit sourceInfo: SourceInfo) {
    if (cover) {
      params.ccover(evict === from.code, s"MSHR_${from}_EVICT", s"State transition from ${from} to evicted ${cfg}")
    } else {
      assert(!(evict === from.code), cf"State transition from ${from} to evicted should be impossible ${cfg}")
    }
    if (cover && f) {
      params.ccover(before === from.code, s"MSHR_${from}_FLUSH", s"State transition from ${from} to flushed ${cfg}")
    } else {
      assert(!(before === from.code), cf"State transition from ${from} to flushed should be impossible ${cfg}")
    }
  }

  def transition(from: CacheState, to: CacheState, cover: Boolean)(implicit sourceInfo: SourceInfo) {
    if (cover) {
      params.ccover(before === from.code && after === to.code, s"MSHR_${from}_${to}", s"State transition from ${from} to ${to} ${cfg}")
    } else {
      assert(!(before === from.code && after === to.code), cf"State transition from ${from} to ${to} should be impossible ${cfg}")
    }
  }

  when ((!s_release && w_rprobeackfirst) && io.schedule.ready) {
    eviction(S_BRANCH,    b)      // MMIO read to read-only device
    eviction(S_BRANCH_C,  b && c) // you need children to become C
    eviction(S_TIP,       true)   // MMIO read || clean release can lead to this state
    eviction(S_TIP_C,     c)      // needs two clients || client + mmio || downgrading client
    eviction(S_TIP_CD,    c)      // needs two clients || client + mmio || downgrading client
    eviction(S_TIP_D,     true)   // MMIO write || dirty release lead here
    eviction(S_TRUNK_C,   c)      // acquire for write
    eviction(S_TRUNK_CD,  c)      // dirty release then reacquire
  }

  when ((!s_writeback && no_wait) && io.schedule.ready) {
    transition(S_INVALID,  S_BRANCH,   b && m) // only MMIO can bring us to BRANCH state
    transition(S_INVALID,  S_BRANCH_C, b && c) // C state is only possible if there are inner caches
    transition(S_INVALID,  S_TIP,      m)      // MMIO read
    transition(S_INVALID,  S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_INVALID,  S_TIP_CD,   false)  // acquire does not cause dirty immediately
    transition(S_INVALID,  S_TIP_D,    m)      // MMIO write
    transition(S_INVALID,  S_TRUNK_C,  c)      // acquire
    transition(S_INVALID,  S_TRUNK_CD, false)  // acquire does not cause dirty immediately

    transition(S_BRANCH,   S_INVALID,  b && p) // probe can do this (flushes run as evictions)
    transition(S_BRANCH,   S_BRANCH_C, b && c) // acquire
    transition(S_BRANCH,   S_TIP,      b && m) // prefetch write
    transition(S_BRANCH,   S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_BRANCH,   S_TIP_CD,   false)  // acquire does not cause dirty immediately
    transition(S_BRANCH,   S_TIP_D,    b && m) // MMIO write
    transition(S_BRANCH,   S_TRUNK_C,  b && c) // acquire
    transition(S_BRANCH,   S_TRUNK_CD, false)  // acquire does not cause dirty immediately

    transition(S_BRANCH_C, S_INVALID,  b && c && p)
    transition(S_BRANCH_C, S_BRANCH,   b && c)      // clean release (optional)
    transition(S_BRANCH_C, S_TIP,      b && c && m) // prefetch write
    transition(S_BRANCH_C, S_TIP_C,    false)       // we would go S_TRUNK_C instead
    transition(S_BRANCH_C, S_TIP_D,    b && c && m) // MMIO write
    transition(S_BRANCH_C, S_TIP_CD,   false)       // going dirty means we must shoot down clients
    transition(S_BRANCH_C, S_TRUNK_C,  b && c)      // acquire
    transition(S_BRANCH_C, S_TRUNK_CD, false)       // acquire does not cause dirty immediately

    transition(S_TIP,      S_INVALID,  p)
    transition(S_TIP,      S_BRANCH,   p)      // losing TIP only possible via probe
    transition(S_TIP,      S_BRANCH_C, false)  // we would go S_TRUNK_C instead
    transition(S_TIP,      S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_TIP,      S_TIP_D,    m)      // direct dirty only via MMIO write
    transition(S_TIP,      S_TIP_CD,   false)  // acquire does not make us dirty immediately
    transition(S_TIP,      S_TRUNK_C,  c)      // acquire
    transition(S_TIP,      S_TRUNK_CD, false)  // acquire does not make us dirty immediately

    transition(S_TIP_C,    S_INVALID,  c && p)
    transition(S_TIP_C,    S_BRANCH,   c && p) // losing TIP only possible via probe
    transition(S_TIP_C,    S_BRANCH_C, c && p) // losing TIP only possible via probe
    transition(S_TIP_C,    S_TIP,      c)      // probed while MMIO read || clean release (optional)
    transition(S_TIP_C,    S_TIP_D,    c && m) // direct dirty only via MMIO write
    transition(S_TIP_C,    S_TIP_CD,   false)  // going dirty means we must shoot down clients
    transition(S_TIP_C,    S_TRUNK_C,  c)      // acquire
    transition(S_TIP_C,    S_TRUNK_CD, false)  // acquire does not make us immediately dirty

    transition(S_TIP_D,    S_INVALID,  p)
    transition(S_TIP_D,    S_BRANCH,   p)      // losing D is only possible via probe
    transition(S_TIP_D,    S_BRANCH_C, p && c) // probed while acquire shared
    transition(S_TIP_D,    S_TIP,      p)      // probed while MMIO read || outer probe.toT (optional)
    transition(S_TIP_D,    S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_TIP_D,    S_TIP_CD,   false)  // we would go S_TRUNK_CD instead
    transition(S_TIP_D,    S_TRUNK_C,  p && c) // probed while acquired
    transition(S_TIP_D,    S_TRUNK_CD, c)      // acquire

    transition(S_TIP_CD,   S_INVALID,  c && p)
    transition(S_TIP_CD,   S_BRANCH,   c && p) // losing D is only possible via probe
    transition(S_TIP_CD,   S_BRANCH_C, c && p) // losing D is only possible via probe
    transition(S_TIP_CD,   S_TIP,      c && p) // probed while MMIO read || outer probe.toT (optional)
    transition(S_TIP_CD,   S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_TIP_CD,   S_TIP_D,    c)      // MMIO write || clean release (optional)
    transition(S_TIP_CD,   S_TRUNK_C,  c && p) // probed while acquire
    transition(S_TIP_CD,   S_TRUNK_CD, c)      // acquire

    transition(S_TRUNK_C,  S_INVALID,  c && p)
    transition(S_TRUNK_C,  S_BRANCH,   c && p) // losing TIP only possible via probe
    transition(S_TRUNK_C,  S_BRANCH_C, c && p) // losing TIP only possible via probe
    transition(S_TRUNK_C,  S_TIP,      c)      // MMIO read || clean release (optional)
    transition(S_TRUNK_C,  S_TIP_C,    c)      // bounce shared
    transition(S_TRUNK_C,  S_TIP_D,    c)      // dirty release
    transition(S_TRUNK_C,  S_TIP_CD,   c)      // dirty bounce shared
    transition(S_TRUNK_C,  S_TRUNK_CD, c)      // dirty bounce

    transition(S_TRUNK_CD, S_INVALID,  c && p)
    transition(S_TRUNK_CD, S_BRANCH,   c && p) // losing D only possible via probe
    transition(S_TRUNK_CD, S_BRANCH_C, c && p) // losing D only possible via probe
    transition(S_TRUNK_CD, S_TIP,      c && p) // probed while MMIO read || outer probe.toT (optional)
    transition(S_TRUNK_CD, S_TIP_C,    false)  // we would go S_TRUNK_C instead
    transition(S_TRUNK_CD, S_TIP_D,    c)      // dirty release
    transition(S_TRUNK_CD, S_TIP_CD,   c)      // bounce shared
    transition(S_TRUNK_CD, S_TRUNK_C,  c && p) // probed while acquire
  }

  // Handle response messages
  // 这里统计的是“old victim 这条 line”上需要被 probe 的 client 是否已经全部回完，
  // 所以必须基于 victimMeta.clients，而不能基于面向新 line 结果视图的 refillMeta。
  val probe_bit = params.clientBit(io.sinkc.bits.source)
  val last_probe = (probes_done | probe_bit) === (victimMeta.clients & ~excluded_client)
  val probe_toN = isToN(io.sinkc.bits.param)
  if (!params.firstLevel) when (io.sinkc.valid) {
    params.ccover( probe_toN && io.schedule.bits.b.bits.param === toB, "MSHR_PROBE_FULL", "Client downgraded to N when asked only to do B")
    params.ccover(!probe_toN && io.schedule.bits.b.bits.param === toB, "MSHR_PROBE_HALF", "Client downgraded to B when asked only to do B")
    // Caution: the probe matches us only in set.
    // We would never allow an outer probe to nest until both w_[rp]probeack complete, so
    // it is safe to just unguardedly update the probe FSM.
    probes_done := probes_done | probe_bit
    probes_toN := probes_toN | Mux(probe_toN, probe_bit, 0.U)
    probes_noT := probes_noT || io.sinkc.bits.param =/= TtoT
    w_rprobeackfirst := w_rprobeackfirst || last_probe
    w_rprobeacklast := w_rprobeacklast || (last_probe && io.sinkc.bits.last)
    w_pprobeackfirst := w_pprobeackfirst || last_probe
    w_pprobeacklast := w_pprobeacklast || (last_probe && io.sinkc.bits.last)
    // Allow wormhole routing from sinkC if the first request beat has offset 0
    val set_pprobeack = last_probe && (io.sinkc.bits.last || request.offset === 0.U)
    w_pprobeack := w_pprobeack || set_pprobeack
    params.ccover(!set_pprobeack && w_rprobeackfirst, "MSHR_PROBE_SERIAL", "Sequential routing of probe response data")
    params.ccover( set_pprobeack && w_rprobeackfirst, "MSHR_PROBE_WORMHOLE", "Wormhole routing of probe response data")
    // However, meta-data updates need to be done more cautiously
    // 这里匹配到的是 old victim 这条 line 的 SinkC 返回；如果内层把带 data 的脏 line
    // 交回来了，就要修正 victim 侧视图本身的 dirty/crypto/counter 语义，因此更新的是
    // victimMeta，而不是表示“事务完成后新 line 结果视图”的 refillMeta。
    when (meta.state =/= INVALID && io.sinkc.bits.tag === meta.tag && io.sinkc.bits.data) {
      victimMeta.dirty := true.B
      when (request_valid && meta_valid &&
            (!w_rprobeackfirst || !w_rprobeacklast || !w_pprobeackfirst || !w_pprobeacklast)) {
        victimMeta.cryptoLine := io.sinkc.bits.cryptoLine
        victimMeta.counterValid := io.sinkc.bits.cryptoLine
      }
    }
  }
  when (io.sinkd.valid) {
    when (debugLogEnable) {
      printf(p"[MSHR-SINKD-TRACE] req_source=0x${Hexadecimal(request.source)} opcode=0x${Hexadecimal(io.sinkd.bits.opcode)} source=0x${Hexadecimal(io.sinkd.bits.source)} isCounter=${io.sinkd.bits.isCounter} last=${io.sinkd.bits.last} w_releaseack=${w_releaseack} s_release=${s_release}\n")
    }
    when (io.sinkd.bits.isCounter && io.sinkd.bits.opcode === AccessAckData) {
      assert(!l2CryptoAssertEnable || !w_countergrant,
        "MSHR received duplicate counter grant")
      w_countergrant := true.B
      refillMeta.cryptoLine := true.B
      refillMeta.counterValid := true.B
    } .elsewhen (io.sinkd.bits.isCounter && io.sinkd.bits.opcode === AccessAck) {
      assert(!l2CryptoAssertEnable || !w_counterput_ack,
        "MSHR received duplicate counter put acknowledgement")
      w_counterput_ack := true.B
    } .elsewhen (io.sinkd.bits.opcode === Grant || io.sinkd.bits.opcode === GrantData) {
      sink := io.sinkd.bits.sink
      w_grantfirst := true.B
      w_grantlast := io.sinkd.bits.last
      // Record if we need to prevent taking ownership
      bad_grant := io.sinkd.bits.denied
      // Allow wormhole routing for requests whose first beat has offset 0
      w_grant := request.offset === 0.U || io.sinkd.bits.last
      params.ccover(io.sinkd.bits.opcode === GrantData && request.offset === 0.U, "MSHR_GRANT_WORMHOLE", "Wormhole routing of grant response data")
      params.ccover(io.sinkd.bits.opcode === GrantData && request.offset =/= 0.U, "MSHR_GRANT_SERIAL", "Sequential routing of grant response data")
      gotT := io.sinkd.bits.param === toT
    }
    .elsewhen (io.sinkd.bits.opcode === ReleaseAck) {
      w_releaseack := true.B
    }
  }
  when (debugLogEnable && io.sinke.valid) {
    printf(p"[MSHR-SINKE-TRACE] source=0x${Hexadecimal(request.source)} set=0x${Hexadecimal(request.set)} tag=0x${Hexadecimal(request.tag)} sink=0x${Hexadecimal(io.sinke.bits.sink)} w_grantack_before=${w_grantack}\n")
  }
  when (io.sinke.valid) {
    w_grantack := true.B
  }

  // Bootstrap new requests
  val allocate_as_full = WireInit(new FullRequest(params), init = io.allocate.bits)
  // new_meta_base 是 repeat bypass 时沿用 final_meta，否则直接取 directory 命中结果。
  val new_meta_base = Mux(io.allocate.valid && io.allocate.bits.repeat, final_meta_writeback, io.directory.bits)
  // new_meta 表示这次建计划时看到的“当前 victim 视图”。
  val new_meta = WireInit(new_meta_base)
  // new_request 表示这次建计划真正要服务的请求，可能来自 allocate reload，也可能是老请求续跑。
  val new_request = Mux(io.allocate.valid, allocate_as_full, request)
  // new_needT 表示这次新请求是否需要以 T 权限完成。
  val new_needT = needT(new_request.opcode, new_request.param)
  // new_clientBit 是新请求来源客户端在 clients 位图中的 one-hot。
  val new_clientBit = params.clientBit(new_request.source)
  // new_skipProbe 表示这次请求允许跳过 probe 的客户端集合。
  val new_skipProbe = Mux(skipProbeN(new_request.opcode, params.cache.hintsSkipProbe), new_clientBit, 0.U)
  // 新请求在建计划时就检测到的同 tag 模式切换窗口。
  val new_same_tag_mode_mismatch =
    new_request.prio(2) &&
    (!params.firstLevel).B &&
    (new_request.tag === new_meta.tag) &&
    (new_request.cryptoLine =/= new_meta.cryptoLine)

  // new_victim_meta_base 与 new_meta_base 等价，但后面会继续派生出 victim/refill 两套独立视图。
  val new_victim_meta_base =
    Mux(io.allocate.valid && io.allocate.bits.repeat, final_meta_writeback, io.directory.bits)
  // new_victim_meta 是建计划阶段的 victim 侧目录视图。
  val new_victim_meta = WireInit(new_victim_meta_base)
  // new_refill_meta 是建计划阶段预估的 refill/提交后目录视图。
  val new_refill_meta = WireInit(new_victim_meta_base)
  // refill_mode_mismatch 表示这次 A 请求想装入的模式与当前 victim mode 不一致。
  val refill_mode_mismatch =
    new_request.prio(0) &&
    !new_request.control &&
    (new_request.cryptoLine =/= new_victim_meta.cryptoLine)
  // new_need_counter_put 表示这次建计划时确认 old victim 还欠一笔 counter writeback。
  val new_need_counter_put =
    new_victim_meta.hit &&
    new_victim_meta.dirty &&
    new_victim_meta.cryptoLine &&
    new_victim_meta.counterValid
  // 需要从请求本身重建 refill 视图，而不是沿用当前 victim 视图。
  val refill_rebuild_from_request =
    new_request.prio(0) && !new_request.control &&
    (!new_victim_meta.hit ||
      (new_victim_meta.state === BRANCH && new_needT) ||
      refill_mode_mismatch)
  val newRequestLineAddress = params.restoreAddress(params.expandAddress(new_request.tag, new_request.set, 0.U))
  val newVictimLineAddress = params.restoreAddress(params.expandAddress(new_victim_meta.tag, new_request.set, 0.U))
  val newRefillLineAddress = params.restoreAddress(params.expandAddress(new_refill_meta.tag, new_request.set, 0.U))
  val newRequestLineAddress64 =
    if (params.outer.bundle.addressBits < 64) {
      Cat(0.U((64 - params.outer.bundle.addressBits).W), newRequestLineAddress)
    } else {
      newRequestLineAddress
    }
  val newVictimLineAddress64 =
    if (params.outer.bundle.addressBits < 64) {
      Cat(0.U((64 - params.outer.bundle.addressBits).W), newVictimLineAddress)
    } else {
      newVictimLineAddress
    }
  val newRefillLineAddress64 =
    if (params.outer.bundle.addressBits < 64) {
      Cat(0.U((64 - params.outer.bundle.addressBits).W), newRefillLineAddress)
    } else {
      newRefillLineAddress
    }
  val debugDataRangePlan =
    newRequestLineAddress64 >= debugDataWatchStart &&
    newRequestLineAddress64 < debugDataWatchEnd

  when (new_request.prio(0) && !new_request.control) {
    new_refill_meta.tag := new_request.tag
    new_refill_meta.way := new_victim_meta.way
    when (refill_rebuild_from_request) {
      new_refill_meta.cryptoLine := new_request.cryptoLine
      new_refill_meta.counterValid := !new_request.cryptoLine
    }
  }

  val prior = cacheState(final_meta_writeback, true.B)
  def bypass(from: CacheState, cover: Boolean)(implicit sourceInfo: SourceInfo) {
    if (cover) {
      params.ccover(prior === from.code, s"MSHR_${from}_BYPASS", s"State bypass transition from ${from} ${cfg}")
    } else {
      assert(!(prior === from.code), cf"State bypass from ${from} should be impossible ${cfg}")
    }
  }

  when (io.allocate.valid && io.allocate.bits.repeat) {
    bypass(S_INVALID,   f || p) // Can lose permissions (probe/flush)
    bypass(S_BRANCH,    b)      // MMIO read to read-only device
    bypass(S_BRANCH_C,  b && c) // you need children to become C
    bypass(S_TIP,       true)   // MMIO read || clean release can lead to this state
    bypass(S_TIP_C,     c)      // needs two clients || client + mmio || downgrading client
    bypass(S_TIP_CD,    c)      // needs two clients || client + mmio || downgrading client
    bypass(S_TIP_D,     true)   // MMIO write || dirty release lead here
    bypass(S_TRUNK_C,   c)      // acquire for write
    bypass(S_TRUNK_CD,  c)      // dirty release then reacquire
  }

  when (io.allocate.valid) {
    assert (!request_valid || (no_wait && io.schedule.fire))
    request_valid := true.B
    request := io.allocate.bits
  }

  // Create execution plan
  when (io.directory.valid || (io.allocate.valid && io.allocate.bits.repeat)) {
  meta_valid := true.B
    victimMeta := new_victim_meta
    refillMeta := new_refill_meta
    probes_done := 0.U
    probes_toN := 0.U
    probes_noT := false.B
    gotT := false.B
    bad_grant := false.B

    // These should already be either true or turning true
    // We clear them here explicitly to simplify the mux tree
    s_rprobe         := true.B
    w_rprobeackfirst := true.B
    w_rprobeacklast  := true.B
    s_release        := true.B
    w_releaseack     := true.B
    s_pprobe         := true.B
    s_acquire        := true.B
    s_flush          := true.B
    w_grantfirst     := true.B
    w_grantlast      := true.B
    w_grant          := true.B
    w_pprobeackfirst := true.B
    w_pprobeacklast  := true.B
    w_pprobeack      := true.B
    s_probeack       := true.B
    s_grantack       := true.B
    s_execute        := true.B
    w_grantack       := true.B
    s_writeback      := true.B
    s_counter_acquire := true.B
    w_countergrant    := true.B
    s_counter_put     := true.B
    w_counterput_ack  := true.B

    // For C channel requests (ie: Release[Data])
    when (new_request.prio(2) && (!params.firstLevel).B) {
      s_execute := false.B
      // Do we need to go dirty?
      when (new_request.opcode(0) && !new_meta.dirty) {
        s_writeback := false.B
      }
      // Does our state change?
      when (isToB(new_request.param) && new_meta.state === TRUNK) {
        s_writeback := false.B
      }
      // Do our clients change?
      when (isToN(new_request.param) && (new_meta.clients & new_clientBit) =/= 0.U) {
        s_writeback := false.B
      }
      when (new_same_tag_mode_mismatch) {
        s_writeback := false.B
      }
      assert (new_meta.hit || new_same_tag_mode_mismatch)
    }
    // For X channel requests (ie: flush)
    .elsewhen (new_request.control && params.control.B) { // new_request.prio(0)
      s_flush := false.B
      // Do we need to actually do something?
      when (new_meta.hit) {
        s_release := false.B
        w_releaseack := false.B
        when (new_need_counter_put) {
          s_counter_put := false.B
        }
        // Do we need to shoot-down inner caches?
        when ((!params.firstLevel).B && (new_meta.clients =/= 0.U)) {
          s_rprobe := false.B
          w_rprobeackfirst := false.B
          w_rprobeacklast := false.B
        }
      }
    }
    // For A channel requests
    .otherwise { // new_request.prio(0) && !new_request.control
      s_execute := false.B
      // Do we need an eviction?
      when (!new_meta.hit && new_meta.state =/= INVALID) {
        s_release := false.B
        w_releaseack := false.B
        when (new_need_counter_put) {
          s_counter_put := false.B
        }
        // Do we need to shoot-down inner caches?
        when ((!params.firstLevel).B & (new_meta.clients =/= 0.U)) {
          s_rprobe := false.B
          w_rprobeackfirst := false.B
          w_rprobeacklast := false.B
        }
      }
      // Do we need an acquire?
      when (!new_meta.hit || (new_meta.state === BRANCH && new_needT)) {
        s_acquire := false.B
        w_grantfirst := false.B
        w_grantlast := false.B
        w_grant := false.B
        s_grantack := false.B
        s_writeback := false.B
      }
      // Do we need a probe?
      when ((!params.firstLevel).B && (new_meta.hit &&
            (new_needT || new_meta.state === TRUNK) &&
            (new_meta.clients & ~new_skipProbe) =/= 0.U)) {
        s_pprobe := false.B
        w_pprobeackfirst := false.B
        w_pprobeacklast := false.B
        w_pprobeack := false.B
        s_writeback := false.B
      }
      // Do we need a grantack?
      when (new_request.opcode === AcquireBlock || new_request.opcode === AcquirePerm) {
        w_grantack := false.B
        s_writeback := false.B
      }
      // Becomes dirty?
      when (!new_request.opcode(2) && new_meta.hit && !new_meta.dirty) {
        s_writeback := false.B
      }
    }
  }
}
