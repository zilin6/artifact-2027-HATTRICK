package sifive.blocks.inclusivecache.top

import boom.v3.lsu.BoomNonBlockingDCache
import boom.v3.common.NullMicroOp
import chisel3._
import chisel3.experimental.{SourceInfo, UnlocatableSourceInfo}
import chisel3.util.experimental.BoringUtils
import freechips.rocketchip.diplomacy.{AddressSet, DisableMonitors, InModuleBody, LazyModule, LazyModuleImp, RegionType, TransferSizes, ValName}
import freechips.rocketchip.rocket.M_XRD
import freechips.rocketchip.rocket.M_XWR
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, CacheCryptoRefillMetaField, CacheCryptoWritebackMeta, DCacheParams}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import sifive.blocks.inclusivecache.{CacheParameters, InclusiveCache, InclusiveCacheMicroParameters, InclusiveCacheParameters, InclusiveCacheTestBoringNames}

class TLCTestTap(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode()
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val stall_boom_inner_d = Input(Bool())
      val c_valid = Output(Bool())
      val c_ready = Output(Bool())
      val c_opcode = Output(UInt(3.W))
      val c_address = Output(UInt(64.W))
      val c_data = Output(UInt(64.W))
      val c_counter = Output(UInt(64.W))
      val c_crypto = Output(Bool())
      val d_valid = Output(Bool())
      val d_ready = Output(Bool())
      val d_opcode = Output(UInt(3.W))
      val d_source = Output(UInt(8.W))
      val d_sink = Output(UInt(4.W))
      val d_data = Output(UInt(64.W))
      val d_counter = Output(UInt(64.W))
      val d_crypto = Output(Bool())
    })

    (node.in zip node.out).foreach { case ((in, _), (out, _)) =>
      out.a <> in.a
      in.b <> out.b
      out.c <> in.c
      out.e <> in.e

      in.d.valid := out.d.valid
      in.d.bits := out.d.bits
      out.d.ready := in.d.ready && !io.stall_boom_inner_d

      io.c_valid := in.c.valid
      io.c_ready := in.c.ready
      io.c_opcode := in.c.bits.opcode
      io.c_address := in.c.bits.address
      io.c_data := in.c.bits.data
      io.c_counter := in.c.bits.user.lift(CacheCryptoWritebackMeta).map(_.counter).getOrElse(0.U)
      io.c_crypto := in.c.bits.user.lift(CacheCryptoWritebackMeta).map(_.cryptoLine).getOrElse(false.B)
      io.d_valid := in.d.valid
      io.d_ready := out.d.ready
      io.d_opcode := in.d.bits.opcode
      io.d_source := in.d.bits.source
      io.d_sink := in.d.bits.sink
      io.d_data := in.d.bits.data
      io.d_counter := in.d.bits.user.lift(CacheCryptoRefillMeta).map(_.counter).getOrElse(0.U)
      io.d_crypto := in.d.bits.user.lift(CacheCryptoRefillMeta).map(_.cryptoLine).getOrElse(false.B)
    }
  }
  lazy val module = new Impl
  def tapIO = module.io
}

object InclusiveCacheTopBoomHarness {
  implicit val p: Parameters = BoomDCacheRealDriverHarness.p
  val cache: CacheParameters = InclusiveCacheTopHarness.fastCache
  val micro: InclusiveCacheMicroParameters = InclusiveCacheTopHarness.fastMicro
  val dcacheWays = 2
  val dcacheSets = 8
}

class InclusiveCacheTopBoomHarness(
  val topParams: Parameters,
  val cache: CacheParameters,
  val micro: InclusiveCacheMicroParameters,
  val dcacheWays: Int,
  val dcacheSets: Int) extends LazyModule()(topParams) {
  implicit val sourceInfo: SourceInfo = UnlocatableSourceInfo

  private val visibilityNode = TLEphemeralNode()(ValName("boom_inclusive_tile_master"))
  val boomParams: Parameters = BoomDCacheRealDriverHarness.boomP(visibilityNode, dcacheWays = dcacheWays, dcacheSets = dcacheSets)
  val dcache = LazyModule(new BoomNonBlockingDCache(0)(boomParams))
  val inclusive = LazyModule(new InclusiveCache(cache, micro, None)(topParams))
  val tap = LazyModule(new TLCTestTap)

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
    outerNode :=* inclusive.node :=* tap.node :=* visibilityNode :=* dcache.node
  }

  val outer_tl = InModuleBody { outerNode.makeIOs() }

  lazy val module = new InclusiveCacheTopBoomHarnessModule(this)
}

class InclusiveCacheTopBoomHarnessModule(lm: InclusiveCacheTopBoomHarness) extends LazyModuleImp(lm) {
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
    val release_valid = Output(Bool())
    val release_opcode = Output(UInt(3.W))
    val release_address = Output(UInt(64.W))
    val release_data = Output(UInt(64.W))
    val release_counter = Output(UInt(64.W))
    val release_crypto = Output(Bool())
    val release_clear = Input(Bool())
    val release_seen = Output(Bool())
    val release_count = Output(UInt(8.W))
    val release_last_address = Output(UInt(64.W))
    val release_last_data = Output(UInt(64.W))
    val release_last_counter = Output(UInt(64.W))
    val release_last_crypto = Output(Bool())
    val inner_c_valid = Output(Bool())
    val inner_c_ready = Output(Bool())
    val inner_c_opcode = Output(UInt(3.W))
    val inner_c_address = Output(UInt(64.W))
    val inner_c_data = Output(UInt(64.W))
    val inner_c_counter = Output(UInt(64.W))
    val inner_c_crypto = Output(Bool())
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
  tap.stall_boom_inner_d := false.B
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
  io.release_valid := tap.c_valid
  io.release_opcode := tap.c_opcode
  io.release_address := tap.c_address
  io.release_data := tap.c_data
  io.release_counter := tap.c_counter
  io.release_crypto := tap.c_crypto
  val releaseSeenReg = RegInit(false.B)
  val releaseCountReg = RegInit(0.U(8.W))
  val releaseAddrReg = RegInit(0.U(64.W))
  val releaseDataReg = RegInit(0.U(64.W))
  val releaseCounterReg = RegInit(0.U(64.W))
  val releaseCryptoReg = RegInit(false.B)
  when (io.release_clear) {
    releaseSeenReg := false.B
    releaseCountReg := 0.U
    releaseAddrReg := 0.U
    releaseDataReg := 0.U
    releaseCounterReg := 0.U
    releaseCryptoReg := false.B
  } .elsewhen (tap.c_valid && tap.c_ready) {
    releaseSeenReg := true.B
    releaseCountReg := releaseCountReg + 1.U
    releaseAddrReg := tap.c_address
    releaseDataReg := tap.c_data
    releaseCounterReg := tap.c_counter
    releaseCryptoReg := tap.c_crypto
  }
  io.release_seen := releaseSeenReg
  io.release_count := releaseCountReg
  io.release_last_address := releaseAddrReg
  io.release_last_data := releaseDataReg
  io.release_last_counter := releaseCounterReg
  io.release_last_crypto := releaseCryptoReg
  io.inner_c_valid := tap.c_valid
  io.inner_c_ready := tap.c_ready
  io.inner_c_opcode := tap.c_opcode
  io.inner_c_address := tap.c_address
  io.inner_c_data := tap.c_data
  io.inner_c_counter := tap.c_counter
  io.inner_c_crypto := tap.c_crypto

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

class InclusiveCacheTopBoomHarnessWrapper(
  cache: CacheParameters = InclusiveCacheTopBoomHarness.cache,
  micro: InclusiveCacheMicroParameters = InclusiveCacheTopBoomHarness.micro,
  dcacheWays: Int = InclusiveCacheTopBoomHarness.dcacheWays,
  dcacheSets: Int = InclusiveCacheTopBoomHarness.dcacheSets) extends Module {
  implicit val p: Parameters = InclusiveCacheTopBoomHarness.p
  val root = new InclusiveCacheTopBoomHarness(p, cache, micro, dcacheWays, dcacheSets)
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
    val release_valid = Output(Bool())
    val release_opcode = Output(UInt(3.W))
    val release_address = Output(UInt(64.W))
    val release_data = Output(UInt(64.W))
    val release_counter = Output(UInt(64.W))
    val release_crypto = Output(Bool())
    val release_clear = Input(Bool())
    val release_seen = Output(Bool())
    val release_count = Output(UInt(8.W))
    val release_last_address = Output(UInt(64.W))
    val release_last_data = Output(UInt(64.W))
    val release_last_counter = Output(UInt(64.W))
    val release_last_crypto = Output(Bool())
    val inner_c_valid = Output(Bool())
    val inner_c_ready = Output(Bool())
    val inner_c_opcode = Output(UInt(3.W))
    val inner_c_address = Output(UInt(64.W))
    val inner_c_data = Output(UInt(64.W))
    val inner_c_counter = Output(UInt(64.W))
    val inner_c_crypto = Output(Bool())
    val outer = chiselTypeOf(root.outer_tl.head)
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
  io.release_valid := dut.io.release_valid
  io.release_opcode := dut.io.release_opcode
  io.release_address := dut.io.release_address
  io.release_data := dut.io.release_data
  io.release_counter := dut.io.release_counter
  io.release_crypto := dut.io.release_crypto
  dut.io.release_clear := io.release_clear
  io.release_seen := dut.io.release_seen
  io.release_count := dut.io.release_count
  io.release_last_address := dut.io.release_last_address
  io.release_last_data := dut.io.release_last_data
  io.release_last_counter := dut.io.release_last_counter
  io.release_last_crypto := dut.io.release_last_crypto
  io.inner_c_valid := dut.io.inner_c_valid
  io.inner_c_ready := dut.io.inner_c_ready
  io.inner_c_opcode := dut.io.inner_c_opcode
  io.inner_c_address := dut.io.inner_c_address
  io.inner_c_data := dut.io.inner_c_data
  io.inner_c_counter := dut.io.inner_c_counter
  io.inner_c_crypto := dut.io.inner_c_crypto
  io.outer <> root.outer_tl.head
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
