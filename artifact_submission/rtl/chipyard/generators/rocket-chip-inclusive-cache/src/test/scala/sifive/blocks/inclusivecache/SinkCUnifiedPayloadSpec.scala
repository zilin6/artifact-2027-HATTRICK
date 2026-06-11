package sifive.blocks.inclusivecache

import chisel3._
import chiseltest._
import freechips.rocketchip.rocket.CacheCryptoWritebackMeta
import freechips.rocketchip.tilelink.TLMessages._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheCounterTestUtils._

class SinkCUnifiedPayloadSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "SinkC unified payload setup"

  it should "build a ReleaseData payload with matching data and counter lookup state" in {
    test(new SinkC(testParams)) { dut =>
      // Initial state target: an active crypto ReleaseData transaction with line-level counter payload.
      clearSinkC(dut)
      dut.clock.step()

      // Initialization transaction sequence: L1 -> L2 ReleaseData two beats.
      driveReleaseBeat(dut, releaseBeat0, cryptoLine = true, newCounter)
      dut.io.req.valid.expect(true.B)
      dut.io.req.bits.cryptoLine.expect(true.B)
      val putIndex = dut.io.req.bits.put.peek().litValue
      dut.clock.step()

      driveReleaseBeat(dut, releaseBeat1, cryptoLine = true, newCounter)
      dut.clock.step()

      // Consumer under test: SinkC -> SourceD sideband payload pop.
      dut.io.c.valid.poke(false.B)
      dut.io.rel_pop.valid.poke(true.B)
      dut.io.rel_pop.bits.index.poke(putIndex.U)
      dut.io.rel_pop.bits.last.poke(false.B)

      // External assertion: data beat matches the stored release payload.
      dut.io.rel_pop.ready.expect(true.B)
      dut.io.rel_beat.data.expect(releaseBeat0.U)

      // Internal assertion: counter payload is present on the same transaction index.
      dut.io.rel_beat.counterValid.expect(true.B)
      dut.io.rel_beat.counter.expect(newCounter.U)

      dut.clock.step()
      dut.io.rel_pop.bits.last.poke(true.B)
      dut.clock.step()

      dut.io.rel_pop.bits.last.poke(false.B)
      dut.io.rel_pop.ready.expect(false.B)
      dut.io.rel_beat.counterValid.expect(false.B)
    }
  }

  it should "not create a counter payload for a plain ReleaseData line" in {
    test(new SinkC(testParams)) { dut =>
      // Initial state target: a plain ReleaseData transaction with no counter payload.
      clearSinkC(dut)
      dut.clock.step()

      // Initialization transaction sequence: L1 -> L2 plain ReleaseData.
      driveReleaseBeat(dut, releaseBeat0, cryptoLine = false, newCounter)
      dut.io.req.valid.expect(true.B)
      dut.io.req.bits.cryptoLine.expect(false.B)
      val putIndex = dut.io.req.bits.put.peek().litValue
      dut.clock.step()

      driveReleaseBeat(dut, releaseBeat1, cryptoLine = false, newCounter)
      dut.clock.step()

      // Consumer under test: SinkC -> SourceD sideband payload pop.
      dut.io.c.valid.poke(false.B)
      dut.io.rel_pop.valid.poke(true.B)
      dut.io.rel_pop.bits.index.poke(putIndex.U)
      dut.io.rel_pop.bits.last.poke(false.B)

      // External assertion: data payload still exists for the release.
      dut.io.rel_pop.ready.expect(true.B)
      dut.io.rel_beat.data.expect(releaseBeat0.U)

      // Internal assertion: the plain line never exposes a counter payload.
      dut.io.rel_beat.counterValid.expect(false.B)
    }
  }

  it should "surface ProbeAckData counter metadata and write the committed sidecar" in {
    test(new SinkC(testParams)) { dut =>
      // Initial state target: a ProbeAckData beat that should commit data+counter to resident storage.
      clearSinkC(dut)
      dut.clock.step()

      // Initialization transaction sequence: L1 -> L2 ProbeAckData.
      driveReleaseBeat(dut, releaseBeat0, cryptoLine = true, newCounter, opcode = ProbeAckData.litValue.toInt)

      var sawCounterWrite = false
      var sawResp = false
      for (_ <- 0 until 2) {
        // Consumer under test: ProbeAck committed sidecar path.
        if (dut.io.counter_write.valid.peek().litToBoolean) {
          // Internal assertion: committed sidecar write carries the ProbeAckData counter.
          dut.io.counter_write.bits.set.expect(0.U)
          dut.io.counter_write.bits.way.expect(1.U)
          dut.io.counter_write.bits.counter.expect(newCounter.U)
          sawCounterWrite = true
        }
        if (dut.io.resp.valid.peek().litToBoolean) {
          // External assertion: ProbeAck response surfaces matching crypto metadata.
          dut.io.resp.bits.cryptoLine.expect(true.B)
          dut.io.resp.bits.counter.expect(newCounter.U)
          sawResp = true
        }
        dut.clock.step()
      }

      sawCounterWrite shouldBe true
      sawResp shouldBe true
    }
  }

  it should "keep counter payloads separated across concurrent release slots" in {
    test(new SinkC(testParams)) { dut =>
      // Initial state target: two concurrent crypto release transactions in different slots.
      clearSinkC(dut)
      dut.clock.step()

      // Initialization transaction sequence: back-to-back L1 -> L2 releases for two lines.
      driveReleaseBeat(dut, releaseBeat0, cryptoLine = true, newCounter)
      val put0 = dut.io.req.bits.put.peek().litValue
      dut.clock.step()
      driveReleaseBeat(dut, releaseBeat1, cryptoLine = true, newCounter)
      dut.clock.step()

      driveReleaseBeat(dut, committedBeat0, cryptoLine = true, altCounter, address = testParams.cache.blockBytes, source = 1)
      val put1 = dut.io.req.bits.put.peek().litValue
      dut.clock.step()
      driveReleaseBeat(dut, releaseBeat0, cryptoLine = true, altCounter, address = testParams.cache.blockBytes, source = 1)
      dut.clock.step()

      put0 should not be put1

      // Consumer under test: SinkC sideband payload pop on each slot.
      dut.io.c.valid.poke(false.B)
      dut.io.rel_pop.valid.poke(true.B)
      dut.io.rel_pop.bits.index.poke(put0.U)
      dut.io.rel_pop.bits.last.poke(false.B)

      // External assertion: first slot returns the first line's payload beat.
      dut.io.rel_beat.data.expect(releaseBeat0.U)

      // Internal assertion: first slot returns only its own counter payload.
      dut.io.rel_beat.counterValid.expect(true.B)
      dut.io.rel_beat.counter.expect(newCounter.U)
      dut.clock.step()
      dut.io.rel_pop.bits.last.poke(true.B)
      dut.clock.step()

      dut.io.rel_pop.bits.index.poke(put1.U)
      dut.io.rel_pop.bits.last.poke(false.B)
      dut.io.rel_beat.data.expect(committedBeat0.U)
      dut.io.rel_beat.counterValid.expect(true.B)
      dut.io.rel_beat.counter.expect(altCounter.U)
    }
  }

  it should "reuse a freed slot without leaking the previous transaction counter" in {
    test(new SinkC(testParams)) { dut =>
      // Initial state target: a slot gets freed, then reused by a later transaction.
      clearSinkC(dut)
      dut.clock.step()

      // Initialization transaction sequence: first release allocates and drains a slot, second release reuses it.
      driveReleaseBeat(dut, releaseBeat0, cryptoLine = true, newCounter)
      val firstPut = dut.io.req.bits.put.peek().litValue
      dut.clock.step()
      driveReleaseBeat(dut, releaseBeat1, cryptoLine = true, newCounter)
      dut.clock.step()

      dut.io.c.valid.poke(false.B)
      dut.io.rel_pop.valid.poke(true.B)
      dut.io.rel_pop.bits.index.poke(firstPut.U)
      dut.io.rel_pop.bits.last.poke(false.B)
      dut.clock.step()
      dut.io.rel_pop.bits.last.poke(true.B)
      dut.clock.step()
      dut.io.rel_pop.valid.poke(false.B)

      driveReleaseBeat(dut, committedBeat0, cryptoLine = true, altCounter)
      val reusedPut = dut.io.req.bits.put.peek().litValue
      reusedPut shouldBe firstPut
      dut.clock.step()
      driveReleaseBeat(dut, releaseBeat0, cryptoLine = true, altCounter)
      dut.clock.step()

      // Consumer under test: payload pop after slot reuse.
      dut.io.c.valid.poke(false.B)
      dut.io.rel_pop.valid.poke(true.B)
      dut.io.rel_pop.bits.index.poke(reusedPut.U)
      dut.io.rel_pop.bits.last.poke(false.B)

      // External assertion: reused slot returns the second transaction's data.
      dut.io.rel_beat.data.expect(committedBeat0.U)

      // Internal assertion: reused slot only sees the second transaction's counter.
      dut.io.rel_beat.counterValid.expect(true.B)
      dut.io.rel_beat.counter.expect(altCounter.U)
    }
  }
}
