package sifive.blocks.inclusivecache.top

import boom.v3.common.NullMicroOp
import boom.v3.lsu.BoomNonBlockingDCache
import chisel3._
import chisel3.experimental.{SourceInfo, UnlocatableSourceInfo}
import chisel3.util.experimental.BoringUtils
import freechips.rocketchip.diplomacy.{AddressSet, DisableMonitors, IdRange, InModuleBody, LazyModule, LazyModuleImp, RegionType, TransferSizes, ValName}
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, CacheCryptoRefillMetaField, CacheCryptoWritebackMeta, CacheCryptoWritebackMetaField, M_XRD, M_XWR}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import sifive.blocks.inclusivecache.{CacheParameters, InclusiveCache, InclusiveCacheMicroParameters, InclusiveCacheParameters, InclusiveCacheTestBoringNames}

object InclusiveCacheTopBoomDualHarness {
  implicit val p: Parameters = BoomDCacheRealDriverHarness.p
  val cache: CacheParameters = InclusiveCacheTopHarness.fastCache
  val micro: InclusiveCacheMicroParameters = InclusiveCacheTopHarness.fastMicro
}

class InclusiveCacheTopBoomDualHarness(
  val topParams: Parameters,
  val cache: CacheParameters,
  val micro: InclusiveCacheMicroParameters,
  val dcacheWays: Int,
  val dcacheSets: Int) extends LazyModule()(topParams) {
  implicit val sourceInfo: SourceInfo = UnlocatableSourceInfo

  val rawClientNode = TLClientNode(Seq(TLMasterPortParameters.v2(
    masters = Seq(TLMasterParameters.v2(
      name = "test-l1-raw",
      sourceId = IdRange(0, 4),
      supports = TLSlaveToMasterTransferSizes(
        probe = TransferSizes(1, cache.blockBytes),
        arithmetic = TransferSizes(1, cache.beatBytes),
        logical = TransferSizes(1, cache.beatBytes),
        get = TransferSizes(1, cache.blockBytes),
        putFull = TransferSizes(1, cache.blockBytes),
        putPartial = TransferSizes(1, cache.blockBytes),
        hint = TransferSizes(1, cache.blockBytes)))),
    requestFields = Seq(CacheCryptoWritebackMetaField(counterBits = topParams(freechips.rocketchip.rocket.CacheCryptoCounterBitsKey))),
    responseKeys = Seq(CacheCryptoRefillMeta))))

  private val visibilityNode = TLEphemeralNode()(ValName("boom_dual_tile_master"))
  val boomParams: Parameters = BoomDCacheRealDriverHarness.boomP(visibilityNode, dcacheWays = dcacheWays, dcacheSets = dcacheSets)
  val dcache = LazyModule(new BoomNonBlockingDCache(0)(boomParams))
  val inclusive = LazyModule(new InclusiveCache(cache, micro, None)(topParams))
  val tap = LazyModule(new TLCTestTap)
  val xbar = LazyModule(new TLXbar())

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
    responseFields = Seq(CacheCryptoRefillMetaField(counterBits = topParams(freechips.rocketchip.rocket.CacheCryptoCounterBitsKey))),
    requestKeys = Seq(CacheCryptoWritebackMeta))))

  DisableMonitors { implicit p =>
    outerNode :=* inclusive.node :=* xbar.node
    xbar.node := rawClientNode
    xbar.node :=* tap.node :=* visibilityNode :=* dcache.node
  }

  val raw_tl = InModuleBody { rawClientNode.makeIOs() }
  val outer_tl = InModuleBody { outerNode.makeIOs() }

  lazy val module = new InclusiveCacheTopBoomDualHarnessModule(this)
}

class InclusiveCacheTopBoomDualHarnessModule(lm: InclusiveCacheTopBoomDualHarness) extends LazyModuleImp(lm) {
  val io = IO(new Bundle {
    val req_valid = Input(Bool())
    val req_addr = Input(UInt(64.W))
    val req_data = Input(UInt(64.W))
    val req_cmd = Input(UInt(4.W))
    val req_mem_size = Input(UInt(2.W))
    val req_mem_signed = Input(Bool())
    val req_ready = Output(Bool())
    val resp_valid = Output(Bool())
    val resp_data = Output(UInt(64.W))
    val resp_clear = Input(Bool())
    val resp_seen = Output(Bool())
    val resp_last_data = Output(UInt(64.W))
    val nack_valid = Output(Bool())
    val c_valid = Output(Bool())
    val c_ready = Output(Bool())
    val c_opcode = Output(UInt(3.W))
    val c_address = Output(UInt(64.W))
    val c_data = Output(UInt(64.W))
    val c_counter = Output(UInt(64.W))
    val c_crypto = Output(Bool())
    val stall_boom_inner_d = Input(Bool())
    val stall_sourceD_retire_bs_wadr = Input(Bool())
    val stall_sourceD_retire_counter_write = Input(Bool())
    val boom_d_valid = Output(Bool())
    val boom_d_ready = Output(Bool())
    val boom_d_opcode = Output(UInt(3.W))
    val boom_d_source = Output(UInt(8.W))
    val boom_d_sink = Output(UInt(4.W))
    val boom_d_data = Output(UInt(64.W))
    val boom_d_counter = Output(UInt(64.W))
    val boom_d_crypto = Output(Bool())
    val raw_d_clear = Input(Bool())
    val raw_d_seen = Output(Bool())
    val raw_d_count = Output(UInt(8.W))
    val raw_d_opcode = Output(UInt(3.W))
    val raw_d_sink = Output(UInt(4.W))
    val raw_d_data = Output(UInt(64.W))
    val raw_d_counter = Output(UInt(64.W))
    val raw_reload_obs_seen = Output(Bool())
    val raw_reload_obs_count = Output(UInt(8.W))
    val raw_reload_obs_need_pb = Output(Bool())
    val raw_reload_obs_need_r = Output(Bool())
    val raw_reload_obs_ctr_payload_path = Output(Bool())
    val raw_reload_obs_ctr_needs_committed = Output(Bool())
    val raw_reload_obs_data = Output(UInt(64.W))
    val raw_reload_obs_counter = Output(UInt(64.W))
    val dataKey = Input(UInt(128.W))
    val cacheCryptoLoadEnableValue = Input(Bool())
    val cacheCryptoStoreEnableValue = Input(Bool())
    val cacheCryptoEnableWen = Input(Bool())
    val cacheCryptoCounterBaseValue = Input(UInt(64.W))
    val cacheCryptoCounterBaseWen = Input(Bool())
    val cus_base_address = Input(UInt(64.W))
    val cus_base_wen = Input(Bool())
    val log = Input(Bool())
  })

  val lsu = lm.dcache.module.io.lsu
  val tap = lm.tap.tapIO
  tap.stall_boom_inner_d := io.stall_boom_inner_d
  val raw = lm.raw_tl.head
  val rawReloadObsSeen = WireDefault(false.B)
  val rawReloadObsCount = WireDefault(0.U(8.W))
  val rawReloadObsNeedPb = WireDefault(false.B)
  val rawReloadObsNeedR = WireDefault(false.B)
  val rawReloadObsCtrPayloadPath = WireDefault(false.B)
  val rawReloadObsCtrNeedsCommitted = WireDefault(false.B)
  val rawReloadObsData = WireDefault(0.U(64.W))
  val rawReloadObsCounter = WireDefault(0.U(64.W))
  val reqBits = Wire(Vec(lsu.req.bits.length, chiselTypeOf(lsu.req.bits.head)))
  reqBits := 0.U.asTypeOf(reqBits)
  reqBits(0).valid := io.req_valid
  reqBits(0).bits.uop := NullMicroOp()(lm.boomParams)
  reqBits(0).bits.uop.mem_cmd := io.req_cmd
  reqBits(0).bits.uop.mem_size := io.req_mem_size
  reqBits(0).bits.uop.mem_signed := io.req_mem_signed
  reqBits(0).bits.uop.uses_ldq := io.req_cmd === M_XRD
  reqBits(0).bits.uop.uses_stq := io.req_cmd === M_XWR
  reqBits(0).bits.addr := io.req_addr
  reqBits(0).bits.data := io.req_data
  reqBits(0).bits.is_hella := false.B

  lsu.req.valid := io.req_valid
  lsu.req.bits := reqBits
  io.req_ready := lsu.req.ready
  lsu.s1_kill := VecInit(Seq.fill(lsu.s1_kill.length)(false.B))
  lsu.brupdate.b1.resolve_mask := 0.U
  lsu.brupdate.b1.mispredict_mask := 0.U
  lsu.brupdate.b2.uop := NullMicroOp()(lm.boomParams)
  lsu.brupdate.b2.valid := false.B
  lsu.brupdate.b2.mispredict := false.B
  lsu.brupdate.b2.taken := false.B
  lsu.brupdate.b2.cfi_type := 0.U
  lsu.brupdate.b2.pc_sel := 0.U
  lsu.brupdate.b2.jalr_target := 0.U
  lsu.brupdate.b2.target_offset := 0.S(1.W)
  lsu.exception := false.B
  lsu.rob_pnr_idx := 0.U
  lsu.rob_head_idx := 0.U
  lsu.release.ready := true.B
  lsu.force_order := false.B

  io.resp_valid := lsu.resp.map(_.valid).reduce(_ || _)
  io.resp_data := Mux(lsu.resp.head.valid, lsu.resp.head.bits.data, 0.U)
  val respSeenReg = RegInit(false.B)
  val respLastDataReg = RegInit(0.U(64.W))
  when (io.resp_clear) {
    respSeenReg := false.B
    respLastDataReg := 0.U
  } .elsewhen (lsu.resp.head.valid) {
    respSeenReg := true.B
    respLastDataReg := lsu.resp.head.bits.data
  }
  io.resp_seen := respSeenReg
  io.resp_last_data := respLastDataReg
  io.nack_valid := lsu.nack.map(_.valid).reduce(_ || _)

  io.c_valid := tap.c_valid
  io.c_ready := tap.c_ready
  io.c_opcode := tap.c_opcode
  io.c_address := tap.c_address
  io.c_data := tap.c_data
  io.c_counter := tap.c_counter
  io.c_crypto := tap.c_crypto
  io.boom_d_valid := tap.d_valid
  io.boom_d_ready := tap.d_ready
  io.boom_d_opcode := tap.d_opcode
  io.boom_d_source := tap.d_source
  io.boom_d_sink := tap.d_sink
  io.boom_d_data := tap.d_data
  io.boom_d_counter := tap.d_counter
  io.boom_d_crypto := tap.d_crypto

  val rawDSeenReg = RegInit(false.B)
  val rawDCountReg = RegInit(0.U(8.W))
  val rawDOpcodeReg = RegInit(0.U(3.W))
  val rawDSinkReg = RegInit(0.U(4.W))
  val rawDDataReg = RegInit(0.U(64.W))
  val rawDCounterReg = RegInit(0.U(64.W))
  when (io.raw_d_clear) {
    rawDSeenReg := false.B
    rawDCountReg := 0.U
    rawDOpcodeReg := 0.U
    rawDSinkReg := 0.U
    rawDDataReg := 0.U
    rawDCounterReg := 0.U
  } .elsewhen (raw.d.valid && raw.d.ready) {
    rawDSeenReg := true.B
    rawDCountReg := rawDCountReg + 1.U
    rawDOpcodeReg := raw.d.bits.opcode
    rawDSinkReg := raw.d.bits.sink
    rawDDataReg := raw.d.bits.data
    rawDCounterReg := raw.d.bits.user.lift(CacheCryptoRefillMeta).map(_.counter).getOrElse(0.U)
  }
  io.raw_d_seen := rawDSeenReg
  io.raw_d_count := rawDCountReg
  io.raw_d_opcode := rawDOpcodeReg
  io.raw_d_sink := rawDSinkReg
  io.raw_d_data := rawDDataReg
  io.raw_d_counter := rawDCounterReg
  io.raw_reload_obs_seen := rawReloadObsSeen
  io.raw_reload_obs_count := rawReloadObsCount
  io.raw_reload_obs_need_pb := rawReloadObsNeedPb
  io.raw_reload_obs_need_r := rawReloadObsNeedR
  io.raw_reload_obs_ctr_payload_path := rawReloadObsCtrPayloadPath
  io.raw_reload_obs_ctr_needs_committed := rawReloadObsCtrNeedsCommitted
  io.raw_reload_obs_data := rawReloadObsData
  io.raw_reload_obs_counter := rawReloadObsCounter

  lm.dcache.module.io.dataKey := io.dataKey
  lm.dcache.module.io.cacheCryptoLoadEnableValue := io.cacheCryptoLoadEnableValue
  lm.dcache.module.io.cacheCryptoStoreEnableValue := io.cacheCryptoStoreEnableValue
  lm.dcache.module.io.cacheCryptoEnableWen := io.cacheCryptoEnableWen
  lm.dcache.module.io.cacheCryptoCounterBaseValue := io.cacheCryptoCounterBaseValue
  lm.dcache.module.io.cacheCryptoCounterBaseWen := io.cacheCryptoCounterBaseWen
  lm.dcache.module.io.log := io.log

  lm.inclusive.module.io.cus_base_address := io.cus_base_address
  lm.inclusive.module.io.cus_base_wen := io.cus_base_wen
}

class InclusiveCacheTopBoomDualHarnessWrapper(
  cache: CacheParameters = InclusiveCacheTopBoomDualHarness.cache,
  micro: InclusiveCacheMicroParameters = InclusiveCacheTopBoomDualHarness.micro,
  dcacheWays: Int = 1,
  dcacheSets: Int = 8) extends Module {
  implicit val p: Parameters = InclusiveCacheTopBoomDualHarness.p
  val root = new InclusiveCacheTopBoomDualHarness(p, cache, micro, dcacheWays, dcacheSets)
  val dut = Module(LazyModule(root).module)

  val io = IO(new Bundle {
    val req_valid = Input(Bool())
    val req_addr = Input(UInt(64.W))
    val req_data = Input(UInt(64.W))
    val req_cmd = Input(UInt(4.W))
    val req_mem_size = Input(UInt(2.W))
    val req_mem_signed = Input(Bool())
    val req_ready = Output(Bool())
    val resp_valid = Output(Bool())
    val resp_data = Output(UInt(64.W))
    val resp_clear = Input(Bool())
    val resp_seen = Output(Bool())
    val resp_last_data = Output(UInt(64.W))
    val nack_valid = Output(Bool())
    val raw = Flipped(chiselTypeOf(root.raw_tl.head))
    val outer = chiselTypeOf(root.outer_tl.head)
    val c_valid = Output(Bool())
    val c_ready = Output(Bool())
    val c_opcode = Output(UInt(3.W))
    val c_address = Output(UInt(64.W))
    val c_data = Output(UInt(64.W))
    val c_counter = Output(UInt(64.W))
    val c_crypto = Output(Bool())
    val stall_boom_inner_d = Input(Bool())
    val stall_sourceD_retire_bs_wadr = Input(Bool())
    val stall_sourceD_retire_counter_write = Input(Bool())
    val boom_d_valid = Output(Bool())
    val boom_d_ready = Output(Bool())
    val boom_d_opcode = Output(UInt(3.W))
    val boom_d_source = Output(UInt(8.W))
    val boom_d_sink = Output(UInt(4.W))
    val boom_d_data = Output(UInt(64.W))
    val boom_d_counter = Output(UInt(64.W))
    val boom_d_crypto = Output(Bool())
    val raw_d_clear = Input(Bool())
    val raw_d_seen = Output(Bool())
    val raw_d_count = Output(UInt(8.W))
    val raw_d_opcode = Output(UInt(3.W))
    val raw_d_sink = Output(UInt(4.W))
    val raw_d_data = Output(UInt(64.W))
    val raw_d_counter = Output(UInt(64.W))
    val raw_reload_obs_seen = Output(Bool())
    val raw_reload_obs_count = Output(UInt(8.W))
    val raw_reload_obs_need_pb = Output(Bool())
    val raw_reload_obs_need_r = Output(Bool())
    val raw_reload_obs_ctr_payload_path = Output(Bool())
    val raw_reload_obs_ctr_needs_committed = Output(Bool())
    val raw_reload_obs_data = Output(UInt(64.W))
    val raw_reload_obs_counter = Output(UInt(64.W))
    val dataKey = Input(UInt(128.W))
    val cacheCryptoLoadEnableValue = Input(Bool())
    val cacheCryptoStoreEnableValue = Input(Bool())
    val cacheCryptoEnableWen = Input(Bool())
    val cacheCryptoCounterBaseValue = Input(UInt(64.W))
    val cacheCryptoCounterBaseWen = Input(Bool())
    val cus_base_address = Input(UInt(64.W))
    val cus_base_wen = Input(Bool())
    val log = Input(Bool())
  })

  dut.io.req_valid := io.req_valid
  dut.io.req_addr := io.req_addr
  dut.io.req_data := io.req_data
  dut.io.req_cmd := io.req_cmd
  dut.io.req_mem_size := io.req_mem_size
  dut.io.req_mem_signed := io.req_mem_signed
  io.req_ready := dut.io.req_ready
  io.resp_valid := dut.io.resp_valid
  io.resp_data := dut.io.resp_data
  dut.io.resp_clear := io.resp_clear
  io.resp_seen := dut.io.resp_seen
  io.resp_last_data := dut.io.resp_last_data
  io.nack_valid := dut.io.nack_valid
  io.raw <> root.raw_tl.head
  io.outer <> root.outer_tl.head
  io.c_valid := dut.io.c_valid
  io.c_ready := dut.io.c_ready
  io.c_opcode := dut.io.c_opcode
  io.c_address := dut.io.c_address
  io.c_data := dut.io.c_data
  io.c_counter := dut.io.c_counter
  io.c_crypto := dut.io.c_crypto
  dut.io.stall_boom_inner_d := io.stall_boom_inner_d
  dut.io.stall_sourceD_retire_bs_wadr := io.stall_sourceD_retire_bs_wadr
  dut.io.stall_sourceD_retire_counter_write := io.stall_sourceD_retire_counter_write
  io.boom_d_valid := dut.io.boom_d_valid
  io.boom_d_ready := dut.io.boom_d_ready
  io.boom_d_opcode := dut.io.boom_d_opcode
  io.boom_d_source := dut.io.boom_d_source
  io.boom_d_sink := dut.io.boom_d_sink
  io.boom_d_data := dut.io.boom_d_data
  io.boom_d_counter := dut.io.boom_d_counter
  io.boom_d_crypto := dut.io.boom_d_crypto
  dut.io.raw_d_clear := io.raw_d_clear
  io.raw_d_seen := dut.io.raw_d_seen
  io.raw_d_count := dut.io.raw_d_count
  io.raw_d_opcode := dut.io.raw_d_opcode
  io.raw_d_sink := dut.io.raw_d_sink
  io.raw_d_data := dut.io.raw_d_data
  io.raw_d_counter := dut.io.raw_d_counter
  io.raw_reload_obs_seen := dut.io.raw_reload_obs_seen
  io.raw_reload_obs_count := dut.io.raw_reload_obs_count
  io.raw_reload_obs_need_pb := dut.io.raw_reload_obs_need_pb
  io.raw_reload_obs_need_r := dut.io.raw_reload_obs_need_r
  io.raw_reload_obs_ctr_payload_path := dut.io.raw_reload_obs_ctr_payload_path
  io.raw_reload_obs_ctr_needs_committed := dut.io.raw_reload_obs_ctr_needs_committed
  io.raw_reload_obs_data := dut.io.raw_reload_obs_data
  io.raw_reload_obs_counter := dut.io.raw_reload_obs_counter
  dut.io.dataKey := io.dataKey
  dut.io.cacheCryptoLoadEnableValue := io.cacheCryptoLoadEnableValue
  dut.io.cacheCryptoStoreEnableValue := io.cacheCryptoStoreEnableValue
  dut.io.cacheCryptoEnableWen := io.cacheCryptoEnableWen
  dut.io.cacheCryptoCounterBaseValue := io.cacheCryptoCounterBaseValue
  dut.io.cacheCryptoCounterBaseWen := io.cacheCryptoCounterBaseWen
  dut.io.cus_base_address := io.cus_base_address
  dut.io.cus_base_wen := io.cus_base_wen
  dut.io.log := io.log
}
