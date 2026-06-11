package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, M_XRD, M_XWR}
import freechips.rocketchip.tilelink.TLMessages.{AcquireBlock, GrantData}
import freechips.rocketchip.tilelink.TLPermissions.toT
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BoomDCacheTrapDisableReencSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val blockBytes = 64
  private val beatBytes = 8
  private val beatCount = blockBytes / beatBytes
  private val lineAddr = BigInt(0x000)
  private val missAddr = lineAddr + 6 * beatBytes
  private val chunk0Addr = lineAddr
  private val chunk6Addr = lineAddr + 6 * beatBytes
  private val lineBeats = (0 until beatCount).map(i => BigInt("4000000000000000", 16) + i)
  private val counterBase = BigInt("100000", 16)
  private val dataKey = BigInt("00112233445566778899aabbccddeeff", 16)
  private val initCounter = BigInt("ff", 16)
  private val newChunk0 = BigInt("1122334455667788", 16)

  private def clearHarness(dut: BoomDCacheRealDriverHarnessWrapper): Unit = {
    dut.io.req_valid.poke(false.B)
    dut.io.req_addr.poke(0.U)
    dut.io.req_data.poke(0.U)
    dut.io.req_cmd.poke(M_XRD)
    dut.io.req_mem_size.poke(3.U)
    dut.io.req_mem_signed.poke(false.B)

    dut.io.dataKey.poke(0.U)
    dut.io.cacheCryptoLoadEnableValue.poke(false.B)
    dut.io.cacheCryptoStoreEnableValue.poke(false.B)
    dut.io.cacheCryptoEnableWen.poke(false.B)
    dut.io.cacheCryptoCounterBaseValue.poke(0.U)
    dut.io.cacheCryptoCounterBaseWen.poke(false.B)
    dut.io.log.poke(false.B)

    val outer = dut.io.outer
    outer.a.ready.poke(true.B)
    outer.c.ready.poke(true.B)
    outer.e.ready.poke(true.B)
    outer.b.valid.poke(false.B)
    outer.d.valid.poke(false.B)
    outer.d.bits.opcode.poke(GrantData)
    outer.d.bits.param.poke(toT)
    outer.d.bits.size.poke(log2Ceil(blockBytes).U)
    outer.d.bits.source.poke(0.U)
    outer.d.bits.sink.poke(0.U)
    outer.d.bits.denied.poke(false.B)
    outer.d.bits.data.poke(0.U)
    outer.d.bits.corrupt.poke(false.B)
    outer.d.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
    }
  }

  private def setCryptoEnable(dut: BoomDCacheRealDriverHarnessWrapper, enable: Boolean): Unit = {
    dut.io.cacheCryptoLoadEnableValue.poke(enable.B)
    dut.io.cacheCryptoStoreEnableValue.poke(enable.B)
    dut.io.cacheCryptoEnableWen.poke(true.B)
    dut.clock.step()
    dut.io.cacheCryptoEnableWen.poke(false.B)
  }

  private def programCryptoMode(dut: BoomDCacheRealDriverHarnessWrapper): Unit = {
    dut.io.dataKey.poke(dataKey.U)
    dut.io.cacheCryptoCounterBaseValue.poke(counterBase.U)
    dut.io.cacheCryptoCounterBaseWen.poke(true.B)
    setCryptoEnable(dut, enable = true)
    dut.io.cacheCryptoCounterBaseWen.poke(false.B)
  }

  private def issueReq(dut: BoomDCacheRealDriverHarnessWrapper, cmd: UInt, address: BigInt, data: BigInt = 0): Unit = {
    dut.io.req_addr.poke(address.U)
    dut.io.req_data.poke(data.U)
    dut.io.req_cmd.poke(cmd)
    dut.io.req_mem_size.poke(3.U)
    dut.io.req_mem_signed.poke(false.B)
    dut.io.req_valid.poke(true.B)
    var cycles = 0
    while (!dut.io.req_ready.peek().litToBoolean && cycles < 100) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.req_ready.peek().litToBoolean, s"Timed out waiting req_ready for cmd=${cmd.litValue} addr=0x${address.toString(16)}")
    dut.clock.step()
    dut.io.req_valid.poke(false.B)
  }

  private def waitOuterAcquire(dut: BoomDCacheRealDriverHarnessWrapper, address: BigInt, maxCycles: Int = 200): BigInt = {
    val outer = dut.io.outer
    var cycles = 0
    while (!(outer.a.valid.peek().litToBoolean &&
             outer.a.bits.opcode.peek().litValue == AcquireBlock.litValue &&
             outer.a.bits.address.peek().litValue == address) &&
           cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(outer.a.valid.peek().litToBoolean, s"Timed out waiting outer AcquireBlock for 0x${address.toString(16)}")
    outer.a.bits.source.peek().litValue
  }

  private def respondGrantData(dut: BoomDCacheRealDriverHarnessWrapper, source: BigInt): Unit = {
    val outer = dut.io.outer
    outer.d.valid.poke(true.B)
    outer.d.bits.opcode.poke(GrantData)
    outer.d.bits.param.poke(toT)
    outer.d.bits.size.poke(log2Ceil(blockBytes).U)
    outer.d.bits.source.poke(source.U)
    outer.d.bits.sink.poke(0.U)
    outer.d.bits.denied.poke(false.B)
    outer.d.bits.corrupt.poke(false.B)
    outer.d.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
      u.counter.poke(initCounter.U)
      u.cryptoLine.poke(true.B)
    }
    lineBeats.foreach { beat =>
      outer.d.bits.data.poke(beat.U)
      var cycles = 0
      while (!outer.d.ready.peek().litToBoolean && cycles < 200) {
        dut.clock.step()
        cycles += 1
      }
      assert(outer.d.ready.peek().litToBoolean, "Timed out waiting for outer.d.ready")
      dut.clock.step()
    }
    outer.d.valid.poke(false.B)
  }

  private def waitForLoadRespData(dut: BoomDCacheRealDriverHarnessWrapper, maxCycles: Int = 400): Option[BigInt] = {
    var cycles = 0
    while (cycles < maxCycles) {
      if (dut.io.resp_valid.peek().litToBoolean) {
        return Some(dut.io.resp_data.peek().litValue)
      }
      dut.clock.step()
      cycles += 1
    }
    None
  }

  private def issueLoadUntilResp(
    dut: BoomDCacheRealDriverHarnessWrapper,
    address: BigInt,
    maxAttempts: Int = 8,
    maxCyclesPerAttempt: Int = 200): Option[BigInt] = {
    var attempts = 0
    while (attempts < maxAttempts) {
      issueReq(dut, M_XRD, address)
      var cycles = 0
      var gotNack = false
      while (!gotNack && cycles < maxCyclesPerAttempt) {
        if (dut.io.resp_valid.peek().litToBoolean) {
          return Some(dut.io.resp_data.peek().litValue)
        }
        if (dut.io.nack_valid.peek().litToBoolean) {
          gotNack = true
        } else {
          dut.clock.step()
          cycles += 1
        }
      }
      attempts += 1
    }
    None
  }

  private def waitForReencStart(dut: BoomDCacheRealDriverHarnessWrapper, maxCycles: Int = 200): Unit = {
    var cycles = 0
    while (!(dut.io.reenc_pending.peek().litToBoolean ||
             dut.io.reenc_active.peek().litToBoolean ||
             dut.io.reenc_meta_set_pending.peek().litToBoolean) &&
           cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(
      dut.io.reenc_pending.peek().litToBoolean ||
      dut.io.reenc_active.peek().litToBoolean ||
      dut.io.reenc_meta_set_pending.peek().litToBoolean,
      s"Timed out waiting for reenc to start after $maxCycles cycles")
  }

  private def waitForReencActive(dut: BoomDCacheRealDriverHarnessWrapper, maxCycles: Int = 200): Unit = {
    var cycles = 0
    while (!dut.io.reenc_active.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.reenc_active.peek().litToBoolean, s"Timed out waiting for reencActive after $maxCycles cycles")
  }

  private def waitForReencClear(dut: BoomDCacheRealDriverHarnessWrapper, maxCycles: Int = 400): Unit = {
    var cycles = 0
    while ((dut.io.reenc_pending.peek().litToBoolean ||
            dut.io.reenc_active.peek().litToBoolean ||
            dut.io.reenc_meta_set_pending.peek().litToBoolean ||
            dut.io.reenc_meta_clear_pending.peek().litToBoolean) &&
           cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(
      !dut.io.reenc_pending.peek().litToBoolean &&
      !dut.io.reenc_active.peek().litToBoolean &&
      !dut.io.reenc_meta_set_pending.peek().litToBoolean &&
      !dut.io.reenc_meta_clear_pending.peek().litToBoolean,
      s"Timed out waiting for reenc to fully clear after $maxCycles cycles")
  }

  behavior of "Boom V3 DCache trap-time crypto disable during reenc"

  it should "complete reenc and keep same-line forward progress if crypto remains enabled" in {
    test(new BoomDCacheRealDriverHarnessWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)
      programCryptoMode(dut)

      issueReq(dut, M_XRD, missAddr)
      val grantSource = waitOuterAcquire(dut, lineAddr)
      respondGrantData(dut, grantSource)
      val baselineChunk6 = waitForLoadRespData(dut)
      assert(baselineChunk6.nonEmpty, "Warmup crypto load did not respond")

      issueReq(dut, M_XWR, chunk0Addr, newChunk0)
      waitForReencStart(dut)
      waitForReencClear(dut)

      issueLoadUntilResp(dut, chunk6Addr) shouldBe baselineChunk6
    }
  }

  it should "deadlock the same line after trap-time disable clears engine reenc state mid-flight" in {
    test(new BoomDCacheRealDriverHarnessWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)
      programCryptoMode(dut)

      issueReq(dut, M_XRD, missAddr)
      val grantSource = waitOuterAcquire(dut, lineAddr)
      respondGrantData(dut, grantSource)
      val baselineChunk6 = waitForLoadRespData(dut)
      assert(baselineChunk6.nonEmpty, "Warmup crypto load did not respond")

      issueReq(dut, M_XWR, chunk0Addr, newChunk0)
      waitForReencStart(dut)
      waitForReencActive(dut)

      setCryptoEnable(dut, enable = false)
      setCryptoEnable(dut, enable = true)

      assert(
        !dut.io.reenc_pending.peek().litToBoolean &&
        !dut.io.reenc_active.peek().litToBoolean &&
        !dut.io.reenc_meta_set_pending.peek().litToBoolean &&
        !dut.io.reenc_meta_clear_pending.peek().litToBoolean,
        "engine internal reenc state did not get cleared by trap-time disable")

      issueLoadUntilResp(dut, chunk6Addr, maxAttempts = 8, maxCyclesPerAttempt = 200) shouldBe None
    }
  }
}
