package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, CacheCryptoWritebackMeta, M_XRD}
import freechips.rocketchip.tilelink.TLMessages.AcquireBlock
import freechips.rocketchip.tilelink.TLMessages.GrantData
import freechips.rocketchip.tilelink.TLPermissions.toT
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BoomDCacheCryptoSameSetMissSpacingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val blockBytes = 64
  private val beatCount = blockBytes / 8
  private val firstAddr = BigInt(0x000)
  private val secondAddr = BigInt(0x200)
  private val counterBase = BigInt("100000", 16)
  private val firstCounter = BigInt("8877665544332211", 16)
  private val secondCounter = BigInt("1122334455667788", 16)
  private val dataKey = BigInt("00112233445566778899aabbccddeeff", 16)

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

    outer.b.valid.poke(false.B)
  }

  private def programCryptoMode(dut: BoomDCacheRealDriverHarnessWrapper): Unit = {
    dut.io.dataKey.poke(dataKey.U)
    dut.io.cacheCryptoCounterBaseValue.poke(counterBase.U)
    dut.io.cacheCryptoCounterBaseWen.poke(true.B)
    dut.io.cacheCryptoLoadEnableValue.poke(true.B)
    dut.io.cacheCryptoStoreEnableValue.poke(false.B)
    dut.io.cacheCryptoEnableWen.poke(true.B)
    dut.clock.step()
    dut.io.cacheCryptoCounterBaseWen.poke(false.B)
    dut.io.cacheCryptoEnableWen.poke(false.B)
  }

  private def issueSingleLoadAttempt(dut: BoomDCacheRealDriverHarnessWrapper, address: BigInt): Unit = {
    dut.io.req_addr.poke(address.U)
    dut.io.req_mem_size.poke(3.U)
    dut.io.req_mem_signed.poke(false.B)
    dut.io.req_data.poke(0.U)
    dut.io.req_cmd.poke(M_XRD)
    dut.io.req_valid.poke(true.B)
    var cycles = 0
    while (!dut.io.req_ready.peek().litToBoolean && cycles < 100) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.req_ready.peek().litToBoolean, s"Timed out waiting for req_ready for address 0x${address.toString(16)}")
    dut.clock.step()
    dut.io.req_valid.poke(false.B)
  }

  private def waitOuterAcquire(dut: BoomDCacheRealDriverHarnessWrapper, maxCycles: Int = 200): (BigInt, BigInt, Boolean) = {
    val outer = dut.io.outer
    var cycles = 0
    while (!(outer.a.valid.peek().litToBoolean &&
             outer.a.bits.opcode.peek().litValue == AcquireBlock.litValue) &&
           cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(outer.a.valid.peek().litToBoolean, s"Timed out waiting for outer AcquireBlock after $maxCycles cycles")
    val cryptoLine = outer.a.bits.user.lift(CacheCryptoWritebackMeta).exists(_.cryptoLine.peek().litToBoolean)
    (outer.a.bits.address.peek().litValue, outer.a.bits.source.peek().litValue, cryptoLine)
  }

  private def respondGrantData(
    dut: BoomDCacheRealDriverHarnessWrapper,
    source: BigInt,
    beatsBase: BigInt,
    counter: BigInt,
    cryptoLine: Boolean): Unit = {
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
      u.counter.poke(counter.U)
      u.cryptoLine.poke(cryptoLine.B)
    }

    for (i <- 0 until beatCount) {
      outer.d.bits.data.poke((beatsBase + i).U)
      var cycles = 0
      while (!outer.d.ready.peek().litToBoolean && cycles < 200) {
        dut.clock.step()
        cycles += 1
      }
      assert(outer.d.ready.peek().litToBoolean, s"Timed out waiting for outer.d.ready on beat $i")
      dut.clock.step()
    }

    outer.d.valid.poke(false.B)
  }

  private def waitResp(dut: BoomDCacheRealDriverHarnessWrapper, maxCycles: Int = 200): Unit = {
    var cycles = 0
    while (!dut.io.resp_valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.resp_valid.peek().litToBoolean, s"Timed out waiting for resp_valid after $maxCycles cycles")
    dut.clock.step()
  }

  private def retryLoadUntilOuterAcquire(
    dut: BoomDCacheRealDriverHarnessWrapper,
    address: BigInt,
    maxAttempts: Int = 16,
    observeCyclesPerAttempt: Int = 64): (BigInt, Boolean) = {
    var attempts = 0
    while (attempts < maxAttempts) {
      issueSingleLoadAttempt(dut, address)

      var cycles = 0
      while (cycles < observeCyclesPerAttempt) {
        if (dut.io.outer.a.valid.peek().litToBoolean &&
            dut.io.outer.a.bits.opcode.peek().litValue == AcquireBlock.litValue &&
            dut.io.outer.a.bits.address.peek().litValue == address) {
          val cryptoLine = dut.io.outer.a.bits.user.lift(CacheCryptoWritebackMeta).exists(_.cryptoLine.peek().litToBoolean)
          return (dut.io.outer.a.bits.source.peek().litValue, cryptoLine)
        }
        dut.clock.step()
        cycles += 1
      }
      attempts += 1
    }
    fail(s"Timed out waiting for outer AcquireBlock for address 0x${address.toString(16)} after $maxAttempts retry attempts")
  }

  behavior of "Boom V3 DCache realistic crypto miss spacing"

  it should "nack and delay a second same-set crypto miss until the first miss completes while keeping outer AcquireBlock cryptoLine=1" in {
    test(new BoomDCacheRealDriverHarnessWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)
      programCryptoMode(dut)
      dut.clock.step(4)

      issueSingleLoadAttempt(dut, firstAddr)
      val (firstOuterAddr, firstSource, firstOuterCryptoLine) = waitOuterAcquire(dut)
      firstOuterAddr shouldBe firstAddr
      firstOuterCryptoLine shouldBe true

      issueSingleLoadAttempt(dut, secondAddr)

      var sawSecondAcquireEarly = false
      var sawSecondNack = false
      for (_ <- 0 until 64) {
        sawSecondAcquireEarly ||= dut.io.outer.a.valid.peek().litToBoolean &&
          dut.io.outer.a.bits.opcode.peek().litValue == AcquireBlock.litValue &&
          dut.io.outer.a.bits.address.peek().litValue == secondAddr
        sawSecondNack ||= dut.io.nack_valid.peek().litToBoolean
        dut.clock.step()
      }
      sawSecondAcquireEarly shouldBe false
      sawSecondNack shouldBe true

      respondGrantData(
        dut,
        source = firstSource,
        beatsBase = BigInt("4000000000000000", 16),
        counter = firstCounter,
        cryptoLine = true)
      waitResp(dut, maxCycles = 400)

      val (secondSource, secondOuterCryptoLine) =
        retryLoadUntilOuterAcquire(dut, secondAddr, maxAttempts = 24, observeCyclesPerAttempt = 64)
      secondOuterCryptoLine shouldBe true

      respondGrantData(
        dut,
        source = secondSource,
        beatsBase = BigInt("5000000000000000", 16),
        counter = secondCounter,
        cryptoLine = true)
      waitResp(dut, maxCycles = 400)
    }
  }
}
