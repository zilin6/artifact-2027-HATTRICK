package boom.v3.lsu

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters

import boom.v3.common._
import boom.v3.exu.BrUpdateInfo
import boom.v3.util.{AsconCryptoMode, AsconCryptoParams, GetNewBrMask, IsKilledByBranch, asconaead64}
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{AMOALU, CacheCryptoCounterBitsKey, CacheCryptoDebugLog, HasL1HellaCacheParameters, L1Metadata, LoadGen, StoreGen}

class BoomCacheLineId(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val idx = UInt(idxBits.W)
  val tag = UInt(tagBits.W)
  val way_en = UInt(nWays.W)
}

class BoomCacheProbeBlockIO(implicit p: Parameters) extends BoomBundle()(p) {
  val incoming = Valid(new BoomCacheLineId)
  val ingress = Valid(new BoomCacheLineId)
  val plain = Valid(new BoomCacheLineId)
  val modify = Valid(new BoomCacheLineId)
  val result = Valid(new BoomCacheLineId)
  val reenc = Valid(new BoomCacheLineId)
}

class BoomCacheEngineMetaReadReq(implicit p: Parameters) extends BoomBundle()(p) {
  val line = new BoomCacheLineId
}

class BoomCacheEngineMetaWriteReq(implicit p: Parameters) extends BoomBundle()(p) {
  val line = new BoomCacheLineId
  val data = new L1Metadata
}

class BoomCacheEngineDataReadReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val line = new BoomCacheLineId
  val chunk = UInt(log2Ceil(cacheBlockBytes / (xLen / 8)).W)
}

class BoomCacheEngineDataReadResp(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val chunk = UInt(log2Ceil(cacheBlockBytes / (xLen / 8)).W)
  val data = UInt(xLen.W)
  val counter = UInt(p(CacheCryptoCounterBitsKey).W)
}

class BoomCacheEngineDataWriteReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val line = new BoomCacheLineId
  val chunk = UInt(log2Ceil(cacheBlockBytes / (xLen / 8)).W)
  val data = UInt(xLen.W)
  val counter = UInt(p(CacheCryptoCounterBitsKey).W)
  val counter_wen = Bool()
}

class BoomCacheEngineHitReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val req = new BoomDCacheReq
  val lane = UInt(log2Ceil(memWidth max 2).W)
  val line = new BoomCacheLineId
  val meta = new L1Metadata
  val counter = UInt(p(CacheCryptoCounterBitsKey).W)
  val cipherWord = UInt(xLen.W)
  val sendResp = Bool()
}

class BoomCacheEngineResp(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomCoreParameters
{
  val lane = UInt(log2Ceil(memWidth max 2).W)
  val resp = new BoomDCacheResp
}

class BoomCacheEngineSvcIO(implicit p: Parameters) extends BoomBundle()(p) {
  val meta_read = Decoupled(new BoomCacheEngineMetaReadReq)
  val meta_resp = Flipped(Valid(new L1Metadata))
  val meta_write = Decoupled(new BoomCacheEngineMetaWriteReq)
  val data_read = Decoupled(new BoomCacheEngineDataReadReq)
  val data_resp = Flipped(Valid(new BoomCacheEngineDataReadResp))
  val data_write = Decoupled(new BoomCacheEngineDataWriteReq)
}

class BoomCacheCryptoEngine(implicit p: Parameters) extends BoomModule()(p)
  with HasL1HellaCacheParameters
  with MemoryOpConstants
{
  require(xLen == 64, "Ascon dcache crypto path currently expects 64b words")

  val io = IO(new Bundle {
    val counterBaseAddress = Input(UInt(64.W))
    val dataKey = Input(UInt(128.W))
    val loadCryptoEnable = Input(Bool())
    val storeCryptoEnable = Input(Bool())
    val reqReady = Output(Bool())
    val loadReady = Output(Bool())
    val storeReady = Output(Bool())

    val hitReq = Input(Valid(new BoomCacheEngineHitReq))
    val hitReqIsStore = Input(Bool())
    val brupdate = Input(new BrUpdateInfo)
    val exception = Input(Bool())
    val loadResp = Decoupled(new BoomCacheEngineResp)
    val storeResp = Decoupled(new BoomCacheEngineResp)
    val probeBlock = Output(new BoomCacheProbeBlockIO)
    val svc = new BoomCacheEngineSvcIO
    val cryptoAssertEnable = Input(Bool())
    val debugScFailDiag = Input(Bool())
    val debugScFailDiagCycle = Input(UInt(64.W))
  })
  // Clean remake skeleton:
  // ingressReg -> D-path combinational -> loadExitReg
  // ingressReg -> D-path combinational -> preEncReg -> encAscon -> storeExitReg
  //
  // TODO(cache-crypto/probe interlock):
  // Export engine-owned busy lines to DCache/ProbeUnit, so ProbeUnit can recheck
  // "is this exact line still being held inside the engine?" immediately before any
  // destructive probe action (writeback / release / metadata commit).
  //
  // Intended first use:
  // - probeBlock.ingress: raw request accepted into ingressReg
  // - probeBlock.plain: decrypt/old-plain stage ownership
  // - probeBlock.modify: modify/new-plain stage ownership
  // - probeBlock.result: encrypted result while data_write has not completed
  // - probeBlock.reenc: reencLine while reencPending/reencActive/hold is live
  //
  // Intended policy:
  // - do not block ordinary s2 hit/access with this signal
  // - only block late destructive ownership changes, starting from ProbeUnit recheck
  // - if later proven necessary, extend the same late check to the final MSHR victim
  //   overwrite / refill commit point as well

  private val lineChunkBytes = xLen / 8
  private val lineChunkCount = cacheBlockBytes / lineChunkBytes

  private val dcacheCryptoAssertEnable = io.cryptoAssertEnable
  private def assertOnlyWatchdog(waiting: Bool, limit: Int, message: String): Unit = {
    val enabledWaiting = dcacheCryptoAssertEnable && waiting
    val cycles = RegInit(0.U(log2Ceil(limit + 1).W))
    when (!enabledWaiting) {
      cycles := 0.U
    } .elsewhen (cycles =/= limit.U) {
      cycles := cycles + 1.U
    }
    assert(!dcacheCryptoAssertEnable || cycles =/= limit.U, message)
  }
  private val lineChunkIdxBits = (log2Ceil(lineChunkCount) max 1)
  private val wordByteOffsetBits = log2Ceil(xLen / 8)
  private val fullWordStoreSize = wordByteOffsetBits.U
  private val counterLowWidth = 8 min p(CacheCryptoCounterBitsKey)
  private val reencDoneHoldCycles = 2
  private val reencDoneHoldBits = log2Ceil(reencDoneHoldCycles + 1)
  private val lastLineChunk = (lineChunkCount - 1).U(lineChunkIdxBits.W)
  private def chunkIdx(addr: UInt): UInt = {
    if (lineChunkCount == 1) 0.U else addr(log2Ceil(cacheBlockBytes) - 1, log2Ceil(lineChunkBytes))
  }

  private def sameLine(a: BoomCacheLineId, b: BoomCacheLineId): Bool = {
    a.idx === b.idx && a.tag === b.tag && a.way_en === b.way_en
  }

  private def uopKilled(uop: MicroOp): Bool = IsKilledByBranch(io.brupdate, uop)
  private def uopKilledOrFlushed(uop: MicroOp): Bool =
    uopKilled(uop) || (io.exception && uop.uses_ldq)
  private def refreshUopBrMask(uop: MicroOp): MicroOp = {
    val out = WireInit(uop)
    out.br_mask := GetNewBrMask(io.brupdate, uop)
    out
  }
  private def refreshHitReqBrMask(req: BoomCacheEngineHitReq): BoomCacheEngineHitReq = {
    val out = WireInit(req)
    out.req.uop := refreshUopBrMask(req.req.uop)
    out
  }
  private def refreshEngineRespBrMask(resp: BoomCacheEngineResp): BoomCacheEngineResp = {
    val out = WireInit(resp)
    out.resp.uop := refreshUopBrMask(resp.resp.uop)
    out
  }
  private def dbgAtomicLike(uop: MicroOp, memCmd: UInt): Bool =
    uop.is_amo || memCmd === M_XSC || memCmd === M_XLR

  val decAscon = Module(new asconaead64())
  val encAscon = Module(new asconaead64())
  decAscon.io.in_mode := AsconCryptoMode.decrypt
  decAscon.io.in_key := io.dataKey
  decAscon.io.in_nonce := 0.U
  decAscon.io.in_msg := 0.U
  encAscon.io.in_mode := AsconCryptoMode.encrypt
  encAscon.io.in_key := io.dataKey
  encAscon.io.in_nonce := 0.U
  encAscon.io.in_msg := 0.U

  // Ingress stage registers: these hold the raw request accepted into the engine.
  val ingressRegValid = RegInit(false.B)
  val ingressRegIsStore = RegInit(false.B)
  val ingressRegReq = Reg(new BoomCacheEngineHitReq)
  val ingressRegCounterBypassed = RegInit(false.B)
  val ingressRegBypassCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val ingressRegOldPlainBypassed = RegInit(false.B)
  val ingressRegBypassOldPlainWord = Reg(UInt(xLen.W))

  // Plain stage registers: decrypt/bypass selection has completed, but the
  // final store-like architectural update has not yet been applied.
  val plainStageRegValid = RegInit(false.B)
  val plainStageRegReq = Reg(new BoomCacheEngineHitReq)
  val plainStageRegChunk = Reg(UInt(lineChunkIdxBits.W))
  val plainStageRegOldPlainWord = Reg(UInt(xLen.W))
  val plainStageRegEffectiveCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val plainStageRegNeedsReenc = RegInit(false.B)

  val loadExitValid = RegInit(false.B)
  val loadExit = Reg(new BoomCacheEngineResp)

  // Modify stage registers: final store-like architectural update has been
  // computed and is now a stable bypass source.
  val modifyStageRegValid = RegInit(false.B)
  val modifyStageRegReq = Reg(new BoomCacheEngineHitReq)
  val modifyStageRegChunk = Reg(UInt(lineChunkIdxBits.W))
  val modifyStageRegNewPlainWord = Reg(UInt(xLen.W))
  val modifyStageRegNextCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val modifyStageRegVisibleCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val modifyStageRegEncryptCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val modifyStageRegCounterWen = RegInit(false.B)
  val modifyStageRegRespData = Reg(UInt(xLen.W))

  // Result stage registers: encryption has completed and the request is
  // waiting to update the data array and/or produce a store response.
  val resultStageRegValid = RegInit(false.B)
  val resultStageRegReq = Reg(new BoomCacheEngineHitReq)
  val resultStageRegChunk = Reg(UInt(lineChunkIdxBits.W))
  val resultStageRegPlainWord = Reg(UInt(xLen.W))
  val resultStageRegCipherWord = Reg(UInt(xLen.W))
  val resultStageRegNextCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val resultStageRegVisibleCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val resultStageRegEncryptCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val resultStageRegCounterWen = RegInit(false.B)
  val resultStageRegRespData = Reg(UInt(xLen.W))
  val resultStageRegWriteDone = RegInit(false.B)
  val resultStageRegRespDone = RegInit(false.B)

  // Shadow-only bypass visibility after result-stage retirement. These stages
  // do not participate in data_write; they only keep the latest
  // plaintext/counter view visible long enough for subsequent same-line replays
  // to chain their counter.
  val shadow1Valid = RegInit(false.B)
  val shadow1Req = Reg(new BoomCacheEngineHitReq)
  val shadow1Chunk = Reg(UInt(lineChunkIdxBits.W))
  val shadow1PlainWord = Reg(UInt(xLen.W))
  val shadow1NextCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val shadow1VisibleCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))

  val shadow2Valid = RegInit(false.B)
  val shadow2Req = Reg(new BoomCacheEngineHitReq)
  val shadow2Chunk = Reg(UInt(lineChunkIdxBits.W))
  val shadow2PlainWord = Reg(UInt(xLen.W))
  val shadow2NextCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val shadow2VisibleCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))

  // Background reencrypt state: the causing store has already completed on the normal store path.
  val reencPending = RegInit(false.B)
  val reencActive = RegInit(false.B)
  val reencMetaSetPending = RegInit(false.B)
  val reencMetaClearPending = RegInit(false.B)
  val reencDoneHoldCounter = RegInit(0.U(reencDoneHoldBits.W))
  val reencLine = Reg(new BoomCacheLineId)
  val reencMetaBase = Reg(new L1Metadata)
  val reencOldCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val reencNextCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))
  val reencIssueChunk = Reg(UInt(lineChunkIdxBits.W))
  val reencAllReadsIssued = RegInit(false.B)
  val reencReadRespValid = RegInit(false.B)
  val reencReadRespChunk = Reg(UInt(lineChunkIdxBits.W))
  val reencReadRespCipher = Reg(UInt(xLen.W))
  val reencReadOutstanding = RegInit(false.B)
  val reencDecValid = RegInit(false.B)
  val reencDecChunk = Reg(UInt(lineChunkIdxBits.W))
  val reencDecPlain = Reg(UInt(xLen.W))
  val reencEncValid = RegInit(false.B)
  val reencEncChunk = Reg(UInt(lineChunkIdxBits.W))
  val reencEncCipher = Reg(UInt(xLen.W))
  val reencWriteMask = RegInit(0.U(lineChunkCount.W))
  val lastReencArmValid = RegInit(false.B)
  val lastReencArmLine = Reg(new BoomCacheLineId)
  val lastReencArmOldCounter = Reg(UInt(p(CacheCryptoCounterBitsKey).W))

  val plainStageRegKilled = plainStageRegValid && uopKilled(plainStageRegReq.req.uop)
  val modifyStageRegKilled = modifyStageRegValid && uopKilled(modifyStageRegReq.req.uop)
  val resultStageRegKilled = resultStageRegValid && uopKilled(resultStageRegReq.req.uop)
  val shadow1Killed = shadow1Valid && uopKilled(shadow1Req.req.uop)
  val shadow2Killed = shadow2Valid && uopKilled(shadow2Req.req.uop)

  val modifyCombIsAmo = isAMO(plainStageRegReq.req.uop.mem_cmd)
  val modifyCombStoreMask = new StoreGen(plainStageRegReq.req.uop.mem_size, plainStageRegReq.req.addr, 0.U, xLen / 8).mask
  val modifyCombStoreByteOffset = plainStageRegReq.req.addr(wordByteOffsetBits - 1, 0)
  val modifyCombStoreDataAligned = (plainStageRegReq.req.data << (modifyCombStoreByteOffset << 3)).asUInt
  val modifyCombStoreMaskBits = FillInterleaved(8, modifyCombStoreMask)
  val modifyCombStoreIsFullWord = !modifyCombIsAmo &&
    plainStageRegReq.req.uop.mem_size === fullWordStoreSize &&
    modifyCombStoreByteOffset === 0.U
  val modifyCombStoreLikeNewPlainWord = Mux(modifyCombStoreIsFullWord, modifyCombStoreDataAligned,
    (plainStageRegOldPlainWord & ~modifyCombStoreMaskBits) | (modifyCombStoreDataAligned & modifyCombStoreMaskBits))

  val amoalu = Module(new AMOALU(xLen))
  amoalu.io.mask := modifyCombStoreMask
  amoalu.io.cmd := plainStageRegReq.req.uop.mem_cmd
  amoalu.io.lhs := plainStageRegOldPlainWord
  amoalu.io.rhs := plainStageRegReq.req.data

  // AMO/LR/SC architectural return data follows the normal load formatting
  // rules for the addressed subword. In particular, AMO.W on RV64 must return
  // the old 32b value sign-extended to XLEN.
  val modifyCombAmoRespGen = new LoadGen(
    plainStageRegReq.req.uop.mem_size,
    true.B,
    plainStageRegReq.req.addr,
    plainStageRegOldPlainWord,
    false.B,
    lineChunkBytes)

  val modifyCombFinalNewPlainWord = Mux(modifyCombIsAmo, amoalu.io.out, modifyCombStoreLikeNewPlainWord)
  val modifyCombRespData = Mux(modifyCombIsAmo, modifyCombAmoRespGen.data, 0.U)
  val modifyCombNextCounter = plainStageRegEffectiveCounter + 1.U
  val modifyCombVisibleCounter =
    Mux(plainStageRegNeedsReenc, plainStageRegEffectiveCounter, modifyCombNextCounter)
  val modifyCombEncryptCounter =
    Mux(plainStageRegNeedsReenc, plainStageRegEffectiveCounter, plainStageRegEffectiveCounter + 1.U)
  val modifyCombCounterWen = !plainStageRegNeedsReenc
  val modifyCombNonceCounterMask = ~((BigInt(1) << counterLowWidth) - 1).U(p(CacheCryptoCounterBitsKey).W)
  when (dcacheCryptoAssertEnable && plainStageRegValid && !plainStageRegKilled && plainStageRegReq.meta.cryptoLine && modifyCombCounterWen) {
    assert((modifyCombEncryptCounter & modifyCombNonceCounterMask) ===
      (plainStageRegEffectiveCounter & modifyCombNonceCounterMask),
      "crypto store changed the nonce-effective counter without full-line reencrypt")
  }

  // Combinational view derived from the ingress stage registers.
  val ingressCombChunk = chunkIdx(ingressRegReq.req.addr)
  val ingressCombBypassS0SameLine = plainStageRegValid && !plainStageRegKilled && sameLine(ingressRegReq.line, plainStageRegReq.line)
  val ingressCombBypassS0SameChunk = ingressCombBypassS0SameLine && ingressCombChunk === plainStageRegChunk
  val ingressCombBypassS1SameLine = modifyStageRegValid && !modifyStageRegKilled && sameLine(ingressRegReq.line, modifyStageRegReq.line)
  val ingressCombBypassS1SameChunk = ingressCombBypassS1SameLine && ingressCombChunk === modifyStageRegChunk
  val ingressCombBypassS2SameLine = resultStageRegValid && !resultStageRegKilled && sameLine(ingressRegReq.line, resultStageRegReq.line)
  val ingressCombBypassS2SameChunk = ingressCombBypassS2SameLine && ingressCombChunk === resultStageRegChunk
  val ingressCombBypassS3SameLine = shadow1Valid && !shadow1Killed && sameLine(ingressRegReq.line, shadow1Req.line)
  val ingressCombBypassS3SameChunk = ingressCombBypassS3SameLine && ingressCombChunk === shadow1Chunk
  val ingressCombBypassS4SameLine = shadow2Valid && !shadow2Killed && sameLine(ingressRegReq.line, shadow2Req.line)
  val ingressCombBypassS4SameChunk = ingressCombBypassS4SameLine && ingressCombChunk === shadow2Chunk

  val ingressCombDynamicCounterBypassed =
    ingressCombBypassS0SameLine || ingressCombBypassS1SameLine || ingressCombBypassS2SameLine || ingressCombBypassS3SameLine || ingressCombBypassS4SameLine
  val ingressCombDynamicBypassCounter =
    Mux(ingressCombBypassS0SameLine, modifyCombVisibleCounter,
      Mux(ingressCombBypassS1SameLine, modifyStageRegVisibleCounter,
        Mux(ingressCombBypassS2SameLine, resultStageRegVisibleCounter,
          Mux(ingressCombBypassS3SameLine, shadow1VisibleCounter, shadow2VisibleCounter))))
  val ingressCombEffectiveCounter = Mux(ingressCombDynamicCounterBypassed, ingressCombDynamicBypassCounter,
    Mux(ingressRegCounterBypassed, ingressRegBypassCounter, ingressRegReq.counter))
  val reencIncomingRespValid = reencActive && io.svc.data_resp.valid
  val reencIncomingRespChunk = io.svc.data_resp.bits.chunk
  val reencIncomingRespCipher = io.svc.data_resp.bits.data

  val ingressCombDynamicOldPlainBypassed =
    ingressCombBypassS0SameChunk || ingressCombBypassS1SameChunk || ingressCombBypassS2SameChunk || ingressCombBypassS3SameChunk || ingressCombBypassS4SameChunk
  val ingressCombDynamicBypassOldPlainWord =
    Mux(ingressCombBypassS0SameChunk, modifyCombFinalNewPlainWord,
      Mux(ingressCombBypassS1SameChunk, modifyStageRegNewPlainWord,
        Mux(ingressCombBypassS2SameChunk, resultStageRegPlainWord,
          Mux(ingressCombBypassS3SameChunk, shadow1PlainWord, shadow2PlainWord))))

  val acceptHitLive = io.hitReq.valid
  val acceptChunk = chunkIdx(io.hitReq.bits.req.addr)
  val acceptBypassS0SameLine = acceptHitLive && plainStageRegValid && !plainStageRegKilled && sameLine(io.hitReq.bits.line, plainStageRegReq.line)
  val acceptBypassS0SameChunk = acceptBypassS0SameLine && acceptChunk === plainStageRegChunk
  val acceptBypassS1SameLine = acceptHitLive && modifyStageRegValid && !modifyStageRegKilled && sameLine(io.hitReq.bits.line, modifyStageRegReq.line)
  val acceptBypassS1SameChunk = acceptBypassS1SameLine && acceptChunk === modifyStageRegChunk
  val acceptBypassS2SameLine = acceptHitLive && resultStageRegValid && !resultStageRegKilled && sameLine(io.hitReq.bits.line, resultStageRegReq.line)
  val acceptBypassS2SameChunk = acceptBypassS2SameLine && acceptChunk === resultStageRegChunk
  val acceptBypassS3SameLine = acceptHitLive && shadow1Valid && !shadow1Killed && sameLine(io.hitReq.bits.line, shadow1Req.line)
  val acceptBypassS3SameChunk = acceptBypassS3SameLine && acceptChunk === shadow1Chunk
  val acceptBypassS4SameLine = acceptHitLive && shadow2Valid && !shadow2Killed && sameLine(io.hitReq.bits.line, shadow2Req.line)
  val acceptBypassS4SameChunk = acceptBypassS4SameLine && acceptChunk === shadow2Chunk
  val acceptDynamicCounterBypassed =
    acceptBypassS0SameLine || acceptBypassS1SameLine || acceptBypassS2SameLine || acceptBypassS3SameLine || acceptBypassS4SameLine
  val acceptDynamicBypassCounter =
    Mux(acceptBypassS0SameLine, modifyCombVisibleCounter,
      Mux(acceptBypassS1SameLine, modifyStageRegVisibleCounter,
        Mux(acceptBypassS2SameLine, resultStageRegVisibleCounter,
          Mux(acceptBypassS3SameLine, shadow1VisibleCounter, shadow2VisibleCounter))))
  val acceptDynamicOldPlainBypassed =
    acceptBypassS0SameChunk || acceptBypassS1SameChunk || acceptBypassS2SameChunk || acceptBypassS3SameChunk || acceptBypassS4SameChunk
  val acceptDynamicBypassOldPlainWord =
    Mux(acceptBypassS0SameChunk, modifyCombFinalNewPlainWord,
      Mux(acceptBypassS1SameChunk, modifyStageRegNewPlainWord,
        Mux(acceptBypassS2SameChunk, resultStageRegPlainWord,
          Mux(acceptBypassS3SameChunk, shadow1PlainWord, shadow2PlainWord))))
  val ingressCombPlainOldWord = Mux(ingressCombDynamicOldPlainBypassed, ingressCombDynamicBypassOldPlainWord,
    Mux(ingressRegOldPlainBypassed, ingressRegBypassOldPlainWord,
    Mux(ingressRegReq.req.is_hella, ingressRegReq.cipherWord, decAscon.io.out_msg))
  )
  val ingressCombLoadGen = new LoadGen(
    ingressRegReq.req.uop.mem_size,
    ingressRegReq.req.uop.mem_signed,
    ingressRegReq.req.addr,
    ingressCombPlainOldWord,
    false.B,
    lineChunkBytes)

  val reencEncInputValid = reencDecValid
  encAscon.io.in_nonce := Mux(reencEncInputValid,
    AsconCryptoParams.nonce(reencNextCounter, Cat(reencLine.tag, reencLine.idx, reencDecChunk, 0.U(wordByteOffsetBits.W))),
    AsconCryptoParams.nonce(modifyStageRegEncryptCounter, modifyStageRegReq.req.addr))
  encAscon.io.in_msg := Mux(reencEncInputValid, reencDecPlain, modifyStageRegNewPlainWord)

  val resultStageResp = Wire(new BoomCacheEngineResp)
  resultStageResp := 0.U.asTypeOf(new BoomCacheEngineResp)
  resultStageResp.lane := resultStageRegReq.lane
  resultStageResp.resp.uop := refreshUopBrMask(resultStageRegReq.req.uop)
  resultStageResp.resp.data := resultStageRegRespData
  resultStageResp.resp.is_hella := resultStageRegReq.req.is_hella
  val reencMetaSetNow = WireDefault(false.B)
  val reencIssueMetaSet = WireDefault(false.B)
  val reencIssueMetaClear = WireDefault(false.B)
  val hitReqAccept = WireDefault(false.B)

  io.loadResp.valid := false.B
  io.loadResp.bits := 0.U.asTypeOf(new BoomCacheEngineResp)
  io.storeResp.valid := false.B
  io.storeResp.bits := 0.U.asTypeOf(new BoomCacheEngineResp)
  io.svc.meta_read.valid := false.B
  io.svc.meta_read.bits := 0.U.asTypeOf(new BoomCacheEngineMetaReadReq)
  io.svc.meta_write.valid := false.B
  io.svc.meta_write.bits := 0.U.asTypeOf(new BoomCacheEngineMetaWriteReq)
  io.svc.data_read.valid := false.B
  io.svc.data_read.bits := 0.U.asTypeOf(new BoomCacheEngineDataReadReq)
  io.svc.data_write.valid := false.B
  io.svc.data_write.bits := 0.U.asTypeOf(new BoomCacheEngineDataWriteReq)
  io.probeBlock := 0.U.asTypeOf(new BoomCacheProbeBlockIO)

  when (reencIssueMetaSet) {
    io.svc.meta_write.valid := true.B
    io.svc.meta_write.bits.line := Mux(reencMetaSetPending, reencLine, ingressRegReq.line)
    io.svc.meta_write.bits.data := Mux(reencMetaSetPending, reencMetaBase, ingressRegReq.meta)
    io.svc.meta_write.bits.data.reenc_active := true.B
  } .elsewhen (reencIssueMetaClear) {
    io.svc.meta_write.valid := true.B
    io.svc.meta_write.bits.line := reencLine
    io.svc.meta_write.bits.data := reencMetaBase
    io.svc.meta_write.bits.data.reenc_active := false.B
  }

  val resultStageNeedWrite = resultStageRegValid && !resultStageRegKilled && !resultStageRegWriteDone
  val resultStageRespVisible = resultStageRegValid && !resultStageRegKilled && resultStageRegReq.sendResp && !resultStageRegRespDone
  val loadExitKilled = loadExitValid && uopKilledOrFlushed(loadExit.resp.uop)
  val loadExitRespVisible = loadExitValid && !loadExitKilled
  val reencBusy = reencPending || reencActive || reencMetaSetPending || reencMetaClearPending || (reencDoneHoldCounter =/= 0.U)
  assertOnlyWatchdog(reencBusy, 6144, "DCache crypto reenc busy stuck too long")
  assertOnlyWatchdog(
    reencMetaSetPending && !io.svc.meta_write.ready,
    2048,
    "DCache crypto reenc meta set blocked too long")
  assertOnlyWatchdog(
    reencMetaClearPending && !io.svc.meta_write.ready,
    2048,
    "DCache crypto reenc meta clear blocked too long")
  val ingressRegKilled = ingressRegValid && uopKilledOrFlushed(ingressRegReq.req.uop)
  val ingressCombCounterLow = ingressCombEffectiveCounter(counterLowWidth - 1, 0)
  val ingressCombNeedsReenc = ingressRegValid && ingressRegIsStore && !ingressRegKilled && ingressRegReq.meta.cryptoLine && ingressCombCounterLow.andR
  val probeBlockIncomingFromAcceptedStore = hitReqAccept && io.hitReqIsStore
  val probeBlockIngressFromIngressReg = ingressRegValid && ingressRegIsStore && !ingressRegKilled
  val probeBlockPlainValid = plainStageRegValid && !plainStageRegKilled
  val probeBlockModifyValid = modifyStageRegValid && !modifyStageRegKilled
  val probeBlockResultValid = resultStageRegValid && !resultStageRegKilled && !resultStageRegWriteDone
  val probeBlockReencValid =
    reencPending || reencActive || reencMetaSetPending || reencMetaClearPending || (reencDoneHoldCounter =/= 0.U)
  io.loadResp.valid := loadExitRespVisible
  io.loadResp.bits := refreshEngineRespBrMask(loadExit)
  io.storeResp.valid := resultStageRespVisible
  io.storeResp.bits := refreshEngineRespBrMask(resultStageResp)
  io.probeBlock.incoming.valid := probeBlockIncomingFromAcceptedStore
  io.probeBlock.incoming.bits := io.hitReq.bits.line
  io.probeBlock.ingress.valid := probeBlockIngressFromIngressReg
  io.probeBlock.ingress.bits := ingressRegReq.line
  io.probeBlock.plain.valid := probeBlockPlainValid
  io.probeBlock.plain.bits := plainStageRegReq.line
  io.probeBlock.modify.valid := probeBlockModifyValid
  io.probeBlock.modify.bits := modifyStageRegReq.line
  io.probeBlock.result.valid := probeBlockResultValid
  io.probeBlock.result.bits := resultStageRegReq.line
  io.probeBlock.reenc.valid := probeBlockReencValid
  io.probeBlock.reenc.bits := reencLine

  val resultStageWriteFire = resultStageNeedWrite && io.svc.data_write.ready
  val resultStageRespFire = resultStageRespVisible && io.storeResp.ready
  val resultStageRegWillDequeue = resultStageRegValid &&
    (resultStageRegKilled ||
      ((!resultStageNeedWrite || resultStageWriteFire) &&
       (!resultStageRespVisible || resultStageRespFire)))
  val resultStageRegReady = !resultStageRegValid || resultStageRegWillDequeue

  val modifyStageRegWillDequeue = modifyStageRegValid && (modifyStageRegKilled || resultStageRegReady)
  val modifyStageRegReady = !modifyStageRegValid || modifyStageRegWillDequeue

  val plainStageRegWillDequeue = plainStageRegValid && (plainStageRegKilled || modifyStageRegReady)
  val plainStageRegReady = !plainStageRegValid || plainStageRegWillDequeue

  val loadExitWillDequeue = loadExitValid && (loadExitKilled || (loadExitRespVisible && io.loadResp.ready))
  val loadExitReady = !loadExitValid || loadExitWillDequeue

  val ingressCombLoadPathReady = !ingressRegReq.sendResp || loadExitReady
  val ingressCombStorePathReady = plainStageRegReady
  val ingressCombPathReady = Mux(ingressRegIsStore, ingressCombStorePathReady, ingressCombLoadPathReady)
  val ingressRegWillDequeue = ingressRegValid && (ingressRegKilled || ingressCombPathReady)
  val ingressRegReady = !ingressRegValid || ingressRegWillDequeue
  val ingressCanAcceptNewReq = !ingressRegValid
  val reencArmNow = ingressRegWillDequeue && ingressRegIsStore && !ingressRegKilled && ingressCombNeedsReenc
  val acceptHitKilled = io.hitReq.valid && uopKilledOrFlushed(io.hitReq.bits.req.uop)

  reencMetaSetNow := reencArmNow
  reencIssueMetaSet := reencMetaSetNow || reencMetaSetPending
  reencIssueMetaClear := reencMetaClearPending
  val normalPipeEmpty = !ingressRegValid && !plainStageRegValid && !modifyStageRegValid && !resultStageRegValid && !loadExitValid
  val reencCanStart = reencPending && !reencMetaSetPending && normalPipeEmpty

  val reencWriteFire = reencActive && reencEncValid && io.svc.data_write.ready
  val reencEncReady = !reencEncValid || reencWriteFire
  assertOnlyWatchdog(
    reencReadOutstanding && !reencIncomingRespValid,
    2048,
    "DCache crypto reenc read outstanding stuck too long")
  assertOnlyWatchdog(
    reencActive && reencEncValid && !io.svc.data_write.ready,
    2048,
    "DCache crypto reenc data write blocked too long")
  val reencDecWillAdvance = reencDecValid && reencEncReady
  val reencDecCanAccept = !reencDecValid || reencDecWillAdvance
  val reencReadRespWillAdvance = reencReadRespValid && reencDecCanAccept
  val reencReadRespReady = !reencReadRespValid || reencReadRespWillAdvance
  val reencIncomingRespDirectAdvance =
    !reencReadRespValid && reencIncomingRespValid && reencDecCanAccept
  val reencRespConsumedThisCycle =
    reencReadRespWillAdvance || reencIncomingRespDirectAdvance
  val reencRespSrcValid = reencReadRespValid || reencIncomingRespValid
  val reencRespSrcChunk = Mux(reencReadRespValid, reencReadRespChunk, reencIncomingRespChunk)
  val reencRespSrcCipher = Mux(reencReadRespValid, reencReadRespCipher, reencIncomingRespCipher)
  val reencReadIssueValid =
    reencActive &&
    !reencAllReadsIssued &&
    (!reencReadOutstanding || reencRespConsumedThisCycle)
  assertOnlyWatchdog(
    reencActive && reencReadIssueValid && !io.svc.data_read.ready,
    2048,
    "DCache crypto reenc data read blocked too long")
  val reencReadIssueFire = reencReadIssueValid && io.svc.data_read.ready
  val reencReadRespCapture =
    reencIncomingRespValid && !reencReadRespValid && !reencDecCanAccept
  val loadExitAlloc = ingressRegWillDequeue && !ingressRegIsStore && !ingressRegKilled && ingressRegReq.sendResp
  val plainStageRegAlloc = ingressRegWillDequeue && ingressRegIsStore && !ingressRegKilled
  val modifyStageRegAlloc = plainStageRegWillDequeue && !plainStageRegKilled
  val resultStageRegAlloc = modifyStageRegWillDequeue && !modifyStageRegKilled
  val reencDecAdvance = reencRespConsumedThisCycle
  val reencEncAdvance = reencDecWillAdvance
  val reencFinalWriteFire = reencWriteFire && (reencEncChunk === lastLineChunk)

  when (reencReadIssueFire) {
    assert(!dcacheCryptoAssertEnable || !reencReadOutstanding || reencRespConsumedThisCycle,
      "reenc issued a new read before the previous read response was consumed")
  }
  when (reencIncomingRespValid) {
    assert(!dcacheCryptoAssertEnable || !reencReadRespValid || reencReadRespWillAdvance,
      "reenc got a new read response while the buffered response could not advance")
  }
  assert(!dcacheCryptoAssertEnable || !(reencReadRespValid && reencIncomingRespValid),
    "reenc has both buffered and incoming read responses live in the same cycle")

  val reencDecInputValid = reencRespSrcValid
  decAscon.io.in_nonce := Mux(reencDecInputValid,
    AsconCryptoParams.nonce(reencOldCounter, Cat(reencLine.tag, reencLine.idx, reencRespSrcChunk, 0.U(wordByteOffsetBits.W))),
    AsconCryptoParams.nonce(ingressCombEffectiveCounter, ingressRegReq.req.addr))
  decAscon.io.in_msg := Mux(reencDecInputValid, reencRespSrcCipher, ingressRegReq.cipherWord)

  io.reqReady := ingressCanAcceptNewReq && !reencBusy
  io.loadReady := ingressCanAcceptNewReq && !reencBusy
  io.storeReady := ingressCanAcceptNewReq && !reencBusy
  hitReqAccept := io.hitReq.valid && ingressCanAcceptNewReq && !reencBusy && !acceptHitKilled

  when (io.debugScFailDiag) {
    printf("[L1D-SC-FAIL-ENGINE] cycle=%d loadReady=%d storeReady=%d hitReq_valid=%d hitReq_is_store=%d hitReq_accept=%d accept_killed=%d hitReq_cmd=0x%x hitReq_addr=0x%x hitReq_crypto=%d ingress_valid=%d ingress_is_store=%d plain_valid=%d modify_valid=%d result_valid=%d loadExit_valid=%d loadResp_valid=%d loadResp_ready=%d storeResp_valid=%d storeResp_ready=%d result_need_write=%d result_resp_visible=%d result_write_fire=%d result_resp_fire=%d reenc_busy=%d reenc_pending=%d reenc_active=%d reenc_meta_set_pending=%d reenc_meta_clear_pending=%d reenc_hold=%d reenc_line_idx=0x%x reenc_line_tag=0x%x reenc_line_way=0x%x reenc_read_outstanding=%d reenc_all_reads_issued=%d reenc_read_issue_valid=%d reenc_read_issue_fire=%d reenc_dec_valid=%d reenc_enc_valid=%d data_read_valid=%d data_read_ready=%d data_write_valid=%d data_write_ready=%d meta_write_valid=%d meta_write_ready=%d\n",
      io.debugScFailDiagCycle,
      io.loadReady,
      io.storeReady,
      io.hitReq.valid,
      io.hitReqIsStore,
      hitReqAccept,
      acceptHitKilled,
      io.hitReq.bits.req.uop.mem_cmd,
      io.hitReq.bits.req.addr,
      io.hitReq.bits.meta.cryptoLine,
      ingressRegValid,
      ingressRegIsStore,
      plainStageRegValid,
      modifyStageRegValid,
      resultStageRegValid,
      loadExitValid,
      io.loadResp.valid,
      io.loadResp.ready,
      io.storeResp.valid,
      io.storeResp.ready,
      resultStageNeedWrite,
      resultStageRespVisible,
      resultStageWriteFire,
      resultStageRespFire,
      reencBusy,
      reencPending,
      reencActive,
      reencMetaSetPending,
      reencMetaClearPending,
      reencDoneHoldCounter,
      reencLine.idx,
      reencLine.tag,
      reencLine.way_en,
      reencReadOutstanding,
      reencAllReadsIssued,
      reencReadIssueValid,
      reencReadIssueFire,
      reencDecValid,
      reencEncValid,
      io.svc.data_read.valid,
      io.svc.data_read.ready,
      io.svc.data_write.valid,
      io.svc.data_write.ready,
      io.svc.meta_write.valid,
      io.svc.meta_write.ready)
  }

  val acceptBypassCounterVisible = acceptDynamicCounterBypassed
  val acceptBypassCounter = acceptDynamicBypassCounter
  val acceptBypassOldPlainVisible = acceptDynamicOldPlainBypassed
  val acceptBypassOldPlainWord = acceptDynamicBypassOldPlainWord

  when (ingressRegValid) {
    ingressRegReq.req.uop.br_mask := GetNewBrMask(io.brupdate, ingressRegReq.req.uop)
  }
  when (plainStageRegValid) {
    plainStageRegReq.req.uop.br_mask := GetNewBrMask(io.brupdate, plainStageRegReq.req.uop)
  }
  when (modifyStageRegValid) {
    modifyStageRegReq.req.uop.br_mask := GetNewBrMask(io.brupdate, modifyStageRegReq.req.uop)
  }
  when (resultStageRegValid) {
    resultStageRegReq.req.uop.br_mask := GetNewBrMask(io.brupdate, resultStageRegReq.req.uop)
  }
  when (loadExitValid) {
    loadExit.resp.uop.br_mask := GetNewBrMask(io.brupdate, loadExit.resp.uop)
  }
  when (shadow1Valid) {
    shadow1Req.req.uop.br_mask := GetNewBrMask(io.brupdate, shadow1Req.req.uop)
  }
  when (shadow2Valid) {
    shadow2Req.req.uop.br_mask := GetNewBrMask(io.brupdate, shadow2Req.req.uop)
  }

  when (ingressRegWillDequeue) {
    ingressRegValid := false.B
    ingressRegCounterBypassed := false.B
    ingressRegOldPlainBypassed := false.B
  }
  when (hitReqAccept) {
    ingressRegValid := true.B
    ingressRegIsStore := io.hitReqIsStore
    ingressRegReq := refreshHitReqBrMask(io.hitReq.bits)
    ingressRegCounterBypassed := acceptBypassCounterVisible
    ingressRegOldPlainBypassed := acceptBypassOldPlainVisible
    when (acceptBypassCounterVisible) {
      ingressRegBypassCounter := acceptBypassCounter
    }
    when (acceptBypassOldPlainVisible) {
      ingressRegBypassOldPlainWord := acceptBypassOldPlainWord
    }
    when (dbgAtomicLike(io.hitReq.bits.req.uop, io.hitReq.bits.req.uop.mem_cmd)) {
    }
    when (acceptBypassCounterVisible) {
    }
  }
  when (reencMetaSetNow) {
    assert(!dcacheCryptoAssertEnable || !(lastReencArmValid &&
      sameLine(ingressRegReq.line, lastReencArmLine) &&
      ingressCombEffectiveCounter === lastReencArmOldCounter),
      "same line armed reenc twice from the same old counter")
    reencPending := true.B
    reencLine := ingressRegReq.line
    reencMetaBase := ingressRegReq.meta
    reencOldCounter := ingressCombEffectiveCounter
    reencNextCounter := ingressCombEffectiveCounter + 1.U
    reencMetaSetPending := !io.svc.meta_write.ready
    reencWriteMask := 0.U
    lastReencArmValid := true.B
    lastReencArmLine := ingressRegReq.line
    lastReencArmOldCounter := ingressCombEffectiveCounter
  } .elsewhen (reencMetaSetPending && io.svc.meta_write.ready) {
    reencMetaSetPending := false.B
  }
  when (reencIssueMetaClear && io.svc.meta_write.ready) {
    reencMetaClearPending := false.B
  }
  when (ingressRegValid && !ingressRegWillDequeue && ingressCombDynamicCounterBypassed) {
    ingressRegCounterBypassed := true.B
    ingressRegBypassCounter := ingressCombDynamicBypassCounter
  }
  when (ingressRegValid && !ingressRegWillDequeue && ingressCombDynamicOldPlainBypassed) {
    ingressRegOldPlainBypassed := true.B
    ingressRegBypassOldPlainWord := ingressCombDynamicBypassOldPlainWord
  }

  when (loadExitAlloc) {
    loadExitValid := true.B
    loadExit.lane := ingressRegReq.lane
    loadExit.resp.uop := refreshUopBrMask(ingressRegReq.req.uop)
    loadExit.resp.data := ingressCombLoadGen.data
    loadExit.resp.is_hella := ingressRegReq.req.is_hella
  } .elsewhen (loadExitWillDequeue) {
    loadExitValid := false.B
  }

  when (plainStageRegAlloc) {
    plainStageRegValid := true.B
    plainStageRegReq := refreshHitReqBrMask(ingressRegReq)
    plainStageRegChunk := ingressCombChunk
    plainStageRegOldPlainWord := ingressCombPlainOldWord
    plainStageRegEffectiveCounter := ingressCombEffectiveCounter
    plainStageRegNeedsReenc := ingressCombNeedsReenc
    when (dbgAtomicLike(ingressRegReq.req.uop, ingressRegReq.req.uop.mem_cmd)) {
    }
  } .elsewhen (plainStageRegWillDequeue) {
    plainStageRegValid := false.B
  }

  when (modifyStageRegAlloc) {
    modifyStageRegValid := true.B
    modifyStageRegReq := refreshHitReqBrMask(plainStageRegReq)
    modifyStageRegChunk := plainStageRegChunk
    modifyStageRegNewPlainWord := modifyCombFinalNewPlainWord
    modifyStageRegNextCounter := modifyCombNextCounter
    modifyStageRegVisibleCounter := modifyCombVisibleCounter
    modifyStageRegEncryptCounter := modifyCombEncryptCounter
    modifyStageRegCounterWen := modifyCombCounterWen
    modifyStageRegRespData := modifyCombRespData
    when (dbgAtomicLike(plainStageRegReq.req.uop, plainStageRegReq.req.uop.mem_cmd)) {
    }
  } .elsewhen (modifyStageRegWillDequeue) {
    modifyStageRegValid := false.B
  }

  when (resultStageRegAlloc) {
    resultStageRegValid := true.B
    resultStageRegReq := refreshHitReqBrMask(modifyStageRegReq)
    resultStageRegChunk := modifyStageRegChunk
    resultStageRegPlainWord := modifyStageRegNewPlainWord
    resultStageRegCipherWord := encAscon.io.out_msg
    resultStageRegNextCounter := modifyStageRegNextCounter
    resultStageRegVisibleCounter := modifyStageRegVisibleCounter
    resultStageRegEncryptCounter := modifyStageRegEncryptCounter
    resultStageRegCounterWen := modifyStageRegCounterWen
    resultStageRegRespData := modifyStageRegRespData
    resultStageRegWriteDone := false.B
    resultStageRegRespDone := false.B
    when (dbgAtomicLike(modifyStageRegReq.req.uop, modifyStageRegReq.req.uop.mem_cmd)) {
    }
  } .elsewhen (resultStageRegValid) {
    when (resultStageWriteFire) {
      resultStageRegWriteDone := true.B
    }
    when (resultStageRespFire) {
      resultStageRegRespDone := true.B
    }
    when (resultStageRegWillDequeue) {
      resultStageRegValid := false.B
    }
  }

  when (resultStageRegWillDequeue && !resultStageRegKilled) {
    shadow1Valid := true.B
    shadow1Req := refreshHitReqBrMask(resultStageRegReq)
    shadow1Chunk := resultStageRegChunk
    shadow1PlainWord := resultStageRegPlainWord
    shadow1NextCounter := resultStageRegNextCounter
    shadow1VisibleCounter := resultStageRegVisibleCounter
  } .otherwise {
    shadow1Valid := false.B
  }

  when (shadow1Valid && !shadow1Killed) {
    shadow2Valid := true.B
    shadow2Req := refreshHitReqBrMask(shadow1Req)
    shadow2Chunk := shadow1Chunk
    shadow2PlainWord := shadow1PlainWord
    shadow2NextCounter := shadow1NextCounter
    shadow2VisibleCounter := shadow1VisibleCounter
  } .otherwise {
    shadow2Valid := false.B
  }

  when (reencCanStart) {
    reencPending := false.B
    reencReadRespValid := false.B
    reencReadOutstanding := false.B
    reencDecValid := false.B
    reencEncValid := false.B
    reencActive := true.B
    reencIssueChunk := 0.U
    reencAllReadsIssued := false.B
    reencWriteMask := 0.U
  }
  when (reencActive && reencReadIssueFire) {
    reencReadOutstanding := true.B
    when (reencIssueChunk === lastLineChunk) {
      reencAllReadsIssued := true.B
    } .otherwise {
      reencIssueChunk := reencIssueChunk + 1.U
    }
  }
  when (reencReadRespCapture) {
    reencReadRespValid := true.B
    reencReadRespChunk := reencIncomingRespChunk
    reencReadRespCipher := reencIncomingRespCipher
  } .elsewhen (reencReadRespWillAdvance) {
    reencReadRespValid := false.B
  }
  when (reencRespConsumedThisCycle) {
    reencReadOutstanding := false.B
  }
  when (reencDecAdvance) {
    reencDecValid := true.B
    reencDecChunk := reencRespSrcChunk
    reencDecPlain := decAscon.io.out_msg
  } .elsewhen (reencDecWillAdvance) {
    reencDecValid := false.B
  }
  when (reencEncAdvance) {
    reencEncValid := true.B
    reencEncChunk := reencDecChunk
    reencEncCipher := encAscon.io.out_msg
  } .elsewhen (reencWriteFire) {
    reencEncValid := false.B
  }
  when (reencWriteFire) {
    val nextReencWriteMask = reencWriteMask | UIntToOH(reencEncChunk, lineChunkCount).asUInt
    assert(!dcacheCryptoAssertEnable || !reencWriteMask(reencEncChunk), "reenc wrote the same chunk twice before completion")
    reencWriteMask := nextReencWriteMask
  }
  when (reencFinalWriteFire) {
    val finalReencWriteMask = reencWriteMask | UIntToOH(reencEncChunk, lineChunkCount).asUInt
    assert(!dcacheCryptoAssertEnable || finalReencWriteMask.andR, "reenc completed before all cache-line chunks were rewritten")
    reencActive := false.B
    reencAllReadsIssued := false.B
    reencReadRespValid := false.B
    reencReadOutstanding := false.B
    reencDecValid := false.B
    reencEncValid := false.B
    reencDoneHoldCounter := reencDoneHoldCycles.U
  }
  when (reencDoneHoldCounter =/= 0.U) {
    reencDoneHoldCounter := reencDoneHoldCounter - 1.U
    when (reencDoneHoldCounter === 1.U) {
      reencMetaClearPending := true.B
    }
  }

  io.svc.data_write.valid := resultStageNeedWrite
  io.svc.data_write.bits.line := resultStageRegReq.line
  io.svc.data_write.bits.chunk := resultStageRegChunk
  io.svc.data_write.bits.data := resultStageRegCipherWord
  io.svc.data_write.bits.counter := resultStageRegEncryptCounter
  io.svc.data_write.bits.counter_wen := resultStageRegCounterWen
  when (resultStageWriteFire) {
  }
  when (resultStageWriteFire && dbgAtomicLike(resultStageRegReq.req.uop, resultStageRegReq.req.uop.mem_cmd)) {
  }
  when (reencActive) {
    io.svc.data_read.valid := reencReadIssueValid
    io.svc.data_read.bits.line := reencLine
    io.svc.data_read.bits.chunk := reencIssueChunk
    io.svc.data_write.valid := reencEncValid
    io.svc.data_write.bits.line := reencLine
    io.svc.data_write.bits.chunk := reencEncChunk
    io.svc.data_write.bits.data := reencEncCipher
    io.svc.data_write.bits.counter := reencNextCounter
    io.svc.data_write.bits.counter_wen := reencEncChunk === lastLineChunk
  }
  // when (!io.loadCryptoEnable && !io.storeCryptoEnable) {
    // modifyStageRegCounterWen := false.B
    // resultStageRegValid := false.B
    // resultStageRegWriteDone := false.B
    // resultStageRegRespDone := false.B
    // resultStageRegCounterWen := false.B
    // shadow1Valid := false.B
    // shadow2Valid := false.B
    // reencPending := false.B
    // reencActive := false.B
    // reencMetaSetPending := false.B
    // reencMetaClearPending := false.B
    // reencDoneHoldCounter := 0.U
    // reencAllReadsIssued := false.B
    // reencReadRespValid := false.B
    // reencReadOutstanding := false.B
    // reencDecValid := false.B
    // reencEncValid := false.B
    // reencWriteMask := 0.U
    // lastReencArmValid := false.B
  // }

  dontTouch(io.counterBaseAddress)
}
