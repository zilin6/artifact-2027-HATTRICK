// See LICENSE for license details

package boom.v3.util

import chisel3._
import chisel3.util._

import boom.v3.common._
import boom.v3.ifu._
import boom.v3.util._
import chisel3.util.HasBlackBoxResource
import chisel3.experimental._
import org.chipsalliance.cde.config.{Parameters, Field}
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._
import roccaccutils._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.rocket._

object AsconCryptoMode {
  val idle = 0.U(4.W)
  val encrypt = 1.U(4.W)
  val decrypt = 2.U(4.W)
}

object AsconCryptoParams {
  private def zeroExtendTo(x: UInt, width: Int): UInt = {
    if (x.getWidth >= width) x(width - 1, 0) else Cat(0.U((width - x.getWidth).W), x)
  }

  def nonce(counter: UInt, paddr: UInt): UInt = {
    val ctrEff = zeroExtendTo(counter, 64) & "hffffffffffffff00".U(64.W)
    val paddrEff = zeroExtendTo(paddr, 64) & "hfffffffffffffff8".U(64.W)
    Cat(ctrEff, paddrEff)
  }
}
/** BlackBox wrapper for the SystemVerilog module:
  *
  *   module asconaead64(
  *       input  logic [  3:0] in_mode,
  *       input  logic [127:0] in_key,
  *       input  logic [127:0] in_nonce,
  *       input  logic [ 63:0] in_msg,
  *       output logic [ 63:0] out_msg,
  *       output logic [127:0] out_tag
  *   );
  */
class asconaead64 extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val in_mode   = Input(UInt(4.W))
    val in_key    = Input(UInt(128.W))
    val in_nonce  = Input(UInt(128.W))
    val in_msg    = Input(UInt(64.W))
    val out_msg   = Output(UInt(64.W))
    val out_tag   = Output(UInt(128.W))
  })

  // 把 SV 文件加入 FIRRTL 编译列表
  addResource("/asconaead64.sv")
  addResource("/asconp_boom.sv")
  addResource("./config.svh")
}

class asconaead24 extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val in_mode   = Input(UInt(4.W))
    val in_key    = Input(UInt(128.W))
    val in_nonce  = Input(UInt(128.W))
    val in_msg    = Input(UInt(24.W))
    val out_msg   = Output(UInt(24.W))
    val out_tag   = Output(UInt(128.W))
  })

  // 把 SV 文件加入 FIRRTL 编译列表
  addResource("/asconaead24.sv")
  addResource("/asconp_boom.sv")
  addResource("./config.svh")
}

// class cc_isa_encptr_req(implicit p: Parameters)extends BoomBundle with HasBoomUOP
// {
//   val decrypt_addr = UInt(64.W)
//   val power_with_version = UInt(64.W)
// }

// class cc_isa_encptr_resp(implicit p: Parameters)extends BoomBundle with HasBoomUOP
// {
//   val encptr_addr = UInt(64.W)
// }

class addr_req(implicit p: Parameters) extends BoomBundle with HasBoomUOP
{
  val addr = UInt((vaddrBits+1).W)
  val data = UInt(xLen.W)
  val data_valid = Bool()
}

class addr_resp(implicit p: Parameters) extends BoomBundle  with HasBoomUOP
{
  val addr = UInt((vaddrBits+1).W)
  val origin_addr = UInt((vaddrBits+1).W)
  val data = UInt(xLen.W)
  val data_valid = Bool()
}

class msg_output(implicit p:Parameters)extends BoomBundle  with HasBoomUOP
{
  val is_insn = Bool() 
  val addr = UInt(64.W)
  val data = UInt(xLen.W)
  val data_valid = Bool()
  val src = UInt(2.W)
}

// 当处于 s mode时， 只有 os_pointer_data_key有效
class pointer_engine(implicit p: Parameters) extends BoomModule
{
  private val srcFetch = 1.U(2.W)
  private val srcLsu   = 2.U(2.W)
  private val srcAlu   = 3.U(2.W)
  private val watchVpn0 = "h40000".U(26.W)
  private val watchVpn1 = "h40002".U(26.W)

  val io = IO(new Bundle {
    val fetch_addr_req    = Flipped(DecoupledIO(new addr_req))
    // val fetch_addr_resp   =  DecoupledIO(new addr_resp)
    val pointer_key = Input(UInt(128.W))
    val alu_addr_req = Flipped(DecoupledIO(new addr_req))
    val lsu_addr_req = Flipped(DecoupledIO(new addr_req))
    val lsu_addr_resp = Vec(2,DecoupledIO(new addr_resp))
    val write_fetch_tlb = Valid(new Bundle {
      val key = UInt(26.W)
      val value = UInt(26.W)
      })
    val write_lsu_tlb = Valid(new Bundle {
      val key = UInt(26.W)
      val value = UInt(26.W)
      })
    // val fetch_addr_flush = Input(Bool())
    val c4_flush = Input(Bool())
  })
  private def watchFetchAddr(addr: UInt): Bool = true.B
  private def watchAddrVpn(addr: UInt): Bool = addr(37,12) === watchVpn0 || addr(37,12) === watchVpn1
  private def watchAddrFocus(addr: UInt): Bool = watchFetchAddr(addr) && watchAddrVpn(addr)

  val ascon = Module(new asconaead64())

  io.fetch_addr_req.ready := true.B
  io.lsu_addr_req.ready := true.B
  io.alu_addr_req.ready := true.B

  val in_msg_reg = Reg(Valid(new msg_output()))
//   val cc_isa_encptr_out_msg_reg = Reg(Valid(new msg_output()))

  io.write_fetch_tlb.valid := false.B
  io.write_fetch_tlb.bits := DontCare
  io.write_lsu_tlb.valid := false.B
  io.write_lsu_tlb.bits := DontCare

  // io.fetch_addr_resp.valid := false.B
  // io.fetch_addr_resp.bits := DontCare
  io.lsu_addr_resp(0).valid := false.B
  io.lsu_addr_resp(0).bits  := DontCare
  io.lsu_addr_resp(1).valid := false.B
  io.lsu_addr_resp(1).bits  := DontCare

  when(io.fetch_addr_req.valid )
  {
    // io.alu_addr_req.ready := false.B
    io.lsu_addr_req.ready := false.B
    when (io.alu_addr_req.valid)
    {
      // printf("Vec 2 \n")
      io.lsu_addr_resp(1).valid := true.B
      io.lsu_addr_resp(1).bits.addr  := io.alu_addr_req.bits.addr
      io.lsu_addr_resp(1).bits.uop   := io.alu_addr_req.bits.uop
      io.lsu_addr_resp(1).bits.origin_addr := io.alu_addr_req.bits.addr
      io.lsu_addr_resp(1).bits.data := io.alu_addr_req.bits.data
      io.lsu_addr_resp(1).bits.data_valid := io.alu_addr_req.bits.data_valid
    }
    // printf("recieve fetch addr req : 0x%x\n",io.fetch_addr_req.bits.addr)
    // ascon.io.in_mode :=  1.U(4.W)
    // ascon.io.in_key := io.pointer_key
    // ascon.io.in_nonce := 0.U
    // printf("fetch in msg : 0x%x\n", Cat( 0.U(63-vaddrBits),io.fetch_addr_req.bits.addr(vaddrBits,12),0.U(12.W) ))
    // ascon.io.in_msg :=  Cat( 0.U(63-vaddrBits),io.fetch_addr_req.bits.addr(vaddrBits,12),0.U(12.W) )
    // out_msg_reg.valid := true.B
    // out_msg_reg.bits.addr := Cat( io.fetch_addr_req.bits.addr(vaddrBits,38),ascon.io.out_msg(37,12),io.fetch_addr_req.bits.addr(11,0) )
    // out_msg_reg.bits.uop := DontCare
    // out_msg_reg.bits.is_insn := true.B
    //  printf("fetch result : 0x%x\n", ascon.io.out_msg)
    // printf()
    // io.write_fetch_tlb.valid := true.B
    // io.write_fetch_tlb.bits.key := io.fetch_addr_req.bits.addr(37,12)
    // io.write_fetch_tlb.bits.value := ascon.io.out_msg(37,12)
    in_msg_reg.valid := true.B
    in_msg_reg.bits.is_insn := true.B
    in_msg_reg.bits.addr := io.fetch_addr_req.bits.addr
    in_msg_reg.bits.data := io.fetch_addr_req.bits.data
    in_msg_reg.bits.data_valid := io.fetch_addr_req.bits.data_valid
    in_msg_reg.bits.uop := io.fetch_addr_req.bits.uop
    in_msg_reg.bits.src := srcFetch
    when (watchFetchAddr(io.fetch_addr_req.bits.addr)) {
      printf(p"[ADDR-ENG-FETCH-REQ] addr=0x${Hexadecimal(io.fetch_addr_req.bits.addr)} flush=${io.c4_flush.asUInt}\n")
    }
    when (watchAddrFocus(io.fetch_addr_req.bits.addr)) {
      printf(p"[ADDR-ENG-FOCUS-REQ] src=fetch addr=0x${Hexadecimal(io.fetch_addr_req.bits.addr)} " +
        p"vpn=0x${Hexadecimal(io.fetch_addr_req.bits.addr(37,12))} key_lo=0x${Hexadecimal(io.pointer_key(63,0))} " +
        p"key_hi=0x${Hexadecimal(io.pointer_key(127,64))} flush=${io.c4_flush.asUInt}\n")
    }
  }.elsewhen(io.alu_addr_req.valid){
    // MemAddrCalc async requests are one-shot. Prioritize them over LSU retries,
    // which remain queued in the LDQ/STQ and can be re-issued later.
    io.lsu_addr_req.ready := false.B
    // printf("recieve alu_addr_req \n");
    // ascon.io.in_mode := 1.U(4.W)
    // ascon.io.in_key := io.pointer_key
    // ascon.io.in_nonce := 0.U
    // ascon.io.in_msg :=  Cat( 0.U(63-vaddrBits),io.alu_addr_req.bits.addr(vaddrBits,12),0.U(12.W) )
    // printf("alu in msg : 0x%x\n",Cat( 0.U(63-vaddrBits),io.alu_addr_req.bits.addr(vaddrBits,12),0.U(12.W) ))
    in_msg_reg.valid := true.B
    in_msg_reg.bits.addr := io.alu_addr_req.bits.addr
    in_msg_reg.bits.data := io.alu_addr_req.bits.data
    in_msg_reg.bits.data_valid := io.alu_addr_req.bits.data_valid
    // in_msg_reg.bits.addr := Cat(io.alu_addr_req.bits.addr(vaddrBits,38),ascon.io.out_msg(37,12),io.alu_addr_req.bits.addr(11,0))
    // printf("alu result : 0x%x\n", ascon.io.out_msg)
    // printf("out_msg_reg : 0x%x\n", Cat(io.alu_addr_req.bits.addr(vaddrBits,38),ascon.io.out_msg(37,12),io.alu_addr_req.bits.addr(11,0)))
    in_msg_reg.bits.uop := io.alu_addr_req.bits.uop
    in_msg_reg.bits.is_insn := false.B
    in_msg_reg.bits.src := srcAlu
    printf(p"[ADDR-ENG-ALU-REQ] addr=0x${Hexadecimal(io.alu_addr_req.bits.addr)} " +
      p"data=0x${Hexadecimal(io.alu_addr_req.bits.data)} valid=${io.alu_addr_req.bits.data_valid.asUInt}\n")
    when (watchAddrFocus(io.alu_addr_req.bits.addr)) {
      printf(p"[ADDR-ENG-FOCUS-REQ] src=alu addr=0x${Hexadecimal(io.alu_addr_req.bits.addr)} " +
        p"vpn=0x${Hexadecimal(io.alu_addr_req.bits.addr(37,12))} key_lo=0x${Hexadecimal(io.pointer_key(63,0))} " +
        p"key_hi=0x${Hexadecimal(io.pointer_key(127,64))} data=0x${Hexadecimal(io.alu_addr_req.bits.data)} " +
        p"valid=${io.alu_addr_req.bits.data_valid.asUInt}\n")
    }
    // io.write_lsu_tlb.valid := true.B
    // io.write_lsu_tlb.bits.key := io.alu_addr_req.bits.addr(37,12)
    // io.write_lsu_tlb.bits.value := ascon.io.out_msg(37,12)
  }.elsewhen (io.lsu_addr_req.valid)
  {
    // printf("recieve lsu_addr_req : 0x%x\n",io.lsu_addr_req.bits.addr);
    // ascon.io.in_mode :=  1.U(4.W)
    // ascon.io.in_key := io.pointer_key
    // ascon.io.in_nonce := 0.U
    // ascon.io.in_msg :=  Cat( 0.U(63-vaddrBits),io.lsu_addr_req.bits.addr(vaddrBits,12),0.U(12.W) )
    //  printf("lsu in msg : 0x%x\n",io.lsu_addr_req.bits.addr(37,12))
    in_msg_reg.valid := true.B
    in_msg_reg.bits.is_insn := false.B
    in_msg_reg.bits.addr := io.lsu_addr_req.bits.addr
    in_msg_reg.bits.data := io.lsu_addr_req.bits.data
    in_msg_reg.bits.data_valid := io.lsu_addr_req.bits.data_valid
    in_msg_reg.bits.uop := io.lsu_addr_req.bits.uop 
    in_msg_reg.bits.src := srcLsu
    printf(p"[ADDR-ENG-LSU-REQ] addr=0x${Hexadecimal(io.lsu_addr_req.bits.addr)} " +
      p"data=0x${Hexadecimal(io.lsu_addr_req.bits.data)} valid=${io.lsu_addr_req.bits.data_valid.asUInt}\n")
    when (watchAddrFocus(io.lsu_addr_req.bits.addr)) {
      printf(p"[ADDR-ENG-FOCUS-REQ] src=lsu addr=0x${Hexadecimal(io.lsu_addr_req.bits.addr)} " +
        p"vpn=0x${Hexadecimal(io.lsu_addr_req.bits.addr(37,12))} key_lo=0x${Hexadecimal(io.pointer_key(63,0))} " +
        p"key_hi=0x${Hexadecimal(io.pointer_key(127,64))} data=0x${Hexadecimal(io.lsu_addr_req.bits.data)} " +
        p"valid=${io.lsu_addr_req.bits.data_valid.asUInt}\n")
    }

    // out_msg_reg.bits.addr := Cat( io.lsu_addr_req.bits.addr(vaddrBits,38),ascon.io.out_msg(37,12),io.lsu_addr_req.bits.addr(11,0) )
    // out_msg_reg.bits.uop := io.lsu_addr_req.bits.uop
    // out_msg_reg.bits.is_insn := false.B

    // io.write_lsu_tlb.valid := true.B
    // io.write_lsu_tlb.bits.key := io.lsu_addr_req.bits.addr(37,12)
    // io.write_lsu_tlb.bits.value := ascon.io.out_msg(37,12)

  }.otherwise {
    // ascon.io.in_mode := 0.U(4.W)
    // ascon.io.in_key := DontCare
    // ascon.io.in_nonce := DontCare 
    // ascon.io.in_msg := DontCare
    in_msg_reg.valid := false.B
    in_msg_reg.bits := DontCare
  }
  

  when (in_msg_reg.valid)
  {
    when( in_msg_reg.bits.is_insn === true.B)
    {
      // printf ("aes recieve request \n")
      // 现在的key 还是 hardcode
      //val key = 0xffffff.U
      //val decrypt_addr = key ^ RegNext(req_reg).bits.ca(57,34)
      // 这里 有几位 是 time version  
      when (!io.c4_flush)
      {
        ascon.io.in_mode :=  1.U(4.W)
        ascon.io.in_key := io.pointer_key
        ascon.io.in_nonce := 0.U
        // printf("fetch in msg addr: 0x%x\n", Cat( 0.U(63-vaddrBits),in_msg_reg.bits.addr(vaddrBits,12),0.U(12.W) ) )
        ascon.io.in_msg :=  Cat( 0.U(63-vaddrBits),in_msg_reg.bits.addr(vaddrBits,12),0.U(12.W) )
        
        // io.fetch_addr_resp.valid := true.B
        // io.fetch_addr_resp.bits.uop := DontCare
        // io.fetch_addr_resp.bits.addr := Cat(in_msg_reg.bits.addr(vaddrBits,38),ascon.io.out_msg(37,12),in_msg_reg.bits.addr(11,0))
        // printf("fetch out : 0x%x\n",Cat(in_msg_reg.bits.addr(vaddrBits,38),ascon.io.out_msg(37,12),in_msg_reg.bits.addr(11,0)))

        io.write_fetch_tlb.valid := true.B
        io.write_fetch_tlb.bits.key := in_msg_reg.bits.addr(37,12)
        io.write_fetch_tlb.bits.value := ascon.io.out_msg(37,12)
        when (watchFetchAddr(in_msg_reg.bits.addr)) {
          val encAddr = Cat(in_msg_reg.bits.addr(vaddrBits,38), ascon.io.out_msg(37,12), in_msg_reg.bits.addr(11,0))
          val origPage = Cat(in_msg_reg.bits.addr(37,12), 0.U(12.W))
          val encPage = Cat(ascon.io.out_msg(37,12), 0.U(12.W))
          printf(p"[ADDR-ENG-FETCH-ENC] orig_addr=0x${Hexadecimal(in_msg_reg.bits.addr)} " +
            p"enc_addr=0x${Hexadecimal(encAddr)} orig_page=0x${Hexadecimal(origPage)} " +
            p"enc_page=0x${Hexadecimal(encPage)} key=0x${Hexadecimal(in_msg_reg.bits.addr(37,12))} " +
            p"value=0x${Hexadecimal(ascon.io.out_msg(37,12))}\n")
        }
        when (watchAddrFocus(in_msg_reg.bits.addr)) {
          val encAddr = Cat(in_msg_reg.bits.addr(vaddrBits,38), ascon.io.out_msg(37,12), in_msg_reg.bits.addr(11,0))
          printf(p"[ADDR-ENG-FOCUS-ENC] src=fetch orig_addr=0x${Hexadecimal(in_msg_reg.bits.addr)} " +
            p"enc_addr=0x${Hexadecimal(encAddr)} orig_vpn=0x${Hexadecimal(in_msg_reg.bits.addr(37,12))} " +
            p"enc_vpn=0x${Hexadecimal(ascon.io.out_msg(37,12))} msg=0x${Hexadecimal(ascon.io.in_msg)} " +
            p"key_lo=0x${Hexadecimal(io.pointer_key(63,0))} key_hi=0x${Hexadecimal(io.pointer_key(127,64))}\n")
        }
      }.otherwise{
        ascon.io.in_mode := 0.U(4.W)
        ascon.io.in_key := DontCare
        ascon.io.in_nonce := DontCare 
        ascon.io.in_msg := DontCare
      }

    }.otherwise{
      ascon.io.in_mode :=  1.U(4.W)
      ascon.io.in_key := io.pointer_key
      ascon.io.in_nonce := 0.U
      // printf("lsu in msg : 0x%x\n", Cat( 0.U(63-vaddrBits),in_msg_reg.bits.addr(vaddrBits,12),0.U(12.W) ))
      ascon.io.in_msg :=  Cat( 0.U(63-vaddrBits),in_msg_reg.bits.addr(vaddrBits,12),0.U(12.W) )
      val encAddr = Cat(in_msg_reg.bits.addr(vaddrBits,38),ascon.io.out_msg(37,12),in_msg_reg.bits.addr(11,0))
      val origPage = Cat(in_msg_reg.bits.addr(37,12), 0.U(12.W))
      val encPage = Cat(ascon.io.out_msg(37,12), 0.U(12.W))
      when (in_msg_reg.bits.src === srcAlu) {
        printf(p"[ADDR-ENG-ALU-ENC] orig_addr=0x${Hexadecimal(in_msg_reg.bits.addr)} " +
          p"enc_addr=0x${Hexadecimal(encAddr)} orig_page=0x${Hexadecimal(origPage)} " +
          p"enc_page=0x${Hexadecimal(encPage)} key=0x${Hexadecimal(in_msg_reg.bits.addr(37,12))} " +
          p"value=0x${Hexadecimal(ascon.io.out_msg(37,12))}\n")
      }
      when (watchAddrFocus(in_msg_reg.bits.addr)) {
        val srcStr = Mux(in_msg_reg.bits.src === srcAlu, "h616c75".U, "h6c7375".U)
        printf(p"[ADDR-ENG-FOCUS-ENC] src=0x${Hexadecimal(srcStr)} orig_addr=0x${Hexadecimal(in_msg_reg.bits.addr)} " +
          p"enc_addr=0x${Hexadecimal(encAddr)} orig_vpn=0x${Hexadecimal(in_msg_reg.bits.addr(37,12))} " +
          p"enc_vpn=0x${Hexadecimal(ascon.io.out_msg(37,12))} msg=0x${Hexadecimal(ascon.io.in_msg)} " +
          p"key_lo=0x${Hexadecimal(io.pointer_key(63,0))} key_hi=0x${Hexadecimal(io.pointer_key(127,64))}\n")
      }
      
      io.lsu_addr_resp(0).valid := true.B
      io.lsu_addr_resp(0).bits.addr  := Cat(in_msg_reg.bits.addr(vaddrBits,38),ascon.io.out_msg(37,12),in_msg_reg.bits.addr(11,0))
      io.lsu_addr_resp(0).bits.uop := in_msg_reg.bits.uop
      io.lsu_addr_resp(0).bits.origin_addr := in_msg_reg.bits.addr
      io.lsu_addr_resp(0).bits.data := in_msg_reg.bits.data
      io.lsu_addr_resp(0).bits.data_valid := in_msg_reg.bits.data_valid
      // printf("lsu out : 0x%x\n",Cat(in_msg_reg.bits.addr(vaddrBits,38),ascon.io.out_msg(37,12),in_msg_reg.bits.addr(11,0)))

      io.write_lsu_tlb.valid := true.B
      io.write_lsu_tlb.bits.key := in_msg_reg.bits.addr(37,12)
      io.write_lsu_tlb.bits.value := ascon.io.out_msg(37,12)
      when (in_msg_reg.bits.src === srcLsu) {
        printf(p"[ADDR-ENG-LSU-ENC] orig_addr=0x${Hexadecimal(in_msg_reg.bits.addr)} " +
          p"enc_addr=0x${Hexadecimal(encAddr)} orig_page=0x${Hexadecimal(origPage)} " +
          p"enc_page=0x${Hexadecimal(encPage)} key=0x${Hexadecimal(in_msg_reg.bits.addr(37,12))} " +
          p"value=0x${Hexadecimal(ascon.io.out_msg(37,12))}\n")
      }
    }
  }.otherwise{
    ascon.io.in_mode := 0.U(4.W)
    ascon.io.in_key := DontCare
    ascon.io.in_nonce := DontCare 
    ascon.io.in_msg := DontCare
  }
}

// class data_req(implicit p: Parameters) extends BoomBundle with HasBoomUOP
// {
//   val addr = UInt(64.W)
//   val data = UInt(64.W)
// }

// class data_resp(implicit p: Parameters) extends BoomBundle with HasBoomUOP
// {
//   // bool 用于判断是 insn 还是dat
//   // val is_insn = Bool() 
//   val data = UInt(64.W)
//   val addr = UInt(64.W)
// }

// class data_engine(implicit p : Parameters) extends BoomModule
// {
//   require(icacheParams.fetchBytes == 8)
//   val io = IO(new Bundle{
//     val fetch_data_req  = Flipped(DecoupledIO(new data_req()))
//     val fetch_data_resp  = DecoupledIO(new data_resp())
//     // val fetch_data_flush = Input(Bool())
//     val data_key = Input(UInt(128.W))
//     val c4_flush = Input(Bool())
//     })
    
//   val ascon = Module(new asconaead64())

//   val out_msg_reg = RegInit(0.U.asTypeOf(Valid(new data_resp())))
//   val resp_slot_ready = !out_msg_reg.valid || io.fetch_data_resp.ready

//   io.fetch_data_req.ready := resp_slot_ready && !io.c4_flush
//   io.fetch_data_resp.valid := out_msg_reg.valid && !io.c4_flush
//   io.fetch_data_resp.bits := out_msg_reg.bits

//   ascon.io.in_mode := Mux(io.fetch_data_req.fire, 2.U(4.W), 0.U(4.W))
//   ascon.io.in_key := Mux(io.fetch_data_req.fire, io.data_key, 0.U)
//   ascon.io.in_nonce := Mux(io.fetch_data_req.fire, Cat(0.U(64.W), io.fetch_data_req.bits.addr), 0.U)
//   ascon.io.in_msg := Mux(io.fetch_data_req.fire, io.fetch_data_req.bits.data, 0.U)

//   when (io.c4_flush) {
//     out_msg_reg.valid := false.B
//   } .elsewhen (io.fetch_data_req.fire) {
//     printf("fetch data req : 0x%x\n",io.fetch_data_req.bits.data)
//     printf("fetch data req addr : 0x%x\n",io.fetch_data_req.bits.addr)
//     out_msg_reg.valid := true.B
//     out_msg_reg.bits.data := ascon.io.out_msg
//     out_msg_reg.bits.addr := io.fetch_data_req.bits.addr
//     out_msg_reg.bits.uop := DontCare
//   } .elsewhen (io.fetch_data_resp.fire) {
//     out_msg_reg.valid := false.B
//   }

//   when (io.fetch_data_resp.fire) {
//     printf("fetch data out : 0x%x\n",out_msg_reg.bits.data);
//   }
// }

// class lsu_data_engine(implicit p : Parameters) extends BoomModule with HasL1HellaCacheParameters
// {
//   // 目前只考虑 memwidth为1的情况,只有一个memaddrcalc
//   val io = IO(new Bundle{
//     // val alu_data_req = Flipped(DecoupledIO(new data_req()))
//     // val rreg_data_req = Flipped(DecoupledIO(new data_req()))
//     // val alu_data_req = Flipped(DecoupledIO(new data_req()))
//     // val lsu_data_req = Flipped(DecoupledIO(new data_req()))
//     // val lsu_data_resp = DecoupledIO(new data_resp())
//     val data_key = Input(UInt(128.W)) 
//     val c4_flush = Input(Bool())
//   })

//   val ascon = Module(new asconaead64())
  
//   // io.alu_data_req.ready := true.Blsu_data_req
//   // io.lsu_data_req.ready := true.B
//   // io.rreg_data_req.ready := true.B
//   // io.alu_data_req.ready := true.B

//   val out_msg_reg = Reg(Valid(new data_resp()))

//   val in_msg_reg2 = Reg(Valid(new data_resp()))

//   // lsu一定优先, 因为现在  lsu中 store 的 数据还不存在重发逻辑,因此 一定是 经过 lsu 发过来的数据
//   // 如果 memwidth 改成 2 ,那么就需要修改这里的逻辑
//   when (io.lsu_data_req.valid)
//   {
//     io.rreg_data_req.ready := false.B
//     io.alu_data_req.ready := true.B 
//     // printf("recieve lsu data req \n");
//     when (io.lsu_data_req.bits.uop.uses_ldq )
//     {
//       ascon.io.in_mode := 2.U(4.W)
//       ascon.io.in_msg := io.lsu_data_req.bits.data
//     }.otherwise{
//       ascon.io.in_mode := 1.U(4.W)
//       ascon.io.in_msg := new freechips.rocketchip.rocket.StoreGen( io.lsu_data_req.bits.uop.mem_size,  io.lsu_data_req.bits.addr, io.lsu_data_req.bits.data, wordBytes).data
//     }
//     ascon.io.in_key := io.data_key
//     ascon.io.in_nonce := Cat(0.U(64.W),(io.lsu_data_req.bits.addr >> 3.U) << 3.U)
//     printf("recieve lsu data req addr : 0x%x\n",io.lsu_data_req.bits.addr)
//     printf("recieve lsu data req : 0x%x\n",io.lsu_data_req.bits.data);
//     printf("ascon in msg : 0x%x\n",ascon.io.in_msg)
//     printf("ascon nonce : 0x%x\n",ascon.io.in_nonce)
//     out_msg_reg.valid := true.B
//     out_msg_reg.bits.data := ascon.io.out_msg
//     out_msg_reg.bits.addr := io.lsu_data_req.bits.addr
//     out_msg_reg.bits.uop := io.lsu_data_req.bits.uop
//   }.elsewhen(io.rreg_data_req.valid)
//   {

//     // fetch data 对一定是 对齐的
//     io.alu_data_req.ready := false.B
//     printf("recieve rreg data req \n");
//     when (io.rreg_data_req.bits.uop.uses_ldq )
//     {
//       ascon.io.in_mode := 2.U(4.W)
//     }.otherwise{
//       ascon.io.in_mode := 1.U(4.W)
//     }
//     ascon.io.in_key := io.data_key
//     ascon.io.in_nonce := Cat(0.U(64.W),( io.rreg_data_req.bits.addr >> 3.U) << 3.U)
//     ascon.io.in_msg := io.rreg_data_req.bits.data
//     out_msg_reg.valid := true.B
//     out_msg_reg.bits.data := ascon.io.out_msg
//     out_msg_reg.bits.addr := io.rreg_data_req.bits.addr
//     out_msg_reg.bits.uop := io.rreg_data_req.bits.uop
//   }.elsewhen(io.alu_data_req.valid){
//     // printf("recieve alu data req \n");
//     when (io.alu_data_req.bits.uop.uses_ldq )
//     {
//       ascon.io.in_mode := 2.U(4.W)
//       ascon.io.in_msg := io.alu_data_req.bits.data
//     }.otherwise{
//       ascon.io.in_mode := 1.U(4.W)
//       ascon.io.in_msg := new freechips.rocketchip.rocket.StoreGen( io.alu_data_req.bits.uop.mem_size,   
//               io.alu_data_req.bits.addr, io.alu_data_req.bits.data, wordBytes).data
//     }
//     ascon.io.in_key := io.data_key
//     ascon.io.in_nonce := Cat(0.U(64.W),(io.alu_data_req.bits.addr >> 3.U) << 3.U)
//     // printf("data key : 0x%x\n",io.data_key)
//     printf("recieve alu data req addr : 0x%x\n",io.alu_data_req.bits.addr);
//     printf("recieve alu data req : 0x%x\n",io.alu_data_req.bits.data);
//     printf("ascon in msg : 0x%x\n",ascon.io.in_msg)
//     printf("ascon in nonce : 0x%x\n",ascon.io.in_nonce);
//     out_msg_reg.valid := true.B
//     out_msg_reg.bits.addr := io.alu_data_req.bits.addr 
//     out_msg_reg.bits.data := ascon.io.out_msg
//     out_msg_reg.bits.uop := io.alu_data_req.bits.uop
//   }otherwise{
//     ascon.io.in_mode := 0.U(4.W)
//     ascon.io.in_key := DontCare
//     ascon.io.in_nonce := DontCare 
//     ascon.io.in_msg := DontCare
//     out_msg_reg.valid := false.B
//     out_msg_reg.bits := DontCare
//   }


//   val loadgen = new freechips.rocketchip.rocket.LoadGen(
//     out_msg_reg.bits.uop.mem_size,
//     out_msg_reg.bits.uop.mem_signed,
//     out_msg_reg.bits.addr,
//     out_msg_reg.bits.data,
//     false.B,          // 在 engine 里一般不处理 SC 语义
//     wordBytes
//   )

  // load：用裁减/符号扩展后的数据
  // 非 load：保持全宽 out_data（比如 store/ALU 数据加密）
//   val final_data = Mux(out_msg_reg.bits.uop.uses_ldq, loadgen.data, out_msg_reg.bits.data)



//   when(out_msg_reg.valid)
//   {

//     io.lsu_data_resp.valid := true.B
//     io.lsu_data_resp.bits.data := final_data
//     io.lsu_data_resp.bits.uop := out_msg_reg.bits.uop
//     io.lsu_data_resp.bits.addr := DontCare
//     when(out_msg_reg.bits.uop.uses_ldq)
//     {
//       printf("origin data out : 0x%x\n",out_msg_reg.bits.data);
//     }
//     printf("lsu data engine out : 0x%x\n",final_data)
//   }.otherwise{
//     io.lsu_data_resp.valid := false.B
//     io.lsu_data_resp.bits := DontCare
//     io.lsu_data_resp.bits.addr := DontCare
//   }
// }
