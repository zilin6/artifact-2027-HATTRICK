package boom.v3.ifu

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters

import boom.v3.common._
import boom.v3.util.{AsconCryptoMode, AsconCryptoParams, asconaead64}
import freechips.rocketchip.rocket.{CacheCryptoCounterBitsKey}

class BoomICacheCryptoEngineReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomFrontendParameters
{
  val paddr = UInt(paddrBits.W)
  val cryptoLine = Bool()
  val counter = UInt(p(CacheCryptoCounterBitsKey).W)
  val cipherData = UInt((fetchBytes * 8).W)
}

class BoomICacheCryptoEngineResp(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomFrontendParameters
{
  val plainData = UInt((fetchBytes * 8).W)
}

class BoomICacheCryptoEngine(implicit p: Parameters) extends BoomModule()(p)
  with HasBoomFrontendParameters
{
  private val wordBits = fetchBytes * 8
  private val debugWatchPaddrLine = BigInt("80402940", 16).U(paddrBits.W)
  require(wordBits == 64, "Ascon icache crypto path currently expects 64b fetch data")

  val io = IO(new Bundle {
    val dataKey = Input(UInt(128.W))
    val log = Input(Bool())
    val req = Input(Valid(new BoomICacheCryptoEngineReq))
    val resp = Output(Valid(new BoomICacheCryptoEngineResp))
  })

  val decryptor = Module(new asconaead64())
  val decryptNonce = AsconCryptoParams.nonce(io.req.bits.counter, io.req.bits.paddr)
  private def dbgWatchReq: Bool =
    io.req.bits.cryptoLine &&
    (io.req.bits.paddr & ~((fetchBytes - 1).U(paddrBits.W))) === debugWatchPaddrLine

  decryptor.io.in_mode := AsconCryptoMode.decrypt
  decryptor.io.in_key := io.dataKey
  decryptor.io.in_nonce := decryptNonce
  decryptor.io.in_msg := io.req.bits.cipherData

  when (io.req.valid && io.log) {
    // printf("[L1I-ASCON-IO] crypto=%d mode=%d paddr=0x%x ctr=0x%x nonce=0x%x key=0x%x in=0x%x out=0x%x tag=0x%x\n",
      // io.req.bits.cryptoLine,
      // decryptor.io.in_mode,
      // io.req.bits.paddr,
      // io.req.bits.counter,
      // decryptNonce,
      // io.dataKey,
      // io.req.bits.cipherData,
      // decryptor.io.out_msg,
      // decryptor.io.out_tag)
  }
  when (io.req.valid && dbgWatchReq) {
    chisel3.printf("[L1I-KEY-FOCUS] paddr=0x%x ctr=0x%x nonce=0x%x key=0x%x cipher=0x%x plain=0x%x\n",
      io.req.bits.paddr,
      io.req.bits.counter,
      decryptNonce,
      io.dataKey,
      io.req.bits.cipherData,
      decryptor.io.out_msg)
  }
  when (io.req.valid && io.log) {
    // printf("[L1I-ASCON-REQ] paddr=0x%x crypto=%d ctr=0x%x in=0x%x\n",
      // io.req.bits.paddr,
      // io.req.bits.cryptoLine,
      // io.req.bits.counter,
      // io.req.bits.cipherData)
  }

  // val respValidReg = RegInit(false.B)
  val respDataReg = Reg(UInt(wordBits.W))
  val respValidReg = RegNext(io.req.valid, false.B)
  when (io.req.valid) {
    respDataReg := Mux(io.req.bits.cryptoLine,
      decryptor.io.out_msg,
      io.req.bits.cipherData)
  }

  io.resp.valid := respValidReg
  io.resp.bits.plainData := respDataReg
}
