package freechips.rocketchip.rocket

import chisel3._

object CacheStatePreloadTarget {
  val Width = 8

  def End       : UInt = 0.U(Width.W)
  def L1Meta    : UInt = 1.U(Width.W)
  def L1Data    : UInt = 2.U(Width.W)
  def L1Counter : UInt = 3.U(Width.W)
  def L2Dir     : UInt = 4.U(Width.W)
  def L2Data    : UInt = 5.U(Width.W)
  def L2Counter : UInt = 6.U(Width.W)
}

object CacheStatePreloadBoringNames {
  val Active  = "cache_state_preload_active"
  val Valid   = "cache_state_preload_valid"
  val Header  = "cache_state_preload_header"
  val Payload = "cache_state_preload_payload"
  val AckL1   = "cache_state_preload_ack_l1"
  val AckL2   = "cache_state_preload_ack_l2"
  val ReadyL1 = "cache_state_preload_ready_l1"
  val ReadyL2 = "cache_state_preload_ready_l2"
}

object CacheCryptoBasePreloadBoringNames {
  val Value = "cache_crypto_base_preload_value"
  val Wen = "cache_crypto_base_preload_wen"
  val Done = "cache_crypto_base_preload_done"
}

object CacheStatePreloadFields {
  val HeaderBytes = 8
  val PayloadBytes = 8
  val RecordBytes = HeaderBytes + PayloadBytes

  def target(header: UInt): UInt = header(7, 0)
  def set(header: UInt): UInt = header(23, 8)
  def way(header: UInt): UInt = header(31, 24)
  def beat(header: UInt): UInt = header(39, 32)
  def mask(header: UInt): UInt = header(47, 40)

  def targetsL1(target: UInt): Bool =
    target === CacheStatePreloadTarget.L1Meta ||
      target === CacheStatePreloadTarget.L1Data ||
      target === CacheStatePreloadTarget.L1Counter

  def targetsL2(target: UInt): Bool =
    target === CacheStatePreloadTarget.L2Dir ||
      target === CacheStatePreloadTarget.L2Data ||
      target === CacheStatePreloadTarget.L2Counter
}
