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
import chisel3.util.experimental.BoringUtils
import freechips.rocketchip.rocket.CacheCryptoRefillMeta
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import chisel3.experimental.dataview._

class InclusiveCacheBankScheduler(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val in = Flipped(TLBundle(params.inner.bundle))
    val out = TLBundle(params.outer.bundle)
    val cus_base_address = Input(UInt(64.W))
    val l2_crypto_assert_enable = Input(Bool())
    // Way permissions
    val ways = Flipped(Vec(params.allClients, UInt(params.cache.ways.W)))
    val divs = Flipped(Vec(params.allClients, UInt((InclusiveCacheParameters.lfsrBits + 1).W)))
    // Control port
    val req = Flipped(Decoupled(new SinkXRequest(params)))
    val resp = Decoupled(new SourceXRequest(params))
  })

  val sourceA = Module(new SourceA(params))
  val sourceB = Module(new SourceB(params))
  val sourceC = Module(new SourceC(params))
  val sourceD = Module(new SourceD(params))
  val sourceE = Module(new SourceE(params))
  val sourceX = Module(new SourceX(params))

  sourceA.io.cus_base_address := io.cus_base_address
  sourceA.io.l2_crypto_assert_enable := io.l2_crypto_assert_enable
  sourceD.io.l2_crypto_assert_enable := io.l2_crypto_assert_enable

  io.out.a <> sourceA.io.a
  io.out.c <> sourceC.io.c
  io.out.e <> sourceE.io.e
  io.in.b <> sourceB.io.b
  io.in.d <> sourceD.io.d
  io.resp <> sourceX.io.x

  val sinkA = Module(new SinkA(params))
  val sinkC = Module(new SinkC(params))
  val sinkD = Module(new SinkD(params))
  val sinkE = Module(new SinkE(params))
  val sinkX = Module(new SinkX(params))

  sinkA.io.a <> io.in.a
  sinkC.io.c <> io.in.c
  sinkE.io.e <> io.in.e
  sinkD.io.d <> io.out.d
  sinkX.io.x <> io.req
  sinkC.io.l2_crypto_assert_enable := io.l2_crypto_assert_enable
  sinkD.io.l2_crypto_assert_enable := io.l2_crypto_assert_enable

  io.out.b.ready := true.B // disconnected

  val directory = Module(new Directory(params))
  val bankedStore = Module(new BankedStore(params))
  val requests = Module(new ListBuffer(ListBufferParameters(new FullRequest(params), 3*params.mshrs, params.secondary, false)))
  val mshrs = Seq.fill(params.mshrs) { Module(new MSHR(params)) }
  val abc_mshrs = mshrs.init.init
  val bc_mshr = mshrs.init.last
  val c_mshr = mshrs.last
  val nestedwb = Wire(new NestedWriteback(params))

  bankedStore.io.counterReadA <> sourceC.io.ctr_radr
  sourceC.io.ctr_rdat := bankedStore.io.counterReadAData
  bankedStore.io.counterReadD <> sourceD.io.ctr_radr
  sourceD.io.ctr_rdat := bankedStore.io.counterReadDData
  bankedStore.io.sinkC_counterWrite <> sinkC.io.counter_write
  bankedStore.io.sinkD_counterWrite <> sinkD.io.counter_write
  bankedStore.io.sourceD_counterWrite <> sourceD.io.counter_write

  sourceC.io.ctr_snapshot_idx := sourceA.io.ctr_snapshot_idx
  sourceA.io.ctr_snapshot_data := sourceC.io.ctr_snapshot_data
  sourceA.io.ctr_snapshot_valid := sourceC.io.ctr_snapshot_valid
  sourceC.io.ctr_snapshot_pop := sourceA.io.ctr_snapshot_pop

  mshrs.foreach { m =>
    m.io.l2_crypto_assert_enable := io.l2_crypto_assert_enable
    m.io.allocate.valid := false.B
    m.io.allocate.bits := 0.U.asTypeOf(new AllocateRequest(params))
  }

  // Deliver messages from Sinks to MSHRs
  mshrs.zipWithIndex.foreach { case (m, i) =>
    m.io.sinkc.valid := sinkC.io.resp.valid && sinkC.io.resp.bits.set === m.io.status.bits.set
    m.io.sinkd.valid := sinkD.io.resp.valid && sinkD.io.resp.bits.source === i.U
    m.io.sinke.valid := sinkE.io.resp.valid && sinkE.io.resp.bits.sink   === i.U
    m.io.sinkc.bits := sinkC.io.resp.bits
    m.io.sinkd.bits := sinkD.io.resp.bits
    m.io.sinke.bits := sinkE.io.resp.bits
    m.io.nestedwb := nestedwb
  }

  // If the pre-emption BC or C MSHR have a matching set, the normal MSHR must be blocked
  val mshr_stall_abc = abc_mshrs.map { m =>
    (bc_mshr.io.status.valid && m.io.status.bits.set === bc_mshr.io.status.bits.set) ||
    ( c_mshr.io.status.valid && m.io.status.bits.set ===  c_mshr.io.status.bits.set)
  }
  val mshr_stall_bc =
    c_mshr.io.status.valid && bc_mshr.io.status.bits.set === c_mshr.io.status.bits.set
  val mshr_stall_c = false.B
  val mshr_stall = mshr_stall_abc :+ mshr_stall_bc :+ mshr_stall_c


  val stall_abc = (mshr_stall_abc zip abc_mshrs) map { case (s, m) => s && m.io.status.valid }
  if (!params.lastLevel || !params.firstLevel)
    params.ccover(stall_abc.reduce(_||_), "SCHEDULER_ABC_INTERLOCK", "ABC MSHR interlocked due to pre-emption")
  if (!params.lastLevel)
    params.ccover(mshr_stall_bc && bc_mshr.io.status.valid, "SCHEDULER_BC_INTERLOCK", "BC MSHR interlocked due to pre-emption")

  // Consider scheduling an MSHR only if all the resources it requires are available
  val mshr_request = Cat((mshrs zip mshr_stall).map { case (m, s) =>
    m.io.schedule.valid && !s &&
      (sourceA.io.req.ready || !m.io.schedule.bits.a.valid) &&
      (sourceB.io.req.ready || !m.io.schedule.bits.b.valid) &&
      (sourceC.io.req.ready || !m.io.schedule.bits.c.valid) &&
      (sourceD.io.req.ready || !m.io.schedule.bits.d.valid) &&
      (sourceE.io.req.ready || !m.io.schedule.bits.e.valid) &&
      (sourceX.io.req.ready || !m.io.schedule.bits.x.valid) &&
      (directory.io.write.ready || !m.io.schedule.bits.dir.valid)
  }.reverse)

  // Round-robin arbitration of MSHRs
  val robin_filter = RegInit(0.U(params.mshrs.W))
  val robin_request = Cat(mshr_request, mshr_request & robin_filter)
  val mshr_selectOH2 = ~(leftOR(robin_request) << 1) & robin_request
  val mshr_selectOH = mshr_selectOH2(2*params.mshrs-1, params.mshrs) | mshr_selectOH2(params.mshrs-1, 0)
  val mshr_select = OHToUInt(mshr_selectOH)
  val schedule = Mux1H(mshr_selectOH, mshrs.map(_.io.schedule.bits))
  val scheduleTag = Mux1H(mshr_selectOH, mshrs.map(_.io.status.bits.tag))
  val scheduleSet = Mux1H(mshr_selectOH, mshrs.map(_.io.status.bits.set))
  val scheduleReloadBaseCrypto = Mux1H(mshr_selectOH, mshrs.map(_.io.status.bits.reload_base_crypto_line))

  // When an MSHR wins the schedule, it has lowest priority next time
  when (mshr_request.orR) { robin_filter := ~rightOR(mshr_selectOH) }

  // Fill in which MSHR sends the request
  // 把 outer A 请求编码成 [mshr_select | source_type]。
  // 这样 data、counter fetch、counter writeback 可以在共享的 TL source
  // 空间里彼此隔离，同时返回路径上的 SinkD 仍然能从高位恢复所属的 MSHR。
  val aSourceType =
    Mux(schedule.a.bits.isCounterWrite, OuterRequestSourceType.counterPut,
      Mux(schedule.a.bits.isCounter, OuterRequestSourceType.counterGet, OuterRequestSourceType.data))
  schedule.a.bits.source := Cat(mshr_select, aSourceType)
  // outer C 只承载普通 line data 流量，所以 Release/ReleaseData 需要 source
  // ID 时始终使用 data 子类型。
  // 这里不能再直接塞裸 mshr_select；因为整个 outer source 字段现在统一编码成
  // [mshr_select | source_type]。ReleaseAck 返回时，SinkD 会按这个格式先解低位类型、
  // 再解高位所属 MSHR；如果 C 侧还沿用旧格式，就会把返回类型和 MSHR 号都解错。
  schedule.c.bits.source := Mux(schedule.c.bits.opcode(1), Cat(mshr_select, OuterRequestSourceType.data), 0.U)
  schedule.d.bits.sink   := mshr_select

  sourceA.io.req.valid := schedule.a.valid
  sourceB.io.req.valid := schedule.b.valid
  sourceC.io.req.valid := schedule.c.valid
  sourceD.io.req.valid := schedule.d.valid
  sourceE.io.req.valid := schedule.e.valid
  sourceX.io.req.valid := schedule.x.valid

  sourceA.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.a.bits)) := schedule.a.bits
  sourceB.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.b.bits)) := schedule.b.bits
  sourceC.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.c.bits)) := schedule.c.bits
  sourceD.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.d.bits)) := schedule.d.bits
  sourceE.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.e.bits)) := schedule.e.bits
  sourceX.io.req.bits.viewAsSupertype(chiselTypeOf(schedule.x.bits)) := schedule.x.bits

  directory.io.write.valid := schedule.dir.valid
  directory.io.write.bits.viewAsSupertype(chiselTypeOf(schedule.dir.bits)) := schedule.dir.bits

  // Forward meta-data changes from nested transaction completion
  val select_c  = mshr_selectOH(params.mshrs-1)
  val select_bc = mshr_selectOH(params.mshrs-2)
  nestedwb.set   := Mux(select_c, c_mshr.io.status.bits.set, bc_mshr.io.status.bits.set)
  nestedwb.tag   := Mux(select_c, c_mshr.io.status.bits.tag, bc_mshr.io.status.bits.tag)
  nestedwb.b_toN       := select_bc && bc_mshr.io.schedule.bits.dir.valid && bc_mshr.io.schedule.bits.dir.bits.data.state === MetaData.INVALID
  nestedwb.b_toB       := select_bc && bc_mshr.io.schedule.bits.dir.valid && bc_mshr.io.schedule.bits.dir.bits.data.state === MetaData.BRANCH
  nestedwb.b_clr_dirty := select_bc && bc_mshr.io.schedule.bits.dir.valid
  nestedwb.c_set_dirty := select_c  &&  c_mshr.io.schedule.bits.dir.valid && c_mshr.io.schedule.bits.dir.bits.data.dirty

  // Pick highest priority request
  val request = Wire(Decoupled(new FullRequest(params)))
  request.valid := directory.io.ready && (sinkA.io.req.valid || sinkX.io.req.valid || sinkC.io.req.valid)
  request.bits := Mux(sinkC.io.req.valid, sinkC.io.req.bits,
                  Mux(sinkX.io.req.valid, sinkX.io.req.bits, sinkA.io.req.bits))
  sinkC.io.req.ready := directory.io.ready && request.ready
  sinkX.io.req.ready := directory.io.ready && request.ready && !sinkC.io.req.valid
  sinkA.io.req.ready := directory.io.ready && request.ready && !sinkC.io.req.valid && !sinkX.io.req.valid
  val traceAReqSet0 = request.valid && request.bits.prio(0) && !request.bits.control && request.bits.set === 0.U
  val traceCReqSet0 = request.valid && request.bits.prio(2) && request.bits.set === 0.U

  // If no MSHR has been assigned to this set, we need to allocate one
  val setMatches = Cat(mshrs.map { m => m.io.status.valid && m.io.status.bits.set === request.bits.set }.reverse)
  val alloc = !setMatches.orR // NOTE: no matches also means no BC or C pre-emption on this set
  // If a same-set MSHR says that requests of this type must be blocked (for bounded time), do it
  val blockB = Mux1H(setMatches, mshrs.map(_.io.status.bits.blockB)) && request.bits.prio(1)
  val blockC = Mux1H(setMatches, mshrs.map(_.io.status.bits.blockC)) && request.bits.prio(2)
  // If a same-set MSHR says that requests of this type must be handled out-of-band, use special BC|C MSHR
  // ... these special MSHRs interlock the MSHR that said it should be pre-empted.
  val nestB  = Mux1H(setMatches, mshrs.map(_.io.status.bits.nestB))  && request.bits.prio(1)
  val nestC  = Mux1H(setMatches, mshrs.map(_.io.status.bits.nestC))  && request.bits.prio(2)
  // Prevent priority inversion; we may not queue to MSHRs beyond our level
  val prioFilter = Cat(request.bits.prio(2), !request.bits.prio(0), ~0.U((params.mshrs-2).W))
  val lowerMatches = setMatches & prioFilter
  // If we match an MSHR <= our priority that neither blocks nor nests us, queue to it.
  val queue = lowerMatches.orR && !nestB && !nestC && !blockB && !blockC

  if (!params.lastLevel) {
    params.ccover(request.valid && blockB, "SCHEDULER_BLOCKB", "Interlock B request while resolving set conflict")
    params.ccover(request.valid && nestB,  "SCHEDULER_NESTB", "Priority escalation from channel B")
  }
  if (!params.firstLevel) {
    params.ccover(request.valid && blockC, "SCHEDULER_BLOCKC", "Interlock C request while resolving set conflict")
    params.ccover(request.valid && nestC,  "SCHEDULER_NESTC", "Priority escalation from channel C")
  }
  params.ccover(request.valid && queue, "SCHEDULER_SECONDARY", "Enqueue secondary miss")

  // It might happen that lowerMatches has >1 bit if the two special MSHRs are in-use
  // We want to Q to the highest matching priority MSHR.
  val lowerMatches1 =
    Mux(lowerMatches(params.mshrs-1), 1.U << (params.mshrs-1),
    Mux(lowerMatches(params.mshrs-2), 1.U << (params.mshrs-2),
    lowerMatches))

  // If this goes to the scheduled MSHR, it may need to be bypassed
  // Alternatively, the MSHR may be refilled from a request queued in the ListBuffer
  val selected_requests = Cat(mshr_selectOH, mshr_selectOH, mshr_selectOH) & requests.io.valid
  val a_pop = selected_requests((0 + 1) * params.mshrs - 1, 0 * params.mshrs).orR
  val b_pop = selected_requests((1 + 1) * params.mshrs - 1, 1 * params.mshrs).orR
  val c_pop = selected_requests((2 + 1) * params.mshrs - 1, 2 * params.mshrs).orR
  val bypassMatches = (mshr_selectOH & lowerMatches1).orR &&
                      Mux(c_pop || request.bits.prio(2), !c_pop, Mux(b_pop || request.bits.prio(1), !b_pop, !a_pop))
  val may_pop = a_pop || b_pop || c_pop
  val bypass = request.valid && queue && bypassMatches
  val will_reload = schedule.reload && (may_pop || bypass)
  val will_pop = schedule.reload && may_pop && !bypass

  params.ccover(mshr_selectOH.orR && bypass, "SCHEDULER_BYPASS", "Bypass new request directly to conflicting MSHR")
  params.ccover(mshr_selectOH.orR && will_reload, "SCHEDULER_RELOAD", "Back-to-back service of two requests")
  params.ccover(mshr_selectOH.orR && will_pop, "SCHEDULER_POP", "Service of a secondary miss")

  // Repeat the above logic, but without the fan-in
  mshrs.zipWithIndex.foreach { case (m, i) =>
    val sel = mshr_selectOH(i)
    m.io.schedule.ready := sel
    val a_pop = requests.io.valid(params.mshrs * 0 + i)
    val b_pop = requests.io.valid(params.mshrs * 1 + i)
    val c_pop = requests.io.valid(params.mshrs * 2 + i)
    val bypassMatches = lowerMatches1(i) &&
                        Mux(c_pop || request.bits.prio(2), !c_pop, Mux(b_pop || request.bits.prio(1), !b_pop, !a_pop))
    val may_pop = a_pop || b_pop || c_pop
    val bypass = request.valid && queue && bypassMatches
    val will_reload = m.io.schedule.bits.reload && (may_pop || bypass)
    m.io.allocate.bits.viewAsSupertype(chiselTypeOf(requests.io.data)) := Mux(bypass, WireInit(new FullRequest(params), init = request.bits), requests.io.data)
    m.io.allocate.bits.set := m.io.status.bits.set
    // repeat 表示这笔 reload 请求可以直接复用当前 MSHR 刚推导出的 meta 结果。
    // 除了 set 已经固定相同之外，还要求 tag 相同且 cryptoLine 模式相同；
    // 否则即使落在同一个 set，也不能把当前事务的 meta/result 直接沿用到下一笔请求。
    m.io.allocate.bits.repeat :=
      (m.io.allocate.bits.tag === m.io.status.bits.tag) &&
      (m.io.allocate.bits.cryptoLine === m.io.status.bits.reload_base_crypto_line)
    m.io.allocate.valid := sel && will_reload
  }

  // Determine which of the queued requests to pop (supposing will_pop)
  val prio_requests = ~(~requests.io.valid | (requests.io.valid >> params.mshrs) | (requests.io.valid >> 2*params.mshrs))
  val pop_index = OHToUInt(Cat(mshr_selectOH, mshr_selectOH, mshr_selectOH) & prio_requests)
  requests.io.pop.valid := will_pop
  requests.io.pop.bits  := pop_index

  // Reload from the Directory if the next MSHR operation changes tags
  // ListBuffer 头部请求的 tag 与当前已调度 MSHR 的 tag 是否不一致。
  val lb_tag_mismatch = scheduleTag =/= requests.io.data.tag
  // ListBuffer 头部请求的 cryptoLine 模式与当前已调度 MSHR 的基线模式是否不一致。
  val lb_mode_mismatch = scheduleReloadBaseCrypto =/= requests.io.data.cryptoLine
  // 只要 tag 或 cryptoLine 模式有任一不一致，就不能直接沿用当前 MSHR 的 meta 结果。
  val lb_rebuild_needed = lb_tag_mismatch || lb_mode_mismatch
  // 假设这拍不会发生 bypass，仅从即将 pop 的 ListBuffer 请求出发判断，MSHR 是否需要回目录重建 meta。
  val mshr_uses_directory_assuming_no_bypass = schedule.reload && may_pop && lb_rebuild_needed
  // 真实 will_pop 场景下，给 ListBuffer reload 使用的“是否需要回目录”判定。
  val mshr_uses_directory_for_lb = will_pop && lb_rebuild_needed
  // 下一笔 reload 请求最终采用的 tag；若这拍 bypass，则直接取当前入口 request，否则取 ListBuffer 头部请求。
  val reload_req_tag = Mux(bypass, request.bits.tag, requests.io.data.tag)
  // 下一笔 reload 请求最终采用的 cryptoLine 模式；同样需要区分 bypass 与 ListBuffer 头部请求。
  val reload_req_crypto = Mux(bypass, request.bits.cryptoLine, requests.io.data.cryptoLine)
  // 最终选中的 reload 请求，其 tag 与当前 MSHR tag 是否不一致。
  val reload_tag_mismatch = scheduleTag =/= reload_req_tag
  // 最终选中的 reload 请求，其 cryptoLine 模式与当前 MSHR 基线模式是否不一致。
  val reload_mode_mismatch = scheduleReloadBaseCrypto =/= reload_req_crypto
  // 真实 will_reload 场景下，只要最终 reload 请求的 tag 或模式变了，就必须重新使用 Directory 结果。
  val mshr_uses_directory = will_reload && (reload_tag_mismatch || reload_mode_mismatch)

  // Is there an MSHR free for this request?
  val mshr_validOH = Cat(mshrs.map(_.io.status.valid).reverse)
  val mshr_free = (~mshr_validOH & prioFilter).orR

  // Fanout the request to the appropriate handler (if any)
  val bypassQueue = schedule.reload && bypassMatches
  val request_alloc_cases =
     (alloc && !mshr_uses_directory_assuming_no_bypass && mshr_free) ||
     (nestB && !mshr_uses_directory_assuming_no_bypass && !bc_mshr.io.status.valid && !c_mshr.io.status.valid) ||
     (nestC && !mshr_uses_directory_assuming_no_bypass && !c_mshr.io.status.valid)
  request.ready := request_alloc_cases || (queue && (bypassQueue || requests.io.push.ready))
  val alloc_uses_directory = request.valid && request_alloc_cases

  // When a request goes through, it will need to hit the Directory
  directory.io.read.valid := mshr_uses_directory || alloc_uses_directory
  directory.io.read.bits.set := Mux(mshr_uses_directory_for_lb, scheduleSet,          request.bits.set)
  directory.io.read.bits.tag := Mux(mshr_uses_directory_for_lb, requests.io.data.tag, request.bits.tag)
  directory.io.read.bits.cryptoLine := Mux(mshr_uses_directory_for_lb, requests.io.data.cryptoLine, request.bits.cryptoLine)

  // - prio(0) 表示这笔请求属于 A 类请求
  // - prio(1) 表示这笔请求属于 B 类请求
  // - prio(2) 表示这笔请求属于 C 类请求

  // Enqueue the request if not bypassed directly into an MSHR
  requests.io.push.valid := request.valid && queue && !bypassQueue
  requests.io.push.bits.data  := request.bits
  requests.io.push.bits.index := Mux1H(
    request.bits.prio, Seq(
      OHToUInt(lowerMatches1 << params.mshrs*0),
      OHToUInt(lowerMatches1 << params.mshrs*1),
      OHToUInt(lowerMatches1 << params.mshrs*2)))

  val mshr_insertOH = ~(leftOR(~mshr_validOH) << 1) & ~mshr_validOH & prioFilter
  (mshr_insertOH.asBools zip mshrs) map { case (s, m) =>
    when (request.valid && alloc && s && !mshr_uses_directory_assuming_no_bypass) {
      m.io.allocate.valid := true.B
      m.io.allocate.bits.viewAsSupertype(chiselTypeOf(request.bits)) := request.bits
      m.io.allocate.bits.repeat := false.B
    }
  }

  when (request.valid && nestB && !bc_mshr.io.status.valid && !c_mshr.io.status.valid && !mshr_uses_directory_assuming_no_bypass) {
    bc_mshr.io.allocate.valid := true.B
    bc_mshr.io.allocate.bits.viewAsSupertype(chiselTypeOf(request.bits)) := request.bits
    bc_mshr.io.allocate.bits.repeat := false.B
    assert (!request.bits.prio(0))
  }
  bc_mshr.io.allocate.bits.prio(0) := false.B

  when (request.valid && nestC && !c_mshr.io.status.valid && !mshr_uses_directory_assuming_no_bypass) {
    c_mshr.io.allocate.valid := true.B
    c_mshr.io.allocate.bits.viewAsSupertype(chiselTypeOf(request.bits)) := request.bits
    c_mshr.io.allocate.bits.repeat := false.B
    assert (!request.bits.prio(0))
    assert (!request.bits.prio(1))
  }
  c_mshr.io.allocate.bits.prio(0) := false.B
  c_mshr.io.allocate.bits.prio(1) := false.B

  // Fanout the result of the Directory lookup
  val dirTarget = Mux(alloc, mshr_insertOH, Mux(nestB,(BigInt(1) << (params.mshrs-2)).U,(BigInt(1) << (params.mshrs-1)).U))
  val directoryFanout = params.dirReg(RegNext(Mux(mshr_uses_directory, mshr_selectOH, Mux(alloc_uses_directory, dirTarget, 0.U))))
  mshrs.zipWithIndex.foreach { case (m, i) =>
    m.io.directory.valid := directoryFanout(i)
    m.io.directory.bits := directory.io.result.bits
  }

  // MSHR response meta-data fetch
  sinkC.io.way :=
    Mux(bc_mshr.io.status.valid && bc_mshr.io.status.bits.set === sinkC.io.set,
      bc_mshr.io.status.bits.way,
      Mux1H(abc_mshrs.map(m => m.io.status.valid && m.io.status.bits.set === sinkC.io.set),
            abc_mshrs.map(_.io.status.bits.way)))
  val sinkDSourceIdx = sinkD.io.source(log2Ceil(params.mshrs) - 1, 0)
  sinkD.io.way := VecInit(mshrs.map(_.io.status.bits.way))(sinkDSourceIdx)
  sinkD.io.set := VecInit(mshrs.map(_.io.status.bits.set))(sinkDSourceIdx)

  // Beat buffer connections between components
  sinkA.io.pb_pop <> sourceD.io.pb_pop
  sourceD.io.pb_beat := sinkA.io.pb_beat
  sinkC.io.rel_pop <> sourceD.io.rel_pop
  sourceD.io.rel_beat := sinkC.io.rel_beat

  // Test-only retirement backpressure controls for SourceD Plan 2 experiments.
  // These only gate the ready signals returning to SourceD's retirement ports.
  // val testStallSourceDRetireBsWadr = WireDefault(false.B)
  // val testStallSourceDRetireCounterWrite = WireDefault(false.B)

  // BankedStore ports
  bankedStore.io.sinkC_adr <> sinkC.io.bs_adr
  bankedStore.io.sinkC_dat := sinkC.io.bs_dat
  bankedStore.io.sinkD_adr <> sinkD.io.bs_adr
  bankedStore.io.sinkD_dat := sinkD.io.bs_dat
  bankedStore.io.sourceC_adr <> sourceC.io.bs_adr
  bankedStore.io.sourceD_radr <> sourceD.io.bs_radr
  bankedStore.io.sourceD_wadr.valid := sourceD.io.bs_wadr.valid
  bankedStore.io.sourceD_wadr.bits := sourceD.io.bs_wadr.bits
  sourceD.io.bs_wadr.ready := bankedStore.io.sourceD_wadr.ready
  bankedStore.io.sourceD_wdat := sourceD.io.bs_wdat
  sourceC.io.bs_dat := bankedStore.io.sourceC_dat
  sourceD.io.bs_rdat := bankedStore.io.sourceD_rdat
  bankedStore.io.sourceD_counterWrite.valid := sourceD.io.counter_write.valid
  bankedStore.io.sourceD_counterWrite.bits := sourceD.io.counter_write.bits
  sourceD.io.counter_write.ready :=
    bankedStore.io.sourceD_counterWrite.ready

  // Test-only sticky observation for the raw reload response used by top-level payload-bypass tests.
  val rawReloadObsSeen = RegInit(false.B)
  val rawReloadObsCount = RegInit(0.U(8.W))
  val rawReloadObsNeedPb = RegInit(false.B)
  val rawReloadObsNeedR = RegInit(false.B)
  val rawReloadObsCtrPayloadPath = RegInit(false.B)
  val rawReloadObsCtrNeedsCommitted = RegInit(false.B)
  val rawReloadObsData = RegInit(0.U(params.inner.bundle.dataBits.W))
  val rawReloadObsCounter = RegInit(0.U(params.outer.bundle.dataBits.W))
  when (sourceD.io.d.fire && sourceD.io.d.bits.source >= 4.U && sourceD.io.d.bits.source < 8.U) {
    rawReloadObsSeen := true.B
    rawReloadObsCount := rawReloadObsCount + 1.U
    // These path-classification probes need to be sourced from SourceD itself;
    // reading non-IO child internals from the parent scheduler is illegal in Chisel.
    rawReloadObsNeedPb := false.B
    rawReloadObsNeedR := false.B
    rawReloadObsCtrPayloadPath := false.B
    rawReloadObsCtrNeedsCommitted := false.B
    rawReloadObsData := sourceD.io.d.bits.data
    rawReloadObsCounter :=
      sourceD.io.d.bits.user.lift(CacheCryptoRefillMeta).map(_.counter).getOrElse(0.U)
  }

  // SourceD data hazard interlock
  sourceD.io.evict_req := sourceC.io.evict_req
  sourceD.io.counter_grant_req := sinkD.io.counter_grant_req
  sourceD.io.grant_req := sinkD  .io.grant_req
  // 方案 A 下没有单独的 counter_evict_safe：
  // old victim counter 由 SourceC 在 eviction 起点立即 snapshot，和 old victim data
  // 共享同一条 eviction 启动时机，所以统一复用 evict_safe 来保护两者起步。
  sourceC.io.evict_safe := sourceD.io.evict_safe
  sinkD.io.counter_grant_safe := sourceD.io.counter_grant_safe
  sinkD  .io.grant_safe := sourceD.io.grant_safe

  private def afmt(x: AddressSet) = s"""{"base":${x.base},"mask":${x.mask}}"""
  private def addresses = params.inner.manager.managers.flatMap(_.address).map(afmt _).mkString(",")
  private def setBits = params.addressMapping.drop(params.offsetBits).take(params.setBits).mkString(",")
  private def tagBits = params.addressMapping.drop(params.offsetBits + params.setBits).take(params.tagBits).mkString(",")
  private def simple = s""""reset":"${reset.pathName}","tagBits":[${tagBits}],"setBits":[${setBits}],"blockBytes":${params.cache.blockBytes},"ways":${params.cache.ways}"""
  def json: String = s"""{"addresses":[${addresses}],${simple},"directory":${directory.json},"subbanks":${bankedStore.json}}"""
}
