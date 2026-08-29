//******************************************************************************
// Copyright (c) 2012 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Out-of-Order Load/Store Unit
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Load/Store Unit is made up of the Load Queue, the Store Queue (LDQ and STQ).
//
// Stores are sent to memory at (well, after) commit, loads are executed
// optimstically ASAP.  If a misspeculation was discovered, the pipeline is
// cleared. Loads put to sleep are retried.  If a LoadAddr and StoreAddr match,
// the Load can receive its data by forwarding data out of the Store Queue.
//
// Currently, loads are sent to memory immediately, and in parallel do an
// associative search of the STQ, on entering the LSU. If a hit on the STQ
// search, the memory request is killed on the next cycle, and if the STQ entry
// is valid, the store data is forwarded to the load (delayed to match the
// load-use delay to delay with the write-port structural hazard). If the store
// data is not present, or it's only a partial match (SB->LH), the load is put
// to sleep in the LDQ.
//
// Memory ordering violations are detected by stores at their addr-gen time by
// associatively searching the LDQ for newer loads that have been issued to
// memory.
//
// The store queue contains both speculated and committed stores.
//
// Only one port to memory... loads and stores have to fight for it, West Side
// Story style.
//
// TODO:
//    - Add predicting structure for ordering failures
//    - currently won't STD forward if DMEM is busy
//    - ability to turn off things if VM is disabled
//    - reconsider port count of the wakeup, retry stuff

package boom.v3.lsu

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.rocket
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.Str
import freechips.rocketchip.rocket.{ PRV}
import boom.v3.common._
import boom.v3.exu.{BrUpdateInfo, Exception, FuncUnitResp, CommitSignals, ExeUnitResp}
import boom.v3.util.{BoolToChar, AgePriorityEncoder, IsKilledByBranch, GetNewBrMask, WrapInc, IsOlder, UpdateBrMask}

class LSUExeIO(implicit p: Parameters) extends BoomBundle()(p)
{
  // The "resp" of the maddrcalc is really a "req" to the LSU
  val req       = Flipped(new ValidIO(new FuncUnitResp(xLen)))
  // Send load data to regfiles
  val iresp    = new DecoupledIO(new boom.v3.exu.ExeUnitResp(xLen))
  val fresp    = new DecoupledIO(new boom.v3.exu.ExeUnitResp(xLen+1)) // TODO: Should this be fLen?
}

class BoomDCacheReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomUOP
{
  val addr  = UInt(coreMaxAddrBits.W)
  val data  = Bits(coreDataBits.W)
  val is_hella = Bool() // Is this the hellacache req? If so this is not tracked in LDQ or STQ
}

class BoomDCacheResp(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomUOP
{
  val data = Bits(coreDataBits.W)
  val is_hella = Bool()
}

class LSUDMemIO(implicit p: Parameters, edge: TLEdgeOut) extends BoomBundle()(p)
{
  // In LSU's dmem stage, send the request
  val req         = new DecoupledIO(Vec(memWidth, Valid(new BoomDCacheReq)))
  // In LSU's LCAM search stage, kill if order fail (or forwarding possible)
  val s1_kill     = Output(Vec(memWidth, Bool()))
  // Get a request any cycle
  val resp        = Flipped(Vec(memWidth, new ValidIO(new BoomDCacheResp)))
  // In our response stage, if we get a nack, we need to reexecute
  val nack        = Flipped(Vec(memWidth, new ValidIO(new BoomDCacheReq)))

  val brupdate       = Output(new BrUpdateInfo)
  val exception    = Output(Bool())
  val rob_pnr_idx  = Output(UInt(robAddrSz.W))
  val rob_head_idx = Output(UInt(robAddrSz.W))

  val release = Flipped(new DecoupledIO(new TLBundleC(edge.bundle)))

  // Clears prefetching MSHRs
  val force_order  = Output(Bool())
  val ordered     = Input(Bool())

  val perf = Input(new Bundle {
    val acquire = Bool()
    val release = Bool()
  })

}

class LSUCoreIO(implicit p: Parameters) extends BoomBundle()(p)
{
  val exe = Vec(memWidth, new LSUExeIO)
  val gen_key_req = Flipped(Decoupled(new GenKeyReq))
  val gen_key_resp = Decoupled(new ExeUnitResp(xLen))

  val dis_uops    = Flipped(Vec(coreWidth, Valid(new MicroOp)))
  val dis_ldq_idx = Output(Vec(coreWidth, UInt(ldqAddrSz.W)))
  val dis_stq_idx = Output(Vec(coreWidth, UInt(stqAddrSz.W)))

  val ldq_full    = Output(Vec(coreWidth, Bool()))
  val stq_full    = Output(Vec(coreWidth, Bool()))


  val fp_stdata   = Flipped(Decoupled(new ExeUnitResp(fLen)))

  val commit      = Input(new CommitSignals)
  val commit_load_at_rob_head = Input(Bool())

  // Stores clear busy bit when stdata is received
  // memWidth for int, 1 for fp (to avoid back-pressure fpstdat)
  val clr_bsy         = Output(Vec(memWidth + 1, Valid(UInt(robAddrSz.W))))

  // Speculatively safe load (barring memory ordering failure)
  val clr_unsafe      = Output(Vec(memWidth, Valid(UInt(robAddrSz.W))))

  // Tell the DCache to clear prefetches/speculating misses
  val fence_dmem   = Input(Bool())

  // Speculatively tell the IQs that we'll get load data back next cycle
  val spec_ld_wakeup = Output(Vec(memWidth, Valid(UInt(maxPregSz.W))))
  // Tell the IQs that the load we speculated last cycle was misspeculated
  val ld_miss      = Output(Bool())

  val brupdate       = Input(new BrUpdateInfo)
  val rob_pnr_idx  = Input(UInt(robAddrSz.W))
  val rob_head_idx = Input(UInt(robAddrSz.W))
  val exception    = Input(Bool())

  val fencei_rdy  = Output(Bool())

  val lxcpt       = Output(Valid(new Exception))

  val tsc_reg     = Input(UInt())

  val perf        = Output(new Bundle {
    val acquire = Bool()
    val release = Bool()
    val tlbMiss = Bool()
  })

  val debug = Output(new DebugLSUSignals)
}

class DebugLSUSignals(implicit p: Parameters) extends BoomBundle()(p)
{
  val ldq_head = UInt(ldqAddrSz.W)
  val ldq_tail = UInt(ldqAddrSz.W)
  val stq_head = UInt(stqAddrSz.W)
  val stq_tail = UInt(stqAddrSz.W)
  val stq_commit_head = UInt(stqAddrSz.W)
  val stq_execute_head = UInt(stqAddrSz.W)

  val ldq_head_valid = Bool()
  val ldq_head_addr_valid = Bool()
  val ldq_head_addr_is_virtual = Bool()
  val ldq_head_addr_is_uncacheable = Bool()
  val ldq_head_executed = Bool()
  val ldq_head_succeeded = Bool()
  val ldq_head_order_fail = Bool()
  val ldq_head_forward_std = Bool()
  val ldq_head_pc = UInt(vaddrBitsExtended.W)
  val ldq_head_rob_idx = UInt(robAddrSz.W)
  val ldq_head_addr = UInt(coreMaxAddrBits.W)

  val stq_head_valid = Bool()
  val stq_head_addr_valid = Bool()
  val stq_head_data_valid = Bool()
  val stq_head_committed = Bool()
  val stq_head_succeeded = Bool()
  val stq_head_addr_is_virtual = Bool()
  val stq_head_pc = UInt(vaddrBitsExtended.W)
  val stq_head_rob_idx = UInt(robAddrSz.W)
  val stq_head_addr = UInt(coreMaxAddrBits.W)

  val stq_execute_valid = Bool()
  val stq_execute_addr_valid = Bool()
  val stq_execute_data_valid = Bool()
  val stq_execute_committed = Bool()
  val stq_execute_succeeded = Bool()
  val stq_execute_addr_is_virtual = Bool()
  val stq_execute_pc = UInt(vaddrBitsExtended.W)
  val stq_execute_rob_idx = UInt(robAddrSz.W)
  val stq_execute_addr = UInt(coreMaxAddrBits.W)
}

class LSUIO(implicit p: Parameters, edge: TLEdgeOut) extends BoomBundle()(p)
{
  val ptw   = new rocket.TLBPTWIO
  val core  = new LSUCoreIO
  val dmem  = new LSUDMemIO
  
  val hellacache = Flipped(new freechips.rocketchip.rocket.HellaCacheIO)

  /////////////////////
  val log = Input(Bool())
  val cus_reg = Input(UInt(3.W))
  val lsu_addr_resp = Vec(2,Flipped(DecoupledIO(new boom.v3.util.addr_resp)))
  val lsu_addr_req = DecoupledIO(new boom.v3.util.addr_req)
  val lsu_lookup = Valid(UInt(coreMaxAddrBits.W))
  val lsu_tlb_result = Flipped(Valid(UInt(coreMaxAddrBits.W)))
  val key_gen_req = Decoupled(Bool())
  val key_gen_resp = Input(Bool())
  val load_key_req = Valid(new boom.v3.util.load_key_req)
  val store_key_req = Output(Bool())
  val store_key_resp = Flipped(Valid(Vec(4,UInt(64.W))))
  ///////////////////////// 
}

class GenKeyReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomUOP

class LDQEntry(implicit p: Parameters) extends BoomBundle()(p)
   with HasBoomUOP
{
  val addr                = Valid(UInt(coreMaxAddrBits.W))
  val original_addr       = UInt(coreMaxAddrBits.W)
  val addr_is_virtual     = Bool() // Virtual address, we got a TLB miss
  val addr_is_uncacheable = Bool() // Uncacheable, wait until head of ROB to execute

  val executed            = Bool() // load sent to memory, reset by NACKs
  val succeeded           = Bool()
  val order_fail          = Bool()
  val observed            = Bool()

  val st_dep_mask         = UInt(numStqEntries.W) // list of stores older than us
  val youngest_stq_idx    = UInt(stqAddrSz.W) // index of the oldest store younger than us

  val forward_std_val     = Bool()
  val forward_stq_idx     = UInt(stqAddrSz.W) // Which store did we get the store-load forward from?

  val debug_wb_data       = UInt(xLen.W)
  /////////////////
  // 0 : Null  1: ca  2 : va  3: sent req
  // 相当于 把 addr的1 bit valid 换成这个
  val c4_state               = UInt(2.W) 
  /////////////////
}

class STQEntry(implicit p: Parameters) extends BoomBundle()(p)
   with HasBoomUOP
{
  val addr                = Valid(UInt(coreMaxAddrBits.W))
  val original_addr       = UInt(coreMaxAddrBits.W)
  val addr_is_virtual     = Bool() // Virtual address, we got a TLB miss
  val data                = Valid(UInt(xLen.W))

  val committed           = Bool() // committed by ROB
  val succeeded           = Bool() // D$ has ack'd this, we don't need to maintain this anymore

  val debug_wb_data       = UInt(xLen.W)
  ////////////////
  // 0 : Null  1: ca  2 : va  3: sent req
  val c4_state               = UInt(2.W) 
  /////////////////
}

class LSU(implicit p: Parameters, edge: TLEdgeOut) extends BoomModule()(p)
  with rocket.HasL1HellaCacheParameters
{
  val io = IO(new LSUIO)
  io.hellacache := DontCare
  private def printf(args: Any*): Unit = {}


  val ldq = Reg(Vec(numLdqEntries, Valid(new LDQEntry)))
  val stq = Reg(Vec(numStqEntries, Valid(new STQEntry)))

  val genKeyActive = RegInit(false.B)
  val genKeyUop = Reg(new MicroOp)
  val genKeyRespValid = RegInit(false.B)
  val genKeyRespBits = Reg(new ExeUnitResp(xLen))
  val keyLoadActive = RegInit(false.B)
  val keyLoadLdqIdx = RegInit(0.U(ldqAddrSz.W))
  val keyLoadBeat = RegInit(0.U(2.W))
  val keyLoadBaseAddr = RegInit(0.U(coreMaxAddrBits.W))
  val keyStoreActive = RegInit(false.B)
  val keyStoreStqIdx = RegInit(0.U(stqAddrSz.W))
  val keyStoreBeat = RegInit(0.U(2.W))
  val keyStoreBaseAddr = RegInit(0.U(coreMaxAddrBits.W))
  val keyStoreWaitingResp = RegInit(false.B)

  val asyncLdAddrEnable = io.cus_reg(0) === 1.U
  val asyncStAddrEnable = io.cus_reg(1) === 1.U


  val ldq_addr_req_Vec = VecInit.tabulate(numLdqEntries)(w =>
    asyncLdAddrEnable && ldq(w).valid && (ldq(w).bits.c4_state === 2.U))
  val ldq_addr_req_bits = ldq_addr_req_Vec.asUInt
  val ldq_addr_req_idx  = PriorityEncoder(ldq_addr_req_bits)

  val stq_addr_req_Vec = VecInit.tabulate(numStqEntries)(w =>
    asyncStAddrEnable && stq(w).valid && (stq(w).bits.c4_state === 2.U))
  val stq_addr_req_bits = stq_addr_req_Vec.asUInt
  val stq_addr_req_idx  = PriorityEncoder(stq_addr_req_bits)

  // Valid(Bool())

  io.lsu_addr_req.valid := false.B
  io.lsu_addr_req.bits := DontCare
  // io.lsu_data_req.valid := false.B
  // io.lsu_data_req.bits := DontCare
  io.lsu_lookup.valid := false.B
  io.lsu_lookup.bits := DontCare
  io.key_gen_req.valid := false.B
  io.key_gen_req.bits := false.B
  io.load_key_req.valid := false.B
  io.load_key_req.bits := DontCare
  io.store_key_req := false.B

  io.core.gen_key_req.ready := !genKeyActive && !genKeyRespValid && io.key_gen_req.ready
  io.core.gen_key_resp.valid := genKeyRespValid
  io.core.gen_key_resp.bits := genKeyRespBits

  when (io.core.gen_key_resp.fire) {
    genKeyRespValid := false.B
  }

  when (io.core.gen_key_req.fire) {
    genKeyActive := true.B
    genKeyUop := io.core.gen_key_req.bits.uop
    io.key_gen_req.valid := true.B
    io.key_gen_req.bits := true.B
  }

  when (genKeyActive && io.key_gen_resp) {
    genKeyActive := false.B
    genKeyRespValid := true.B
    genKeyRespBits.uop := genKeyUop
    genKeyRespBits.data := 0.U
    genKeyRespBits.predicated := false.B
    genKeyRespBits.fflags.valid := false.B
    genKeyRespBits.fflags.bits.uop := NullMicroOp
    genKeyRespBits.fflags.bits.flags := 0.U
  }

  when ((io.core.exception || IsKilledByBranch(io.core.brupdate, genKeyUop)) && (genKeyActive || genKeyRespValid)) {
    genKeyActive := false.B
    genKeyRespValid := false.B
  }

  when (keyLoadActive && (io.core.exception || IsKilledByBranch(io.core.brupdate, ldq(keyLoadLdqIdx).bits.uop))) {
    keyLoadActive := false.B
    keyLoadBeat := 0.U
  }

  when (keyStoreActive && !stq(keyStoreStqIdx).bits.committed &&
        (io.core.exception || IsKilledByBranch(io.core.brupdate, stq(keyStoreStqIdx).bits.uop))) {
    keyStoreActive := false.B
    keyStoreWaitingResp := false.B
    keyStoreBeat := 0.U
  }

  when ( io.lsu_addr_req.ready)
  {
    io.lsu_addr_req.bits.addr := Mux( ldq_addr_req_bits.orR, ldq(ldq_addr_req_idx).bits.addr.bits , stq(stq_addr_req_idx).bits.addr.bits )
    io.lsu_addr_req.bits.uop :=  Mux( ldq_addr_req_bits.orR, ldq(ldq_addr_req_idx).bits.uop , stq(stq_addr_req_idx).bits.uop ) 
    io.lsu_addr_req.bits.data := 0.U
    io.lsu_addr_req.bits.data_valid := false.B
    when (ldq_addr_req_bits.orR && (io.cus_reg(0) === 1.U) )
    {
      //  把 ldq或者stq中uop.c4_addr 变成 true.B
      io.lsu_lookup.valid := true.B
      io.lsu_lookup.bits := ldq(ldq_addr_req_idx).bits.addr.bits
      when (io.lsu_tlb_result.valid)
      {
        // The C4 TLB result is already the fully reconstructed encrypted vaddr.
        ldq(ldq_addr_req_idx).bits.addr.bits := io.lsu_tlb_result.bits
        ldq(ldq_addr_req_idx).bits.addr.valid := true.B
        ldq(ldq_addr_req_idx).bits.c4_state := 1.U
      }.otherwise{
        ldq(ldq_addr_req_idx).bits.c4_state := 3.U 
        io.lsu_addr_req.valid := true.B
      }
      
    }.elsewhen(stq_addr_req_bits.orR && (io.cus_reg(1) === 1.U) )
    {
      io.lsu_lookup.valid := true.B
      io.lsu_lookup.bits := stq(stq_addr_req_idx).bits.addr.bits
      when (io.lsu_tlb_result.valid)
      {
        stq(stq_addr_req_idx).bits.addr.bits := io.lsu_tlb_result.bits
        stq(stq_addr_req_idx).bits.addr.valid := true.B
        stq(stq_addr_req_idx).bits.c4_state := 1.U
      }.otherwise{
        stq(stq_addr_req_idx).bits.c4_state := 3.U
        io.lsu_addr_req.valid := true.B
      }
    }
  }


  val ldq_head         = Reg(UInt(ldqAddrSz.W))
  val ldq_tail         = Reg(UInt(ldqAddrSz.W))
  val stq_head         = Reg(UInt(stqAddrSz.W)) // point to next store to clear from STQ (i.e., send to memory)
  val stq_tail         = Reg(UInt(stqAddrSz.W))
  val stq_commit_head  = Reg(UInt(stqAddrSz.W)) // point to next store to commit
  val stq_execute_head = Reg(UInt(stqAddrSz.W)) // point to next store to execute


  // If we got a mispredict, the tail will be misaligned for 1 extra cycle
  assert (io.core.brupdate.b2.mispredict ||
          stq(stq_execute_head).valid ||
          stq_head === stq_execute_head ||
          stq_tail === stq_execute_head,
            "stq_execute_head got off track.")

  val h_ready :: h_s1 :: h_s2 :: h_s2_nack :: h_wait :: h_replay :: h_dead :: Nil = Enum(7)
  // s1 : do TLB, if success and not killed, fire request go to h_s2
  //      store s1_data to register
  //      if tlb miss, go to s2_nack
  //      if don't get TLB, go to s2_nack
  //      store tlb xcpt
  // s2 : If kill, go to dead
  //      If tlb xcpt, send tlb xcpt, go to dead
  // s2_nack : send nack, go to dead
  // wait : wait for response, if nack, go to replay
  // replay : refire request, use already translated address
  // dead : wait for response, ignore it
  val hella_state           = RegInit(h_ready)
  val hella_req             = Reg(new rocket.HellaCacheReq)
  val hella_data            = Reg(new rocket.HellaCacheWriteData)
  val hella_paddr           = Reg(UInt(paddrBits.W))
  val hella_xcpt            = Reg(new rocket.HellaCacheExceptions)


  val dtlb = Module(new NBDTLB(
    instruction = false, lgMaxSize = log2Ceil(coreDataBytes), rocket.TLBConfig(dcacheParams.nTLBSets, dcacheParams.nTLBWays)))

  io.ptw <> dtlb.io.ptw

  dtlb.io.cus_reg := io.cus_reg

  io.core.perf.tlbMiss := io.ptw.req.fire
  io.core.perf.acquire := io.dmem.perf.acquire
  io.core.perf.release := io.dmem.perf.release
  io.core.debug.ldq_head := ldq_head
  io.core.debug.ldq_tail := ldq_tail
  io.core.debug.stq_head := stq_head
  io.core.debug.stq_tail := stq_tail
  io.core.debug.stq_commit_head := stq_commit_head
  io.core.debug.stq_execute_head := stq_execute_head
  io.core.debug.ldq_head_valid := ldq(ldq_head).valid
  io.core.debug.ldq_head_addr_valid := ldq(ldq_head).bits.addr.valid
  io.core.debug.ldq_head_addr_is_virtual := ldq(ldq_head).bits.addr_is_virtual
  io.core.debug.ldq_head_addr_is_uncacheable := ldq(ldq_head).bits.addr_is_uncacheable
  io.core.debug.ldq_head_executed := ldq(ldq_head).bits.executed
  io.core.debug.ldq_head_succeeded := ldq(ldq_head).bits.succeeded
  io.core.debug.ldq_head_order_fail := ldq(ldq_head).bits.order_fail
  io.core.debug.ldq_head_forward_std := ldq(ldq_head).bits.forward_std_val
  io.core.debug.ldq_head_pc := ldq(ldq_head).bits.uop.debug_pc
  io.core.debug.ldq_head_rob_idx := ldq(ldq_head).bits.uop.rob_idx
  io.core.debug.ldq_head_addr := ldq(ldq_head).bits.addr.bits
  io.core.debug.stq_head_valid := stq(stq_head).valid
  io.core.debug.stq_head_addr_valid := stq(stq_head).bits.addr.valid
  io.core.debug.stq_head_data_valid := stq(stq_head).bits.data.valid
  io.core.debug.stq_head_committed := stq(stq_head).bits.committed
  io.core.debug.stq_head_succeeded := stq(stq_head).bits.succeeded
  io.core.debug.stq_head_addr_is_virtual := stq(stq_head).bits.addr_is_virtual
  io.core.debug.stq_head_pc := stq(stq_head).bits.uop.debug_pc
  io.core.debug.stq_head_rob_idx := stq(stq_head).bits.uop.rob_idx
  io.core.debug.stq_head_addr := stq(stq_head).bits.addr.bits
  io.core.debug.stq_execute_valid := stq(stq_execute_head).valid
  io.core.debug.stq_execute_addr_valid := stq(stq_execute_head).bits.addr.valid
  io.core.debug.stq_execute_data_valid := stq(stq_execute_head).bits.data.valid
  io.core.debug.stq_execute_committed := stq(stq_execute_head).bits.committed
  io.core.debug.stq_execute_succeeded := stq(stq_execute_head).bits.succeeded
  io.core.debug.stq_execute_addr_is_virtual := stq(stq_execute_head).bits.addr_is_virtual
  io.core.debug.stq_execute_pc := stq(stq_execute_head).bits.uop.debug_pc
  io.core.debug.stq_execute_rob_idx := stq(stq_execute_head).bits.uop.rob_idx
  io.core.debug.stq_execute_addr := stq(stq_execute_head).bits.addr.bits

  when (true.B) {
    printf(p"[LSU-QUEUE-HEARTBEAT] cycle=0x${Hexadecimal(io.core.tsc_reg)} stq_head=${stq_head} stq_tail=${stq_tail} stq_commit=${stq_commit_head} stq_exec=${stq_execute_head} head_v=${stq(stq_head).valid.asUInt} head_addr_v=${stq(stq_head).bits.addr.valid.asUInt} head_data_v=${stq(stq_head).bits.data.valid.asUInt} head_committed=${stq(stq_head).bits.committed.asUInt} head_succeeded=${stq(stq_head).bits.succeeded.asUInt} head_uopc=0x${Hexadecimal(stq(stq_head).bits.uop.uopc)} head_pc=0x${Hexadecimal(stq(stq_head).bits.uop.debug_pc)} exec_v=${stq(stq_execute_head).valid.asUInt} exec_addr_v=${stq(stq_execute_head).bits.addr.valid.asUInt} exec_data_v=${stq(stq_execute_head).bits.data.valid.asUInt} exec_committed=${stq(stq_execute_head).bits.committed.asUInt} exec_succeeded=${stq(stq_execute_head).bits.succeeded.asUInt}\n")
  }



  val clear_store     = WireInit(false.B)
  val live_store_mask = RegInit(0.U(numStqEntries.W))
  var next_live_store_mask = Mux(clear_store, live_store_mask & ~(1.U << stq_head),
                                              live_store_mask)


  def widthMap[T <: Data](f: Int => T) = VecInit((0 until memWidth).map(f))

  ///////////////////////////////////////////////////////////////////////////////////////////////////
  // Printing incomming LSU requests and responses
  //printf ("IncommingLSUReq: ")
  
  dtlb.io.log := io.log

  // Printing Cach Req & Resp
  // when (io.log)
  // {
  //   printf ("CacheReq: Address:%x, Data:%x\n", io.dmem.req.bits(0).bits.addr, io.dmem.req.bits(0).bits.data)
  // }

  // //printf ("DCacheReq: ")
  // for (i <- 0 until memWidth) {
  //   //when (io.dmem.req.bits(i).bits.uop.debug_pc(31,16) === 0x8000.U)
  //   when (false.B && io.log)
  //   {
  //     printf ("DCacheReq: PC:0x%x, Address:0x%x (%c), Data:0x%x, IsHella: (%c)\n", io.dmem.req.bits(i).bits.uop.debug_pc, io.dmem.req.bits(i).bits.addr,
  //       BoolToChar(io.dmem.req.bits(i).valid, 'V'), io.dmem.req.bits(i).bits.data, BoolToChar(io.dmem.req.bits(i).bits.is_hella, 'V'))
  //   }
  // }
  // //printf ("\n")
  // //printf ("DCacheResp: ")
  // for (i <- 0 until memWidth) {
  //   //when( io.dmem.resp(i).bits.uop.debug_pc(31,16) === 0x8000.U )
  //   when (io.log)
  //   {
  //     printf ("DCacheResp: PC:0x%x  Data:0x%x  (%c)  IsHella:  (%c)  Miss:  (%c)   Release:   (%c)\n", 
  //       io.dmem.resp(i).bits.uop.debug_pc, io.dmem.resp(i).bits.data, BoolToChar(io.dmem.resp(i).valid, 'V'), BoolToChar(io.dmem.resp(i).bits.is_hella, 'V'), BoolToChar(io.dmem.perf.acquire, 'V'), BoolToChar(io.dmem.perf.release, 'V'))
  //   }
  // }
  // //printf ("\n")

  // //printf ("DCacheNack: ")
  // for (i <- 0 until memWidth) {
  //   //  when (io.dmem.nack(i).bits.uop.debug_pc(31,16) === 0x8000.U)
  //   when (io.log)
  //   {
  //     printf ("DCacheNack: PC:0x%x  Data:0x%x  (%c)  IsHella:  (%c)\n", 
  //       io.dmem.nack(i).bits.uop.debug_pc, io.dmem.nack(i).bits.data, BoolToChar(io.dmem.nack(i).valid, 'V'), BoolToChar(io.dmem.nack(i).bits.is_hella, 'V'))
  //   }
  // }
  // //printf ("\n")


  // // Trying to print in a more understandable way!
  // // (%c)------> (LDQHeadorTail)
  // for (i <- 0 until numLdqEntries) {
  //   //when (ldq(i).bits.uop.debug_pc(31,16) === 0x8000.U)
  //   when (io.log)
  //   {
  //       printf ("LoadQueueEntry[%d]: PC:0x%x (%c) Address:0x%x (%c), TLBMiss:%c, Uncacheable:%c, Executed:%c, Succeeded:%c, OrderFail:%c, Observed:%c, STList:0x%x, STIdx:0x%x, STForwValid:%c, STForwIdx:0x%x \n", 
  //         i.U, ldq(i).bits.uop.debug_pc, Mux(ldq_head === i.U && ldq_tail === i.U, Str("B"),
  //           Mux(ldq_head === i.U, Str("H"),
  //             Mux(ldq_tail === i.U, Str("T"), Str(" ")))), ldq(i).bits.addr.bits, BoolToChar(ldq(i).bits.addr.valid, 'V'),
  //         Mux(ldq(i).bits.addr_is_virtual === 1.B, Str("T"), Str("F")), BoolToChar(ldq(i).bits.addr_is_uncacheable, 'T'), BoolToChar (ldq(i).bits.executed, 'T'), BoolToChar (ldq(i).bits.succeeded, 'T'), BoolToChar (ldq(i).bits.order_fail, 'T'), 
  //         BoolToChar (ldq(i).bits.observed, 'T'), ldq(i).bits.st_dep_mask , ldq(i).bits.youngest_stq_idx, BoolToChar (ldq(i).bits.forward_std_val, 'T'), 
  //         ldq(i).bits.forward_stq_idx)
  //   }
  // }


  // // This part is to print store queue
  // // (%c)(%c)(%c)------> (StQHeadorTail)(StQCommitHead)(StQExeHead)
  //     for (i <- 0 until numStqEntries) {
  //     //  when ( stq(i).bits.uop.debug_pc(31,16) === 0x8000.U)
  //       when (false.B && io.log)
  //       {
  //         printf ("StoreQueueEntry[%d]: PC:0x%x (%c)(%c)(%c) Address:0x%x (%c), TLBMiss:%c, Data:0x%x (%c), Committed:%c, Succeeded:%c \n",
  //         i.U, stq(i).bits.uop.debug_pc, Mux(stq_head === i.U && stq_tail === i.U, Str("B"),
  //             Mux(stq_head === i.U, Str("H"),
  //               Mux(stq_tail === i.U, Str("T"), Str(" ")))), Mux(stq_commit_head === i.U, Str("C"), Str(" ")), Mux(stq_execute_head === i.U, Str("E"), Str(" ")),
  //               stq(i).bits.addr.bits, BoolToChar(stq(i).bits.addr.valid, 'V'),
  //               BoolToChar(stq(i).bits.addr_is_virtual, 'T'), 
  //         stq(i).bits.data.bits ,  BoolToChar(stq(i).bits.data.valid,'V'),
  //         BoolToChar (stq(i).bits.committed, 'T'), BoolToChar (stq(i).bits.succeeded, 'T') )
  //       }

  //   }

  //printf("/////////////////////////////////////////////////////////////////////////////////////////////////////\n")

  
  //////////////////////////////////////////////////////////////////////////////////////////////////




  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Enqueue new entries
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // This is a newer store than existing loads, so clear the bit in all the store dependency masks
  for (i <- 0 until numLdqEntries)
  {
    when (clear_store)
    {
      ldq(i).bits.st_dep_mask := ldq(i).bits.st_dep_mask & ~(1.U << stq_head)
    }
  }

  // Decode stage
  var ld_enq_idx = ldq_tail
  var st_enq_idx = stq_tail

  val stq_nonempty = (0 until numStqEntries).map{ i => stq(i).valid }.reduce(_||_) =/= 0.U

  var ldq_full = Bool()
  var stq_full = Bool()


  for (w <- 0 until coreWidth)
  {
    ldq_full = WrapInc(ld_enq_idx, numLdqEntries) === ldq_head
    io.core.ldq_full(w)    := ldq_full
    io.core.dis_ldq_idx(w) := ld_enq_idx

    stq_full = WrapInc(st_enq_idx, numStqEntries) === stq_head
    io.core.stq_full(w)    := stq_full
    io.core.dis_stq_idx(w) := st_enq_idx


    val dis_ld_val = io.core.dis_uops(w).valid && io.core.dis_uops(w).bits.uses_ldq && !io.core.dis_uops(w).bits.exception
    val dis_st_val = io.core.dis_uops(w).valid && io.core.dis_uops(w).bits.uses_stq && !io.core.dis_uops(w).bits.exception
    when (dis_ld_val)
    {
      ldq(ld_enq_idx).valid                := true.B
      ldq(ld_enq_idx).bits.uop             := io.core.dis_uops(w).bits
      ldq(ld_enq_idx).bits.youngest_stq_idx  := st_enq_idx
      ldq(ld_enq_idx).bits.st_dep_mask     := next_live_store_mask

      ldq(ld_enq_idx).bits.addr.valid      := false.B
      ldq(ld_enq_idx).bits.original_addr   := 0.U
      ldq(ld_enq_idx).bits.executed        := false.B
      ldq(ld_enq_idx).bits.succeeded       := false.B
      ldq(ld_enq_idx).bits.order_fail      := false.B
      ldq(ld_enq_idx).bits.observed        := false.B
      ldq(ld_enq_idx).bits.forward_std_val := false.B

      ldq(ld_enq_idx).bits.c4_state           := 0.U
      
      assert (ld_enq_idx === io.core.dis_uops(w).bits.ldq_idx, "[lsu] mismatch enq load tag.")
      assert (!ldq(ld_enq_idx).valid, "[lsu] Enqueuing uop is overwriting ldq entries")
    }
      .elsewhen (dis_st_val)
    {
      stq(st_enq_idx).valid           := true.B
      stq(st_enq_idx).bits.uop        := io.core.dis_uops(w).bits
      stq(st_enq_idx).bits.addr.valid := false.B
      stq(st_enq_idx).bits.original_addr := 0.U
      stq(st_enq_idx).bits.data.valid := false.B
      stq(st_enq_idx).bits.committed  := false.B
      stq(st_enq_idx).bits.succeeded  := false.B

      stq(st_enq_idx).bits.c4_state      := 0.U
     
      assert (st_enq_idx === io.core.dis_uops(w).bits.stq_idx, "[lsu] mismatch enq store tag.")
      assert (!stq(st_enq_idx).valid, "[lsu] Enqueuing uop is overwriting stq entries")
    }

    ld_enq_idx = Mux(dis_ld_val, WrapInc(ld_enq_idx, numLdqEntries),
                                 ld_enq_idx)
    next_live_store_mask = Mux(dis_st_val, next_live_store_mask | (1.U << st_enq_idx),
                                           next_live_store_mask)
    st_enq_idx = Mux(dis_st_val, WrapInc(st_enq_idx, numStqEntries),
                                 st_enq_idx)
    assert(!(dis_ld_val && dis_st_val), "A UOP is trying to go into both the LDQ and the STQ")
  }

  ldq_tail := ld_enq_idx
  stq_tail := st_enq_idx

  io.dmem.force_order   := io.core.fence_dmem
  io.core.fencei_rdy    := !stq_nonempty && io.dmem.ordered


  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Execute stage (access TLB, send requests to Memory)
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // We can only report 1 exception per cycle.
  // Just be sure to report the youngest one
  val mem_xcpt_valid  = Wire(Bool())
  val mem_xcpt_cause  = Wire(UInt())
  val mem_xcpt_uop    = Wire(new MicroOp)
  val mem_xcpt_vaddr  = Wire(UInt())
  val mem_xcpt_original_vaddr = Wire(UInt())


  //---------------------------------------
  // Can-fire logic and wakeup/retry select
  //
  // First we determine what operations are waiting to execute.
  // These are the "can_fire"/"will_fire" signals

  val will_fire_load_incoming  = Wire(Vec(memWidth, Bool()))
  val will_fire_stad_incoming  = Wire(Vec(memWidth, Bool()))
  val will_fire_sta_incoming   = Wire(Vec(memWidth, Bool()))
  val will_fire_std_incoming   = Wire(Vec(memWidth, Bool()))
  val will_fire_sfence         = Wire(Vec(memWidth, Bool()))
  val will_fire_hella_incoming = Wire(Vec(memWidth, Bool()))
  val will_fire_hella_wakeup   = Wire(Vec(memWidth, Bool()))
  val will_fire_release        = Wire(Vec(memWidth, Bool()))
  val will_fire_load_retry     = Wire(Vec(memWidth, Bool()))
  val will_fire_sta_retry      = Wire(Vec(memWidth, Bool()))
  val will_fire_store_commit   = Wire(Vec(memWidth, Bool()))
  val will_fire_load_wakeup    = Wire(Vec(memWidth, Bool()))

  val exe_req = WireInit(VecInit(io.core.exe.map(_.req)))
  // Sfence goes through all pipes
  for (i <- 0 until memWidth) {
    when (io.core.exe(i).req.bits.sfence.valid) {
      exe_req := VecInit(Seq.fill(memWidth) { io.core.exe(i).req })
    }
  }

  // -------------------------------
  // Assorted signals for scheduling

  // Don't wakeup a load if we just sent it last cycle or two cycles ago
  // The block_load_mask may be wrong, but the executing_load mask must be accurate
  val block_load_mask    = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B)))
  val p1_block_load_mask = RegNext(block_load_mask)
  val p2_block_load_mask = RegNext(p1_block_load_mask)

 // Prioritize emptying the store queue when it is almost full
  val stq_almost_full = RegNext(WrapInc(WrapInc(st_enq_idx, numStqEntries), numStqEntries) === stq_head ||
                                WrapInc(st_enq_idx, numStqEntries) === stq_head)

  // The store at the commit head needs the DCache to appear ordered
  // Delay firing load wakeups and retries now
  val store_needs_order = WireInit(false.B)

  val ldq_incoming_idx = widthMap(i => exe_req(i).bits.uop.ldq_idx)
  val ldq_incoming_e   = widthMap(i => ldq(ldq_incoming_idx(i)))

  val stq_incoming_idx = widthMap(i => exe_req(i).bits.uop.stq_idx)
  val stq_incoming_e   = widthMap(i => stq(stq_incoming_idx(i)))

  val ldq_retry_idx = RegNext(AgePriorityEncoder((0 until numLdqEntries).map(i => {
    val e = ldq(i).bits
    val block = block_load_mask(i) || p1_block_load_mask(i)
    e.addr.valid && e.addr_is_virtual && !block
  }), ldq_head))
  val ldq_retry_e            = ldq(ldq_retry_idx)

  val stq_retry_idx = RegNext(AgePriorityEncoder((0 until numStqEntries).map(i => {
    val e = stq(i).bits
    e.addr.valid && e.addr_is_virtual
  }), stq_commit_head))
  val stq_retry_e   = stq(stq_retry_idx)

  val stq_commit_e  = stq(stq_execute_head)

  val ldq_wakeup_idx = RegNext(AgePriorityEncoder((0 until numLdqEntries).map(i=> {
    val e = ldq(i).bits
    val block = block_load_mask(i) || p1_block_load_mask(i)
    e.addr.valid && !e.executed && !e.succeeded && !e.addr_is_virtual && !block
  }), ldq_head))
  val ldq_wakeup_e   = ldq(ldq_wakeup_idx)

  // -----------------------
  // Determine what can fire

  // Can we fire a incoming load
  val can_fire_load_incoming = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_load)

  // Can we fire an incoming store addrgen + store datagen
  val can_fire_stad_incoming = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_sta
                                                              && exe_req(w).bits.uop.ctrl.is_std)
  // Can we fire an incoming store addrgen
  val can_fire_sta_incoming  = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_sta
                                                              && !exe_req(w).bits.uop.ctrl.is_std)

  // Can we fire an incoming store datagen
  val can_fire_std_incoming  = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_std
                                                              && !exe_req(w).bits.uop.ctrl.is_sta)

  // Can we fire an incoming sfence
  val can_fire_sfence        = widthMap(w => exe_req(w).valid && exe_req(w).bits.sfence.valid)

  // Can we fire a request from dcache to release a line
  // This needs to go through LDQ search to mark loads as dangerous
  val can_fire_release       = widthMap(w => (w == memWidth-1).B && io.dmem.release.valid)
  io.dmem.release.ready     := will_fire_release.reduce(_||_)

  // Can we retry a load that missed in the TLB
  val can_fire_load_retry    = widthMap(w =>
                               ( ldq_retry_e.valid                            &&
                                 ldq_retry_e.bits.addr.valid                  &&
                                 ldq_retry_e.bits.addr_is_virtual             &&
                                !p1_block_load_mask(ldq_retry_idx)            &&
                                !p2_block_load_mask(ldq_retry_idx)            &&
                                RegNext(dtlb.io.miss_rdy)                     &&
                                !store_needs_order                            &&
                                (w == memWidth-1).B                           && // TODO: Is this best scheduling?
                                !ldq_retry_e.bits.order_fail))

  // Can we retry a store addrgen that missed in the TLB
  // - Weird edge case when sta_retry and std_incoming for same entry in same cycle. Delay this
  val can_fire_sta_retry     = widthMap(w =>
                               ( stq_retry_e.valid                            &&
                                 stq_retry_e.bits.addr.valid                  &&
                                 stq_retry_e.bits.addr_is_virtual             &&
                                 (w == memWidth-1).B                          &&
                                 RegNext(dtlb.io.miss_rdy)                    &&
                                 !(widthMap(i => (i != w).B               &&
                                                 can_fire_std_incoming(i) &&
                                                 stq_incoming_idx(i) === stq_retry_idx).reduce(_||_))
                               ))
  // Can we commit a store
  val key_store_at_commit_head = stq_commit_e.valid && stq_commit_e.bits.uop.uopc === uop_store_key
  val key_store_commit_ready = key_store_at_commit_head &&
                               keyStoreActive &&
                               keyStoreStqIdx === stq_execute_head &&
                               !keyStoreWaitingResp
  val can_fire_store_commit  = widthMap(w =>
                               ( stq_commit_e.valid                           &&
                                !stq_commit_e.bits.uop.is_fence               &&
                                !mem_xcpt_valid                               &&
                                !stq_commit_e.bits.uop.exception              &&
                                (w == 0).B                                    &&
                                Mux(key_store_at_commit_head,
                                  stq_commit_e.bits.committed && key_store_commit_ready,
                                  stq_commit_e.bits.committed || ( stq_commit_e.bits.uop.is_amo      &&
                                                                   stq_commit_e.bits.addr.valid      &&
                                                                  !stq_commit_e.bits.addr_is_virtual &&
                                                                   stq_commit_e.bits.data.valid))))

  // Can we wakeup a load that was nack'd
  val block_load_wakeup = WireInit(false.B)
  val can_fire_load_wakeup = widthMap(w =>
                             ( ldq_wakeup_e.valid                                      &&
                               ldq_wakeup_e.bits.addr.valid                            &&
                              !ldq_wakeup_e.bits.succeeded                             &&
                              !ldq_wakeup_e.bits.addr_is_virtual                       &&
                              !ldq_wakeup_e.bits.executed                              &&
                              !ldq_wakeup_e.bits.order_fail                            &&
                              !p1_block_load_mask(ldq_wakeup_idx)                      &&
                              !p2_block_load_mask(ldq_wakeup_idx)                      &&
                              !store_needs_order                                       &&
                              !block_load_wakeup                                       &&
                              (w == memWidth-1).B                                      &&
                              (!ldq_wakeup_e.bits.addr_is_uncacheable || (io.core.commit_load_at_rob_head &&
                                                                          ldq_head === ldq_wakeup_idx &&
                                                                          ldq_wakeup_e.bits.st_dep_mask.asUInt === 0.U))))

  // Can we fire an incoming hellacache request
  val can_fire_hella_incoming  = WireInit(widthMap(w => false.B)) // This is assigned to in the hellashim ocntroller

  // Can we fire a hellacache request that the dcache nack'd
  val can_fire_hella_wakeup    = WireInit(widthMap(w => false.B)) // This is assigned to in the hellashim controller

  //---------------------------------------------------------
  // Controller logic. Arbitrate which request actually fires

  val exe_tlb_valid = Wire(Vec(memWidth, Bool()))
  for (w <- 0 until memWidth) {
    var tlb_avail  = true.B
    var dc_avail   = true.B
    var lcam_avail = true.B
    var rob_avail  = true.B

    def lsu_sched(can_fire: Bool, uses_tlb:Boolean, uses_dc:Boolean, uses_lcam: Boolean, uses_rob:Boolean): Bool = {
      val will_fire = can_fire && !(uses_tlb.B && !tlb_avail) &&
                                  !(uses_lcam.B && !lcam_avail) &&
                                  !(uses_dc.B && !dc_avail) &&
                                  !(uses_rob.B && !rob_avail)
      tlb_avail  = tlb_avail  && !(will_fire && uses_tlb.B)
      lcam_avail = lcam_avail && !(will_fire && uses_lcam.B)
      dc_avail   = dc_avail   && !(will_fire && uses_dc.B)
      rob_avail  = rob_avail  && !(will_fire && uses_rob.B)
      dontTouch(will_fire) // dontTouch these so we can inspect the will_fire signals
      will_fire
    }

    // The order of these statements is the priority
    // Some restrictions
    //  - Incoming ops must get precedence, can't backpresure memaddrgen
    //  - Incoming hellacache ops must get precedence over retrying ops (PTW must get precedence over retrying translation)
    // Notes on performance
    //  - Prioritize releases, this speeds up cache line writebacks and refills
    //  - Store commits are lowest priority, since they don't "block" younger instructions unless stq fills up
    will_fire_load_incoming (w) := lsu_sched(can_fire_load_incoming (w) , true , true , true , false) // TLB , DC , LCAM
    will_fire_stad_incoming (w) := lsu_sched(can_fire_stad_incoming (w) , true , false, true , true)  // TLB ,    , LCAM , ROB
    will_fire_sta_incoming  (w) := lsu_sched(can_fire_sta_incoming  (w) , true , false, true , true)  // TLB ,    , LCAM , ROB
    will_fire_std_incoming  (w) := lsu_sched(can_fire_std_incoming  (w) , false, false, false, true)  //                 , ROB
    will_fire_sfence        (w) := lsu_sched(can_fire_sfence        (w) , true , false, false, true)  // TLB ,    ,      , ROB
    will_fire_release       (w) := lsu_sched(can_fire_release       (w) , false, false, true , false) //            LCAM
    will_fire_hella_incoming(w) := lsu_sched(can_fire_hella_incoming(w) , true , true , false, false) // TLB , DC
    will_fire_hella_wakeup  (w) := lsu_sched(can_fire_hella_wakeup  (w) , false, true , false, false) //     , DC
    will_fire_load_retry    (w) := lsu_sched(can_fire_load_retry    (w) , true , true , true , false) // TLB , DC , LCAM
    will_fire_sta_retry     (w) := lsu_sched(can_fire_sta_retry     (w) , true , false, true , true)  // TLB ,    , LCAM , ROB // TODO: This should be higher priority
    will_fire_load_wakeup   (w) := lsu_sched(can_fire_load_wakeup   (w) , false, true , true , false) //     , DC , LCAM1
    will_fire_store_commit  (w) := lsu_sched(can_fire_store_commit  (w) , false, true , false, false) //     , DC

    

    assert(!(exe_req(w).valid && !(will_fire_load_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_incoming(w) || will_fire_std_incoming(w) || will_fire_sfence(w))))

    when (will_fire_load_wakeup(w)) {
      block_load_mask(ldq_wakeup_idx)           := true.B
    } .elsewhen (will_fire_load_incoming(w)) {
      block_load_mask(exe_req(w).bits.uop.ldq_idx) := true.B
    } .elsewhen (will_fire_load_retry(w)) {
      block_load_mask(ldq_retry_idx)            := true.B
    }
    exe_tlb_valid(w) := !tlb_avail
  }
  assert((memWidth == 1).B ||
    (!(will_fire_sfence.reduce(_||_) && !will_fire_sfence.reduce(_&&_)) &&
     !will_fire_hella_incoming.reduce(_&&_) &&
     !will_fire_hella_wakeup.reduce(_&&_)   &&
     !will_fire_load_retry.reduce(_&&_)     &&
     !will_fire_sta_retry.reduce(_&&_)      &&
     !will_fire_store_commit.reduce(_&&_)   &&
     !will_fire_load_wakeup.reduce(_&&_)),
    "Some operations is proceeding down multiple pipes")

  require(memWidth <= 2)

  //--------------------------------------------
  // TLB Access

  assert(!(hella_state =/= h_ready && hella_req.cmd === rocket.M_SFENCE),
    "SFENCE through hella interface not supported")

  val exe_tlb_uop = widthMap(w =>
                    Mux(will_fire_load_incoming (w) ||
                        will_fire_stad_incoming (w) ||
                        will_fire_sta_incoming  (w) ||
                        will_fire_sfence        (w)  , exe_req(w).bits.uop,
                    Mux(will_fire_load_retry    (w)  , ldq_retry_e.bits.uop,
                    Mux(will_fire_sta_retry     (w)  , stq_retry_e.bits.uop,
                    Mux(will_fire_hella_incoming(w)  , NullMicroOp,
                                                       NullMicroOp)))))

  val exe_tlb_vaddr = widthMap(w =>
                    Mux(will_fire_load_incoming (w) ||
                        will_fire_stad_incoming (w) ||
                        will_fire_sta_incoming  (w)  , exe_req(w).bits.addr,
                    Mux(will_fire_sfence        (w)  , exe_req(w).bits.sfence.bits.addr,
                    Mux(will_fire_load_retry    (w)  , ldq_retry_e.bits.addr.bits,
                    Mux(will_fire_sta_retry     (w)  , stq_retry_e.bits.addr.bits,
                    Mux(will_fire_hella_incoming(w)  , hella_req.addr,
                                                       0.U))))))
  
  val exe_origin_vaddr =  widthMap(w =>
                    Mux(will_fire_load_incoming (w) ||
                        will_fire_stad_incoming (w) ||
                        will_fire_sta_incoming  (w)  , exe_req(w).bits.origin_addr,
                    // Mux(will_fire_sfence        (w)  , exe_req(w).bits.sfence.bits.addr,
                    Mux(will_fire_load_retry    (w)  , ldq_retry_e.bits.original_addr,
                    Mux(will_fire_sta_retry     (w)  , stq_retry_e.bits.original_addr,
                                                       0.U))))

  val exe_sfence = WireInit((0.U).asTypeOf(Valid(new rocket.SFenceReq)))
  for (w <- 0 until memWidth) {
    when (will_fire_sfence(w)) {
      exe_sfence := exe_req(w).bits.sfence
    }
  }

  val exe_size   = widthMap(w =>
                   Mux(will_fire_load_incoming (w) ||
                       will_fire_stad_incoming (w) ||
                       will_fire_sta_incoming  (w) ||
                       will_fire_sfence        (w) ||
                       will_fire_load_retry    (w) ||
                       will_fire_sta_retry     (w)  , exe_tlb_uop(w).mem_size,
                   Mux(will_fire_hella_incoming(w)  , hella_req.size,
                                                      0.U)))
  val exe_cmd    = widthMap(w =>
                   Mux(will_fire_load_incoming (w) ||
                       will_fire_stad_incoming (w) ||
                       will_fire_sta_incoming  (w) ||
                       will_fire_sfence        (w) ||
                       will_fire_load_retry    (w) ||
                       will_fire_sta_retry     (w)  , exe_tlb_uop(w).mem_cmd,
                   Mux(will_fire_hella_incoming(w)  , hella_req.cmd,
                                                      0.U)))

  val exe_passthr= widthMap(w =>
                   Mux(will_fire_hella_incoming(w)  , hella_req.phys,
                                                      false.B))
  val exe_kill   = widthMap(w =>
                   Mux(will_fire_hella_incoming(w)  , io.hellacache.s1_kill,
                                                      false.B))
  for (w <- 0 until memWidth) {
    dtlb.io.req(w).valid            := exe_tlb_valid(w)
    dtlb.io.req(w).bits.vaddr       := exe_tlb_vaddr(w)
    dtlb.io.req(w).bits.size        := exe_size(w)
    dtlb.io.req(w).bits.cmd         := exe_cmd(w)
    dtlb.io.req(w).bits.passthrough := exe_passthr(w)
    dtlb.io.req(w).bits.v           := io.ptw.status.v
    dtlb.io.req(w).bits.prv         := io.ptw.status.prv
    dtlb.io.req(w).bits.uop         := exe_tlb_uop(w)
    when (dtlb.io.req(w).valid &&
          dtlb.io.req(w).bits.uop.debug_pc === "h80000a2c".U) {
      chisel3.printf("[LSU-PROBE-PC] lane=%d pc=0x%x inst=0x%x vaddr=0x%x cmd=0x%x size=%d prv=%d ptbr_mode=0x%x ptbr_ppn=0x%x mprv=%d dprv=%d passthrough=%d exe_passthr=%d fire_li=%d fire_stad=%d fire_sta=%d fire_sfence=%d fire_lretry=%d fire_sretry=%d fire_hella=%d fire_hwakeup=%d hella_state=%d hella_addr=0x%x hella_phys=%d req_ready=%d ldq_retry_idx=%d ldq_retry_valid=%d ldq_retry_addr_valid=%d ldq_retry_addr=0x%x ldq_retry_virtual=%d ldq_retry_exec=%d ldq_retry_succ=%d ldq_retry_order_fail=%d ldq_retry_pc=0x%x\n",
        w.U,
        dtlb.io.req(w).bits.uop.debug_pc,
        dtlb.io.req(w).bits.uop.debug_inst,
        dtlb.io.req(w).bits.vaddr,
        dtlb.io.req(w).bits.cmd,
        dtlb.io.req(w).bits.size,
        dtlb.io.req(w).bits.prv,
        io.ptw.ptbr.mode,
        io.ptw.ptbr.ppn,
        io.ptw.status.mprv,
        io.ptw.status.dprv,
        dtlb.io.req(w).bits.passthrough,
        exe_passthr(w),
        will_fire_load_incoming(w),
        will_fire_stad_incoming(w),
        will_fire_sta_incoming(w),
        will_fire_sfence(w),
        will_fire_load_retry(w),
        will_fire_sta_retry(w),
        will_fire_hella_incoming(w),
        will_fire_hella_wakeup(w),
        hella_state,
        hella_req.addr,
        hella_req.phys,
        dtlb.io.req(w).ready,
        ldq_retry_idx,
        ldq_retry_e.valid,
        ldq_retry_e.bits.addr.valid,
        ldq_retry_e.bits.addr.bits,
        ldq_retry_e.bits.addr_is_virtual,
        ldq_retry_e.bits.executed,
        ldq_retry_e.bits.succeeded,
        ldq_retry_e.bits.order_fail,
        ldq_retry_e.bits.uop.debug_pc)
    }
    when (dtlb.io.req(w).valid &&
          io.ptw.status.mprv &&
          io.ptw.status.dprv === PRV.U.U &&
          (dtlb.io.req(w).bits.vaddr(vaddrBitsExtended-1, pgIdxBits) === "h2e7b8ad".U ||
           dtlb.io.req(w).bits.vaddr(vaddrBitsExtended-1, pgIdxBits) === "h40000".U)) {
      chisel3.printf("[LSU-PROBE-DTLB-REQ] lane=%d pc=0x%x inst=0x%x vaddr=0x%x cmd=0x%x size=%d prv=%d mprv=%d dprv=%d passthrough=%d exe_passthr=%d hella_state=%d hella_req_valid_addr=0x%x hella_req_phys=%d fire_li=%d fire_lretry=%d fire_hella=%d fire_hwakeup=%d req_ready=%d\n",
        w.U,
        dtlb.io.req(w).bits.uop.debug_pc,
        dtlb.io.req(w).bits.uop.debug_inst,
        dtlb.io.req(w).bits.vaddr,
        dtlb.io.req(w).bits.cmd,
        dtlb.io.req(w).bits.size,
        dtlb.io.req(w).bits.prv,
        io.ptw.status.mprv,
        io.ptw.status.dprv,
        dtlb.io.req(w).bits.passthrough,
        exe_passthr(w),
        hella_state,
        hella_req.addr,
        hella_req.phys,
        will_fire_load_incoming(w),
        will_fire_load_retry(w),
        will_fire_hella_incoming(w),
        will_fire_hella_wakeup(w),
        dtlb.io.req(w).ready)
    }
  }
  dtlb.io.kill                      := exe_kill.reduce(_||_)
  dtlb.io.sfence                    := exe_sfence

  // exceptions
  val ma_ld = widthMap(w => will_fire_load_incoming(w) && exe_req(w).bits.mxcpt.valid) // We get ma_ld in memaddrcalc
  val ma_st = widthMap(w => (will_fire_sta_incoming(w) || will_fire_stad_incoming(w)) && exe_req(w).bits.mxcpt.valid) // We get ma_ld in memaddrcalc
  val pf_ld = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).pf.ld && exe_tlb_uop(w).uses_ldq)
  val pf_st = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).pf.st && exe_tlb_uop(w).uses_stq)
  val ae_ld = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).ae.ld && exe_tlb_uop(w).uses_ldq)
  val ae_st = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).ae.st && exe_tlb_uop(w).uses_stq)
  // TODO check for xcpt_if and verify that never happens on non-speculative instructions.
  val mem_xcpt_valids = RegNext(widthMap(w =>
                     (pf_ld(w) || pf_st(w) || ae_ld(w) || ae_st(w) || ma_ld(w) || ma_st(w)) &&
                     !io.core.exception &&
                     !IsKilledByBranch(io.core.brupdate, exe_tlb_uop(w))))
  val mem_xcpt_uops   = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, exe_tlb_uop(w))))
  val mem_xcpt_causes = RegNext(widthMap(w =>
    Mux(ma_ld(w), rocket.Causes.misaligned_load.U,
    Mux(ma_st(w), rocket.Causes.misaligned_store.U,
    Mux(pf_ld(w), rocket.Causes.load_page_fault.U,
    Mux(pf_st(w), rocket.Causes.store_page_fault.U,
    Mux(ae_ld(w), rocket.Causes.load_access.U,
                  rocket.Causes.store_access.U)))))))
  val mem_xcpt_vaddrs = RegNext(exe_tlb_vaddr)
  val mem_xcpt_original_vaddrs = RegNext(exe_origin_vaddr)

  for (w <- 0 until memWidth) {
    assert (!(dtlb.io.req(w).valid && exe_tlb_uop(w).is_fence), "Fence is pretending to talk to the TLB")
    assert (!((will_fire_load_incoming(w) || will_fire_sta_incoming(w) || will_fire_stad_incoming(w)) &&
      exe_req(w).bits.mxcpt.valid && dtlb.io.req(w).valid &&
    !(exe_tlb_uop(w).ctrl.is_load || exe_tlb_uop(w).ctrl.is_sta)),
      "A uop that's not a load or store-address is throwing a memory exception.")
  }

  mem_xcpt_valid := mem_xcpt_valids.reduce(_||_)
  mem_xcpt_cause := mem_xcpt_causes(0)
  mem_xcpt_uop   := mem_xcpt_uops(0)
  mem_xcpt_vaddr := mem_xcpt_vaddrs(0)
  mem_xcpt_original_vaddr := mem_xcpt_original_vaddrs(0)
  var xcpt_found = mem_xcpt_valids(0)
  var oldest_xcpt_rob_idx = mem_xcpt_uops(0).rob_idx
  for (w <- 1 until memWidth) {
    val is_older = WireInit(false.B)
    when (mem_xcpt_valids(w) &&
      (IsOlder(mem_xcpt_uops(w).rob_idx, oldest_xcpt_rob_idx, io.core.rob_head_idx) || !xcpt_found)) {
      is_older := true.B
      mem_xcpt_cause := mem_xcpt_causes(w)
      mem_xcpt_uop   := mem_xcpt_uops(w)
      mem_xcpt_vaddr := mem_xcpt_vaddrs(w)
      mem_xcpt_original_vaddr := mem_xcpt_original_vaddrs(w)
    }
    xcpt_found = xcpt_found || mem_xcpt_valids(w)
    oldest_xcpt_rob_idx = Mux(is_older, mem_xcpt_uops(w).rob_idx, oldest_xcpt_rob_idx)
  }

  val exe_tlb_miss  = widthMap(w => dtlb.io.req(w).valid && (dtlb.io.resp(w).miss || !dtlb.io.req(w).ready))
  val exe_tlb_paddr = widthMap(w => Cat(dtlb.io.resp(w).paddr(paddrBits-1,corePgIdxBits),
                                        exe_tlb_vaddr(w)(corePgIdxBits-1,0)))
  val exe_tlb_uncacheable = widthMap(w => !(dtlb.io.resp(w).cacheable))

  for (w <- 0 until memWidth) {
    assert (exe_tlb_paddr(w) === dtlb.io.resp(w).paddr || exe_req(w).bits.sfence.valid, "[lsu] paddrs should match.")

    when (mem_xcpt_valids(w))
    {
      assert(RegNext(will_fire_load_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_incoming(w) ||
        will_fire_load_retry(w) || will_fire_sta_retry(w)))
      // Technically only faulting AMOs need this
      assert(mem_xcpt_uops(w).uses_ldq ^ mem_xcpt_uops(w).uses_stq)
      when (mem_xcpt_uops(w).uses_ldq)
      {
        ldq(mem_xcpt_uops(w).ldq_idx).bits.uop.exception := true.B
      }
        .otherwise
      {
        stq(mem_xcpt_uops(w).stq_idx).bits.uop.exception := true.B
      }
    }
  }



  //------------------------------
  // Issue Someting to Memory
  //
  // A memory op can come from many different places
  // The address either was freshly translated, or we are
  // reading a physical address from the LDQ,STQ, or the HellaCache adapter


  // defaults
  io.dmem.brupdate         := io.core.brupdate
  io.dmem.exception      := io.core.exception
  io.dmem.rob_head_idx   := io.core.rob_head_idx
  io.dmem.rob_pnr_idx    := io.core.rob_pnr_idx

  val dmem_req = Wire(Vec(memWidth, Valid(new BoomDCacheReq)))
  io.dmem.req.valid := dmem_req.map(_.valid).reduce(_||_)
  io.dmem.req.bits  := dmem_req
  val dmem_req_fire = widthMap(w => dmem_req(w).valid && io.dmem.req.fire)

  val s0_executing_loads = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B)))


  for (w <- 0 until memWidth) {
    dmem_req(w).valid := false.B
    dmem_req(w).bits.uop   := NullMicroOp
    dmem_req(w).bits.addr  := 0.U
    dmem_req(w).bits.data  := 0.U
    dmem_req(w).bits.is_hella := false.B

    io.dmem.s1_kill(w) := false.B

    when (will_fire_load_incoming(w)) {
      dmem_req(w).valid      := !exe_tlb_miss(w) && !exe_tlb_uncacheable(w)
      dmem_req(w).bits.addr  := exe_tlb_paddr(w)
      dmem_req(w).bits.uop   := exe_tlb_uop(w)
      s0_executing_loads(ldq_incoming_idx(w)) := dmem_req_fire(w)
      assert(!ldq_incoming_e(w).bits.executed)
    } .elsewhen (will_fire_load_retry(w)) {
      dmem_req(w).valid      := !exe_tlb_miss(w) && !exe_tlb_uncacheable(w)
      dmem_req(w).bits.addr  := exe_tlb_paddr(w)
      dmem_req(w).bits.uop   := exe_tlb_uop(w)
      s0_executing_loads(ldq_retry_idx) := dmem_req_fire(w)
      assert(!ldq_retry_e.bits.executed)
    } .elsewhen (will_fire_store_commit(w)) {
      val store_addr = keyStoreBaseAddr + (keyStoreBeat << 3)
      dmem_req(w).valid         := true.B
      dmem_req(w).bits.addr     := Mux(stq_commit_e.bits.uop.uopc === uop_store_key,
                                    store_addr,
                                    stq_commit_e.bits.addr.bits)
      dmem_req(w).bits.data     := Mux(stq_commit_e.bits.uop.uopc === uop_store_key,
                                    (new freechips.rocketchip.rocket.StoreGen(
                                      stq_commit_e.bits.uop.mem_size, store_addr,
                                      io.store_key_resp.bits(keyStoreBeat),
                                      coreDataBytes)).data,
                                    (new freechips.rocketchip.rocket.StoreGen(
                                      stq_commit_e.bits.uop.mem_size, 0.U,
                                      stq_commit_e.bits.data.bits,
                                      coreDataBytes)).data)
      // dmem_req(w).bits.data     :=  Mux( io.cus_reg(1) === 1.U ,stq_commit_e.bits.data.bits , (new freechips.rocketchip.rocket.StoreGen(
      //                               stq_commit_e.bits.uop.mem_size, 0.U,
      //                               stq_commit_e.bits.data.bits,
      //                               coreDataBytes)).data)
      dmem_req(w).bits.uop      := stq_commit_e.bits.uop
	      when (stq_commit_e.bits.uop.uopc === uop_store_key) {
	        when (dmem_req_fire(w)) {
	          keyStoreWaitingResp := true.B
	          stq(stq_execute_head).bits.succeeded := false.B
	          io.store_key_req := true.B
	          chisel3.printf("[RTL-KEY-STORE] cycle=0x%x lane=%d beat=%d base=0x%x addr=0x%x key_word=0x%x rob=%d pc=0x%x prv=%d dprv=%d\n",
	            io.core.tsc_reg,
	            w.U,
	            keyStoreBeat,
	            keyStoreBaseAddr,
	            store_addr,
	            io.store_key_resp.bits(keyStoreBeat),
	            stq_commit_e.bits.uop.rob_idx,
	            stq_commit_e.bits.uop.debug_pc,
	            io.ptw.status.prv,
	            io.ptw.status.dprv)
	        }
	      } .otherwise {
        stq_execute_head                     := Mux(dmem_req_fire(w),
                                                  WrapInc(stq_execute_head, numStqEntries),
                                                  stq_execute_head)

        stq(stq_execute_head).bits.succeeded := false.B
      }
    } .elsewhen (will_fire_load_wakeup(w)) {
      dmem_req(w).valid      := true.B
      dmem_req(w).bits.addr  := ldq_wakeup_e.bits.addr.bits
      dmem_req(w).bits.uop   := ldq_wakeup_e.bits.uop

      s0_executing_loads(ldq_wakeup_idx) := dmem_req_fire(w)

      assert(!ldq_wakeup_e.bits.executed && !ldq_wakeup_e.bits.addr_is_virtual)
    } .elsewhen (will_fire_hella_incoming(w)) {
      assert(hella_state === h_s1)

      dmem_req(w).valid               := !io.hellacache.s1_kill && (!exe_tlb_miss(w) || hella_req.phys)
      dmem_req(w).bits.addr           := exe_tlb_paddr(w)
      // dmem_req(w).bits.data           := Mux( io.cus_reg(1) === 1.U, io.hellacache.s1_data.data , (new freechips.rocketchip.rocket.StoreGen(
       dmem_req(w).bits.data           := (new freechips.rocketchip.rocket.StoreGen(
        hella_req.size, 0.U,
        io.hellacache.s1_data.data,
        coreDataBytes)).data
      dmem_req(w).bits.uop.mem_cmd    := hella_req.cmd
      dmem_req(w).bits.uop.mem_size   := hella_req.size
      dmem_req(w).bits.uop.mem_signed := hella_req.signed
      dmem_req(w).bits.is_hella       := true.B

      hella_paddr := exe_tlb_paddr(w)
    }
      .elsewhen (will_fire_hella_wakeup(w))
    {
      assert(hella_state === h_replay)
      dmem_req(w).valid               := true.B
      dmem_req(w).bits.addr           := hella_paddr
      // dmem_req(w).bits.data           := Mux( io.cus_reg(1) === 1.U , hella_data.data, (new freechips.rocketchip.rocket.StoreGen(
      dmem_req(w).bits.data           := (new freechips.rocketchip.rocket.StoreGen(
        hella_req.size, 0.U,
        hella_data.data, 
        coreDataBytes)).data 
      dmem_req(w).bits.uop.mem_cmd    := hella_req.cmd
      dmem_req(w).bits.uop.mem_size   := hella_req.size
      dmem_req(w).bits.uop.mem_signed := hella_req.signed
      dmem_req(w).bits.is_hella       := true.B
    }

    when (dmem_req_fire(w) && dmem_req(w).bits.uop.uopc === uop_load_key) {
      when (!keyLoadActive) {
        keyLoadActive := true.B
        keyLoadLdqIdx := dmem_req(w).bits.uop.ldq_idx
        keyLoadBeat := 0.U
        keyLoadBaseAddr := dmem_req(w).bits.addr
      }
    }

    //-------------------------------------------------------------
    // Write Addr into the LAQ/SAQ
    when (will_fire_load_incoming(w) || will_fire_load_retry(w))
    {
      val ldq_idx = Mux(will_fire_load_incoming(w), ldq_incoming_idx(w), ldq_retry_idx)
      val ld_addr = Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w))
      when (exe_tlb_uop(w).debug_pc === "h80000a2c".U) {
        chisel3.printf("[LSU-PROBE-LDQ-WRITE] lane=%d src_in=%d src_retry=%d ldq_idx=%d tlb_miss=%d vaddr=0x%x paddr=0x%x ld_addr=0x%x ptbr_mode=0x%x ptbr_ppn=0x%x prv=%d mprv=%d dprv=%d old_valid=%d old_addr=0x%x old_virtual=%d old_exec=%d old_succ=%d\n",
          w.U,
          will_fire_load_incoming(w),
          will_fire_load_retry(w),
          ldq_idx,
          exe_tlb_miss(w),
          exe_tlb_vaddr(w),
          exe_tlb_paddr(w),
          ld_addr,
          io.ptw.ptbr.mode,
          io.ptw.ptbr.ppn,
          io.ptw.status.prv,
          io.ptw.status.mprv,
          io.ptw.status.dprv,
          ldq(ldq_idx).bits.addr.valid,
          ldq(ldq_idx).bits.addr.bits,
          ldq(ldq_idx).bits.addr_is_virtual,
          ldq(ldq_idx).bits.executed,
          ldq(ldq_idx).bits.succeeded)
      }
      ldq(ldq_idx).bits.addr.valid          := true.B
      ldq(ldq_idx).bits.addr.bits           := ld_addr
      ldq(ldq_idx).bits.original_addr       := exe_origin_vaddr(w)
      ldq(ldq_idx).bits.uop.pdst            := exe_tlb_uop(w).pdst
      ldq(ldq_idx).bits.uop.ctrl            := exe_tlb_uop(w).ctrl
      ldq(ldq_idx).bits.addr_is_virtual     := exe_tlb_miss(w)
      ldq(ldq_idx).bits.addr_is_uncacheable := exe_tlb_uncacheable(w) && !exe_tlb_miss(w)
      assert(!(will_fire_load_incoming(w) && ldq_incoming_e(w).bits.addr.valid),
        "[lsu] Incoming load is overwriting a valid address")
    }

    when (will_fire_sta_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_retry(w))
    {
      val stq_idx = Mux(will_fire_sta_incoming(w) || will_fire_stad_incoming(w),
        stq_incoming_idx(w), stq_retry_idx)
      val st_addr = Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w))
     
      stq(stq_idx).bits.addr.valid := !pf_st(w) // Prevent AMOs from executing!
      stq(stq_idx).bits.addr.bits  := st_addr
      stq(stq_idx).bits.original_addr := exe_origin_vaddr(w)
      stq(stq_idx).bits.uop.pdst   := exe_tlb_uop(w).pdst // Needed for AMOs
      stq(stq_idx).bits.addr_is_virtual := exe_tlb_miss(w)
      stq(stq_idx).bits.uop.ctrl   := exe_tlb_uop(w).ctrl 
      when (exe_tlb_uop(w).uopc === uop_store_key && !exe_tlb_miss(w) && !pf_st(w)) {
        keyStoreActive := true.B
        keyStoreStqIdx := stq_idx
        keyStoreBeat := 0.U
        keyStoreBaseAddr := st_addr
        keyStoreWaitingResp := false.B
      }
      assert(!(will_fire_sta_incoming(w) && stq_incoming_e(w).bits.addr.valid),
        "[lsu] Incoming store is overwriting a valid address")

    }

    //-------------------------------------------------------------
    // Write data into the STQ
    if (w == 0)
      io.core.fp_stdata.ready := !will_fire_std_incoming(w) && !will_fire_stad_incoming(w)
    val fp_stdata_fire = io.core.fp_stdata.fire && (w == 0).B
    when (will_fire_std_incoming(w) || will_fire_stad_incoming(w) || fp_stdata_fire)
    {
      val sidx = Mux(will_fire_std_incoming(w) || will_fire_stad_incoming(w),
        stq_incoming_idx(w),
        io.core.fp_stdata.bits.uop.stq_idx)
      val std_uop = Mux(will_fire_std_incoming(w) || will_fire_stad_incoming(w),
        exe_req(w).bits.uop,
        io.core.fp_stdata.bits.uop)
      val std_data = Mux(will_fire_std_incoming(w) || will_fire_stad_incoming(w),
        exe_req(w).bits.data,
        io.core.fp_stdata.bits.data)
      stq(sidx).bits.data.valid := true.B
      stq(sidx).bits.data.bits  := std_data
      assert(!(stq(sidx).bits.data.valid),
        "[lsu] Incoming store is overwriting a valid data entry")
    }
  }
  val will_fire_stdf_incoming = io.core.fp_stdata.fire
  require (xLen >= fLen) // for correct SDQ size

  io.lsu_addr_resp(0).ready := true.B


  when(io.lsu_addr_resp(0).valid)
  {
    when(io.lsu_addr_resp(0).bits.uop.uses_ldq )
    {
      val ldq_idx = io.lsu_addr_resp(0).bits.uop.ldq_idx
      val ldq_owner_match =
        ldq(ldq_idx).valid &&
        (ldq(ldq_idx).bits.uop.rob_idx === io.lsu_addr_resp(0).bits.uop.rob_idx) &&
        (ldq(ldq_idx).bits.uop.debug_pc === io.lsu_addr_resp(0).bits.uop.debug_pc)
     
      when (ldq_owner_match) {
        when (io.lsu_addr_resp(0).bits.uop.debug_pc === "h80000a2c".U) {
          chisel3.printf("[LSU-PROBE-ASYNC-LDQ0] ldq_idx=%d addr=0x%x old_addr=0x%x old_valid=%d owner_match=%d ptbr_mode=0x%x ptbr_ppn=0x%x prv=%d mprv=%d dprv=%d\n",
            ldq_idx,
            io.lsu_addr_resp(0).bits.addr,
            ldq(ldq_idx).bits.addr.bits,
            ldq(ldq_idx).bits.addr.valid,
            ldq_owner_match,
            io.ptw.ptbr.mode,
            io.ptw.ptbr.ppn,
            io.ptw.status.prv,
            io.ptw.status.mprv,
            io.ptw.status.dprv)
        }
        ldq(ldq_idx).bits.addr.bits := io.lsu_addr_resp(0).bits.addr
        ldq(ldq_idx).bits.original_addr := io.lsu_addr_resp(0).bits.origin_addr
        ldq(ldq_idx).bits.addr.valid := true.B
        ldq(ldq_idx).bits.uop.pdst := io.lsu_addr_resp(0).bits.uop.pdst
        ldq(ldq_idx).bits.addr_is_virtual := true.B
        ldq(ldq_idx).bits.addr_is_uncacheable := false.B
        ldq(ldq_idx).bits.c4_state := 1.U
        ldq(ldq_idx).bits.uop.ctrl := io.lsu_addr_resp(0).bits.uop.ctrl
      }.otherwise {
      }
      // ldq(ldq_idx).bits.addr.valid          := true.B
      // ldq(ldq_idx).bits.addr.bits           := Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w))
      // ldq(ldq_idx).bits.uop.pdst            := exe_tlb_uop(w).pdst
      // ldq(ldq_idx).bits.addr_is_virtual     := exe_tlb_miss(w)

    }.elsewhen( io.lsu_addr_resp(0).bits.uop.uses_stq){
      // stq(io.lsu_addr_resp.bits.uop.stq_idx).bits.addr.bits := io.lsu_addr_resp.bits.addr
      // stq(io.lsu_addr_resp.bits.uop.stq_idx).bits.addr.valid := true.B

      val stq_idx = io.lsu_addr_resp(0).bits.uop.stq_idx
      assert(io.lsu_addr_resp(0).bits.uop.ctrl.is_sta,
        "[lsu] async store addr response must come from STA/STAD, not pure STD")
      assert(!io.lsu_addr_resp(0).bits.data_valid || io.lsu_addr_resp(0).bits.uop.ctrl.is_std,
        "[lsu] async store addr response carries data only for STAD")
      val stq_owner_match =
        stq(stq_idx).valid &&
        (stq(stq_idx).bits.uop.rob_idx === io.lsu_addr_resp(0).bits.uop.rob_idx) &&
        (stq(stq_idx).bits.uop.debug_pc === io.lsu_addr_resp(0).bits.uop.debug_pc)
      when (stq_owner_match) {
        stq(stq_idx).bits.addr.bits := io.lsu_addr_resp(0).bits.addr
        stq(stq_idx).bits.original_addr := io.lsu_addr_resp(0).bits.origin_addr
        stq(stq_idx).bits.addr.valid := true.B
        stq(stq_idx).bits.uop.pdst := io.lsu_addr_resp(0).bits.uop.pdst
        stq(stq_idx).bits.uop.ctrl := io.lsu_addr_resp(0).bits.uop.ctrl
        stq(stq_idx).bits.addr_is_virtual := true.B
        stq(stq_idx).bits.c4_state    := 1.U
        when (io.lsu_addr_resp(0).bits.data_valid) {
          stq(stq_idx).bits.data.valid := true.B
          stq(stq_idx).bits.data.bits := io.lsu_addr_resp(0).bits.data
        }
      }.otherwise {
      }
      // stq(stq_idx).bits.addr.valid := !pf_st(w) // Prevent AMOs from executing!
      // stq(stq_idx).bits.addr.bits  := Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w))
      // stq(stq_idx).bits.uop.pdst   := exe_tlb_uop(w).pdst // Needed for AMOs
      // stq(stq_idx).bits.addr_is_virtual := exe_tlb_miss(w)
    }  
  }

 // 现在的写法与 fault handler不是很兼容，如果真得要兼容，需要原来的写法 ，然后 state 扩展保持不变
  io.lsu_addr_resp(1).ready := true.B

  when (io.lsu_addr_resp(1).valid)
  {
    // val cond3 = !(will_fire_sta_retry.reduce(_ || _) && (stq_retry_e.bits.uop.stq_idx === io.lsu_addr_resp.bits.uop.stq_idx))
    when(io.lsu_addr_resp(1).bits.uop.uses_ldq )
    {
      val ldq_idx = io.lsu_addr_resp(1).bits.uop.ldq_idx
      val ldq_owner_match =
        ldq(ldq_idx).valid &&
        (ldq(ldq_idx).bits.uop.rob_idx === io.lsu_addr_resp(1).bits.uop.rob_idx) &&
        (ldq(ldq_idx).bits.uop.debug_pc === io.lsu_addr_resp(1).bits.uop.debug_pc)
     
      when (ldq_owner_match) {
        when (io.lsu_addr_resp(1).bits.uop.debug_pc === "h80000a2c".U) {
          chisel3.printf("[LSU-PROBE-ASYNC-LDQ1] ldq_idx=%d addr=0x%x old_addr=0x%x owner_match=%d ptbr_mode=0x%x ptbr_ppn=0x%x prv=%d mprv=%d dprv=%d\n",
            ldq_idx,
            io.lsu_addr_resp(1).bits.addr,
            ldq(ldq_idx).bits.addr.bits,
            ldq_owner_match,
            io.ptw.ptbr.mode,
            io.ptw.ptbr.ppn,
            io.ptw.status.prv,
            io.ptw.status.mprv,
            io.ptw.status.dprv)
        }
        ldq(ldq_idx).bits.addr.bits := io.lsu_addr_resp(1).bits.addr
        ldq(ldq_idx).bits.original_addr := io.lsu_addr_resp(1).bits.origin_addr
        ldq(ldq_idx).bits.uop.pdst := io.lsu_addr_resp(1).bits.uop.pdst
        ldq(ldq_idx).bits.uop.ctrl := io.lsu_addr_resp(1).bits.uop.ctrl
        ldq(ldq_idx).bits.addr_is_virtual := true.B
        ldq(ldq_idx).bits.addr_is_uncacheable := false.B
        ldq(ldq_idx).bits.c4_state := 2.U
      }.otherwise {
      }
    }.elsewhen( io.lsu_addr_resp(1).bits.uop.uses_stq){
      val stq_idx = io.lsu_addr_resp(1).bits.uop.stq_idx
      assert(io.lsu_addr_resp(1).bits.uop.ctrl.is_sta,
        "[lsu] bypassed async store addr response must come from STA/STAD, not pure STD")
      assert(!io.lsu_addr_resp(1).bits.data_valid || io.lsu_addr_resp(1).bits.uop.ctrl.is_std,
        "[lsu] bypassed async store addr response carries data only for STAD")
      val stq_owner_match =
        stq(stq_idx).valid &&
        (stq(stq_idx).bits.uop.rob_idx === io.lsu_addr_resp(1).bits.uop.rob_idx) &&
        (stq(stq_idx).bits.uop.debug_pc === io.lsu_addr_resp(1).bits.uop.debug_pc)

      when (stq_owner_match) {
        stq(stq_idx).bits.addr.bits := io.lsu_addr_resp(1).bits.addr
        stq(stq_idx).bits.original_addr := io.lsu_addr_resp(1).bits.origin_addr
        stq(stq_idx).bits.uop.ctrl   := io.lsu_addr_resp(1).bits.uop.ctrl
        stq(stq_idx).bits.uop.pdst := io.lsu_addr_resp(1).bits.uop.pdst
        stq(stq_idx).bits.addr_is_virtual := true.B
        stq(stq_idx).bits.c4_state    := 2.U
        when (io.lsu_addr_resp(1).bits.data_valid) {
          stq(stq_idx).bits.data.valid := true.B
          stq(stq_idx).bits.data.bits := io.lsu_addr_resp(1).bits.data
        }
      }.otherwise {
      }
    }  
  }


  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Cache Access Cycle (Mem)
  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Note the DCache may not have accepted our request

  val exe_req_killed = widthMap(w => IsKilledByBranch(io.core.brupdate, exe_req(w).bits.uop))
  val stdf_killed = IsKilledByBranch(io.core.brupdate, io.core.fp_stdata.bits.uop)

  val fired_load_incoming  = widthMap(w => RegNext(will_fire_load_incoming(w) && !exe_req_killed(w)))
  val fired_stad_incoming  = widthMap(w => RegNext(will_fire_stad_incoming(w) && !exe_req_killed(w)))
  val fired_sta_incoming   = widthMap(w => RegNext(will_fire_sta_incoming (w) && !exe_req_killed(w)))
  val fired_std_incoming   = widthMap(w => RegNext(will_fire_std_incoming (w) && !exe_req_killed(w)))
  val fired_stdf_incoming  = RegNext(will_fire_stdf_incoming && !stdf_killed)
  val fired_sfence         = RegNext(will_fire_sfence)
  val fired_release        = RegNext(will_fire_release)
  val fired_load_retry     = widthMap(w => RegNext(will_fire_load_retry   (w) && !IsKilledByBranch(io.core.brupdate, ldq_retry_e.bits.uop)))
  val fired_sta_retry      = widthMap(w => RegNext(will_fire_sta_retry    (w) && !IsKilledByBranch(io.core.brupdate, stq_retry_e.bits.uop)))
  val fired_store_commit   = RegNext(will_fire_store_commit)
  val fired_load_wakeup    = widthMap(w => RegNext(will_fire_load_wakeup  (w) && !IsKilledByBranch(io.core.brupdate, ldq_wakeup_e.bits.uop)))
  val fired_hella_incoming = RegNext(will_fire_hella_incoming)
  val fired_hella_wakeup   = RegNext(will_fire_hella_wakeup)

  val mem_incoming_uop     = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, exe_req(w).bits.uop)))
  val mem_ldq_incoming_e   = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, ldq_incoming_e(w))))
  val mem_stq_incoming_e   = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, stq_incoming_e(w))))
  val mem_ldq_wakeup_e     = RegNext(UpdateBrMask(io.core.brupdate, ldq_wakeup_e))
  val mem_ldq_retry_e      = RegNext(UpdateBrMask(io.core.brupdate, ldq_retry_e))
  val mem_stq_retry_e      = RegNext(UpdateBrMask(io.core.brupdate, stq_retry_e))
  val mem_ldq_e            = widthMap(w =>
                             Mux(fired_load_incoming(w), mem_ldq_incoming_e(w),
                             Mux(fired_load_retry   (w), mem_ldq_retry_e,
                             Mux(fired_load_wakeup  (w), mem_ldq_wakeup_e, (0.U).asTypeOf(Valid(new LDQEntry))))))
  val mem_stq_e            = widthMap(w =>
                             Mux(fired_stad_incoming(w) ||
                                 fired_sta_incoming (w), mem_stq_incoming_e(w),
                             Mux(fired_sta_retry    (w), mem_stq_retry_e, (0.U).asTypeOf(Valid(new STQEntry)))))
  val mem_stdf_uop         = RegNext(UpdateBrMask(io.core.brupdate, io.core.fp_stdata.bits.uop))


  val mem_tlb_miss             = RegNext(exe_tlb_miss)
  val mem_tlb_uncacheable      = RegNext(exe_tlb_uncacheable)
  val mem_paddr                = RegNext(widthMap(w => dmem_req(w).bits.addr))

  // Task 1: Clr ROB busy bit
  val clr_bsy_valid   = RegInit(widthMap(w => false.B))
  val clr_bsy_rob_idx = Reg(Vec(memWidth, UInt(robAddrSz.W)))
  val clr_bsy_brmask  = Reg(Vec(memWidth, UInt(maxBrCount.W)))

  for (w <- 0 until memWidth) {
    clr_bsy_valid   (w) := false.B
    clr_bsy_rob_idx (w) := 0.U
    clr_bsy_brmask  (w) := 0.U


    when (fired_stad_incoming(w)) {
      clr_bsy_valid   (w) := mem_stq_incoming_e(w).valid           &&
                            !mem_tlb_miss(w)                       &&
                            !mem_stq_incoming_e(w).bits.uop.is_amo &&
                            !IsKilledByBranch(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_incoming_e(w).bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
    } .elsewhen (fired_sta_incoming(w)) {
      clr_bsy_valid   (w) := mem_stq_incoming_e(w).valid            &&
                             (mem_stq_incoming_e(w).bits.data.valid ||
                               mem_stq_incoming_e(w).bits.uop.uopc === uop_store_key) &&
                            !mem_tlb_miss(w)                        &&
                            !mem_stq_incoming_e(w).bits.uop.is_amo  &&
                            !IsKilledByBranch(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_incoming_e(w).bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
    } .elsewhen (fired_std_incoming(w)) {
      clr_bsy_valid   (w) := mem_stq_incoming_e(w).valid                 &&
                             mem_stq_incoming_e(w).bits.addr.valid       &&
                            !mem_stq_incoming_e(w).bits.addr_is_virtual  &&
                            !mem_stq_incoming_e(w).bits.uop.is_amo       &&
                            !IsKilledByBranch(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_incoming_e(w).bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
    } .elsewhen (fired_sfence(w)) {
      clr_bsy_valid   (w) := (w == 0).B // SFence proceeds down all paths, only allow one to clr the rob
      clr_bsy_rob_idx (w) := mem_incoming_uop(w).rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brupdate, mem_incoming_uop(w))
    } .elsewhen (fired_sta_retry(w)) {
      clr_bsy_valid   (w) := mem_stq_retry_e.valid            &&
                             (mem_stq_retry_e.bits.data.valid ||
                               mem_stq_retry_e.bits.uop.uopc === uop_store_key) &&
                            !mem_tlb_miss(w)                  &&
                            !mem_stq_retry_e.bits.uop.is_amo  &&
                            !IsKilledByBranch(io.core.brupdate, mem_stq_retry_e.bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_retry_e.bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brupdate, mem_stq_retry_e.bits.uop)
    }

    io.core.clr_bsy(w).valid := clr_bsy_valid(w) &&
                               !IsKilledByBranch(io.core.brupdate, clr_bsy_brmask(w)) &&
                               !io.core.exception && !RegNext(io.core.exception) && !RegNext(RegNext(io.core.exception))
    io.core.clr_bsy(w).bits  := clr_bsy_rob_idx(w)
  }

  val stdf_clr_bsy_valid   = RegInit(false.B)
  val stdf_clr_bsy_rob_idx = Reg(UInt(robAddrSz.W))
  val stdf_clr_bsy_brmask  = Reg(UInt(maxBrCount.W))
  stdf_clr_bsy_valid   := false.B
  stdf_clr_bsy_rob_idx := 0.U
  stdf_clr_bsy_brmask  := 0.U
  when (fired_stdf_incoming) {
    val s_idx = mem_stdf_uop.stq_idx
    stdf_clr_bsy_valid   := stq(s_idx).valid                 &&
                            stq(s_idx).bits.addr.valid       &&
                            !stq(s_idx).bits.addr_is_virtual &&
                            !stq(s_idx).bits.uop.is_amo      &&
                            !IsKilledByBranch(io.core.brupdate, mem_stdf_uop)
    stdf_clr_bsy_rob_idx := mem_stdf_uop.rob_idx
    stdf_clr_bsy_brmask  := GetNewBrMask(io.core.brupdate, mem_stdf_uop)
  }



  io.core.clr_bsy(memWidth).valid := stdf_clr_bsy_valid &&
                                    !IsKilledByBranch(io.core.brupdate, stdf_clr_bsy_brmask) &&
                                    !io.core.exception && !RegNext(io.core.exception) && !RegNext(RegNext(io.core.exception))
  io.core.clr_bsy(memWidth).bits  := stdf_clr_bsy_rob_idx



  // Task 2: Do LD-LD. ST-LD searches for ordering failures
  //         Do LD-ST search for forwarding opportunities
  // We have the opportunity to kill a request we sent last cycle. Use it wisely!

  // We translated a store last cycle
  val do_st_search = widthMap(w => (fired_stad_incoming(w) || fired_sta_incoming(w) || fired_sta_retry(w)) && !mem_tlb_miss(w))
  // We translated a load last cycle
  val do_ld_search = widthMap(w => ((fired_load_incoming(w) || fired_load_retry(w)) && !mem_tlb_miss(w)) ||
                     fired_load_wakeup(w))
  // We are making a local line visible to other harts
  val do_release_search = widthMap(w => fired_release(w))

  // Store addrs don't go to memory yet, get it from the TLB response
  // Load wakeups don't go through TLB, get it through memory
  // Load incoming and load retries go through both

  val lcam_addr  = widthMap(w => Mux(fired_stad_incoming(w) || fired_sta_incoming(w) || fired_sta_retry(w),
                                     RegNext(exe_tlb_paddr(w)),
                                     Mux(fired_release(w), RegNext(io.dmem.release.bits.address),
                                         mem_paddr(w))))
  val lcam_uop   = widthMap(w => Mux(do_st_search(w), mem_stq_e(w).bits.uop,
                                 Mux(do_ld_search(w), mem_ldq_e(w).bits.uop, NullMicroOp)))

  val lcam_mask  = widthMap(w => GenByteMask(lcam_addr(w), lcam_uop(w).mem_size))
  val lcam_st_dep_mask = widthMap(w => mem_ldq_e(w).bits.st_dep_mask)
  val lcam_is_release = widthMap(w => fired_release(w))
  val lcam_ldq_idx  = widthMap(w =>
                      Mux(fired_load_incoming(w), mem_incoming_uop(w).ldq_idx,
                      Mux(fired_load_wakeup  (w), RegNext(ldq_wakeup_idx),
                      Mux(fired_load_retry   (w), RegNext(ldq_retry_idx), 0.U))))
  val lcam_stq_idx  = widthMap(w =>
                      Mux(fired_stad_incoming(w) ||
                          fired_sta_incoming (w), mem_incoming_uop(w).stq_idx,
                      Mux(fired_sta_retry    (w), RegNext(stq_retry_idx), 0.U)))

  val can_forward = WireInit(widthMap(w =>
    Mux(fired_load_incoming(w) || fired_load_retry(w), !mem_tlb_uncacheable(w),
      !ldq(lcam_ldq_idx(w)).bits.addr_is_uncacheable)))

  // Mask of stores which we conflict on address with
  val ldst_addr_matches    = WireInit(widthMap(w => VecInit((0 until numStqEntries).map(x=>false.B))))
  // Mask of stores which we can forward from
  val ldst_forward_matches = WireInit(widthMap(w => VecInit((0 until numStqEntries).map(x=>false.B))))

  val failed_loads     = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B))) // Loads which we will report as failures (throws a mini-exception)
  val nacking_loads    = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B))) // Loads which are being nacked by dcache in the next stage

  val s1_executing_loads = RegNext(s0_executing_loads)
  val s1_set_execute     = WireInit(s1_executing_loads)

  val mem_forward_valid   = Wire(Vec(memWidth, Bool()))
  val mem_forward_ldq_idx = lcam_ldq_idx
  val mem_forward_ld_addr = lcam_addr
  val mem_forward_stq_idx = Wire(Vec(memWidth, UInt(log2Ceil(numStqEntries).W)))

  val wb_forward_valid    = RegNext(mem_forward_valid)
  val wb_forward_ldq_idx  = RegNext(mem_forward_ldq_idx)
  val wb_forward_ld_addr  = RegNext(mem_forward_ld_addr)
  val wb_forward_stq_idx  = RegNext(mem_forward_stq_idx)

  for (i <- 0 until numLdqEntries) {
    val l_valid = ldq(i).valid
    val l_bits  = ldq(i).bits
    val l_addr  = ldq(i).bits.addr.bits
    val l_mask  = GenByteMask(l_addr, l_bits.uop.mem_size)

    val l_forwarders      = widthMap(w => wb_forward_valid(w) && wb_forward_ldq_idx(w) === i.U)
    val l_is_forwarding   = l_forwarders.reduce(_||_)
    val l_forward_stq_idx = Mux(l_is_forwarding, Mux1H(l_forwarders, wb_forward_stq_idx), l_bits.forward_stq_idx)


    val block_addr_matches = widthMap(w => lcam_addr(w) >> blockOffBits === l_addr >> blockOffBits)
    val dword_addr_matches = widthMap(w => block_addr_matches(w) && lcam_addr(w)(blockOffBits-1,3) === l_addr(blockOffBits-1,3))
    val mask_match   = widthMap(w => (l_mask & lcam_mask(w)) === l_mask)
    val mask_overlap = widthMap(w => (l_mask & lcam_mask(w)).orR)

    // Searcher is a store
    for (w <- 0 until memWidth) {

      when (do_release_search(w) &&
            l_valid              &&
            l_bits.addr.valid    &&
            block_addr_matches(w)) {
        // This load has been observed, so if a younger load to the same address has not
        // executed yet, this load must be squashed
        ldq(i).bits.observed := true.B
      } .elsewhen (do_st_search(w)                                                                                                &&
                   l_valid                                                                                                        &&
                   l_bits.addr.valid                                                                                              &&
                   (l_bits.executed || l_bits.succeeded || l_is_forwarding)                                                       &&
                   !l_bits.addr_is_virtual                                                                                        &&
                   l_bits.st_dep_mask(lcam_stq_idx(w))                                                                            &&
                   dword_addr_matches(w)                                                                                          &&
                   mask_overlap(w)) {

        val forwarded_is_older = IsOlder(l_forward_stq_idx, lcam_stq_idx(w), l_bits.youngest_stq_idx)
        // We are older than this load, which overlapped us.
        when (!l_bits.forward_std_val || // If the load wasn't forwarded, it definitely failed
          ((l_forward_stq_idx =/= lcam_stq_idx(w)) && forwarded_is_older)) { // If the load forwarded from us, we might be ok
          ldq(i).bits.order_fail := true.B
          failed_loads(i)        := true.B
        }
      } .elsewhen (do_ld_search(w)            &&
                   l_valid                    &&
                   l_bits.addr.valid          &&
                   !l_bits.addr_is_virtual    &&
                   dword_addr_matches(w)      &&
                   mask_overlap(w)) {
        val searcher_is_older = IsOlder(lcam_ldq_idx(w), i.U, ldq_head)
        when (searcher_is_older) {
          when ((l_bits.executed || l_bits.succeeded || l_is_forwarding) &&
                !s1_executing_loads(i) && // If the load is proceeding in parallel we don't need to kill it
                l_bits.observed) {        // Its only a ordering failure if the cache line was observed between the younger load and us
            ldq(i).bits.order_fail := true.B
            failed_loads(i)        := true.B
          }
        } .elsewhen (lcam_ldq_idx(w) =/= i.U) {
          // The load is older, and either it hasn't executed, it was nacked, or it is ignoring its response
          // we need to kill ourselves, and prevent forwarding
          val older_nacked = nacking_loads(i) || RegNext(nacking_loads(i))
          when (!(l_bits.executed || l_bits.succeeded) || older_nacked) {
            s1_set_execute(lcam_ldq_idx(w))    := false.B
            io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
            can_forward(w)                     := false.B
          }
        }
      }
    }
  }

  for (i <- 0 until numStqEntries) {
    val s_addr = stq(i).bits.addr.bits
    val s_uop  = stq(i).bits.uop
    val dword_addr_matches = widthMap(w =>
                             ( stq(i).bits.addr.valid      &&
                              !stq(i).bits.addr_is_virtual &&
                              (s_addr(corePAddrBits-1,3) === lcam_addr(w)(corePAddrBits-1,3))))
    val write_mask = GenByteMask(s_addr, s_uop.mem_size)
    for (w <- 0 until memWidth) {
      when (do_ld_search(w) && stq(i).valid && lcam_st_dep_mask(w)(i)) {
        when (((lcam_mask(w) & write_mask) === lcam_mask(w)) && !s_uop.is_fence && !s_uop.is_amo && dword_addr_matches(w) && can_forward(w))
        {
          ldst_addr_matches(w)(i)            := true.B
          ldst_forward_matches(w)(i)         := true.B
          io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
          s1_set_execute(lcam_ldq_idx(w))    := false.B
        }
          .elsewhen (((lcam_mask(w) & write_mask) =/= 0.U) && dword_addr_matches(w))
        {
          ldst_addr_matches(w)(i)            := true.B
          io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
          s1_set_execute(lcam_ldq_idx(w))    := false.B
        }
          .elsewhen (s_uop.is_fence || s_uop.is_amo)
        {
          ldst_addr_matches(w)(i)            := true.B
          io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
          s1_set_execute(lcam_ldq_idx(w))    := false.B
        }
      }
    }
  }

  // Set execute bit in LDQ
  for (i <- 0 until numLdqEntries) {
    when (s1_set_execute(i)) { ldq(i).bits.executed := true.B }
  }

  // Find the youngest store which the load is dependent on
  val forwarding_age_logic = Seq.fill(memWidth) { Module(new ForwardingAgeLogic(numStqEntries)) }
  for (w <- 0 until memWidth) {
    forwarding_age_logic(w).io.addr_matches    := ldst_addr_matches(w).asUInt
    forwarding_age_logic(w).io.youngest_st_idx := lcam_uop(w).stq_idx
  }
  val forwarding_idx = widthMap(w => forwarding_age_logic(w).io.forwarding_idx)

  // Forward if st-ld forwarding is possible from the writemask and loadmask
  mem_forward_valid       := widthMap(w =>
                                  (ldst_forward_matches(w)(forwarding_idx(w))        &&
                                 !IsKilledByBranch(io.core.brupdate, lcam_uop(w))    &&
                                 !io.core.exception && !RegNext(io.core.exception)))
  mem_forward_stq_idx     := forwarding_idx

  // Avoid deadlock with a 1-w LSU prioritizing load wakeups > store commits
  // On a 2W machine, load wakeups and store commits occupy separate pipelines,
  // so only add this logic for 1-w LSU
  if (memWidth == 1) {
    // Wakeups may repeatedly find a st->ld addr conflict and fail to forward,
    // repeated wakeups may block the store from ever committing
    // Disallow load wakeups 1 cycle after this happens to allow the stores to drain
    when (RegNext(ldst_addr_matches(0).reduce(_||_) && !mem_forward_valid(0))) {
      block_load_wakeup := true.B
    }

    // If stores remain blocked for 15 cycles, block load wakeups to get a store through
    val store_blocked_counter = Reg(UInt(4.W))
    when (will_fire_store_commit(0) || !can_fire_store_commit(0)) {
      store_blocked_counter := 0.U
    } .elsewhen (can_fire_store_commit(0) && !will_fire_store_commit(0)) {
      store_blocked_counter := Mux(store_blocked_counter === 15.U, 15.U, store_blocked_counter + 1.U)
    }
    when (store_blocked_counter === 15.U) {
      block_load_wakeup := true.B
    }
  }


  // Task 3: Clr unsafe bit in ROB for succesful translations
  //         Delay this a cycle to avoid going ahead of the exception broadcast
  //         The unsafe bit is cleared on the first translation, so no need to fire for load wakeups
  for (w <- 0 until memWidth) {
    io.core.clr_unsafe(w).valid := RegNext((do_st_search(w) || do_ld_search(w)) && !fired_load_wakeup(w)) && false.B
    io.core.clr_unsafe(w).bits  := RegNext(lcam_uop(w).rob_idx)
  }

  // detect which loads get marked as failures, but broadcast to the ROB the oldest failing load
  // TODO encapsulate this in an age-based  priority-encoder
  //   val l_idx = AgePriorityEncoder((Vec(Vec.tabulate(numLdqEntries)(i => failed_loads(i) && i.U >= laq_head)
  //   ++ failed_loads)).asUInt)
  val temp_bits = (VecInit(VecInit.tabulate(numLdqEntries)(i =>
    failed_loads(i) && i.U >= ldq_head) ++ failed_loads)).asUInt
  val l_idx = PriorityEncoder(temp_bits)

  // one exception port, but multiple causes!
  // - 1) the incoming store-address finds a faulting load (it is by definition younger)
  // - 2) the incoming load or store address is excepting. It must be older and thus takes precedent.
  val r_xcpt_valid = RegInit(false.B)
  val r_xcpt       = Reg(new Exception)

  val ld_xcpt_valid = failed_loads.reduce(_|_)
  val ld_xcpt_uop   = ldq(Mux(l_idx >= numLdqEntries.U, l_idx - numLdqEntries.U, l_idx)).bits.uop

  val use_mem_xcpt = (mem_xcpt_valid && IsOlder(mem_xcpt_uop.rob_idx, ld_xcpt_uop.rob_idx, io.core.rob_head_idx)) || !ld_xcpt_valid

  val xcpt_uop = Mux(use_mem_xcpt, mem_xcpt_uop, ld_xcpt_uop)

  r_xcpt_valid := (ld_xcpt_valid || mem_xcpt_valid) &&
                   !io.core.exception &&
                   !IsKilledByBranch(io.core.brupdate, xcpt_uop)
  r_xcpt.uop         := xcpt_uop
  r_xcpt.uop.br_mask := GetNewBrMask(io.core.brupdate, xcpt_uop)
  r_xcpt.cause       := Mux(use_mem_xcpt, mem_xcpt_cause, MINI_EXCEPTION_MEM_ORDERING)
  r_xcpt.badvaddr    := mem_xcpt_vaddr // TODO is there another register we can use instead?
  r_xcpt.original_badvaddr := mem_xcpt_original_vaddr

  io.core.lxcpt.valid := r_xcpt_valid && !io.core.exception && !IsKilledByBranch(io.core.brupdate, r_xcpt.uop)
  io.core.lxcpt.bits  := r_xcpt


  // Task 4: Speculatively wakeup loads 1 cycle before they come back
  for (w <- 0 until memWidth) {
    io.core.spec_ld_wakeup(w).valid := enableFastLoadUse.B          &&
                                       fired_load_incoming(w)       &&
                                       !mem_incoming_uop(w).fp_val  &&
                                       mem_incoming_uop(w).pdst =/= 0.U
    io.core.spec_ld_wakeup(w).bits  := mem_incoming_uop(w).pdst
  }


  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Writeback Cycle (St->Ld Forwarding Path)
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Handle Memory Responses and nacks
  //----------------------------------
  for (w <- 0 until memWidth) {
    io.core.exe(w).iresp.valid := false.B
    io.core.exe(w).iresp.bits  := DontCare
    io.core.exe(w).fresp.valid := false.B
    io.core.exe(w).fresp.bits  := DontCare
  }

  val dmem_resp_fired = WireInit(widthMap(w => false.B))

  for (w <- 0 until memWidth) {
    // Handle nacks
    when (io.dmem.nack(w).valid)
    {
      // We have to re-execute this!
      when (io.dmem.nack(w).bits.is_hella)
      {
        assert(hella_state === h_wait || hella_state === h_dead)
      }
        .elsewhen (io.dmem.nack(w).bits.uop.uses_ldq)
      {
        assert(ldq(io.dmem.nack(w).bits.uop.ldq_idx).bits.executed)
        ldq(io.dmem.nack(w).bits.uop.ldq_idx).bits.executed  := false.B
        nacking_loads(io.dmem.nack(w).bits.uop.ldq_idx) := true.B
      }
        .otherwise
      {
        assert(io.dmem.nack(w).bits.uop.uses_stq)
        // val stq_idx = io.dmem.nack(w).bits.uop.stq_idx
        when (io.dmem.nack(w).bits.uop.uopc === uop_store_key && keyStoreActive &&
              io.dmem.nack(w).bits.uop.stq_idx === keyStoreStqIdx) {
          keyStoreWaitingResp := false.B
        }
        when (IsOlder(io.dmem.nack(w).bits.uop.stq_idx, stq_execute_head, stq_head)) {
          stq_execute_head := io.dmem.nack(w).bits.uop.stq_idx
        }
      }
    }
    // Handle the response


    when (io.dmem.resp(w).valid)
    {
      when (io.dmem.resp(w).bits.uop.uses_ldq)
      {

        assert(!io.dmem.resp(w).bits.is_hella)
        val ldq_idx = io.dmem.resp(w).bits.uop.ldq_idx
        val dmem_resp_live = !IsKilledByBranch(io.core.brupdate, ldq(ldq_idx).bits.uop)
        val send_iresp = ldq(ldq_idx).bits.uop.dst_rtype === RT_FIX
        val send_fresp = ldq(ldq_idx).bits.uop.dst_rtype === RT_FLT

        io.core.exe(w).iresp.bits.uop  := ldq(ldq_idx).bits.uop
        io.core.exe(w).fresp.bits.uop  := ldq(ldq_idx).bits.uop
        io.core.exe(w).iresp.valid     := send_iresp
        io.core.exe(w).iresp.bits.data := io.dmem.resp(w).bits.data
        io.core.exe(w).fresp.valid     := send_fresp
        io.core.exe(w).fresp.bits.data := io.dmem.resp(w).bits.data

	        when (io.dmem.resp(w).bits.uop.uopc === uop_load_key && keyLoadActive && ldq_idx === keyLoadLdqIdx) {
	          io.load_key_req.valid := dmem_resp_live
	          io.load_key_req.bits.count := keyLoadBeat
	          io.load_key_req.bits.key := io.dmem.resp(w).bits.data
	          ldq(ldq_idx).bits.debug_wb_data := io.dmem.resp(w).bits.data
	          when (dmem_resp_live) {
	            chisel3.printf("[RTL-KEY-LOAD] cycle=0x%x lane=%d beat=%d base=0x%x addr=0x%x key_word=0x%x rob=%d pc=0x%x prv=%d dprv=%d\n",
	              io.core.tsc_reg,
	              w.U,
	              keyLoadBeat,
	              keyLoadBaseAddr,
	              keyLoadBaseAddr + (keyLoadBeat << 3),
	              io.dmem.resp(w).bits.data,
	              io.dmem.resp(w).bits.uop.rob_idx,
	              io.dmem.resp(w).bits.uop.debug_pc,
	              io.ptw.status.prv,
	              io.ptw.status.dprv)
	          }
	          when (dmem_resp_live && keyLoadBeat =/= 3.U) {
            keyLoadBeat := keyLoadBeat + 1.U
            ldq(ldq_idx).bits.addr.bits := keyLoadBaseAddr + ((keyLoadBeat + 1.U) << 3)
            ldq(ldq_idx).bits.executed := false.B
            ldq(ldq_idx).bits.succeeded := false.B
          } .otherwise {
            dmem_resp_fired(w) := dmem_resp_live
            io.core.exe(w).iresp.valid := dmem_resp_live
            io.core.exe(w).iresp.bits.data := 0.U
            io.core.exe(w).fresp.valid := false.B
            ldq(ldq_idx).bits.succeeded := dmem_resp_live
            keyLoadActive := false.B
          }
        } .otherwise {
          assert(send_iresp ^ send_fresp)
          dmem_resp_fired(w) := true.B
          ldq(ldq_idx).bits.succeeded := io.core.exe(w).iresp.valid || io.core.exe(w).fresp.valid
          ldq(ldq_idx).bits.debug_wb_data := io.dmem.resp(w).bits.data
        }
      }
        .elsewhen (io.dmem.resp(w).bits.uop.uses_stq)
      {
        assert(!io.dmem.resp(w).bits.is_hella)
        when (io.dmem.resp(w).bits.uop.uopc === uop_store_key && keyStoreActive &&
              io.dmem.resp(w).bits.uop.stq_idx === keyStoreStqIdx) {
          val stq_idx = io.dmem.resp(w).bits.uop.stq_idx
          keyStoreWaitingResp := false.B
          when (keyStoreBeat === 3.U) {
            stq(stq_idx).bits.succeeded := true.B
            stq_execute_head := WrapInc(stq_execute_head, numStqEntries)
          } .otherwise {
            stq(stq_idx).bits.succeeded := false.B
            keyStoreBeat := keyStoreBeat + 1.U
          }
        } .otherwise {
          stq(io.dmem.resp(w).bits.uop.stq_idx).bits.succeeded := true.B
        }
        when (io.dmem.resp(w).bits.uop.is_amo) {
          // 在这里 需要将  amo / sc寄存器的值 经过 engine
          dmem_resp_fired(w) := true.B
          io.core.exe(w).iresp.valid     := true.B
          io.core.exe(w).iresp.bits.uop  := stq(io.dmem.resp(w).bits.uop.stq_idx).bits.uop
          io.core.exe(w).iresp.bits.data := io.dmem.resp(w).bits.data

          stq(io.dmem.resp(w).bits.uop.stq_idx).bits.debug_wb_data := io.dmem.resp(w).bits.data
        }
      }
    }
    when (dmem_resp_fired(w) && wb_forward_valid(w))
    {
      // Twiddle thumbs. Can't forward because dcache response takes precedence
    }
      .elsewhen (!dmem_resp_fired(w) && wb_forward_valid(w))
    {
      val f_idx       = wb_forward_ldq_idx(w)
      val forward_uop = ldq(f_idx).bits.uop
      val stq_e       = stq(wb_forward_stq_idx(w))
      val data_ready  = stq_e.bits.data.valid
      val live        = !IsKilledByBranch(io.core.brupdate, forward_uop)
      val storegen = new freechips.rocketchip.rocket.StoreGen(
                                stq_e.bits.uop.mem_size, stq_e.bits.addr.bits,
                                stq_e.bits.data.bits, coreDataBytes)
      val loadgen  = new freechips.rocketchip.rocket.LoadGen(
                                forward_uop.mem_size, forward_uop.mem_signed,
                                wb_forward_ld_addr(w),
                                storegen.data, false.B, coreDataBytes)
      io.core.exe(w).iresp.valid := (forward_uop.dst_rtype === RT_FIX) && data_ready && live
      io.core.exe(w).fresp.valid := (forward_uop.dst_rtype === RT_FLT) && data_ready && live
      io.core.exe(w).iresp.bits.uop  := forward_uop
      io.core.exe(w).fresp.bits.uop  := forward_uop
      io.core.exe(w).iresp.bits.data := loadgen.data
      io.core.exe(w).fresp.bits.data := loadgen.data
      when (data_ready && live) {
        ldq(f_idx).bits.succeeded := data_ready
        ldq(f_idx).bits.forward_std_val := true.B
        ldq(f_idx).bits.forward_stq_idx := wb_forward_stq_idx(w)

        ldq(f_idx).bits.debug_wb_data   := loadgen.data
      }
    }
  }

  // Initially assume the speculative load wakeup failed
  io.core.ld_miss         := RegNext(io.core.spec_ld_wakeup.map(_.valid).reduce(_||_))
  val spec_ld_succeed = widthMap(w =>
    !RegNext(io.core.spec_ld_wakeup(w).valid) ||
    (io.core.exe(w).iresp.valid &&
      io.core.exe(w).iresp.bits.uop.ldq_idx === RegNext(mem_incoming_uop(w).ldq_idx)
    )
  ).reduce(_&&_)
  when (spec_ld_succeed) {
    io.core.ld_miss := false.B
  }

  
  //-------------------------------------------------------------
  // Kill speculated entries on branch mispredict
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Kill stores
  val st_brkilled_mask = Wire(Vec(numStqEntries, Bool()))
  for (i <- 0 until numStqEntries)
  {
    st_brkilled_mask(i) := false.B

    when (stq(i).valid)
    {
      stq(i).bits.uop.br_mask := GetNewBrMask(io.core.brupdate, stq(i).bits.uop.br_mask)

      when (IsKilledByBranch(io.core.brupdate, stq(i).bits.uop))
      {
        stq(i).valid           := false.B
        stq(i).bits.addr.valid := false.B
        stq(i).bits.data.valid := false.B
        st_brkilled_mask(i)    := true.B
      }
    }

    assert (!(IsKilledByBranch(io.core.brupdate, stq(i).bits.uop) && stq(i).valid && stq(i).bits.committed),
      "Branch is trying to clear a committed store.")
  }

  // Kill loads
  for (i <- 0 until numLdqEntries)
  {
    when (ldq(i).valid)
    {
      ldq(i).bits.uop.br_mask := GetNewBrMask(io.core.brupdate, ldq(i).bits.uop.br_mask)
      when (IsKilledByBranch(io.core.brupdate, ldq(i).bits.uop))
      {
        // printf("[LDQ-KILL] idx=%d pc=0x%x rob=%d pdst=%d executed=%d succeeded=%d brmask=0x%x\n",
        //   i.U,
        //   ldq(i).bits.uop.debug_pc,
        //   ldq(i).bits.uop.rob_idx,
        //   ldq(i).bits.uop.pdst,
        //   ldq(i).bits.executed,
        //   ldq(i).bits.succeeded,
        //   ldq(i).bits.uop.br_mask)
        ldq(i).valid           := false.B
        ldq(i).bits.addr.valid := false.B
      }
    }
  }

  //-------------------------------------------------------------
  when (io.core.brupdate.b2.mispredict && !io.core.exception)
  {
    stq_tail := io.core.brupdate.b2.uop.stq_idx
    ldq_tail := io.core.brupdate.b2.uop.ldq_idx
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // dequeue old entries on commit
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  var temp_stq_commit_head = stq_commit_head
  var temp_ldq_head        = ldq_head
  for (w <- 0 until coreWidth)
  {
    val commit_store = io.core.commit.valids(w) && io.core.commit.uops(w).uses_stq
    val commit_load  = io.core.commit.valids(w) && io.core.commit.uops(w).uses_ldq
    val idx = Mux(commit_store, temp_stq_commit_head, temp_ldq_head)
    // when (commit_store && stq(idx).valid && stq(idx).bits.addr.valid && dbgBlockMatch(stq(idx).bits.addr.bits)) {
    //   printf("[LSU-TGT-ROB-COMMIT] slot=%d stq_idx=%d pc=0x%x addr=0x%x committed_before=%d succeeded=%d head=%d exec=%d\n",
    //     w.U,
    //     idx,
    //     stq(idx).bits.uop.debug_pc,
    //     stq(idx).bits.addr.bits,
    //     stq(idx).bits.committed,
    //     stq(idx).bits.succeeded,
    //     stq_head,
    //     stq_execute_head)
    // }
    when (commit_store)
    {
      stq(idx).bits.committed := true.B
    } .elsewhen (commit_load) {
      assert (ldq(idx).valid, "[lsu] trying to commit an un-allocated load entry.")
      assert ((ldq(idx).bits.executed || ldq(idx).bits.forward_std_val) && ldq(idx).bits.succeeded ,
        "[lsu] trying to commit an un-executed load entry.")

      ldq(idx).valid                 := false.B
      ldq(idx).bits.addr.valid       := false.B
      ldq(idx).bits.executed         := false.B
      ldq(idx).bits.succeeded        := false.B
      ldq(idx).bits.order_fail       := false.B
      ldq(idx).bits.forward_std_val  := false.B

    }

    when (commit_store || commit_load) {
      val uop    = Mux(commit_store, stq(idx).bits.uop, ldq(idx).bits.uop)
      val addr   = Mux(commit_store, stq(idx).bits.addr.bits, ldq(idx).bits.addr.bits)
      val stdata = Mux(commit_store, stq(idx).bits.data.bits, 0.U)
      val wbdata = Mux(commit_store, stq(idx).bits.debug_wb_data, ldq(idx).bits.debug_wb_data)

      if (MEMTRACE_PRINTF) {
        printf("MT %x %x %x %x %x %x %x\n",
          io.core.tsc_reg, uop.uopc, uop.mem_cmd, uop.mem_size, addr, stdata, wbdata)
      }

      // Track the final st benchmark result locations to distinguish
      // "store wrote a bad value" from "verify loaded a bad value".
      val dbgStSumAAddr = "h800008b8".U(addr.getWidth.W)
      val dbgStSumBAddr = "h800008b0".U(addr.getWidth.W)
      val dbgStCoefAddr = "h800008a8".U(addr.getWidth.W)
      val dbgStResultAddr = addr === dbgStSumAAddr || addr === dbgStSumBAddr || addr === dbgStCoefAddr
      when (dbgStResultAddr) {
        printf("[ST-RESULT-MEM] cycle=0x%x kind=%c pc=0x%x inst=0x%x rob=%d mem_cmd=0x%x mem_size=%d addr=0x%x stdata=0x%x wbdata=0x%x\n",
          io.core.tsc_reg,
          Mux(commit_store, 'S'.U(8.W), 'L'.U(8.W)),
          uop.debug_pc,
          uop.debug_inst,
          uop.rob_idx,
          uop.mem_cmd,
          uop.mem_size,
          addr,
          stdata,
          wbdata)
      }
    }

    temp_stq_commit_head = Mux(commit_store,
                               WrapInc(temp_stq_commit_head, numStqEntries),
                               temp_stq_commit_head)

    temp_ldq_head        = Mux(commit_load,
                               WrapInc(temp_ldq_head, numLdqEntries),
                               temp_ldq_head)
  }
  stq_commit_head := temp_stq_commit_head
  ldq_head        := temp_ldq_head

  // store has been committed AND successfully sent data to memory
  when (stq(stq_head).valid && stq(stq_head).bits.committed)
  {
    when (stq(stq_head).bits.uop.is_fence && !io.dmem.ordered) {
      io.dmem.force_order := true.B
      store_needs_order   := true.B
    }
    clear_store := Mux(stq(stq_head).bits.uop.is_fence, io.dmem.ordered,
                                                        stq(stq_head).bits.succeeded)
  }

  when (clear_store)
  {
    when (stq(stq_head).bits.uop.uopc === uop_store_key) {
      keyStoreActive := false.B
      keyStoreWaitingResp := false.B
      keyStoreBeat := 0.U
    }
    stq(stq_head).valid           := false.B
    stq(stq_head).bits.addr.valid := false.B
    stq(stq_head).bits.data.valid := false.B
    stq(stq_head).bits.succeeded  := false.B
    stq(stq_head).bits.committed  := false.B

    stq_head := WrapInc(stq_head, numStqEntries)
    when (stq(stq_head).bits.uop.is_fence)
    {
      stq_execute_head := WrapInc(stq_execute_head, numStqEntries)
    }
  }


  // -----------------------
  // Hellacache interface
  // We need to time things like a HellaCache would
  io.hellacache.req.ready := false.B
  io.hellacache.s2_nack   := false.B
  io.hellacache.s2_xcpt   := (0.U).asTypeOf(new rocket.HellaCacheExceptions)
  io.hellacache.resp.valid := false.B
  io.hellacache.store_pending := stq.map(_.valid).reduce(_||_)
  when (hella_state === h_ready) {
    io.hellacache.req.ready := true.B
    when (io.hellacache.req.fire) {
      when (io.ptw.status.mprv &&
            io.ptw.status.dprv === PRV.U.U &&
            (io.hellacache.req.bits.addr(vaddrBitsExtended-1, pgIdxBits) === "h2e7b8ad".U ||
             io.hellacache.req.bits.addr(vaddrBitsExtended-1, pgIdxBits) === "h40000".U)) {
        chisel3.printf("[LSU-HELLA-CAPTURE] state=%d addr=0x%x cmd=0x%x size=%d phys=%d dprv=%d dv=%d tag=0x%x s1_kill=%d\n",
          hella_state,
          io.hellacache.req.bits.addr,
          io.hellacache.req.bits.cmd,
          io.hellacache.req.bits.size,
          io.hellacache.req.bits.phys,
          io.hellacache.req.bits.dprv,
          io.hellacache.req.bits.dv,
          io.hellacache.req.bits.tag,
          io.hellacache.s1_kill)
      }
      when (io.hellacache.req.bits.phys) {
        chisel3.printf("[HELLA-PTW-REQ] state=%d addr=0x%x cmd=0x%x size=%d dprv=%d dv=%d tag=0x%x\n",
          hella_state,
          io.hellacache.req.bits.addr,
          io.hellacache.req.bits.cmd,
          io.hellacache.req.bits.size,
          io.hellacache.req.bits.dprv,
          io.hellacache.req.bits.dv,
          io.hellacache.req.bits.tag)
      }
      hella_req   := io.hellacache.req.bits
      hella_state := h_s1
    }
  } .elsewhen (hella_state === h_s1) {
    can_fire_hella_incoming(memWidth-1) := true.B

    hella_data := io.hellacache.s1_data
    hella_xcpt := dtlb.io.resp(memWidth-1)
    when (io.ptw.status.mprv &&
          io.ptw.status.dprv === PRV.U.U &&
          (hella_req.addr(vaddrBitsExtended-1, pgIdxBits) === "h2e7b8ad".U ||
           hella_req.addr(vaddrBitsExtended-1, pgIdxBits) === "h40000".U)) {
      chisel3.printf("[LSU-HELLA-S1-TRACE] state=%d addr=0x%x cmd=0x%x size=%d phys=%d dtlb_req_valid=%d dtlb_passthrough=%d dtlb_vaddr=0x%x dtlb_miss=%d dtlb_ae_ld=%d dtlb_pf_ld=%d dmem_fire=%d\n",
        hella_state,
        hella_req.addr,
        hella_req.cmd,
        hella_req.size,
        hella_req.phys,
        dtlb.io.req(memWidth-1).valid,
        dtlb.io.req(memWidth-1).bits.passthrough,
        dtlb.io.req(memWidth-1).bits.vaddr,
        dtlb.io.resp(memWidth-1).miss,
        dtlb.io.resp(memWidth-1).ae.ld,
        dtlb.io.resp(memWidth-1).pf.ld,
        dmem_req_fire(memWidth-1))
    }
    when (hella_req.phys) {
      chisel3.printf("[HELLA-PTW-S1] state=%d addr=0x%x cmd=0x%x tlb_miss=%d paddr=0x%x ae_ld=%d pf_ld=%d ma_ld=%d dmem_fire=%d req_kill=%d\n",
        hella_state,
        hella_req.addr,
        hella_req.cmd,
        exe_tlb_miss(memWidth-1),
        exe_tlb_paddr(memWidth-1),
        dtlb.io.resp(memWidth-1).ae.ld,
        dtlb.io.resp(memWidth-1).pf.ld,
        dtlb.io.resp(memWidth-1).ma.ld,
        dmem_req_fire(memWidth-1),
        io.hellacache.s1_kill)
      chisel3.printf("[HELLA-PTW-DTLB-IN] req_valid=%d vaddr=0x%x passthrough=%d prv=%d cmd=0x%x size=%d resp_paddr=0x%x resp_miss=%d resp_ae_ld=%d resp_pf_ld=%d\n",
        dtlb.io.req(memWidth-1).valid,
        dtlb.io.req(memWidth-1).bits.vaddr,
        dtlb.io.req(memWidth-1).bits.passthrough,
        dtlb.io.req(memWidth-1).bits.prv,
        dtlb.io.req(memWidth-1).bits.cmd,
        dtlb.io.req(memWidth-1).bits.size,
        dtlb.io.resp(memWidth-1).paddr,
        dtlb.io.resp(memWidth-1).miss,
        dtlb.io.resp(memWidth-1).ae.ld,
        dtlb.io.resp(memWidth-1).pf.ld)
    }

    when (io.hellacache.s1_kill) {
      when (will_fire_hella_incoming(memWidth-1) && dmem_req_fire(memWidth-1)) {
        hella_state := h_dead
      } .otherwise {
        hella_state := h_ready
      }
    } .elsewhen (will_fire_hella_incoming(memWidth-1) && dmem_req_fire(memWidth-1)) {
      hella_state := h_s2
    } .otherwise {
      hella_state := h_s2_nack
    }
  } .elsewhen (hella_state === h_s2_nack) {
    io.hellacache.s2_nack := true.B
    hella_state := h_ready
  } .elsewhen (hella_state === h_s2) {
    io.hellacache.s2_xcpt := hella_xcpt
    when (hella_req.phys) {
      chisel3.printf("[HELLA-PTW-S2] state=%d addr=0x%x paddr=0x%x ae_ld=%d pf_ld=%d ma_ld=%d ae_st=%d s2_kill=%d\n",
        hella_state,
        hella_req.addr,
        hella_paddr,
        hella_xcpt.ae.ld,
        hella_xcpt.pf.ld,
        hella_xcpt.ma.ld,
        hella_xcpt.ae.st,
        io.hellacache.s2_kill)
    }
    when (io.hellacache.s2_kill || hella_xcpt.asUInt =/= 0.U) {
      hella_state := h_dead
    } .otherwise {
      hella_state := h_wait
    }
  } .elsewhen (hella_state === h_wait) {
    for (w <- 0 until memWidth) {
      when (io.dmem.resp(w).valid && io.dmem.resp(w).bits.is_hella) {
        hella_state := h_ready

        io.hellacache.resp.valid       := true.B
        io.hellacache.resp.bits.addr   := hella_req.addr
        io.hellacache.resp.bits.tag    := hella_req.tag
        io.hellacache.resp.bits.cmd    := hella_req.cmd
        io.hellacache.resp.bits.signed := hella_req.signed
        io.hellacache.resp.bits.size   := hella_req.size
        io.hellacache.resp.bits.data   := io.dmem.resp(w).bits.data
      } .elsewhen (io.dmem.nack(w).valid && io.dmem.nack(w).bits.is_hella) {
        hella_state := h_replay
      }
    }
  } .elsewhen (hella_state === h_replay) {
    can_fire_hella_wakeup(memWidth-1) := true.B

    when (will_fire_hella_wakeup(memWidth-1) && dmem_req_fire(memWidth-1)) {
      hella_state := h_wait
    }
  } .elsewhen (hella_state === h_dead) {
    for (w <- 0 until memWidth) {
      when (io.dmem.resp(w).valid && io.dmem.resp(w).bits.is_hella) {
        hella_state := h_ready
      }
    }
  }

  //-------------------------------------------------------------
  // Exception / Reset

  // for the live_store_mask, need to kill stores that haven't been committed
  val st_exc_killed_mask = WireInit(VecInit((0 until numStqEntries).map(x=>false.B)))

  when (reset.asBool || io.core.exception)
  {
    ldq_head := 0.U
    ldq_tail := 0.U

    when (reset.asBool)
    {
      stq_head := 0.U
      stq_tail := 0.U
      stq_commit_head  := 0.U
      stq_execute_head := 0.U

      for (i <- 0 until numStqEntries)
      {
        stq(i).valid           := false.B
        stq(i).bits.addr.valid := false.B
        stq(i).bits.data.valid := false.B
        stq(i).bits.uop        := NullMicroOp
      }
    }
      .otherwise // exception
    {
      stq_tail := stq_commit_head

      for (i <- 0 until numStqEntries)
      {
        when (!stq(i).bits.committed && !stq(i).bits.succeeded)
        {
          stq(i).valid           := false.B
          stq(i).bits.addr.valid := false.B
          stq(i).bits.data.valid := false.B
          st_exc_killed_mask(i)  := true.B
        }
      }
    }

    for (i <- 0 until numLdqEntries)
    {
      ldq(i).valid           := false.B
      ldq(i).bits.addr.valid := false.B
      ldq(i).bits.executed   := false.B
    }
  }

  //-------------------------------------------------------------
  // Live Store Mask
  // track a bit-array of stores that are alive
  // (could maybe be re-produced from the stq_head/stq_tail, but need to know include spec_killed entries)

  // TODO is this the most efficient way to compute the live store mask?
  live_store_mask := next_live_store_mask &
                    ~(st_brkilled_mask.asUInt) &
                    ~(st_exc_killed_mask.asUInt)


}

/**
 * Object to take an address and generate an 8-bit mask of which bytes within a
 * double-word.
 */
object GenByteMask
{
   def apply(addr: UInt, size: UInt): UInt =
   {
      val mask = Wire(UInt(8.W))
      mask := MuxCase(255.U(8.W), Array(
                   (size === 0.U) -> (1.U(8.W) << addr(2,0)),
                   (size === 1.U) -> (3.U(8.W) << (addr(2,1) << 1.U)),
                   (size === 2.U) -> Mux(addr(2), 240.U(8.W), 15.U(8.W)),
                   (size === 3.U) -> 255.U(8.W)))
      mask
   }
}

/**
 * ...
 */
class ForwardingAgeLogic(num_entries: Int)(implicit p: Parameters) extends BoomModule()(p)
{
   val io = IO(new Bundle
   {
      val addr_matches    = Input(UInt(num_entries.W)) // bit vector of addresses that match
                                                       // between the load and the SAQ
      val youngest_st_idx = Input(UInt(stqAddrSz.W)) // needed to get "age"

      val forwarding_val  = Output(Bool())
      val forwarding_idx  = Output(UInt(stqAddrSz.W))
   })

   // generating mask that zeroes out anything younger than tail
   val age_mask = Wire(Vec(num_entries, Bool()))
   for (i <- 0 until num_entries)
   {
      age_mask(i) := true.B
      when (i.U >= io.youngest_st_idx) // currently the tail points PAST last store, so use >=
      {
         age_mask(i) := false.B
      }
   }

   // Priority encoder with moving tail: double length
   val matches = Wire(UInt((2*num_entries).W))
   matches := Cat(io.addr_matches & age_mask.asUInt,
                  io.addr_matches)

   val found_match = Wire(Bool())
   found_match       := false.B
   io.forwarding_idx := 0.U

   // look for youngest, approach from the oldest side, let the last one found stick
   for (i <- 0 until (2*num_entries))
   {
      when (matches(i))
      {
         found_match := true.B
         io.forwarding_idx := (i % num_entries).U
      }
   }

   io.forwarding_val := found_match
}
