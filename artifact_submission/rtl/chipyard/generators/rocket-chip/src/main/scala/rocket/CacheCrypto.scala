// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package freechips.rocketchip.rocket

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Field
import org.chipsalliance.cde.config.Parameters

import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.{BundleField, ControlKey, PlusArg}

case object CacheCryptoEnableKey extends Field[Boolean](false)
case object CacheCryptoNonceBitsKey extends Field[Int](64)
case object CacheCryptoCounterBitsKey extends Field[Int](64)
case class CacheCryptoDebugLogParams(
  plusArgName: String = "cache_crypto_debug_log",
  defaultEnable: Int = 0)
case object CacheCryptoDebugLogKey extends Field[CacheCryptoDebugLogParams](CacheCryptoDebugLogParams())

object CacheCryptoDefaults {
  val nonceBits = 64
  val counterBits = 64
  val counterMax = (BigInt(1) << counterBits) - 1
}

class CacheCryptoConfigBundle extends Bundle {
  val enable = Bool()
  val enable_wen = Bool()
  val base = UInt(64.W)
  val base_wen = Bool()
}

class CacheCryptoMetaBundle(
  val nonceBits: Int = CacheCryptoDefaults.nonceBits,
  val counterBits: Int = CacheCryptoDefaults.counterBits) extends Bundle {
  val counter = UInt(counterBits.W)
  val cryptoLine = Bool()
}

case object CacheCryptoRefillMeta extends ControlKey[CacheCryptoMetaBundle]("cache_crypto_refill_meta")
case object CacheCryptoWritebackMeta extends ControlKey[CacheCryptoMetaBundle]("cache_crypto_writeback_meta")

case class CacheCryptoRefillMetaField(
  nonceBits: Int = CacheCryptoDefaults.nonceBits,
  counterBits: Int = CacheCryptoDefaults.counterBits)
    extends BundleField[CacheCryptoMetaBundle](
      CacheCryptoRefillMeta,
      Output(new CacheCryptoMetaBundle(nonceBits, counterBits)),
      x => {
        x.counter := 0.U
        x.cryptoLine := false.B
      })

case class CacheCryptoWritebackMetaField(
  nonceBits: Int = CacheCryptoDefaults.nonceBits,
  counterBits: Int = CacheCryptoDefaults.counterBits)
    extends BundleField[CacheCryptoMetaBundle](
      CacheCryptoWritebackMeta,
      Output(new CacheCryptoMetaBundle(nonceBits, counterBits)),
      x => {
        x.counter := 0.U
        x.cryptoLine := false.B
      })

object CacheCrypto {
  def nonceEff(nonce: UInt, blockAddr: UInt): UInt = {
    // Keep the interface stable, but in the simplified model the effective
    // nonce is just the counter-like input itself.
    nonce
  }

  def keystream(dataWidth: Int, nonceEff: UInt, beatIdx: UInt): UInt = {
    // Simplified crypto model: XOR data with the counter-like value and
    // ignore beatIdx and other address-derived mixing.
    if (nonceEff.getWidth >= dataWidth) {
      nonceEff(dataWidth - 1, 0)
    } else {
      Cat(0.U((dataWidth - nonceEff.getWidth).W), nonceEff)
    }
  }

  def cryptBeat(data: UInt, nonceEff: UInt, beatIdx: UInt): UInt = {
    data ^ keystream(data.getWidth, nonceEff, beatIdx)
  }
}

object CacheCryptoDebugLog {
  def runtimeEnable(implicit p: Parameters): Bool = {
    val params = p(CacheCryptoDebugLogKey)
    PlusArg(
      params.plusArgName,
      default = params.defaultEnable,
      width = 1,
      docstring = "Enable BOOM cache-crypto debug logging").orR
  }
}

// class CacheCryptoBeat(
//   dataBits: Int,
//   nonceBits: Int = CacheCryptoDefaults.nonceBits,
//   counterBits: Int = CacheCryptoDefaults.counterBits,
//   beatIdxBits: Int = 8) extends Module {
//   val io = IO(new Bundle {
//     val enable = Input(Bool())
//     val in = Input(UInt(dataBits.W))
//     val nonce = Input(UInt(nonceBits.W))
//     val counter = Input(UInt(counterBits.W))
//     val blockAddr = Input(UInt((dataBits max 1).W))
//     val beatIdx = Input(UInt(beatIdxBits.W))
//     val out = Output(UInt(dataBits.W))
//   })

//   val nonceEff = CacheCrypto.nonceEff(io.nonce, io.blockAddr)
//   val crypted = CacheCrypto.cryptBeat(io.in, nonceEff, io.beatIdx)
//   io.out := Mux(io.enable, crypted, io.in)
// }
