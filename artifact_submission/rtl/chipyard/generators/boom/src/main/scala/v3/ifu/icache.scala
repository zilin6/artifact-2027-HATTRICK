//******************************************************************************
// Copyright (c) 2017 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// ICache
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.v3.ifu

import chisel3._
import chisel3.util._
import chisel3.util.random._

import org.chipsalliance.cde.config.{Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.util.property._
import freechips.rocketchip.rocket.{CacheCryptoCounterBitsKey, CacheCryptoDebugLog, CacheCryptoRefillMeta, CacheCryptoWritebackMeta, CacheCryptoWritebackMetaField, HasL1ICacheParameters, ICacheParams, ICacheErrors, ICacheReq}




import boom.v3.common._
import boom.v3.util.{BoomCoreStringPrefix}

/**
 * ICache module
 *
 * @param icacheParams parameters for the icache
 * @param hartId the id of the hardware thread in the cache
 * @param enableBlackBox use a blackbox icache
 */
class ICache(
  val icacheParams: ICacheParams,
  val staticIdForMetadataUseOnly: Int)(implicit p: Parameters)
  extends LazyModule
{
  lazy val module = new ICacheModule(this)
  val masterNode = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1(
      sourceId = IdRange(0, 1 + icacheParams.prefetch.toInt), // 0=refill, 1=hint
      name = s"Core ${staticIdForMetadataUseOnly} ICache")),
    requestFields = Seq(CacheCryptoWritebackMetaField(
      counterBits = p(CacheCryptoCounterBitsKey))),
    responseKeys = Seq(CacheCryptoRefillMeta))))

  val size = icacheParams.nSets * icacheParams.nWays * icacheParams.blockBytes
  private val wordBytes = icacheParams.fetchBytes
}

/**
 * IO Signals leaving the ICache
 *
 * @param outer top level ICache class
 */
class ICacheResp(val outer: ICache) extends Bundle
{
  val data = UInt((outer.icacheParams.fetchBytes*8).W)
  val replay = Bool()
  val ae = Bool()
}

/**
 * IO Signals for interacting with the ICache
 *
 * @param outer top level ICache class
 */
class ICacheBundle(val outer: ICache) extends BoomBundle()(outer.p)
  with HasBoomFrontendParameters
{
  val req = Flipped(Decoupled(new ICacheReq))
  val s1_paddr = Input(UInt(paddrBits.W)) // delayed one cycle w.r.t. req

  val s1_kill = Input(Bool()) // delayed one cycle w.r.t. req
  val s2_kill = Input(Bool()) // delayed two cycles; prevents I$ miss emission

  val resp = Valid(new ICacheResp(outer))
  /////////////////////////////////////////////////
  val late_resp = Valid(new ICacheResp(outer))
  val s2_hit_late = Decoupled(UInt(vaddrBitsExtended.W))
  /////////////////////////////////////////////////
  val invalidate = Input(Bool())

  val perf = Output(new Bundle {
    val acquire = Bool()
  })

  val cacheCryptoEnable = Input(Bool())
  val dataKey = Input(UInt(128.W))
  val frontendEngineMode = Input(Bool())
  val log = Input(Bool())
}

/**
 * Get a tile-specific property without breaking deduplication
 */
object GetPropertyByHartId
{
  def apply[T <: Data](tiles: Seq[RocketTileParams], f: RocketTileParams => Option[T], hartId: UInt): T = {
    PriorityMux(tiles.collect { case t if f(t).isDefined => (t.tileId.U === hartId) -> f(t).get })
  }
}


/**
 * Main ICache module
 *
 * @param outer top level ICache class
 */
class ICacheModule(outer: ICache) extends LazyModuleImp(outer)
  with HasBoomFrontendParameters
{
  private def printf(args: Any*): Unit = {}
  val icacheCryptoAssertEnable = PlusArg("icache_crypto_assert_enable", 0, width = 1) =/= 0.U
  val icacheCryptoDebugLogEnable = CacheCryptoDebugLog.runtimeEnable
  private def assertOnlyWatchdog(waiting: Bool, limit: Int, message: String): Unit = {
    val enabledWaiting = icacheCryptoAssertEnable && waiting
    val cycles = RegInit(0.U(log2Ceil(limit + 1).W))
    when (!enabledWaiting) {
      cycles := 0.U
    } .elsewhen (cycles =/= limit.U) {
      cycles := cycles + 1.U
    }
    assert(!icacheCryptoAssertEnable || cycles =/= limit.U, message)
  }
  val enableICacheDelay = tileParams.core.asInstanceOf[BoomCoreParams].enableICacheDelay
  val icacheDebugCycle = RegInit(0.U(32.W))
  icacheDebugCycle := icacheDebugCycle + 1.U
  /////////////////////////////////////////////////////////////////
  val cacheCryptoCounterBits = p(CacheCryptoCounterBitsKey)
  /////////////////////////////////////////////////////////////////
  val io = IO(new ICacheBundle(outer))
  val (tl_out, edge_out) = outer.masterNode.out(0)

  //////////////////////////////////////////////////////////
  val cryptoEngine = Module(new BoomICacheCryptoEngine)
  //////////////////////////////////////////////////////////
  require(isPow2(nSets) && isPow2(nWays))
  require(usingVM)
  require(pgIdxBits >= untagBits)

  // How many bits do we intend to fetch at most every cycle?
  val wordBits = outer.icacheParams.fetchBytes*8
  // Each of these cases require some special-case handling.
  require (tl_out.d.bits.data.getWidth == wordBits || (2*tl_out.d.bits.data.getWidth == wordBits && nBanks == 2))
  // If TL refill is half the wordBits size and we have two banks, then the
  // refill writes to only one bank per cycle (instead of across two banks every
  // cycle).
  val refillsToOneBank = (2*tl_out.d.bits.data.getWidth == wordBits)



  val s0_valid = io.req.fire
  val s0_vaddr = io.req.bits.addr

  val s1_vaddr = RegEnable(s0_vaddr, 0.U(vaddrBitsExtended.W), s0_valid)
  val s1_valid = RegNext(s0_valid)
  val s1_tag_hit = Wire(Vec(nWays, Bool()))
  val s1_hit = s1_tag_hit.reduce(_||_)
  val s2_valid = RegNext(s1_valid && !io.s1_kill)
  val s2_hit = RegNext(s1_hit)
  //////////////////////////////////////////////////
  val s2_vaddr = RegEnable(s1_vaddr, 0.U(vaddrBitsExtended.W), s1_valid)
  val s2_paddr = RegEnable(io.s1_paddr, 0.U(paddrBits.W), s1_valid)
  val s1_req_crypto_line = RegEnable(io.cacheCryptoEnable, false.B, s0_valid)
  val s2_req_crypto_line = RegEnable(s1_req_crypto_line, false.B, s1_valid)
  ////////////////////////////////////////////////////

  val invalidated = Reg(Bool())
  val refill_valid = RegInit(false.B)
  val refill_fire = tl_out.a.fire
  val refillReqCryptoLine = RegEnable(s2_req_crypto_line, false.B, refill_fire)
  val s2_miss = s2_valid && !s2_hit && !RegNext(refill_valid)
  val refill_paddr = RegEnable(io.s1_paddr, s1_valid && !(refill_valid || s2_miss))
  val refill_tag = refill_paddr(tagBits+untagBits-1,untagBits)
  val refill_idx = refill_paddr(untagBits-1,blockOffBits)
  val refill_one_beat = tl_out.d.fire && edge_out.hasData(tl_out.d.bits)
  val debugWatchFetchLineBase = BigInt("80001700", 16).U(paddrBits.W)
  val debugWatchS1Line = (io.s1_paddr >> 6) === (debugWatchFetchLineBase >> 6)
  val debugWatchRefillLine = (refill_paddr >> 6) === (debugWatchFetchLineBase >> 6)
  val debugFetchWindowLo = BigInt("80000240", 16).U(paddrBits.W)
  val debugFetchWindowHi = BigInt("800002c0", 16).U(paddrBits.W)
  private def debugFetchWindow(addr: UInt): Bool = addr >= debugFetchWindowLo && addr < debugFetchWindowHi

  io.req.ready := !refill_one_beat

  val (_, _, d_done, refill_cnt) = edge_out.count(tl_out.d)
  val refill_done = refill_one_beat && d_done
  tl_out.d.ready := true.B
  assertOnlyWatchdog(
    refillReqCryptoLine && refill_valid && !refill_done && !io.invalidate,
    4096,
    "ICache refill valid but D beats did not complete")
  require (edge_out.manager.minLatency > 0)
  ////////////////////////////////////////////////////////////////////
  val refillCryptoMeta = tl_out.d.bits.user.lift(CacheCryptoRefillMeta)
  val refillHasCryptoMeta = refillCryptoMeta.isDefined.B

  val refillMetaCounter = WireDefault(0.U(cacheCryptoCounterBits.W))
  val refillMetaCryptoLine = WireDefault(false.B)
  refillCryptoMeta.foreach {
    m =>
      refillMetaCounter := m.counter
      refillMetaCryptoLine := m.cryptoLine
  }

  val repl_way = if (isDM) 0.U else LFSR(16, refill_fire)(log2Ceil(nWays)-1,0)

  val tag_array = SyncReadMem(nSets, Vec(nWays, UInt(tagBits.W)))
  val tag_rdata = tag_array.read(s0_vaddr(untagBits-1, blockOffBits), !refill_done && s0_valid)
  when (refill_done) {
    tag_array.write(refill_idx, VecInit(Seq.fill(nWays)(refill_tag)), Seq.tabulate(nWays)(repl_way === _.U))
  }

  val vb_array = RegInit(0.U((nSets*nWays).W))
  ////////////////////////////////////////////////////////////////
  val l1CtrMeta = RegInit(VecInit(Seq.fill(nSets)(VecInit(Seq.fill(nWays)(0.U(cacheCryptoCounterBits.W))))))
  val l1CryptoLineMeta = RegInit(VecInit(Seq.fill(nSets)(VecInit(Seq.fill(nWays)(false.B)))))
  ////////////////////////////////////////////////////////////////
  when (refill_one_beat) {
    vb_array := vb_array.bitSet(Cat(repl_way, refill_idx), refill_done && !invalidated)
  }
  ///////////////////////////////////////////////
  when (refill_done && !invalidated) {
    when (icacheCryptoAssertEnable && (refillReqCryptoLine || refillMetaCryptoLine)) {
      assert(refillMetaCryptoLine === refillReqCryptoLine,
      "ICache refill metadata cryptoLine does not match request mode")
    }
    l1CtrMeta(refill_idx)(repl_way) := refillMetaCounter
    l1CryptoLineMeta(refill_idx)(repl_way) := refillMetaCryptoLine
  }
  /////////////////////////////////////////////////////
  when (io.invalidate) {
    vb_array := 0.U
    invalidated := true.B
  }

  val s2_dout   = Wire(Vec(nWays, UInt(wordBits.W)))
  val s1_bankid = Wire(Bool())
  val s1_meta_idx = io.s1_paddr(untagBits-1,blockOffBits)
  val s1_meta_tag = io.s1_paddr(tagBits+untagBits-1,untagBits)

  for (i <- 0 until nWays) {
    val s1_idx = io.s1_paddr(untagBits-1,blockOffBits)
    val s1_tag = io.s1_paddr(tagBits+untagBits-1,untagBits)
    val s1_vb = vb_array(Cat(i.U, s1_idx))
    val tag = tag_rdata(i)
    /////////////////////////////////////////////////////////////
    // s1_tag_hit(i) := s1_vb && tag === s1_tag
    val s1_crypto_line_match = l1CryptoLineMeta(s1_idx)(i) === s1_req_crypto_line
    s1_tag_hit(i) := s1_vb && tag === s1_tag && s1_crypto_line_match
  }
  when (s1_valid && debugWatchS1Line) {
    printf(p"[ICACHE-META] s1_paddr=0x${Hexadecimal(io.s1_paddr)} idx=0x${Hexadecimal(s1_meta_idx)} tag=0x${Hexadecimal(s1_meta_tag)} " +
      p"reqCrypto=${s1_req_crypto_line} wayCrypto=0x${Hexadecimal(l1CryptoLineMeta(s1_meta_idx).asUInt)} tagHitOH=0x${Hexadecimal(s1_tag_hit.asUInt)}\n")
  }
  // when(io.log)
  // {
  //   printf("s1_hit : %d\n",s1_hit.asUInt)
  //   printf("s2_valid : %d\n",s2_valid.asUInt)
  //   printf("s2_hit : %d\n",s2_hit.asUInt)
  //   printf("s2_miss : %d\n",s2_miss.asUInt)
  // }
  assert(PopCount(s1_tag_hit) <= 1.U || !s1_valid)

  val ramDepth = if (refillsToOneBank && nBanks == 2) {
    nSets * refillCycles / 2
  } else {
    nSets * refillCycles
  }

  val dataArrays = if (nBanks == 1) {
    // Use unbanked icache for narrow accesses.
    (0 until nWays).map { x =>
      DescribedSRAM(
        name = s"dataArrayWay_${x}",
        desc = "ICache Data Array",
        size = ramDepth,
        data = UInt((wordBits).W)
      )
    }
  } else {
    // Use two banks, interleaved.
    (0 until nWays).map { x =>
      DescribedSRAM(
        name = s"dataArrayB0Way_${x}",
        desc = "ICache Data Array",
        size = ramDepth,
        data = UInt((wordBits/nBanks).W)
      )} ++
    (0 until nWays).map { x =>
      DescribedSRAM(
        name = s"dataArrayB1Way_${x}",
        desc = "ICache Data Array",
        size = ramDepth,
        data = UInt((wordBits/nBanks).W)
      )}
  }
  if (nBanks == 1) {
    // Use unbanked icache for narrow accesses.
    s1_bankid := 0.U
    for ((dataArray, i) <- dataArrays.zipWithIndex) {
      def row(addr: UInt) = addr(untagBits-1, blockOffBits-log2Ceil(refillCycles))
      val s0_ren = s0_valid

      val wen = (refill_one_beat && !invalidated) && repl_way === i.U

      val mem_idx = Mux(refill_one_beat, (refill_idx << log2Ceil(refillCycles)) | refill_cnt,
                    row(s0_vaddr))
      when (wen && debugWatchRefillLine) {
        printf(p"[ICACHE-WATCH] refill_write paddr=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} " +
          p"way=0x${Hexadecimal(repl_way)} beat=0x${Hexadecimal(refill_cnt)} data=0x${Hexadecimal(tl_out.d.bits.data)}\n")
        printf(p"[ICACHE-REFILL-TRACE] line=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} " +
          p"way=0x${Hexadecimal(repl_way)} beat=0x${Hexadecimal(refill_cnt)} mem_idx=0x${Hexadecimal(mem_idx)} " +
          p"write=0x${Hexadecimal(tl_out.d.bits.data)} refillCrypto=${refillMetaCryptoLine} " +
          p"refillCounter=0x${Hexadecimal(refillMetaCounter)}\n")
      }
      when (wen) {
        dataArray.write(mem_idx, tl_out.d.bits.data)
      }
      if (enableICacheDelay)
        s2_dout(i) := dataArray.read(RegNext(mem_idx), RegNext(!wen && s0_ren))
      else
        s2_dout(i) := RegNext(dataArray.read(mem_idx, !wen && s0_ren))
    }
  } else {
    // Use two banks, interleaved.
    val dataArraysB0 = dataArrays.take(nWays)
    val dataArraysB1 = dataArrays.drop(nWays)
    require (nBanks == 2)

    // Bank0 row's id wraps around if Bank1 is the starting bank.
    def b0Row(addr: UInt) =
      if (refillsToOneBank) {
        addr(untagBits-1, blockOffBits-log2Ceil(refillCycles)+1) + bank(addr)
      } else {
        addr(untagBits-1, blockOffBits-log2Ceil(refillCycles)) + bank(addr)
      }
    // Bank1 row's id stays the same regardless of which Bank has the fetch address.
    def b1Row(addr: UInt) =
      if (refillsToOneBank) {
        addr(untagBits-1, blockOffBits-log2Ceil(refillCycles)+1)
      } else {
        addr(untagBits-1, blockOffBits-log2Ceil(refillCycles))
      }

    s1_bankid := RegNext(bank(s0_vaddr))

    for (i <- 0 until nWays) {
      val s0_ren = s0_valid
      val wen = (refill_one_beat && !invalidated)&& repl_way === i.U

      var mem_idx0: UInt = null
      var mem_idx1: UInt = null

      if (refillsToOneBank) {
        // write a refill beat across only one beat.
        mem_idx0 =
          Mux(refill_one_beat, (refill_idx << (log2Ceil(refillCycles)-1)) | (refill_cnt >> 1.U),
          b0Row(s0_vaddr))
        mem_idx1 =
          Mux(refill_one_beat, (refill_idx << (log2Ceil(refillCycles)-1)) | (refill_cnt >> 1.U),
          b1Row(s0_vaddr))

        when (wen && refill_cnt(0) === 0.U && debugWatchRefillLine) {
          printf(p"[ICACHE-WATCH] refill_write_b0 paddr=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} " +
            p"way=0x${Hexadecimal(repl_way)} beat=0x${Hexadecimal(refill_cnt)} data=0x${Hexadecimal(tl_out.d.bits.data)}\n")
          printf(p"[ICACHE-REFILL-TRACE] line=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} " +
            p"way=0x${Hexadecimal(repl_way)} bank=0 beat=0x${Hexadecimal(refill_cnt)} mem_idx=0x${Hexadecimal(mem_idx0)} " +
            p"write=0x${Hexadecimal(tl_out.d.bits.data)} refillCrypto=${refillMetaCryptoLine} " +
            p"refillCounter=0x${Hexadecimal(refillMetaCounter)}\n")
        }
        when (wen && refill_cnt(0) === 0.U) {
          dataArraysB0(i).write(mem_idx0, tl_out.d.bits.data)
        }
        when (wen && refill_cnt(0) === 1.U && debugWatchRefillLine) {
          printf(p"[ICACHE-WATCH] refill_write_b1 paddr=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} " +
            p"way=0x${Hexadecimal(repl_way)} beat=0x${Hexadecimal(refill_cnt)} data=0x${Hexadecimal(tl_out.d.bits.data)}\n")
          printf(p"[ICACHE-REFILL-TRACE] line=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} " +
            p"way=0x${Hexadecimal(repl_way)} bank=1 beat=0x${Hexadecimal(refill_cnt)} mem_idx=0x${Hexadecimal(mem_idx1)} " +
            p"write=0x${Hexadecimal(tl_out.d.bits.data)} refillCrypto=${refillMetaCryptoLine} " +
            p"refillCounter=0x${Hexadecimal(refillMetaCounter)}\n")
        }
        when (wen && refill_cnt(0) === 1.U) {
          dataArraysB1(i).write(mem_idx1, tl_out.d.bits.data)
        }
      } else {
        // write a refill beat across both banks.
        mem_idx0 =
          Mux(refill_one_beat, (refill_idx << log2Ceil(refillCycles)) | refill_cnt,
          b0Row(s0_vaddr))
        mem_idx1 =
          Mux(refill_one_beat, (refill_idx << log2Ceil(refillCycles)) | refill_cnt,
          b1Row(s0_vaddr))

        when (wen && debugWatchRefillLine) {
          val data = tl_out.d.bits.data
          printf(p"[ICACHE-WATCH] refill_write_both paddr=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} " +
            p"way=0x${Hexadecimal(repl_way)} beat=0x${Hexadecimal(refill_cnt)} data=0x${Hexadecimal(data)}\n")
          printf(p"[ICACHE-REFILL-TRACE] line=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} " +
            p"way=0x${Hexadecimal(repl_way)} beat=0x${Hexadecimal(refill_cnt)} mem_idx0=0x${Hexadecimal(mem_idx0)} " +
            p"mem_idx1=0x${Hexadecimal(mem_idx1)} write_b0=0x${Hexadecimal(data(wordBits/2-1, 0))} " +
            p"write_b1=0x${Hexadecimal(data(wordBits-1, wordBits/2))} refillCrypto=${refillMetaCryptoLine} " +
            p"refillCounter=0x${Hexadecimal(refillMetaCounter)}\n")
        }
        when (wen) {
          val data = tl_out.d.bits.data
          dataArraysB0(i).write(mem_idx0, data(wordBits/2-1, 0))
          dataArraysB1(i).write(mem_idx1, data(wordBits-1, wordBits/2))
        }
      }
      if (enableICacheDelay) {
        s2_dout(i) := Cat(dataArraysB1(i).read(RegNext(mem_idx1), RegNext(!wen && s0_ren)),
                          dataArraysB0(i).read(RegNext(mem_idx0), RegNext(!wen && s0_ren)))
      } else {
        s2_dout(i) := RegNext(Cat(dataArraysB1(i).read(mem_idx1, !wen && s0_ren),
                                  dataArraysB0(i).read(mem_idx0, !wen && s0_ren)))
      }
    }
  }
  val s2_tag_hit = RegNext(s1_tag_hit)
  /////////////////////////////////////////////////////////
  // 选中当前访问的set
  val s1_idx = io.s1_paddr(untagBits-1,blockOffBits)
  val s1_way_counter = VecInit((0 until nWays).map(i => l1CtrMeta(s1_idx)(i)))
  val s1_way_crypto = VecInit((0 until nWays).map(i => l1CryptoLineMeta(s1_idx)(i)))
  val s1_way_valid = VecInit((0 until nWays).map(i => vb_array(Cat(i.U, s1_idx))))
  val s2_way_counter = RegNext(s1_way_counter)
  val s2_way_crypto = RegNext(s1_way_crypto)
  val s2_way_valid = RegNext(s1_way_valid)
  ////////////////////////////////////////////////////////////
  val s2_hit_way = OHToUInt(s2_tag_hit)
  val s2_bankid = RegNext(s1_bankid)
  val s2_way_mux = Mux1H(s2_tag_hit, s2_dout)

  val s2_unbanked_data = s2_way_mux
  val sz = s2_way_mux.getWidth
  val s2_bank0_data = s2_way_mux(sz/2-1,0)
  val s2_bank1_data = s2_way_mux(sz-1,sz/2)

  val s2_data =
    if (nBanks == 2) {
      Mux(s2_bankid,
        Cat(s2_bank0_data, s2_bank1_data),
        Cat(s2_bank1_data, s2_bank0_data))
    } else {
      s2_unbanked_data
    }
  ////////////////////////////////////////////
  val s2_hit_data = s2_data
  val s2_hit_counter = Mux(s2_hit, Mux1H(s2_tag_hit, s2_way_counter), 0.U(cacheCryptoCounterBits.W))
  val s2_hit_crypto = Mux(s2_hit, Mux1H(s2_tag_hit, s2_way_crypto), false.B)
  val s2_plain_hit_resp = s2_valid && s2_hit && !io.frontendEngineMode
  val debugWatchFetchLine = (s2_paddr >> 6) === (debugWatchFetchLineBase >> 6)
  // `s2_hit_late` describes the current s2 hit result. It must not depend on the
  // *current* s1 kill signal, otherwise frontend backpressure/f1_clear can feed
  // back combinationally into this valid and create a frontend<->icache loop.
  val s2_hit_late_valid = s2_valid && s2_hit && io.frontendEngineMode && !io.s2_kill && !io.invalidate
  val s2_launch_crypto = io.s2_hit_late.fire
  when (icacheCryptoAssertEnable && s2_valid && s2_hit) {
    assert(s2_hit_crypto === s2_req_crypto_line,
      "ICache hit cryptoLine does not match request crypto mode")
  }
  when (icacheCryptoAssertEnable && io.frontendEngineMode) {
    assert(!io.resp.valid,
      "ICache produced plain response while frontend crypto engine mode is active")
  }
  when (icacheCryptoAssertEnable && s2_launch_crypto) {
    assert(s2_hit,
      "ICache launched crypto engine without a valid hit")
  }

  cryptoEngine.io.dataKey := io.dataKey
  cryptoEngine.io.log := io.log
  cryptoEngine.io.req.valid := s2_launch_crypto
  cryptoEngine.io.req.bits.paddr := s2_paddr
  cryptoEngine.io.req.bits.cryptoLine := s2_hit_crypto
  cryptoEngine.io.req.bits.counter := s2_hit_counter
  cryptoEngine.io.req.bits.cipherData := s2_hit_data
  val icacheCryptoLatePending = RegInit(false.B)
  icacheCryptoLatePending := (icacheCryptoLatePending && !cryptoEngine.io.resp.valid && !io.invalidate) || s2_launch_crypto
  assertOnlyWatchdog(
    icacheCryptoLatePending && !cryptoEngine.io.resp.valid && !io.invalidate,
    16,
    "ICache crypto engine launched but late_resp did not return")
  ////////////////////////////////////////////////////////////////////
  io.s2_hit_late.valid := s2_hit_late_valid
  io.s2_hit_late.bits := s2_vaddr
  io.resp.bits.ae := DontCare
  io.resp.bits.replay := DontCare
  // io.resp.bits.data := s2_data
  // io.resp.valid := s2_valid && s2_hit
  io.resp.bits.data := s2_hit_data
  io.resp.valid := s2_plain_hit_resp

  io.late_resp.valid := cryptoEngine.io.resp.valid
  io.late_resp.bits.ae := DontCare
  io.late_resp.bits.replay := DontCare
  io.late_resp.bits.data := cryptoEngine.io.resp.bits.plainData

  when (s1_valid && debugWatchS1Line) {
    printf(p"[ICACHE-WATCH] s1_req paddr=0x${Hexadecimal(io.s1_paddr)} refillValid=${refill_valid} s2_miss=${s2_miss} frontendMode=${io.frontendEngineMode}\n")
  }

  when ((s2_plain_hit_resp || s2_hit_late_valid) && debugWatchFetchLine) {
    printf(p"[ICACHE-WATCH] paddr=0x${Hexadecimal(s2_paddr)} hit=${s2_hit} crypto=${s2_hit_crypto} " +
      p"frontendMode=${io.frontendEngineMode} data=0x${Hexadecimal(s2_hit_data)}\n")
  }
  when (s2_valid && debugWatchFetchLine) {
    printf(p"[ICACHE-HIT-TRACE] paddr=0x${Hexadecimal(s2_paddr)} hit=${s2_hit} hitOH=0x${Hexadecimal(s2_tag_hit.asUInt)} " +
      p"hitWay=0x${Hexadecimal(s2_hit_way)} bankid=${s2_bankid.asUInt} frontendMode=${io.frontendEngineMode.asUInt} " +
      p"wayCrypto=0x${Hexadecimal(s2_way_crypto.asUInt)} selectedCrypto=${s2_hit_crypto.asUInt} " +
      p"selectedCounter=0x${Hexadecimal(s2_hit_counter)} way0=0x${Hexadecimal(s2_dout(0))} " +
      p"way1=0x${Hexadecimal(s2_dout(1))} way2=0x${Hexadecimal(s2_dout(2))} way3=0x${Hexadecimal(s2_dout(3))} " +
      p"wayMux=0x${Hexadecimal(s2_way_mux)} finalData=0x${Hexadecimal(s2_hit_data)}\n")
    printf(p"[ICACHE-HIT-META] paddr=0x${Hexadecimal(s2_paddr)} ctr0=0x${Hexadecimal(s2_way_counter(0))} " +
      p"ctr1=0x${Hexadecimal(s2_way_counter(1))} ctr2=0x${Hexadecimal(s2_way_counter(2))} " +
      p"ctr3=0x${Hexadecimal(s2_way_counter(3))} setCrypto=0x${Hexadecimal(s2_way_crypto.asUInt)} " +
      p"setValid=0x${Hexadecimal(s2_way_valid.asUInt)}\n")
  }


  tl_out.a.valid := s2_miss && !refill_valid && !io.s2_kill
  assertOnlyWatchdog(
    s2_req_crypto_line && tl_out.a.valid && !tl_out.a.ready && !io.invalidate,
    2048,
    "ICache refill Get blocked by TileLink A ready")
  assertOnlyWatchdog(
    s2_req_crypto_line && s2_miss && !tl_out.a.fire && !refill_valid && !io.s2_kill && !io.invalidate,
    4096,
    "ICache miss could not launch refill")

  tl_out.a.bits := edge_out.Get(
    fromSource = 0.U,
    toAddress = (refill_paddr >> blockOffBits) << blockOffBits,
    lgSize = lgCacheBlockBytes.U)._2
  val outerAHasCryptoMeta = tl_out.a.bits.user.lift(CacheCryptoWritebackMeta).isDefined.B
  tl_out.a.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
    u.counter := 0.U
    u.cryptoLine := s2_req_crypto_line
  }

  when (tl_out.a.fire && (((refill_paddr >> blockOffBits) << blockOffBits) === debugWatchFetchLineBase)) {
    printf(p"[ICACHE-WATCH] refill_req paddr=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} cryptoReq=${s2_req_crypto_line}\n")
  }
  when (icacheCryptoDebugLogEnable && s1_valid && debugFetchWindow(io.s1_paddr)) {
    _root_.chisel3.printf(p"[ICACHE-FETCH-S1] cycle=${icacheDebugCycle} vaddr=0x${Hexadecimal(s1_vaddr)} paddr=0x${Hexadecimal(io.s1_paddr)} req_ready=${io.req.ready.asUInt} s1_kill=${io.s1_kill.asUInt} s2_kill=${io.s2_kill.asUInt} refill_valid=${refill_valid.asUInt} refill_one_beat=${refill_one_beat.asUInt} frontend_mode=${io.frontendEngineMode.asUInt}\n")
  }
  when (icacheCryptoDebugLogEnable && s2_valid && debugFetchWindow(s2_paddr)) {
    _root_.chisel3.printf(p"[ICACHE-FETCH-S2] cycle=${icacheDebugCycle} vaddr=0x${Hexadecimal(s2_vaddr)} paddr=0x${Hexadecimal(s2_paddr)} hit=${s2_hit.asUInt} miss=${s2_miss.asUInt} hit_oh=0x${Hexadecimal(s2_tag_hit.asUInt)} req_crypto=${s2_req_crypto_line.asUInt} hit_crypto=${s2_hit_crypto.asUInt} counter=0x${Hexadecimal(s2_hit_counter)} frontend_mode=${io.frontendEngineMode.asUInt} s2_kill=${io.s2_kill.asUInt} resp=${io.resp.valid.asUInt} late_valid=${io.s2_hit_late.valid.asUInt} late_ready=${io.s2_hit_late.ready.asUInt} late_fire=${io.s2_hit_late.fire.asUInt} crypto_resp=${cryptoEngine.io.resp.valid.asUInt} refill_valid=${refill_valid.asUInt} refill_paddr=0x${Hexadecimal(refill_paddr)} a_valid=${tl_out.a.valid.asUInt} a_ready=${tl_out.a.ready.asUInt} a_fire=${tl_out.a.fire.asUInt}\n")
  }
  when (icacheCryptoDebugLogEnable && tl_out.a.valid && debugFetchWindow(refill_paddr)) {
    _root_.chisel3.printf(p"[ICACHE-FETCH-REFILL-REQ] cycle=${icacheDebugCycle} paddr=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} req_crypto=${s2_req_crypto_line.asUInt} ready=${tl_out.a.ready.asUInt} fire=${tl_out.a.fire.asUInt} s2_miss=${s2_miss.asUInt} s2_kill=${io.s2_kill.asUInt}\n")
  }
  when (icacheCryptoDebugLogEnable && refill_one_beat && debugFetchWindow(refill_paddr)) {
    _root_.chisel3.printf(p"[ICACHE-FETCH-REFILL-BEAT] cycle=${icacheDebugCycle} paddr=0x${Hexadecimal((refill_paddr >> blockOffBits) << blockOffBits)} beat=${refill_cnt} done=${refill_done.asUInt} meta_crypto=${refillMetaCryptoLine.asUInt} meta_counter=0x${Hexadecimal(refillMetaCounter)} data=0x${Hexadecimal(tl_out.d.bits.data)}\n")
  }


  tl_out.b.ready := true.B
  tl_out.c.valid := false.B
  tl_out.e.valid := false.B

  io.perf.acquire := tl_out.a.fire

  when (!refill_valid) { invalidated := false.B }
  when (refill_fire) { refill_valid := true.B }
  when (refill_done) { refill_valid := false.B }

  override def toString: String = BoomCoreStringPrefix(
    "==L1-ICache==",
    "Fetch bytes   : " + cacheParams.fetchBytes,
    "Block bytes   : " + (1 << blockOffBits),
    "Row bytes     : " + rowBytes,
    "Word bits     : " + wordBits,
    "Sets          : " + nSets,
    "Ways          : " + nWays,
    "Refill cycles : " + refillCycles,
    "RAMs          : (" +  wordBits/nBanks + " x " + nSets*refillCycles + ") using " + nBanks + " banks",
    "" + (if (nBanks == 2) "Dual-banked" else "Single-banked"),
    "I-TLB ways    : " + cacheParams.nTLBWays + "\n")
}
