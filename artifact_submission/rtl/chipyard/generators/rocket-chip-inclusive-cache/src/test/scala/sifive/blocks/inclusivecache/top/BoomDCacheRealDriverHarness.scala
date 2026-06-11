package sifive.blocks.inclusivecache.top

import boom.v3.common.BoomTileAttachParams
import boom.v3.common.NullMicroOp
import boom.v3.lsu.BoomNonBlockingDCache
import boom.v3.lsu.BoomDCacheReq
import chipyard.SmallBoomV3Config
import chisel3._
import chisel3.experimental.{SourceInfo, UnlocatableSourceInfo}
import chisel3.util.experimental.BoringUtils
import freechips.rocketchip.diplomacy.{AddressSet, DisableMonitors, IdRange, InModuleBody, LazyModule, LazyModuleImp, RegionType, TransferSizes, ValName}
import freechips.rocketchip.rocket.M_XRD
import freechips.rocketchip.rocket.M_XWR
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, CacheCryptoRefillMetaField, CacheCryptoWritebackMeta, DCacheParams}
import freechips.rocketchip.subsystem.{InSubsystem, TilesLocated}
import freechips.rocketchip.tile.{TileKey, TileVisibilityNodeKey}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

object BoomDCacheRealDriverHarness {
  implicit val p: Parameters = new SmallBoomV3Config

  lazy val boomTile = p(TilesLocated(InSubsystem)).collectFirst {
    case tp: BoomTileAttachParams => tp.tileParams
  }.get

  def boomP(visibilityNode: TLNode, dcacheWays: Int = 2, dcacheSets: Int = 8): Parameters = p.alterMap(Map(
    TileVisibilityNodeKey -> visibilityNode,
    TileKey -> boomTile.copy(
      dcache = Some(boomTile.dcache.getOrElse(DCacheParams()).copy(nWays = dcacheWays, nSets = dcacheSets))
    )
  ))
}

class BoomDCacheRealDriverHarness(
  val topParams: Parameters,
  val dcacheWays: Int = 2,
  val dcacheSets: Int = 8) extends LazyModule()(topParams) {
  implicit val sourceInfo: SourceInfo = UnlocatableSourceInfo

  private val visibilityNode = TLEphemeralNode()(ValName("boom_real_tile_master"))
  val boomParams: Parameters = BoomDCacheRealDriverHarness.boomP(visibilityNode, dcacheWays = dcacheWays, dcacheSets = dcacheSets)
  val dcache = LazyModule(new BoomNonBlockingDCache(0)(boomParams))

  val outerNode = TLManagerNode(Seq(TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v2(
      address = Seq(AddressSet(0x0, 0xffffffffL)),
      regionType = RegionType.UNCACHED,
      supports = TLMasterToSlaveTransferSizes(
        acquireT = TransferSizes(64, 64),
        acquireB = TransferSizes(64, 64),
        arithmetic = TransferSizes.none,
        logical = TransferSizes.none,
        get = TransferSizes(8, 64),
        putFull = TransferSizes(8, 64),
        putPartial = TransferSizes.none,
        hint = TransferSizes.none))),
    beatBytes = 8,
    endSinkId = 8,
    minLatency = 1,
    responseFields = Seq(CacheCryptoRefillMetaField(counterBits = topParams(freechips.rocketchip.rocket.CacheCryptoCounterBitsKey))),
    requestKeys = Seq(CacheCryptoWritebackMeta))))

  DisableMonitors { implicit p => outerNode :=* visibilityNode :=* dcache.node }

  val outer_tl = InModuleBody { outerNode.makeIOs() }

  lazy val module = new BoomDCacheRealDriverHarnessModule(this)
}

class BoomDCacheRealDriverHarnessModule(lm: BoomDCacheRealDriverHarness) extends LazyModuleImp(lm) {
  val io = IO(new Bundle {
    val req_valid = Input(Bool())
    val req_addr = Input(UInt(64.W))
    val req_data = Input(UInt(64.W))
    val req_cmd = Input(UInt(5.W))
    val req_mem_size = Input(UInt(2.W))
    val req_mem_signed = Input(Bool())
    val req_ready = Output(Bool())
    val resp_valid = Output(Bool())
    val resp_data = Output(UInt(64.W))
    val nack_valid = Output(Bool())
    val replay_type = Output(UInt(3.W))
    val replay_selected = Output(Bool())
    val replay_addr = Output(UInt(64.W))
    val replay_cmd = Output(UInt(5.W))
    val replay_way = Output(UInt(16.W))
    val replay_live_tag_eq_way = Output(UInt(16.W))
    val replay_counter = Output(UInt(64.W))
    val reenc_pending = Output(Bool())
    val reenc_active = Output(Bool())
    val reenc_meta_set_pending = Output(Bool())
    val reenc_meta_clear_pending = Output(Bool())
    val dataKey = Input(UInt(128.W))
    val cacheCryptoLoadEnableValue = Input(Bool())
    val cacheCryptoStoreEnableValue = Input(Bool())
    val cacheCryptoEnableWen = Input(Bool())
    val cacheCryptoCounterBaseValue = Input(UInt(64.W))
    val cacheCryptoCounterBaseWen = Input(Bool())
    val log = Input(Bool())
  })

  val lsu = lm.dcache.module.io.lsu
  val dbgS2Type = BoringUtils.bore(lm.dcache.module.s2_type)
  val dbgS1TagEqWay = BoringUtils.bore(lm.dcache.module.s1_tag_eq_way(0))
  val dbgS2ReplaySelected = BoringUtils.bore(lm.dcache.module.s2_replay_fresh_selected)
  val dbgS2ReqAddr = BoringUtils.bore(lm.dcache.module.s2_req(0).addr)
  val dbgS2ReqCmd = BoringUtils.bore(lm.dcache.module.s2_req(0).uop.mem_cmd)
  val dbgS2Way = BoringUtils.bore(lm.dcache.module.s2_tag_match_way(0))
  val dbgS2Counter = BoringUtils.bore(lm.dcache.module.s2_counter(0))
  val dbgReencPending = BoringUtils.bore(lm.dcache.module.engine.reencPending)
  val dbgReencActive = BoringUtils.bore(lm.dcache.module.engine.reencActive)
  val dbgReencMetaSetPending = BoringUtils.bore(lm.dcache.module.engine.reencMetaSetPending)
  val dbgReencMetaClearPending = BoringUtils.bore(lm.dcache.module.engine.reencMetaClearPending)
  val dbgS2LiveTagEqWay = RegNext(dbgS1TagEqWay)
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
  io.nack_valid := lsu.nack.map(_.valid).reduce(_ || _)
  io.replay_type := dbgS2Type
  io.replay_selected := dbgS2ReplaySelected
  io.replay_addr := dbgS2ReqAddr
  io.replay_cmd := dbgS2ReqCmd
  io.replay_way := dbgS2Way
  io.replay_live_tag_eq_way := dbgS2LiveTagEqWay
  io.replay_counter := dbgS2Counter
  io.reenc_pending := dbgReencPending
  io.reenc_active := dbgReencActive
  io.reenc_meta_set_pending := dbgReencMetaSetPending
  io.reenc_meta_clear_pending := dbgReencMetaClearPending

  lm.dcache.module.io.dataKey := io.dataKey
  lm.dcache.module.io.cacheCryptoLoadEnableValue := io.cacheCryptoLoadEnableValue
  lm.dcache.module.io.cacheCryptoStoreEnableValue := io.cacheCryptoStoreEnableValue
  lm.dcache.module.io.cacheCryptoEnableWen := io.cacheCryptoEnableWen
  lm.dcache.module.io.cacheCryptoCounterBaseValue := io.cacheCryptoCounterBaseValue
  lm.dcache.module.io.cacheCryptoCounterBaseWen := io.cacheCryptoCounterBaseWen
  lm.dcache.module.io.log := io.log
}

class BoomDCacheRealDriverHarnessWrapper(
  dcacheWays: Int = 2,
  dcacheSets: Int = 8) extends Module {
  implicit val p: Parameters = BoomDCacheRealDriverHarness.p
  val root = new BoomDCacheRealDriverHarness(p, dcacheWays = dcacheWays, dcacheSets = dcacheSets)
  val dut = Module(LazyModule(root).module)

  val io = IO(new Bundle {
    val req_valid = Input(Bool())
    val req_addr = Input(UInt(64.W))
    val req_data = Input(UInt(64.W))
    val req_cmd = Input(UInt(5.W))
    val req_mem_size = Input(UInt(2.W))
    val req_mem_signed = Input(Bool())
    val req_ready = Output(Bool())
    val resp_valid = Output(Bool())
    val resp_data = Output(UInt(64.W))
    val nack_valid = Output(Bool())
    val replay_type = Output(UInt(3.W))
    val replay_selected = Output(Bool())
    val replay_addr = Output(UInt(64.W))
    val replay_cmd = Output(UInt(5.W))
    val replay_way = Output(UInt(16.W))
    val replay_live_tag_eq_way = Output(UInt(16.W))
    val replay_counter = Output(UInt(64.W))
    val reenc_pending = Output(Bool())
    val reenc_active = Output(Bool())
    val reenc_meta_set_pending = Output(Bool())
    val reenc_meta_clear_pending = Output(Bool())
    val outer = chiselTypeOf(root.outer_tl.head)
    val dataKey = Input(UInt(128.W))
    val cacheCryptoLoadEnableValue = Input(Bool())
    val cacheCryptoStoreEnableValue = Input(Bool())
    val cacheCryptoEnableWen = Input(Bool())
    val cacheCryptoCounterBaseValue = Input(UInt(64.W))
    val cacheCryptoCounterBaseWen = Input(Bool())
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
  io.nack_valid := dut.io.nack_valid
  io.replay_type := dut.io.replay_type
  io.replay_selected := dut.io.replay_selected
  io.replay_addr := dut.io.replay_addr
  io.replay_cmd := dut.io.replay_cmd
  io.replay_way := dut.io.replay_way
  io.replay_live_tag_eq_way := dut.io.replay_live_tag_eq_way
  io.replay_counter := dut.io.replay_counter
  io.reenc_pending := dut.io.reenc_pending
  io.reenc_active := dut.io.reenc_active
  io.reenc_meta_set_pending := dut.io.reenc_meta_set_pending
  io.reenc_meta_clear_pending := dut.io.reenc_meta_clear_pending
  io.outer <> root.outer_tl.head
  dut.io.dataKey := io.dataKey
  dut.io.cacheCryptoLoadEnableValue := io.cacheCryptoLoadEnableValue
  dut.io.cacheCryptoStoreEnableValue := io.cacheCryptoStoreEnableValue
  dut.io.cacheCryptoEnableWen := io.cacheCryptoEnableWen
  dut.io.cacheCryptoCounterBaseValue := io.cacheCryptoCounterBaseValue
  dut.io.cacheCryptoCounterBaseWen := io.cacheCryptoCounterBaseWen
  dut.io.log := io.log
}
