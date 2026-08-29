//******************************************************************************
// Ported from Rocket-Chip
// See LICENSE.Berkeley and LICENSE.SiFive in Rocket-Chip for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.v3.lsu

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import freechips.rocketchip.rocket._

import boom.v3.common._
import boom.v3.exu.BrUpdateInfo
import boom.v3.util.{IsKilledByBranch, GetNewBrMask, BranchKillableQueue, IsOlder, UpdateBrMask, AgePriorityEncoder, WrapInc}

class BoomDCacheReqInternal(implicit p: Parameters) extends BoomDCacheReq()(p)
  with HasL1HellaCacheParameters
{
  val req_crypto_line = Bool()
  // miss info
  val tag_match = Bool()
  val old_meta  = new L1Metadata
  val way_en    = UInt(nWays.W)
  // Replays may happen before the refilled line metadata is committed into the
  // metadata array. Carry a side-band "effective metadata" image so replay
  // handling can use the transaction-captured line mode/state instead of
  // transient resident metadata array contents.
  val replay_meta = new L1Metadata
  val replay_meta_valid = Bool()
  // Used in the MSHRs
  val sdq_id    = UInt(log2Ceil(cfg.nSDQ).W)
}

class BoomReplayHeadIO(implicit p: Parameters) extends BoomBundle()(p) {
  val valid = Output(Bool())
  val bits  = Output(new BoomDCacheReqInternal)
  val pop   = Input(Bool())
}


class BoomMSHR(implicit edge: TLEdgeOut, p: Parameters) extends BoomModule()(p)
  with HasL1HellaCacheParameters
{
  val io = IO(new Bundle {
    val id = Input(UInt())
    // DCache 本地锁存后的 cache-crypto 使能位，供 MSHR 后续按模式分支使用。
    val cacheCryptoEnable = Input(Bool())
    val log = Input(Bool())

    val req_pri_val = Input(Bool())
    val req_pri_rdy = Output(Bool())
    val req_sec_val = Input(Bool())
    val req_sec_rdy = Output(Bool())

    val clear_prefetch = Input(Bool())
    val brupdate       = Input(new BrUpdateInfo)
    val exception    = Input(Bool())
    val rob_pnr_idx  = Input(UInt(robAddrSz.W))
    val rob_head_idx = Input(UInt(robAddrSz.W))

    val req          = Input(new BoomDCacheReqInternal)
    val req_is_probe = Input(Bool())

    val idx = Output(Valid(UInt()))
    val way = Output(Valid(UInt()))
    val tag = Output(Valid(UInt()))


    val mem_acquire = Decoupled(new TLBundleA(edge.bundle))

    val mem_grant   = Flipped(Decoupled(new TLBundleD(edge.bundle)))
    val mem_finish  = Decoupled(new TLBundleE(edge.bundle))

    val prober_state = Input(Valid(UInt(coreMaxAddrBits.W)))

    val refill      = Decoupled(new L1DataWriteReq)

    val meta_write  = Decoupled(new L1MetaWriteReq)
    val meta_read   = Decoupled(new L1MetaReadReq)
    val meta_resp   = Input(Valid(new L1Metadata))
    val wb_req      = Decoupled(new WritebackReq(edge.bundle))

    // To inform the prefetcher when we are commiting the fetch of this line
    val commit_val  = Output(Bool())
    val commit_addr = Output(UInt(coreMaxAddrBits.W))
    val commit_coh  = Output(new ClientMetadata)

    // Reading from the line buffer
    val lb_read       = Decoupled(new LineBufferReadReq)
    val lb_resp       = Input(UInt(encRowBits.W))
    val lb_write      = Decoupled(new LineBufferWriteReq)

    // Replays go through the cache pipeline again
    // val replay      = Decoupled(new BoomDCacheReqInternal)
    val replay      = new BoomReplayHeadIO
    // Resp go straight out to the core
    val resp        = Decoupled(new BoomDCacheResp)

    // Writeback unit tells us when it is done processing our wb
    val wb_resp     = Input(Bool())

    val probe_rdy   = Output(Bool())
  })
  // TODO: Optimize this. We don't want to mess with cache during speculation
  // s_refill_req      : Make a request for a new cache line
  // s_refill_resp     : Store the refill response into our buffer
  // s_drain_rpq_loads : Drain out loads from the rpq
  //                   : If miss was misspeculated, go to s_invalid
  // s_wb_req          : Write back the evicted cache line
  // s_wb_resp         : Finish writing back the evicted cache line
  // s_meta_write_req  : Write the metadata for new cache lne
  // s_meta_write_resp :

  val s_invalid :: s_refill_req :: s_refill_resp :: s_drain_rpq_loads :: s_meta_read :: s_meta_resp_1 :: s_meta_resp_2 :: s_meta_clear :: s_wb_meta_read :: s_wb_req :: s_wb_resp :: s_commit_line :: s_drain_rpq :: s_meta_write_req :: s_mem_finish_1 :: s_mem_finish_2 :: s_prefetched :: s_prefetch :: Nil = Enum(18)
  val state = RegInit(s_invalid)

  val req     = Reg(new BoomDCacheReqInternal)
  val req_idx = req.addr(untagBits-1, blockOffBits)
  val req_tag = req.addr >> untagBits
  val req_block_addr = (req.addr >> blockOffBits) << blockOffBits
  val req_needs_wb = RegInit(false.B)

  val new_coh = RegInit(ClientMetadata.onReset)
  ///////////////////////////////////////////////////////////////////////
  val refill_counter = RegInit(0.U(p(CacheCryptoCounterBitsKey).W))
  val refill_crypto_line = RegInit(false.B)
  ///////////////////////////////////////////////////////////////////////
  // 旧line 需要被替换，因此这里要判断是否需要writeback，先把旧line在metdadata里的coherence状态改成 “被驱逐”状态
  val (_, shrink_param, coh_on_clear) = req.old_meta.coh.onCacheControl(M_FLUSH)
  val grow_param = new_coh.onAccess(req.uop.mem_cmd)._2
  val coh_on_grant = new_coh.onGrant(req.uop.mem_cmd, io.mem_grant.bits.param)

  //////////////////////////////////////////////////////////////////////////
  val refillCryptoMeta = io.mem_grant.bits.user.lift(CacheCryptoRefillMeta)
  val refillHasCryptoMeta = refillCryptoMeta.isDefined.B
  val refillMetaCounter = WireDefault(0.U(p(CacheCryptoCounterBitsKey).W))
  val refillMetaCryptoLine = WireDefault(false.B)
  refillCryptoMeta.foreach {
    m =>
      refillMetaCounter := m.counter
      refillMetaCryptoLine := m.cryptoLine
  }
  //////////////////////////////////////////////////////////////////////////

  // We only accept secondary misses if the original request had sufficient permissions
  val (cmd_requires_second_acquire, is_hit_again, _, dirtier_coh, dirtier_cmd) =
    new_coh.onSecondaryAccess(req.uop.mem_cmd, io.req.uop.mem_cmd)

  val (_, _, refill_done, refill_address_inc) = edge.addr_inc(io.mem_grant)
  val sec_rdy = (!cmd_requires_second_acquire && !io.req_is_probe &&
                 !state.isOneOf(s_invalid, s_meta_write_req, s_mem_finish_1, s_mem_finish_2))// Always accept secondary misses

  // rpq就是 replay queue ,是一个 挂起后等待重放的 请求队列  ，branch-killable 的请求队列，会在branch mispredict或 flush时把队列中的请求杀掉
  val rpq = Module(new BranchKillableQueue(new BoomDCacheReqInternal, cfg.nRPQ, u => u.uses_ldq, false))
  rpq.io.brupdate := io.brupdate
  rpq.io.flush  := io.exception
  assert(!(state === s_invalid && !rpq.io.empty))
  // 存储 MSHR负责的line上 ，需要等miss完成后再继续的  请求
  rpq.io.enq.valid := ((io.req_pri_val && io.req_pri_rdy) || (io.req_sec_val && io.req_sec_rdy)) && !isPrefetch(io.req.uop.mem_cmd)
  rpq.io.enq.bits  := io.req
  rpq.io.deq.ready := false.B


  val grantack = Reg(Valid(new TLBundleE(edge.bundle)))
  val refill_ctr  = Reg(UInt(log2Ceil(cacheDataBeats).W))
  val commit_line = Reg(Bool())
  val grant_had_data = Reg(Bool())
  val finish_to_prefetch = Reg(Bool())
  // Permission-only upgrades do not bring back a data beat, so refill_crypto_line
  // keeps its reset value. In that case we must preserve the resident line's mode
  // instead of accidentally rewriting metadata as a plain line.
  val committedCryptoLine = Mux(grant_had_data, refill_crypto_line, req.old_meta.cryptoLine)

  // Block probes if a tag write we started is still in the pipeline
  val meta_hazard = RegInit(0.U(2.W))
  when (meta_hazard =/= 0.U) { meta_hazard := meta_hazard + 1.U }
  when (io.meta_write.fire) { meta_hazard := 1.U }
  io.probe_rdy   := (meta_hazard === 0.U && (state.isOneOf(s_invalid, s_refill_req, s_refill_resp, s_drain_rpq_loads) || (state === s_meta_read && grantack.valid)))
  io.idx.valid := state =/= s_invalid
  io.tag.valid := state =/= s_invalid
  io.way.valid := !state.isOneOf(s_invalid, s_prefetch)
  io.idx.bits := req_idx
  io.tag.bits := req_tag
  io.way.bits := req.way_en

  io.meta_write.valid    := false.B
  io.meta_write.bits     := DontCare
  io.req_pri_rdy         := false.B
  io.req_sec_rdy         := sec_rdy && rpq.io.enq.ready
  io.mem_acquire.valid   := false.B
  io.mem_acquire.bits    := DontCare
  io.refill.valid        := false.B
  io.refill.bits         := DontCare
  io.replay.valid        := false.B
  io.replay.bits         := DontCare
  io.wb_req.valid        := false.B
  io.wb_req.bits         := DontCare
  io.resp.valid          := false.B
  io.resp.bits           := DontCare
  io.commit_val          := false.B
  io.commit_addr         := req.addr
  io.commit_coh          := coh_on_grant
  io.meta_read.valid     := false.B
  io.meta_read.bits      := DontCare
  io.mem_finish.valid    := false.B
  io.mem_finish.bits     := DontCare
  io.lb_write.valid      := false.B
  io.lb_write.bits       := DontCare
  io.lb_read.valid       := false.B
  io.lb_read.bits        := DontCare
  io.mem_grant.ready     := false.B

  val state_prev = RegNext(state, s_invalid)

  // Observational trace for the L1 MSHR owning TL source 1 in the
  // re-encryption-boundary reproducer.
  when (state =/= state_prev || io.mem_grant.fire || io.mem_finish.fire ||
      grantack.valid =/= RegNext(grantack.valid, false.B)) {
    printf(p"[L1D-MSHR-E-DIAG] id=${io.id} state=${state} prev=${state_prev} grantack_v=${grantack.valid} grantack_sink=0x${Hexadecimal(grantack.bits.sink)} mem_grant_v=${io.mem_grant.valid} mem_grant_r=${io.mem_grant.ready} mem_grant_fire=${io.mem_grant.fire} d_opcode=0x${Hexadecimal(io.mem_grant.bits.opcode)} d_source=0x${Hexadecimal(io.mem_grant.bits.source)} d_sink=0x${Hexadecimal(io.mem_grant.bits.sink)} refill_done=${refill_done} mem_finish_v=${io.mem_finish.valid} mem_finish_r=${io.mem_finish.ready} mem_finish_fire=${io.mem_finish.fire}\n")
  }

  when (io.req_sec_val && io.req_sec_rdy) {
    req.uop.mem_cmd := dirtier_cmd
    when (is_hit_again) {
      new_coh := dirtier_coh
    }
  }

  def handle_pri_req(old_state: UInt): UInt = {
    val new_state = WireInit(old_state)
    grantack.valid := false.B
    refill_ctr := 0.U
    assert(rpq.io.enq.ready)
    req := io.req
    val old_coh   = io.req.old_meta.coh
    req_needs_wb := old_coh.onCacheControl(M_FLUSH)._1 // does the line we are evicting need to be written back
    when (io.req.tag_match) {
      val (is_hit, _, coh_on_hit) = old_coh.onAccess(io.req.uop.mem_cmd)
      // 如果 tag hit且权限足够，就不refill，直接切换到drain_rpq状态；
      when (is_hit) { // set dirty bit
        assert(isWrite(io.req.uop.mem_cmd))
        new_coh     := coh_on_hit
        new_state   := s_drain_rpq
      } .otherwise { // upgrade permissions
        // 如果权限不够或者 miss，就 refill
        new_coh     := old_coh
        new_state   := s_refill_req
      }
    } .otherwise { // refill and writeback if necessary
      new_coh     := ClientMetadata.onReset
      new_state   := s_refill_req
    }
    new_state
  }

  when (state === s_invalid) {
    io.req_pri_rdy := true.B
    grant_had_data := false.B
    // 开始一个新的事物时，初始化这两个事物级寄存器
    when (io.req_pri_val && io.req_pri_rdy) {
      ///////////////////////////
      refill_counter := 0.U
      refill_crypto_line := false.B
      ///////////////////////////
      state := handle_pri_req(state)
    }
  } .elsewhen (state === s_refill_req) {
    io.mem_acquire.valid := true.B
    // TODO: Use AcquirePerm if just doing permissions acquire
    io.mem_acquire.bits  := edge.AcquireBlock(
      fromSource      = io.id,
      toAddress       = Cat(req_tag, req_idx) << blockOffBits,
      lgSize          = lgCacheBlockBytes.U,
      growPermissions = grow_param)._2
    io.mem_acquire.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter := 0.U
      u.cryptoLine := req.req_crypto_line
    }
    when (io.mem_acquire.fire) {
      state := s_refill_resp
    }
  } .elsewhen (state === s_refill_resp) {
      // 写入line buffer，暂存
      // io.mem_grant表示的是 inclusive cache 返回给 mshr的 D 通道响应
    when (edge.hasData(io.mem_grant.bits)) {
      io.mem_grant.ready      := io.lb_write.ready
      io.lb_write.valid       := io.mem_grant.valid
      io.lb_write.bits.id     := io.id
      io.lb_write.bits.offset := refill_address_inc >> rowOffBits
      io.lb_write.bits.data   := io.mem_grant.bits.data
    } .otherwise {
      io.mem_grant.ready      := true.B
    }

    when (io.mem_grant.fire) {
      grant_had_data := edge.hasData(io.mem_grant.bits)
      ////////////////////////////////////////////
      when (edge.hasData(io.mem_grant.bits)) {
        assert(refillMetaCryptoLine === req.req_crypto_line,
          "refill data beat crypto mode must match the captured request crypto mode")
        when (grant_had_data && refillMetaCryptoLine) {
          assert(refillMetaCounter === refill_counter,
            "DCache refill GrantData beats carried inconsistent counters")
        }
        refill_counter := refillMetaCounter
        refill_crypto_line := refillMetaCryptoLine
      }
      ////////////////////////////////////////////
    }
    // 如果这次只是 permission upgrade ，没有 data grant，不走 s_commit_line，从 s_refill_resp直接去s_drain_rpq
    when (refill_done) {
      grantack.valid := edge.isRequest(io.mem_grant.bits)
      grantack.bits := edge.GrantAck(io.mem_grant.bits)
      state := Mux(grant_had_data, s_drain_rpq_loads, s_drain_rpq)
      assert(!(!grant_had_data && req_needs_wb))
      when (!grant_had_data) {
        assert(req.req_crypto_line === req.old_meta.cryptoLine,
          "permission-only miss must preserve the resident line crypto mode")
      }
      commit_line := false.B
      new_coh := coh_on_grant
    }
  } .elsewhen (state === s_drain_rpq_loads) {
    // refill数据刚回来时， 先用 line buffer直接满足等待中的load
    // 当前还没有把 line-buffer fast path 真正接到 engine，
    // 因此先保守关闭 MSHR -> LSU 的直返路径，避免 data/counter 语义提前失配。
    val lineBufferFastPathEnabled = false.B
    // 只有纯 load且这次 refill带data
    // 改成，只有当 data和 counter都完整时，才允许走 fast path
    // val drain_load = (isRead(rpq.io.deq.bits.uop.mem_cmd) &&
                    //  !isWrite(rpq.io.deq.bits.uop.mem_cmd) &&
                    //  (rpq.io.deq.bits.uop.mem_cmd =/= M_XLR))
    val drain_load = Mux(io.cacheCryptoEnable, lineBufferFastPathEnabled &&
                     refill_crypto_line ,true.B) &&
                     (isRead(rpq.io.deq.bits.uop.mem_cmd) &&
                     !isWrite(rpq.io.deq.bits.uop.mem_cmd) &&
                     (rpq.io.deq.bits.uop.mem_cmd =/= M_XLR)) // LR should go through replay
    // drain all loads for now
    val rp_addr = Cat(req_tag, req_idx, rpq.io.deq.bits.addr(blockOffBits-1,0))
    val word_idx  = if (rowWords == 1) 0.U else rp_addr(log2Up(rowWords*coreDataBytes)-1, log2Up(wordBytes))
    val data      = io.lb_resp
    val data_word = data >> Cat(word_idx, 0.U(log2Up(coreDataBits).W))
    val loadgen = new LoadGen(rpq.io.deq.bits.uop.mem_size, rpq.io.deq.bits.uop.mem_signed,
      Cat(req_tag, req_idx, rpq.io.deq.bits.addr(blockOffBits-1,0)),
      data_word, false.B, wordBytes)


    rpq.io.deq.ready       := io.resp.ready && io.lb_read.ready && drain_load
    io.lb_read.valid       := rpq.io.deq.valid && drain_load
    io.lb_read.bits.id     := io.id
    io.lb_read.bits.offset := rpq.io.deq.bits.addr >> rowOffBits

    io.resp.valid     := rpq.io.deq.valid && io.lb_read.fire && drain_load
    io.resp.bits.uop  := rpq.io.deq.bits.uop
    io.resp.bits.data := loadgen.data
    io.resp.bits.is_hella := rpq.io.deq.bits.is_hella
    when (rpq.io.deq.fire) {
      commit_line   := true.B
    }
      .elsewhen (rpq.io.empty && !commit_line)
    {
      when (!rpq.io.enq.fire) {
        state := s_mem_finish_1
        finish_to_prefetch := enablePrefetching.B
      }
    } .elsewhen (rpq.io.empty || (rpq.io.deq.valid && !drain_load)) {
      // 并且再queue里面从头开始看，第一个不满足会堵塞后面所有req
      // io.commit_val is for the prefetcher. it tells the prefetcher that this line was correctly acquired
      // The prefetcher should consider fetching the next line
      io.commit_val := true.B
      state := s_meta_read
    }
  } .elsewhen (state === s_meta_read) {
      // 发起旧metadat读取
    io.meta_read.valid := !io.prober_state.valid || !grantack.valid || (io.prober_state.bits(untagBits-1,blockOffBits) =/= req_idx)
    io.meta_read.bits.idx := req_idx
    io.meta_read.bits.tag := req_tag
    io.meta_read.bits.way_en := req.way_en
    when (io.meta_read.fire) {
      state := s_meta_resp_1
    }
  } .elsewhen (state === s_meta_resp_1) {
    state := s_meta_resp_2
  } .elsewhen (state === s_meta_resp_2) {
    //  val needs_wb = io.meta_resp.bits.coh.onCacheControl(M_FLUSH)._1
    // state := Mux(!io.meta_resp.valid, s_meta_read, // Prober could have nack'd this read
            //  Mux(needs_wb, s_meta_clear, s_commit_line))
    // 上面发起的读取req的resp返回了，判断是否需要写回
    val needs_wb = io.meta_resp.bits.coh.onCacheControl(M_FLUSH)._1
    state := Mux(!io.meta_resp.valid, s_meta_read, // Prober could have nack'd this read
             Mux(io.meta_resp.bits.reenc_active, s_meta_read,
             Mux(needs_wb, s_meta_clear, s_commit_line)))
    // state := Mux( io.cacheCryptoEnable, Mux(!io.meta_resp.valid, s_meta_read, // Prober could have nack'd this read
            //  Mux(io.meta_resp.bits.reenc_active, s_meta_read,
            //  Mux(needs_wb, s_meta_clear, s_commit_line))),
                // Mux(!io.meta_resp.valid, s_meta_read, // Prober could have nack'd this read
            //  Mux(needs_wb, s_meta_clear, s_commit_line)) )
  } .elsewhen (state === s_meta_clear) {
    io.meta_write.valid         := true.B
    io.meta_write.bits.idx      := req_idx
    io.meta_write.bits.data.coh := coh_on_clear
    io.meta_write.bits.data.tag := req_tag
    io.meta_write.bits.data.reenc_active := false.B
    io.meta_write.bits.data.cryptoLine := false.B
    io.meta_write.bits.way_en   := req.way_en
    // 是否要清理 metadata
    when (io.meta_write.fire) {
      state      := s_wb_req
    }
  } .elsewhen (state === s_wb_req) {
      // 把被替换掉的旧 line写回 下层
    io.wb_req.valid          := true.B

    io.wb_req.bits.tag       := req.old_meta.tag
    io.wb_req.bits.idx       := req_idx
    io.wb_req.bits.param     := shrink_param
    io.wb_req.bits.way_en    := req.way_en
    io.wb_req.bits.source    := io.id
    io.wb_req.bits.voluntary := true.B
    io.wb_req.bits.cryptoLine := req.old_meta.cryptoLine
    when (io.wb_req.fire) {
      state := s_wb_resp
    }
  } .elsewhen (state === s_wb_resp) {
    when (io.wb_resp) {
      state := s_commit_line
    }
  } .elsewhen (state === s_commit_line) {
    // 把新line data 写入 l1 data array
    io.lb_read.valid       := true.B
    io.lb_read.bits.id     := io.id
    io.lb_read.bits.offset := refill_ctr

    io.refill.valid       := io.lb_read.fire
    io.refill.bits.addr   := req_block_addr | (refill_ctr << rowOffBits)
    io.refill.bits.way_en := req.way_en
    io.refill.bits.wmask  := ~(0.U(rowWords.W))
    io.refill.bits.data   := io.lb_resp
    io.refill.bits.counter.epoch := refill_counter
    io.refill.bits.counter.wordCtr.foreach(_ := 0.U)
    io.refill.bits.counter_wen := refill_ctr === (cacheDataBeats - 1).U
    when (io.refill.fire) {
      refill_ctr := refill_ctr + 1.U
      when (refill_ctr === (cacheDataBeats - 1).U) {
        state := s_drain_rpq
      }
    }
  } .elsewhen (state === s_drain_rpq) {
    // line安装完成后， 把剩下的请求送到 dcache pipeline replay
    // io.replay <> rpq.io.deq
    io.replay.valid := rpq.io.deq.valid
    io.replay.bits := rpq.io.deq.bits
    io.replay.bits.way_en    := req.way_en
    io.replay.bits.addr := Cat(req_tag, req_idx, rpq.io.deq.bits.addr(blockOffBits-1,0))
    rpq.io.deq.ready := io.replay.pop
    io.replay.bits.replay_meta_valid := true.B
    io.replay.bits.replay_meta.tag := req_tag
    io.replay.bits.replay_meta.coh := new_coh
    io.replay.bits.replay_meta.reenc_active := false.B
    io.replay.bits.req_crypto_line := committedCryptoLine
    io.replay.bits.replay_meta.cryptoLine := committedCryptoLine
    assert(io.replay.bits.req_crypto_line === io.replay.bits.replay_meta.cryptoLine,
      "replay request crypto mode must match replay metadata crypto mode")
    when (io.replay.pop && isWrite(rpq.io.deq.bits.uop.mem_cmd)) {
      // Set dirty bit
      val (is_hit, _, coh_on_hit) = new_coh.onAccess(rpq.io.deq.bits.uop.mem_cmd)
      assert(is_hit, "We still don't have permissions for this store")
      new_coh := coh_on_hit
    }
   
    when (rpq.io.empty && !rpq.io.enq.valid) {
      state := s_meta_write_req
    }
  } .elsewhen (state === s_meta_write_req) {
    // metadata写回的阶段
    io.meta_write.valid         := true.B
    io.meta_write.bits.idx      := req_idx
    io.meta_write.bits.data.coh := new_coh
    io.meta_write.bits.data.tag := req_tag
    io.meta_write.bits.data.reenc_active := false.B
    io.meta_write.bits.data.cryptoLine := committedCryptoLine
    io.meta_write.bits.way_en   := req.way_en
    when (io.meta_write.fire) {
      state := s_mem_finish_1
      finish_to_prefetch := false.B
    }
  } .elsewhen (state === s_mem_finish_1) {
    io.mem_finish.valid := grantack.valid
    io.mem_finish.bits  := grantack.bits
    when (io.mem_finish.fire || !grantack.valid) {
      grantack.valid := false.B
      state := s_mem_finish_2
    }
  } .elsewhen (state === s_mem_finish_2) {
    state := Mux(finish_to_prefetch, s_prefetch, s_invalid)
  } .elsewhen (state === s_prefetch) {
    io.req_pri_rdy := true.B
    when ((io.req_sec_val && !io.req_sec_rdy) || io.clear_prefetch) {
      state := s_invalid
    } .elsewhen (io.req_sec_val && io.req_sec_rdy) {
      val (is_hit, _, coh_on_hit) = new_coh.onAccess(io.req.uop.mem_cmd)
      when (is_hit) { // Proceed with refill
        new_coh := coh_on_hit
        state := s_meta_read
      } .otherwise { // Reacquire this line
        new_coh := ClientMetadata.onReset
        state := s_refill_req
      }
    } .elsewhen (io.req_pri_val && io.req_pri_rdy) {
      grant_had_data := false.B
      state := handle_pri_req(state)
    }
  }

}

class BoomIOMSHR(id: Int)(implicit edge: TLEdgeOut, p: Parameters) extends BoomModule()(p)
  with HasL1HellaCacheParameters
{
  val io = IO(new Bundle {
    val req  = Flipped(Decoupled(new BoomDCacheReq))
    val resp = Decoupled(new BoomDCacheResp)
    val mem_access = Decoupled(new TLBundleA(edge.bundle))
    val mem_ack    = Flipped(Valid(new TLBundleD(edge.bundle)))

    // We don't need brupdate in here because uncacheable operations are guaranteed non-speculative
  })

  def beatOffset(addr: UInt) = addr.extract(beatOffBits-1, wordOffBits)

  def wordFromBeat(addr: UInt, dat: UInt) = {
    val shift = Cat(beatOffset(addr), 0.U((wordOffBits+log2Ceil(wordBytes)).W))
    (dat >> shift)(wordBits-1, 0)
  }

  val req = Reg(new BoomDCacheReq)
  val grant_word = Reg(UInt(wordBits.W))

  val s_idle :: s_mem_access :: s_mem_ack :: s_resp :: Nil = Enum(4)

  val state = RegInit(s_idle)
  io.req.ready := state === s_idle

  val loadgen = new LoadGen(req.uop.mem_size, req.uop.mem_signed, req.addr, grant_word, false.B, wordBytes)

  val a_source  = id.U
  val a_address = req.addr
  val a_size    = req.uop.mem_size
  val a_data    = Fill(beatWords, req.data)

  val get      = edge.Get(a_source, a_address, a_size)._2
  val put      = edge.Put(a_source, a_address, a_size, a_data)._2
  val atomics  = if (edge.manager.anySupportLogical) {
    MuxLookup(req.uop.mem_cmd, (0.U).asTypeOf(new TLBundleA(edge.bundle)))(Array(
      M_XA_SWAP -> edge.Logical(a_source, a_address, a_size, a_data, TLAtomics.SWAP)._2,
      M_XA_XOR  -> edge.Logical(a_source, a_address, a_size, a_data, TLAtomics.XOR) ._2,
      M_XA_OR   -> edge.Logical(a_source, a_address, a_size, a_data, TLAtomics.OR)  ._2,
      M_XA_AND  -> edge.Logical(a_source, a_address, a_size, a_data, TLAtomics.AND) ._2,
      M_XA_ADD  -> edge.Arithmetic(a_source, a_address, a_size, a_data, TLAtomics.ADD)._2,
      M_XA_MIN  -> edge.Arithmetic(a_source, a_address, a_size, a_data, TLAtomics.MIN)._2,
      M_XA_MAX  -> edge.Arithmetic(a_source, a_address, a_size, a_data, TLAtomics.MAX)._2,
      M_XA_MINU -> edge.Arithmetic(a_source, a_address, a_size, a_data, TLAtomics.MINU)._2,
      M_XA_MAXU -> edge.Arithmetic(a_source, a_address, a_size, a_data, TLAtomics.MAXU)._2))
  } else {
    // If no managers support atomics, assert fail if processor asks for them
    assert(state === s_idle || !isAMO(req.uop.mem_cmd))
    (0.U).asTypeOf(new TLBundleA(edge.bundle))
  }
  assert(state === s_idle || req.uop.mem_cmd =/= M_XSC)

  io.mem_access.valid := state === s_mem_access
  io.mem_access.bits  := Mux(isAMO(req.uop.mem_cmd), atomics, Mux(isRead(req.uop.mem_cmd), get, put))

  val send_resp = isRead(req.uop.mem_cmd)

  io.resp.valid     := (state === s_resp) && send_resp
  io.resp.bits.is_hella := req.is_hella
  io.resp.bits.uop  := req.uop
  io.resp.bits.data := loadgen.data

  when (io.req.fire) {
    req   := io.req.bits
    state := s_mem_access
  }
  when (io.mem_access.fire) {
    state := s_mem_ack
  }
  when (state === s_mem_ack && io.mem_ack.valid) {
    state := s_resp
    when (isRead(req.uop.mem_cmd)) {
      grant_word := wordFromBeat(req.addr, io.mem_ack.bits.data)
    }
  }
  when (state === s_resp) {
    when (!send_resp || io.resp.fire) {
      state := s_idle
    }
  }
}

class LineBufferReadReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val id      = UInt(log2Ceil(nLBEntries).W)
  val offset  = UInt(log2Ceil(cacheDataBeats).W)
  def lb_addr = Cat(id, offset)
}

class LineBufferWriteReq(implicit p: Parameters) extends LineBufferReadReq()(p)
{
  val data   = UInt(encRowBits.W)
}

class LineBufferMetaWriteReq(implicit p: Parameters) extends BoomBundle()(p)
{
  val id   = UInt(log2Ceil(nLBEntries).W)
  val coh  = new ClientMetadata
  val addr = UInt(coreMaxAddrBits.W)
}

class LineBufferMeta(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val coh  = new ClientMetadata
  val addr = UInt(coreMaxAddrBits.W)
}

class BoomMSHRFile(implicit edge: TLEdgeOut, p: Parameters) extends BoomModule()(p)
  with HasL1HellaCacheParameters
{
  val io = IO(new Bundle {
    val req  = Flipped(Vec(memWidth, Decoupled(new BoomDCacheReqInternal))) // Req from s2 of DCache pipe
    val req_is_probe = Input(Vec(memWidth, Bool()))
    // DCache 本地锁存后的 cache-crypto 使能位，供 MSHRFile/MSHR 后续按模式分支使用。
    val cacheCryptoEnable = Input(Bool())
    val cacheCryptoLoadEnable = Input(Bool())
    val cacheCryptoStoreEnable = Input(Bool())
    val resp = Decoupled(new BoomDCacheResp)
    val secondary_miss = Output(Vec(memWidth, Bool()))
    val block_hit = Output(Vec(memWidth, Bool()))

    val brupdate       = Input(new BrUpdateInfo)
    val exception    = Input(Bool())
    val rob_pnr_idx  = Input(UInt(robAddrSz.W))
    val rob_head_idx = Input(UInt(robAddrSz.W))

    val mem_acquire  = Decoupled(new TLBundleA(edge.bundle))
    val mem_grant    = Flipped(Decoupled(new TLBundleD(edge.bundle)))
    val mem_finish   = Decoupled(new TLBundleE(edge.bundle))

    val refill     = Decoupled(new L1DataWriteReq)
    val meta_write = Decoupled(new L1MetaWriteReq)
    val meta_read  = Decoupled(new L1MetaReadReq)
    val meta_resp  = Input(Valid(new L1Metadata))
    val replay     = Decoupled(new BoomDCacheReqInternal)
    val replay_uses_crypto_protocol = Output(Bool())
    val replay_done  = Input(Bool())
    val replay_retry = Input(Bool())
    val debug_sc_fail_diag = Input(Bool())
    val debug_sc_fail_diag_cycle = Input(UInt(64.W))
    val prefetch   = Decoupled(new BoomDCacheReq)
    val wb_req     = Decoupled(new WritebackReq(edge.bundle))

    val prober_state = Input(Valid(UInt(coreMaxAddrBits.W)))

    val clear_all = Input(Bool()) // Clears all uncommitted MSHRs to prepare for fence

    val wb_resp   = Input(Bool())

    val fence_rdy = Output(Bool())
    val probe_rdy = Output(Bool())

    ////////
    val log = Input(Bool())
    ////////
  })

  val req_idx = OHToUInt(io.req.map(_.valid))
  val req     = io.req(req_idx)
  val req_is_probe = io.req_is_probe(0)
  val dbgLoopPc = req.bits.uop.debug_pc >= "h800003b0".U && req.bits.uop.debug_pc <= "h800003c4".U
  val dbgSourceBlock = (req.bits.addr >> blockOffBits) === ("h80002040".U(coreMaxAddrBits.W) >> blockOffBits)

  for (w <- 0 until memWidth)
    io.req(w).ready := false.B

  val prefetcher: DataPrefetcher = if (enablePrefetching) Module(new NLPrefetcher)
                                                     else Module(new NullPrefetcher)

  io.prefetch <> prefetcher.io.prefetch

  //////////////////////////////////////////////////////////////////////
  when (io.log) {
    printf ("Prefetcher: Address:0x%x Data:0x%x \n", io.prefetch.bits.addr, io.prefetch.bits.data)
  }
  ///////////////////////////////////////////////////////////////////////



  val cacheable = edge.manager.supportsAcquireBFast(req.bits.addr, lgCacheBlockBytes.U)

  // --------------------
  // The MSHR SDQ
  val sdq_val      = RegInit(0.U(cfg.nSDQ.W))
  val sdq_alloc_id = PriorityEncoder(~sdq_val(cfg.nSDQ-1,0))
  val sdq_rdy      = !sdq_val.andR
  val sdq_enq      = req.fire && cacheable && isWrite(req.bits.uop.mem_cmd)
  val sdq          = Mem(cfg.nSDQ, UInt(coreDataBits.W))

  when (sdq_enq) {
    sdq(sdq_alloc_id) := req.bits.data
  }

  // --------------------
  // The LineBuffer Data
  // Holds refilling lines, prefetched lines
  val lb = Mem(nLBEntries * cacheDataBeats, UInt(encRowBits.W))
  val lb_read_arb  = Module(new Arbiter(new LineBufferReadReq, cfg.nMSHRs))
  val lb_write_arb = Module(new Arbiter(new LineBufferWriteReq, cfg.nMSHRs))

  ///////////////////////////////////////////////////////////////////////
  // when (io.log)
  // {
  //   for (i <- 0 until nLBEntries*cacheDataBeats) 
  //   {  
  //     printf ("LineBufferEntry [%d] = %x \n", i.U, lb(i))
  //   }
  // }
  ////////////////////////////////////////////////////////////////////////


  lb_read_arb.io.out.ready  := false.B
  lb_write_arb.io.out.ready := true.B

  val lb_read_data = WireInit(0.U(encRowBits.W))
  when (lb_write_arb.io.out.fire) {
    lb.write(lb_write_arb.io.out.bits.lb_addr, lb_write_arb.io.out.bits.data)
    // when (io.log)
    // {
      ////////////////////////////////////////////////////////////////////////
      // printf ("Writing on LFB: Address = %x   Data = %x \n",lb_write_arb.io.out.bits.lb_addr, lb_write_arb.io.out.bits.data)  
      ////////////////////////////////////////////////////////////////////////
    // }

  } .otherwise {
    lb_read_arb.io.out.ready := true.B
    when (lb_read_arb.io.out.fire) {
      lb_read_data := lb.read(lb_read_arb.io.out.bits.lb_addr)
    }
  }
  def widthMap[T <: Data](f: Int => T) = VecInit((0 until memWidth).map(f))




  val idx_matches = Wire(Vec(memWidth, Vec(cfg.nMSHRs, Bool())))
  val tag_matches = Wire(Vec(memWidth, Vec(cfg.nMSHRs, Bool())))
  val way_matches = Wire(Vec(memWidth, Vec(cfg.nMSHRs, Bool())))

  val tag_match   = widthMap(w => Mux1H(idx_matches(w), tag_matches(w)))
  val idx_match   = widthMap(w => idx_matches(w).reduce(_||_))
  val way_match   = widthMap(w => Mux1H(idx_matches(w), way_matches(w)))

  val wb_tag_list = Wire(Vec(cfg.nMSHRs, UInt(tagBits.W)))

  val meta_write_arb = Module(new Arbiter(new L1MetaWriteReq           , cfg.nMSHRs))
  val meta_read_arb  = Module(new Arbiter(new L1MetaReadReq            , cfg.nMSHRs))
  val wb_req_arb     = Module(new Arbiter(new WritebackReq(edge.bundle), cfg.nMSHRs))
  val replay_arb     = Module(new Arbiter(new BoomDCacheReqInternal    , cfg.nMSHRs))
  val resp_arb       = Module(new Arbiter(new BoomDCacheResp           , cfg.nMSHRs + nIOMSHRs))
  val refill_arb     = Module(new Arbiter(new L1DataWriteReq           , cfg.nMSHRs))

  val commit_vals    = Wire(Vec(cfg.nMSHRs, Bool()))
  val commit_addrs   = Wire(Vec(cfg.nMSHRs, UInt(coreMaxAddrBits.W)))
  val commit_cohs    = Wire(Vec(cfg.nMSHRs, new ClientMetadata))

  var sec_rdy   = false.B

  io.fence_rdy := true.B
  io.probe_rdy := true.B
  io.mem_grant.ready := false.B
  assert(!(io.replay_done && io.replay_retry), "Replay completion protocol cannot signal done and retry together")

  val mshr_alloc_idx = Wire(UInt())
  val pri_rdy = WireInit(false.B)
  val pri_val = req.valid && sdq_rdy && cacheable && !idx_match(req_idx)
  val replay_pop = WireInit(VecInit(Seq.fill(cfg.nMSHRs) { false.B }))
  val mshrs = (0 until cfg.nMSHRs) map { i =>
    val mshr = Module(new BoomMSHR)
    mshr.io.id := i.U(log2Ceil(cfg.nMSHRs).W)
    mshr.io.cacheCryptoEnable := io.cacheCryptoEnable
    mshr.io.log := io.log

    for (w <- 0 until memWidth) {
      idx_matches(w)(i) := mshr.io.idx.valid && mshr.io.idx.bits === io.req(w).bits.addr(untagBits-1,blockOffBits)
      tag_matches(w)(i) := mshr.io.tag.valid && mshr.io.tag.bits === io.req(w).bits.addr >> untagBits
      way_matches(w)(i) := mshr.io.way.valid && mshr.io.way.bits === io.req(w).bits.way_en
    }
    wb_tag_list(i) := mshr.io.wb_req.bits.tag


    //进入owner MSHRd的条件
    mshr.io.req_pri_val  := (i.U === mshr_alloc_idx) && pri_val
    when (i.U === mshr_alloc_idx) {
      pri_rdy := mshr.io.req_pri_rdy
    }

    mshr.io.req_sec_val  := req.valid && sdq_rdy && tag_match(req_idx) && idx_matches(req_idx)(i) && cacheable
    mshr.io.req          := req.bits
    mshr.io.req_is_probe := req_is_probe
    mshr.io.req.sdq_id   := sdq_alloc_id

    // Clear because of a FENCE, a request to the same idx as a prefetched line,
    // a probe to that prefetched line, all mshrs are in use
    mshr.io.clear_prefetch := ((io.clear_all && !req.valid)||
      (req.valid && idx_matches(req_idx)(i) && cacheable && !tag_match(req_idx)) ||
      (req_is_probe && idx_matches(req_idx)(i)))
    mshr.io.brupdate       := io.brupdate
    mshr.io.exception    := io.exception
    mshr.io.rob_pnr_idx  := io.rob_pnr_idx
    mshr.io.rob_head_idx := io.rob_head_idx

    mshr.io.prober_state := io.prober_state

    mshr.io.wb_resp      := io.wb_resp

    meta_write_arb.io.in(i) <> mshr.io.meta_write
    meta_read_arb.io.in(i)  <> mshr.io.meta_read
    mshr.io.meta_resp       := io.meta_resp
    wb_req_arb.io.in(i)     <> mshr.io.wb_req
    replay_arb.io.in(i).valid := mshr.io.replay.valid
    replay_arb.io.in(i).bits  := mshr.io.replay.bits
    mshr.io.replay.pop      := replay_pop(i)
    refill_arb.io.in(i)     <> mshr.io.refill

    lb_read_arb.io.in(i)       <> mshr.io.lb_read
    mshr.io.lb_resp            := lb_read_data
    lb_write_arb.io.in(i)      <> mshr.io.lb_write

    commit_vals(i)  := mshr.io.commit_val
    commit_addrs(i) := mshr.io.commit_addr
    commit_cohs(i)  := mshr.io.commit_coh

    mshr.io.mem_grant.valid := false.B
    mshr.io.mem_grant.bits  := DontCare
    when (io.mem_grant.bits.source === i.U) {
      mshr.io.mem_grant <> io.mem_grant
    }
    when (mshr.io.mem_grant.valid) {
      printf(p"[L1D-MEMGRANT-ROUTE] id=${i} source=0x${Hexadecimal(mshr.io.mem_grant.bits.source)} sink=0x${Hexadecimal(mshr.io.mem_grant.bits.sink)} opcode=0x${Hexadecimal(mshr.io.mem_grant.bits.opcode)} ready=${mshr.io.mem_grant.ready} fire=${mshr.io.mem_grant.fire}\n")
    }

    sec_rdy   = sec_rdy || (mshr.io.req_sec_rdy && mshr.io.req_sec_val)
    resp_arb.io.in(i) <> mshr.io.resp

    when (!mshr.io.req_pri_rdy) {
      io.fence_rdy := false.B
    }
    for (w <- 0 until memWidth) {
      when (!mshr.io.probe_rdy && idx_matches(w)(i) && io.req_is_probe(w)) {
        io.probe_rdy := false.B
      }
    }

    mshr
  }

  // Try to round-robin the MSHRs
  val mshr_head      = RegInit(0.U(log2Ceil(cfg.nMSHRs).W))
  mshr_alloc_idx    := RegNext(AgePriorityEncoder(mshrs.map(m=>m.io.req_pri_rdy), mshr_head))
  when (pri_rdy && pri_val) { mshr_head := WrapInc(mshr_head, cfg.nMSHRs) }
  io.meta_write <> meta_write_arb.io.out
  io.meta_read  <> meta_read_arb.io.out
  io.wb_req     <> wb_req_arb.io.out

  val replay_src_valid = replay_arb.io.out.valid
  val replay_src_bits = replay_arb.io.out.bits
  val replay_src_cmd = replay_src_bits.uop.mem_cmd
  val replay_src_is_load_like = isRead(replay_src_cmd) && !isWrite(replay_src_cmd)
  val replay_src_is_store_like = isWrite(replay_src_cmd) || isAMO(replay_src_cmd) || replay_src_cmd === M_XSC
  val replay_src_uses_crypto_protocol = replay_src_valid &&
    replay_src_bits.req_crypto_line &&
    (replay_src_is_load_like || replay_src_is_store_like)
  val replay_outstanding_valid = RegInit(false.B)
  val replay_outstanding_src   = Reg(UInt(log2Ceil(cfg.nMSHRs).W))
  val replay_outstanding_bits  = Reg(new BoomDCacheReqInternal)

  val mmio_alloc_arb = Module(new Arbiter(Bool(), nIOMSHRs))


  var mmio_rdy = false.B

  val mmios = (0 until nIOMSHRs) map { i =>
    val id = cfg.nMSHRs + 1 + i // +1 for wb unit
    val mshr = Module(new BoomIOMSHR(id))

    mmio_alloc_arb.io.in(i).valid := mshr.io.req.ready
    mmio_alloc_arb.io.in(i).bits  := DontCare
    mshr.io.req.valid := mmio_alloc_arb.io.in(i).ready
    mshr.io.req.bits  := req.bits

    mmio_rdy = mmio_rdy || mshr.io.req.ready

    mshr.io.mem_ack.bits  := io.mem_grant.bits
    mshr.io.mem_ack.valid := io.mem_grant.valid && io.mem_grant.bits.source === id.U
    when (io.mem_grant.bits.source === id.U) {
      io.mem_grant.ready := true.B
    }

    resp_arb.io.in(cfg.nMSHRs + i) <> mshr.io.resp
    when (!mshr.io.req.ready) {
      io.fence_rdy := false.B
    }
    mshr
  }

  mmio_alloc_arb.io.out.ready := req.valid && !cacheable

  TLArbiter.lowestFromSeq(edge, io.mem_acquire, mshrs.map(_.io.mem_acquire) ++ mmios.map(_.io.mem_access))
  TLArbiter.lowestFromSeq(edge, io.mem_finish,  mshrs.map(_.io.mem_finish))

  val respq = Module(new BranchKillableQueue(new BoomDCacheResp, 4, u => u.uses_ldq, flow = false))
  respq.io.brupdate := io.brupdate
  respq.io.flush    := io.exception
  respq.io.enq      <> resp_arb.io.out
  io.resp           <> respq.io.deq

  for (w <- 0 until memWidth) {
    io.req(w).ready      := (w.U === req_idx) &&
      Mux(!cacheable, mmio_rdy, sdq_rdy && Mux(idx_match(w), tag_match(w) && sec_rdy, pri_rdy))
    io.secondary_miss(w) := idx_match(w) && way_match(w) && !tag_match(w)
    io.block_hit(w)      := idx_match(w) && tag_match(w)
  }
  io.refill         <> refill_arb.io.out

  // io.replay <> replay_arb.io.out
  io.replay.valid := replay_arb.io.out.valid && !replay_outstanding_valid
  io.replay.bits := replay_arb.io.out.bits
  io.replay.bits.data := sdq(replay_arb.io.out.bits.sdq_id)
  replay_arb.io.out.ready := io.replay.ready && !replay_outstanding_valid
  io.replay_uses_crypto_protocol := io.replay.valid && replay_src_uses_crypto_protocol

  val replay_fire = io.replay.fire
  val replay_crypto_protocol_fire = replay_fire && replay_src_uses_crypto_protocol
  val replay_direct_fire = replay_fire && !replay_src_uses_crypto_protocol
  val replay_src_fire_oh = VecInit(replay_arb.io.in.map(_.fire)).asUInt
  val replay_src_sel = OHToUInt(replay_src_fire_oh)
  val replay_protocol_done = io.replay_done && replay_outstanding_valid
  val free_sdq_direct = replay_direct_fire && isWrite(io.replay.bits.uop.mem_cmd)
  val free_sdq_protocol = replay_protocol_done && isWrite(replay_outstanding_bits.uop.mem_cmd)
  val free_sdq = free_sdq_direct || free_sdq_protocol
  val free_sdq_id = Mux(free_sdq_protocol, replay_outstanding_bits.sdq_id, io.replay.bits.sdq_id)
  when (replay_fire && (replay_src_is_load_like || replay_src_is_store_like)) {
    assert(io.replay_uses_crypto_protocol === io.replay.bits.req_crypto_line,
      "replay protocol selection must match the request-captured crypto mode")
  }

  when (replay_crypto_protocol_fire) {
    assert(!replay_outstanding_valid, "new crypto-protocol replay issued while another replay is still outstanding")
    replay_outstanding_valid := true.B
    replay_outstanding_src := replay_src_sel
    replay_outstanding_bits := io.replay.bits
    assert(io.replay.bits.req_crypto_line,
      "crypto-protocol replay must come from a request captured as crypto-line")
  }
  when ((io.replay_done || io.replay_retry) && replay_outstanding_valid) {
    replay_outstanding_valid := false.B
  }
  when (replay_direct_fire) {
    replay_pop(replay_src_sel) := true.B
  }
  when (io.replay_done && replay_outstanding_valid) {
    replay_pop(replay_outstanding_src) := true.B
  }

  when (free_sdq || sdq_enq) {
    sdq_val := sdq_val & ~(UIntToOH(free_sdq_id) & Fill(cfg.nSDQ, free_sdq)) |
      PriorityEncoderOH(~sdq_val(cfg.nSDQ-1,0)) & Fill(cfg.nSDQ, sdq_enq)
  }

  when (io.replay_done) {
    assert(replay_outstanding_valid, "replay_done should only be returned for a crypto-protocol replay")
  }
  when (io.replay_retry) {
    assert(replay_outstanding_valid, "replay_retry should only be returned for a crypto-protocol replay")
  }

  when (io.log && (io.replay.valid || io.replay.fire || io.replay_done || io.replay_retry || replay_outstanding_valid)) {
    printf("[L1D-MSHR-REPLAY-TRACE] cycle=%d replay_valid=%d replay_ready=%d replay_fire=%d protocol_done=%d replay_cmd=0x%x replay_addr=0x%x replay_crypto=%d replay_uses_crypto=%d outstanding=%d done=%d retry=%d src_valid=%d src_crypto=%d src_cmd=0x%x src_addr=0x%x sdq_val=0x%x\n",
      io.debug_sc_fail_diag_cycle,
      io.replay.valid,
      io.replay.ready,
      replay_fire,
      replay_protocol_done,
      io.replay.bits.uop.mem_cmd,
      io.replay.bits.addr,
      io.replay.bits.req_crypto_line,
      io.replay_uses_crypto_protocol,
      replay_outstanding_valid,
      io.replay_done,
      io.replay_retry,
      replay_src_valid,
      replay_src_uses_crypto_protocol,
      replay_src_cmd,
      replay_src_bits.addr,
      sdq_val)
  }

  when (io.debug_sc_fail_diag) {
    printf("[L1D-SC-FAIL-MSHR] cycle=%d req_valid=%d req_idx=%d cacheable=%d sdq_rdy=%d pri_val=%d pri_rdy=%d idx_match=%d tag_match=%d sec_rdy=%d replay_valid=%d replay_ready=%d replay_fire=%d replay_cmd=0x%x replay_addr=0x%x replay_crypto=%d replay_uses_crypto=%d replay_src_valid=%d replay_src_cmd=0x%x replay_src_addr=0x%x replay_src_crypto=%d replay_src_uses_crypto=%d replay_direct_fire=%d replay_crypto_fire=%d replay_done=%d replay_retry=%d replay_outstanding=%d outstanding_src=%d outstanding_cmd=0x%x outstanding_addr=0x%x outstanding_crypto=%d replay_protocol_done=%d free_sdq=%d sdq_val=0x%x\n",
      io.debug_sc_fail_diag_cycle,
      req.valid,
      req_idx,
      cacheable,
      sdq_rdy,
      pri_val,
      pri_rdy,
      idx_match(req_idx),
      tag_match(req_idx),
      sec_rdy,
      io.replay.valid,
      io.replay.ready,
      replay_fire,
      io.replay.bits.uop.mem_cmd,
      io.replay.bits.addr,
      io.replay.bits.req_crypto_line,
      io.replay_uses_crypto_protocol,
      replay_src_valid,
      replay_src_cmd,
      replay_src_bits.addr,
      replay_src_bits.req_crypto_line,
      replay_src_uses_crypto_protocol,
      replay_direct_fire,
      replay_crypto_protocol_fire,
      io.replay_done,
      io.replay_retry,
      replay_outstanding_valid,
      replay_outstanding_src,
      replay_outstanding_bits.uop.mem_cmd,
      replay_outstanding_bits.addr,
      replay_outstanding_bits.req_crypto_line,
      replay_protocol_done,
      free_sdq,
      sdq_val)
  }

  prefetcher.io.mshr_avail    := RegNext(pri_rdy)
  prefetcher.io.req_val       := RegNext(commit_vals.reduce(_||_))
  prefetcher.io.req_addr      := RegNext(Mux1H(commit_vals, commit_addrs))
  prefetcher.io.req_coh       := RegNext(Mux1H(commit_vals, commit_cohs))
}
