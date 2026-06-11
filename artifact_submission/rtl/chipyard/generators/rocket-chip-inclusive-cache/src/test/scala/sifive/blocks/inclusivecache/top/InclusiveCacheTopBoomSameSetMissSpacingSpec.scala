package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import freechips.rocketchip.rocket.CacheCryptoRefillMeta
import freechips.rocketchip.tilelink.TLMessages.AcquireBlock
import freechips.rocketchip.tilelink.TLMessages.GrantData
import freechips.rocketchip.tilelink.TLPermissions.toT
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InclusiveCacheTopBoomSameSetMissSpacingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val blockBytes = 64
  private val beatCount = blockBytes / 8
  private val firstAddr = BigInt(0x000)
  private val secondAddr = BigInt(0x200)

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

  private def waitOuterAcquire(dut: InclusiveCacheTopBoomHarnessWrapper, maxCycles: Int = 200): (BigInt, BigInt) = {
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

  private def respondGrantData(dut: InclusiveCacheTopBoomHarnessWrapper, source: BigInt, beatsBase: BigInt): Unit = {
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
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
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

  private def waitResp(dut: InclusiveCacheTopBoomHarnessWrapper, maxCycles: Int = 300): Unit = {
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

  behavior of "Boom MSHRFile -> InclusiveCache realistic miss spacing"

  it should "delay the second same-set miss at InclusiveCache outer side until the first miss completes" in {
    test(new InclusiveCacheTopBoomHarnessWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)

      issueSingleLoadAttempt(dut, firstAddr)
      val (firstOuterAddr, firstSource) = waitOuterAcquire(dut, maxCycles = 300)
      firstOuterAddr shouldBe firstAddr

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

      respondGrantData(dut, firstSource, BigInt("4000000000000000", 16))
      waitResp(dut, maxCycles = 500)

      val secondSource = retryLoadUntilOuterAcquire(dut, secondAddr)
      respondGrantData(dut, secondSource, BigInt("5000000000000000", 16))
      waitResp(dut, maxCycles = 500)
    }
  }
}
