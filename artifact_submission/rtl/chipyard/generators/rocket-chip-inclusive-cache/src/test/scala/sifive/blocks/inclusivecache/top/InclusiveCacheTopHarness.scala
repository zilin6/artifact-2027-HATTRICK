package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.experimental.{SourceInfo, UnlocatableSourceInfo}
import chisel3.util.log2Ceil
import chisel3.util.experimental.BoringUtils
import freechips.rocketchip.diplomacy.{AddressSet, BindingScope, DisableMonitors, IdRange, InModuleBody, LazyModule, LazyModuleImp, RegionType, TransferSizes}
import freechips.rocketchip.rocket.{
  CacheCryptoCounterBitsKey,
  CacheCryptoRefillMeta,
  CacheCryptoRefillMetaField,
  CacheCryptoWritebackMeta,
  CacheCryptoWritebackMetaField
}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import sifive.blocks.inclusivecache._

object InclusiveCacheTopHarness {
  implicit val p: Parameters = Parameters.empty.alterPartial {
    case CacheCryptoCounterBitsKey => 64
  }

  val cache = CacheParameters(
    level = 2,
    ways = 8,
    sets = 1024,
    blockBytes = 64,
    beatBytes = 8,
    hintsSkipProbe = false)

  val micro = InclusiveCacheMicroParameters(
    writeBytes = 8,
    memCycles = 40,
    portFactor = 4,
    innerBuf = InclusiveCachePortParameters.none,
    outerBuf = InclusiveCachePortParameters.flowAE)

  val fastCache = CacheParameters(
    level = 2,
    ways = 8,
    sets = 8,
    blockBytes = 64,
    beatBytes = 8,
    hintsSkipProbe = false)

  val fastMicro = InclusiveCacheMicroParameters(
    writeBytes = 8,
    memCycles = 4,
    portFactor = 4,
    innerBuf = InclusiveCachePortParameters.none,
    outerBuf = InclusiveCachePortParameters.flowAE)
}

class InclusiveCacheTopHarness(
  cache: CacheParameters = InclusiveCacheTopHarness.cache,
  micro: InclusiveCacheMicroParameters = InclusiveCacheTopHarness.micro
)(implicit p: Parameters) extends LazyModule {
  implicit val sourceInfo: SourceInfo = UnlocatableSourceInfo

  val innerNode = TLClientNode(Seq(TLMasterPortParameters.v2(
    masters = Seq(TLMasterParameters.v2(
      name = "test-l1",
      sourceId = IdRange(0, 4),
      supports = TLSlaveToMasterTransferSizes(
        probe = TransferSizes(1, cache.blockBytes),
        arithmetic = TransferSizes(1, cache.beatBytes),
        logical = TransferSizes(1, cache.beatBytes),
        get = TransferSizes(1, cache.blockBytes),
        putFull = TransferSizes(1, cache.blockBytes),
        putPartial = TransferSizes(1, cache.blockBytes),
        hint = TransferSizes(1, cache.blockBytes)))),
    requestFields = Seq(CacheCryptoWritebackMetaField(counterBits = p(CacheCryptoCounterBitsKey))),
    responseKeys = Seq(CacheCryptoRefillMeta))))
  val outerNode = TLManagerNode(Seq(TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v2(
      address = Seq(AddressSet(0x0, 0xffffffffL)),
      regionType = RegionType.UNCACHED,
      supports = TLMasterToSlaveTransferSizes(
        acquireT = TransferSizes(cache.blockBytes, cache.blockBytes),
        acquireB = TransferSizes(cache.blockBytes, cache.blockBytes),
        arithmetic = TransferSizes.none,
        logical = TransferSizes.none,
        get = TransferSizes(8, cache.blockBytes),
        putFull = TransferSizes(8, cache.blockBytes),
        putPartial = TransferSizes.none,
        hint = TransferSizes.none))),
    beatBytes = cache.beatBytes,
    endSinkId = InclusiveCacheParameters.all_mshrs(cache, micro),
    minLatency = 1,
    responseFields = Seq(CacheCryptoRefillMetaField(counterBits = p(CacheCryptoCounterBitsKey))),
    requestKeys = Seq(CacheCryptoWritebackMeta))))
  val dut = LazyModule(new InclusiveCache(cache, micro, None))

  DisableMonitors { implicit p => dut.node :=* innerNode }
  DisableMonitors { implicit p => outerNode :=* dut.node }

  val inner_tl = InModuleBody { innerNode.makeIOs() }
  val outer_tl = InModuleBody { outerNode.makeIOs() }

  lazy val module = new InclusiveCacheTopHarnessModule(this)
}

class InclusiveCacheTopHarnessModule(outer: InclusiveCacheTopHarness) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val cus_base_address = Input(UInt(64.W))
    val cus_base_wen = Input(Bool())
  })

  val rawReloadObsSeen = WireDefault(false.B)
  val rawReloadObsCount = WireDefault(0.U(8.W))
  val rawReloadObsNeedPb = WireDefault(false.B)
  val rawReloadObsNeedR = WireDefault(false.B)
  val rawReloadObsCtrPayloadPath = WireDefault(false.B)
  val rawReloadObsCtrNeedsCommitted = WireDefault(false.B)
  val rawReloadObsData = WireDefault(0.U(64.W))
  val rawReloadObsCounter = WireDefault(0.U(64.W))

  outer.dut.module.io.cus_base_address := io.cus_base_address
  outer.dut.module.io.cus_base_wen := io.cus_base_wen
}

class InclusiveCacheTopHarnessWrapper(
  cache: CacheParameters = InclusiveCacheTopHarness.cache,
  micro: InclusiveCacheMicroParameters = InclusiveCacheTopHarness.micro
)(implicit p: Parameters) extends Module {
  val root = new InclusiveCacheTopHarness(cache, micro)
  val dut = Module(LazyModule(root).module)

  val io = IO(new Bundle {
    val cus_base_address = Input(UInt(64.W))
    val cus_base_wen = Input(Bool())
    val inner = Flipped(chiselTypeOf(root.inner_tl.head))
    val outer = chiselTypeOf(root.outer_tl.head)
  })

  dut.io.cus_base_address := io.cus_base_address
  dut.io.cus_base_wen := io.cus_base_wen
  io.inner <> root.inner_tl.head
  io.outer <> root.outer_tl.head
}
