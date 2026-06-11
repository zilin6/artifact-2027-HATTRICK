package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, CacheCryptoWritebackMeta, M_XRD, M_XWR}
import freechips.rocketchip.tilelink.TLMessages.{AcquireBlock, GrantData, ReleaseData}
import freechips.rocketchip.tilelink.TLPermissions.toT
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BoomDCacheTrapDisableReencWritebackSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val blockBytes = 64
  private val beatBytes = 8
  private val beatCount = blockBytes / beatBytes
  private val lineA = BigInt(0x000)
  private val lineB = BigInt(0x200)
  private val missAddr = lineA + 6 * beatBytes
  private val chunk0Addr = lineA
  private val chunk6Beat = 6
  private val lineABeats = (0 until beatCount).map(i => BigInt("4000000000000000", 16) + i)
  private val lineBBeats = (0 until beatCount).map(i => BigInt("5000000000000000", 16) + i)
  private val counterBase = BigInt("100000", 16)
  private val dataKey = BigInt("00112233445566778899aabbccddeeff", 16)
  private val initCounter = BigInt("ff", 16)
  private val nextCounter = initCounter + 1
  private val newChunk0 = BigInt("1122334455667788", 16)

  private case class WritebackObs(counter: BigInt, beats: Seq[BigInt], cryptoLine: Boolean)

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

  private def respondGrantData(
    dut: BoomDCacheRealDriverHarnessWrapper,
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
      assert(outer.d.ready.peek().litToBoolean, "Timed out waiting for outer.d.ready")
      dut.clock.step()
    }
    outer.d.valid.poke(false.B)
  }

  private def waitForLoadResp(dut: BoomDCacheRealDriverHarnessWrapper, maxCycles: Int = 400): Unit = {
    var cycles = 0
    while (!dut.io.resp_valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.resp_valid.peek().litToBoolean, s"Timed out waiting load response after $maxCycles cycles")
    dut.clock.step()
  }

  private def waitForReencActive(dut: BoomDCacheRealDriverHarnessWrapper, maxCycles: Int = 200): Unit = {
    var cycles = 0
    while (!dut.io.reenc_active.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.reenc_active.peek().litToBoolean, s"Timed out waiting reenc_active after $maxCycles cycles")
  }

  private def waitForWritebackAndLineBMiss(
    dut: BoomDCacheRealDriverHarnessWrapper,
    disableAtReenc: Boolean): WritebackObs = {
    val outer = dut.io.outer
    var wbCounter: Option[BigInt] = None
    var wbCrypto = false
    val wbBeats = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    var lineBAcquireSource: Option[BigInt] = None
    var lineBGrantDone = false
    var cycles = 0
    while ((wbBeats.length < beatCount || !lineBGrantDone) && cycles < 2000) {
      if (disableAtReenc && dut.io.reenc_active.peek().litToBoolean) {
        setCryptoEnable(dut, enable = false)
        setCryptoEnable(dut, enable = true)
      }
      if (outer.c.valid.peek().litToBoolean && outer.c.bits.opcode.peek().litValue == ReleaseData.litValue) {
        if (wbCounter.isEmpty) {
          wbCounter = outer.c.bits.user.lift(CacheCryptoWritebackMeta).map(_.counter.peek().litValue)
          wbCrypto = outer.c.bits.user.lift(CacheCryptoWritebackMeta).exists(_.cryptoLine.peek().litToBoolean)
        }
        wbBeats += outer.c.bits.data.peek().litValue
      }
      if (lineBAcquireSource.isEmpty &&
          outer.a.valid.peek().litToBoolean &&
          outer.a.bits.opcode.peek().litValue == AcquireBlock.litValue &&
          outer.a.bits.address.peek().litValue == lineB) {
        lineBAcquireSource = Some(outer.a.bits.source.peek().litValue)
      }
      dut.clock.step()
      lineBAcquireSource.foreach { src =>
        if (!lineBGrantDone) {
          respondGrantData(dut, src, lineBBeats, initCounter)
          lineBGrantDone = true
        }
      }
      cycles += 1
    }
    assert(wbCounter.nonEmpty, "Timed out waiting for writeback counter on outer.c")
    assert(wbBeats.length == beatCount, s"Timed out waiting for full writeback beats; got ${wbBeats.length}")
    assert(lineBGrantDone, "Timed out waiting to complete conflicting lineB miss")
    WritebackObs(wbCounter.get, wbBeats.toSeq, wbCrypto)
  }

  behavior of "Boom V3 DCache trap-time reenc writeback check"

  it should "write back the reencrypted line after uninterrupted reenc" in {
    test(new BoomDCacheRealDriverHarnessWrapper(dcacheWays = 1, dcacheSets = 8))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)
      programCryptoMode(dut)

      issueReq(dut, M_XRD, missAddr)
      val srcA = waitOuterAcquire(dut, lineA)
      respondGrantData(dut, srcA, lineABeats, initCounter)
      waitForLoadResp(dut)

      issueReq(dut, M_XWR, chunk0Addr, newChunk0)
      waitForReencActive(dut)

      issueReq(dut, M_XRD, lineB)
      val wb = waitForWritebackAndLineBMiss(dut, disableAtReenc = false)

      wb.cryptoLine shouldBe true
      wb.counter shouldBe nextCounter
      wb.beats(chunk6Beat) should not be lineABeats(chunk6Beat)
    }
  }

  it should "write back a non-reencrypted line if crypto is disabled while reenc is active" in {
    test(new BoomDCacheRealDriverHarnessWrapper(dcacheWays = 1, dcacheSets = 8))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)
      programCryptoMode(dut)

      issueReq(dut, M_XRD, missAddr)
      val srcA = waitOuterAcquire(dut, lineA)
      respondGrantData(dut, srcA, lineABeats, initCounter)
      waitForLoadResp(dut)

      issueReq(dut, M_XWR, chunk0Addr, newChunk0)
      waitForReencActive(dut)

      issueReq(dut, M_XRD, lineB)
      val wb = waitForWritebackAndLineBMiss(dut, disableAtReenc = true)

      wb.cryptoLine shouldBe true
      wb.counter shouldBe initCounter
      wb.beats(chunk6Beat) shouldBe lineABeats(chunk6Beat)
    }
  }
}
