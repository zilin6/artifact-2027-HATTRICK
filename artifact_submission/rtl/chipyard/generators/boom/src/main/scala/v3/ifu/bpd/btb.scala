package boom.v3.ifu

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

import boom.v3.common._
import boom.v3.util.{BoomCoreStringPrefix, BoolToChar}

import scala.math.min

case class BoomBTBParams(
  nSets: Int = 128,
  nWays: Int = 2,
  offsetSz: Int = 13,
  extendedNSets: Int = 128
)


class BTBBranchPredictorBank(params: BoomBTBParams = BoomBTBParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  override val nSets         = params.nSets
  override val nWays         = params.nWays
  val tagSz         = vaddrBitsExtended - log2Ceil(nSets) - log2Ceil(fetchWidth) - 1
  val offsetSz      = params.offsetSz
  val extendedNSets = params.extendedNSets

  require(isPow2(nSets))
  require(isPow2(extendedNSets) || extendedNSets == 0)
  require(extendedNSets <= nSets)
  require(extendedNSets >= 1)

  class BTBEntry extends Bundle {
    val offset   = SInt(offsetSz.W)
    val extended = Bool()
  }
  val btbEntrySz = offsetSz + 1

  class BTBMeta extends Bundle {
    val is_br = Bool()
    val tag   = UInt(tagSz.W)
  }
  val btbMetaSz = tagSz + 1

  class BTBPredictMeta extends Bundle {
    val write_way = UInt(log2Ceil(nWays).W)
  }

  val s1_meta = Wire(new BTBPredictMeta)
  val f3_meta = RegNext(RegNext(s1_meta))


  io.f3_meta := f3_meta.asUInt

  override val metaSz = s1_meta.asUInt.getWidth

  val doing_reset = RegInit(true.B)
  val reset_idx   = RegInit(0.U(log2Ceil(nSets).W))
  reset_idx := reset_idx + doing_reset
  when (reset_idx === (nSets-1).U) { doing_reset := false.B }

  val meta     = Seq.fill(nWays) { SyncReadMem(nSets, Vec(bankWidth, UInt(btbMetaSz.W))) }
  val btb      = Seq.fill(nWays) { SyncReadMem(nSets, Vec(bankWidth, UInt(btbEntrySz.W))) }
  val ebtb     = SyncReadMem(extendedNSets, UInt(vaddrBitsExtended.W))

  val mems = (((0 until nWays) map ({w:Int => Seq(
    (f"btb_meta_way$w", nSets, bankWidth * btbMetaSz),
    (f"btb_data_way$w", nSets, bankWidth * btbEntrySz))})).flatten ++ Seq(("ebtb", extendedNSets, vaddrBitsExtended)))

  val s1_req_rbtb  = VecInit(btb.map { b => VecInit(b.read(s0_lookup_idx , s0_valid).map(_.asTypeOf(new BTBEntry))) })
  val s1_req_rmeta = VecInit(meta.map { m => VecInit(m.read(s0_lookup_idx, s0_valid).map(_.asTypeOf(new BTBMeta))) })
  val s1_req_rebtb = ebtb.read(s0_lookup_idx, s0_valid)
  val s1_req_tag   = s1_lookup_idx >> log2Ceil(nSets)

  val s1_resp   = Wire(Vec(bankWidth, Valid(UInt(vaddrBitsExtended.W))))
  val s1_is_br  = Wire(Vec(bankWidth, Bool()))
  val s1_is_jal = Wire(Vec(bankWidth, Bool()))
  // val btbWatchLo = "h8000230".U(vaddrBitsExtended.W)
  // val btbWatchHi = "h8000240".U(vaddrBitsExtended.W)
  // def inBtbWatch(pc: UInt): Bool = pc >= btbWatchLo && pc < btbWatchHi
  // val btbWatch =
  //   (s1_valid && inBtbWatch(s1_pc)) ||
  //   (s1_update.valid && (inBtbWatch(s1_update.bits.pc) || inBtbWatch(s1_update.bits.target) || s1_update.bits.target === 0.U))

  val s1_hit_ohs = VecInit((0 until bankWidth) map { i =>
    VecInit((0 until nWays) map { w =>
      s1_req_rmeta(w)(i).tag === s1_req_tag(tagSz-1,0)
    })
  })
  val s1_hits     = s1_hit_ohs.map { oh => oh.reduce(_||_) }
  val s1_hit_ways = s1_hit_ohs.map { oh => PriorityEncoder(oh) }

  for (w <- 0 until bankWidth) {
    val entry_meta = s1_req_rmeta(s1_hit_ways(w))(w)
    val entry_btb  = s1_req_rbtb(s1_hit_ways(w))(w)
    s1_resp(w).valid := !doing_reset && s1_valid && s1_hits(w)
    s1_resp(w).bits  := Mux(
      entry_btb.extended,
      s1_req_rebtb,
      (s1_pc.asSInt + (w << 1).S + entry_btb.offset).asUInt)
    s1_is_br(w)  := !doing_reset && s1_resp(w).valid &&  entry_meta.is_br
    s1_is_jal(w) := !doing_reset && s1_resp(w).valid && !entry_meta.is_br


    io.resp.f2(w) := io.resp_in(0).f2(w)
    io.resp.f3(w) := io.resp_in(0).f3(w)
    when (RegNext(s1_hits(w))) {
      io.resp.f2(w).predicted_pc := RegNext(s1_resp(w))
      io.resp.f2(w).is_br        := RegNext(s1_is_br(w))
      io.resp.f2(w).is_jal       := RegNext(s1_is_jal(w))
      when (RegNext(s1_is_jal(w))) {
        io.resp.f2(w).taken      := true.B
      }
    }
    when (RegNext(RegNext(s1_hits(w)))) {
      io.resp.f3(w).predicted_pc := RegNext(io.resp.f2(w).predicted_pc)
      io.resp.f3(w).is_br        := RegNext(io.resp.f2(w).is_br)
      io.resp.f3(w).is_jal       := RegNext(io.resp.f2(w).is_jal)
      when (RegNext(RegNext(s1_is_jal(w)))) {
        io.resp.f3(w).taken      := true.B
      }
    }
  }

  val s1_any_hit      = s1_hits.reduce(_||_)
  val s1_hit_oh_cat   = Cat(s1_hit_ohs.reverse.map(_.asUInt))
  val s1_hit_way_cat  = Cat(s1_hit_ways.reverse)
  val s1_resp_v_mask  = Cat(s1_resp.reverse.map(_.valid))
  val s1_resp_br_mask = Cat(s1_is_br.reverse)
  val s1_resp_jal_mask = Cat(s1_is_jal.reverse)

  // when (btbWatch && s1_valid) {
  //   printf("[BTB][READ] s1_pc=0x%x s0_idx=0x%x s1_idx=0x%x s1_tag=0x%x ebtb=0x%x\n",
  //     s1_pc, s0_idx, s1_idx, s1_req_tag(tagSz-1,0), s1_req_rebtb)
  //   for (i <- 0 until bankWidth) {
  //     printf("[BTB][READ] slot=%d hit=%c way=%d respV=%c resp=0x%x is_br=%c is_jal=%c\n",
  //       i.U,
  //       BoolToChar(s1_hits(i),'T'),
  //       s1_hit_ways(i),
  //       BoolToChar(s1_resp(i).valid,'T'),
  //       s1_resp(i).bits,
  //       BoolToChar(s1_is_br(i),'T'),
  //       BoolToChar(s1_is_jal(i),'T'))
  //     for (w <- 0 until nWays) {
  //       val metaBits = s1_req_rmeta(w)(i)
  //       val dataBits = s1_req_rbtb(w)(i)
  //       printf("[BTB][READ]  slot=%d way=%d tag=0x%x is_br=%c off=0x%x ext=%c hit=%c\n",
  //         i.U, w.U, metaBits.tag, BoolToChar(metaBits.is_br,'T'),
  //         dataBits.offset.asUInt, BoolToChar(dataBits.extended,'T'),
  //         BoolToChar(s1_hit_ohs(i)(w),'T'))
  //     }
  //   }
  // }

  val alloc_way = if (nWays > 1) {
    val r_metas = Cat(VecInit(s1_req_rmeta.map { w => VecInit(w.map(_.tag)) }).asUInt, s1_req_tag(tagSz-1,0))
    val l = log2Ceil(nWays)
    val nChunks = (r_metas.getWidth + l - 1) / l
    val chunks = (0 until nChunks) map { i =>
      r_metas(min((i+1)*l, r_metas.getWidth)-1, i*l)
    }
    chunks.reduce(_^_)
  } else {
    0.U
  }
  s1_meta.write_way := Mux(s1_hits.reduce(_||_),
    PriorityEncoder(s1_hit_ohs.map(_.asUInt).reduce(_|_)),
    alloc_way)

  val s1_update_cfi_idx = s1_update.bits.cfi_idx.bits
  val s1_update_meta    = s1_update.bits.meta.asTypeOf(new BTBPredictMeta)
  val s1_update_tag     = s1_update_lookup_idx >> log2Ceil(nSets)

  val max_offset_value = Cat(0.B, ~(0.U((offsetSz-1).W))).asSInt
  val min_offset_value = Cat(1.B,  (0.U((offsetSz-1).W))).asSInt
  val new_offset_value = (s1_update.bits.target.asSInt -
    (s1_update.bits.pc + (s1_update.bits.cfi_idx.bits << 1)).asSInt)
  val offset_is_extended = (new_offset_value > max_offset_value ||
                            new_offset_value < min_offset_value)


  val s1_update_wbtb_data  = Wire(new BTBEntry)
  s1_update_wbtb_data.extended := offset_is_extended
  s1_update_wbtb_data.offset   := new_offset_value
  val s1_update_wbtb_mask = (UIntToOH(s1_update_cfi_idx) &
    Fill(bankWidth, s1_update.bits.cfi_idx.valid && s1_update.valid && s1_update.bits.cfi_taken && s1_update.bits.is_commit_update))

  val s1_update_wmeta_mask = ((s1_update_wbtb_mask | s1_update.bits.br_mask) &
    (Fill(bankWidth, s1_update.valid && s1_update.bits.is_commit_update) |
     (Fill(bankWidth, s1_update.valid) & s1_update.bits.btb_mispredicts)
    )
  )
  val s1_update_wmeta_data = Wire(Vec(bankWidth, new BTBMeta))

  for (w <- 0 until bankWidth) {
    s1_update_wmeta_data(w).tag     := Mux(s1_update.bits.btb_mispredicts(w), 0.U, s1_update_lookup_idx >> log2Ceil(nSets))
    s1_update_wmeta_data(w).is_br   := s1_update.bits.br_mask(w)
  }

  for (w <- 0 until nWays) {
    when (doing_reset || s1_update_meta.write_way === w.U || (w == 0 && nWays == 1).B) {
      btb(w).write(
        Mux(doing_reset,
          reset_idx,
          s1_update_lookup_idx),
        Mux(doing_reset,
          VecInit(Seq.fill(bankWidth) { 0.U(btbEntrySz.W) }),
          VecInit(Seq.fill(bankWidth) { s1_update_wbtb_data.asUInt })),
        Mux(doing_reset,
          (~(0.U(bankWidth.W))),
          s1_update_wbtb_mask).asBools
      )
      meta(w).write(
        Mux(doing_reset,
          reset_idx,
          s1_update_lookup_idx),
        Mux(doing_reset,
          VecInit(Seq.fill(bankWidth) { 0.U(btbMetaSz.W) }),
          VecInit(s1_update_wmeta_data.map(_.asUInt))),
        Mux(doing_reset,
          (~(0.U(bankWidth.W))),
          s1_update_wmeta_mask).asBools
      )


    }
  }
  when (s1_update_wbtb_mask =/= 0.U && offset_is_extended) {
    ebtb.write(s1_update_lookup_idx, s1_update.bits.target)
  }

  when (io.log && s1_valid) {
    printf(p"[BTB][LOOKUP] pc=0x${Hexadecimal(s1_pc)} cpc_v=${s1_cvalid.asUInt} cpc=0x${Hexadecimal(s1_cpc)} idx=0x${Hexadecimal(s1_lookup_idx)} tag=0x${Hexadecimal(s1_req_tag(tagSz-1,0))} any_hit=${s1_any_hit.asUInt} hit_oh=0x${Hexadecimal(s1_hit_oh_cat)} hit_way=0x${Hexadecimal(s1_hit_way_cat)} resp_v=0x${Hexadecimal(s1_resp_v_mask)} br_mask=0x${Hexadecimal(s1_resp_br_mask)} jal_mask=0x${Hexadecimal(s1_resp_jal_mask)} ebtb=0x${Hexadecimal(s1_req_rebtb)}\n")
  }

  when (io.log && s1_update.valid) {
    printf(p"[BTB][UPDATE] pc=0x${Hexadecimal(s1_update.bits.pc)} cpc_v=${s1_update.bits.cpc_valid.asUInt} cpc=0x${Hexadecimal(s1_update.bits.cpc)} idx=0x${Hexadecimal(s1_update_lookup_idx)} tag=0x${Hexadecimal(s1_update_tag(tagSz-1,0))} target=0x${Hexadecimal(s1_update.bits.target)} cfi_v=${s1_update.bits.cfi_idx.valid.asUInt} cfi_idx=${s1_update.bits.cfi_idx.bits} taken=${s1_update.bits.cfi_taken.asUInt} br_mask=0x${Hexadecimal(s1_update.bits.br_mask)} btb_misp=0x${Hexadecimal(s1_update.bits.btb_mispredicts)} wbtb_mask=0x${Hexadecimal(s1_update_wbtb_mask)} wmeta_mask=0x${Hexadecimal(s1_update_wmeta_mask)} write_way=${s1_update_meta.write_way} offset=0x${Hexadecimal(new_offset_value.asUInt)} extended=${offset_is_extended.asUInt}\n")
  }

  // when (btbWatch && s1_update.valid) {
  //   printf("[BTB][UPD] pc=0x%x target=0x%x idx=0x%x cfiV=%c cfi_idx=%d taken=%c br_mask=0x%x btb_misp=0x%x off=0x%x ext=%c write_way=%d commit=%c\n",
  //     s1_update.bits.pc,
  //     s1_update.bits.target,
  //     s1_update_idx,
  //     BoolToChar(s1_update.bits.cfi_idx.valid,'T'),
  //     s1_update.bits.cfi_idx.bits,
  //     BoolToChar(s1_update.bits.cfi_taken,'T'),
  //     s1_update.bits.br_mask,
  //     s1_update.bits.btb_mispredicts,
  //     new_offset_value.asUInt,
  //     BoolToChar(offset_is_extended,'T'),
  //     s1_update_meta.write_way,
  //     BoolToChar(s1_update.bits.is_commit_update,'T'))
  //   printf("[BTB][UPD] wbtb_mask=0x%x wmeta_mask=0x%x\n", s1_update_wbtb_mask, s1_update_wmeta_mask)
  // }

}
