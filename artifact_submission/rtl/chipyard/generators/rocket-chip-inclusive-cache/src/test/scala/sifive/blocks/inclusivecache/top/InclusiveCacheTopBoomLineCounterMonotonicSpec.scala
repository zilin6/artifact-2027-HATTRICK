package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, M_XRD, M_XWR}
import freechips.rocketchip.tilelink.TLMessages.{AccessAckData, AcquireBlock, Get, GrantData}
import freechips.rocketchip.tilelink.TLPermissions.toT
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InclusiveCacheTopBoomLineCounterMonotonicSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val cache = InclusiveCacheTopHarness.fastCache.copy(sets = 64)
  private val micro = InclusiveCacheTopHarness.fastMicro

  private val blockBytes = cache.blockBytes
  private val beatCount = blockBytes / cache.beatBytes
  private val counterBase = BigInt("100000", 16)
  private val dataKey = BigInt("00112233445566778899aabbccddeeff", 16)

  private val lineA = BigInt("80002000", 16)
  private val lineB = BigInt("80002200", 16)

  private val initCounterA = BigInt("8877665544332211", 16)
  private val initCounterB = BigInt("7766554433221100", 16)
  private val storeWord1 = BigInt("1122334455667788", 16)
  private val storeWord2 = BigInt("99aabbccddeeff00", 16)

  private def lineData(base: BigInt): Seq[BigInt] =
    (0 until beatCount).map(i => base + i)

  private val backing = scala.collection.mutable.Map[BigInt, (Seq[BigInt], BigInt)](
    lineA -> (lineData(BigInt("4000000000000000", 16)), initCounterA),
    lineB -> (lineData(BigInt("5000000000000000", 16)), initCounterB))

  private def counterAddrForLine(address: BigInt): BigInt =
    counterBase + ((address >> log2Ceil(blockBytes)) << 3)

  private def clearHarness(dut: InclusiveCacheTopBoomHarnessWrapper): Unit = {
    dut.io.req_valid.poke(false.B)
    dut.io.req_addr.poke(0.U)
    dut.io.req_data.poke(0.U)
    dut.io.req_cmd.poke(M_XRD)
    dut.io.req_mem_size.poke(3.U)
    dut.io.req_mem_signed.poke(false.B)
    dut.io.resp_clear.poke(false.B)

    dut.io.dataKey.poke(0.U)
    dut.io.cacheCryptoLoadEnableValue.poke(false.B)
    dut.io.cacheCryptoStoreEnableValue.poke(false.B)
    dut.io.cacheCryptoEnableWen.poke(false.B)
    dut.io.cacheCryptoCounterBaseValue.poke(0.U)
    dut.io.cacheCryptoCounterBaseWen.poke(false.B)
    dut.io.cus_base_address.poke(0.U)
    dut.io.cus_base_wen.poke(false.B)
    dut.io.log.poke(false.B)
    dut.io.release_clear.poke(false.B)

    val outer = dut.io.outer
    outer.a.ready.poke(false.B)
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

  private def programCryptoMode(dut: InclusiveCacheTopBoomHarnessWrapper): Unit = {
    dut.io.dataKey.poke(dataKey.U)
    dut.io.cacheCryptoCounterBaseValue.poke(counterBase.U)
    dut.io.cacheCryptoCounterBaseWen.poke(true.B)
    dut.io.cacheCryptoLoadEnableValue.poke(true.B)
    dut.io.cacheCryptoStoreEnableValue.poke(true.B)
    dut.io.cacheCryptoEnableWen.poke(true.B)
    dut.io.cus_base_address.poke(counterBase.U)
    dut.io.cus_base_wen.poke(true.B)
    dut.clock.step()
    dut.io.cacheCryptoCounterBaseWen.poke(false.B)
    dut.io.cacheCryptoEnableWen.poke(false.B)
    dut.io.cus_base_wen.poke(false.B)
  }

  private def startBoomReq(
    dut: InclusiveCacheTopBoomHarnessWrapper,
    cmd: UInt,
    addr: BigInt,
    data: BigInt = 0): Unit = {
    dut.io.req_cmd.poke(cmd)
    dut.io.req_addr.poke(addr.U)
    dut.io.req_data.poke(data.U)
    dut.io.req_mem_size.poke(3.U)
    dut.io.req_mem_signed.poke(false.B)
    dut.io.req_valid.poke(true.B)
  }

  private def finishBoomReqWhenAccepted(dut: InclusiveCacheTopBoomHarnessWrapper): Boolean = {
    val accepted = dut.io.req_valid.peek().litToBoolean && dut.io.req_ready.peek().litToBoolean
    dut.clock.step()
    if (accepted) dut.io.req_valid.poke(false.B)
    accepted
  }

  private def issueBoomReq(
    dut: InclusiveCacheTopBoomHarnessWrapper,
    cmd: UInt,
    addr: BigInt,
    data: BigInt = 0): Unit = {
    dut.io.resp_clear.poke(true.B)
    dut.clock.step()
    dut.io.resp_clear.poke(false.B)
    startBoomReq(dut, cmd, addr, data)
    var accepted = false
    var cycles = 0
    while (!accepted && cycles < 100) {
      accepted = finishBoomReqWhenAccepted(dut)
      cycles += 1
    }
    assert(accepted, s"Timed out waiting to issue req cmd=${cmd.litValue} addr=0x${addr.toString(16)}")
  }

  private def respondGrantData(
    dut: InclusiveCacheTopBoomHarnessWrapper,
    source: BigInt,
    beats: Seq[BigInt],
    counter: BigInt): Unit = {
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
      u.cryptoLine.poke(true.B)
    }
    beats.foreach { beat =>
      outer.d.bits.data.poke(beat.U)
      var cycles = 0
      while (!outer.d.ready.peek().litToBoolean && cycles < 200) {
        dut.clock.step()
        cycles += 1
      }
      assert(outer.d.ready.peek().litToBoolean, "Timed out waiting for outer GrantData ready")
      dut.clock.step()
    }
    outer.d.valid.poke(false.B)
  }

  private def respondCounterAckData(
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
    outer.d.bits.corrupt.poke(false.B)
    outer.d.bits.data.poke(counter.U)
    outer.d.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
    }
    var cycles = 0
    while (!outer.d.ready.peek().litToBoolean && cycles < 200) {
      dut.clock.step()
      cycles += 1
    }
    assert(outer.d.ready.peek().litToBoolean, "Timed out waiting for outer counter ack ready")
    dut.clock.step()
    outer.d.valid.poke(false.B)
  }

  private def serviceOuterOnce(dut: InclusiveCacheTopBoomHarnessWrapper): Unit = {
    val outer = dut.io.outer
    if (outer.a.valid.peek().litToBoolean) {
      val opcode = outer.a.bits.opcode.peek().litValue
      val address = outer.a.bits.address.peek().litValue
      val source = outer.a.bits.source.peek().litValue
      outer.a.ready.poke(true.B)
      dut.clock.step()
      outer.a.ready.poke(false.B)
      if (opcode == AcquireBlock.litValue) {
        val (beats, counter) = backing.getOrElse(address,
          throw new RuntimeException(s"Missing backing data for AcquireBlock address 0x${address.toString(16)}"))
        respondGrantData(dut, source, beats, counter)
      } else if (opcode == Get.litValue) {
        val lineAddr = backing.keys.find(counterAddrForLine(_) == address).getOrElse(
          throw new RuntimeException(s"Missing backing line for counter address 0x${address.toString(16)}"))
        val counter = backing(lineAddr)._2
        respondCounterAckData(dut, source, counter)
      } else {
        fail(s"Unexpected outer.a opcode 0x${opcode.toString(16)} at address 0x${address.toString(16)}")
      }
    } else {
      dut.clock.step()
    }
  }

  private def completeCryptoLoadMiss(dut: InclusiveCacheTopBoomHarnessWrapper, addr: BigInt): Unit = {
    issueBoomReq(dut, M_XRD, addr)
    var sawResp = false
    var cycles = 0
    while (!sawResp && cycles < 1000) {
      sawResp ||= dut.io.resp_seen.peek().litToBoolean
      if (!sawResp) serviceOuterOnce(dut)
      else dut.clock.step()
      cycles += 1
    }
    assert(sawResp, s"Timed out waiting for load resp for 0x${addr.toString(16)}")
  }

  private def evictLineAAndCaptureRelease(
    dut: InclusiveCacheTopBoomHarnessWrapper,
    lineToEvictWith: BigInt): BigInt = {
    dut.io.release_clear.poke(true.B)
    dut.clock.step()
    dut.io.release_clear.poke(false.B)

    issueBoomReq(dut, M_XRD, lineToEvictWith)

    var lineBRespSeen = false
    var sawRelease = false
    var releaseCounter = BigInt(0)
    var cycles = 0
    while ((!lineBRespSeen || !sawRelease) && cycles < 4000) {
      if (!sawRelease &&
          dut.io.release_seen.peek().litToBoolean &&
          dut.io.release_last_address.peek().litValue == lineA &&
          dut.io.release_last_crypto.peek().litToBoolean &&
          dut.io.release_count.peek().litValue >= beatCount) {
        sawRelease = true
        releaseCounter = dut.io.release_last_counter.peek().litValue
      }

      if (dut.io.outer.a.valid.peek().litToBoolean) {
        serviceOuterOnce(dut)
      } else {
        if (dut.io.resp_seen.peek().litToBoolean) {
          lineBRespSeen = true
        }
        dut.clock.step()
      }
      cycles += 1
    }

    assert(lineBRespSeen, s"Timed out waiting for eviction miss response at 0x${lineToEvictWith.toString(16)}")
    assert(sawRelease, "Timed out waiting for full lineA release observation")
    releaseCounter
  }

  behavior of "Boom top-level line counter monotonicity"

  it should "never send a smaller counter on a later writeback of the same cache line" in {
    test(new InclusiveCacheTopBoomHarnessWrapper(cache = cache, micro = micro, dcacheWays = 1, dcacheSets = 8))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)
      programCryptoMode(dut)
      dut.io.log.poke(false.B)
      dut.clock.step(4)

      completeCryptoLoadMiss(dut, lineA)

      issueBoomReq(dut, M_XWR, lineA, storeWord1)
      dut.clock.step(20)
      val firstReleaseCounter = evictLineAAndCaptureRelease(dut, lineB)

      completeCryptoLoadMiss(dut, lineA)
      dut.io.resp_last_data.peek().litValue shouldBe storeWord1

      issueBoomReq(dut, M_XWR, lineA, storeWord2)
      dut.clock.step(20)
      val secondReleaseCounter = evictLineAAndCaptureRelease(dut, lineB)

      withClue(
        f"lineA release counters regressed: first=0x$firstReleaseCounter%x second=0x$secondReleaseCounter%x") {
        secondReleaseCounter should be > firstReleaseCounter
      }

      println(
        f"[LineCounterMonotonic] lineA firstReleaseCounter=0x$firstReleaseCounter%x " +
        f"secondReleaseCounter=0x$secondReleaseCounter%x")
    }
  }
}
