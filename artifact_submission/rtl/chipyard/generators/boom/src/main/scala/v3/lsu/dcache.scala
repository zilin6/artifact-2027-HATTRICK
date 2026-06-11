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
import midas.targetutils.{PlusArg => SynthesizedPlusArg}

import boom.v3.common._
import boom.v3.exu.BrUpdateInfo
import boom.v3.util.{IsKilledByBranch, GetNewBrMask, BranchKillableQueue, IsOlder, UpdateBrMask, AgePriorityEncoder, WrapInc, Transpose}


class BoomWritebackUnit(implicit edge: TLEdgeOut, p: Parameters) extends L1HellaCacheModule()(p) {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new WritebackReq(edge.bundle)))
    val meta_read = Decoupled(new L1MetaReadReq)
    val resp = Output(Bool())
    val idx = Output(Valid(UInt()))
    val data_req = Decoupled(new L1DataReadReq)
    // val data_resp = Input(UInt(encRowBits.W))
    ////////////////////////////////////////////
    // WBUnit在写回时自己重读 victim line；counter 也跟随这次 data array 读取一并返回，
    // 而不是通过 WritebackReq 侧带传进来。
    val data_resp = Input(new L1DataReadResp)
    /////////////////////////////////////////////
    val mem_grant = Input(Bool())
    val release = Decoupled(new TLBundleC(edge.bundle))
    val lsu_release = Decoupled(new TLBundleC(edge.bundle))
    
    ////////
    val log = Input(Bool())
    ////////
  })
  ////////////////////////////////////////////////////////////
  val cacheCryptoCounterBits = p(CacheCryptoCounterBitsKey)
  ////////////////////////////////////////////////////////////

  val req = Reg(new WritebackReq(edge.bundle))

  ////////////////////////////////////////////////////////////
  val wbCounter = RegInit(0.U(cacheCryptoCounterBits.W))
  ////////////////////////////////////////////////////////////
  val s_invalid :: s_fill_buffer :: s_lsu_release :: s_active :: s_grant :: Nil = Enum(5)
  val state = RegInit(s_invalid)
  val r1_data_req_fired = RegInit(false.B)
  val r2_data_req_fired = RegInit(false.B)
  val r1_data_req_cnt = Reg(UInt(log2Up(refillCycles+1).W))
  val r2_data_req_cnt = Reg(UInt(log2Up(refillCycles+1).W))
  val data_req_cnt = RegInit(0.U(log2Up(refillCycles+1).W))
  val (_, last_beat, all_beats_done, beat_count) = edge.count(io.release)
  val wb_buffer = Reg(Vec(refillCycles, UInt(encRowBits.W)))
  val acked = RegInit(false.B)

  io.idx.valid       := state =/= s_invalid
  io.idx.bits        := req.idx
  io.release.valid   := false.B
  io.release.bits    := DontCare
  io.req.ready       := false.B
  io.meta_read.valid := false.B
  io.meta_read.bits  := DontCare
  io.data_req.valid  := false.B
  io.data_req.bits   := DontCare
  io.resp            := false.B
  io.lsu_release.valid := false.B
  io.lsu_release.bits := DontCare

  //////////////////////////////////////////////////////////////////////////////////////////
  // Printing Write-back buffer
  // when (io.log)
  // {  
  //   printf ("Writeback Buffer: ")
  //   for (i <- 0 until refillCycles) 
  //   {
  //     printf ("WriteBackBufferEntry [%d] = %x \n", i.U, wb_buffer(i))
  //   }
  // }
//////////////////////////////////////////////////////////////////////////////////////////


  val r_address = Cat(req.tag, req.idx) << blockOffBits
  val id = cfg.nMSHRs
  val probeResponse = edge.ProbeAck(
                          fromSource = id.U,
                          toAddress = r_address,
                          lgSize = lgCacheBlockBytes.U,
                          reportPermissions = req.param,
                          data = wb_buffer(data_req_cnt))

  val voluntaryRelease = edge.Release(
                          fromSource = id.U,
                          toAddress = r_address,
                          lgSize = lgCacheBlockBytes.U,
                          shrinkPermissions = req.param,
                          data = wb_buffer(data_req_cnt))._2


  when (state === s_invalid) {
    io.req.ready := true.B
    when (io.req.fire) {
      state := s_fill_buffer
      data_req_cnt := 0.U
      req := io.req.bits
      wbCounter := 0.U
      acked := false.B
    }
  } .elsewhen (state === s_fill_buffer) {
    io.meta_read.valid := data_req_cnt < refillCycles.U
    io.meta_read.bits.idx := req.idx
    io.meta_read.bits.tag := req.tag

    io.data_req.valid := data_req_cnt < refillCycles.U
    io.data_req.bits.way_en := req.way_en
    io.data_req.bits.addr := (if(refillCycles > 1)
                              Cat(req.idx, data_req_cnt(log2Up(refillCycles)-1,0))
                            else req.idx) << rowOffBits

    r1_data_req_fired := false.B
    r1_data_req_cnt   := 0.U
    r2_data_req_fired := r1_data_req_fired
    r2_data_req_cnt   := r1_data_req_cnt
    when (io.data_req.fire && io.meta_read.fire) {
      r1_data_req_fired := true.B
      r1_data_req_cnt   := data_req_cnt
      data_req_cnt := data_req_cnt + 1.U
    }
    when (r2_data_req_fired) {
      // wb_buffer(r2_data_req_cnt) := io.data_resp
      wb_buffer(r2_data_req_cnt) := io.data_resp.data
      // The writeback counter comes from the same victim-line read as the data beats.
      wbCounter := io.data_resp.counter
      when (r2_data_req_cnt === (refillCycles-1).U) {
        io.resp := true.B
        state := s_lsu_release
        data_req_cnt := 0.U
      }
    }
  } .elsewhen (state === s_lsu_release) {
    io.lsu_release.valid := true.B
    io.lsu_release.bits := probeResponse
    // data已经放在 probeResponse中了
    io.lsu_release.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter := wbCounter
      u.cryptoLine := req.cryptoLine
    }
    when (io.lsu_release.fire) {
     state := s_active
    }
  } .elsewhen (state === s_active) {
    io.release.valid := data_req_cnt < refillCycles.U
    // data已经放在 voluntaryRelease中了
    io.release.bits := Mux(req.voluntary, voluntaryRelease, probeResponse)
    io.release.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter := wbCounter
      u.cryptoLine := req.cryptoLine
    }
    when (io.mem_grant) {
      acked := true.B
    }
    when (io.release.fire) {
      data_req_cnt := data_req_cnt + 1.U
    }
    when ((data_req_cnt === (refillCycles-1).U) && io.release.fire) {
      state := Mux(req.voluntary, s_grant, s_invalid)
    }
  } .elsewhen (state === s_grant) {
    when (io.mem_grant) {
      acked := true.B
    }
    when (acked) {
      state := s_invalid
    }
  }
}

class BoomProbeUnit(implicit edge: TLEdgeOut, p: Parameters) extends L1HellaCacheModule()(p) {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new TLBundleB(edge.bundle)))
    val rep = Decoupled(new TLBundleC(edge.bundle))
    val meta_read = Decoupled(new L1MetaReadReq)
    val meta_write = Decoupled(new L1MetaWriteReq)
    val wb_req = Decoupled(new WritebackReq(edge.bundle))
    val way_en = Input(UInt(nWays.W))
    val wb_rdy = Input(Bool()) // Is writeback unit currently busy? If so need to retry meta read when its done
    val mshr_rdy = Input(Bool()) // Is MSHR ready for this request to proceed?
    val mshr_wb_rdy = Output(Bool()) // Should we block MSHR writebacks while we finish our own?
    // val block_state = Input(new ClientMetadata())
    /////////////////////////////////////////////////////////
    // 这里改成 L1Metadata是因为
    val block_meta = Input(new L1Metadata)
    /////////////////////////////////////////////////////////
    // DCache 本地锁存后的 cache-crypto 使能位，供 ProbeUnit 后续按模式分支使用。
    val cacheCryptoEnable = Input(Bool())
    val probeBlock = Input(new BoomCacheProbeBlockIO)
    val lsu_release = Decoupled(new TLBundleC(edge.bundle))

    val state = Output(Valid(UInt(coreMaxAddrBits.W)))
  })
  private def sameProbeBlockLine(line: BoomCacheLineId): Bool = {
    req_idx === line.idx &&
    req_tag === line.tag &&
    io.way_en === line.way_en
  }

  val (s_invalid :: s_meta_read :: s_meta_resp :: s_mshr_req ::
       s_mshr_resp :: s_recheck_meta_read :: s_recheck_meta_resp :: s_recheck_req ::
       s_lsu_release :: s_release :: s_writeback_req :: s_writeback_resp ::
       s_meta_write :: s_meta_write_resp :: Nil) = Enum(14)
  val state = RegInit(s_invalid)

  val req = Reg(new TLBundleB(edge.bundle))
  val req_idx = req.address(idxMSB, idxLSB)
  val req_tag = req.address >> untagBits

  val way_en = Reg(UInt())
  val tag_matches = way_en.orR
  // 被probe的line在l1 中原来的coherence状态
  val old_coh = Reg(new ClientMetadata)
  val old_reenc_active = RegInit(false.B)
  val old_crypto_line = RegInit(false.B)
  // 如果没有命中
  val miss_coh = ClientMetadata.onReset
  val reply_coh = Mux(tag_matches, old_coh, miss_coh)
  // is_dirty 代表probe响应时，l1 是否需要叫出 data ,决定是否要writeback
  // report_param 代表 probe ack里权限的字段，决定了 probe ack是一个 simple ack 还是带权限的 ack
  // new_coh代表 probe完成后，这条line在本地的新状态，最终写回metadata
  val (is_dirty, report_param, new_coh) = reply_coh.onProbe(req.param)
  val probeBlockedByEngine =
    (io.probeBlock.incoming.valid && sameProbeBlockLine(io.probeBlock.incoming.bits)) ||
    (io.probeBlock.ingress.valid && sameProbeBlockLine(io.probeBlock.ingress.bits)) ||
    (io.probeBlock.plain.valid && sameProbeBlockLine(io.probeBlock.plain.bits)) ||
    (io.probeBlock.modify.valid && sameProbeBlockLine(io.probeBlock.modify.bits)) ||
    (io.probeBlock.result.valid && sameProbeBlockLine(io.probeBlock.result.bits)) ||
    (io.probeBlock.reenc.valid && sameProbeBlockLine(io.probeBlock.reenc.bits))

  io.state.valid := state =/= s_invalid
  io.state.bits  := req.address

  io.req.ready := state === s_invalid
  io.rep.valid := state === s_release
  io.rep.bits := edge.ProbeAck(req, report_param)
  /////////////////////////////////////////////////////////
  // 因为 probe unit.io.req发送的是不带data的 probeack，因此 需要把 counter位置0
  io.rep.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
    u.counter := 0.U
    u.cryptoLine := false.B
  }
  /////////////////////////////////////////////////////////
  assert(!io.rep.valid || !edge.hasData(io.rep.bits),
    "ProbeUnit should not send ProbeAcks with data, WritebackUnit should handle it")

  //  meta_read是发送给 dcache的，当 l2 cache发送了一个 probe，ProbeUnit需要去查询这个cache line的位置
  io.meta_read.valid := state === s_meta_read
  io.meta_read.bits.idx := req_idx
  io.meta_read.bits.tag := req_tag
  io.meta_read.bits.way_en := ~(0.U(nWays.W))
  when (state === s_recheck_meta_read) {
    io.meta_read.valid := true.B
  }

  //  meta_write是发送给 dcache的, 当probe处里完之后，如果 line还留在L1,但权限下降/evict需要写回
  //  因此需要把 line的新状态 new_coh写回metadata array
  io.meta_write.valid := state === s_meta_write
  io.meta_write.bits.way_en := way_en
  io.meta_write.bits.idx := req_idx
  io.meta_write.bits.tag := req_tag
  io.meta_write.bits.data.tag := req_tag
  io.meta_write.bits.data.coh := new_coh
  //////////////////////////////////////////
  // 如果prober unit写回 metadata直接把 reenc_activate设置为 false,那么是否会出现一种情况，就是当 这个line 的metadata进入 prober unit后，
  // 才发生了 re-enc，但是 prober unit处里完之后，又把reenc_activate设置为false,覆盖了 true的值，因此我们要做的时，如果一个cache line正在被 prober unit处理
  // 那么它不会进入 re-enc状态，如果是否允许被 load和 store呢，这需要分析现在的代码
  io.meta_write.bits.data.reenc_active := false.B
  io.meta_write.bits.data.cryptoLine := old_crypto_line
  ////////////////////////////////////////////////////
  // 经过 wbArb,发送给write back unit,所以带 data的writeback是 writebackunit去做的
  io.wb_req.valid := state === s_writeback_req
  io.wb_req.bits.source := req.source
  io.wb_req.bits.idx := req_idx
  io.wb_req.bits.tag := req_tag
  io.wb_req.bits.param := report_param
  io.wb_req.bits.way_en := way_en
  io.wb_req.bits.voluntary := false.B
  ////////////////////////////////////////////////////
  io.wb_req.bits.cryptoLine := old_crypto_line
  ////////////////////////////////////////////////////
  io.mshr_wb_rdy := !state.isOneOf(s_release, s_writeback_req, s_writeback_resp, s_meta_write, s_meta_write_resp)

  io.lsu_release.valid := state === s_lsu_release
  io.lsu_release.bits  := edge.ProbeAck(req, report_param)
  //////////////////////////////////////////////////////////////////////////
  io.lsu_release.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
    u.counter := 0.U
    u.cryptoLine := false.B
  }
  ////////////////////////////////////////////////////////////////////////////
  // state === s_invalid
  when (state === s_invalid) {
    when (io.req.fire) {
      state := s_meta_read
      req := io.req.bits
    }
  } .elsewhen (state === s_meta_read) {
    when (io.meta_read.fire) {
      state := s_meta_resp
    }
  } .elsewhen (state === s_recheck_meta_read) {
    when (io.meta_read.fire) {
      state := s_recheck_meta_resp
    }
  } .elsewhen (state === s_meta_resp) {
    // we need to wait one cycle for the metadata to be read from the array
    state := s_mshr_req
  } .elsewhen (state === s_recheck_meta_resp) {
    // Revalidate the resident line right before we make the destructive probe
    // decision so a newly-published reenc_active is observed before wb/release.
    state := s_recheck_req
  } .elsewhen (state === s_mshr_req) {
    // old_coh := io.block_state
    //////////////////////////////////////////////
    old_coh := io.block_meta.coh
    old_reenc_active := io.block_meta.reenc_active
    old_crypto_line := io.block_meta.cryptoLine
    ///////////////////////////////////////////////
    way_en := io.way_en
    // if the read didn't go through, we need to retry
    state := Mux(io.mshr_rdy && io.wb_rdy, s_mshr_resp, s_meta_read)
  } .elsewhen (state === s_mshr_resp) {
    //  state := Mux(tag_matches && is_dirty, s_writeback_req, s_lsu_release)
    // Keep native BOOM probe semantics (dirty matching line => writeback,
    // clean matching line => release) while adding one extra guard: if the
    // matching resident line is already marked reenc_active, probe must wait
    // until that transient state is published away before destructive action.
    state := Mux(tag_matches && old_reenc_active, s_meta_read,
      Mux(io.cacheCryptoEnable,
        Mux(tag_matches, s_recheck_meta_read, s_lsu_release),
        Mux(tag_matches && is_dirty, s_writeback_req, s_lsu_release)))
    // state := Mux(io.cacheCryptoEnable,
      // Mux(tag_matches && old_reenc_active, s_meta_read,
        // Mux(tag_matches, s_recheck_meta_read, s_lsu_release)),
      // Mux(tag_matches && is_dirty, s_writeback_req, s_lsu_release))
  } .elsewhen (state === s_recheck_req) {
    old_coh := io.block_meta.coh
    old_reenc_active := io.block_meta.reenc_active
    old_crypto_line := io.block_meta.cryptoLine
    way_en := io.way_en
    state := Mux(io.way_en.orR && (io.block_meta.reenc_active || probeBlockedByEngine), s_meta_read,
      Mux(io.way_en.orR && io.block_meta.coh.onProbe(req.param)._1, s_writeback_req, s_lsu_release))
  } .elsewhen (state === s_lsu_release) {
    when (io.lsu_release.fire) {
      state := s_release
    }
  } .elsewhen (state === s_release) {
    when (io.rep.ready) {
      state := Mux(tag_matches, s_meta_write, s_invalid)
    }
  } .elsewhen (state === s_writeback_req) {
    when (io.wb_req.fire) {
      // printf("[L1-PROBE-WBREQ] addr=0x%x tagMatch=%d is_dirty=%d old_crypto=%d report_param=0x%x way_en=0x%x\n",
        // req.address,
        // tag_matches,
        // is_dirty,
        // old_crypto_line,
        // report_param,
        // way_en)
      state := s_writeback_resp
    }
  } .elsewhen (state === s_writeback_resp) {
    // wait for the writeback request to finish before updating the metadata
    when (io.wb_req.ready) {
      state := s_meta_write
    }
  } .elsewhen (state === s_meta_write) {
    when (io.meta_write.fire) {
      state := s_meta_write_resp
    }
  } .elsewhen (state === s_meta_write_resp) {
    state := s_invalid
  }
}

class BoomL1MetaReadReq(implicit p: Parameters) extends BoomBundle()(p) {
  val req = Vec(memWidth, new L1MetaReadReq)
}

class BoomL1DataReadReq(implicit p: Parameters) extends BoomBundle()(p) {
  val req = Vec(memWidth, new L1DataReadReq)
  val valid = Vec(memWidth, Bool())
}

abstract class AbstractBoomDataArray(implicit p: Parameters) extends BoomModule with HasL1HellaCacheParameters {
  val io = IO(new BoomBundle {
    val read  = Input(Vec(memWidth, Valid(new L1DataReadReq)))
    val write = Input(Valid(new L1DataWriteReq))
    // val resp  = Output(Vec(memWidth, Vec(nWays, Bits(encRowBits.W))))
    // 带上了 counter
    val resp  = Output(Vec(memWidth, Vec(nWays, new L1DataReadResp)))
    val nacks = Output(Vec(memWidth, Bool()))
  })

  def pipeMap[T <: Data](f: Int => T) = VecInit((0 until memWidth).map(f))

}


// BoomDuplicatedDataArray
  // 复制整套阵列给每个读端口
  // 面积大
  // 基本没有读端口冲突
// BoomBankedDataArray
  // 把阵列切成多个 bank 共享
  // 面积更省
  // 可能出现 bank conflict，需要 nack/replay


class BoomDuplicatedDataArray(implicit p: Parameters) extends AbstractBoomDataArray
{

  val waddr = io.write.bits.addr >> rowOffBits
  val cwaddr = io.write.bits.addr >> blockOffBits
  when (io.write.valid && io.write.bits.counter_wen) {
    assert(PopCount(io.write.bits.way_en) === 1.U,
      "line counter write must select exactly one DCache way")
  }
  for (j <- 0 until memWidth) {

    val raddr = io.read(j).bits.addr >> rowOffBits
    val craddr = io.read(j).bits.addr >> blockOffBits
    for (w <- 0 until nWays) {
      // describedSRAM本体就是 一个 SyncReadMem
      val array = DescribedSRAM(
        name = s"array_${w}_${j}",
        desc = "Non-blocking DCache Data Array",
        size = nSets * refillCycles,
        data = Vec(rowWords, Bits(encDataBits.W))
      )
      val counterArray = SyncReadMem(nSets, UInt(p(CacheCryptoCounterBitsKey).W))
      when (io.write.bits.way_en(w) && io.write.valid) {
        val data = VecInit((0 until rowWords) map (i => io.write.bits.data(encDataBits*(i+1)-1,encDataBits*i)))
        array.write(waddr, data, io.write.bits.wmask.asBools)
      }
      // io.resp(j)(w) := RegNext(array.read(raddr, io.read(j).bits.way_en(w) && io.read(j).valid).asUInt)
      when (io.write.bits.way_en(w) && io.write.valid && io.write.bits.counter_wen) {
        counterArray.write(cwaddr, io.write.bits.counter)
      }
      io.resp(j)(w).data := RegNext(array.read(raddr, io.read(j).bits.way_en(w) && io.read(j).valid).asUInt)
      io.resp(j)(w).counter := RegNext(counterArray.read(craddr, io.read(j).bits.way_en(w) && io.read(j).valid))
    }
    io.nacks(j) := false.B
  }
}

class BoomBankedDataArray(implicit p: Parameters) extends AbstractBoomDataArray {

  val nBanks   = boomParams.numDCacheBanks
  val bankSize = nSets * refillCycles / nBanks
  require (nBanks >= memWidth)
  require (bankSize > 0)

  val bankBits    = log2Ceil(nBanks)
  val bankOffBits = log2Ceil(rowWords) + log2Ceil(wordBytes)
  val bidxBits    = log2Ceil(bankSize)
  val bidxOffBits = bankOffBits + bankBits

  //----------------------------------------------------------------------------------------------------

  val s0_rbanks = if (nBanks > 1) VecInit(io.read.map(r => (r.bits.addr >> bankOffBits)(bankBits-1,0))) else VecInit(0.U)
  val s0_wbank  = if (nBanks > 1) (io.write.bits.addr >> bankOffBits)(bankBits-1,0) else 0.U
  val s0_ridxs  = VecInit(io.read.map(r => (r.bits.addr >> bidxOffBits)(bidxBits-1,0)))
  val s0_widx   = (io.write.bits.addr >> bidxOffBits)(bidxBits-1,0)
  ///////////////////////////////////////////////////////////////////////////////
  // 计算每个读请求要去读哪一个set的line counter
  val s0_counter_ridxs = VecInit(io.read.map(r => (r.bits.addr >> blockOffBits)(idxBits-1,0)))
  // 计算当前写请求要去写哪一个set的line counter
  val s0_counter_widx = (io.write.bits.addr >> blockOffBits)(idxBits-1,0)
  when (io.write.valid && io.write.bits.counter_wen) {
    assert(PopCount(io.write.bits.way_en) === 1.U,
      "line counter write must select exactly one DCache way")
  }
  ///////////////////////////////////////////////////////////////////////////////

  val s0_read_valids    = VecInit(io.read.map(_.valid))
  val s0_bank_conflicts = pipeMap(w => (0 until w).foldLeft(false.B)((c,i) => c || io.read(i).valid && s0_rbanks(i) === s0_rbanks(w)))
  val s0_do_bank_read   = s0_read_valids zip s0_bank_conflicts map {case (v,c) => v && !c}
  val s0_bank_read_gnts = Transpose(VecInit(s0_rbanks zip s0_do_bank_read map {case (b,d) => VecInit((UIntToOH(b) & Fill(nBanks,d)).asBools)}))
  val s0_bank_write_gnt = (UIntToOH(s0_wbank) & Fill(nBanks, io.write.valid)).asBools

  //----------------------------------------------------------------------------------------------------

  val s1_rbanks         = RegNext(s0_rbanks)
  val s1_ridxs          = RegNext(s0_ridxs)
  val s1_read_valids    = RegNext(s0_read_valids)
  val s1_pipe_selection = pipeMap(i => VecInit(PriorityEncoderOH(pipeMap(j =>
                            if (j < i) s1_read_valids(j) && s1_rbanks(j) === s1_rbanks(i)
                            else if (j == i) true.B else false.B))))
  val s1_ridx_match     = pipeMap(i => pipeMap(j => if (j < i) s1_ridxs(j) === s1_ridxs(i)
                                                    else if (j == i) true.B else false.B))
  val s1_nacks          = pipeMap(w => s1_read_valids(w) && (s1_pipe_selection(w).asUInt & ~s1_ridx_match(w).asUInt).orR)
  val s1_bank_selection = pipeMap(w => Mux1H(s1_pipe_selection(w), s1_rbanks))

  //----------------------------------------------------------------------------------------------------

  val s2_bank_selection = RegNext(s1_bank_selection)
  val s2_nacks          = RegNext(s1_nacks)

  for (w <- 0 until nWays) {
    val s2_bank_reads = Reg(Vec(nBanks, Bits(encRowBits.W)))
    ////////////////////////////////////////////////////////////////////////////
    val counterArrays = Seq.fill(memWidth)(SyncReadMem(nSets, UInt(p(CacheCryptoCounterBitsKey).W)))
    val s2_counter_reads = Wire(Vec(memWidth, UInt(p(CacheCryptoCounterBitsKey).W)))
    ////////////////////////////////////////////////////////////////////////////
    for (b <- 0 until nBanks) {
      val array = DescribedSRAM(
        name = s"array_${w}_${b}",
        desc = "Non-blocking DCache Data Array",
        size = bankSize,
        data = Vec(rowWords, Bits(encDataBits.W))
      )
      val ridx = Mux1H(s0_bank_read_gnts(b), s0_ridxs)
      val way_en = Mux1H(s0_bank_read_gnts(b), io.read.map(_.bits.way_en))
      s2_bank_reads(b) := array.read(ridx, way_en(w) && s0_bank_read_gnts(b).reduce(_||_)).asUInt

      when (io.write.bits.way_en(w) && s0_bank_write_gnt(b)) {
        val data = VecInit((0 until rowWords) map (i => io.write.bits.data(encDataBits*(i+1)-1,encDataBits*i)))
        array.write(s0_widx, data, io.write.bits.wmask.asBools)
      }
    }

    for (i <- 0 until memWidth) {
      // io.resp(i)(w) := s2_bank_reads(s2_bank_selection(i))
      ////////////////////////////////////////////////
      when (io.write.bits.way_en(w) && io.write.valid && io.write.bits.counter_wen) {
        counterArrays(i).write(s0_counter_widx, io.write.bits.counter)
      }
      // s2_bank_reads是一个显式的寄存器
      s2_counter_reads(i) := RegNext(counterArrays(i).read(s0_counter_ridxs(i), io.read(i).bits.way_en(w) && io.read(i).valid))
     
    }

    for (i <- 0 until memWidth) {
      io.resp(i)(w).data := s2_bank_reads(s2_bank_selection(i))
      io.resp(i)(w).counter := s2_counter_reads(i) 
      ////////////////////////////////////////////////
    }
  }

  io.nacks := s2_nacks
}

/**
 * Top level class wrapping a non-blocking dcache.
 *
 * @param hartid hardware thread for the cache
 */
class BoomNonBlockingDCache(staticIdForMetadataUseOnly: Int)(implicit p: Parameters) extends LazyModule
{
  private val tileParams = p(TileKey)
  protected val cfg = tileParams.dcache.get

  protected def cacheClientParameters = cfg.scratch.map(x => Seq()).getOrElse(Seq(TLMasterParameters.v1(
    name          = s"Core ${staticIdForMetadataUseOnly} DCache",
    sourceId      = IdRange(0, 1 max (cfg.nMSHRs + 1)),
    supportsProbe = TransferSizes(cfg.blockBytes, cfg.blockBytes))))

  protected def mmioClientParameters = Seq(TLMasterParameters.v1(
    name          = s"Core ${staticIdForMetadataUseOnly} DCache MMIO",
    sourceId      = IdRange(cfg.nMSHRs + 1, cfg.nMSHRs + 1 + cfg.nMMIOs),
    requestFifo   = true))

  val node = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = cacheClientParameters ++ mmioClientParameters,
    minLatency = 1,
    requestFields = Seq(CacheCryptoWritebackMetaField(
      counterBits = p(CacheCryptoCounterBitsKey))),
    responseKeys = Seq(CacheCryptoRefillMeta))))


  lazy val module = new BoomNonBlockingDCacheModule(this)

  def flushOnFenceI = cfg.scratch.isEmpty && !node.edges.out(0).manager.managers.forall(m => !m.supportsAcquireT || !m.executable || m.regionType >= RegionType.TRACKED || m.regionType <= RegionType.IDEMPOTENT)

  require(!tileParams.core.haveCFlush || cfg.scratch.isEmpty, "CFLUSH_D_L1 instruction requires a D$")
}


class BoomDCacheBundle(implicit p: Parameters, edge: TLEdgeOut) extends BoomBundle()(p) {
  val lsu   = Flipped(new LSUDMemIO)
  val dataKey = Input(UInt(128.W))
  val cacheCryptoLoadEnableValue = Input(Bool())
  val cacheCryptoStoreEnableValue = Input(Bool())
  val cacheCryptoEnableWen = Input(Bool())
  val cacheCryptoCounterBaseValue = Input(UInt(64.W))
  val cacheCryptoCounterBaseWen = Input(Bool())
  //////////
  val log = Input(Bool())
  //////////
}

class BoomNonBlockingDCacheModule(outer: BoomNonBlockingDCache) extends LazyModuleImp(outer)
  with HasL1HellaCacheParameters
  with HasBoomCoreParameters
{
  implicit val edge = outer.node.edges.out(0)
  val (tl_out, _) = outer.node.out(0)
  val io = IO(new BoomDCacheBundle)
  val engine = Module(new BoomCacheCryptoEngine)
  val dcacheCryptoAssertEnableReader = Module(new plusarg_reader(
    "dcache_crypto_assert_enable=%d",
    0,
    "Enable synthesized DCache crypto assert/watchdog checks",
    1))
  SynthesizedPlusArg(dcacheCryptoAssertEnableReader)
  val dcacheCryptoAssertEnable = dcacheCryptoAssertEnableReader.io.out =/= 0.U
  private val dcacheCryptoDebugLogEnable = CacheCryptoDebugLog.runtimeEnable
  private val dcacheDebugCycle = RegInit(0.U(64.W))
  dcacheDebugCycle := dcacheDebugCycle + 1.U
  private val dcacheVerboseLogEnable = false.B

  // L1D locally latches the cache-crypto runtime configuration so later users do not
  // depend on a long direct CSR fanout path.
  val cacheCryptoLoadEnableReg = RegInit(false.B)
  val cacheCryptoStoreEnableReg = RegInit(false.B)
  val cacheCryptoEnableReg = cacheCryptoLoadEnableReg || cacheCryptoStoreEnableReg
  val cacheCryptoCounterBaseReg = RegInit(0.U(64.W))
  when (io.cacheCryptoEnableWen) {
    cacheCryptoLoadEnableReg := io.cacheCryptoLoadEnableValue
    cacheCryptoStoreEnableReg := io.cacheCryptoStoreEnableValue
  }
  when (io.cacheCryptoCounterBaseWen) {
    cacheCryptoCounterBaseReg := io.cacheCryptoCounterBaseValue
  }

  engine.io.counterBaseAddress := cacheCryptoCounterBaseReg
  engine.io.cryptoAssertEnable := dcacheCryptoAssertEnable
  engine.io.dataKey := io.dataKey
  engine.io.loadCryptoEnable := cacheCryptoLoadEnableReg
  engine.io.storeCryptoEnable := cacheCryptoStoreEnableReg
  engine.io.brupdate := io.lsu.brupdate
  engine.io.exception := io.lsu.exception

  private val fifoManagers = edge.manager.managers.filter(TLFIFOFixer.allVolatile)
  fifoManagers.foreach { m =>
    require (m.fifoId == fifoManagers.head.fifoId,
      s"IOMSHRs must be FIFO for all regions with effects, but HellaCache sees ${m.nodePath.map(_.name)}")
  }

  def widthMap[T <: Data](f: Int => T) = VecInit((0 until memWidth).map(f))
  val t_replay :: t_probe :: t_wb :: t_mshr_meta_read :: t_lsu :: t_prefetch :: Nil = Enum(6)
  private def reqIsStoreLike(req: BoomDCacheReq): Bool = {
    isWrite(req.uop.mem_cmd) || isAMO(req.uop.mem_cmd)
  }
  private def reqIsPureLoad(req: BoomDCacheReq): Bool = {
    isRead(req.uop.mem_cmd) && !isWrite(req.uop.mem_cmd)
  }
  private def reqCryptoForType(reqType: UInt, req: BoomDCacheReq): Bool = {
    Mux(req.is_hella, false.B,
      Mux(reqType === t_prefetch, cacheCryptoLoadEnableReg,
      Mux(reqType.isOneOf(t_probe, t_wb, t_mshr_meta_read), false.B,
        Mux(reqIsStoreLike(req), cacheCryptoStoreEnableReg,
          Mux(reqIsPureLoad(req), cacheCryptoLoadEnableReg, false.B)))))
  }

  val wb = Module(new BoomWritebackUnit)

  /////////
  wb.io.log := io.log
  /////////

  val prober = Module(new BoomProbeUnit)
  val mshrs = Module(new BoomMSHRFile)
  mshrs.io.replay_done := false.B
  mshrs.io.replay_retry := false.B
  engine.io.debugScFailDiag := false.B
  engine.io.debugScFailDiagCycle := dcacheDebugCycle
  mshrs.io.debug_sc_fail_diag := false.B
  mshrs.io.debug_sc_fail_diag_cycle := dcacheDebugCycle
  prober.io.cacheCryptoEnable := cacheCryptoEnableReg
  prober.io.probeBlock := engine.io.probeBlock
  mshrs.io.cacheCryptoEnable := cacheCryptoEnableReg
  mshrs.io.cacheCryptoLoadEnable := cacheCryptoLoadEnableReg
  mshrs.io.cacheCryptoStoreEnable := cacheCryptoStoreEnableReg
  mshrs.io.clear_all    := io.lsu.force_order
  mshrs.io.brupdate       := io.lsu.brupdate
  mshrs.io.exception    := io.lsu.exception
  mshrs.io.rob_pnr_idx  := io.lsu.rob_pnr_idx
  mshrs.io.rob_head_idx := io.lsu.rob_head_idx

  ///////
  mshrs.io.log := io.log
  ///////

  // tags
  def onReset = L1Metadata(0.U, ClientMetadata.onReset)
  val meta = Seq.fill(memWidth) { Module(new L1MetadataArray(onReset _)) }
  val metaWriteArb = Module(new Arbiter(new L1MetaWriteReq, 3))
  // 0 goes to engine, 1 goes to MSHR refills, 2 goes to prober
  val metaReadArb = Module(new Arbiter(new BoomL1MetaReadReq, 7))
  // 0 goes to MSHR replays, 1 goes to prober, 2 goes to wb, 3 goes to MSHR meta read,
  // 4 goes to pipeline, 5 goes to prefetcher, 6 goes to engine

  metaReadArb.io.in := DontCare
  for (w <- 0 until memWidth) {
    meta(w).io.write.valid := metaWriteArb.io.out.fire
    meta(w).io.write.bits  := metaWriteArb.io.out.bits
    meta(w).io.read.valid  := metaReadArb.io.out.valid
    meta(w).io.read.bits   := metaReadArb.io.out.bits.req(w)
  }
  metaReadArb.io.out.ready  := meta.map(_.io.read.ready).reduce(_||_)
  metaWriteArb.io.out.ready := meta.map(_.io.write.ready).reduce(_||_)

  // data
  val data = Module(if (boomParams.numDCacheBanks == 1) new BoomDuplicatedDataArray else new BoomBankedDataArray)
  val dataWriteArb = Module(new Arbiter(new L1DataWriteReq, 3))
  // 0 goes to engine, 1 goes to pipeline, 2 goes to MSHR refills
  val dataReadArb = Module(new Arbiter(new BoomL1DataReadReq, 3))
  // 0 goes to MSHR replays, 1 goes to wb, 2 goes to pipeline.
  // Engine re-encrypt data reads bypass this arbiter so phaseDec cannot be
  // starved by a steady stream of replay/pipeline requests.
  dataReadArb.io.in := DontCare

  data.io.write.valid := dataWriteArb.io.out.fire
  data.io.write.bits  := dataWriteArb.io.out.bits
  dataWriteArb.io.out.ready := true.B
  val debugWatchDataWriteLineIdx = BigInt("3f", 16).U((dataWriteArb.io.out.bits.addr.getWidth - blockOffBits).W)
  ///////////////////////////////////////////////////////////////////////////////////
  // --------------------------------------------------------------------------
  // Engine side-band service hooks
  // --------------------------------------------------------------------------
  private val engineChunkBytes = xLen / 8
  private val engineLineChunkCount = cacheBlockBytes / engineChunkBytes
  private val engineChunkIdxBits = (log2Ceil(engineLineChunkCount) max 1)
  private val engineRowsPerLine = cacheBlockBytes / rowBytes
  private val engineRowInLineBits = (log2Ceil(engineRowsPerLine) max 1)
  // Separate the true row-word selection bits from the debug/register width.
  // When rowWords == 1, the true selector width must stay 0; otherwise adjacent
  // chunks alias onto the same row/word slot.
  private val engineWordSelBits = log2Ceil(rowWords)
  private val engineWordIdxBits = (engineWordSelBits max 1)
  private def engineWordIdx(chunk: UInt): UInt = {
    if (rowWords == 1) 0.U(engineWordIdxBits.W) else chunk(engineWordSelBits - 1, 0)
  }
  private def engineRowInLine(chunk: UInt): UInt = {
    if (engineRowsPerLine == 1) 0.U(engineRowInLineBits.W)
    else if (engineWordSelBits == 0) chunk(engineChunkIdxBits - 1, 0)
    else chunk(engineChunkIdxBits - 1, engineWordSelBits)
  }
  private def engineRowAddr(idx: UInt, chunk: UInt): UInt = {
    Cat(idx, engineRowInLine(chunk)) << rowOffBits
  }

  engine.io.hitReq.valid := false.B
  engine.io.hitReq.bits := 0.U.asTypeOf(new BoomCacheEngineHitReq)
  engine.io.hitReqIsStore := false.B
  engine.io.loadResp.ready := false.B
  engine.io.storeResp.ready := false.B

  engine.io.svc.meta_resp.valid := false.B
  engine.io.svc.meta_resp.bits := 0.U.asTypeOf(new L1Metadata)
  engine.io.svc.data_resp.valid := false.B
  engine.io.svc.data_resp.bits := 0.U.asTypeOf(new BoomCacheEngineDataReadResp)

  // engine metadata read
  metaReadArb.io.in(6).valid := engine.io.svc.meta_read.valid
  metaReadArb.io.in(6).bits := 0.U.asTypeOf(new BoomL1MetaReadReq)
  metaReadArb.io.in(6).bits.req(0).idx := engine.io.svc.meta_read.bits.line.idx
  metaReadArb.io.in(6).bits.req(0).tag := engine.io.svc.meta_read.bits.line.tag
  metaReadArb.io.in(6).bits.req(0).way_en := engine.io.svc.meta_read.bits.line.way_en
  engine.io.svc.meta_read.ready := metaReadArb.io.in(6).ready

  // 因为 读取 metadata不是同步读取的 要花费一个 cycle,dcache中也是 s0发送 metadata read s1使用meta.io.resp
  val engineMetaReadFire = metaReadArb.io.in(6).fire
  val engineMetaWayEn = RegEnable(engine.io.svc.meta_read.bits.line.way_en, engineMetaReadFire)
  val engineMetaRespValid = RegNext(engineMetaReadFire, init=false.B)
  engine.io.svc.meta_resp.valid := engineMetaRespValid
  // 从 多路way响应中选中目标 way的metadata
  engine.io.svc.meta_resp.bits := Mux1H(engineMetaWayEn, meta(0).io.resp.toSeq)

  // engine metadata write: preserve the original metadata image from the engine and only
  // let the engine override the fields it intentionally changed before handing it here.
  metaWriteArb.io.in(0).valid := engine.io.svc.meta_write.valid
  metaWriteArb.io.in(0).bits := 0.U.asTypeOf(new L1MetaWriteReq)
  metaWriteArb.io.in(0).bits.idx := engine.io.svc.meta_write.bits.line.idx
  metaWriteArb.io.in(0).bits.tag := engine.io.svc.meta_write.bits.line.tag
  metaWriteArb.io.in(0).bits.way_en := engine.io.svc.meta_write.bits.line.way_en
  metaWriteArb.io.in(0).bits.data := engine.io.svc.meta_write.bits.data
  engine.io.svc.meta_write.ready := metaWriteArb.io.in(0).ready
  when (dcacheCryptoDebugLogEnable && metaWriteArb.io.in(0).fire) {
    printf("[L1D-CRYPTO-SVC-META-WRITE] cycle=%d idx=0x%x tag=0x%x way=0x%x crypto=%d reenc=%d\n",
      dcacheDebugCycle,
      engine.io.svc.meta_write.bits.line.idx,
      engine.io.svc.meta_write.bits.line.tag,
      engine.io.svc.meta_write.bits.line.way_en,
      engine.io.svc.meta_write.bits.data.cryptoLine,
      engine.io.svc.meta_write.bits.data.reenc_active)
  }

  // engine data read
  val engineDataReadRowAddr = engineRowAddr(engine.io.svc.data_read.bits.line.idx, engine.io.svc.data_read.bits.chunk)
  val engineDataReadRowIdx = engineDataReadRowAddr >> rowOffBits
  val engineDataReadWordIdx = engineWordIdx(engine.io.svc.data_read.bits.chunk)
  val engineDataReadReq = Wire(new BoomL1DataReadReq)
  engineDataReadReq := 0.U.asTypeOf(new BoomL1DataReadReq)
  engineDataReadReq.valid := widthMap(w => (w == 0).B)
  engineDataReadReq.req(0).addr := engineDataReadRowAddr
  engineDataReadReq.req(0).way_en := engine.io.svc.data_read.bits.line.way_en
  val engineDataReadOverride = engine.io.svc.data_read.valid

  for (w <- 0 until memWidth) {
    data.io.read(w).valid := Mux(engineDataReadOverride,
      engineDataReadReq.valid(w),
      dataReadArb.io.out.bits.valid(w) && dataReadArb.io.out.valid)
    data.io.read(w).bits := Mux(engineDataReadOverride,
      engineDataReadReq.req(w),
      dataReadArb.io.out.bits.req(w))
  }
  dataReadArb.io.out.ready := !engineDataReadOverride
  engine.io.svc.data_read.ready := true.B

  val engineDataReadFire = engine.io.svc.data_read.fire

  val engineDataWayEnS1 = RegEnable(engine.io.svc.data_read.bits.line.way_en, engineDataReadFire)
  val engineDataRespChunkS1 = RegEnable(engine.io.svc.data_read.bits.chunk, engineDataReadFire)

 
  val engineDataWordIdxRegS1 = RegEnable(engineDataReadWordIdx, engineDataReadFire)
  val engineDataRespValidS1 = RegNext(engineDataReadFire, init=false.B)
  val engineDataWayEn = RegNext(engineDataWayEnS1)
  val engineDataRespChunk = RegNext(engineDataRespChunkS1)



  val engineDataWordIdxReg = RegNext(engineDataWordIdxRegS1)
  val engineDataRespValid = RegNext(engineDataRespValidS1, init=false.B)
    // 从 多路way响应中选中目标 way的data
  val engineDataLine = Mux1H(engineDataWayEn, data.io.resp(0).toSeq)
  val engineDataWord = engineDataLine.data >> Cat(engineDataWordIdxReg, 0.U(log2Ceil(coreDataBits).W))
  engine.io.svc.data_resp.valid := engineDataRespValid
  engine.io.svc.data_resp.bits.chunk := engineDataRespChunk
  engine.io.svc.data_resp.bits.data := engineDataWord(xLen-1, 0)
  engine.io.svc.data_resp.bits.counter := engineDataLine.counter
  when (dcacheCryptoDebugLogEnable && engineDataReadFire) {
    printf("[L1D-CRYPTO-SVC-DATA-READ] cycle=%d idx=0x%x tag=0x%x way=0x%x chunk=%d row=0x%x word=%d\n",
      dcacheDebugCycle,
      engine.io.svc.data_read.bits.line.idx,
      engine.io.svc.data_read.bits.line.tag,
      engine.io.svc.data_read.bits.line.way_en,
      engine.io.svc.data_read.bits.chunk,
      engineDataReadRowIdx,
      engineDataReadWordIdx)
  }
  when (dcacheCryptoDebugLogEnable && engineDataRespValid) {
    printf("[L1D-CRYPTO-SVC-DATA-RESP] cycle=%d chunk=%d way=0x%x counter=0x%x data=0x%x\n",
      dcacheDebugCycle,
      engineDataRespChunk,
      engineDataWayEn,
      engineDataLine.counter,
      engineDataWord(xLen-1, 0))
  }
  when (engineDataReadFire) {
    assert(PopCount(engine.io.svc.data_read.bits.line.way_en) === 1.U,
      "Engine data_read must select exactly one way")
  }

  // engine data write
  val engineDataWriteAddr = engineRowAddr(engine.io.svc.data_write.bits.line.idx, engine.io.svc.data_write.bits.chunk)
  val engineDataWriteRowIdx = engineDataWriteAddr >> rowOffBits
  val engineDataWriteWidx = engineWordIdx(engine.io.svc.data_write.bits.chunk)
  val engineDataWriteWmask = if (rowWords == 1) 1.U(1.W) else UIntToOH(engineDataWriteWidx, rowWords)
  val enginePrevWriteChunk = RegInit(0.U(engineChunkIdxBits.W))
  val engineWriteSeqSeen = RegInit(false.B)
  val engineWriteSeqOk = !engineWriteSeqSeen ||
    (engine.io.svc.data_write.bits.chunk === 0.U) ||
    (engine.io.svc.data_write.bits.chunk === enginePrevWriteChunk + 1.U)
  dataWriteArb.io.in(0).valid := engine.io.svc.data_write.valid
  dataWriteArb.io.in(0).bits := 0.U.asTypeOf(new L1DataWriteReq)
  dataWriteArb.io.in(0).bits.addr := engineDataWriteAddr
  dataWriteArb.io.in(0).bits.wmask := engineDataWriteWmask
  dataWriteArb.io.in(0).bits.data := Fill(rowWords, engine.io.svc.data_write.bits.data)
  dataWriteArb.io.in(0).bits.counter := engine.io.svc.data_write.bits.counter
  dataWriteArb.io.in(0).bits.counter_wen := engine.io.svc.data_write.bits.counter_wen
  dataWriteArb.io.in(0).bits.way_en := engine.io.svc.data_write.bits.line.way_en
  engine.io.svc.data_write.ready := dataWriteArb.io.in(0).ready
  when (dataWriteArb.io.in(0).fire) {
    when (dcacheCryptoDebugLogEnable) {
      printf("[L1D-CRYPTO-SVC-DATA-WRITE] cycle=%d idx=0x%x tag=0x%x way=0x%x chunk=%d row=0x%x word=%d counter=0x%x counter_wen=%d data=0x%x\n",
        dcacheDebugCycle,
        engine.io.svc.data_write.bits.line.idx,
        engine.io.svc.data_write.bits.line.tag,
        engine.io.svc.data_write.bits.line.way_en,
        engine.io.svc.data_write.bits.chunk,
        engineDataWriteRowIdx,
        engineDataWriteWidx,
        engine.io.svc.data_write.bits.counter,
        engine.io.svc.data_write.bits.counter_wen,
        engine.io.svc.data_write.bits.data)
    }
    assert(PopCount(engine.io.svc.data_write.bits.line.way_en) === 1.U,
      "Engine data_write must select exactly one way")
    assert(PopCount(engineDataWriteWmask) === 1.U,
      "Engine data_write must select exactly one row word")
    enginePrevWriteChunk := engine.io.svc.data_write.bits.chunk
    engineWriteSeqSeen := true.B
  }
  //////////////////////////////////////////////////////////////////////
  // ------------
  // New requests

  // 同时发起metadata read和 data read
  io.lsu.req.ready := metaReadArb.io.in(4).ready && dataReadArb.io.in(2).ready
  metaReadArb.io.in(4).valid := io.lsu.req.valid
  dataReadArb.io.in(2).valid := io.lsu.req.valid
  for (w <- 0 until memWidth) {
    // Tag read for new requests
    metaReadArb.io.in(4).bits.req(w).idx    := io.lsu.req.bits(w).bits.addr >> blockOffBits
    metaReadArb.io.in(4).bits.req(w).way_en := DontCare
    metaReadArb.io.in(4).bits.req(w).tag    := DontCare
    // Data read for new requests
    dataReadArb.io.in(2).bits.valid(w)      := io.lsu.req.bits(w).valid
    dataReadArb.io.in(2).bits.req(w).addr   := io.lsu.req.bits(w).bits.addr
    dataReadArb.io.in(2).bits.req(w).way_en := ~0.U(nWays.W)
  }

  // ------------
  // MSHR Replays
  val replay_req = Wire(Vec(memWidth, new BoomDCacheReq))

  replay_req               := DontCare
  replay_req(0).uop        := mshrs.io.replay.bits.uop
  replay_req(0).addr       := mshrs.io.replay.bits.addr
  replay_req(0).data       := mshrs.io.replay.bits.data
  replay_req(0).is_hella   := mshrs.io.replay.bits.is_hella
  mshrs.io.replay.ready    := metaReadArb.io.in(0).ready && dataReadArb.io.in(0).ready
  // Tag read for MSHR replays
  // We don't actually need to read the metadata, for replays we already know our way
  metaReadArb.io.in(0).valid              := mshrs.io.replay.valid
  metaReadArb.io.in(0).bits.req(0).idx    := mshrs.io.replay.bits.addr >> blockOffBits
  metaReadArb.io.in(0).bits.req(0).way_en := DontCare
  metaReadArb.io.in(0).bits.req(0).tag    := DontCare
  // Data read for MSHR replays
  dataReadArb.io.in(0).valid              := mshrs.io.replay.valid
  dataReadArb.io.in(0).bits.req(0).addr   := mshrs.io.replay.bits.addr
  dataReadArb.io.in(0).bits.req(0).way_en := mshrs.io.replay.bits.way_en
  dataReadArb.io.in(0).bits.valid         := widthMap(w => (w == 0).B)

  // -----------
  // MSHR Meta read
  val mshr_read_req = Wire(Vec(memWidth, new BoomDCacheReq))
  mshr_read_req             := DontCare
  mshr_read_req(0).uop      := NullMicroOp
  mshr_read_req(0).addr     := Cat(mshrs.io.meta_read.bits.tag, mshrs.io.meta_read.bits.idx) << blockOffBits
  mshr_read_req(0).data     := DontCare
  mshr_read_req(0).is_hella := false.B
  metaReadArb.io.in(3).valid       := mshrs.io.meta_read.valid
  metaReadArb.io.in(3).bits.req(0) := mshrs.io.meta_read.bits
  mshrs.io.meta_read.ready         := metaReadArb.io.in(3).ready



  // -----------
  // Write-backs
  val wb_fire = wb.io.meta_read.fire && wb.io.data_req.fire
  val wb_req = Wire(Vec(memWidth, new BoomDCacheReq))
  wb_req             := DontCare
  wb_req(0).uop      := NullMicroOp
  wb_req(0).addr     := Cat(wb.io.meta_read.bits.tag, wb.io.data_req.bits.addr)
  wb_req(0).data     := DontCare
  wb_req(0).is_hella := false.B
  // Couple the two decoupled interfaces of the WBUnit's meta_read and data_read
  // Tag read for write-back
  metaReadArb.io.in(2).valid        := wb.io.meta_read.valid
  metaReadArb.io.in(2).bits.req(0)  := wb.io.meta_read.bits
  wb.io.meta_read.ready := metaReadArb.io.in(2).ready && dataReadArb.io.in(1).ready
  // Data read for write-back
  dataReadArb.io.in(1).valid        := wb.io.data_req.valid
  dataReadArb.io.in(1).bits.req(0)  := wb.io.data_req.bits
  dataReadArb.io.in(1).bits.valid   := widthMap(w => (w == 0).B)
  wb.io.data_req.ready  := metaReadArb.io.in(2).ready && dataReadArb.io.in(1).ready
  assert(!(wb.io.meta_read.fire ^ wb.io.data_req.fire))

  // -------
  // Prober
  val prober_fire  = prober.io.meta_read.fire
  val prober_req   = Wire(Vec(memWidth, new BoomDCacheReq))
  prober_req             := DontCare
  prober_req(0).uop      := NullMicroOp
  prober_req(0).addr     := Cat(prober.io.meta_read.bits.tag, prober.io.meta_read.bits.idx) << blockOffBits
  prober_req(0).data     := DontCare
  prober_req(0).is_hella := false.B
  // Tag read for prober
  metaReadArb.io.in(1).valid       := prober.io.meta_read.valid
  metaReadArb.io.in(1).bits.req(0) := prober.io.meta_read.bits
  prober.io.meta_read.ready := metaReadArb.io.in(1).ready
  // Prober does not need to read data array

  // -------
  // Prefetcher
  val prefetch_fire = mshrs.io.prefetch.fire
  val prefetch_req  = Wire(Vec(memWidth, new BoomDCacheReq))
  prefetch_req    := DontCare
  prefetch_req(0) := mshrs.io.prefetch.bits
  // Tag read for prefetch
  metaReadArb.io.in(5).valid              := mshrs.io.prefetch.valid
  metaReadArb.io.in(5).bits.req(0).idx    := mshrs.io.prefetch.bits.addr >> blockOffBits
  metaReadArb.io.in(5).bits.req(0).way_en := DontCare
  metaReadArb.io.in(5).bits.req(0).tag    := DontCare
  mshrs.io.prefetch.ready := metaReadArb.io.in(5).ready
  // Prefetch does not need to read data array

  val s0_valid = Mux(io.lsu.req.fire, VecInit(io.lsu.req.bits.map(_.valid)),
                 Mux(mshrs.io.replay.fire || wb_fire || prober_fire || prefetch_fire || mshrs.io.meta_read.fire,
                                        VecInit(1.U(memWidth.W).asBools), VecInit(0.U(memWidth.W).asBools)))
  val s0_req   = Mux(io.lsu.req.fire        , VecInit(io.lsu.req.bits.map(_.bits)),
                 Mux(wb_fire                  , wb_req,
                 Mux(prober_fire              , prober_req,
                 Mux(prefetch_fire            , prefetch_req,
                 Mux(mshrs.io.meta_read.fire, mshr_read_req
                                              , replay_req)))))
  val s0_req_crypto_locked = widthMap(w =>
    Mux(io.lsu.req.fire && io.lsu.req.bits(w).valid,
      reqCryptoForType(t_lsu, io.lsu.req.bits(w).bits),
      Mux(prefetch_fire && (w == 0).B,
        reqCryptoForType(t_prefetch, prefetch_req(0)),
        false.B)))
  // 这里是原版写法
  val s0_type  = Mux(io.lsu.req.fire        , t_lsu,
                 Mux(wb_fire                  , t_wb,
                 Mux(prober_fire              , t_probe,
                 Mux(prefetch_fire            , t_prefetch,
                 Mux(mshrs.io.meta_read.fire, t_mshr_meta_read
                                              , t_replay)))))

  // Does this request need to send a response or nack
  val s0_send_resp_or_nack = Mux(io.lsu.req.fire, s0_valid,
    VecInit(Mux(mshrs.io.replay.fire && isRead(mshrs.io.replay.bits.uop.mem_cmd), 1.U(memWidth.W), 0.U(memWidth.W)).asBools))
  val replayIssuedS0 = mshrs.io.replay.fire
  val replayProtocolIssuedS0 = mshrs.io.replay.fire && mshrs.io.replay_uses_crypto_protocol
  when (mshrs.io.replay.fire) {
  }


  val s1_req          = RegNext(s0_req)
  val s1_req_crypto_locked = RegNext(s0_req_crypto_locked, VecInit(Seq.fill(memWidth)(false.B)))
  for (w <- 0 until memWidth)
    s1_req(w).uop.br_mask := GetNewBrMask(io.lsu.brupdate, s0_req(w).uop)
  val replayIssuedS1 = RegNext(replayIssuedS0, init=false.B)
  val replayProtocolIssuedS1 = RegNext(replayProtocolIssuedS0, init=false.B)
  val s2_store_failed = Wire(Bool())
  val s1_valid = widthMap(w =>
                 RegNext(s0_valid(w)                                     &&
                         !IsKilledByBranch(io.lsu.brupdate, s0_req(w).uop) &&
                         !(io.lsu.exception && s0_req(w).uop.uses_ldq)   &&
                         !(s2_store_failed && io.lsu.req.fire && s0_req(w).uop.uses_stq),
                         init=false.B))
  for (w <- 0 until memWidth)
    assert(!(io.lsu.s1_kill(w) && !RegNext(io.lsu.req.fire) && !RegNext(io.lsu.req.bits(w).valid)))
  val s1_addr         = s1_req.map(_.addr)
  // 如果 probeUnit还没有处理完某个 set，且 s1阶段的请求也达到这个set，那么这个req(load和store)先nack
  // a(idxMSB,idxLSB) 计算set index
  //  prober.io.meta_write.bits.idx是 probeUnit正在处理的 set 
  // !prober.io.req.ready 表示 probeUnit还没有处理完这个 set
  val s1_nack         = s1_addr.map(a => a(idxMSB,idxLSB) === prober.io.meta_write.bits.idx && !prober.io.req.ready)
  val s1_send_resp_or_nack = RegNext(s0_send_resp_or_nack)
  val s1_type         = RegNext(s0_type)

  val s1_mshr_meta_read_way_en = RegNext(mshrs.io.meta_read.bits.way_en)
  val s1_replay_way_en         = RegNext(mshrs.io.replay.bits.way_en) // For replays, the metadata isn't written yet
  val s1_replay_req_crypto_line = RegNext(mshrs.io.replay.bits.req_crypto_line, init=false.B)
  val s1_replay_meta = Reg(new L1Metadata)
  val s1_replay_meta_valid = RegInit(false.B)
  when (mshrs.io.replay.fire) {
    s1_replay_meta := mshrs.io.replay.bits.replay_meta
    s1_replay_meta_valid := mshrs.io.replay.bits.replay_meta_valid
  } .otherwise {
    s1_replay_meta_valid := false.B
  }
  val s1_wb_way_en             = RegNext(wb.io.data_req.bits.way_en)

  // tag check
  def wayMap[T <: Data](f: Int => T) = VecInit((0 until nWays).map(f))
  val s1_tag_eq_way = widthMap(i => wayMap((w: Int) => meta(i).io.resp(w).tag === (s1_addr(i) >> untagBits)).asUInt)
  val s1_tag_match_way = widthMap(i =>
                         Mux(s1_type === t_replay, s1_replay_way_en,
                         Mux(s1_type === t_wb,     s1_wb_way_en,
                         Mux(s1_type === t_mshr_meta_read, s1_mshr_meta_read_way_en,
                           wayMap((w: Int) => s1_tag_eq_way(i)(w) && meta(i).io.resp(w).coh.isValid()).asUInt))))

  val s1_wb_idx_matches = widthMap(i => (s1_addr(i)(untagBits-1,blockOffBits) === wb.io.idx.bits) && wb.io.idx.valid)

  val s2_req   = RegNext(s1_req)
  val s2_req_crypto_locked = RegNext(s1_req_crypto_locked, VecInit(Seq.fill(memWidth)(false.B)))
  val s2_type  = RegNext(s1_type)
  val replayIssuedS2 = RegNext(replayIssuedS1, init=false.B)
  val replayProtocolIssuedS2 = RegNext(replayProtocolIssuedS1, init=false.B)
  val s2_valid = widthMap(w =>
                  RegNext(s1_valid(w) &&
                         !io.lsu.s1_kill(w) &&
                         !IsKilledByBranch(io.lsu.brupdate, s1_req(w).uop) &&
                         !(io.lsu.exception && s1_req(w).uop.uses_ldq) &&
                         !(s2_store_failed && (s1_type === t_lsu) && s1_req(w).uop.uses_stq)))
  for (w <- 0 until memWidth)
    s2_req(w).uop.br_mask := GetNewBrMask(io.lsu.brupdate, s1_req(w).uop)

  val s2_tag_match_way = RegNext(s1_tag_match_way)
  val s2_tag_match     = s2_tag_match_way.map(_.orR)
  val s2_replay_req_crypto_line = RegNext(s1_replay_req_crypto_line, init=false.B)
  val s2_replay_meta = Reg(new L1Metadata)
  val s2_replay_meta_valid = RegInit(false.B)
  when (replayIssuedS1) {
    s2_replay_meta := s1_replay_meta
    s2_replay_meta_valid := s1_replay_meta_valid
  } .otherwise {
    s2_replay_meta_valid := false.B
  }

  val s2_hit_meta = widthMap(i => Mux1H(s2_tag_match_way(i), wayMap((w: Int) => RegNext(meta(i).io.resp(w))).toSeq))
  val s2_effective_hit_meta = widthMap(i =>
    Mux((s2_type === t_replay) && (i == 0).B && s2_replay_req_crypto_line && s2_replay_meta_valid,
      s2_replay_meta,
      s2_hit_meta(i)))
  val s2_hit_state = widthMap(i => s2_effective_hit_meta(i).coh)
  val s2_hit_reenc_active = widthMap(i => s2_effective_hit_meta(i).reenc_active)
  val s2_effective_req_crypto = widthMap(w =>
    Mux((s2_type === t_replay) && (w == 0).B, s2_replay_req_crypto_line, s2_req_crypto_locked(w)))
  // 在 L1 DCache 的 s2 阶段，这笔请求虽然按地址/tag 命中了某条 resident line，
  // 但如果当前请求期望的 cache mode 与 resident line 的 cryptoLine 不一致，
  // 这次就不能按普通 hit 处理，而要强制把这条 old line 当作 victim 原地替换掉。
  val s2_force_mode_replace = widthMap(w => s2_valid(w) &&
    s2_type.isOneOf(t_lsu, t_prefetch) &&
    s2_tag_match(w) &&
    (s2_effective_hit_meta(w).cryptoLine =/= s2_effective_req_crypto(w)))
  //////////////////////////////////////////////////////////////////////////////
  // 这里只先考虑普通 load/store hit。
  // 对普通 load hit 而言，line 本身的 coherence / metadata 状态不应该被这次访问修改；
  // 因此只有当 onAccess 推导出的新状态和旧状态一致时，才允许它走普通 hit 路径。
  // 如果一次访问会导致 line 状态变化（例如需要权限升级），就不再按普通 hit 处理，
  // 而是转去 MSHR 路径。
  val s2_has_permission = widthMap(w => s2_hit_state(w).onAccess(s2_req(w).uop.mem_cmd)._1)
  val s2_new_hit_state  = widthMap(w => s2_hit_state(w).onAccess(s2_req(w).uop.mem_cmd)._3)
  ///////////////////////////////////////////////////////////////
  val s2_reenc_hit_block = widthMap(w => s2_valid(w) &&
    s2_type.isOneOf(t_lsu, t_prefetch) &&
    s2_tag_match(w) &&
    s2_hit_reenc_active(w))
  // 只要当前有一个MSHR正在持有这条 line, Dcache就不会把新的 lsu req当作普通 hit 直接放行
  // val s2_hit = widthMap(w => (s2_tag_match(w) && s2_has_permission(w) && s2_hit_state(w) === s2_new_hit_state(w) && !mshrs.io.block_hit(w)) || s2_type.isOneOf(t_replay, t_wb))
  val s2_hit = widthMap(w => (s2_tag_match(w) &&
                              // 如果这个cache line正在被重加密，那么新的访问就算命中也不能直接放行，必须等重加密完成后才能放行
                              !s2_reenc_hit_block(w) &&
                              !s2_force_mode_replace(w) &&
                              s2_has_permission(w) &&
                              s2_hit_state(w) === s2_new_hit_state(w) &&
                              !mshrs.io.block_hit(w)) || s2_type.isOneOf(t_replay, t_wb))
  /////////////////////////////////////////////////////////////
  val s2_nack = Wire(Vec(memWidth, Bool()))
  assert(!(s2_type === t_replay && !s2_hit(0)), "Replays should always hit")
  assert(!(s2_type === t_wb && !s2_hit(0)), "Writeback should always see data hit")

  val s2_wb_idx_matches = RegNext(s1_wb_idx_matches)

  // lr/sc
  val debug_sc_fail_addr = RegInit(0.U)
  val debug_sc_fail_cnt  = RegInit(0.U(8.W))

  val lrsc_count = RegInit(0.U(log2Ceil(lrscCycles).W))
  val lrsc_valid = lrsc_count > lrscBackoff.U
  val lrsc_addr  = Reg(UInt())
  // 用于识别 sc和 lr  
  val s2_lr = s2_req(0).uop.mem_cmd === M_XLR && (!RegNext(s1_nack(0)) || s2_type === t_replay)
  val s2_sc = s2_req(0).uop.mem_cmd === M_XSC && (!RegNext(s1_nack(0)) || s2_type === t_replay)
  val s2_crypto_reservation_selected = WireDefault(false.B)
  val s2_lrsc_addr_match = widthMap(w => lrsc_valid && lrsc_addr === (s2_req(w).addr >> blockOffBits))
  val s2_sc_fail = s2_sc && !s2_lrsc_addr_match(0)
  val debug_sc_line_match = lrsc_addr === (s2_req(0).addr >> blockOffBits)
  val debug_lrsc_update_ok = s2_valid(0) && ((s2_type === t_lsu && s2_hit(0) && !s2_nack(0)) ||
                     (s2_type === t_replay && s2_req(0).uop.mem_cmd =/= M_FLUSH_ALL)) &&
                     (!s2_effective_req_crypto(0) || s2_crypto_reservation_selected)
  val debug_lrsc_set = debug_lrsc_update_ok && s2_lr
  val debug_lrsc_clear_by_access = debug_lrsc_update_ok && (lrsc_count > 0.U)
  val debug_lrsc_clear_by_miss = widthMap(w => s2_valid(w) &&
    s2_type === t_lsu &&
    !s2_hit(w) &&
    !(s2_has_permission(w) && s2_tag_match(w)) &&
    s2_lrsc_addr_match(w) &&
    !s2_nack(w))
  val debug_lrsc_clear_by_miss_any = debug_lrsc_clear_by_miss.reduce(_||_)

  val debug_last_lrsc_event = RegInit(0.U(3.W)) // 0 none, 1 set, 2 clear-access, 3 clear-miss
  val debug_last_lrsc_cycle = RegInit(0.U(64.W))
  val debug_last_lrsc_addr  = RegInit(0.U(coreMaxAddrBits.W))
  val debug_last_lrsc_pc    = RegInit(0.U(s2_req(0).uop.debug_pc.getWidth.W))
  val debug_last_lrsc_count = RegInit(0.U(log2Ceil(lrscCycles).W))
  val debug_last_lrsc_type  = RegInit(0.U(3.W))
  val debug_last_lrsc_cmd   = RegInit(0.U(5.W))
  val debug_last_lrsc_hit   = RegInit(false.B)
  val debug_last_lrsc_nack  = RegInit(false.B)
  val debug_last_lrsc_req_crypto = RegInit(false.B)
  val debug_last_lrsc_reservation_selected = RegInit(false.B)
  val debug_last_lrsc_replay_req_crypto = RegInit(false.B)
  val debug_last_lrsc_replay_uses_crypto = RegInit(false.B)
  val debug_last_lrsc_engine_load_ready = RegInit(false.B)
  val debug_last_lrsc_engine_store_ready = RegInit(false.B)

  when (debug_lrsc_set || debug_lrsc_clear_by_access || debug_lrsc_clear_by_miss_any) {
    debug_last_lrsc_event := Mux(debug_lrsc_set, 1.U,
                              Mux(debug_lrsc_clear_by_miss_any, 3.U, 2.U))
    debug_last_lrsc_cycle := dcacheDebugCycle
    debug_last_lrsc_addr  := s2_req(0).addr
    debug_last_lrsc_pc    := s2_req(0).uop.debug_pc
    debug_last_lrsc_count := lrsc_count
    debug_last_lrsc_type  := s2_type
    debug_last_lrsc_cmd   := s2_req(0).uop.mem_cmd
    debug_last_lrsc_hit   := s2_hit(0)
    debug_last_lrsc_nack  := s2_nack(0)
    debug_last_lrsc_req_crypto := s2_effective_req_crypto(0)
    debug_last_lrsc_reservation_selected := s2_crypto_reservation_selected
    debug_last_lrsc_replay_req_crypto := s2_replay_req_crypto_line
    debug_last_lrsc_replay_uses_crypto := mshrs.io.replay_uses_crypto_protocol
    debug_last_lrsc_engine_load_ready := engine.io.loadReady
    debug_last_lrsc_engine_store_ready := engine.io.storeReady
  }

  when (lrsc_count > 0.U) { lrsc_count := lrsc_count - 1.U }
  when (s2_valid(0) && ((s2_type === t_lsu && s2_hit(0) && !s2_nack(0)) ||
                     (s2_type === t_replay && s2_req(0).uop.mem_cmd =/= M_FLUSH_ALL)) &&
                     (!s2_effective_req_crypto(0) || s2_crypto_reservation_selected)) {
    when (s2_lr) {
      lrsc_count := (lrscCycles - 1).U
      lrsc_addr := s2_req(0).addr >> blockOffBits
    }
    when (lrsc_count > 0.U) {
      lrsc_count := 0.U
    }
  }
  for (w <- 0 until memWidth) {
    when (s2_valid(w)                            &&
      s2_type === t_lsu                          &&
      !s2_hit(w)                                 &&
      !(s2_has_permission(w) && s2_tag_match(w)) &&
      s2_lrsc_addr_match(w)                      &&
      !s2_nack(w)) {
      lrsc_count := 0.U
    }
  }

  when (s2_valid(0)) {
    when (s2_req(0).addr === debug_sc_fail_addr) {
      when (s2_sc_fail) {
        debug_sc_fail_cnt := debug_sc_fail_cnt + 1.U
      } .elsewhen (s2_sc) {
        debug_sc_fail_cnt := 0.U
      }
    } .otherwise {
      when (s2_sc_fail) {
        debug_sc_fail_addr := s2_req(0).addr
        debug_sc_fail_cnt  := 1.U
      }
    }
  }
  val debug_sc_fail_diag_fire = (debug_sc_fail_cnt >= 95.U) || (s2_valid(0) && s2_sc_fail && debug_sc_fail_cnt >= 90.U)
  engine.io.debugScFailDiag := debug_sc_fail_diag_fire
  mshrs.io.debug_sc_fail_diag := debug_sc_fail_diag_fire
  when (debug_sc_fail_diag_fire) {
    printf("[L1D-SC-FAIL-DIAG] cycle=%d fail_cnt=%d fail_addr=0x%x cur_valid=%d cur_pc=0x%x cur_cmd=0x%x cur_addr=0x%x cur_line=0x%x type=%d hit=%d tag_match=%d has_perm=%d nack=%d lr=%d sc=%d sc_fail=%d lrsc_valid=%d line_match=%d lrsc_count=%d lrsc_addr=0x%x req_crypto=%d reservation_selected=%d engine_load_ready=%d engine_store_ready=%d replay_valid=%d replay_ready=%d replay_crypto_protocol=%d s2_replay_req_crypto=%d last_event=%d last_cycle=%d last_addr=0x%x last_pc=0x%x last_cmd=0x%x last_type=%d last_count=%d last_hit=%d last_nack=%d last_req_crypto=%d last_reservation_selected=%d last_replay_req_crypto=%d last_replay_uses_crypto=%d last_engine_load_ready=%d last_engine_store_ready=%d\n",
      dcacheDebugCycle,
      debug_sc_fail_cnt,
      debug_sc_fail_addr,
      s2_valid(0),
      s2_req(0).uop.debug_pc,
      s2_req(0).uop.mem_cmd,
      s2_req(0).addr,
      s2_req(0).addr >> blockOffBits,
      s2_type,
      s2_hit(0),
      s2_tag_match(0),
      s2_has_permission(0),
      s2_nack(0),
      s2_lr,
      s2_sc,
      s2_sc_fail,
      lrsc_valid,
      debug_sc_line_match,
      lrsc_count,
      lrsc_addr,
      s2_effective_req_crypto(0),
      s2_crypto_reservation_selected,
      engine.io.loadReady,
      engine.io.storeReady,
      mshrs.io.replay.valid,
      metaReadArb.io.in(0).ready && dataReadArb.io.in(0).ready,
      mshrs.io.replay_uses_crypto_protocol,
      s2_replay_req_crypto_line,
      debug_last_lrsc_event,
      debug_last_lrsc_cycle,
      debug_last_lrsc_addr,
      debug_last_lrsc_pc,
      debug_last_lrsc_cmd,
      debug_last_lrsc_type,
      debug_last_lrsc_count,
      debug_last_lrsc_hit,
      debug_last_lrsc_nack,
      debug_last_lrsc_req_crypto,
      debug_last_lrsc_reservation_selected,
      debug_last_lrsc_replay_req_crypto,
      debug_last_lrsc_replay_uses_crypto,
      debug_last_lrsc_engine_load_ready,
      debug_last_lrsc_engine_store_ready)
  }
  assert(debug_sc_fail_cnt < 100.U, "L1DCache failed too many SCs in a row")

  ///////////////////////////////////////////////////////////////
  // val s2_data = Wire(Vec(memWidth, Vec(nWays, UInt(encRowBits.W))))
  val s2_data = Wire(Vec(memWidth, Vec(nWays, new L1DataReadResp)))
  ///////////////////////////////////////////////////////////////
  for (i <- 0 until memWidth) {
    for (w <- 0 until nWays) {
      s2_data(i)(w) := data.io.resp(i)(w)
    }
  }
  // val s2_data_muxed = widthMap(w => Mux1H(s2_tag_match_way(w), s2_data(w)))
  val s2_data_muxed = widthMap(w => Mux1H(s2_tag_match_way(w), s2_data(w).map(_.data)))
  val s2_counter_muxed = widthMap(w => Mux1H(s2_tag_match_way(w), s2_data(w).map(_.counter)))
  val s2_word_idx   = widthMap(w => if (rowWords == 1) 0.U else s2_req(w).addr(log2Up(rowWords*wordBytes)-1, log2Up(wordBytes)))

  // replacement policy
  //  因为现在的替换策略是随机
  val replacer = cacheParams.replacement
  val s1_replaced_way_en = UIntToOH(replacer.way)
  val s2_replaced_way_en = UIntToOH(RegNext(replacer.way))
  val s2_repl_meta = widthMap(i => Mux1H(s2_replaced_way_en, wayMap((w: Int) => RegNext(meta(i).io.resp(w))).toSeq))
  //////////////////////////////////////////////////////
  // 发生miss, victim line的1counter
  val s2_repl_counter = widthMap(i => Mux1H(s2_replaced_way_en, s2_data(i).map(_.counter)))
  //////////////////////////////////////////////////////
  // nack because of incoming probe
  val s2_nack_hit    = RegNext(VecInit(s1_nack))
  // Nack when we hit something currently being evicted
  val s2_nack_victim = widthMap(w => s2_valid(w) &&  s2_hit(w) && mshrs.io.secondary_miss(w))
  // MSHRs not ready for request
  val s2_nack_miss   = widthMap(w => s2_valid(w) && !s2_hit(w) && !mshrs.io.req(w).ready)
  // Bank conflict on data arrays
  val s2_nack_data   = widthMap(w => data.io.nacks(w))
  // Can't allocate MSHR for same set currently being written back
  val s2_nack_wb     = widthMap(w => s2_valid(w) && !s2_hit(w) && s2_wb_idx_matches(w))


  ////////////////////////////////////////////////////////////////
  // 如果这个请求访问的 cache line正在被重加密，那么这个miss也要nack掉，等重加密完成后再重试
  val s2_nack_reenc_victim = widthMap(w => s2_valid(w) &&
    s2_type === t_lsu &&
    !s2_tag_match(w) &&
    s2_repl_meta(w).reenc_active)
  //  cache line hit，但是这个 cache line 正在重加密，所以 nack
  val s2_nack_reenc_hit = widthMap(w => s2_valid(w) &&
    s2_type === t_lsu &&
    s2_tag_match(w) &&
    s2_hit_reenc_active(w))
  // engine 入口这一拍是否可接收一个新的 hit/replay 请求。
  // 注意这里既会影响 LSU 请求，也会影响 replay 请求：
  // - 对 LSU：如果 engine 不可用，后面会通过 s2_nack_engine 触发 nack/retry
  // - 对 replay：不走 LSU 的 nack 语义，而是靠 replay hold / RPQ backpressure 保证不丢
  val s2_engine_store_available = engine.io.storeReady
  val s2_engine_load_available = engine.io.loadReady
  val s2_replay_killed =
    s2_valid(0) &&
    s2_type === t_replay &&
    (IsKilledByBranch(io.lsu.brupdate, s2_req(0).uop) ||
      (io.lsu.exception && s2_req(0).uop.uses_ldq))
  val s2_replay_plain_store_candidate = s2_replay_req_crypto_line &&
    s2_valid(0) &&
     !s2_replay_killed &&
    s2_hit(0) &&
    s2_type === t_replay &&
    isWrite(s2_req(0).uop.mem_cmd) &&
    !isRead(s2_req(0).uop.mem_cmd) &&
    !isAMO(s2_req(0).uop.mem_cmd) &&
    !s2_sc
  val s2_replay_atomic_candidate = s2_replay_req_crypto_line &&
    s2_valid(0) &&
     !s2_replay_killed &&
    s2_hit(0) &&
    s2_type === t_replay &&
    (isAMO(s2_req(0).uop.mem_cmd) || (s2_sc && !s2_sc_fail))
  val s2_replay_storelike_candidate = s2_replay_plain_store_candidate || s2_replay_atomic_candidate
  val s2_replay_load_candidate = s2_replay_req_crypto_line &&
    s2_valid(0) &&
     !s2_replay_killed &&
    s2_hit(0) &&
    s2_type === t_replay &&
    isRead(s2_req(0).uop.mem_cmd) &&
    !isWrite(s2_req(0).uop.mem_cmd) &&
    !s2_hit_reenc_active(0)
  val s2_replay_any_candidate = s2_replay_storelike_candidate || s2_replay_load_candidate
  when (s2_type === t_replay && s2_valid(0) && s2_replay_req_crypto_line) {
    assert(s2_replay_meta_valid,
      "crypto replay reaching s2 must carry side-band replay metadata")
    assert(s2_effective_hit_meta(0).cryptoLine,
      "crypto replay reaching s2 must observe crypto-line effective metadata")
  }
  // replay 不走 LSU 的 nack/retry 机制。修改后 replay 的原件保留在 RPQ 里，
  // 这里的选择只针对当前已经发进 DCache pipeline 的 replay 副本。
  val s2_replay_fresh_store_selected =
    s2_replay_storelike_candidate && s2_engine_store_available
  val s2_replay_fresh_load_selected =
    s2_replay_load_candidate && s2_engine_load_available
  val s2_replay_fresh_selected = s2_replay_fresh_store_selected || s2_replay_fresh_load_selected
  val s2_replay_engine_selected = s2_replay_fresh_selected
  assert(!(s2_type === t_replay && !s2_replay_req_crypto_line && s2_replay_engine_selected),
    "plain replay must not be selected into the crypto engine")
  val s2_lsu_engine_block = widthMap(w =>
    s2_nack_hit(w) ||
    s2_nack_victim(w) ||
    s2_nack_data(w) ||
    s2_nack_wb(w) ||
    s2_nack_reenc_victim(w) ||
    s2_nack_reenc_hit(w))
  // 普通 LSU store hit 是 engine 候选。
  val s2_engine_store_candidate = s2_valid(0) &&
    cacheCryptoStoreEnableReg &&
    s2_effective_req_crypto(0) &&
    s2_hit(0) &&
    s2_type === t_lsu &&
    !s2_lsu_engine_block(0) &&
    isWrite(s2_req(0).uop.mem_cmd) &&
    !isRead(s2_req(0).uop.mem_cmd) &&
    !isAMO(s2_req(0).uop.mem_cmd) &&
    !s2_sc
  // Cached AMO/SC success under cache-crypto also needs the store-like engine path:
  // decrypt old word -> AMO/merge in plaintext -> re-encrypt new word -> update counter.
  val s2_engine_atomic_candidate = s2_valid(0) &&
    cacheCryptoStoreEnableReg &&
    s2_effective_req_crypto(0) &&
    s2_hit(0) &&
    s2_type === t_lsu &&
    !s2_lsu_engine_block(0) &&
    (isAMO(s2_req(0).uop.mem_cmd) || (s2_sc && !s2_sc_fail))
  val s2_engine_storelike_candidate = s2_engine_store_candidate || s2_engine_atomic_candidate
  val s2_lsu_store_selected =
    s2_engine_storelike_candidate &&
    s2_engine_store_available &&
    !s2_replay_engine_selected
  val s2_replay_store_selected =
    s2_replay_fresh_store_selected
  val s2_any_store_selected = s2_lsu_store_selected || s2_replay_store_selected

  // 这里只考虑普通 load hit：它不更新 line 状态，只需要把解密后的数据返回给 LSU。
  // 未来如果引入会改变 line 状态的特殊 load 语义，需要单独处理，不能直接并到这里。
  // 可以被用于筛选进入engine的 候选
  val s2_engine_load_candidate = widthMap(w => s2_valid(w) &&
    cacheCryptoLoadEnableReg &&
    s2_effective_req_crypto(w) &&
    s2_hit(w) &&
    s2_type === t_lsu &&
    !s2_lsu_engine_block(w) &&
    !s2_req(w).is_hella &&
    isRead(s2_req(w).uop.mem_cmd) &&
    !isWrite(s2_req(w).uop.mem_cmd) &&
    !s2_hit_reenc_active(w))
  // PTW/Hella loads should keep plain-line semantics, but we still route them
  // through the engine slot so the timing shape stays aligned with crypto loads.
  val s2_engine_hella_load_candidate = widthMap(w => s2_valid(w) &&
    cacheCryptoLoadEnableReg &&
    s2_effective_req_crypto(w) &&
    s2_hit(w) &&
    s2_type === t_lsu &&
    !s2_lsu_engine_block(w) &&
    s2_req(w).is_hella &&
    isRead(s2_req(w).uop.mem_cmd) &&
    !isWrite(s2_req(w).uop.mem_cmd) &&
    !s2_hit_reenc_active(w))
  // 用来筛选那一笔能进入engine的普通 LSU load。
  val s2_lsu_load_selected = Wire(Vec(memWidth, Bool()))
  val s2_replay_load_selected =
    s2_replay_fresh_load_selected
  var sawEarlierEngineLoad = false.B
  for (w <- 0 until memWidth) {
    // Single-issue engine: pick the earliest eligible load unless a store already
    // owns the engine this cycle.
    // store比load先处理
    // 在筛选的时候就加入engine是否可用这个条件
    s2_lsu_load_selected(w) := s2_engine_load_available &&
      !s2_replay_engine_selected &&
      !s2_engine_storelike_candidate &&
      (s2_engine_load_candidate(w) || s2_engine_hella_load_candidate(w)) &&
      !sawEarlierEngineLoad
    sawEarlierEngineLoad = sawEarlierEngineLoad || s2_engine_load_candidate(w) || s2_engine_hella_load_candidate(w)
  }
  val s2_lsu_load_any = s2_lsu_load_selected.reduce(_||_)
  val s2_any_load_selected = s2_lsu_load_any || s2_replay_load_selected
  s2_crypto_reservation_selected := s2_effective_req_crypto(0) && (
    s2_lsu_load_selected(0) ||
    s2_lsu_store_selected ||
    s2_replay_load_selected ||
    s2_replay_store_selected)
  val s2_lsu_load_candidate_any =
    s2_engine_load_candidate.reduce(_||_) ||
    s2_engine_hella_load_candidate.reduce(_||_)
  for (w <- 0 until memWidth) {
    assert(!(s2_engine_hella_load_candidate(w) && !cacheCryptoLoadEnableReg),
      "hella/PTW load must not become an engine candidate when load crypto is disabled")
    when (s2_engine_hella_load_candidate(w)) {
    }
  }
  val s2_crypto_engine_req = widthMap(w =>
    Mux((w == 0).B,
      s2_engine_load_candidate(w) || s2_engine_hella_load_candidate(w) || s2_engine_storelike_candidate || s2_replay_any_candidate,
      s2_engine_load_candidate(w) || s2_engine_hella_load_candidate(w)))

  // 对 LSU 请求，如果它本来应该走 engine，但这拍没有真的被 engine 接收，
  // 就必须通过 LSU 的 nack/retry 机制重放：
  // - load：candidate=1 但 selected=0，说明要么 engine 忙，要么这拍被更高优先级请求占掉
  // - store：candidate=1 但 s2_lsu_store_selected=0，含义相同
  // 特别地，如果这拍 replay hold/fresh replay 占用了 engine，
  // 那么 LSU candidate 也会在这里被判成 nack_engine，从而走 retry，而不是和 replay 同拍争用 engine。
  val s2_nack_engine = widthMap(w =>
    ((s2_engine_load_candidate(w) || s2_engine_hella_load_candidate(w)) && !s2_lsu_load_selected(w)) ||
    ((w == 0).B && s2_engine_storelike_candidate && !s2_lsu_store_selected))
  /////////////////////////////////////////////////////////////

  // LSU 请求在这些条件下会被变成 nack。
  // 注意最后显式要求 s2_type =/= t_replay：
  // - LSU 请求：通过 nack/retry 机制重放
  // - replay 请求：不通过 nack 重放，而是由 MSHR/RPQ 继续持有 replay 原件；
  //   DCache 只处理一份副本，并通过 replay_done/replay_retry 回告结果。
  // s2_nack           := widthMap(w => (s2_nack_miss(w) || s2_nack_hit(w) || s2_nack_victim(w) || s2_nack_data(w) || s2_nack_wb(w)) && s2_type =/= t_replay)
  val s2_nack_no_engine = widthMap(w =>
    (s2_nack_miss(w) ||
      s2_nack_hit(w) ||
      s2_nack_victim(w) ||
      s2_nack_data(w) ||
      s2_nack_wb(w) ||
      s2_nack_reenc_victim(w) ||
      s2_nack_reenc_hit(w)) && s2_type =/= t_replay)
  s2_nack := widthMap(w => s2_nack_no_engine(w) || (s2_nack_engine(w) && s2_type =/= t_replay))
  // 现在的写法是对于 s2_send_resp粗暴的全都置为false
  val s2_send_resp_no_engine = widthMap(w => (RegNext(s1_send_resp_or_nack(w)) && !s2_nack_no_engine(w) &&
                      (   ( s2_hit(w) && !s2_crypto_engine_req(w) ) ||
                       (mshrs.io.req(w).fire && isWrite(s2_req(w).uop.mem_cmd) && !isRead(s2_req(w).uop.mem_cmd)))))
  val s2_send_resp = widthMap(w => (RegNext(s1_send_resp_or_nack(w)) && !s2_nack(w) &&
                      // (  s2_hit(w) || (mshrs.io.req(w).fire && isWrite(s2_req(w).uop.mem_cmd) && !isRead(s2_req(w).uop.mem_cmd)))) )
                      (   ( s2_hit(w) && !s2_crypto_engine_req(w) ) ||
                       (mshrs.io.req(w).fire && isWrite(s2_req(w).uop.mem_cmd) && !isRead(s2_req(w).uop.mem_cmd)))))
  val s2_send_nack = widthMap(w => (RegNext(s1_send_resp_or_nack(w)) && s2_nack(w)))

  for (w <- 0 until memWidth)
    assert(!(s2_send_resp(w) && s2_send_nack(w)))

  val s2_replay_uses_crypto_protocol =
    replayProtocolIssuedS2 &&
    s2_valid(0) &&
    s2_type === t_replay
  val replay_done_direct =
    s2_replay_uses_crypto_protocol &&
    !s2_replay_killed &&
    !s2_replay_any_candidate
  val replay_done_engine =
    s2_replay_uses_crypto_protocol &&
    !s2_replay_killed &&
    s2_replay_engine_selected
  val replay_done_kill_s1 =
    replayProtocolIssuedS1 && !s1_valid(0)
  val replay_done_kill_s2 =
    replayProtocolIssuedS2 && !s2_valid(0)
  mshrs.io.replay_done :=
    replay_done_direct ||
    replay_done_engine ||
    replay_done_kill_s1 ||
    replay_done_kill_s2 ||
    (s2_replay_uses_crypto_protocol && s2_replay_killed)
  mshrs.io.replay_retry :=
    s2_replay_uses_crypto_protocol &&
    !s2_replay_killed &&
    s2_replay_any_candidate &&
    !s2_replay_engine_selected
  when (mshrs.io.replay_done || mshrs.io.replay_retry) {
  }

  // hits always send a response
  // If MSHR is not available, LSU has to replay this request later
  // If MSHR is available and this is only a store(not a amo), we don't need to wait for resp later
  s2_store_failed := s2_valid(0) && s2_nack(0) && s2_send_nack(0) && s2_req(0).uop.uses_stq

  for (w <- 0 until memWidth) {
    mshrs.io.req(w).valid := s2_valid(w)          &&
                            !s2_hit(w)            &&
                            !s2_nack_hit(w)       &&
                            !s2_nack_victim(w)    &&
                            !s2_nack_data(w)      &&
                            !s2_nack_wb(w)        &&
                            /////////////////////////////////////////////////////////////////
                            !s2_hit_reenc_active(w) &&
                            !( !s2_tag_match(w) && s2_repl_meta(w).reenc_active ) &&
                            /////////////////////////////////////////////////////////////////
                             s2_type.isOneOf(t_lsu, t_prefetch)             &&
                            !IsKilledByBranch(io.lsu.brupdate, s2_req(w).uop) &&
                            !(io.lsu.exception && s2_req(w).uop.uses_ldq)   &&
                             (isPrefetch(s2_req(w).uop.mem_cmd) ||
                              isRead(s2_req(w).uop.mem_cmd)     ||
                              isWrite(s2_req(w).uop.mem_cmd))
    assert(!(mshrs.io.req(w).valid && s2_type === t_replay), "Replays should not need to go back into MSHRs")
    mshrs.io.req(w).bits             := DontCare
    mshrs.io.req(w).bits.uop         := s2_req(w).uop
    mshrs.io.req(w).bits.uop.br_mask := GetNewBrMask(io.lsu.brupdate, s2_req(w).uop)
    mshrs.io.req(w).bits.addr        := s2_req(w).addr
    mshrs.io.req(w).bits.req_crypto_line := s2_effective_req_crypto(w)
    // 同 tag 但 resident line 仍是 plain-line 时，把这次访问当作 mode-mismatch miss，
    // 并在原 way 上强制替换，避免在同一个 set 里产生 duplicate tag。
    mshrs.io.req(w).bits.tag_match   := s2_tag_match(w) && !s2_force_mode_replace(w)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////

    when (dcacheCryptoDebugLogEnable && mshrs.io.req(w).valid && s2_effective_req_crypto(w)) {
      printf("[L1D-CRYPTO-MSHR-REQ] cycle=%d lane=%d pc=0x%x cmd=0x%x addr=0x%x fire=%d ready=%d hit=%d tag_match=%d force_replace=%d repl_reenc=%d old_crypto=%d old_reenc=%d\n",
        dcacheDebugCycle,
        w.U,
        s2_req(w).uop.debug_pc,
        s2_req(w).uop.mem_cmd,
        s2_req(w).addr,
        mshrs.io.req(w).fire,
        mshrs.io.req(w).ready,
        s2_hit(w),
        s2_tag_match(w),
        s2_force_mode_replace(w),
        s2_repl_meta(w).reenc_active,
        s2_hit_meta(w).cryptoLine,
        s2_hit_meta(w).reenc_active)
    }

    // tag hit 但走 MSHR（包括 permission upgrade 和 mode-mismatch forced replace）
    // 时，old_meta 必须来自命中的 resident line，而不是 replacer 选出的 victim。
    //  mshrs.io.req(w).bits.old_meta    := Mux(s2_tag_match(w), L1Metadata(s2_repl_meta(w).tag, s2_hit_state(w)), s2_repl_meta(w))
    mshrs.io.req(w).bits.old_meta    := Mux(s2_tag_match(w) || s2_force_mode_replace(w),
      s2_hit_meta(w),
      s2_repl_meta(w))
    ////////////////////////////////////////////////////////////////////////////////////////////////////////
    // mshrs.io.req(w).bits.way_en      := Mux(s2_tag_match(w), s2_tag_match_way(w), s2_replaced_way_en)
    mshrs.io.req(w).bits.way_en      := Mux(s2_tag_match(w) || s2_force_mode_replace(w), s2_tag_match_way(w), s2_replaced_way_en)
    // mshrs.io.req(w).bits.replay_meta := 0.U.asTypeOf(new L1Metadata)
    // mshrs.io.req(w).bits.replay_meta_valid := false.B

    mshrs.io.req(w).bits.data        := s2_req(w).data
    mshrs.io.req(w).bits.is_hella    := s2_req(w).is_hella
    mshrs.io.req_is_probe(w)         := s2_type === t_probe && s2_valid(w)
  }
  mshrs.io.meta_resp.valid      := !s2_nack_hit(0) || prober.io.mshr_wb_rdy
  mshrs.io.meta_resp.bits       := Mux1H(s2_tag_match_way(0), RegNext(meta(0).io.resp))
  // when (mshrs.io.meta_resp.valid) {
  //   printf("[L1-META-TAP-RSP] s2_type=%d s2_valid=%d s2_addr=0x%x s2_tag_match_way=0x%x mshr_meta_way_s1=0x%x rsp_tag=0x%x rsp_coh=0x%x rsp_reenc=%d rsp_crypto=%d prober_wb_rdy=%d\n",
  //     s2_type,
  //     s2_valid(0),
  //     s2_req(0).addr,
  //     s2_tag_match_way(0),
  //     s1_mshr_meta_read_way_en,
  //     mshrs.io.meta_resp.bits.tag,
  //     mshrs.io.meta_resp.bits.coh.state,
  //     mshrs.io.meta_resp.bits.reenc_active,
  //     mshrs.io.meta_resp.bits.cryptoLine,
  //     prober.io.mshr_wb_rdy)
  // }
  // 只要有任意一个MSHR_req成功分配，就通知replacement policy 一次 miss
  // when (mshrs.io.req.map(_.fire).reduce(_||_)) { replacer.miss }
  // 只有 MSHR req fire 且这次不是 s2_force_mode_replace 才算一次需要汇报给 replacer 的“真正 miss”
  when ((mshrs.io.req zip s2_force_mode_replace).map { case (r, forced) => r.fire && !forced }.reduce(_||_)) { replacer.miss }
  tl_out.a <> mshrs.io.mem_acquire
  ///////////////////////////////////////////////////////////////////
  // 发到外部tl_out.c的 A channel 信息
  val outerAHasCryptoMeta = tl_out.a.bits.user.lift(CacheCryptoWritebackMeta).isDefined.B
  val outerACryptoLine = tl_out.a.bits.user.lift(CacheCryptoWritebackMeta).map(_.cryptoLine).getOrElse(false.B)
  when (!cacheCryptoEnableReg && tl_out.a.fire) {
    assert(!outerACryptoLine, "pre-enable outer A should not carry cryptoLine=1")
  }
  ////////////////////////////////////////////////////////////////////
  // probes and releases
  // B 通道 传递进入 probe unit
  prober.io.req.valid   := tl_out.b.valid && !lrsc_valid
  tl_out.b.ready        := prober.io.req.ready && !lrsc_valid
  prober.io.req.bits    := tl_out.b.bits
  prober.io.way_en      := s2_tag_match_way(0)
  // 对于 proberunit，不能因为 reenc_activae把输入阻塞，否则会导致 probe引起miss
  // prober.io.block_state := s2_hit_state(0) block_state被我们改成 block_meta了
  prober.io.block_meta :=  s2_hit_meta(0)
  metaWriteArb.io.in(2) <> prober.io.meta_write
  prober.io.mshr_rdy    := mshrs.io.probe_rdy
  prober.io.wb_rdy      := (prober.io.meta_write.bits.idx =/= wb.io.idx.bits) || !wb.io.idx.valid
  mshrs.io.prober_state := prober.io.state

  // refills
  when (tl_out.d.bits.source === cfg.nMSHRs.U) {
    // This should be ReleaseAck
    tl_out.d.ready := true.B
    mshrs.io.mem_grant.valid := false.B
    mshrs.io.mem_grant.bits  := DontCare
  } .otherwise {
    // This should be GrantData
    mshrs.io.mem_grant <> tl_out.d
  }
  when (tl_out.d.fire && tl_out.d.bits.source === cfg.nMSHRs.U) {
    assert(!edge.hasData(tl_out.d.bits), "WB ack path should not receive GrantData")
  }

  dataWriteArb.io.in(2) <> mshrs.io.refill
  when (dataWriteArb.io.in(2).fire) {
  }
  metaWriteArb.io.in(1) <> mshrs.io.meta_write

  tl_out.e <> mshrs.io.mem_finish

  // writebacks
  val wbArb = Module(new Arbiter(new WritebackReq(edge.bundle), 2))
  // 0 goes to prober, 1 goes to MSHR evictions
  wbArb.io.in(0)       <> prober.io.wb_req
  wbArb.io.in(1)       <> mshrs.io.wb_req 
  wb.io.req            <> wbArb.io.out   
  //////////////////////////////////////////////////             
  // wb.io.data_resp       := s2_data_muxed(0)
  wb.io.data_resp.data  := s2_data_muxed(0)
  // WBUnit gets the victim counter from the same cache-line read response as the data beat.
  wb.io.data_resp.counter := s2_counter_muxed(0)
  //////////////////////////////////////////////////
  mshrs.io.wb_resp      := wb.io.resp
  wb.io.mem_grant       := tl_out.d.fire && tl_out.d.bits.source === cfg.nMSHRs.U

  val lsu_release_arb = Module(new Arbiter(new TLBundleC(edge.bundle), 2))
  io.lsu.release <> lsu_release_arb.io.out
  lsu_release_arb.io.in(0) <> wb.io.lsu_release
  lsu_release_arb.io.in(1) <> prober.io.lsu_release

  TLArbiter.lowest(edge, tl_out.c, wb.io.release, prober.io.rep)

  io.lsu.perf.release := edge.done(tl_out.c)
  io.lsu.perf.acquire := edge.done(tl_out.a)

  // load data gen
  val s2_data_word_prebypass = widthMap(w => s2_data_muxed(w) >> Cat(s2_word_idx(w), 0.U(log2Ceil(coreDataBits).W)))

  //////////////////////////////////////////////
  val s2_counter_prebypass = s2_counter_muxed
  //////////////////////////////////////////////

  val s2_data_word = Wire(Vec(memWidth, UInt()))
  
  ///////////////////////////////
  val s2_counter = Wire(Vec(memWidth, UInt(p(CacheCryptoCounterBitsKey).W)))
  ///////////////////////////////
  val loadgen = (0 until memWidth).map { w =>
    new LoadGen(s2_req(w).uop.mem_size, s2_req(w).uop.mem_signed, s2_req(w).addr,
                s2_data_word(w), s2_sc && (w == 0).B, wordBytes)
  }
  // Mux between cache responses and uncache responses
  // 送给 lsu的输出 cache hit的情况
  val cache_resp   = Wire(Vec(memWidth, Valid(new BoomDCacheResp)))
  for (w <- 0 until memWidth) {
    cache_resp(w).valid         := s2_valid(w) && s2_send_resp(w)
    cache_resp(w).bits.uop      := s2_req(w).uop
    cache_resp(w).bits.data     := loadgen(w).data | s2_sc_fail
    cache_resp(w).bits.is_hella := s2_req(w).is_hella
    when (cache_resp(w).valid) {
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  val engine_load_resp = Wire(Vec(memWidth, Valid(new BoomDCacheResp)))
  val engine_store_resp = Wire(Vec(memWidth, Valid(new BoomDCacheResp)))
  val engine_resp = Wire(Vec(memWidth, Valid(new BoomDCacheResp)))
  val cache_resp_lane_busy_for_engine = Wire(Vec(memWidth, Bool()))
  engine_load_resp.foreach(_ := 0.U.asTypeOf(Valid(new BoomDCacheResp)))
  engine_store_resp.foreach(_ := 0.U.asTypeOf(Valid(new BoomDCacheResp)))
  engine_resp.foreach(_ := 0.U.asTypeOf(Valid(new BoomDCacheResp)))
  for (w <- 0 until memWidth) {
    // Predict whether this lane will be occupied by the direct cache response path
    // without depending on s2_nack_engine / engine ready, to avoid a ready-valid loop.
    cache_resp_lane_busy_for_engine(w) := s2_valid(w) && s2_send_resp_no_engine(w)
  }

  val engine_load_lane_oh = UIntToOH(engine.io.loadResp.bits.lane, memWidth)
  val engine_store_lane_oh = UIntToOH(engine.io.storeResp.bits.lane, memWidth)
  val engine_load_lane_free = Mux(engine.io.loadResp.valid, Mux1H(engine_load_lane_oh, cache_resp_lane_busy_for_engine.map(b => !b)), false.B)
  val engine_store_lane_free = Mux(engine.io.storeResp.valid, Mux1H(engine_store_lane_oh, cache_resp_lane_busy_for_engine.map(b => !b)), false.B)
  val engine_resp_same_lane = engine.io.loadResp.bits.lane === engine.io.storeResp.bits.lane
  val engine_store_resp_ready = engine.io.storeResp.valid && engine_store_lane_free
  val engine_load_resp_ready = engine.io.loadResp.valid &&
    engine_load_lane_free &&
    !(engine.io.storeResp.valid && engine_store_resp_ready && engine_resp_same_lane)

  engine.io.loadResp.ready := engine_load_resp_ready
  engine.io.storeResp.ready := engine_store_resp_ready
  for (w <- 0 until memWidth) {
    when (engine.io.loadResp.valid && engine.io.loadResp.ready && engine_load_lane_oh(w)) {
      engine_load_resp(w).valid := true.B
      engine_load_resp(w).bits := engine.io.loadResp.bits.resp
      when (dcacheCryptoDebugLogEnable) {
        printf("[L1D-CRYPTO-ENGINE-LOAD-RESP] cycle=%d lane=%d pc=0x%x cmd=0x%x data=0x%x\n",
          dcacheDebugCycle,
          w.U,
          engine.io.loadResp.bits.resp.uop.debug_pc,
          engine.io.loadResp.bits.resp.uop.mem_cmd,
          engine.io.loadResp.bits.resp.data)
      }
    }
    when (engine.io.storeResp.valid && engine.io.storeResp.ready && engine_store_lane_oh(w)) {
      engine_store_resp(w).valid := true.B
      engine_store_resp(w).bits := engine.io.storeResp.bits.resp
      when (dcacheCryptoDebugLogEnable) {
        printf("[L1D-CRYPTO-ENGINE-STORE-RESP] cycle=%d lane=%d pc=0x%x cmd=0x%x data=0x%x\n",
          dcacheDebugCycle,
          w.U,
          engine.io.storeResp.bits.resp.uop.debug_pc,
          engine.io.storeResp.bits.resp.uop.mem_cmd,
          engine.io.storeResp.bits.resp.data)
      }
      when (engine.io.storeResp.bits.resp.uop.is_amo ||
            engine.io.storeResp.bits.resp.uop.mem_cmd === M_XSC ||
            engine.io.storeResp.bits.resp.uop.mem_cmd === M_XLR) {
      }
    }
    when (engine_store_resp(w).valid) {
      engine_resp(w) := engine_store_resp(w)
    } .elsewhen (engine_load_resp(w).valid) {
      engine_resp(w) := engine_load_resp(w)
    }
  }
  /////////////////////////////////////////////////////////////////////////////////

  val uncache_resp = Wire(Valid(new BoomDCacheResp))
  uncache_resp.bits     := mshrs.io.resp.bits
  uncache_resp.valid    := mshrs.io.resp.valid
  /////////////////////////////////////////////////////////////
  // val resp = WireInit(cache_resp)
  val resp_with_engine = WireInit(cache_resp)
  for (w <- 0 until memWidth) {
    when (engine_resp(w).valid) {
      resp_with_engine(w) := engine_resp(w)
    }
  }

  // mshrs.io.resp.ready := !(cache_resp.map(_.valid).reduce(_&&_)) // We can backpressure the MSHRs, but not cache hits
  // val resp = WireInit(cache_resp)
  mshrs.io.resp.ready := !(resp_with_engine.map(_.valid).reduce(_&&_)) // We can backpressure the MSHRs, but not cache hits
  val resp = WireInit(resp_with_engine)
  ////////////////////////////////////////////////////////////

  var uncache_responding = false.B
  for (w <- 0 until memWidth) {
    // val uncache_respond = !cache_resp(w).valid && !uncache_responding
    /////////////////////////////////////////////////////////////////////////////
    val uncache_respond = !resp_with_engine(w).valid && !uncache_responding
    /////////////////////////////////////////////////////////////////////////////
    when (uncache_respond) {
      resp(w) := uncache_resp
    }
    uncache_responding = uncache_responding || uncache_respond
  }

  for (w <- 0 until memWidth) {
    io.lsu.resp(w).valid := resp(w).valid &&
                            !(io.lsu.exception && resp(w).bits.uop.uses_ldq) &&
                            !IsKilledByBranch(io.lsu.brupdate, resp(w).bits.uop)
    io.lsu.resp(w).bits  := UpdateBrMask(io.lsu.brupdate, resp(w).bits)
    when (io.lsu.resp(w).valid) {
    }

    io.lsu.nack(w).valid := s2_valid(w) && s2_send_nack(w) &&
                            !(io.lsu.exception && s2_req(w).uop.uses_ldq) &&
                            !IsKilledByBranch(io.lsu.brupdate, s2_req(w).uop)
    io.lsu.nack(w).bits  := UpdateBrMask(io.lsu.brupdate, s2_req(w))
    assert(!(io.lsu.nack(w).valid && s2_type =/= t_lsu))
  }

  // Store/amo hits
  // 每个cycle只会有一个store req
  // s2_nack(0) : 是否不能继续
  // s2_send_nack(0): 是否要通过 LSU 的 nack 接口对外发一个 nack
  // 普通 store hit 现在改成由 engine 通过 meta/data side-band 完成事务；
  // 因此这条原有的 s3 提交路径只保留给非-engine fallback（例如旧的 AMO 路径）。
  val s3_req   = RegNext(s2_req(0))
  val s2_storelike_req = s2_valid(0) && s2_hit(0) && isWrite(s2_req(0).uop.mem_cmd) &&
    !s2_sc_fail && !(s2_send_nack(0) && s2_nack(0))
  val s2_crypto_store_req = s2_storelike_req && s2_effective_req_crypto(0)
  // val s3_valid = RegNext(s2_valid(0) && s2_hit(0) && isWrite(s2_req(0).uop.mem_cmd) &&
                        //  !s2_sc_fail && !(s2_send_nack(0) && s2_nack(0)))
  val s3_valid = RegNext(s2_storelike_req &&
                         !s2_any_store_selected &&
                         !s2_effective_req_crypto(0))
  assert(!(RegNext(s2_crypto_store_req) && s3_valid),
    "crypto store request must not enter the plain s3 store pipeline")

  val s3_counter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  ////////////////////////////////////////////////////////////////////////
  for (w <- 1 until memWidth) {
    assert(!(s2_valid(w) && s2_hit(w) && isWrite(s2_req(w).uop.mem_cmd) &&
                         !s2_sc_fail && !(s2_send_nack(w) && s2_nack(w))),
      "Store must go through 0th pipe in L1D")
  }

  // For bypassing
  val s4_req   = RegNext(s3_req)
  val s4_valid = RegNext(s3_valid)
  val s4_counter = RegNext(s3_counter)
  val s5_req   = RegNext(s4_req)
  val s5_valid = RegNext(s4_valid)
  val s5_counter = RegNext(s4_counter)

  val s3_bypass = widthMap(w => s3_valid && ((s2_req(w).addr >> wordOffBits) === (s3_req.addr >> wordOffBits)))
  val s4_bypass = widthMap(w => s4_valid && ((s2_req(w).addr >> wordOffBits) === (s4_req.addr >> wordOffBits)))
  val s5_bypass = widthMap(w => s5_valid && ((s2_req(w).addr >> wordOffBits) === (s5_req.addr >> wordOffBits)))
  val s3_counter_bypass = widthMap(w => s3_valid && ((s2_req(w).addr >> blockOffBits) === (s3_req.addr >> blockOffBits)))
  val s4_counter_bypass = widthMap(w => s4_valid && ((s2_req(w).addr >> blockOffBits) === (s4_req.addr >> blockOffBits)))
  val s5_counter_bypass = widthMap(w => s5_valid && ((s2_req(w).addr >> blockOffBits) === (s5_req.addr >> blockOffBits)))

  // Store -> Load bypassing
  for (w <- 0 until memWidth) {
    s2_data_word(w) := Mux(s3_bypass(w), s3_req.data,
                       Mux(s4_bypass(w), s4_req.data,
                       Mux(s5_bypass(w), s5_req.data,
                                         s2_data_word_prebypass(w))))
    //////////////////////////////////////////////////////////////////
    s2_counter(w) := Mux(s3_counter_bypass(w), s3_counter,
                     Mux(s4_counter_bypass(w), s4_counter,
                     Mux(s5_counter_bypass(w), s5_counter,
                                       s2_counter_prebypass(w))))
    //////////////////////////////////////////////////////////////////
    when (dcacheCryptoDebugLogEnable && s2_valid(w) && s2_effective_req_crypto(w) &&
          (s2_lr || s2_sc || s2_crypto_engine_req(w) || s2_nack(w) || s2_send_resp(w))) {
      printf("[L1D-CRYPTO-S2] cycle=%d lane=%d pc=0x%x cmd=0x%x addr=0x%x type=%d hit=%d tag_match=%d force_replace=%d req_crypto=%d meta_crypto=%d reenc_hit=%d nack=%d nack_no_engine=%d nack_engine=%d send_resp=%d send_nack=%d lr=%d sc=%d sc_fail=%d lrsc_valid=%d lrsc_match=%d lrsc_count=%d lrsc_addr=0x%x reservation_selected=%d load_sel=%d store_sel=%d replay_load_sel=%d replay_store_sel=%d engine_req=%d counter=0x%x data=0x%x\n",
        dcacheDebugCycle,
        w.U,
        s2_req(w).uop.debug_pc,
        s2_req(w).uop.mem_cmd,
        s2_req(w).addr,
        s2_type,
        s2_hit(w),
        s2_tag_match(w),
        s2_force_mode_replace(w),
        s2_effective_req_crypto(w),
        s2_effective_hit_meta(w).cryptoLine,
        s2_hit_reenc_active(w),
        s2_nack(w),
        s2_nack_no_engine(w),
        s2_nack_engine(w),
        s2_send_resp(w),
        s2_send_nack(w),
        s2_lr && (w == 0).B,
        s2_sc && (w == 0).B,
        s2_sc_fail && (w == 0).B,
        lrsc_valid,
        s2_lrsc_addr_match(w),
        lrsc_count,
        lrsc_addr,
        s2_crypto_reservation_selected,
        s2_lsu_load_selected(w),
        s2_lsu_store_selected && (w == 0).B,
        s2_replay_load_selected && (w == 0).B,
        s2_replay_store_selected && (w == 0).B,
        s2_crypto_engine_req(w),
        s2_counter(w),
        s2_data_word(w))
    }
  }
  //////////////////////////////////////////////////////
  val freshReplayReq = Wire(new BoomCacheEngineHitReq)
  freshReplayReq := 0.U.asTypeOf(new BoomCacheEngineHitReq)
  when (s2_replay_any_candidate) {
    freshReplayReq.req := s2_req(0)
    freshReplayReq.lane := 0.U
    freshReplayReq.line.idx := s2_req(0).addr(idxMSB, idxLSB)
    freshReplayReq.line.tag := s2_req(0).addr >> untagBits
    freshReplayReq.line.way_en := s2_tag_match_way(0)
    freshReplayReq.meta := s2_effective_hit_meta(0)
    freshReplayReq.sendResp := !s2_replay_plain_store_candidate
    freshReplayReq.cipherWord := s2_data_word(0)
    freshReplayReq.counter := s2_counter(0)
  }
  when (s2_type === t_replay && s2_replay_req_crypto_line && s2_replay_any_candidate) {
    assert(freshReplayReq.meta.cryptoLine,
      "crypto replay entering the engine must carry crypto-line metadata")
  }
  val s2_engine_req = Wire(new BoomCacheEngineHitReq)
  s2_engine_req := 0.U.asTypeOf(new BoomCacheEngineHitReq)
  when (s2_replay_fresh_selected) {
    s2_engine_req := freshReplayReq
  } .elsewhen (s2_lsu_store_selected) {
    s2_engine_req.req := s2_req(0)
    s2_engine_req.lane := 0.U
    s2_engine_req.line.idx := s2_req(0).addr(idxMSB, idxLSB)
    s2_engine_req.line.tag := s2_req(0).addr >> untagBits
    s2_engine_req.line.way_en := s2_tag_match_way(0)
    s2_engine_req.meta := s2_hit_meta(0)
    s2_engine_req.sendResp := true.B
    s2_engine_req.cipherWord := s2_data_word(0)
    s2_engine_req.counter := s2_counter(0)
  }
  for (w <- 0 until memWidth) {
    when (s2_lsu_load_selected(w)) {
      s2_engine_req.req := s2_req(w)
      s2_engine_req.lane := w.U
      s2_engine_req.line.idx := s2_req(w).addr(idxMSB, idxLSB)
      s2_engine_req.line.tag := s2_req(w).addr >> untagBits
      s2_engine_req.line.way_en := s2_tag_match_way(w)
      s2_engine_req.meta := s2_hit_meta(w)
      s2_engine_req.sendResp := true.B
      s2_engine_req.cipherWord := s2_data_word(w)
      s2_engine_req.counter := s2_counter(w)
    }
  }
  // 修改后 replay 不再经过 DCache 本地 hold。
  engine.io.hitReq.valid := s2_replay_fresh_selected || s2_lsu_store_selected || s2_lsu_load_any
  engine.io.hitReq.bits := s2_engine_req
  engine.io.hitReqIsStore := Mux(s2_replay_fresh_selected, s2_replay_storelike_candidate, s2_lsu_store_selected)
  when (dcacheCryptoDebugLogEnable && engine.io.hitReq.valid) {
    printf("[L1D-CRYPTO-ENGINE-HITREQ] cycle=%d pc=0x%x cmd=0x%x addr=0x%x lane=%d is_store=%d replay=%d store_sel=%d load_any=%d meta_crypto=%d meta_reenc=%d way=0x%x counter=0x%x cipher=0x%x send_resp=%d\n",
      dcacheDebugCycle,
      engine.io.hitReq.bits.req.uop.debug_pc,
      engine.io.hitReq.bits.req.uop.mem_cmd,
      engine.io.hitReq.bits.req.addr,
      engine.io.hitReq.bits.lane,
      engine.io.hitReqIsStore,
      s2_replay_fresh_selected,
      s2_lsu_store_selected,
      s2_lsu_load_any,
      engine.io.hitReq.bits.meta.cryptoLine,
      engine.io.hitReq.bits.meta.reenc_active,
      engine.io.hitReq.bits.line.way_en,
      engine.io.hitReq.bits.counter,
      engine.io.hitReq.bits.cipherWord,
      engine.io.hitReq.bits.sendResp)
  }
  when (engine.io.hitReq.valid) {
    when (s2_replay_fresh_selected) {
      assert(engine.io.hitReq.bits.meta.cryptoLine === s2_replay_req_crypto_line,
        "engine replay request metadata cryptoLine does not match replay request mode")
    } .otherwise {
      assert(engine.io.hitReq.bits.meta.cryptoLine === s2_effective_req_crypto(engine.io.hitReq.bits.lane),
        "engine request metadata cryptoLine does not match locked request mode")
    }
  }
  when (engine.io.hitReq.valid &&
        (engine.io.hitReq.bits.req.uop.is_amo ||
         engine.io.hitReq.bits.req.uop.mem_cmd === M_XSC ||
         engine.io.hitReq.bits.req.uop.mem_cmd === M_XLR)) {
  }
  //////////////////////////////////////////////////////
  // Legacy fallback path for non-engine store-like hits when cache-crypto is disabled.
  val amoalu   = Module(new AMOALU(xLen))
  amoalu.io.mask := new StoreGen(s2_req(0).uop.mem_size, s2_req(0).addr, 0.U, xLen/8).mask
  amoalu.io.cmd  := s2_req(0).uop.mem_cmd
  amoalu.io.lhs  := s2_data_word(0)
  amoalu.io.rhs  := s2_req(0).data

  s3_req.data := amoalu.io.out
  s3_counter := s2_counter(0)
  
  ////////////////////////////////////////////////////////////////////////
  
  val s3_way   = RegNext(s2_tag_match_way(0))

  dataWriteArb.io.in(1).valid       := s3_valid
  dataWriteArb.io.in(1).bits.addr   := s3_req.addr
  dataWriteArb.io.in(1).bits.wmask  := UIntToOH(s3_req.addr.extract(rowOffBits-1,offsetlsb))
  dataWriteArb.io.in(1).bits.data   := Fill(rowWords, s3_req.data)
  ///////////////////////////////////////////////////////////
  dataWriteArb.io.in(1).bits.counter := s3_counter
  dataWriteArb.io.in(1).bits.counter_wen := s3_valid
  ///////////////////////////////////////////////////////////
  dataWriteArb.io.in(1).bits.way_en := s3_way
  when (dataWriteArb.io.in(1).fire) {
  }


  io.lsu.ordered := mshrs.io.fence_rdy && !s1_valid.reduce(_||_) && !s2_valid.reduce(_||_)
}
