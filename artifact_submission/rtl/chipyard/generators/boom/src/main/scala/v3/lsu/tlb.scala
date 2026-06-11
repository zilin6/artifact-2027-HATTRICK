package boom.v3.lsu

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.rocket._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem.{CacheBlockBytes}
import freechips.rocketchip.diplomacy.{RegionType}
import freechips.rocketchip.util._

import boom.v3.common._
import boom.v3.exu.{BrResolutionInfo, Exception, FuncUnitResp, CommitSignals}
import boom.v3.util.{BoolToChar, AgePriorityEncoder, IsKilledByBranch, GetNewBrMask, WrapInc, IsOlder, UpdateBrMask}

// class TLBReq(lgMaxSize: Int)(implicit p: Parameters) extends CoreBundle()(p) {
//   /** request address from CPU. */
//   val vaddr = UInt(vaddrBitsExtended.W)
//   /** don't lookup TLB, bypass vaddr as paddr */
//   val passthrough = Bool()
//   /** granularity */
//   val size = UInt(log2Ceil(lgMaxSize + 1).W)
//   /** memory command. */
//   val cmd  = Bits(M_SZ.W)
//   val prv = UInt(PRV.SZ.W)
//   /** virtualization mode */
//   val v = Bool()

// }

//This two classes were added to the source code in replacement of their equivalent: TLBReq/TLBResp
////////////////////////////////////////////////////////////////////////////////////////////////
class TLBReqq(lgMaxSize: Int)(implicit p: Parameters) extends BoomBundle()(p)
with HasBoomUOP
{
  val vaddr = UInt(vaddrBitsExtended.W)
  val passthrough = Bool()
  val size = UInt(log2Ceil(lgMaxSize + 1).W)
  val cmd  = Bits(M_SZ.W)
  /** virtualization mode */
  val v = Bool()
  val prv = UInt(PRV.SZ.W)
}

// class TLBResp(lgMaxSize: Int = 3)(implicit p: Parameters) extends CoreBundle()(p) {
//   // lookup responses
//   val miss = Bool()
//   /** physical address */
//   val paddr = UInt(paddrBits.W)
//   val gpa = UInt(vaddrBitsExtended.W)
//   val gpa_is_pte = Bool()
//   /** page fault exception */
//   val pf = new TLBExceptions
//   /** guest page fault exception */
//   val gf = new TLBExceptions
//   /** access exception */
//   val ae = new TLBExceptions
//   /** misaligned access exception */
//   val ma = new TLBExceptions
//   /** if this address is cacheable */
//   val cacheable = Bool()
//   /** if caches must allocate this address */
//   val must_alloc = Bool()
//   /** if this address is prefetchable for caches*/
//   val prefetchable = Bool()
//   /** size/cmd of request that generated this response*/
//   val size = UInt(log2Ceil(lgMaxSize + 1).W)
//   val cmd = UInt(M_SZ.W)

// }
  
class TLBRespp(lgMaxSize: Int)(implicit p: Parameters) extends BoomBundle()(p) 
with HasBoomUOP
{
  // lookup responses
  val miss = Bool()
  val paddr = UInt(paddrBits.W)
  val pf = new TLBExceptions
  val ae = new TLBExceptions
  val ma = new TLBExceptions
  val cacheable = Bool()
  val must_alloc = Bool()
  val prefetchable = Bool()
  val gpa = UInt(vaddrBitsExtended.W)
  val gpa_is_pte = Bool()
  /** guest page fault exception */
  val gf = new TLBExceptions 
   /** size/cmd of request that generated this response*/
  val size = UInt(log2Ceil(lgMaxSize + 1).W) 
  val cmd = UInt(M_SZ.W)
}
 

////////////////////////////////////////////////////////////////////////////////////////////////


class NBDTLB(instruction: Boolean, lgMaxSize: Int, cfg: TLBConfig)(implicit edge: TLEdgeOut, p: Parameters) extends BoomModule()(p) {
  require(!instruction)
  val io = IO(new Bundle {
    //val req = Flipped(Vec(memWidth, Decoupled(new TLBReq(lgMaxSize))))
    val req = Flipped(Vec(memWidth, Decoupled(new TLBReqq(lgMaxSize))))
    val miss_rdy = Output(Bool())
    //val resp = Output(Vec(memWidth, new TLBResp))
    val resp = Output(Vec(memWidth, new TLBRespp(lgMaxSize)))
    val sfence = Input(Valid(new SFenceReq))
    val ptw = new TLBPTWIO
    val kill = Input(Bool())
    val log = Input(Bool())
    val cus_reg = Input(UInt(3.W))
  })
  private def printf(args: Any*): Unit = {}
  io.ptw := DontCare
  io.resp := DontCare

  class EntryData extends Bundle {
    val ppn = UInt(ppnBits.W)
    val s_mode_pf = Bool()
    val u = Bool()
    val g = Bool()
    val ae = Bool()
    val sw = Bool()
    val sx = Bool()
    val sr = Bool()
    val pw = Bool()
    val px = Bool()
    val pr = Bool()
    val pal = Bool() // AMO logical
    val paa = Bool() // AMO arithmetic
    val eff = Bool() // get/put effects
    val c = Bool()
    val fragmented_superpage = Bool()
   
  }

  class Entry(val nSectors: Int, val superpage: Boolean, val superpageOnly: Boolean) extends Bundle {
    require(nSectors == 1 || !superpage)
    require(isPow2(nSectors))
    require(!superpageOnly || superpage)

    val level = UInt(log2Ceil(pgLevels).W)
    val tag = UInt(vpnBits.W)
    val data = Vec(nSectors, UInt(new EntryData().getWidth.W))
    val valid = Vec(nSectors, Bool())
    def entry_data = data.map(_.asTypeOf(new EntryData))

    private def sectorIdx(vpn: UInt) = vpn.extract(log2Ceil(nSectors)-1, 0)
    def getData(vpn: UInt) = OptimizationBarrier(data(sectorIdx(vpn)).asTypeOf(new EntryData))
    def sectorHit(vpn: UInt) = valid.orR && sectorTagMatch(vpn)
    def sectorTagMatch(vpn: UInt) = ((tag ^ vpn) >> log2Ceil(nSectors)) === 0.U
    def hit(vpn: UInt) = {
      if (superpage && usingVM) {
        var tagMatch = valid.head
        for (j <- 0 until pgLevels) {
          val base = vpnBits - (j + 1) * pgLevelBits
          val ignore = level < j.U || (superpageOnly && j == pgLevels - 1).B
          tagMatch = tagMatch && (ignore || tag(base + pgLevelBits - 1, base) === vpn(base + pgLevelBits - 1, base))
        }
        tagMatch
      } else {
        val idx = sectorIdx(vpn)
        valid(idx) && sectorTagMatch(vpn)
      }
    }
    def ppn(vpn: UInt) = {
      val data = getData(vpn)
      if (superpage && usingVM) {
        var res = data.ppn >> pgLevelBits*(pgLevels - 1)
        for (j <- 1 until pgLevels) {
          val ignore = (level < j.U) || (superpageOnly && j == pgLevels - 1).B
          res = Cat(res, (Mux(ignore, vpn, 0.U) | data.ppn)(vpnBits - j*pgLevelBits - 1, vpnBits - (j + 1)*pgLevelBits))
        }
        res
      } else {
        data.ppn
      }
    }

    def insert(tag: UInt, level: UInt, entry: EntryData) {
      this.tag := tag
      this.level := level.extract(log2Ceil(pgLevels - superpageOnly.toInt)-1, 0)

      val idx = sectorIdx(tag)
      valid(idx) := true.B
      data(idx) := entry.asUInt
    }

    def invalidate() { valid.foreach(_ := false.B) }
    def invalidateVPN(vpn: UInt) {
      if (superpage) {
         when (hit(vpn)) { invalidate() }
      } else {
        when (sectorTagMatch(vpn)) { valid(sectorIdx(vpn)) := false.B }

        // For fragmented superpage mappings, we assume the worst (largest)
        // case, and zap entries whose most-significant VPNs match
         when (((tag ^ vpn) >> (pgLevelBits * (pgLevels - 1))) === 0.U) {
           for ((v, e) <- valid zip entry_data)
             when (e.fragmented_superpage) { v := false.B }
         }
      }
    }
    def invalidateNonGlobal() {
       for ((v, e) <- valid zip entry_data)
         when (!e.g) { v := false.B }
    }
  }

  def widthMap[T <: Data](f: Int => T) = VecInit((0 until memWidth).map(f))

  val pageGranularityPMPs = pmpGranularity >= (1 << pgIdxBits)
  val sectored_entries = Reg(Vec((cfg.nSets * cfg.nWays) / cfg.nSectors, new Entry(cfg.nSectors, false, false)))
  val superpage_entries = Reg(Vec(cfg.nSuperpageEntries, new Entry(1, true, true)))
  val special_entry = (!pageGranularityPMPs).option(Reg(new Entry(1, true, false)))
  def ordinary_entries = sectored_entries ++ superpage_entries
  def all_entries = ordinary_entries ++ special_entry

  val s_ready :: s_request :: s_wait :: s_wait_invalidate :: Nil = Enum(4)
  val state = RegInit(s_ready)
  val r_refill_tag = Reg(UInt(vpnBits.W))
  val r_superpage_repl_addr = Reg(UInt(log2Ceil(superpage_entries.size).W))
  val r_sectored_repl_addr = Reg(UInt(log2Ceil(sectored_entries.size).W))
  val r_sectored_hit_addr = Reg(UInt(log2Ceil(sectored_entries.size).W))
  val r_sectored_hit = Reg(Bool())
  val probeWatchPc = "h80000a2c".U(xLen.W)
  val probeWatchActive = RegInit(false.B)
  val probeWatchVpn = Reg(UInt(vpnBits.W))

  val priv = if (instruction) io.ptw.status.prv else io.ptw.status.dprv
  val priv_s = priv(0)
  val priv_uses_vm = priv <= PRV.S.U
  val vm_enabled = widthMap(w => usingVM.B && io.ptw.ptbr.mode(io.ptw.ptbr.mode.getWidth-1) && priv_uses_vm && !io.req(w).bits.passthrough)


  // val vpn_sd_or_ld = widthMap(w => io.req(w).bits.uop.ctrl.is_std)
  // 现在的问题是 memcpy中 load 没有使用虚拟地址
  // val vm_enabled = if (instruction)  widthMap(w => usingVM.B && io.ptw.ptbr.mode(io.ptw.ptbr.mode.getWidth-1) && priv_uses_vm && !io.req(w).bits.passthrough)   else widthMap(w =>  usingVM.B && io.ptw.ptbr.mode(io.ptw.ptbr.mode.getWidth-1) && priv_uses_vm && !io.req(w).bits.passthrough && Mux( io.ptw.status.mprv  , Mux(vpn_sd_or_ld(w), (io.cus_reg(1) === 1.U), (io.cus_reg(0) === 1.U)) ,true.B ) )
  // val vm_enabled_load = widthMap(w => usingVM.B && io.ptw.ptbr.mode(io.ptw.ptbr.mode.getWidth-1) && priv_uses_vm && io.cus_reg(0) && !io.req(w).bits.passthrough )
  // val vm_enabled_store = widthMap(w => usingVM.B && io.ptw.ptbr.mode(io.ptw.ptbr.mode.getWidth-1) &&  && io.cus_reg(1) && !io.req(w).bits.passthrough )
  when(false.B && io.log)
  {
    printf("mprv : 0x%x\n",io.ptw.status.mprv);
    for (w <- 0 until memWidth)
    {
      // printf("vm_enabled[%d] : %x ",w.U,vm_enabled(w));
    }
    printf("\n");
      // vm_enabled
    for (w <- 0 until memWidth)
    {
      // printf("vpn_sd_or_ld[%d] : %x ",w.U,vpn_sd_or_ld(w));
      printf("cus_reg[1] : %d\n",io.cus_reg(1));
      printf("cus_reg[0] : %d\n",io.cus_reg(0));
    }
    printf("\n");
  }
  // share a single physical memory attribute checker (unshare if critical path)
  val vpn = widthMap(w => io.req(w).bits.vaddr(vaddrBits-1, pgIdxBits))
  val refill_ppn = io.ptw.resp.bits.pte.ppn(ppnBits-1, 0)
  val do_refill = usingVM.B && io.ptw.resp.valid
  val invalidate_refill = state.isOneOf(s_request /* don't care */, s_wait_invalidate) || io.sfence.valid
  val mpu_ppn = widthMap(w =>
                Mux(do_refill, refill_ppn,
                Mux(vm_enabled(w) && special_entry.nonEmpty.B, special_entry.map(_.ppn(vpn(w))).getOrElse(0.U), io.req(w).bits.vaddr >> pgIdxBits)))
  val mpu_physaddr = widthMap(w => Cat(mpu_ppn(w), io.req(w).bits.vaddr(pgIdxBits-1, 0)))
  val pmp = Seq.fill(memWidth) { Module(new PMPChecker(lgMaxSize)) }
  for (w <- 0 until memWidth) {
    pmp(w).io.addr := mpu_physaddr(w)
    pmp(w).io.size := io.req(w).bits.size
    pmp(w).io.pmp := (io.ptw.pmp: Seq[PMP])
    pmp(w).io.prv := Mux(usingVM.B && (do_refill || io.req(w).bits.passthrough /* PTW */), PRV.S.U, priv) // TODO should add separate bit to track PTW
  }
  val legal_address = widthMap(w => edge.manager.findSafe(mpu_physaddr(w)).reduce(_||_))
  def fastCheck(member: TLManagerParameters => Boolean, w: Int) =
    legal_address(w) && edge.manager.fastProperty(mpu_physaddr(w), member, (b:Boolean) => b.B)
  val supports_get = widthMap(w => fastCheck(_.supportsGet, w))
  val supports_put = widthMap(w => fastCheck(_.supportsPutFull, w))
  val supports_exec = widthMap(w => fastCheck(_.executable, w))
  val cacheable = widthMap(w => fastCheck(_.supportsAcquireT, w) && (instruction || !usingDataScratchpad).B)
  val homogeneous = widthMap(w => TLBPageLookup(edge.manager.managers, xLen, p(CacheBlockBytes), BigInt(1) << pgIdxBits, 1 << lgMaxSize)(mpu_physaddr(w)).homogeneous)
  val prot_r   = widthMap(w => supports_get(w) && pmp(w).io.r)
  val prot_w   = widthMap(w => supports_put(w) && pmp(w).io.w)
  val prot_al  = widthMap(w => fastCheck(_.supportsLogical, w))
  val prot_aa  = widthMap(w => fastCheck(_.supportsArithmetic, w))
  val prot_x   = widthMap(w => supports_exec(w) && pmp(w).io.x)
  val prot_eff = widthMap(w => fastCheck(Seq(RegionType.PUT_EFFECTS, RegionType.GET_EFFECTS) contains _.regionType, w))

  val sector_hits = widthMap(w => VecInit(sectored_entries.map(_.sectorHit(vpn(w)))))
  val superpage_hits = widthMap(w => VecInit(superpage_entries.map(_.hit(vpn(w)))))
  val hitsVec = widthMap(w => VecInit(all_entries.map(vm_enabled(w) && _.hit(vpn(w)))))
  val real_hits = widthMap(w => hitsVec(w).asUInt)
  val hits = widthMap(w => Cat(!vm_enabled(w), real_hits(w)))
  val ppn = widthMap(w => Mux1H(hitsVec(w) :+ !vm_enabled(w), all_entries.map(_.ppn(vpn(w))) :+ vpn(w)(ppnBits-1, 0)))

    // permission bit arrays
    when (do_refill) {
      val pte = io.ptw.resp.bits.pte
      val newEntry = Wire(new EntryData)
    newEntry.ppn := pte.ppn
    newEntry.c := cacheable(0)
    newEntry.u := pte.u
    newEntry.g := pte.g
    newEntry.ae := io.ptw.resp.bits.ae_final
    newEntry.sr := pte.sr()
    newEntry.sw := pte.sw()
    newEntry.sx := pte.sx()
    newEntry.pr := prot_r(0)
    newEntry.pw := prot_w(0)
    newEntry.px := prot_x(0)
    newEntry.pal := prot_al(0)
    newEntry.paa := prot_aa(0)
    newEntry.eff := prot_eff(0)
    newEntry.fragmented_superpage := io.ptw.resp.bits.fragmented_superpage

    newEntry.s_mode_pf := pte.s_mode_pf()
    
    // printf("vpn : 0x%x\n",pte.vpn);
    when (special_entry.nonEmpty.B && !io.ptw.resp.bits.homogeneous) {
      special_entry.foreach(_.insert(r_refill_tag, io.ptw.resp.bits.level, newEntry))
    }.elsewhen (io.ptw.resp.bits.level < (pgLevels-1).U) {
      for ((e, i) <- superpage_entries.zipWithIndex) when (r_superpage_repl_addr === i.U) {
        e.insert(r_refill_tag, io.ptw.resp.bits.level, newEntry)
      }
      }.otherwise {
        val waddr = Mux(r_sectored_hit, r_sectored_hit_addr, r_sectored_repl_addr)
        for ((e, i) <- sectored_entries.zipWithIndex) when (waddr === i.U) {
          when (!r_sectored_hit) { e.invalidate() }
          e.insert(r_refill_tag, 0.U, newEntry)
        }
      }
      when (probeWatchActive && r_refill_tag === probeWatchVpn) {
        chisel3.printf("[DTLB-PROBE-REFILL] vpn=0x%x level=%d ppn=0x%x pte_v=%d pte_u=%d pte_r=%d pte_w=%d pte_x=%d pte_a=%d pte_d=%d ae_ptw=%d ae_final=%d homogeneous=%d fragmented=%d\n",
          r_refill_tag,
          io.ptw.resp.bits.level,
          pte.ppn,
          pte.v,
          pte.u,
          pte.r,
          pte.w,
          pte.x,
          pte.a,
          pte.d,
          io.ptw.resp.bits.ae_ptw,
          io.ptw.resp.bits.ae_final,
          io.ptw.resp.bits.homogeneous,
          io.ptw.resp.bits.fragmented_superpage)
      }
    }

  val entries = widthMap(w => VecInit(all_entries.map(_.getData(vpn(w)))))
  val normal_entries = widthMap(w => VecInit(ordinary_entries.map(_.getData(vpn(w)))))
  val nPhysicalEntries = 1 + special_entry.size
  val ptw_ae_array = widthMap(w => Cat(false.B, entries(w).map(_.ae).asUInt))
  val priv_rw_ok   = widthMap(w => Mux(!priv_s || io.ptw.status.sum, entries(w).map(_.u).asUInt, 0.U) | Mux(priv_s, ~entries(w).map(_.u).asUInt, 0.U))
  val priv_x_ok    = widthMap(w => Mux(priv_s, ~entries(w).map(_.u).asUInt, entries(w).map(_.u).asUInt))
  val r_array      = widthMap(w => Cat(true.B, priv_rw_ok(w) & (entries(w).map(_.sr).asUInt | Mux(io.ptw.status.mxr, entries(w).map(_.sx).asUInt, 0.U))))
  val w_array      = widthMap(w => Cat(true.B, priv_rw_ok(w) & entries(w).map(_.sw).asUInt))
  val x_array      = widthMap(w => Cat(true.B, priv_x_ok(w)  & entries(w).map(_.sx).asUInt))
  val pr_array     = widthMap(w => Cat(Fill(nPhysicalEntries, prot_r(w))   , normal_entries(w).map(_.pr).asUInt) & ~ptw_ae_array(w))
  val pw_array     = widthMap(w => Cat(Fill(nPhysicalEntries, prot_w(w))   , normal_entries(w).map(_.pw).asUInt) & ~ptw_ae_array(w))
  val px_array     = widthMap(w => Cat(Fill(nPhysicalEntries, prot_x(w))   , normal_entries(w).map(_.px).asUInt) & ~ptw_ae_array(w))
  val eff_array    = widthMap(w => Cat(Fill(nPhysicalEntries, prot_eff(w)) , normal_entries(w).map(_.eff).asUInt))
  val c_array      = widthMap(w => Cat(Fill(nPhysicalEntries, cacheable(w)), normal_entries(w).map(_.c).asUInt))
  val paa_array    = widthMap(w => Cat(Fill(nPhysicalEntries, prot_aa(w))  , normal_entries(w).map(_.paa).asUInt))
  val pal_array    = widthMap(w => Cat(Fill(nPhysicalEntries, prot_al(w))  , normal_entries(w).map(_.pal).asUInt))
  val paa_array_if_cached = widthMap(w => paa_array(w) | Mux(usingAtomicsInCache.B, c_array(w), 0.U))
  val pal_array_if_cached = widthMap(w => pal_array(w) | Mux(usingAtomicsInCache.B, c_array(w), 0.U))
  val prefetchable_array  = widthMap(w => Cat((cacheable(w) && homogeneous(w)) << (nPhysicalEntries-1), normal_entries(w).map(_.c).asUInt))

  val s_mode_pf_array = widthMap(w => Cat(false.B, entries(w).map(_.s_mode_pf).asUInt))
  val s_mode_custom_pf_array = widthMap(w =>
    Mux(vm_enabled(w) && priv_s, s_mode_pf_array(w), 0.U))

  val misaligned = widthMap(w => (io.req(w).bits.vaddr & (UIntToOH(io.req(w).bits.size) - 1.U)).orR)
  val bad_va = widthMap(w => if (!usingVM || (minPgLevels == pgLevels && vaddrBits == vaddrBitsExtended)) false.B else vm_enabled(w) && {
    val nPgLevelChoices = pgLevels - minPgLevels + 1
    val minVAddrBits = pgIdxBits + minPgLevels * pgLevelBits
    (for (i <- 0 until nPgLevelChoices) yield {
      val mask = ((BigInt(1) << vaddrBitsExtended) - (BigInt(1) << (minVAddrBits + i * pgLevelBits - 1))).U
      val maskedVAddr = io.req(w).bits.vaddr & mask
      io.ptw.ptbr.additionalPgLevels === i.U && !(maskedVAddr === 0.U || maskedVAddr === mask)
    }).orR
  })

  val cmd_lrsc           = widthMap(w => usingAtomics.B && io.req(w).bits.cmd.isOneOf(M_XLR, M_XSC))
  val cmd_amo_logical    = widthMap(w => usingAtomics.B && isAMOLogical(io.req(w).bits.cmd))
  val cmd_amo_arithmetic = widthMap(w => usingAtomics.B && isAMOArithmetic(io.req(w).bits.cmd))
  val cmd_read           = widthMap(w => isRead(io.req(w).bits.cmd))
  val cmd_write          = widthMap(w => isWrite(io.req(w).bits.cmd))
  val cmd_write_perms    = widthMap(w => cmd_write(w) ||
    coreParams.haveCFlush.B && io.req(w).bits.cmd === M_FLUSH_ALL) // not a write, but needs write permissions

  val lrscAllowed = widthMap(w => Mux((usingDataScratchpad || usingAtomicsOnlyForIO).B, 0.U, c_array(w)))
  val ae_array = widthMap(w =>
    Mux(misaligned(w), eff_array(w), 0.U) |
    Mux(cmd_lrsc(w)  , ~lrscAllowed(w), 0.U))
  val ae_valid_array = widthMap(w => Cat(if (special_entry.isEmpty) true.B else Cat(true.B, Fill(special_entry.size, !do_refill)),
                                         Fill(normal_entries(w).size, true.B)))
  val ae_ld_array = widthMap(w => Mux(cmd_read(w), ae_array(w) | ~pr_array(w), 0.U))
  val ae_st_array = widthMap(w =>
    Mux(cmd_write_perms(w)   , ae_array(w) | ~pw_array(w), 0.U) |
    Mux(cmd_amo_logical(w)   , ~pal_array_if_cached(w), 0.U) |
    Mux(cmd_amo_arithmetic(w), ~paa_array_if_cached(w), 0.U))
  val must_alloc_array = widthMap(w =>
    Mux(cmd_amo_logical(w)   , ~paa_array(w), 0.U) |
    Mux(cmd_amo_arithmetic(w), ~pal_array(w), 0.U) |
    Mux(cmd_lrsc(w)          , ~0.U(pal_array(w).getWidth.W), 0.U))
  val ma_ld_array = widthMap(w => Mux(misaligned(w) && cmd_read(w) , ~eff_array(w), 0.U))
  val ma_st_array = widthMap(w => Mux(misaligned(w) && cmd_write(w), ~eff_array(w), 0.U))
  val pf_ld_array = widthMap(w =>
    Mux(cmd_read(w), ~(r_array(w) | ptw_ae_array(w)), 0.U))
  val pf_st_array = widthMap(w =>
    Mux(cmd_write_perms(w), ~(w_array(w) | ptw_ae_array(w)) | s_mode_custom_pf_array(w), 0.U))
  val pf_inst_array = widthMap(w => ~(x_array(w) | ptw_ae_array(w)))
  val tlb_hit = widthMap(w => real_hits(w).orR)
  val tlb_miss = widthMap(w => vm_enabled(w) && !bad_va(w) && !tlb_hit(w))

  val sectored_plru = new PseudoLRU(sectored_entries.size)
  val superpage_plru = new PseudoLRU(superpage_entries.size)
  for (w <- 0 until memWidth) {
    when (io.req(w).valid && vm_enabled(w)) {
      when (sector_hits(w).orR) { sectored_plru.access(OHToUInt(sector_hits(w))) }
      when (superpage_hits(w).orR) { superpage_plru.access(OHToUInt(superpage_hits(w))) }
    }
  }

  // Superpages create the possibility that two entries in the TLB may match.
  // This corresponds to a software bug, but we can't return complete garbage;
  // we must return either the old translation or the new translation.  This
  // isn't compatible with the Mux1H approach.  So, flush the TLB and report
  // a miss on duplicate entries.
  val multipleHits = widthMap(w => PopCountAtLeast(real_hits(w), 2))
  val ptw_root_watch = widthMap(w =>
    io.req(w).valid &&
    io.req(w).bits.passthrough &&
    (io.req(w).bits.vaddr(paddrBits-1, pgIdxBits) === "h8004c".U ||
     io.req(w).bits.vaddr(paddrBits-1, pgIdxBits) === "h8006d".U))
  val ptw_root_watch_d = widthMap(w => RegNext(ptw_root_watch(w), false.B))
  val ptw_root_vaddr_d = widthMap(w => RegEnable(io.req(w).bits.vaddr, ptw_root_watch(w)))
  val ptw_root_paddr_d = widthMap(w => RegEnable(mpu_physaddr(w), ptw_root_watch(w)))
  val ptw_root_legal_d = widthMap(w => RegEnable(legal_address(w), ptw_root_watch(w)))
  val ptw_root_sup_get_d = widthMap(w => RegEnable(supports_get(w), ptw_root_watch(w)))
  val ptw_root_sup_put_d = widthMap(w => RegEnable(supports_put(w), ptw_root_watch(w)))
  val ptw_root_sup_exec_d = widthMap(w => RegEnable(supports_exec(w), ptw_root_watch(w)))
  val ptw_root_pmp_r_d = widthMap(w => RegEnable(pmp(w).io.r, ptw_root_watch(w)))
  val ptw_root_pmp_w_d = widthMap(w => RegEnable(pmp(w).io.w, ptw_root_watch(w)))
  val ptw_root_pmp_x_d = widthMap(w => RegEnable(pmp(w).io.x, ptw_root_watch(w)))
  val ptw_root_prot_r_d = widthMap(w => RegEnable(prot_r(w), ptw_root_watch(w)))
  val ptw_root_prot_w_d = widthMap(w => RegEnable(prot_w(w), ptw_root_watch(w)))
  val ptw_root_prot_x_d = widthMap(w => RegEnable(prot_x(w), ptw_root_watch(w)))
  val ptw_root_miss_d = widthMap(w => RegEnable(do_refill || tlb_miss(w) || multipleHits(w), ptw_root_watch(w)))
  val ptw_root_ae_ld_d = widthMap(w => RegEnable((ae_valid_array(w) & ae_ld_array(w) & hits(w)).orR, ptw_root_watch(w)))
  val ptw_root_pf_ld_d = widthMap(w => RegEnable((bad_va(w) && cmd_read(w)) || (pf_ld_array(w) & hits(w)).orR, ptw_root_watch(w)))
  val ptw_root_tlb_hit_d = widthMap(w => RegEnable(tlb_hit(w), ptw_root_watch(w)))
  val ptw_root_hits_d = widthMap(w => RegEnable(hits(w), ptw_root_watch(w)))
  val ptw_root_pr_d = widthMap(w => RegEnable(pr_array(w), ptw_root_watch(w)))
  val ptw_root_ae_ld_arr_d = widthMap(w => RegEnable(ae_ld_array(w), ptw_root_watch(w)))
  val ptw_root_pf_ld_arr_d = widthMap(w => RegEnable(pf_ld_array(w), ptw_root_watch(w)))

  io.miss_rdy := state === s_ready
  for (w <- 0 until memWidth) 
  {
    io.req(w).ready    := true.B
    io.resp(w).pf.ld   := (bad_va(w) && cmd_read(w)) || (pf_ld_array(w) & hits(w)).orR
    io.resp(w).pf.st   := (bad_va(w) && cmd_write_perms(w)) || (pf_st_array(w) & hits(w)).orR
    when (io.req(w).valid && (!vm_enabled(w) || !priv_s)) {
      assert(s_mode_custom_pf_array(w) === 0.U,
        "S-mode custom page fault bit affected non-S or VM-disabled request")
    }
    when (io.req(w).valid && io.req(w).bits.passthrough && cmd_read(w)) {
      assert(!io.resp(w).pf.ld,
        "physical passthrough/PTW request got load page fault")
    }
    val dbgIfetchStartupLo = "h800001c0".U(vaddrBitsExtended.W)
    val dbgIfetchStartupHi = "h80000640".U(vaddrBitsExtended.W)
    val dbgIfetchStartup = io.req(w).bits.vaddr >= dbgIfetchStartupLo && io.req(w).bits.vaddr < dbgIfetchStartupHi
    val dbgStrlen = io.req(w).bits.uop.debug_pc === "h4000152c".U ||
      io.req(w).bits.uop.debug_pc === "h40001530".U ||
      io.req(w).bits.uop.debug_pc === "h40001532".U ||
      io.req(w).bits.uop.debug_pc === "h8000152c".U ||
      io.req(w).bits.uop.debug_pc === "h80001530".U ||
      io.req(w).bits.uop.debug_pc === "h80001532".U
    val dbgVmPfTest = io.req(w).bits.prv =/= PRV.M.U
    val dbgVmPfData = (io.req(w).bits.vaddr >= "h40004000".U(vaddrBitsExtended.W) &&
      io.req(w).bits.vaddr < "h40006000".U(vaddrBitsExtended.W))
    val dbgProbeLoad =
      io.req(w).valid &&
      io.ptw.status.mprv &&
      io.ptw.status.dprv === PRV.U.U &&
      cmd_read(w) &&
      (io.req(w).bits.vaddr(vaddrBitsExtended-1, pgIdxBits) === "h2e7b8ad".U ||
       io.req(w).bits.vaddr(vaddrBitsExtended-1, pgIdxBits) === "h40000".U)
    when (dbgProbeLoad) {
      chisel3.printf("[DTLB-PROBE-RAW] lane=%d pc=0x%x inst=0x%x prv=%d mprv=%d dprv=%d vaddr=0x%x vpn=0x%x paddr=0x%x vm=%d bad_va=%d miss=%d tlb_hit=%d hits=0x%x ae_ld_arr=0x%x pf_ld_arr=0x%x ae_ld=%d pf_ld=%d legal=%d prot_r=%d ptw_ae=0x%x\n",
        w.U,
        io.req(w).bits.uop.debug_pc,
        io.req(w).bits.uop.debug_inst,
        io.req(w).bits.prv,
        io.ptw.status.mprv,
        io.ptw.status.dprv,
        io.req(w).bits.vaddr,
        vpn(w),
        io.resp(w).paddr,
        vm_enabled(w),
        bad_va(w),
        io.resp(w).miss,
        tlb_hit(w),
        hits(w),
        ae_ld_array(w),
        pf_ld_array(w),
        io.resp(w).ae.ld,
        io.resp(w).pf.ld,
        legal_address(w),
        prot_r(w),
        ptw_ae_array(w))
    }
    when(dbgStrlen && io.req(w).valid)
    {
      printf("[STRLEN-TLB-RAW] lane=%d pc=0x%x inst=0x%x rob=%d vpn=0x%x vaddr=0x%x paddr=0x%x vm_enabled=%d cmd_read=%d cmd_write=%d bad_va=%d tlb_hit=0x%x hits=0x%x pf_ld_array=0x%x pf_st_array=0x%x ae_ld_array=0x%x ae_st_array=0x%x ae_valid_array=0x%x r_array=0x%x w_array=0x%x x_array=0x%x ptw_ae_array=0x%x miss=%d pf_ld=%d pf_st=%d ae_ld=%d ae_st=%d\n",
        w.U,
        io.req(w).bits.uop.debug_pc,
        io.req(w).bits.uop.debug_inst,
        io.req(w).bits.uop.rob_idx,
        vpn(w),
        io.req(w).bits.vaddr,
        io.resp(w).paddr,
        vm_enabled(w),
        cmd_read(w),
        cmd_write(w),
        bad_va(w),
        tlb_hit(w),
        hits(w),
        pf_ld_array(w),
        pf_st_array(w),
        ae_ld_array(w),
        ae_st_array(w),
        ae_valid_array(w),
        r_array(w),
        w_array(w),
        x_array(w),
        ptw_ae_array(w),
        tlb_miss(w),
        io.resp(w).pf.ld,
        io.resp(w).pf.st,
        io.resp(w).ae.ld,
        io.resp(w).ae.st)
    }
    
   
    when (io.req(w).valid && io.req(w).bits.passthrough && cmd_read(w)) {
      chisel3.printf("[DTLB-PTW-PHYS] lane=%d prv=%d vaddr=0x%x vpn=0x%x mpu_ppn=0x%x mpu_paddr=0x%x size=%d legal=%d pmp_r=%d pmp_w=%d pmp_x=%d prot_r=%d prot_w=%d prot_x=%d cacheable=%d homogeneous=%d miss=%d tlb_hit=%d hits=0x%x pr=0x%x ptw_ae=0x%x ae_ld_arr=0x%x pf_ld_arr=0x%x resp_ae_ld=%d resp_pf_ld=%d\n",
        w.U,
        io.req(w).bits.prv,
        io.req(w).bits.vaddr,
        vpn(w),
        mpu_ppn(w),
        mpu_physaddr(w),
        io.req(w).bits.size,
        legal_address(w),
        pmp(w).io.r,
        pmp(w).io.w,
        pmp(w).io.x,
        prot_r(w),
        prot_w(w),
        prot_x(w),
        cacheable(w),
        homogeneous(w),
        io.resp(w).miss,
        tlb_hit(w),
        hits(w),
        pr_array(w),
        ptw_ae_array(w),
        ae_ld_array(w),
        pf_ld_array(w),
        io.resp(w).ae.ld,
        io.resp(w).pf.ld)
    }
    when(false.B && io.log)
    {
      printf("dtlb\n");
      printf("bad_va : %c , cmd_write_perms : %c\n",BoolToChar( bad_va(w),'T'), BoolToChar( cmd_write_perms(w),'T'))
      printf("pf_st_array : 0x%x\n", pf_st_array(w))
      printf("hits : 0x%x\n",hits(w))
      printf("ae_array : %x \n",ae_array(w));
      printf("pw_array(w) : %x\n",pw_array(w));
    }
    io.resp(w).pf.inst := bad_va(w) || (pf_inst_array(w) & hits(w)).orR
    io.resp(w).ae.ld   := (ae_valid_array(w) & ae_ld_array(w) & hits(w)).orR
    io.resp(w).ae.st   := (ae_valid_array(w) & ae_st_array(w) & hits(w)).orR
    io.resp(w).ae.inst := (ae_valid_array(w) & ~px_array(w)   & hits(w)).orR
    io.resp(w).ma.ld   := (ma_ld_array(w) & hits(w)).orR
    io.resp(w).ma.st   := (ma_st_array(w) & hits(w)).orR
    io.resp(w).ma.inst := false.B // this is up to the pipeline to figure out
    io.resp(w).cacheable    := (c_array(w) & hits(w)).orR
    io.resp(w).must_alloc   := (must_alloc_array(w) & hits(w)).orR
    io.resp(w).prefetchable := (prefetchable_array(w) & hits(w)).orR && edge.manager.managers.forall(m => !m.supportsAcquireB || m.supportsHint).B
    val dbgPtwRootMpu =
      mpu_physaddr(w)(paddrBits-1, pgIdxBits) === "h8004c".U ||
      mpu_physaddr(w)(paddrBits-1, pgIdxBits) === "h8006d".U
    when (dbgPtwRootMpu) {
      chisel3.printf("[DTLB-PTW-ROOT-MPU] lane=%d req_valid=%d passthrough=%d vaddr=0x%x mpu_paddr=0x%x legal=%d sup_get=%d sup_put=%d sup_x=%d pmp_r=%d pmp_w=%d pmp_x=%d prot_r=%d prot_w=%d prot_x=%d miss=%d ae_ld=%d pf_ld=%d tlb_hit=%d hits=0x%x pr=0x%x ae_ld_arr=0x%x pf_ld_arr=0x%x\n",
        w.U,
        io.req(w).valid,
        io.req(w).bits.passthrough,
        io.req(w).bits.vaddr,
        mpu_physaddr(w),
        legal_address(w),
        supports_get(w),
        supports_put(w),
        supports_exec(w),
        pmp(w).io.r,
        pmp(w).io.w,
        pmp(w).io.x,
        prot_r(w),
        prot_w(w),
        prot_x(w),
        io.resp(w).miss,
        io.resp(w).ae.ld,
        io.resp(w).pf.ld,
        tlb_hit(w),
        hits(w),
        pr_array(w),
        ae_ld_array(w),
        pf_ld_array(w))
    }
    when (ptw_root_watch_d(w)) {
      chisel3.printf("[DTLB-PTW-ROOT-CHECK] lane=%d vaddr=0x%x mpu_paddr=0x%x legal=%d sup_get=%d sup_put=%d sup_x=%d pmp_r=%d pmp_w=%d pmp_x=%d prot_r=%d prot_w=%d prot_x=%d miss=%d ae_ld=%d pf_ld=%d tlb_hit=%d hits=0x%x pr=0x%x ae_ld_arr=0x%x pf_ld_arr=0x%x\n",
        w.U,
        ptw_root_vaddr_d(w),
        ptw_root_paddr_d(w),
        ptw_root_legal_d(w),
        ptw_root_sup_get_d(w),
        ptw_root_sup_put_d(w),
        ptw_root_sup_exec_d(w),
        ptw_root_pmp_r_d(w),
        ptw_root_pmp_w_d(w),
        ptw_root_pmp_x_d(w),
        ptw_root_prot_r_d(w),
        ptw_root_prot_w_d(w),
        ptw_root_prot_x_d(w),
        ptw_root_miss_d(w),
        ptw_root_ae_ld_d(w),
        ptw_root_pf_ld_d(w),
        ptw_root_tlb_hit_d(w),
        ptw_root_hits_d(w),
        ptw_root_pr_d(w),
        ptw_root_ae_ld_arr_d(w),
        ptw_root_pf_ld_arr_d(w))
      for (pmpIndex <- 0 until 8) {
        chisel3.printf("[DTLB-PTW-PMP] idx=%d addr=0x%x cfg_l=%d cfg_a=0x%x cfg_r=%d cfg_w=%d cfg_x=%d\n",
          pmpIndex.U,
          io.ptw.pmp(pmpIndex).addr,
          io.ptw.pmp(pmpIndex).cfg.l,
          io.ptw.pmp(pmpIndex).cfg.a,
          io.ptw.pmp(pmpIndex).cfg.r,
          io.ptw.pmp(pmpIndex).cfg.w,
          io.ptw.pmp(pmpIndex).cfg.x)
      }
    }
    io.resp(w).miss  := do_refill || tlb_miss(w) || multipleHits(w)
    io.resp(w).paddr := Cat(ppn(w), io.req(w).bits.vaddr(pgIdxBits-1, 0))
    io.resp(w).size := io.req(w).bits.size
    io.resp(w).cmd := io.req(w).bits.cmd
    when (io.req(w).valid && (dbgIfetchStartup || io.resp(w).ae.inst || io.resp(w).pf.inst)) {
      printf("[ITLB-TRACE] lane=%d vaddr=0x%x paddr=0x%x vm=%d prv=%d v=%d bad_va=%d miss=%d tlb_hit=0x%x hits=0x%x x_array=0x%x px_array=0x%x ae_valid=0x%x ptw_ae=0x%x pf_inst=%d ae_inst=%d\n",
        w.U,
        io.req(w).bits.vaddr,
        io.resp(w).paddr,
        vm_enabled(w),
        io.req(w).bits.prv,
        io.req(w).bits.v,
        bad_va(w),
        io.resp(w).miss,
        tlb_hit(w),
        hits(w),
        x_array(w),
        px_array(w),
        ae_valid_array(w),
        ptw_ae_array(w),
        io.resp(w).pf.inst,
        io.resp(w).ae.inst)
    }
  }

  io.ptw.req.valid := state === s_request
  io.ptw.req.bits.valid := !io.kill
  io.ptw.req.bits.bits.addr := r_refill_tag
  if (usingVM) {
    val sfence = io.sfence.valid
    for (w <- 0 until memWidth) {
      when (io.req(w).fire && tlb_miss(w) && state === s_ready) {
        state := s_request
        r_refill_tag := vpn(w)
        r_superpage_repl_addr := replacementEntry(superpage_entries, superpage_plru.way)
        r_sectored_repl_addr  := replacementEntry(sectored_entries, sectored_plru.way)
        r_sectored_hit_addr   := OHToUInt(sector_hits(w))
        r_sectored_hit        := sector_hits(w).orR
        when (io.req(w).bits.uop.debug_pc === probeWatchPc) {
          probeWatchActive := true.B
          probeWatchVpn := vpn(w)
          chisel3.printf("[DTLB-PROBE-MISS] lane=%d pc=0x%x vaddr=0x%x vpn=0x%x cmd=0x%x prv=%d miss=%d bad_va=%d tlb_hit=%d hits=0x%x satp_mode=0x%x ptbr_ppn=0x%x mprv=%d dprv=%d\n",
            w.U,
            io.req(w).bits.uop.debug_pc,
            io.req(w).bits.vaddr,
            vpn(w),
            io.req(w).bits.cmd,
            io.req(w).bits.prv,
            tlb_miss(w),
            bad_va(w),
            tlb_hit(w),
            hits(w),
            io.ptw.ptbr.mode,
            io.ptw.ptbr.ppn,
            io.ptw.status.mprv,
            io.ptw.status.dprv)
        }
      }
    }
    when (state === s_request) {
      when (probeWatchActive && r_refill_tag === probeWatchVpn) {
        chisel3.printf("[DTLB-PROBE-PTW-REQ] vpn=0x%x req_valid=%d req_ready=%d kill=%d\n",
          r_refill_tag,
          io.ptw.req.valid,
          io.ptw.req.ready,
          io.kill)
      }
      when (sfence) { state := s_ready }
      when (io.ptw.req.ready) { state := Mux(sfence, s_wait_invalidate, s_wait) }
      when (io.kill) { state := s_ready }
    }
    when (state === s_wait && sfence) {
      state := s_wait_invalidate
    }
    when (state === s_wait && probeWatchActive && r_refill_tag === probeWatchVpn) {
      chisel3.printf("[DTLB-PROBE-PTW-WAIT] vpn=0x%x resp_valid=%d ae_ptw=%d ae_final=%d pf=%d level=%d ppn=0x%x\n",
        r_refill_tag,
        io.ptw.resp.valid,
        io.ptw.resp.bits.ae_ptw,
        io.ptw.resp.bits.ae_final,
        io.ptw.resp.bits.pf,
        io.ptw.resp.bits.level,
        io.ptw.resp.bits.pte.ppn)
    }
    when (io.ptw.resp.valid) {
      state := s_ready
      when (probeWatchActive && r_refill_tag === probeWatchVpn) {
        probeWatchActive := false.B
      }
    }

    when (sfence) {
      for (w <- 0 until memWidth) {
        assert(!io.sfence.bits.rs1 || (io.sfence.bits.addr >> pgIdxBits) === vpn(w))
        for (e <- all_entries) {
          when (io.sfence.bits.rs1) { e.invalidateVPN(vpn(w)) }
          .elsewhen (io.sfence.bits.rs2) { e.invalidateNonGlobal() }
          .otherwise { e.invalidate() }
        }
      }
    }
    when (multipleHits.orR || reset.asBool) {
      all_entries.foreach(_.invalidate())
    }
  }

  def replacementEntry(set: Seq[Entry], alt: UInt) = {
    val valids = set.map(_.valid.orR).asUInt
    Mux(valids.andR, alt, PriorityEncoder(~valids))
  }
///////////////////////
  //Printing TLB entries
  // val printOnce = RegInit(true.B)
  // when (printOnce)
  // { 
  //   printf("///////////////////////////////////////\n")
  //   printf(p"AllEntries = $all_entries\n")
  //   printf(p"OrdinaryEtries = $ordinary_entries\n")
  //   printf(p"SectoredEntries = $sectored_entries\n")
  //   printf(p"SuperpageEntries = $superpage_entries\n")
  //   printf(p"SpecialEntries = $special_entry\n")
  //   printf("///////////////////////////////////////\n")
  //   printf("TLB Entries\n")
  //   printf("Sectored Entries\n")
  //   printOnce := false.B
  // }
  when(false.B && io.log)
  {
    for (j <- 0 until (cfg.nSets * cfg.nWays) / cfg.nSectors){   //This can be replaced with 2

      for (i <- 0 until cfg.nSectors){  ////This can be replaced with 4
        printf("Secotor[%d]Entry[%d]: ppn:0x%x, u: (%c), g: (%c), ae: (%c), sw: (%c), sx: (%c), sr: (%c), pw: (%c), px: (%c), pr: (%c), pal: (%c), paa: (%c), eff: (%c), c: (%c), fs: (%c) ",
          j.U, i.U, 
          sectored_entries(j).data(i).asTypeOf(new EntryData).ppn, BoolToChar(sectored_entries(0).data(i).asTypeOf(new EntryData).u, 'T'),
          BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).g, 'T'), BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).ae, 'T'),
          BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).sw, 'T'),BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).sx, 'T'),
          BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).sr, 'T'),BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).pw, 'T'),
          BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).px, 'T'),BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).pr, 'T'),
          BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).pal, 'T'),BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).paa, 'T'),
          BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).eff, 'T'),BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).c, 'T'),
          BoolToChar(sectored_entries(j).data(i).asTypeOf(new EntryData).fragmented_superpage, 'T'))
        printf("\n")
      }
    }
  }
  when(false.B && io.log)
  {
    printf("Superpage Entries\n")  
    for (j <- 0 until cfg.nSuperpageEntries)
    { ////This can be replaced with 4
      for (i <- 0 until 1){
        printf("Secotor[%d]Entry[%d]: ppn:0x%x, u: (%c), g: (%c), ae: (%c), sw: (%c), sx: (%c), sr: (%c), pw: (%c), px: (%c), pr: (%c), pal: (%c), paa: (%c), eff: (%c), c: (%c), fs: (%c) ",
          j.U, i.U, 
          superpage_entries(j).data(i).asTypeOf(new EntryData).ppn, BoolToChar(superpage_entries(0).data(i).asTypeOf(new EntryData).u, 'T'),
          BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).g, 'T'), BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).ae, 'T'),
          BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).sw, 'T'),BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).sx, 'T'),
          BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).sr, 'T'),BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).pw, 'T'),
          BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).px, 'T'),BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).pr, 'T'),
          BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).pal, 'T'),BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).paa, 'T'),
          BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).eff, 'T'),BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).c, 'T'),
          BoolToChar(superpage_entries(j).data(i).asTypeOf(new EntryData).fragmented_superpage, 'T'))
        printf("\n")
      }
    }
  }      
/////////////////////
}
