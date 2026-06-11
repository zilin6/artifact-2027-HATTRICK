package sifive.blocks.inclusivecache

import chisel3._
import chisel3.experimental.{SourceInfo, UnlocatableSourceInfo}
import chisel3.util.{Decoupled, Valid, log2Ceil}
import chisel3.util.experimental.BoringUtils
import chiseltest._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes}
import freechips.rocketchip.rocket.{
  CacheCryptoCounterBitsKey,
  CacheCryptoRefillMeta,
  CacheCryptoRefillMetaField,
  CacheCryptoWritebackMeta,
  CacheCryptoWritebackMetaField
}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import org.chipsalliance.cde.config.Parameters
import MetaData._

object InclusiveCacheCounterTestUtils {
  implicit val p: Parameters = Parameters.empty.alterPartial {
    case CacheCryptoCounterBitsKey => 64
  }

  def params(implicit p: Parameters): InclusiveCacheParameters = {
    implicit val sourceInfo: SourceInfo = UnlocatableSourceInfo

    val cache = CacheParameters(
      level = 2,
      ways = 2,
      sets = 4,
      blockBytes = 16,
      beatBytes = 8,
      hintsSkipProbe = false)
    val micro = InclusiveCacheMicroParameters(
      writeBytes = 8,
      memCycles = 4,
      portFactor = 2,
      innerBuf = InclusiveCachePortParameters.none,
      outerBuf = InclusiveCachePortParameters.flowAE)

    val innerClient = TLMasterParameters.v2(
      name = "test-l1",
      sourceId = IdRange(0, 4),
      supports = TLSlaveToMasterTransferSizes(
        probe = TransferSizes(1, cache.blockBytes),
        arithmetic = TransferSizes(1, cache.beatBytes),
        logical = TransferSizes(1, cache.beatBytes),
        get = TransferSizes(1, cache.blockBytes),
        putFull = TransferSizes(1, cache.blockBytes),
        putPartial = TransferSizes(1, cache.blockBytes),
        hint = TransferSizes(1, cache.blockBytes)))
    val innerMasterPort = TLMasterPortParameters.v2(
      masters = Seq(innerClient),
      requestFields = Seq(CacheCryptoWritebackMetaField(counterBits = p(CacheCryptoCounterBitsKey))),
      responseKeys = Seq(CacheCryptoRefillMeta))
    val innerManager = TLSlaveParameters.v2(
      address = Seq(AddressSet(0x0, 0xffff)),
      regionType = RegionType.CACHED,
      supports = TLMasterToSlaveTransferSizes(
        acquireT = TransferSizes(cache.blockBytes, cache.blockBytes),
        acquireB = TransferSizes(cache.blockBytes, cache.blockBytes),
        arithmetic = TransferSizes(1, cache.beatBytes),
        logical = TransferSizes(1, cache.beatBytes),
        get = TransferSizes(1, cache.blockBytes),
        putFull = TransferSizes(1, cache.blockBytes),
        putPartial = TransferSizes(1, cache.blockBytes),
        hint = TransferSizes(1, cache.blockBytes)))
    val innerSlavePort = TLSlavePortParameters.v1(
      managers = Seq(innerManager),
      beatBytes = cache.beatBytes,
      endSinkId = 2,
      responseFields = Seq(CacheCryptoRefillMetaField(counterBits = p(CacheCryptoCounterBitsKey))),
      requestKeys = Seq(CacheCryptoWritebackMeta))

    val outerClient = TLMasterParameters.v2(
      name = "test-l2",
      sourceId = IdRange(0, 16),
      supports = TLSlaveToMasterTransferSizes(
        probe = TransferSizes.none,
        arithmetic = TransferSizes.none,
        logical = TransferSizes.none,
        get = TransferSizes.none,
        putFull = TransferSizes.none,
        putPartial = TransferSizes.none,
        hint = TransferSizes.none),
      emits = TLMasterToSlaveTransferSizes(
        acquireT = TransferSizes(cache.blockBytes, cache.blockBytes),
        acquireB = TransferSizes(cache.blockBytes, cache.blockBytes),
        arithmetic = TransferSizes.none,
        logical = TransferSizes.none,
        get = TransferSizes(8, cache.blockBytes),
        putFull = TransferSizes(8, cache.blockBytes),
        putPartial = TransferSizes.none,
        hint = TransferSizes.none))
    val outerMasterPort = TLMasterPortParameters.v2(
      masters = Seq(outerClient),
      requestFields = Seq(CacheCryptoWritebackMetaField(counterBits = p(CacheCryptoCounterBitsKey))),
      responseKeys = Seq(CacheCryptoRefillMeta))
    val outerManager = TLSlaveParameters.v2(
      address = Seq(AddressSet(0x0, 0xffff)),
      regionType = RegionType.UNCACHED,
      supports = TLMasterToSlaveTransferSizes(
        acquireT = TransferSizes(cache.blockBytes, cache.blockBytes),
        acquireB = TransferSizes(cache.blockBytes, cache.blockBytes),
        arithmetic = TransferSizes.none,
        logical = TransferSizes.none,
        get = TransferSizes(8, cache.blockBytes),
        putFull = TransferSizes(8, cache.blockBytes),
        putPartial = TransferSizes.none,
        hint = TransferSizes.none))
    val outerSlavePort = TLSlavePortParameters.v1(
      managers = Seq(outerManager),
      beatBytes = cache.beatBytes,
      endSinkId = 2,
      minLatency = 1)

    val inner = new TLEdgeIn(innerMasterPort, innerSlavePort, p, sourceInfo)
    val outer = new TLEdgeOut(outerMasterPort, outerSlavePort, p, sourceInfo)
    InclusiveCacheParameters(cache, micro, control = false, inner, outer)
  }

  val testParams = params
  val blockLgSize = log2Ceil(testParams.cache.blockBytes)
  val beatLgSize = log2Ceil(testParams.cache.beatBytes)

  val releaseBeat0 = BigInt("1122334455667788", 16)
  val releaseBeat1 = BigInt("99aabbccddeeff00", 16)
  val committedBeat0 = BigInt("deadbeefcafef00d", 16)
  val committedBeat1 = BigInt("0badf00dcafed00d", 16)
  val oldCounter = BigInt("0102030405060708", 16)
  val newCounter = BigInt("8877665544332211", 16)
  val altCounter = BigInt("1020304050607080", 16)

  def mshrSource(idx: Int, sourceType: Int = 0): BigInt =
    (BigInt(idx) << OuterRequestSourceType.width) | BigInt(sourceType)

  def clearSinkC(dut: SinkC): Unit = {
    dut.io.way.poke(1.U)
    dut.io.req.ready.poke(true.B)
    dut.io.bs_adr.ready.poke(true.B)
    dut.io.counter_write.ready.poke(true.B)
    dut.io.rel_pop.valid.poke(false.B)
    dut.io.rel_pop.bits.index.poke(0.U)
    dut.io.rel_pop.bits.last.poke(false.B)
    dut.io.c.valid.poke(false.B)
    dut.io.c.bits.opcode.poke(ReleaseData)
    dut.io.c.bits.param.poke(TtoT)
    dut.io.c.bits.size.poke(blockLgSize.U)
    dut.io.c.bits.source.poke(0.U)
    dut.io.c.bits.address.poke(0.U)
    dut.io.c.bits.data.poke(0.U)
    dut.io.c.bits.corrupt.poke(false.B)
    dut.io.c.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
    }
  }

  def driveReleaseBeat(
    dut: SinkC,
    data: BigInt,
    cryptoLine: Boolean,
    counter: BigInt,
    address: BigInt = 0,
    source: Int = 0,
    opcode: Int = ReleaseData.litValue.toInt): Unit = {
    dut.io.c.valid.poke(true.B)
    dut.io.c.bits.opcode.poke(opcode.U)
    dut.io.c.bits.param.poke(TtoT)
    dut.io.c.bits.size.poke(blockLgSize.U)
    dut.io.c.bits.source.poke(source.U)
    dut.io.c.bits.address.poke(address.U)
    dut.io.c.bits.data.poke(data.U)
    dut.io.c.bits.corrupt.poke(false.B)
    dut.io.c.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter.poke(counter.U)
      u.cryptoLine.poke(cryptoLine.B)
    }
  }

  def clearSourceA(dut: SourceA): Unit = {
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.tag.poke(0.U)
    dut.io.req.bits.set.poke(0.U)
    dut.io.req.bits.way.poke(1.U)
    dut.io.req.bits.put.poke(0.U)
    dut.io.req.bits.param.poke(0.U)
    dut.io.req.bits.source.poke(0.U)
    dut.io.req.bits.block.poke(true.B)
    dut.io.req.bits.isCounter.poke(false.B)
    dut.io.req.bits.isCounterWrite.poke(false.B)
    dut.io.cus_base_address.poke(0.U)
    dut.io.ctr_snapshot_data.poke(0.U)
    dut.io.ctr_snapshot_valid.poke(false.B)
    dut.io.a.ready.poke(true.B)
  }

  def clearSourceC(dut: SourceC): Unit = {
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.opcode.poke(ReleaseData)
    dut.io.req.bits.param.poke(TtoN)
    dut.io.req.bits.source.poke(0.U)
    dut.io.req.bits.tag.poke(0.U)
    dut.io.req.bits.set.poke(0.U)
    dut.io.req.bits.way.poke(1.U)
    dut.io.req.bits.dirty.poke(false.B)
    dut.io.req.bits.cryptoLine.poke(false.B)
    dut.io.c.ready.poke(true.B)
    dut.io.bs_adr.ready.poke(true.B)
    dut.io.bs_dat.data.poke(0.U)
    dut.io.ctr_radr.ready.poke(true.B)
    dut.io.ctr_rdat.poke(0.U)
    dut.io.ctr_snapshot_idx.poke(0.U)
    dut.io.ctr_snapshot_pop.poke(false.B)
    dut.io.evict_safe.poke(false.B)
  }

  def clearDirectory(dut: Directory): Unit = {
    dut.io.write.valid.poke(false.B)
    dut.io.write.bits.set.poke(0.U)
    dut.io.write.bits.way.poke(0.U)
    dut.io.write.bits.data.dirty.poke(false.B)
    dut.io.write.bits.data.state.poke(INVALID)
    dut.io.write.bits.data.clients.poke(0.U)
    dut.io.write.bits.data.tag.poke(0.U)
    dut.io.write.bits.data.cryptoLine.poke(false.B)
    dut.io.write.bits.data.counterValid.poke(false.B)
    dut.io.read.valid.poke(false.B)
    dut.io.read.bits.set.poke(0.U)
    dut.io.read.bits.tag.poke(0.U)
    dut.io.read.bits.cryptoLine.poke(false.B)
  }

  def clearMSHR(dut: MSHR): Unit = {
    dut.io.allocate.valid.poke(false.B)
    dut.io.allocate.bits.prio.foreach(_.poke(false.B))
    dut.io.allocate.bits.control.poke(false.B)
    dut.io.allocate.bits.opcode.poke(0.U)
    dut.io.allocate.bits.param.poke(0.U)
    dut.io.allocate.bits.size.poke(blockLgSize.U)
    dut.io.allocate.bits.source.poke(0.U)
    dut.io.allocate.bits.tag.poke(0.U)
    dut.io.allocate.bits.offset.poke(0.U)
    dut.io.allocate.bits.put.poke(0.U)
    dut.io.allocate.bits.set.poke(0.U)
    dut.io.allocate.bits.cryptoLine.poke(false.B)
    dut.io.allocate.bits.repeat.poke(false.B)

    dut.io.directory.valid.poke(false.B)
    dut.io.directory.bits.dirty.poke(false.B)
    dut.io.directory.bits.state.poke(INVALID)
    dut.io.directory.bits.clients.poke(0.U)
    dut.io.directory.bits.tag.poke(0.U)
    dut.io.directory.bits.cryptoLine.poke(false.B)
    dut.io.directory.bits.counterValid.poke(false.B)
    dut.io.directory.bits.hit.poke(false.B)
    dut.io.directory.bits.way.poke(0.U)

    dut.io.schedule.ready.poke(true.B)
    dut.io.sinkc.valid.poke(false.B)
    dut.io.sinkd.valid.poke(false.B)
    dut.io.sinke.valid.poke(false.B)
    dut.io.nestedwb.set.poke(0.U)
    dut.io.nestedwb.tag.poke(0.U)
    dut.io.nestedwb.b_toN.poke(false.B)
    dut.io.nestedwb.b_toB.poke(false.B)
    dut.io.nestedwb.b_clr_dirty.poke(false.B)
    dut.io.nestedwb.c_set_dirty.poke(false.B)
  }
}

class SourceASnapshotSendHarness(params: InclusiveCacheParameters) extends Module {
  val io = IO(new Bundle {
    val sourceAReq = Flipped(Decoupled(new SourceARequest(params)))
    val sourceAOut = Decoupled(new TLBundleA(params.outer.bundle))
    val snapshotIdx = Output(UInt(log2Ceil(params.mshrs).W))
    val snapshotData = Input(UInt(params.outer.bundle.dataBits.W))
    val snapshotValid = Input(Bool())
    val snapshotPop = Output(Bool())
    val observedCounterValue = Output(UInt(params.outer.bundle.dataBits.W))
  })

  val sourceA = Module(new SourceA(params))

  io.sourceAReq <> sourceA.io.req
  io.sourceAOut <> sourceA.io.a
  sourceA.io.cus_base_address := 0.U
  io.snapshotIdx := sourceA.io.ctr_snapshot_idx
  sourceA.io.ctr_snapshot_data := io.snapshotData
  sourceA.io.ctr_snapshot_valid := io.snapshotValid
  io.snapshotPop := sourceA.io.ctr_snapshot_pop
  io.observedCounterValue := sourceA.io.a.bits.data
}

class SourceCSnapshotHarness(params: InclusiveCacheParameters) extends Module {
  val io = IO(new Bundle {
    val sourceCReq = Flipped(Decoupled(new SourceCRequest(params)))
    val sourceCOut = Decoupled(new TLBundleC(params.outer.bundle))
    val sourceAReq = Flipped(Decoupled(new SourceARequest(params)))
    val sourceAOut = Decoupled(new TLBundleA(params.outer.bundle))
    val committedData = Input(UInt((params.outer.manager.beatBytes * 8).W))
    val committedCounter = Input(UInt(params.outer.bundle.dataBits.W))
    val evictSafe = Input(Bool())
    val snapshotIdx = Input(UInt(log2Ceil(params.mshrs).W))
    val snapshotData = Output(UInt(params.outer.bundle.dataBits.W))
    val snapshotValid = Output(Bool())
    val snapshotPop = Output(Bool())
    val ctrReadValid = Output(Bool())
    val ctrReadFire = Output(Bool())
    val bsReadValid = Output(Bool())
    val bsReadFire = Output(Bool())
    val ctrReadResp = Output(Bool())
    val snapshotReqMshrIdx = Output(UInt(log2Ceil(params.mshrs).W))
    val currentBeat = Output(UInt(params.outerBeatBits.W))
    val s2Beat = Output(UInt(params.outerBeatBits.W))
    val s3Beat = Output(UInt(params.outerBeatBits.W))
    val s2Valid = Output(Bool())
    val s3Valid = Output(Bool())
  })

  val sourceC = Module(new SourceC(params))
  val sourceA = Module(new SourceA(params))

  io.sourceCReq <> sourceC.io.req
  io.sourceCOut <> sourceC.io.c
  io.sourceAReq <> sourceA.io.req
  io.sourceAOut <> sourceA.io.a
  sourceA.io.cus_base_address := 0.U

  sourceC.io.bs_adr.ready := true.B
  sourceC.io.bs_dat.data := io.committedData
  sourceC.io.ctr_radr.ready := true.B
  sourceC.io.ctr_rdat := io.committedCounter
  sourceC.io.evict_safe := io.evictSafe
  sourceC.io.ctr_snapshot_idx := io.snapshotIdx
  sourceC.io.ctr_snapshot_pop := sourceA.io.ctr_snapshot_pop

  sourceA.io.ctr_snapshot_data := sourceC.io.ctr_snapshot_data
  sourceA.io.ctr_snapshot_valid := sourceC.io.ctr_snapshot_valid

  io.snapshotData := sourceC.io.ctr_snapshot_data
  io.snapshotValid := sourceC.io.ctr_snapshot_valid
  io.snapshotPop := sourceA.io.ctr_snapshot_pop
  io.ctrReadValid := sourceC.io.ctr_radr.valid
  io.ctrReadFire := sourceC.io.ctr_radr.fire
  io.bsReadValid := sourceC.io.bs_adr.valid
  io.bsReadFire := sourceC.io.bs_adr.fire
  io.ctrReadResp := false.B
  io.snapshotReqMshrIdx := 0.U
  io.currentBeat := 0.U
  io.s2Beat := 0.U
  io.s3Beat := 0.U
  io.s2Valid := false.B
  io.s3Valid := false.B
  BoringUtils.bore(sourceC.ctr_read_resp, Seq(io.ctrReadResp))
  BoringUtils.bore(sourceC.reqMshrIdx, Seq(io.snapshotReqMshrIdx))
  BoringUtils.bore(sourceC.beat, Seq(io.currentBeat))
  BoringUtils.bore(sourceC.s2_beat, Seq(io.s2Beat))
  BoringUtils.bore(sourceC.s3_beat, Seq(io.s3Beat))
  BoringUtils.bore(sourceC.s2_valid, Seq(io.s2Valid))
  BoringUtils.bore(sourceC.s3_valid, Seq(io.s3Valid))
}

class SourceDObservationHarness(params: InclusiveCacheParameters) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new SourceDRequest(params)))
    val ctrRadr = Decoupled(new CounterSidecarAddress(params))
    val ctrRdat = Input(UInt(params.outer.bundle.dataBits.W))
    val d = Decoupled(new TLBundleD(params.inner.bundle))
    val pbPop = Decoupled(new PutBufferPop(params))
    val pbBeat = Flipped(new PutBufferAEntry(params))
    val relPop = Decoupled(new PutBufferPop(params))
    val relBeat = Flipped(new PutBufferCEntry(params))
    val bsRadr = Decoupled(new BankedStoreInnerAddress(params))
    val bsRdat = Flipped(new BankedStoreInnerDecoded(params))
    val bsWadr = Decoupled(new BankedStoreInnerAddress(params))
    val bsWdat = new BankedStoreInnerPoison(params)
    val counterWrite = Decoupled(new CounterSidecarWrite(params))
    val evictReq = Flipped(new SourceDHazard(params))
    val evictSafe = Output(Bool())
    val counterGrantReq = Flipped(new SourceDHazard(params))
    val counterGrantSafe = Output(Bool())
    val grantReq = Flipped(new SourceDHazard(params))
    val grantSafe = Output(Bool())
    val counterHitS2 = Output(Bool())
    val counterHitS3 = Output(Bool())
    val counterHitS4 = Output(Bool())
    val counterHitS5 = Output(Bool())
    val counterHitS6 = Output(Bool())
    val counterHitS7 = Output(Bool())
    val counterBypassHit = Output(Bool())
    val counterBypassData = Output(UInt(params.outer.bundle.dataBits.W))
    val s2CounterPayloadHit = Output(Bool())
    val s2CounterPipelineHit = Output(Bool())
    val s2Counter = Output(UInt(params.outer.bundle.dataBits.W))
    val s2CounterPayloadData = Output(UInt(params.outer.bundle.dataBits.W))
    val s3Counter = Output(UInt(params.outer.bundle.dataBits.W))
    val s3CounterPayloadData = Output(UInt(params.outer.bundle.dataBits.W))
    val s3CtrNeedPb = Output(Bool())
    val s3CtrNeedR = Output(Bool())
    val preS3CtrNeedR = Output(Bool())
    val s2NeedR = Output(Bool())
    val s2Full = Output(Bool())
    val s3Full = Output(Bool())
    val s3Ready = Output(Bool())
    val ctrQueueEnqValid = Output(Bool())
    val ctrQueueDeqValid = Output(Bool())
    val s4Counter = Output(UInt(params.outer.bundle.dataBits.W))
    val s5Counter = Output(UInt(params.outer.bundle.dataBits.W))
    val s6Counter = Output(UInt(params.outer.bundle.dataBits.W))
    val s7Counter = Output(UInt(params.outer.bundle.dataBits.W))
    val s5Valid = Output(Bool())
    val s6Valid = Output(Bool())
    val s7Valid = Output(Bool())
    val s1DataBypassMask = Output(UInt((params.inner.manager.beatBytes / params.micro.writeBytes).W))
    val s3BypassMask = Output(UInt((params.inner.manager.beatBytes / params.micro.writeBytes).W))
    val s4ReleaseCounterWrite = Output(Bool())
    val capturedRespFire = Output(Bool())
    val capturedRespData = Output(UInt(params.inner.bundle.dataBits.W))
    val capturedRespCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val capturedRespDataBypassMask = Output(UInt((params.inner.manager.beatBytes / params.micro.writeBytes).W))
  })

  val sourceD = Module(new SourceD(params))

  io.req <> sourceD.io.req
  io.ctrRadr <> sourceD.io.ctr_radr
  sourceD.io.ctr_rdat := io.ctrRdat
  io.d <> sourceD.io.d
  io.pbPop <> sourceD.io.pb_pop
  sourceD.io.pb_beat := io.pbBeat
  io.relPop <> sourceD.io.rel_pop
  sourceD.io.rel_beat := io.relBeat
  io.bsRadr <> sourceD.io.bs_radr
  sourceD.io.bs_rdat := io.bsRdat
  io.bsWadr <> sourceD.io.bs_wadr
  io.bsWdat <> sourceD.io.bs_wdat
  io.counterWrite <> sourceD.io.counter_write
  sourceD.io.evict_req <> io.evictReq
  io.evictSafe := sourceD.io.evict_safe
  sourceD.io.counter_grant_req <> io.counterGrantReq
  io.counterGrantSafe := sourceD.io.counter_grant_safe
  sourceD.io.grant_req <> io.grantReq
  io.grantSafe := sourceD.io.grant_safe

  io.counterHitS2 := false.B
  io.counterHitS3 := false.B
  io.counterHitS4 := false.B
  io.counterHitS5 := false.B
  io.counterHitS6 := false.B
  io.counterHitS7 := false.B
  io.counterBypassHit := false.B
  io.counterBypassData := 0.U
  io.s2CounterPayloadHit := false.B
  io.s2CounterPipelineHit := false.B
  io.s2Counter := 0.U
  io.s2CounterPayloadData := 0.U
  io.s3Counter := 0.U
  io.s3CounterPayloadData := 0.U
  io.s3CtrNeedPb := false.B
  io.s3CtrNeedR := false.B
  io.preS3CtrNeedR := false.B
  io.s2NeedR := false.B
  io.s2Full := false.B
  io.s3Full := false.B
  io.s3Ready := false.B
  io.ctrQueueEnqValid := false.B
  io.ctrQueueDeqValid := false.B
  io.s4Counter := 0.U
  io.s5Counter := 0.U
  io.s6Counter := 0.U
  io.s7Counter := 0.U
  io.s5Valid := false.B
  io.s6Valid := false.B
  io.s7Valid := false.B
  io.s1DataBypassMask := 0.U
  io.s3BypassMask := 0.U
  io.s4ReleaseCounterWrite := false.B
  io.capturedRespFire := false.B
  io.capturedRespData := 0.U
  io.capturedRespCounter := 0.U
  io.capturedRespDataBypassMask := 0.U
  val capturedRespFireReg = RegInit(false.B)
  val capturedRespDataReg = RegInit(0.U(params.inner.bundle.dataBits.W))
  val capturedRespCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  val capturedRespDataBypassMaskReg = RegInit(0.U((params.inner.manager.beatBytes / params.micro.writeBytes).W))
  when (sourceD.io.d.valid && sourceD.io.d.bits.opcode === TLMessages.AccessAckData) {
    capturedRespFireReg := true.B
    capturedRespDataReg := sourceD.io.d.bits.data
    capturedRespDataBypassMaskReg := io.s3BypassMask
    sourceD.io.d.bits.user.lift(freechips.rocketchip.rocket.CacheCryptoRefillMeta).foreach { u =>
      capturedRespCounterReg := u.counter
    }
  }
  io.capturedRespFire := capturedRespFireReg
  io.capturedRespData := capturedRespDataReg
  io.capturedRespCounter := capturedRespCounterReg
  io.capturedRespDataBypassMask := capturedRespDataBypassMaskReg
  io.counterHitS5 := false.B
  io.counterHitS6 := false.B
  io.counterHitS7 := false.B
  BoringUtils.bore(sourceD.s1_counter_pipe_hit_s2, Seq(io.counterHitS2))
  BoringUtils.bore(sourceD.s1_counter_pipe_hit_s3, Seq(io.counterHitS3))
  BoringUtils.bore(sourceD.s1_counter_pipe_hit_s4, Seq(io.counterHitS4))
  BoringUtils.bore(sourceD.s1_counter_pipe_hit, Seq(io.counterBypassHit))
  BoringUtils.bore(sourceD.s3_counter_pipe_value, Seq(io.counterBypassData))
  BoringUtils.bore(sourceD.s2_counter_payload_valid, Seq(io.s2CounterPayloadHit))
  BoringUtils.bore(sourceD.s3_counter_pipe_available, Seq(io.s2CounterPipelineHit))
  BoringUtils.bore(sourceD.s2_counter_payload_value, Seq(io.s2Counter))
  BoringUtils.bore(sourceD.s2_counter_payload_raw, Seq(io.s2CounterPayloadData))
  BoringUtils.bore(sourceD.s3_counter_rdata, Seq(io.s3Counter))
  BoringUtils.bore(sourceD.s3_counter_payload_value, Seq(io.s3CounterPayloadData))
  BoringUtils.bore(sourceD.s3_counter_payload_path, Seq(io.s3CtrNeedPb))
  BoringUtils.bore(sourceD.s3_counter_needs_committed, Seq(io.s3CtrNeedR))
  BoringUtils.bore(sourceD.pre_s3_ctr_need_r, Seq(io.preS3CtrNeedR))
  BoringUtils.bore(sourceD.s2_need_r, Seq(io.s2NeedR))
  BoringUtils.bore(sourceD.s2_full, Seq(io.s2Full))
  BoringUtils.bore(sourceD.s3_full, Seq(io.s3Full))
  BoringUtils.bore(sourceD.s3_ready, Seq(io.s3Ready))
  BoringUtils.bore(sourceD.ctrQueue.io.enq.valid, Seq(io.ctrQueueEnqValid))
  BoringUtils.bore(sourceD.ctrQueue.io.deq.valid, Seq(io.ctrQueueDeqValid))
  BoringUtils.bore(sourceD.s4_counter, Seq(io.s4Counter))
  BoringUtils.bore(sourceD.s5_counter_value, Seq(io.s5Counter))
  BoringUtils.bore(sourceD.s6_counter_value, Seq(io.s6Counter))
  BoringUtils.bore(sourceD.s7_counter_value, Seq(io.s7Counter))
  BoringUtils.bore(sourceD.s5_counter_valid, Seq(io.s5Valid))
  BoringUtils.bore(sourceD.s6_counter_valid, Seq(io.s6Valid))
  BoringUtils.bore(sourceD.s7_counter_valid, Seq(io.s7Valid))
  BoringUtils.bore(sourceD.s1_x_bypass, Seq(io.s1DataBypassMask))
  BoringUtils.bore(sourceD.s3_bypass, Seq(io.s3BypassMask))
  BoringUtils.bore(sourceD.s4_releaseCounterWrite, Seq(io.s4ReleaseCounterWrite))
}

class MSHRObservationHarness(params: InclusiveCacheParameters) extends Module {
  val io = IO(new Bundle {
    val allocate = Flipped(Valid(new AllocateRequest(params)))
    val directory = Flipped(Valid(new DirectoryResult(params)))
    val status = Valid(new MSHRStatus(params))
    val schedule = Decoupled(new ScheduleRequest(params))
    val sinkc = Flipped(Valid(new SinkCResponse(params)))
    val sinkd = Flipped(Valid(new SinkDResponse(params)))
    val sinke = Flipped(Valid(new SinkEResponse(params)))
    val nestedwb = Flipped(new NestedWriteback(params))
    val victimMetaCryptoLine = Output(Bool())
    val victimMetaCounterValid = Output(Bool())
    val victimMetaTag = Output(UInt(params.tagBits.W))
    val refillMetaCryptoLine = Output(Bool())
    val refillMetaCounterValid = Output(Bool())
    val refillMetaTag = Output(UInt(params.tagBits.W))
    val finalMetaCryptoLine = Output(Bool())
    val finalMetaCounterValid = Output(Bool())
    val sameTagModeMismatch = Output(Bool())
    val newSameTagModeMismatch = Output(Bool())
    val needCounterPut = Output(Bool())
  })

  val mshr = Module(new MSHR(params))

  mshr.io.allocate <> io.allocate
  mshr.io.directory <> io.directory
  io.status <> mshr.io.status
  io.schedule <> mshr.io.schedule
  mshr.io.sinkc <> io.sinkc
  mshr.io.sinkd <> io.sinkd
  mshr.io.sinke <> io.sinke
  mshr.io.nestedwb <> io.nestedwb

  io.victimMetaCryptoLine := false.B
  io.victimMetaCounterValid := false.B
  io.victimMetaTag := 0.U
  io.refillMetaCryptoLine := false.B
  io.refillMetaCounterValid := false.B
  io.refillMetaTag := 0.U
  io.finalMetaCryptoLine := false.B
  io.finalMetaCounterValid := false.B
  io.sameTagModeMismatch := false.B
  io.newSameTagModeMismatch := false.B
  io.needCounterPut := false.B
  BoringUtils.bore(mshr.victimMeta.cryptoLine, Seq(io.victimMetaCryptoLine))
  BoringUtils.bore(mshr.victimMeta.counterValid, Seq(io.victimMetaCounterValid))
  BoringUtils.bore(mshr.victimMeta.tag, Seq(io.victimMetaTag))
  BoringUtils.bore(mshr.refillMeta.cryptoLine, Seq(io.refillMetaCryptoLine))
  BoringUtils.bore(mshr.refillMeta.counterValid, Seq(io.refillMetaCounterValid))
  BoringUtils.bore(mshr.refillMeta.tag, Seq(io.refillMetaTag))
  BoringUtils.bore(mshr.final_meta_writeback.cryptoLine, Seq(io.finalMetaCryptoLine))
  BoringUtils.bore(mshr.final_meta_writeback.counterValid, Seq(io.finalMetaCounterValid))
  BoringUtils.bore(mshr.same_tag_mode_mismatch, Seq(io.sameTagModeMismatch))
  BoringUtils.bore(mshr.new_same_tag_mode_mismatch, Seq(io.newSameTagModeMismatch))
  BoringUtils.bore(mshr.need_counter_put, Seq(io.needCounterPut))
}

class SinkCSourceDClosureHarness(params: InclusiveCacheParameters) extends Module {
  val io = IO(new Bundle {
    val sinkC = Flipped(Decoupled(new TLBundleC(params.inner.bundle)))
    val sourceDReq = Flipped(Decoupled(new SourceDRequest(params)))
    val sourceDOut = Decoupled(new TLBundleD(params.inner.bundle))
    val committedData = Input(UInt((params.inner.manager.beatBytes * 8).W))
    val committedCounter = Input(UInt(params.outer.bundle.dataBits.W))
    val sinkReqValid = Output(Bool())
    val sinkReqPut = Output(UInt(params.putBits.W))
    val sinkPayloadData = Output(UInt(params.inner.bundle.dataBits.W))
    val sinkPayloadCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val sinkPayloadCounterValid = Output(Bool())
    val relPopValid = Output(Bool())
    val relPopIndex = Output(UInt(params.putBits.W))
    val relPopReady = Output(Bool())
    val sourceDRelBeatData = Output(UInt(params.inner.bundle.dataBits.W))
    val sourceDRelCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val sourceDRelCounterValid = Output(Bool())
    val consumedRelFire = Output(Bool())
    val consumedRelIdx = Output(UInt(params.putBits.W))
    val consumedRelData = Output(UInt(params.inner.bundle.dataBits.W))
    val consumedRelCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val consumedRelCounterValid = Output(Bool())
    val consumedLastRelFire = Output(Bool())
    val consumedLastRelIdx = Output(UInt(params.putBits.W))
    val consumedLastRelData = Output(UInt(params.inner.bundle.dataBits.W))
    val consumedLastRelCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val consumedLastRelCounterValid = Output(Bool())
    val sourceDS3BypassMask = Output(UInt((params.inner.manager.beatBytes / params.micro.writeBytes).W))
    val sourceDS3Counter = Output(UInt(params.outer.bundle.dataBits.W))
    val sourceDS4Counter = Output(UInt(params.outer.bundle.dataBits.W))
    val usedPayloadData = Output(Bool())
    val usedCommittedData = Output(Bool())
    val capturedRespFire = Output(Bool())
    val capturedRespData = Output(UInt(params.inner.bundle.dataBits.W))
    val capturedRespCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val capturedUsedPayloadData = Output(Bool())
    val capturedUsedCommittedData = Output(Bool())
    val capturedReadReqFire = Output(Bool())
    val capturedReadReqDataBypassMask = Output(UInt((params.inner.manager.beatBytes / params.micro.writeBytes).W))
    val capturedReadReqCounterBypassHit = Output(Bool())
    val sourceDHitS2 = Output(Bool())
    val sourceDHitS3 = Output(Bool())
    val sourceDHitS4 = Output(Bool())
    val sourceDHitS5 = Output(Bool())
    val sourceDHitS6 = Output(Bool())
    val sourceDHitS7 = Output(Bool())
    val sourceDS2Counter = Output(UInt(params.outer.bundle.dataBits.W))
    val sourceDS2PayloadHit = Output(Bool())
    val sourceDS2PipelineHit = Output(Bool())
    val sourceDPayloadHit = Output(Bool())
    val sourceDPipelineHit = Output(Bool())
    val sourceDBypassHit = Output(Bool())
    val sourceDBypassData = Output(UInt(params.outer.bundle.dataBits.W))
  })

  val sink = Module(new SinkC(params))
  val sourceD = Module(new SourceDObservationHarness(params))

  sink.io.c <> io.sinkC
  sink.io.req.ready := true.B
  sink.io.way := 1.U
  sink.io.bs_adr.ready := true.B
  sink.io.counter_write.ready := true.B

  sourceD.io.req <> io.sourceDReq
  io.sourceDOut <> sourceD.io.d
  sourceD.io.ctrRadr.ready := true.B
  sourceD.io.ctrRdat := io.committedCounter
  sourceD.io.pbPop.ready := true.B
  sourceD.io.pbBeat.data := 0.U
  sourceD.io.pbBeat.mask := 0.U
  sourceD.io.pbBeat.corrupt := false.B
  sourceD.io.pbBeat.counter := 0.U
  sourceD.io.pbBeat.counterValid := false.B
  sourceD.io.bsRadr.ready := true.B
  sourceD.io.bsRdat.data := io.committedData
  sourceD.io.bsWadr.ready := true.B
  sourceD.io.counterWrite.ready := true.B
  sourceD.io.evictReq.set := 0.U
  sourceD.io.evictReq.way := 0.U
  sourceD.io.counterGrantReq.set := 0.U
  sourceD.io.counterGrantReq.way := 0.U
  sourceD.io.grantReq.set := 0.U
  sourceD.io.grantReq.way := 0.U

  sink.io.rel_pop.valid := sourceD.io.relPop.valid
  sink.io.rel_pop.bits := sourceD.io.relPop.bits
  sourceD.io.relPop.ready := sink.io.rel_pop.ready
  sourceD.io.relBeat := sink.io.rel_beat

  val consumedRelFireReg = RegInit(false.B)
  val consumedRelIdxReg = RegInit(0.U(params.putBits.W))
  val consumedRelDataReg = RegInit(0.U(params.inner.bundle.dataBits.W))
  val consumedRelCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  val consumedRelCounterValidReg = RegInit(false.B)
  val consumedLastRelFireReg = RegInit(false.B)
  val consumedLastRelIdxReg = RegInit(0.U(params.putBits.W))
  val consumedLastRelDataReg = RegInit(0.U(params.inner.bundle.dataBits.W))
  val consumedLastRelCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  val consumedLastRelCounterValidReg = RegInit(false.B)
  when (sourceD.io.relPop.valid && sourceD.io.relPop.ready && !consumedRelFireReg) {
    consumedRelFireReg := true.B
    consumedRelIdxReg := sourceD.io.relPop.bits.index
    consumedRelDataReg := sourceD.io.relBeat.data
    consumedRelCounterReg := sourceD.io.relBeat.counter
    consumedRelCounterValidReg := sourceD.io.relBeat.counterValid
  }
  when (sourceD.io.relPop.valid && sourceD.io.relPop.ready && sourceD.io.relPop.bits.last) {
    consumedLastRelFireReg := true.B
    consumedLastRelIdxReg := sourceD.io.relPop.bits.index
    consumedLastRelDataReg := sourceD.io.relBeat.data
    consumedLastRelCounterReg := sourceD.io.relBeat.counter
    consumedLastRelCounterValidReg := sourceD.io.relBeat.counterValid
  }

  val usedPayloadDataReg = RegInit(false.B)
  val usedCommittedDataReg = RegInit(false.B)
  val capturedRespFireReg = RegInit(false.B)
  val capturedRespDataReg = RegInit(0.U(params.inner.bundle.dataBits.W))
  val capturedRespCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  val capturedUsedPayloadDataReg = RegInit(false.B)
  val capturedUsedCommittedDataReg = RegInit(false.B)
  val capturedReadReqFireReg = RegInit(false.B)
  val capturedReadReqDataBypassMaskReg = RegInit(0.U((params.inner.manager.beatBytes / params.micro.writeBytes).W))
  val capturedReadReqCounterBypassHitReg = RegInit(false.B)
  when (sourceD.io.req.valid && sourceD.io.req.ready && sourceD.io.req.bits.prio(0)) {
    capturedReadReqFireReg := true.B
    capturedReadReqDataBypassMaskReg := sourceD.io.s1DataBypassMask
    capturedReadReqCounterBypassHitReg := sourceD.io.counterBypassHit
  }
  when (sourceD.io.d.valid && sourceD.io.d.bits.opcode === TLMessages.AccessAckData) {
    usedPayloadDataReg := sourceD.io.s3BypassMask.orR
    usedCommittedDataReg := !sourceD.io.s3BypassMask.orR
    capturedRespFireReg := true.B
    capturedRespDataReg := sourceD.io.d.bits.data
    capturedUsedPayloadDataReg := sourceD.io.s3BypassMask.orR
    capturedUsedCommittedDataReg := !sourceD.io.s3BypassMask.orR
    sourceD.io.d.bits.user.lift(freechips.rocketchip.rocket.CacheCryptoRefillMeta).foreach { u =>
      capturedRespCounterReg := u.counter
    }
  }

  io.sinkReqValid := sink.io.req.valid
  io.sinkReqPut := sink.io.req.bits.put
  io.sinkPayloadData := sink.io.rel_beat.data
  io.sinkPayloadCounter := sink.io.rel_beat.counter
  io.sinkPayloadCounterValid := sink.io.rel_beat.counterValid
  io.relPopValid := sourceD.io.relPop.valid
  io.relPopIndex := sourceD.io.relPop.bits.index
  io.relPopReady := sourceD.io.relPop.ready
  io.sourceDRelBeatData := sourceD.io.relBeat.data
  io.sourceDRelCounter := sourceD.io.relBeat.counter
  io.sourceDRelCounterValid := sourceD.io.relBeat.counterValid
  io.consumedRelFire := consumedRelFireReg
  io.consumedRelIdx := consumedRelIdxReg
  io.consumedRelData := consumedRelDataReg
  io.consumedRelCounter := consumedRelCounterReg
  io.consumedRelCounterValid := consumedRelCounterValidReg
  io.consumedLastRelFire := consumedLastRelFireReg
  io.consumedLastRelIdx := consumedLastRelIdxReg
  io.consumedLastRelData := consumedLastRelDataReg
  io.consumedLastRelCounter := consumedLastRelCounterReg
  io.consumedLastRelCounterValid := consumedLastRelCounterValidReg
  io.sourceDS3BypassMask := sourceD.io.s3BypassMask
  io.sourceDS3Counter := sourceD.io.s3Counter
  io.sourceDS4Counter := sourceD.io.s4Counter
  io.usedPayloadData := usedPayloadDataReg
  io.usedCommittedData := usedCommittedDataReg
  io.capturedRespFire := capturedRespFireReg
  io.capturedRespData := capturedRespDataReg
  io.capturedRespCounter := capturedRespCounterReg
  io.capturedUsedPayloadData := capturedUsedPayloadDataReg
  io.capturedUsedCommittedData := capturedUsedCommittedDataReg
  io.capturedReadReqFire := capturedReadReqFireReg
  io.capturedReadReqDataBypassMask := capturedReadReqDataBypassMaskReg
  io.capturedReadReqCounterBypassHit := capturedReadReqCounterBypassHitReg
  io.sourceDHitS2 := sourceD.io.counterHitS2
  io.sourceDHitS3 := sourceD.io.counterHitS3
  io.sourceDHitS4 := sourceD.io.counterHitS4
  io.sourceDHitS5 := sourceD.io.counterHitS5
  io.sourceDHitS6 := sourceD.io.counterHitS6
  io.sourceDHitS7 := sourceD.io.counterHitS7
  io.sourceDS2Counter := sourceD.io.s2Counter
  io.sourceDS2PayloadHit := sourceD.io.s2CounterPayloadHit
  io.sourceDS2PipelineHit := sourceD.io.s2CounterPipelineHit
  io.sourceDPayloadHit := sourceD.io.s2CounterPayloadHit
  io.sourceDPipelineHit := sourceD.io.s2CounterPipelineHit
  io.sourceDBypassHit := sourceD.io.counterBypassHit
  io.sourceDBypassData := sourceD.io.counterBypassData
}

class SinkCSourceDStorageFeedbackHarness(params: InclusiveCacheParameters) extends Module {
  val io = IO(new Bundle {
    val sinkC = Flipped(Decoupled(new TLBundleC(params.inner.bundle)))
    val sourceDReq = Flipped(Decoupled(new SourceDRequest(params)))
    val sourceDOut = Decoupled(new TLBundleD(params.inner.bundle))
    val initialBeat0 = Input(UInt((params.inner.manager.beatBytes * 8).W))
    val initialBeat1 = Input(UInt((params.inner.manager.beatBytes * 8).W))
    val initialCounter = Input(UInt(params.outer.bundle.dataBits.W))
    val sinkReqValid = Output(Bool())
    val sinkReqPut = Output(UInt(params.putBits.W))
    val consumedRelFire = Output(Bool())
    val consumedRelIdx = Output(UInt(params.putBits.W))
    val consumedRelData = Output(UInt(params.inner.bundle.dataBits.W))
    val consumedRelCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val consumedRelCounterValid = Output(Bool())
    val consumedLastRelFire = Output(Bool())
    val consumedLastRelIdx = Output(UInt(params.putBits.W))
    val consumedLastRelData = Output(UInt(params.inner.bundle.dataBits.W))
    val consumedLastRelCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val consumedLastRelCounterValid = Output(Bool())
    val dataWriteFire = Output(Bool())
    val dataWriteBeat = Output(UInt(params.innerBeatBits.W))
    val dataWriteData = Output(UInt((params.inner.manager.beatBytes * 8).W))
    val counterWriteFire = Output(Bool())
    val counterWriteData = Output(UInt(params.outer.bundle.dataBits.W))
    val feedbackBeat0Valid = Output(Bool())
    val feedbackBeat1Valid = Output(Bool())
    val feedbackCounterValid = Output(Bool())
    val feedbackBeat0Data = Output(UInt((params.inner.manager.beatBytes * 8).W))
    val feedbackBeat1Data = Output(UInt((params.inner.manager.beatBytes * 8).W))
    val feedbackCounterData = Output(UInt(params.outer.bundle.dataBits.W))
  })

  val sink = Module(new SinkC(params))
  val sourceD = Module(new SourceDObservationHarness(params))

  sink.io.c <> io.sinkC
  sink.io.req.ready := true.B
  sink.io.way := 1.U
  sink.io.bs_adr.ready := true.B
  sink.io.counter_write.ready := true.B

  sourceD.io.req <> io.sourceDReq
  io.sourceDOut <> sourceD.io.d
  sourceD.io.ctrRadr.ready := true.B
  sourceD.io.pbPop.ready := true.B
  sourceD.io.pbBeat.data := 0.U
  sourceD.io.pbBeat.mask := 0.U
  sourceD.io.pbBeat.corrupt := false.B
  sourceD.io.pbBeat.counter := 0.U
  sourceD.io.pbBeat.counterValid := false.B
  sourceD.io.bsRadr.ready := true.B
  sourceD.io.bsWadr.ready := true.B
  sourceD.io.counterWrite.ready := true.B
  sourceD.io.evictReq.set := 0.U
  sourceD.io.evictReq.way := 0.U
  sourceD.io.counterGrantReq.set := 0.U
  sourceD.io.counterGrantReq.way := 0.U
  sourceD.io.grantReq.set := 0.U
  sourceD.io.grantReq.way := 0.U

  sink.io.rel_pop.valid := sourceD.io.relPop.valid
  sink.io.rel_pop.bits := sourceD.io.relPop.bits
  sourceD.io.relPop.ready := sink.io.rel_pop.ready
  sourceD.io.relBeat := sink.io.rel_beat

  val consumedRelFireReg = RegInit(false.B)
  val consumedRelIdxReg = RegInit(0.U(params.putBits.W))
  val consumedRelDataReg = RegInit(0.U(params.inner.bundle.dataBits.W))
  val consumedRelCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  val consumedRelCounterValidReg = RegInit(false.B)
  val consumedLastRelFireReg = RegInit(false.B)
  val consumedLastRelIdxReg = RegInit(0.U(params.putBits.W))
  val consumedLastRelDataReg = RegInit(0.U(params.inner.bundle.dataBits.W))
  val consumedLastRelCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  val consumedLastRelCounterValidReg = RegInit(false.B)
  when (sourceD.io.relPop.valid && sourceD.io.relPop.ready && !consumedRelFireReg) {
    consumedRelFireReg := true.B
    consumedRelIdxReg := sourceD.io.relPop.bits.index
    consumedRelDataReg := sourceD.io.relBeat.data
    consumedRelCounterReg := sourceD.io.relBeat.counter
    consumedRelCounterValidReg := sourceD.io.relBeat.counterValid
  }
  when (sourceD.io.relPop.valid && sourceD.io.relPop.ready && sourceD.io.relPop.bits.last) {
    consumedLastRelFireReg := true.B
    consumedLastRelIdxReg := sourceD.io.relPop.bits.index
    consumedLastRelDataReg := sourceD.io.relBeat.data
    consumedLastRelCounterReg := sourceD.io.relBeat.counter
    consumedLastRelCounterValidReg := sourceD.io.relBeat.counterValid
  }

  val feedbackBeat0ValidReg = RegInit(false.B)
  val feedbackBeat1ValidReg = RegInit(false.B)
  val feedbackCounterValidReg = RegInit(false.B)
  val feedbackBeat0DataReg = RegInit(0.U((params.inner.manager.beatBytes * 8).W))
  val feedbackBeat1DataReg = RegInit(0.U((params.inner.manager.beatBytes * 8).W))
  val feedbackCounterDataReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  val dataWriteFire = sourceD.io.bsWadr.valid && sourceD.io.bsWadr.ready
  when (dataWriteFire) {
    when (sourceD.io.bsWadr.bits.beat === 0.U) {
      feedbackBeat0ValidReg := true.B
      feedbackBeat0DataReg := sourceD.io.bsWdat.data
    } .otherwise {
      feedbackBeat1ValidReg := true.B
      feedbackBeat1DataReg := sourceD.io.bsWdat.data
    }
  }
  val counterWriteFire = sourceD.io.counterWrite.valid && sourceD.io.counterWrite.ready
  when (counterWriteFire) {
    feedbackCounterValidReg := true.B
    feedbackCounterDataReg := sourceD.io.counterWrite.bits.counter
  }

  sourceD.io.bsRdat.data := Mux(sourceD.io.bsRadr.bits.beat === 0.U,
    Mux(feedbackBeat0ValidReg, feedbackBeat0DataReg, io.initialBeat0),
    Mux(feedbackBeat1ValidReg, feedbackBeat1DataReg, io.initialBeat1))
  sourceD.io.ctrRdat := Mux(feedbackCounterValidReg, feedbackCounterDataReg, io.initialCounter)

  io.sinkReqValid := sink.io.req.valid
  io.sinkReqPut := sink.io.req.bits.put
  io.consumedRelFire := consumedRelFireReg
  io.consumedRelIdx := consumedRelIdxReg
  io.consumedRelData := consumedRelDataReg
  io.consumedRelCounter := consumedRelCounterReg
  io.consumedRelCounterValid := consumedRelCounterValidReg
  io.consumedLastRelFire := consumedLastRelFireReg
  io.consumedLastRelIdx := consumedLastRelIdxReg
  io.consumedLastRelData := consumedLastRelDataReg
  io.consumedLastRelCounter := consumedLastRelCounterReg
  io.consumedLastRelCounterValid := consumedLastRelCounterValidReg
  io.dataWriteFire := dataWriteFire
  io.dataWriteBeat := sourceD.io.bsWadr.bits.beat
  io.dataWriteData := sourceD.io.bsWdat.data
  io.counterWriteFire := counterWriteFire
  io.counterWriteData := sourceD.io.counterWrite.bits.counter
  io.feedbackBeat0Valid := feedbackBeat0ValidReg
  io.feedbackBeat1Valid := feedbackBeat1ValidReg
  io.feedbackCounterValid := feedbackCounterValidReg
  io.feedbackBeat0Data := feedbackBeat0DataReg
  io.feedbackBeat1Data := feedbackBeat1DataReg
  io.feedbackCounterData := feedbackCounterDataReg
}

class SinkCSourceCSnapshotClosureHarness(params: InclusiveCacheParameters) extends Module {
  val io = IO(new Bundle {
    val sinkC = Flipped(Decoupled(new TLBundleC(params.inner.bundle)))
    val sourceCReq = Flipped(Decoupled(new SourceCRequest(params)))
    val sourceCOut = Decoupled(new TLBundleC(params.outer.bundle))
    val sourceAReq = Flipped(Decoupled(new SourceARequest(params)))
    val sourceAOut = Decoupled(new TLBundleA(params.outer.bundle))
    val committedData = Input(UInt((params.outer.manager.beatBytes * 8).W))
    val committedCounter = Input(UInt(params.outer.bundle.dataBits.W))
    val evictSafe = Input(Bool())
    val snapshotIdx = Input(UInt(log2Ceil(params.mshrs).W))
    val sinkReqPut = Output(UInt(params.putBits.W))
    val sinkAcceptedBeat0Valid = Output(Bool())
    val sinkAcceptedBeat0Data = Output(UInt(params.inner.bundle.dataBits.W))
    val sinkAcceptedBeat1Valid = Output(Bool())
    val sinkAcceptedBeat1Data = Output(UInt(params.inner.bundle.dataBits.W))
    val sinkAcceptedCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val sinkPayloadCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val sinkPayloadCounterValid = Output(Bool())
    val sinkPayloadData = Output(UInt(params.inner.bundle.dataBits.W))
    val snapshotData = Output(UInt(params.outer.bundle.dataBits.W))
    val snapshotValid = Output(Bool())
    val snapshotPop = Output(Bool())
    val ctrReadValid = Output(Bool())
    val ctrReadFire = Output(Bool())
    val bsReadValid = Output(Bool())
    val bsReadFire = Output(Bool())
    val currentBeat = Output(UInt(params.outerBeatBits.W))
    val s2Beat = Output(UInt(params.outerBeatBits.W))
    val s3Beat = Output(UInt(params.outerBeatBits.W))
    val s2Valid = Output(Bool())
    val s3Valid = Output(Bool())
    val sourceCFreezeFire = Output(Bool())
    val sourceCFreezeCommittedData = Output(UInt((params.outer.manager.beatBytes * 8).W))
    val sourceCFreezeCommittedCounter = Output(UInt(params.outer.bundle.dataBits.W))
  })

  val sink = Module(new SinkC(params))
  val sourceC = Module(new SourceC(params))
  val sourceA = Module(new SourceA(params))

  sink.io.c <> io.sinkC
  sink.io.req.ready := true.B
  sink.io.way := 1.U
  sink.io.bs_adr.ready := true.B
  sink.io.counter_write.ready := true.B
  sink.io.rel_pop.valid := false.B
  sink.io.rel_pop.bits.index := 0.U
  sink.io.rel_pop.bits.last := false.B

  io.sourceCReq <> sourceC.io.req
  io.sourceCOut <> sourceC.io.c
  io.sourceAReq <> sourceA.io.req
  io.sourceAOut <> sourceA.io.a
  sourceA.io.cus_base_address := 0.U

  sourceC.io.bs_adr.ready := true.B
  sourceC.io.bs_dat.data := io.committedData
  sourceC.io.ctr_radr.ready := true.B
  sourceC.io.ctr_rdat := io.committedCounter
  sourceC.io.evict_safe := io.evictSafe
  sourceC.io.ctr_snapshot_idx := io.snapshotIdx
  sourceC.io.ctr_snapshot_pop := sourceA.io.ctr_snapshot_pop

  sourceA.io.ctr_snapshot_data := sourceC.io.ctr_snapshot_data
  sourceA.io.ctr_snapshot_valid := sourceC.io.ctr_snapshot_valid

  val sinkAcceptedBeat0ValidReg = RegInit(false.B)
  val sinkAcceptedBeat0DataReg = RegInit(0.U(params.inner.bundle.dataBits.W))
  val sinkAcceptedBeat1ValidReg = RegInit(false.B)
  val sinkAcceptedBeat1DataReg = RegInit(0.U(params.inner.bundle.dataBits.W))
  val sinkAcceptedCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  when (sink.io.c.valid && sink.io.c.ready && sink.io.c.bits.opcode === ReleaseData) {
    when (!sinkAcceptedBeat0ValidReg) {
      sinkAcceptedBeat0ValidReg := true.B
      sinkAcceptedBeat0DataReg := sink.io.c.bits.data
      sink.io.c.bits.user.lift(freechips.rocketchip.rocket.CacheCryptoWritebackMeta).foreach { u =>
        sinkAcceptedCounterReg := u.counter
      }
    } .elsewhen (!sinkAcceptedBeat1ValidReg) {
      sinkAcceptedBeat1ValidReg := true.B
      sinkAcceptedBeat1DataReg := sink.io.c.bits.data
    }
  }

  val sourceCFreezeFireReg = RegInit(false.B)
  val sourceCFreezeCommittedDataReg = RegInit(0.U((params.outer.manager.beatBytes * 8).W))
  val sourceCFreezeCommittedCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  when (sourceC.io.ctr_radr.fire && sourceC.io.bs_adr.fire && !sourceCFreezeFireReg) {
    sourceCFreezeFireReg := true.B
    sourceCFreezeCommittedDataReg := io.committedData
    sourceCFreezeCommittedCounterReg := io.committedCounter
  }

  io.sinkReqPut := sink.io.req.bits.put
  io.sinkAcceptedBeat0Valid := sinkAcceptedBeat0ValidReg
  io.sinkAcceptedBeat0Data := sinkAcceptedBeat0DataReg
  io.sinkAcceptedBeat1Valid := sinkAcceptedBeat1ValidReg
  io.sinkAcceptedBeat1Data := sinkAcceptedBeat1DataReg
  io.sinkAcceptedCounter := sinkAcceptedCounterReg
  io.sinkPayloadCounter := sink.io.rel_beat.counter
  io.sinkPayloadCounterValid := sink.io.rel_beat.counterValid
  io.sinkPayloadData := sink.io.rel_beat.data
  io.snapshotData := sourceC.io.ctr_snapshot_data
  io.snapshotValid := sourceC.io.ctr_snapshot_valid
  io.snapshotPop := sourceA.io.ctr_snapshot_pop
  io.ctrReadValid := sourceC.io.ctr_radr.valid
  io.ctrReadFire := sourceC.io.ctr_radr.fire
  io.bsReadValid := sourceC.io.bs_adr.valid
  io.bsReadFire := sourceC.io.bs_adr.fire
  io.currentBeat := 0.U
  io.s2Beat := 0.U
  io.s3Beat := 0.U
  io.s2Valid := false.B
  io.s3Valid := false.B
  io.sourceCFreezeFire := sourceCFreezeFireReg
  io.sourceCFreezeCommittedData := sourceCFreezeCommittedDataReg
  io.sourceCFreezeCommittedCounter := sourceCFreezeCommittedCounterReg
  BoringUtils.bore(sourceC.beat, Seq(io.currentBeat))
  BoringUtils.bore(sourceC.s2_beat, Seq(io.s2Beat))
  BoringUtils.bore(sourceC.s3_beat, Seq(io.s3Beat))
  BoringUtils.bore(sourceC.s2_valid, Seq(io.s2Valid))
  BoringUtils.bore(sourceC.s3_valid, Seq(io.s3Valid))
}

class SourceDSourceCSnapshotHarness(params: InclusiveCacheParameters) extends Module {
  val io = IO(new Bundle {
    val sinkC = Flipped(Decoupled(new TLBundleC(params.inner.bundle)))
    val sourceDReq = Flipped(Decoupled(new SourceDRequest(params)))
    val sourceCReq = Flipped(Decoupled(new SourceCRequest(params)))
    val sourceCOut = Decoupled(new TLBundleC(params.outer.bundle))
    val committedData = Input(UInt((params.outer.manager.beatBytes * 8).W))
    val committedCounter = Input(UInt(params.outer.bundle.dataBits.W))
    val snapshotIdx = Input(UInt(log2Ceil(params.mshrs).W))
    val sinkReqPut = Output(UInt(params.putBits.W))
    val consumedRelFire = Output(Bool())
    val consumedRelData = Output(UInt(params.inner.bundle.dataBits.W))
    val consumedRelCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val consumedRelCounterValid = Output(Bool())
    val consumedLastRelFire = Output(Bool())
    val consumedLastRelData = Output(UInt(params.inner.bundle.dataBits.W))
    val consumedLastRelCounter = Output(UInt(params.outer.bundle.dataBits.W))
    val consumedLastRelCounterValid = Output(Bool())
    val snapshotData = Output(UInt(params.outer.bundle.dataBits.W))
    val snapshotValid = Output(Bool())
    val sourceDEvictSafe = Output(Bool())
    val sourceDCounterBypassHit = Output(Bool())
    val sourceDCounterBypassData = Output(UInt(params.outer.bundle.dataBits.W))
    val sourceDS5Valid = Output(Bool())
    val sourceDS6Valid = Output(Bool())
    val sourceDS7Valid = Output(Bool())
    val ctrReadValid = Output(Bool())
    val ctrReadFire = Output(Bool())
    val bsReadValid = Output(Bool())
    val bsReadFire = Output(Bool())
    val currentBeat = Output(UInt(params.outerBeatBits.W))
    val s2Beat = Output(UInt(params.outerBeatBits.W))
    val s3Beat = Output(UInt(params.outerBeatBits.W))
    val s2Valid = Output(Bool())
    val s3Valid = Output(Bool())
    val sourceCFreezeFire = Output(Bool())
    val sourceCFreezeCommittedData = Output(UInt((params.outer.manager.beatBytes * 8).W))
    val sourceCFreezeCommittedCounter = Output(UInt(params.outer.bundle.dataBits.W))
  })

  val sink = Module(new SinkC(params))
  val sourceD = Module(new SourceDObservationHarness(params))
  val sourceC = Module(new SourceC(params))

  sink.io.c <> io.sinkC
  sink.io.req.ready := true.B
  sink.io.way := 1.U
  sink.io.bs_adr.ready := true.B
  sink.io.counter_write.ready := true.B

  sourceD.io.req <> io.sourceDReq
  sourceD.io.ctrRadr.ready := true.B
  sourceD.io.ctrRdat := io.committedCounter
  sourceD.io.d.ready := true.B
  sourceD.io.pbPop.ready := true.B
  sourceD.io.pbBeat.data := 0.U
  sourceD.io.pbBeat.mask := 0.U
  sourceD.io.pbBeat.corrupt := false.B
  sourceD.io.pbBeat.counter := 0.U
  sourceD.io.pbBeat.counterValid := false.B
  sourceD.io.bsRadr.ready := true.B
  sourceD.io.bsRdat.data := io.committedData
  sourceD.io.bsWadr.ready := true.B
  sourceD.io.counterWrite.ready := true.B

  sink.io.rel_pop.valid := sourceD.io.relPop.valid
  sink.io.rel_pop.bits := sourceD.io.relPop.bits
  sourceD.io.relPop.ready := sink.io.rel_pop.ready
  sourceD.io.relBeat := sink.io.rel_beat

  io.sourceCReq <> sourceC.io.req
  io.sourceCOut <> sourceC.io.c
  sourceC.io.bs_adr.ready := true.B
  sourceC.io.bs_dat.data := io.committedData
  sourceC.io.ctr_radr.ready := true.B
  sourceC.io.ctr_rdat := io.committedCounter
  sourceC.io.ctr_snapshot_idx := io.snapshotIdx
  sourceC.io.ctr_snapshot_pop := false.B
  sourceC.io.evict_safe := sourceD.io.evictSafe

  sourceD.io.evictReq <> sourceC.io.evict_req
  sourceD.io.counterGrantReq.set := 0.U
  sourceD.io.counterGrantReq.way := 0.U
  sourceD.io.grantReq.set := 0.U
  sourceD.io.grantReq.way := 0.U

  val consumedRelFireReg = RegInit(false.B)
  val consumedRelDataReg = RegInit(0.U(params.inner.bundle.dataBits.W))
  val consumedRelCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  val consumedRelCounterValidReg = RegInit(false.B)
  val consumedLastRelFireReg = RegInit(false.B)
  val consumedLastRelDataReg = RegInit(0.U(params.inner.bundle.dataBits.W))
  val consumedLastRelCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  val consumedLastRelCounterValidReg = RegInit(false.B)
  when (sourceD.io.relPop.valid && sourceD.io.relPop.ready && !consumedRelFireReg) {
    consumedRelFireReg := true.B
    consumedRelDataReg := sourceD.io.relBeat.data
    consumedRelCounterReg := sourceD.io.relBeat.counter
    consumedRelCounterValidReg := sourceD.io.relBeat.counterValid
  }
  when (sourceD.io.relPop.valid && sourceD.io.relPop.ready && sourceD.io.relPop.bits.last) {
    consumedLastRelFireReg := true.B
    consumedLastRelDataReg := sourceD.io.relBeat.data
    consumedLastRelCounterReg := sourceD.io.relBeat.counter
    consumedLastRelCounterValidReg := sourceD.io.relBeat.counterValid
  }

  val sourceCFreezeFireReg = RegInit(false.B)
  val sourceCFreezeCommittedDataReg = RegInit(0.U((params.outer.manager.beatBytes * 8).W))
  val sourceCFreezeCommittedCounterReg = RegInit(0.U(params.outer.bundle.dataBits.W))
  when (sourceC.io.ctr_radr.fire && sourceC.io.bs_adr.fire && !sourceCFreezeFireReg) {
    sourceCFreezeFireReg := true.B
    sourceCFreezeCommittedDataReg := io.committedData
    sourceCFreezeCommittedCounterReg := io.committedCounter
  }

  io.sinkReqPut := sink.io.req.bits.put
  io.consumedRelFire := consumedRelFireReg
  io.consumedRelData := consumedRelDataReg
  io.consumedRelCounter := consumedRelCounterReg
  io.consumedRelCounterValid := consumedRelCounterValidReg
  io.consumedLastRelFire := consumedLastRelFireReg
  io.consumedLastRelData := consumedLastRelDataReg
  io.consumedLastRelCounter := consumedLastRelCounterReg
  io.consumedLastRelCounterValid := consumedLastRelCounterValidReg
  io.snapshotData := sourceC.io.ctr_snapshot_data
  io.snapshotValid := sourceC.io.ctr_snapshot_valid
  io.sourceDEvictSafe := sourceD.io.evictSafe
  io.sourceDCounterBypassHit := sourceD.io.counterBypassHit
  io.sourceDCounterBypassData := sourceD.io.counterBypassData
  io.sourceDS5Valid := sourceD.io.s5Valid
  io.sourceDS6Valid := sourceD.io.s6Valid
  io.sourceDS7Valid := sourceD.io.s7Valid
  io.ctrReadValid := sourceC.io.ctr_radr.valid
  io.ctrReadFire := sourceC.io.ctr_radr.fire
  io.bsReadValid := sourceC.io.bs_adr.valid
  io.bsReadFire := sourceC.io.bs_adr.fire
  io.currentBeat := 0.U
  io.s2Beat := 0.U
  io.s3Beat := 0.U
  io.s2Valid := false.B
  io.s3Valid := false.B
  io.sourceCFreezeFire := sourceCFreezeFireReg
  io.sourceCFreezeCommittedData := sourceCFreezeCommittedDataReg
  io.sourceCFreezeCommittedCounter := sourceCFreezeCommittedCounterReg
  BoringUtils.bore(sourceC.beat, Seq(io.currentBeat))
  BoringUtils.bore(sourceC.s2_beat, Seq(io.s2Beat))
  BoringUtils.bore(sourceC.s3_beat, Seq(io.s3Beat))
  BoringUtils.bore(sourceC.s2_valid, Seq(io.s2Valid))
  BoringUtils.bore(sourceC.s3_valid, Seq(io.s3Valid))
}
