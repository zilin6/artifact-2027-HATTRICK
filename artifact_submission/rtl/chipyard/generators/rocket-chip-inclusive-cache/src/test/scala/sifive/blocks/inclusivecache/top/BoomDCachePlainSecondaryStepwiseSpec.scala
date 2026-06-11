package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, M_XRD, M_XWR}
import freechips.rocketchip.tilelink.TLMessages.{AcquireBlock, GrantData}
import freechips.rocketchip.tilelink.TLPermissions.toT
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BoomDCachePlainSecondaryStepwiseSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val blockBytes = 64
  private val beatBytes = 8
  private val beatCount = blockBytes / beatBytes
  private val lineAddr = BigInt(0x000)
  private val missAddr = lineAddr + 6 * beatBytes
  private val chunk1Addr = lineAddr + beatBytes
  private val chunk0Addr = lineAddr
  private val lineBeats = (0 until beatCount).map(i => BigInt("4000000000000000", 16) + i)
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
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
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

  private def collectLoadRespData(dut: BoomDCacheRealDriverHarnessWrapper, expectedCount: Int, maxCycles: Int = 1000): Seq[BigInt] = {
    var respData = Seq.empty[BigInt]
    var cycles = 0
    while (respData.length < expectedCount && cycles < maxCycles) {
      if (dut.io.resp_valid.peek().litToBoolean) {
        respData :+= dut.io.resp_data.peek().litValue
      }
      dut.clock.step()
      cycles += 1
    }
    respData
  }

  behavior of "Boom V3 DCache plain same-line secondary baseline"

  it should "handle outstanding miss plus later same-line loads without losing the line contents" in {
    test(new BoomDCacheRealDriverHarnessWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)

      issueReq(dut, M_XRD, missAddr)
      val grantSource = waitOuterAcquire(dut, lineAddr)

      issueReq(dut, M_XRD, chunk1Addr)
      issueReq(dut, M_XRD, chunk0Addr)

      respondGrantData(dut, grantSource)
      val respData = collectLoadRespData(dut, expectedCount = 3)

      respData shouldBe Seq(lineBeats(6), lineBeats(1), lineBeats(0))
    }
  }

  // The fake-LSU harness can drive load-only secondary traffic credibly, but it does not
  // model the plain store path faithfully enough to make this assertion trustworthy yet.
  ignore should "show whether a same-line store before miss completion becomes visible to a later same-line load" in {
    test(new BoomDCacheRealDriverHarnessWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)

      issueReq(dut, M_XRD, missAddr)
      val grantSource = waitOuterAcquire(dut, lineAddr)

      issueReq(dut, M_XWR, chunk0Addr, newChunk0)
      issueReq(dut, M_XRD, chunk0Addr)

      respondGrantData(dut, grantSource)
      val respData = collectLoadRespData(dut, expectedCount = 2)

      println(s"[PlainStoreThenLoad] respData=$respData")
      respData.length shouldBe 2
      respData.head shouldBe lineBeats(6)
      respData.last shouldBe newChunk0
    }
  }

  ignore should "still return the new value for a same-line store after the miss has fully completed" in {
    test(new BoomDCacheRealDriverHarnessWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)

      issueReq(dut, M_XRD, missAddr)
      val grantSource = waitOuterAcquire(dut, lineAddr)
      respondGrantData(dut, grantSource)

      val warmupResp = collectLoadRespData(dut, expectedCount = 1)
      warmupResp shouldBe Seq(lineBeats(6))

      issueReq(dut, M_XWR, chunk0Addr, newChunk0)
      dut.clock.step(20)
      issueReq(dut, M_XRD, chunk0Addr)

      val respData = collectLoadRespData(dut, expectedCount = 1)
      respData shouldBe Seq(newChunk0)
    }
  }
}
