package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, CacheCryptoWritebackMeta, M_XRD, M_XWR}
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InclusiveCacheTopBoomPayloadBypassSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val cache = InclusiveCacheTopHarness.fastCache.copy(sets = 64, ways = 2)
  private val micro = InclusiveCacheTopHarness.fastMicro
  private val blockBytes = cache.blockBytes
  private val beatCount = blockBytes / cache.beatBytes

  private val lineA = BigInt("80002000", 16)
  private val lineB = BigInt("80002200", 16)
  private val counterBase = BigInt("100000", 16)
  private val dataKey = BigInt("00112233445566778899aabbccddeeff", 16)
  private val initCounterA = BigInt("8877665544332211", 16)
  private val initCounterB = BigInt("7766554433221100", 16)
  private val newStoreWord = BigInt("1122334455667788", 16)

  private val lineABeats = (0 until beatCount).map(i => BigInt("4000000000000000", 16) + i)
  private val lineBBeats = (0 until beatCount).map(i => BigInt("5000000000000000", 16) + i)

  private def clearHarness(dut: InclusiveCacheTopBoomDualHarnessWrapper): Unit = {
    dut.io.req_valid.poke(false.B)
    dut.io.req_addr.poke(0.U)
    dut.io.req_data.poke(0.U)
    dut.io.req_cmd.poke(M_XRD)
    dut.io.req_mem_size.poke(3.U)
    dut.io.req_mem_signed.poke(false.B)
    dut.io.resp_clear.poke(false.B)
    dut.io.raw_d_clear.poke(false.B)
    dut.io.stall_boom_inner_d.poke(false.B)
    dut.io.stall_sourceD_retire_bs_wadr.poke(false.B)
    dut.io.stall_sourceD_retire_counter_write.poke(false.B)

    dut.io.dataKey.poke(0.U)
    dut.io.cacheCryptoLoadEnableValue.poke(false.B)
    dut.io.cacheCryptoStoreEnableValue.poke(false.B)
    dut.io.cacheCryptoEnableWen.poke(false.B)
    dut.io.cacheCryptoCounterBaseValue.poke(0.U)
    dut.io.cacheCryptoCounterBaseWen.poke(false.B)
    dut.io.cus_base_address.poke(0.U)
    dut.io.cus_base_wen.poke(false.B)
    dut.io.log.poke(false.B)

    val raw = dut.io.raw
    raw.a.valid.poke(false.B)
    raw.a.bits.opcode.poke(AcquireBlock)
    raw.a.bits.param.poke(NtoT)
    raw.a.bits.size.poke(log2Ceil(blockBytes).U)
    raw.a.bits.source.poke(0.U)
    raw.a.bits.address.poke(0.U)
    raw.a.bits.mask.poke("hff".U)
    raw.a.bits.data.poke(0.U)
    raw.a.bits.corrupt.poke(false.B)
    raw.a.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
    }
    raw.c.valid.poke(false.B)
    raw.e.valid.poke(false.B)
    raw.e.bits.sink.poke(0.U)
    raw.b.ready.poke(true.B)
    raw.d.ready.poke(true.B)

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

  private def programCryptoMode(dut: InclusiveCacheTopBoomDualHarnessWrapper): Unit = {
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

  private def serviceOuter(dut: InclusiveCacheTopBoomDualHarnessWrapper): Unit = {
    val outer = dut.io.outer
    if (outer.a.valid.peek().litToBoolean) {
      val opcode = outer.a.bits.opcode.peek().litValue
      val address = outer.a.bits.address.peek().litValue
      val source = outer.a.bits.source.peek().litValue
      outer.a.ready.poke(true.B)
      dut.clock.step()
      outer.a.ready.poke(false.B)
      if (opcode == AcquireBlock.litValue) {
        val (beats, counter) =
          if (address == lineA) (lineABeats, initCounterA)
          else if (address == lineB) (lineBBeats, initCounterB)
          else throw new RuntimeException(s"unexpected line address 0x${address.toString(16)}")
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
          while (!outer.d.ready.peek().litToBoolean) dut.clock.step()
          dut.clock.step()
        }
        outer.d.valid.poke(false.B)
      } else if (opcode == Get.litValue) {
        val counter =
          if (address == counterBase + ((lineA >> log2Ceil(blockBytes)) << 3)) initCounterA
          else if (address == counterBase + ((lineB >> log2Ceil(blockBytes)) << 3)) initCounterB
          else throw new RuntimeException(s"unexpected counter address 0x${address.toString(16)}")
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
        while (!outer.d.ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        outer.d.valid.poke(false.B)
      } else {
        fail(s"unexpected outer opcode 0x${opcode.toString(16)}")
      }
    } else {
      dut.clock.step()
    }
  }

  private def issueBoomReq(dut: InclusiveCacheTopBoomDualHarnessWrapper, cmd: UInt, addr: BigInt, data: BigInt = 0): Unit = {
    dut.io.resp_clear.poke(true.B)
    dut.clock.step()
    dut.io.resp_clear.poke(false.B)
    dut.io.req_cmd.poke(cmd)
    dut.io.req_addr.poke(addr.U)
    dut.io.req_data.poke(data.U)
    dut.io.req_mem_size.poke(3.U)
    dut.io.req_mem_signed.poke(false.B)
    dut.io.req_valid.poke(true.B)
    while (!dut.io.req_ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.req_valid.poke(false.B)
  }

  behavior of "Boom SinkC plus raw SourceA payload bypass"

  it should "return new data and new counter through the payload bypass window" in {
    test(new InclusiveCacheTopBoomDualHarnessWrapper(cache = cache, micro = micro, dcacheWays = 1, dcacheSets = 8))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearHarness(dut)
      dut.clock.step(8)
      programCryptoMode(dut)

      issueBoomReq(dut, M_XRD, lineA)
      var warmupA = 0
      while (!dut.io.resp_seen.peek().litToBoolean && warmupA < 2000) {
        serviceOuter(dut)
        warmupA += 1
      }
      assert(dut.io.resp_seen.peek().litToBoolean, "Timed out waiting BOOM load resp for lineA")

      issueBoomReq(dut, M_XWR, lineA, newStoreWord)
      dut.clock.step(20)

      issueBoomReq(dut, M_XRD, lineB)

      val raw = dut.io.raw
      dut.io.raw_d_clear.poke(true.B)
      dut.clock.step()
      dut.io.raw_d_clear.poke(false.B)

      var startedReload = false
      var rawReloadAccepted = false
      var sawSinkCWindow = false
      var stallCyclesRemaining = 0
      var sawHeldBoomD = false
      var firstBeatData = BigInt(0)
      var firstBeatCounter = BigInt(0)
      var firstObservedBoomDCounter = BigInt(0)
      var cycles = 0
      while (dut.io.raw_d_count.peek().litValue < beatCount && cycles < 4000) {
        val rawAFireThisCycle = startedReload && raw.a.valid.peek().litToBoolean && raw.a.ready.peek().litToBoolean
        val sawRelevantSinkC =
          dut.io.c_valid.peek().litToBoolean &&
          dut.io.c_address.peek().litValue == lineA &&
          dut.io.c_crypto.peek().litToBoolean
        if (!sawSinkCWindow && sawRelevantSinkC) {
          sawSinkCWindow = true
          stallCyclesRemaining = 192
        }

        dut.io.stall_boom_inner_d.poke((stallCyclesRemaining > 0).B)
        dut.io.stall_sourceD_retire_bs_wadr.poke(false.B)
        dut.io.stall_sourceD_retire_counter_write.poke(false.B)

        if (!startedReload && stallCyclesRemaining > 0) {
          raw.a.valid.poke(true.B)
          raw.a.bits.opcode.poke(AcquireBlock)
          raw.a.bits.param.poke(NtoT)
          raw.a.bits.size.poke(log2Ceil(blockBytes).U)
          raw.a.bits.source.poke(2.U)
          raw.a.bits.address.poke(lineA.U)
          raw.a.bits.mask.poke("hff".U)
          raw.a.bits.data.poke(0.U)
          raw.a.bits.corrupt.poke(false.B)
          raw.a.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
            u.counter.poke(0.U)
            u.cryptoLine.poke(true.B)
          }
          startedReload = true
        }
        if (firstObservedBoomDCounter == 0 &&
            dut.io.boom_d_valid.peek().litToBoolean &&
            !dut.io.boom_d_ready.peek().litToBoolean &&
            dut.io.boom_d_crypto.peek().litToBoolean) {
          firstObservedBoomDCounter = dut.io.boom_d_counter.peek().litValue
          sawHeldBoomD = true
        }
        if (dut.io.raw_d_seen.peek().litToBoolean && dut.io.raw_d_count.peek().litValue == 1 && firstBeatData == 0) {
          firstBeatData = dut.io.raw_d_data.peek().litValue
          firstBeatCounter = dut.io.raw_d_counter.peek().litValue
        }
        serviceOuter(dut)
        if (rawAFireThisCycle) {
          rawReloadAccepted = true
          raw.a.valid.poke(false.B)
        }
        if (stallCyclesRemaining > 0) {
          if (sawHeldBoomD && dut.io.raw_d_count.peek().litValue > 0) stallCyclesRemaining = 0
          else stallCyclesRemaining -= 1
        }
        cycles += 1
      }
      dut.io.stall_boom_inner_d.poke(false.B)

      assert(sawSinkCWindow, "never observed Boom SinkC window for lineA")
      assert(sawHeldBoomD, "stall_boom_inner_d never actually held a BOOM-side crypto D response")
      assert(startedReload, "raw reload never started inside the stretched Boom SinkC window")
      assert(rawReloadAccepted, "raw reload never handshook on raw.a during stretched window")
      assert(dut.io.raw_d_count.peek().litValue == beatCount, s"raw reload did not receive full GrantData beats; count=${dut.io.raw_d_count.peek().litValue}")

      raw.e.valid.poke(true.B)
      raw.e.bits.sink.poke(dut.io.raw_d_sink.peek())
      dut.clock.step()
      raw.e.valid.poke(false.B)

      firstBeatCounter shouldBe BigInt("8877665544332212", 16)
      println(f"[PayloadBypassCheck] firstBeatData=0x$firstBeatData%x firstBeatCounter=0x$firstBeatCounter%x stalledBoomDCounter=0x$firstObservedBoomDCounter%x")
    }
  }
}
