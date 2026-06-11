package boom.v3.ifu

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

import boom.v3.common._
import boom.v3.util.{BoomCoreStringPrefix}
import boom.v3.util.{BoolToChar}

class ComposedBranchPredictorBank(implicit p: Parameters) extends BranchPredictorBank()(p)
{

  val (components, resp) = getBPDComponents(io.resp_in(0), p)
  io.resp := resp

 
  // when (io.log)
  // { 
  //   val in1  = io.resp_in(0).f1(i)
  //   val out1 = io.resp.f1(i)
  //   printf("[CBPD][F1] i=%d IN(tgtV=%c tgt=0x%x) OUT(tgtV=%c tgt=0x%x)\n",
  //     i.U,
  //     BoolToChar(in1.predicted_pc.valid,'T'), in1.predicted_pc.bits,
  //     BoolToChar(out1.predicted_pc.valid,'T'), out1.predicted_pc.bits)
  //   printf("[CBPD] nComp=%d\n", components.length.U)
  // }

  var metas = 0.U(1.W)
  var meta_sz = 0
  for (c <- components) {
    c.io.f0_valid  := io.f0_valid
    c.io.f0_pc     := io.f0_pc
    c.io.f0_cpc    := io.f0_cpc
    c.io.f0_cpc_valid := io.f0_cpc_valid
    c.io.f0_addr_crypto_mode := io.f0_addr_crypto_mode
    c.io.f0_mask   := io.f0_mask
    c.io.f1_ghist  := io.f1_ghist
    c.io.f1_lhist  := io.f1_lhist
    c.io.f3_fire   := io.f3_fire
    c.io.log       := io.log
    if (c.metaSz > 0) {
      metas = (metas << c.metaSz) | c.io.f3_meta(c.metaSz-1,0)
    }
    meta_sz = meta_sz + c.metaSz
  }
  require(meta_sz < bpdMaxMetaLength)
  io.f3_meta := metas


  var update_meta = io.update.bits.meta
  for (c <- components.reverse) {
    c.io.update := io.update
    c.io.update.bits.meta := update_meta
    update_meta = update_meta >> c.metaSz
  }

  val mems = components.map(_.mems).flatten


  // ============================================================
  // DEBUG LOGS
  // ============================================================
  // val bpdWatchLo = "h8000230".U(vaddrBitsExtended.W)
  // val bpdWatchHi = "h8000240".U(vaddrBitsExtended.W)
  // def inBpdWatch(pc: UInt): Bool = pc >= bpdWatchLo && pc < bpdWatchHi
  // val dbg_watch =
  //   (io.f0_valid && inBpdWatch(io.f0_pc)) ||
  //   (s1_valid && inBpdWatch(s1_pc)) ||
  //   (s2_valid && inBpdWatch(RegNext(s1_pc))) ||
  //   (s3_valid && inBpdWatch(RegNext(s2_idx << (log2Ceil(fetchWidth) + 1)))) ||
  //   (io.update.valid && (inBpdWatch(io.update.bits.pc) || inBpdWatch(io.update.bits.target) || io.update.bits.target === 0.U))
  // val dbg_en = (io.log || dbg_watch) && (io.f0_valid || s1_valid || s2_valid || s3_valid || io.update.valid || io.f3_fire)

  // when (dbg_en) {
  //   // --- basic pipeline alignment (from BranchPredictorBank base class) ---
  //   // s0_pc/s1_pc/s2_idx/s3_idx are defined in BranchPredictorBank
  //   printf("[CBPD] s0_valid=%c s1_valid=%c s2_valid=%c s3_valid=%c f3_fire=%c updV=%c\n",
  //     BoolToChar(s0_valid,'T'),
  //     BoolToChar(s1_valid,'T'),
  //     BoolToChar(s2_valid,'T'),
  //     BoolToChar(s3_valid,'T'),
  //     BoolToChar(io.f3_fire,'T'),
  //     BoolToChar(io.update.valid,'T')
  //   )
  //   printf("[CBPD] s0_pc=0x%x s1_pc=0x%x s0_mask=0x%x s1_mask=0x%x s2_mask=0x%x s3_mask=0x%x\n",
  //     s0_pc, s1_pc, s0_mask, s1_mask, s2_mask, s3_mask
  //   )
  //   printf("[CBPD] idx: s0_idx=0x%x s1_idx=0x%x s2_idx=0x%x s3_idx=0x%x\n",
  //     s0_idx, s1_idx, s2_idx, s3_idx
  //   )
  // }
  // --- Print packed meta at F3 ---
  // when (dbg_en && (s3_valid || io.f3_fire)) {
  //   printf("[CBPD][META] packed_f3_meta=0x%x total_meta_sz=%d nComp=%d\n",
  //     io.f3_meta, meta_sz.U, components.length.U
  //   )
  // }

  // --- Print each component's metaSz and its f3_meta slice (low bits) ---
  // Note: component names are not easily printable at runtime; we index them.
  // for ((c, idx) <- components.zipWithIndex) {
  //   when (dbg_en && (s3_valid || io.f3_fire)) {
  //     if (c.metaSz > 0) {
  //       printf("[CBPD][C%d] metaSz=%d f3_meta_low=0x%x\n",
  //         idx.U, c.metaSz.U, c.io.f3_meta(c.metaSz-1, 0)
  //       )
  //     } else {
  //       printf("[CBPD][C%d] metaSz=0\n", idx.U)
  //     }
  //   }
  // }

  // --- Print bank response at each stage (F1/F2/F3 are vectors of bankWidth preds) ---
  // when (dbg_en && s1_valid) {
  //   printf("[CBPD][F1] pc=0x%x\n", s1_pc)
  //   for (i <- 0 until bankWidth) {
  //     val p1 = io.resp.f1(i)
  //     printf("[CBPD][F1]  i=%d br=%c jal=%c taken=%c tgtV=%c tgt=0x%x\n",
  //       i.U,
  //       BoolToChar(p1.is_br,'T'),
  //       BoolToChar(p1.is_jal,'T'),
  //       BoolToChar(p1.taken,'T'),
  //       BoolToChar(p1.predicted_pc.valid,'T'),
  //       p1.predicted_pc.bits
  //     )
  //     val in1  = io.resp_in(0).f1(i)
  //     val out1 = io.resp.f1(i)
  //     printf("[CBPD][F1] i=%d IN(tgtV=%c tgt=0x%x) OUT(tgtV=%c tgt=0x%x)\n",
  //       i.U,
  //       BoolToChar(in1.predicted_pc.valid,'T'), in1.predicted_pc.bits,
  //       BoolToChar(out1.predicted_pc.valid,'T'), out1.predicted_pc.bits)
  //     printf("[CBPD] nComp=%d\n", components.length.U)
  //     }
  // }

  // when (dbg_en && s2_valid) {
  //   printf("[CBPD][F2] (pc aligns w/ s2_idx) s1_pc_reg=0x%x\n", s1_pc)
  //   for (i <- 0 until bankWidth) {
  //     val p2 = io.resp.f2(i)
  //     printf("[CBPD][F2]  i=%d br=%c jal=%c taken=%c tgtV=%c tgt=0x%x\n",
  //       i.U,
  //       BoolToChar(p2.is_br,'T'),
  //       BoolToChar(p2.is_jal,'T'),
  //       BoolToChar(p2.taken,'T'),
  //       BoolToChar(p2.predicted_pc.valid,'T'),
  //       p2.predicted_pc.bits
  //     )
  //   }
  // }

  // when (dbg_en && s3_valid) {
  //   printf("[CBPD][F3] (pc aligns w/ s3_idx)\n")
  //   for (i <- 0 until bankWidth) {
  //     val p3 = io.resp.f3(i)
  //     printf("[CBPD][F3]  i=%d br=%c jal=%c taken=%c tgtV=%c tgt=0x%x\n",
  //       i.U,
  //       BoolToChar(p3.is_br,'T'),
  //       BoolToChar(p3.is_jal,'T'),
  //       BoolToChar(p3.taken,'T'),
  //       BoolToChar(p3.predicted_pc.valid,'T'),
  //       p3.predicted_pc.bits
  //     )
  //   }
  // }

  // --- Print update content (this is the one to catch target==0 on BTB fast repair) ---
  // when (dbg_en && io.update.valid) {
  //   val u = io.update.bits
  //   printf("[CBPD][UPD] pc=0x%x br_mask=0x%x btb_misp=0x%x mispred=%c repair=%c commit=%c\n",
  //     u.pc, u.br_mask, u.btb_mispredicts,
  //     BoolToChar(u.is_mispredict_update,'T'),
  //     BoolToChar(u.is_repair_update,'T'),
  //     BoolToChar(u.is_commit_update,'T')
  //   )
  //   printf("[CBPD][UPD] cfiV=%c cfi_idx=%d taken=%c mispred=%c is_br=%c is_jal=%c is_jalr=%c target=0x%x\n",
  //     BoolToChar(u.cfi_idx.valid,'T'),
  //     u.cfi_idx.bits,
  //     BoolToChar(u.cfi_taken,'T'),
  //     BoolToChar(u.cfi_mispredicted,'T'),
  //     BoolToChar(u.cfi_is_br,'T'),
  //     BoolToChar(u.cfi_is_jal,'T'),
  //     BoolToChar(u.cfi_is_jalr,'T'),
  //     u.target
  //   )
  //   printf("[CBPD][UPD] meta_full=0x%x\n", u.meta.asUInt)
  // }

  // --- Print per-component meta slice assignment during update unpacking ---
  // Recompute slices locally only for logging (so you can verify unpacking aligns w/ packing)
  // when (dbg_en && io.update.valid) {
  //   var um = io.update.bits.meta.asUInt
  //   for ((c, ridx) <- components.reverse.zipWithIndex) {
  //     val idx = (components.length - 1 - ridx).U
  //     if (c.metaSz > 0) {
  //       val slice = um(c.metaSz-1, 0)
  //       printf("[CBPD][UPD_META] comp=%d metaSz=%d slice=0x%x\n",
  //         idx, c.metaSz.U, slice
  //       )
  //       um = um >> c.metaSz
  //     } else {
  //       printf("[CBPD][UPD_META] comp=%d metaSz=0\n", idx)
  //     }
  //   }
  // }
}
