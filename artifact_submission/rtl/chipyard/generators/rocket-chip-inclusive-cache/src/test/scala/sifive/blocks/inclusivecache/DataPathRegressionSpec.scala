package sifive.blocks.inclusivecache

import chisel3._
import chiseltest._
import freechips.rocketchip.rocket.CacheCryptoWritebackMeta
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheCounterTestUtils._

class DataPathRegressionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Inclusive-cache data path regression"

  it should "keep SinkC data putbuffer behavior unchanged while adding counter payloads" in {
    test(new SinkC(testParams)) { dut =>
      // Initial state target: a crypto release still uses the original data putbuffer semantics.
      clearSinkC(dut)
      dut.clock.step()

      // Initialization transaction sequence: L1 -> L2 ReleaseData through SinkC.
      driveReleaseBeat(dut, releaseBeat0, cryptoLine = true, newCounter)
      val putIndex = dut.io.req.bits.put.peek().litValue
      dut.clock.step()
      driveReleaseBeat(dut, releaseBeat1, cryptoLine = true, newCounter)
      dut.clock.step()
      dut.io.c.valid.poke(false.B)

      // Consumer under test: SinkC data pop order.
      dut.io.rel_pop.valid.poke(true.B)
      dut.io.rel_pop.bits.index.poke(putIndex.U)
      dut.io.rel_pop.bits.last.poke(false.B)

      // External assertion: beat 0 data still comes out first.
      dut.io.rel_beat.data.expect(releaseBeat0.U)

      // Internal assertion: counter payload is additive and does not perturb the data slot.
      dut.io.rel_beat.counterValid.expect(true.B)
      dut.clock.step()
      dut.io.rel_pop.bits.last.poke(true.B)
      dut.io.rel_beat.data.expect(releaseBeat1.U)
    }
  }

  it should "keep SourceD data bypass behavior unchanged while the counter path bypasses in parallel" in {
    test(new SinkCSourceDClosureHarness(testParams)) { dut =>
      // Initial state target: SourceD should still sit on the original two-beat SinkC payload path while counter metadata tracks with it.
      dut.io.sinkC.valid.poke(false.B)
      dut.io.sinkC.bits.opcode.poke(ReleaseData)
      dut.io.sinkC.bits.param.poke(TtoT)
      dut.io.sinkC.bits.size.poke(blockLgSize.U)
      dut.io.sinkC.bits.source.poke(0.U)
      dut.io.sinkC.bits.address.poke(0.U)
      dut.io.sinkC.bits.data.poke(0.U)
      dut.io.sinkC.bits.corrupt.poke(false.B)
      dut.io.sinkC.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
        u.counter.poke(0.U)
        u.cryptoLine.poke(false.B)
      }

      dut.io.sourceDReq.valid.poke(false.B)
      dut.io.sourceDReq.bits.prio.foreach(_.poke(false.B))
      dut.io.sourceDReq.bits.control.poke(false.B)
      dut.io.sourceDReq.bits.opcode.poke(ReleaseData)
      dut.io.sourceDReq.bits.param.poke(TtoT)
      dut.io.sourceDReq.bits.size.poke(blockLgSize.U)
      dut.io.sourceDReq.bits.source.poke(0.U)
      dut.io.sourceDReq.bits.tag.poke(0.U)
      dut.io.sourceDReq.bits.offset.poke(0.U)
      dut.io.sourceDReq.bits.put.poke(0.U)
      dut.io.sourceDReq.bits.set.poke(0.U)
      dut.io.sourceDReq.bits.cryptoLine.poke(true.B)
      dut.io.sourceDReq.bits.sink.poke(0.U)
      dut.io.sourceDReq.bits.way.poke(1.U)
      dut.io.sourceDReq.bits.bad.poke(false.B)
      dut.io.sourceDOut.ready.poke(true.B)
      dut.io.committedData.poke(committedBeat0.U)
      dut.io.committedCounter.poke(oldCounter.U)
      dut.clock.step()

      // Initialization transaction sequence: real two-beat SinkC payload, then SourceD consumes it.
      dut.io.sinkC.valid.poke(true.B)
      dut.io.sinkC.bits.data.poke(releaseBeat0.U)
      dut.io.sinkC.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
        u.counter.poke(newCounter.U)
        u.cryptoLine.poke(true.B)
      }
      dut.clock.step()

      val realPutIndex = dut.io.sinkReqPut.peek().litValue
      dut.io.sinkC.bits.data.poke(releaseBeat1.U)
      dut.clock.step()
      dut.io.sinkC.valid.poke(false.B)

      dut.io.sourceDReq.valid.poke(true.B)
      dut.io.sourceDReq.bits.put.poke(realPutIndex.U)
      dut.io.sourceDReq.bits.prio(2).poke(true.B)
      dut.clock.step()
      dut.io.sourceDReq.valid.poke(false.B)
      dut.io.sourceDReq.bits.prio(2).poke(false.B)

      // Consumer under test: real SinkC -> SourceD data path.
      var sawFirstConsume = false
      var sawLastConsume = false
      for (_ <- 0 until 4) {
        sawFirstConsume ||= dut.io.consumedRelFire.peek().litToBoolean
        sawLastConsume ||= dut.io.consumedLastRelFire.peek().litToBoolean
        dut.clock.step()
      }

      // External assertion: the two consumed payload beats still preserve the original data ordering.
      sawFirstConsume shouldBe true
      dut.io.consumedRelData.expect(releaseBeat0.U)
      sawLastConsume shouldBe true
      dut.io.consumedLastRelData.expect(releaseBeat1.U)

      // Internal assertion: counter metadata stays paired with those consumed data beats.
      dut.io.consumedRelCounter.expect(newCounter.U)
      dut.io.consumedRelCounterValid.expect(true.B)
      dut.io.consumedLastRelCounter.expect(newCounter.U)
      dut.io.consumedLastRelCounterValid.expect(true.B)
    }
  }

  it should "keep SourceC victim data reads unchanged while snapshot state is added beside them" in {
    test(new SourceCSnapshotHarness(testParams)) { dut =>
      // Initial state target: committed victim data should still be read and written back unchanged.
      dut.io.sourceCReq.valid.poke(false.B)
      dut.io.sourceAReq.valid.poke(false.B)
      dut.io.sourceCOut.ready.poke(true.B)
      dut.io.sourceAOut.ready.poke(true.B)
      dut.io.committedData.poke(committedBeat0.U)
      dut.io.committedCounter.poke(oldCounter.U)
      dut.io.evictSafe.poke(false.B)
      dut.io.snapshotIdx.poke(1.U)
      dut.clock.step()

      // Initialization transaction sequence: committed victim writeback under evict_safe.
      dut.io.sourceCReq.valid.poke(true.B)
      dut.io.sourceCReq.bits.opcode.poke(ReleaseData)
      dut.io.sourceCReq.bits.param.poke(TtoN)
      dut.io.sourceCReq.bits.source.poke(mshrSource(1).U)
      dut.io.sourceCReq.bits.tag.poke(0.U)
      dut.io.sourceCReq.bits.set.poke(0.U)
      dut.io.sourceCReq.bits.way.poke(1.U)
      dut.io.sourceCReq.bits.dirty.poke(true.B)
      dut.io.sourceCReq.bits.cryptoLine.poke(true.B)
      dut.clock.step()

      dut.io.sourceCReq.valid.poke(false.B)
      dut.io.evictSafe.poke(true.B)
      var sawReads = false
      for (_ <- 0 until 2) {
        sawReads ||= dut.io.bsReadValid.peek().litToBoolean && dut.io.ctrReadValid.peek().litToBoolean
        dut.clock.step()
      }
      dut.clock.step()

      // Consumer under test: SourceC victim data writeback.
      dut.io.sourceCOut.valid.expect(true.B)

      // External assertion: victim data payload is still the committed beat.
      dut.io.sourceCOut.bits.data.expect(committedBeat0.U)

      // Internal assertion: data-bank read and snapshot read were both issued.
      sawReads shouldBe true
    }
  }
}
