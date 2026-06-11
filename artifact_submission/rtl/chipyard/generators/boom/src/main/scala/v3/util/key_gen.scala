package boom.v3.util

import chisel3._
import chisel3.util._

import boom.v3.common._
import boom.v3.ifu._
import boom.v3.util._
import freechips.rocketchip.rocket.{Causes, PRV, TracedInstruction}
import org.chipsalliance.cde.config.{Parameters, Field}
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._
import roccaccutils._


class load_key_req(implicit p: Parameters) extends BoomBundle
{
  val key = UInt(64.W)
  val count = UInt(8.W)
}


trait Key_Engine_State
{
   val init ::free :: sent :: gen_xor :: gen_key :: finished :: Nil = Enum(6)
}

class Key_Engine (implicit p: Parameters) extends BoomModule with Key_Engine_State
{
  val io = IO(new Bundle {
    val gen_key_req   = Flipped(Decoupled(Bool()))
    val gen_key_resp  = Output(Bool())
    val data_key = Output(UInt(128.W))
    val pointer_key = Output(UInt(128.W))
      // count + data
    val load_key_req = Flipped(Valid(new load_key_req()))
    val store_key_req =  Input(Bool())
    val store_key_resp = Valid(Vec(4,UInt(64.W)))
    //val switch = Input(Bool())
    val gen_xor_req = Input(Bool())
    val gen_xor_resp = Output(Bool())
    val xor_key  = Vec(numIntPhysRegs,DecoupledIO(UInt(64.W)))
    // val finish  = Output(Bool())
    val prv = Input(UInt(PRV.SZ.W))
    val dprv = Input(UInt(PRV.SZ.W))
    val send_xor_req = Input(Bool())
    val log = Input(Bool())
    // val trap = Input(Bool())

  })

  val constant = 0xdeadbeefL.U(64.W)
  val hardware_key = VecInit(Seq(
    BigInt("0123456789abcdef", 16).U(64.W),
    BigInt("fedcba9876543210", 16).U(64.W),
    BigInt("0f1e2d3c4b5a6978", 16).U(64.W),
    BigInt("8877665544332211", 16).U(64.W)
  ))
  val state = RegInit(free)

  val data_pointer_key = Reg(Vec(4,(UInt(64.W))))
  val key_count = RegInit(0.U(4.W))

  val os_data_pointer_key = Reg(Vec(4,(UInt(64.W))))
  val os_key_count = RegInit(0.U(4.W))

  val xor_reg_valid = RegInit(false.B)
  val xor_reg = Reg(Vec(numIntPhysRegs,(UInt(64.W))))
  val xor_count = RegInit(0.U(6.W))
  val gen_key_resp_valid = RegInit(false.B)

  // val init_once = RegInit(false.B)

  val active_key = Wire(Vec(4, UInt(64.W)))
  val protected_key = Wire(Vec(4, UInt(64.W)))
  for (i <- 0 until 4) {
    active_key(i) := Mux(io.dprv === (PRV.S).U, os_data_pointer_key(i), data_pointer_key(i))
    protected_key(i) := active_key(i) ^ hardware_key(i)
  }

  io.store_key_resp.valid := io.store_key_req
  io.store_key_resp.bits := protected_key


  // when (init_once === false.B)
  // {
    // when (xor_count =/= numIntPhysRegs.U)
    // {
    //   xor_reg(xor_count) := random.LFSR(64)
    //   xor_count := xor_count + 1.U
    //   printf("gen xor key : 0x%x at index : %d\n",xor_reg(xor_count),xor_count)
    // }.otherwise{
    //   init_once := true.B
    //   xor_count := 0.U
    // }


  // when ( (os_key_count =/= 6.U) && (io.prv === (PRV.S).U))
  // {
  //   os_data_pointer_key(os_key_count) := random.LFSR(64)
  //   os_key_count := os_key_count + 1.U
  //   printf("gen os key : 0x%x  index : %d\n",os_data_pointer_key(os_key_count),os_key_count)
  // }



  io.data_key := Cat(active_key(0), active_key(1))
  io.pointer_key := Cat(active_key(2), active_key(3))

  // io.data_key := Cat(os_data_pointer_key(0),os_data_pointer_key(1))
  // io.pointer_key := Cat(os_data_pointer_key(2),os_data_pointer_key(3))

  for (w <- 0 until numIntPhysRegs)
  {
    io.xor_key(w).bits := DontCare
    io.xor_key(w).valid := false.B
  }

  io.gen_key_req.ready := state === free && !gen_key_resp_valid
  io.gen_key_resp := gen_key_resp_valid

  when (gen_key_resp_valid) {
    gen_key_resp_valid := false.B
  }

  // 当 m mode 切换到 s mode，
  when(state === free && io.gen_xor_req){
    // 当 s mode切换到 m mode
    state := gen_xor
  }.elsewhen(io.gen_key_req.fire){
    state := gen_key
    key_count := 0.U
  }


  // io.finish := false.B
  io.gen_xor_resp := xor_reg_valid

  when(state === gen_xor){
    xor_reg(xor_count) := random.LFSR(64)
    xor_count := xor_count + 1.U
    when(xor_count === numIntPhysRegs.U){
      xor_count := 0.U
      state := free
      // io.finish := true.B
      // io.gen_xor_resp := true.B
      xor_reg_valid := true.B
    }
  }.elsewhen(state === gen_key){
    // to be done

    when (io.dprv === (PRV.S).U)
    {
      printf("gen os key\n");
      os_data_pointer_key(key_count) := random.LFSR(64)
      when (key_count === 3.U)
      {
        key_count := 0.U
        state := free
        gen_key_resp_valid := true.B
      }.otherwise{
        key_count := key_count + 1.U
      }
    }.otherwise{
      printf("gen process key\n")
      data_pointer_key(key_count) := random.LFSR(64)
      when (key_count === 3.U)
      {
        key_count := 0.U
        state := free
        gen_key_resp_valid := true.B
      }.otherwise{
        key_count := key_count + 1.U
      }
    }

  }

  when(  (io.prv === (PRV.M).U ) && (RegNext(io.prv) === (PRV.U).U))
  {
    xor_reg_valid := false.B
  }
  when(io.log)
  {
    printf("xor_reg_valid : 0x%x\n",xor_reg_valid.asUInt)
  }

  when (io.load_key_req.valid)
  {
    val load_key_idx = io.load_key_req.bits.count(1, 0)
    val restored_key_word = io.load_key_req.bits.key ^ hardware_key(load_key_idx)
    data_pointer_key(load_key_idx) := restored_key_word
    printf("[KEYGEN-LOAD] prv=%d dprv=%d idx=%d enc_word=0x%x word=0x%x\n",
      io.prv, io.dprv, io.load_key_req.bits.count, io.load_key_req.bits.key, restored_key_word)
    when (io.load_key_req.bits.count === 3.U) {
      printf("[KEYGEN-LOAD-FINAL] user_w0=0x%x user_w1=0x%x user_w2=0x%x user_w3=0x%x data_key=0x%x ptr_key=0x%x\n",
        data_pointer_key(0),
        data_pointer_key(1),
        data_pointer_key(2),
        restored_key_word,
        Cat(data_pointer_key(0), data_pointer_key(1)),
        Cat(data_pointer_key(2), restored_key_word))
    }
  }

  when (io.store_key_req)
  {
    // io.store_key_resp.bits := data_pointer_key(io.store_key_req.bits)
    // printf ("store key : 0x%x, index : %d\n",data_pointer_key(io.store_key_req.bits),io.store_key_req.bits)
  }


  when (io.log){
    printf("os key ")
    for (i <-0  until 4)
    {
      printf("[%d] : 0x%x ",i.U,os_data_pointer_key(i))
    }
    printf(" os key valid : %c\n",BoolToChar((io.dprv === (PRV.S).U),'V'))
    printf("process key ")
    for (i <- 0 until 4)
    {
        printf("[%d] : 0x%x",i.U,data_pointer_key(i))
    }
    printf(" process key valid : %c\n",BoolToChar((io.dprv =/= (PRV.S).U),'V'))
    printf("xor_reg ")
    for (i <- 0 until numIntPhysRegs)
    {
      printf("[%d] : 0x%x ",i.U,xor_reg(i))
    }

  }

}
