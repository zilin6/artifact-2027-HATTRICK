package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import freechips.rocketchip.rocket.CacheCryptoRefillMeta
import freechips.rocketchip.tilelink.TLMessages.AccessAckData
import freechips.rocketchip.tilelink.TLMessages.AcquireBlock
import freechips.rocketchip.tilelink.TLMessages.GrantData
import freechips.rocketchip.tilelink.TLMessages.Get
import freechips.rocketchip.tilelink.TLPermissions.toT
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InclusiveCacheTopBoomCryptoSameSetMissSpacingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val blockBytes = 64
  private val beatCount = blockBytes / 8
  private val firstAddr = BigInt(0x000)
  private val secondAddr = BigInt(0x200)
  private val counterBase = BigInt("100000", 16)
  private val firstCounter = BigInt("8877665544332211", 16)
  private val secondCounter = BigInt("1122334455667788", 16)
  private val dataKey = BigInt("00112233445566778899aabbccddeeff", 16)

  private def clearHarness(dut: InclusiveCacheTopBoomHarnessWrapper): Unit = {
    dut.io.req_valid.poke(false.B)
    dut.io.req_addr.poke(0.U)
    dut.io.req_data.poke(0.U)
    dut.io.req_mem_size.poke(3.U)
    dut.io.req_mem_signed.poke(false.B)
    dut.io.dataKey.poke(0.U)
    dut.io.cacheCryptoLoadEnableValue.poke(false.B)
    dut.io.cacheCryptoStoreEnableValue.poke(false.B)
    dut.io.cacheCryptoEnableWen.poke(false.B)
    dut.io.cacheCryptoCounterBaseValue.poke(0.U)
    dut.io.cacheCryptoCounterBaseWen.poke(false.B)
    dut.io.cus_base_address.poke(0.U)
    dut.io.cus_base_wen.poke(false.B)
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

  private def programCryptoMode(dut: InclusiveCacheTopBoomHarnessWrapper): Unit = {
    dut.io.dataKey.poke(dataKey.U)
    dut.io.cacheCryptoCounterBaseValue.poke(counterBase.U)
    dut.io.cacheCryptoCounterBaseWen.poke(true.B)
    dut.io.cacheCryptoLoadEnableValue.poke(true.B)
    dut.io.cacheCryptoStoreEnableValue.poke(false.B)
    dut.io.cacheCryptoEnableWen.poke(true.B)
    dut.io.cus_base_address.poke(counterBase.U)
    dut.io.cus_base_wen.poke(true.B)
    dut.clock.step()
    dut.io.cacheCryptoCounterBaseWen.poke(false.B)
    dut.io.cacheCryptoEnableWen.poke(false.B)
    dut.io.cus_base_wen.poke(false.B)
  }

  private def counterAddrForLine(address: BigInt): BigInt = {
    counterBase + ((address >> 6) << 3)
  }

  private def issueSingleLoadAttempt(dut: InclusiveCacheTopBoomHarnessWrapper, address: BigInt): Unit = {
    dut.io.req_addr.poke(address.U)
    dut.io.req_mem_size.poke(3.U)
    dut.io.req_mem_signed.poke(false.B)
    dut.io.req_data.poke(0.U)
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

  private def waitOuterAcquire(dut: InclusiveCacheTopBoomHarnessWrapper, maxCycles: Int = 300): (BigInt, BigInt) = {
    val outer = dut.io.outer
    var cycles = 0
    while (!(outer.a.valid.peek().litToBoolean &&
             outer.a.bits.opcode.peek().litValue == AcquireBlock.litValue) &&
           cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(outer.a.valid.peek().litToBoolean, s"Timed out waiting for outer AcquireBlock after $maxCycles cycles")
    (outer.a.bits.address.peek().litValue, outer.a.bits.source.peek().litValue)
  }

  private def respondGrantData(
    dut: InclusiveCacheTopBoomHarnessWrapper,
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

  private def waitOuterGet(dut: InclusiveCacheTopBoomHarnessWrapper, expectedAddress: BigInt, maxCycles: Int = 300): BigInt = {
    val outer = dut.io.outer
    var cycles = 0
    while (!(outer.a.valid.peek().litToBoolean &&
             outer.a.bits.opcode.peek().litValue == Get.litValue &&
             outer.a.bits.address.peek().litValue == expectedAddress) &&
           cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(outer.a.valid.peek().litToBoolean, s"Timed out waiting for outer Get at 0x${expectedAddress.toString(16)} after $maxCycles cycles")
    outer.a.bits.source.peek().litValue
  }

  private def respondCounterAccessAckData(
    dut: InclusiveCacheTopBoomHarnessWrapper,
    source: BigInt,
    counter: BigInt): Unit = {
    val outer = dut.io.outer
    outer.d.valid.poke(true.B)
    outer.d.bits.opcode.poke(AccessAckData)
    outer.d.bits.param.poke(0.U)
    outer.d.bits.size.poke(3.U)
    outer.d.bits.source.poke(source.U)
    outer.d.bits.sink.poke(0.U)
    outer.d.bits.denied.poke(false.B)
    outer.d.bits.data.poke(counter.U)
    outer.d.bits.corrupt.poke(false.B)
    outer.d.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
    }
    var cycles = 0
    while (!outer.d.ready.peek().litToBoolean && cycles < 200) {
      dut.clock.step()
      cycles += 1
    }
    assert(outer.d.ready.peek().litToBoolean, s"Timed out waiting for outer.d.ready for counter source ${source.toString(16)}")
    dut.clock.step()
    outer.d.valid.poke(false.B)
  }

  private def waitResp(dut: InclusiveCacheTopBoomHarnessWrapper, maxCycles: Int = 500): Unit = {
    var cycles = 0
    while (!dut.io.resp_valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.resp_valid.peek().litToBoolean, s"Timed out waiting for resp_valid after $maxCycles cycles")
    dut.clock.step()
  }

  private def retryLoadUntilOuterAcquire(
    dut: InclusiveCacheTopBoomHarnessWrapper,
    address: BigInt,
    maxAttempts: Int = 24,
    observeCyclesPerAttempt: Int = 64): BigInt = {
    var attempts = 0
    while (attempts < maxAttempts) {
      issueSingleLoadAttempt(dut, address)
      var cycles = 0
      while (cycles < observeCyclesPerAttempt) {
        if (dut.io.outer.a.valid.peek().litToBoolean &&
            dut.io.outer.a.bits.opcode.peek().litValue == AcquireBlock.litValue &&
            dut.io.outer.a.bits.address.peek().litValue == address) {
          return dut.io.outer.a.bits.source.peek().litValue
        }
        dut.clock.step()
        cycles += 1
      }
      attempts += 1
    }
    fail(s"Timed out waiting for outer AcquireBlock for address 0x${address.toString(16)} after $maxAttempts retry attempts")
  }

  behavior of "Boom MSHRFile -> InclusiveCache realistic crypto miss spacing"

  it should "delay the second same-set crypto miss at InclusiveCache outer side until the first miss completes" in {
    test(new InclusiveCacheTopBoomHarnessWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)
      programCryptoMode(dut)
      dut.clock.step(4)

      issueSingleLoadAttempt(dut, firstAddr)
      val (firstOuterAddr, firstSource) = waitOuterAcquire(dut)
      firstOuterAddr shouldBe firstAddr
      val firstCounterSource = waitOuterGet(dut, counterAddrForLine(firstAddr))

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

      respondGrantData(dut, firstSource, BigInt("4000000000000000", 16), firstCounter, cryptoLine = true)
      respondCounterAccessAckData(dut, firstCounterSource, firstCounter)
      waitResp(dut)

      val secondSource = retryLoadUntilOuterAcquire(dut, secondAddr)
      val secondCounterSource = waitOuterGet(dut, counterAddrForLine(secondAddr))

      respondGrantData(dut, secondSource, BigInt("5000000000000000", 16), secondCounter, cryptoLine = true)
      respondCounterAccessAckData(dut, secondCounterSource, secondCounter)
      waitResp(dut)
    }
  }
}
