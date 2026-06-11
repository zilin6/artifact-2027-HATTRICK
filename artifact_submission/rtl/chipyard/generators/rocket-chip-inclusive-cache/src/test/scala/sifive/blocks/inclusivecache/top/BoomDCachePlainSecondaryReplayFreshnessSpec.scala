package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, M_XRD, M_XWR}
import freechips.rocketchip.tilelink.TLMessages.{AcquireBlock, GrantData}
import freechips.rocketchip.tilelink.TLPermissions.toT
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BoomDCachePlainSecondaryReplayFreshnessSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val blockBytes = 64
  private val beatBytes = 8
  private val beatCount = blockBytes / beatBytes
  private val lineAddr = BigInt(0x000)
  private val newChunk0 = BigInt("1122334455667788", 16)
  private val newChunk1 = BigInt("99aabbccddeeff00", 16)

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

  private def respondGrantData(dut: BoomDCacheRealDriverHarnessWrapper, source: BigInt, beats: Seq[BigInt]): Unit = {
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

  behavior of "Boom V3 DCache plain secondary replay freshness"

  it should "show the plain replay behavior for the same sequence as the crypto secondary replay test" in {
    test(new BoomDCacheRealDriverHarnessWrapper)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)

      val lineBeats = (0 until beatCount).map(i => BigInt("4000000000000000", 16) + i)
      val missAddr = lineAddr + 6 * beatBytes
      val chunk1Addr = lineAddr + beatBytes
      val chunk0Addr = lineAddr

      issueReq(dut, M_XRD, missAddr)
      val grantSource = waitOuterAcquire(dut, lineAddr)

      issueReq(dut, M_XWR, chunk0Addr, newChunk0)
      issueReq(dut, M_XWR, chunk1Addr, newChunk1)
      issueReq(dut, M_XRD, chunk1Addr)
      issueReq(dut, M_XRD, chunk0Addr)

      var sawUnexpectedSecondAcquire = false
      for (_ <- 0 until 32) {
        sawUnexpectedSecondAcquire ||= dut.io.outer.a.valid.peek().litToBoolean &&
          dut.io.outer.a.bits.opcode.peek().litValue == AcquireBlock.litValue &&
          dut.io.outer.a.bits.address.peek().litValue == lineAddr
        dut.clock.step()
      }
      sawUnexpectedSecondAcquire shouldBe false

      respondGrantData(dut, grantSource, lineBeats)

      var replayCmdAddrs = Seq.empty[(BigInt, BigInt)]
      var replayWays = Seq.empty[BigInt]
      var replayLiveWays = Seq.empty[BigInt]
      var loadRespData = Seq.empty[BigInt]
      var cycles = 0
      while ((loadRespData.length < 3 || replayCmdAddrs.length < 3) && cycles < 1000) {
        if (dut.io.replay_type.peek().litValue == 0) {
          replayCmdAddrs :+= (dut.io.replay_cmd.peek().litValue, dut.io.replay_addr.peek().litValue)
          replayWays :+= dut.io.replay_way.peek().litValue
          replayLiveWays :+= dut.io.replay_live_tag_eq_way.peek().litValue
        }
        if (dut.io.resp_valid.peek().litToBoolean) {
          loadRespData :+= dut.io.resp_data.peek().litValue
        }
        dut.clock.step()
        cycles += 1
      }

      println(s"[PlainSecondaryReplay] replayCmdAddrs=$replayCmdAddrs replayWays=$replayWays replayLiveWays=$replayLiveWays loadRespData=$loadRespData")
      loadRespData.length shouldBe 3
    }
  }
}
