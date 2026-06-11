//******************************************************************************
// Copyright (c) 2017 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package boom.v3.common

import chisel3._
import chisel3.util.{RRArbiter, Queue}

import scala.collection.mutable.{ListBuffer}

import org.chipsalliance.cde.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.{RocketCrossingParams}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._

import boom.v3.exu._
import boom.v3.ifu._
import boom.v3.lsu._
import boom.v3.util.{BoomCoreStringPrefix}
import freechips.rocketchip.prci.ClockSinkParameters


case class BoomTileAttachParams(
  tileParams: BoomTileParams,
  crossingParams: RocketCrossingParams
) extends CanAttachTile {
  type TileType = BoomTile
  val lookup = PriorityMuxHartIdFromSeq(Seq(tileParams))
}


/**
 * BOOM tile parameter class used in configurations
 *
 */
case class BoomTileParams(
  core: BoomCoreParams = BoomCoreParams(),
  icache: Option[ICacheParams] = Some(ICacheParams()),
  dcache: Option[DCacheParams] = Some(DCacheParams()),
  btb: Option[BTBParams] = Some(BTBParams()),
  name: Option[String] = Some("boom_tile"),
  tileId: Int = 0
) extends InstantiableTileParams[BoomTile]
{
  require(icache.isDefined)
  require(dcache.isDefined)
  def instantiate(crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters): BoomTile = {
    new BoomTile(this, crossing, lookup)
  }
  val beuAddr: Option[BigInt] = None
  val blockerCtrlAddr: Option[BigInt] = None
  val boundaryBuffers: Boolean = false // if synthesized with hierarchical PnR, cut feed-throughs?
  val clockSinkParams: ClockSinkParameters = ClockSinkParameters()
  val baseName = name.getOrElse("boom_tile")
  val uniqueName = s"${baseName}_$tileId"
}

/**
 * BOOM tile
 *
 */
class BoomTile private(
  val boomParams: BoomTileParams,
  crossing: ClockCrossingType,
  lookup: LookupByHartIdImpl,
  q: Parameters)
  extends BaseTile(boomParams, crossing, lookup, q)
  with SinksExternalInterrupts
  with SourcesExternalNotifications
{

  // Private constructor ensures altered LazyModule.p is used implicitly
  def this(params: BoomTileParams, crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  val intOutwardNode = None
  val masterNode = TLIdentityNode()
  val slaveNode = TLIdentityNode()

  val tile_master_blocker =
    tileParams.blockerCtrlAddr
      .map(BasicBusBlockerParams(_, xBytes, masterPortBeatBytes, deadlock = true))
      .map(bp => LazyModule(new BasicBusBlocker(bp)))

  tile_master_blocker.foreach(lm => connectTLSlave(lm.controlNode, xBytes))

  // TODO: this doesn't block other masters, e.g. RoCCs
  tlOtherMastersNode := tile_master_blocker.map { _.node := tlMasterXbar.node } getOrElse { tlMasterXbar.node }
  masterNode :=* tlOtherMastersNode

  val cpuDevice: SimpleDevice = new SimpleDevice("cpu", Seq("ucb-bar,boom0", "riscv")) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++
                        cpuProperties ++
                        nextLevelCacheProperty ++
                        tileProperties)
    }
  }

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(tileId))
  }

  override def makeMasterBoundaryBuffers(crossing: ClockCrossingType)(implicit p: Parameters) = crossing match {
    case _: RationalCrossing =>
      if (!boomParams.boundaryBuffers) TLBuffer(BufferParams.none)
      else TLBuffer(BufferParams.none, BufferParams.flow, BufferParams.none, BufferParams.flow, BufferParams(1))
    case _ => TLBuffer(BufferParams.none)
  }

  override def makeSlaveBoundaryBuffers(crossing: ClockCrossingType)(implicit p: Parameters) = crossing match {
    case _: RationalCrossing =>
      if (!boomParams.boundaryBuffers) TLBuffer(BufferParams.none)
      else TLBuffer(BufferParams.flow, BufferParams.none, BufferParams.none, BufferParams.none, BufferParams.none)
    case _ => TLBuffer(BufferParams.none)
  }

  override lazy val module = new BoomTileModuleImp(this)

  // DCache
  lazy val dcache: BoomNonBlockingDCache = LazyModule(new BoomNonBlockingDCache(tileId))
  val dCacheTap = TLIdentityNode()
  tlMasterXbar.node := dCacheTap := TLWidthWidget(tileParams.dcache.get.rowBits/8) := visibilityNode := dcache.node

  

  // Frontend/ICache
  val frontend = LazyModule(new BoomFrontend(tileParams.icache.get, tileId))

  // frontend.io.log := 

  frontend.resetVectorSinkNode := resetVectorNexusNode
  tlMasterXbar.node := TLWidthWidget(tileParams.icache.get.rowBits/8) := frontend.masterNode

  require(tileParams.dcache.get.rowBits == tileParams.icache.get.rowBits)

  // ROCC
  val roccs = p(BuildRoCC).map(_(p))
  roccs.map(_.atlNode).foreach { atl => tlMasterXbar.node :=* atl }
  roccs.map(_.tlNode).foreach { tl => tlOtherMastersNode :=* tl }
}

/**
 * BOOM tile implementation
 *
 * @param outer top level BOOM tile
 */
class BoomTileModuleImp(outer: BoomTile) extends BaseTileModuleImp(outer){

  val core = Module(new BoomCore()(outer.p))
  val lsu  = Module(new LSU()(outer.p, outer.dcache.module.edge))

  val ptwPorts         = ListBuffer(lsu.io.ptw, outer.frontend.module.io.ptw, core.io.ptw_tlb)

  val hellaCachePorts  = ListBuffer[HellaCacheIO]()

  outer.reportWFI(None) // TODO: actually report this?

  outer.decodeCoreInterrupts(core.io.interrupts) // Decode the interrupt vector

  // Pass through various external constants and reports
  outer.traceSourceNode.bundle <> core.io.trace
  outer.bpwatchSourceNode.bundle <> DontCare // core.io.bpwatch
  core.io.hartid := outer.hartIdSinkNode.bundle

  // Connect the core pipeline to other intra-tile modules
  outer.frontend.module.io.cpu <> core.io.ifu
  core.io.lsu <> lsu.io.core

  val key_gen = Module(new boom.v3.util.Key_Engine()(outer.p))
  // key_gen.io.gen_xor_req <> core.io.gen_xor_req
  key_gen.io.gen_key_req <> lsu.io.key_gen_req
  lsu.io.key_gen_resp := key_gen.io.gen_key_resp
  // key_gen.io.gen_xor_req := DontCare
  key_gen.io.xor_key := DontCare

  key_gen.io.log := core.io.log
  key_gen.io.prv := core.io.prv
  key_gen.io.dprv := core.io.dprv

  key_gen.io.gen_xor_req := core.io.gen_xor_req 
  core.io.gen_xor_resp := key_gen.io.gen_xor_resp 
  key_gen.io.send_xor_req := core.io.send_xor_req
  // key_gen.io.xor_key <> core.io.xor_key
  // core.io.finish := key_gen.io.finish 

  key_gen.io.load_key_req <> lsu.io.load_key_req
  key_gen.io.store_key_req := lsu.io.store_key_req
  
  lsu.io.store_key_resp <> key_gen.io.store_key_resp

  val pointer_engine = Module(new boom.v3.util.pointer_engine()(outer.p))
  
  pointer_engine.io.pointer_key <> key_gen.io.pointer_key

  pointer_engine.io.alu_addr_req <> core.io.alu_addr_req 

  // pointer_engine.io.fetch_addr_flush := outer.frontend.module.io.fetch_addr_flush
  

  lsu.io.lsu_addr_resp(0) <> pointer_engine.io.lsu_addr_resp(0)
  lsu.io.lsu_addr_resp(1) <> pointer_engine.io.lsu_addr_resp(1)
  lsu.io.lsu_addr_req <> pointer_engine.io.lsu_addr_req
  lsu.io.cus_reg := core.io.cus_reg
  
  // pointer_engine.io.cc_isa_encptr_req <> core.io.cc_isa_encptr_req
  // core.io.cc_isa_encptr_resp <> pointer_engine.io.cc_isa_encptr_resp
  // val data_engine = Module(new boom.v3.util.data_engine()(outer.p))

  
  // outer.frontend.module.io.fetch_addr_req <> pointer_engine.io.fetch_addr_req
  // outer.frontend.module.io.fetch_addr_resp <> pointer_engine.io.fetch_addr_resp

  // data_engine.io.fetch_data_flush := outer.frontend.module.io.fetch_data_flush

  outer.frontend.module.io.cus_reg <> core.io.cus_reg
  outer.frontend.module.io.icache_crypto_enable := core.io.icache_crypto_enable_value
  outer.frontend.module.io.dataKey := key_gen.io.data_key
  val prevActiveDataKey = RegNext(key_gen.io.data_key)
  val prevActivePointerKey = RegNext(key_gen.io.pointer_key)
  when (prevActiveDataKey =/= key_gen.io.data_key || prevActivePointerKey =/= key_gen.io.pointer_key) {
    chisel3.printf("[TILE-ACTIVE-KEY] prv=%d dprv=%d data_key=0x%x ptr_key=0x%x\n",
      core.io.prv,
      core.io.dprv,
      key_gen.io.data_key,
      key_gen.io.pointer_key)
  }


  // val lsu_data_engine = Module(new boom.v3.util.lsu_data_engine()(outer.p))

  // lsu_data_engine.io.alu_data_req <> core.io.alu_data_req

  // 两个data engine 
  val c4_lsu_tlb = Module(new boom.v3.util.C4_TLB()(outer.p))

  c4_lsu_tlb.io.write <> pointer_engine.io.write_lsu_tlb

  val c4_fetch_tlb = Module(new boom.v3.util.C4_frontend_TLB()(outer.p))

  // fetch tlb flush 应该在 切换页表的时候 刷新 ？

  c4_fetch_tlb.io.fetch_addr_req <> pointer_engine.io.fetch_addr_req
  
  // 如果  alu中直接 tlb返回结果，那么如何 区分呢
  // 1.加一根线 
  // 2. 或者如果 tlb false，直接将 alu的 resp设置为 false,然后由 engine来填充 （开销更大）

  c4_fetch_tlb.io.write <> pointer_engine.io.write_fetch_tlb

  c4_fetch_tlb.io.lookup(0) <> outer.frontend.module.io.fetch_lookup(0)
  c4_fetch_tlb.io.lookup(1) <> outer.frontend.module.io.fetch_lookup(1)

  outer.frontend.module.io.fetch_tlb_result(0) <> c4_fetch_tlb.io.result(0) 
  outer.frontend.module.io.fetch_tlb_result(1) <> c4_fetch_tlb.io.result(1)

  c4_lsu_tlb.io.lookup(0) <> core.io.alu_lookup
  c4_lsu_tlb.io.lookup(1) <> lsu.io.lsu_lookup 

  core.io.alu_tlb_result <> c4_lsu_tlb.io.result(0)
  // core.io.rreg_data_req <> lsu_data_engine.io.rreg_data_req
  
  lsu.io.lsu_tlb_result  <> c4_lsu_tlb.io.result(1)

  // data_engine.io.data_key <> key_gen.io.data_key
  // lsu_data_engine.io.data_key <> key_gen.io.data_key

  // lsu.io.lsu_data_resp <> lsu_data_engine.io.lsu_data_resp 
  // lsu.io.lsu_data_req <> lsu_data_engine.io.lsu_data_req

  // lsu_data_engine.io.c4_flush  := core.io.c4_flush
  // data_engine.io.c4_flush  := core.io.c4_flush
  pointer_engine.io.c4_flush := core.io.c4_flush

  c4_lsu_tlb.io.flush := core.io.c4_flush
  c4_fetch_tlb.io.flush := core.io.c4_flush
  //fpuOpt foreach { fpu => core.io.fpu <> fpu.io } RocketFpu - not needed in boom
  core.io.rocc := DontCare

  // RoCC
  if (outer.roccs.size > 0) {
    val (respArb, cmdRouter) = {
      val respArb = Module(new RRArbiter(new RoCCResponse()(outer.p), outer.roccs.size))
      val cmdRouter = Module(new RoccCommandRouter(outer.roccs.map(_.opcodes))(outer.p))
      outer.roccs.zipWithIndex.foreach { case (rocc, i) =>
        ptwPorts ++= rocc.module.io.ptw
        rocc.module.io.cmd <> cmdRouter.io.out(i)
        val dcIF = Module(new SimpleHellaCacheIF()(outer.p))
        dcIF.io.requestor <> rocc.module.io.mem
        hellaCachePorts += dcIF.io.cache
        respArb.io.in(i) <> Queue(rocc.module.io.resp)
      }
      // first keep fpu ios unconnected
      val fp_ios = outer.roccs.map(r => {
        val roccio = r.module.io
        roccio.fpu_req.ready := true.B
        roccio.fpu_resp.valid := false.B
        roccio.fpu_resp.bits := DontCare
      })
      // Create this FPU just for RoCC
      val nFPUPorts = outer.roccs.filter(_.usesFPU).size
      if (nFPUPorts > 0) {
        val fpuOpt = outer.tileParams.core.fpu.map(params => Module(new freechips.rocketchip.tile.FPU(params)(outer.p)))
        // TODO: Check this FPU works properly
        fpuOpt foreach { fpu =>
          // This FPU does not get CPU requests
          fpu.io := DontCare
          fpu.io.fcsr_rm := core.io.fcsr_rm
          fpu.io.ll_resp_val := false.B
          fpu.io.valid := false.B
          fpu.io.killx := false.B
          fpu.io.killm := false.B

          val fpArb = Module(new InOrderArbiter(new FPInput()(outer.p), new FPResult()(outer.p), nFPUPorts))
          val fp_rocc_ios = outer.roccs.filter(_.usesFPU).map(_.module.io)
          fpArb.io.in_req <> fp_rocc_ios.map(_.fpu_req)
          fp_rocc_ios.zip(fpArb.io.in_resp).foreach {
            case (rocc, arb) => rocc.fpu_resp <> arb
          }
          fpu.io.cp_req <> fpArb.io.out_req
          fpArb.io.out_resp <> fpu.io.cp_resp
        }
      }
      (respArb, cmdRouter)
    }

    cmdRouter.io.in <> core.io.rocc.cmd
    outer.roccs.foreach(_.module.io.exception := core.io.rocc.exception)
    core.io.rocc.resp <> respArb.io.out
    core.io.rocc.busy <> (cmdRouter.io.busy || outer.roccs.map(_.module.io.busy).reduce(_||_))
    core.io.rocc.interrupt := outer.roccs.map(_.module.io.interrupt).reduce(_||_)
  }

  /////////////
  //when (core.io.log.B)
  //{
  //  lsu.io.log = true
  //  frontend.module.fb_setlog()
  //}
 
  lsu.io.log := core.io.log
  outer.frontend.module.io.log := core.io.log
  outer.dcache.module.io.log := core.io.log
  outer.dcache.module.io.dataKey := key_gen.io.data_key
  outer.dcache.module.io.cacheCryptoLoadEnableValue := core.io.cache_crypto_load_enable_value
  outer.dcache.module.io.cacheCryptoStoreEnableValue := core.io.cache_crypto_store_enable_value
  outer.dcache.module.io.cacheCryptoEnableWen := core.io.cache_crypto_enable_wen
  outer.dcache.module.io.cacheCryptoCounterBaseValue := core.io.cache_crypto_counter_base_value
  outer.dcache.module.io.cacheCryptoCounterBaseWen := core.io.cache_crypto_counter_base_wen
  outer.cacheCryptoConfigSourceNode.bundle.enable := core.io.cache_crypto_load_enable_value || core.io.cache_crypto_store_enable_value
  outer.cacheCryptoConfigSourceNode.bundle.enable_wen := core.io.cache_crypto_enable_wen
  outer.cacheCryptoConfigSourceNode.bundle.base := core.io.cache_crypto_counter_base_value
  outer.cacheCryptoConfigSourceNode.bundle.base_wen := core.io.cache_crypto_counter_base_wen
  /////////////
  // PTW
  val ptw  = Module(new PTW(ptwPorts.length)(outer.dcache.node.edges.out(0), outer.p))
  ptw.io.log := core.io.log
  core.io.ptw <> ptw.io.dpath
  ptw.io.requestor <> ptwPorts.toSeq
  ptw.io.mem +=: hellaCachePorts


   // LSU IO
  val hellaCacheArb = Module(new HellaCacheArbiter(hellaCachePorts.length)(outer.p))
  hellaCacheArb.io.requestor <> hellaCachePorts.toSeq
  lsu.io.hellacache <> hellaCacheArb.io.mem
  outer.dcache.module.io.lsu <> lsu.io.dmem

  // Generate a descriptive string
  val frontendStr = outer.frontend.module.toString
  val coreStr = core.toString
  val boomTileStr =
    (BoomCoreStringPrefix(s"======BOOM Tile ${outer.tileId} Params======") + "\n"
    + frontendStr
    + coreStr + "\n")

  override def toString: String = boomTileStr

  print(boomTileStr)
}
