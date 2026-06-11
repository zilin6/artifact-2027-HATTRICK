package sifive.blocks.inclusivecache

import chisel3._
import chiseltest._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheCounterTestUtils._

class SourceCSnapshotSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private def captureFirstSourceCData(
    valid: => Boolean,
    data: => BigInt,
    step: () => Unit,
    limit: Int = 8): Option[BigInt] = {
    var seen: Option[BigInt] = None
    var cycles = limit
    while (seen.isEmpty && cycles > 0) {
      if (valid) seen = Some(data)
      step()
      cycles -= 1
    }
    seen
  }

  private def initClosureHarness(dut: SinkCSourceCSnapshotClosureHarness): Unit = {
    dut.io.sinkC.valid.poke(false.B)
    dut.io.sinkC.bits.opcode.poke(ReleaseData)
    dut.io.sinkC.bits.param.poke(TtoT)
    dut.io.sinkC.bits.size.poke(blockLgSize.U)
    dut.io.sinkC.bits.source.poke(0.U)
    dut.io.sinkC.bits.address.poke(0.U)
    dut.io.sinkC.bits.data.poke(0.U)
    dut.io.sinkC.bits.corrupt.poke(false.B)
    dut.io.sinkC.bits.user.lift(freechips.rocketchip.rocket.CacheCryptoWritebackMeta).foreach { u =>
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
    }

    dut.io.sourceCReq.valid.poke(false.B)
    dut.io.sourceAReq.valid.poke(false.B)
    dut.io.sourceCOut.ready.poke(true.B)
    dut.io.sourceAOut.ready.poke(true.B)
    dut.io.committedData.poke(committedBeat0.U)
    dut.io.committedCounter.poke(oldCounter.U)
    dut.io.evictSafe.poke(false.B)
    dut.io.snapshotIdx.poke(1.U)
  }

  private def initPipelineHarness(dut: SourceDSourceCSnapshotHarness): Unit = {
    dut.io.sinkC.valid.poke(false.B)
    dut.io.sinkC.bits.opcode.poke(ReleaseData)
    dut.io.sinkC.bits.param.poke(TtoT)
    dut.io.sinkC.bits.size.poke(blockLgSize.U)
    dut.io.sinkC.bits.source.poke(0.U)
    dut.io.sinkC.bits.address.poke(0.U)
    dut.io.sinkC.bits.data.poke(0.U)
    dut.io.sinkC.bits.corrupt.poke(false.B)
    dut.io.sinkC.bits.user.lift(freechips.rocketchip.rocket.CacheCryptoWritebackMeta).foreach { u =>
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

    dut.io.sourceCReq.valid.poke(false.B)
    dut.io.sourceCReq.bits.opcode.poke(ReleaseData)
    dut.io.sourceCReq.bits.param.poke(TtoN)
    dut.io.sourceCReq.bits.source.poke(mshrSource(1).U)
    dut.io.sourceCReq.bits.tag.poke(0.U)
    dut.io.sourceCReq.bits.set.poke(0.U)
    dut.io.sourceCReq.bits.way.poke(1.U)
    dut.io.sourceCReq.bits.dirty.poke(true.B)
    dut.io.sourceCReq.bits.cryptoLine.poke(true.B)

    dut.io.committedData.poke(committedBeat0.U)
    dut.io.committedCounter.poke(oldCounter.U)
    dut.io.snapshotIdx.poke(1.U)
    dut.io.sourceCOut.ready.poke(true.B)
  }

  behavior of "SourceC snapshot path"

  it should "wait for evict_safe before freezing the counter snapshot" in {
    test(new SourceCSnapshotHarness(testParams)) { dut =>
      // Initial state target: dirty crypto victim exists in committed resident storage only.
      dut.io.sourceCReq.valid.poke(false.B)
      dut.io.sourceAReq.valid.poke(false.B)
      dut.io.sourceCOut.ready.poke(true.B)
      dut.io.sourceAOut.ready.poke(true.B)
      dut.io.committedData.poke(committedBeat0.U)
      dut.io.committedCounter.poke(oldCounter.U)
      dut.io.evictSafe.poke(false.B)
      dut.io.snapshotIdx.poke(1.U)
      dut.clock.step()

      // Initialization transaction sequence: victim writeback request, evict_safe arrives later.
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
      dut.io.ctrReadValid.expect(false.B)
      dut.io.bsReadValid.expect(false.B)
      dut.io.snapshotValid.expect(false.B)

      dut.io.evictSafe.poke(true.B)
      dut.io.ctrReadValid.expect(true.B)
      dut.io.bsReadValid.expect(true.B)
      dut.io.ctrReadFire.expect(true.B)
      dut.io.bsReadFire.expect(true.B)
      dut.io.currentBeat.expect(0.U)
      dut.clock.step()

      // Consumer under test: SourceC snapshot freezer.
      dut.io.snapshotValid.expect(false.B)
      dut.clock.step()

      // External assertion: counter snapshot becomes visible only after the protected read window.
      dut.io.snapshotValid.expect(true.B)
      dut.io.snapshotData.expect(oldCounter.U)

      // Internal assertion: the snapshot is keyed to the expected MSHR and the data read was issued in the same window.
      dut.io.snapshotReqMshrIdx.expect(1.U)
      val observedDataSnapshot =
        captureFirstSourceCData(
          dut.io.sourceCOut.valid.peek().litToBoolean,
          dut.io.sourceCOut.bits.data.peek().litValue,
          () => dut.clock.step()).getOrElse(BigInt(-1))

      // External assertion: data snapshot is frozen from the same committed victim window.
      observedDataSnapshot shouldBe committedBeat0
    }
  }

  it should "clear the frozen snapshot after SourceA consumes it" in {
    test(new SourceCSnapshotHarness(testParams)) { dut =>
      // Initial state target: SourceC has frozen a committed victim snapshot for one MSHR.
      dut.io.sourceCReq.valid.poke(false.B)
      dut.io.sourceAReq.valid.poke(false.B)
      dut.io.sourceCOut.ready.poke(true.B)
      dut.io.sourceAOut.ready.poke(true.B)
      dut.io.committedData.poke(committedBeat0.U)
      dut.io.committedCounter.poke(oldCounter.U)
      dut.io.evictSafe.poke(false.B)
      dut.io.snapshotIdx.poke(1.U)
      dut.clock.step()

      // Initialization transaction sequence: SourceC freezes snapshot, SourceA later sends it.
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
      var sawProtectedWindow = false
      for (_ <- 0 until 2) {
        sawProtectedWindow ||= dut.io.ctrReadFire.peek().litToBoolean &&
          dut.io.bsReadFire.peek().litToBoolean &&
          dut.io.currentBeat.peek().litValue == 0
        dut.clock.step()
      }

      // Consumer under test: SourceA consuming SourceC snapshot.
      dut.io.snapshotValid.expect(true.B)
      dut.io.snapshotData.expect(oldCounter.U)
      val observedDataSnapshot =
        captureFirstSourceCData(
          dut.io.sourceCOut.valid.peek().litToBoolean,
          dut.io.sourceCOut.bits.data.peek().litValue,
          () => dut.clock.step()).getOrElse(BigInt(-1))
      observedDataSnapshot shouldBe committedBeat0
      dut.io.sourceAReq.valid.poke(true.B)
      dut.io.sourceAReq.bits.tag.poke(0.U)
      dut.io.sourceAReq.bits.set.poke(0.U)
      dut.io.sourceAReq.bits.way.poke(1.U)
      dut.io.sourceAReq.bits.put.poke(0.U)
      dut.io.sourceAReq.bits.param.poke(0.U)
      dut.io.sourceAReq.bits.source.poke(mshrSource(1).U)
      dut.io.sourceAReq.bits.block.poke(true.B)
      dut.io.sourceAReq.bits.isCounter.poke(true.B)
      dut.io.sourceAReq.bits.isCounterWrite.poke(true.B)
      dut.clock.step()

      dut.io.sourceAReq.valid.poke(false.B)

      // External assertion: the outgoing counterPut carries the frozen snapshot.
      dut.io.sourceAOut.valid.expect(true.B)
      dut.io.sourceAOut.bits.opcode.expect(PutFullData)
      dut.io.sourceAOut.bits.data.expect(oldCounter.U)

      // Internal assertion: snapshot buffer is popped and cleared after send.
      sawProtectedWindow shouldBe true
      dut.io.snapshotPop.expect(true.B)
      dut.clock.step()
      dut.io.snapshotValid.expect(false.B)
    }
  }

  it should "keep data and counter committed-aligned even when a younger SinkC payload exists" in {
    test(new SinkCSourceCSnapshotClosureHarness(testParams)) { dut =>
      // Initial state target: old committed resident counter plus a newer active SinkC payload on the same line.
      initClosureHarness(dut)
      dut.clock.step()

      // Initialization transaction sequence: L1 -> L2 crypto ReleaseData builds active payload, then SourceC freezes victim snapshot.
      dut.io.sinkC.valid.poke(true.B)
      dut.io.sinkC.bits.opcode.poke(ReleaseData)
      dut.io.sinkC.bits.param.poke(TtoT)
      dut.io.sinkC.bits.size.poke(blockLgSize.U)
      dut.io.sinkC.bits.source.poke(0.U)
      dut.io.sinkC.bits.address.poke(0.U)
      dut.io.sinkC.bits.data.poke(releaseBeat0.U)
      dut.io.sinkC.bits.user.lift(freechips.rocketchip.rocket.CacheCryptoWritebackMeta).foreach { u =>
        u.counter.poke(newCounter.U)
        u.cryptoLine.poke(true.B)
      }
      dut.clock.step()

      dut.io.sinkC.bits.data.poke(releaseBeat1.U)
      dut.clock.step()
      dut.io.sinkC.valid.poke(false.B)

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
      var sawProtectedWindow = false
      for (_ <- 0 until 2) {
        sawProtectedWindow ||= dut.io.ctrReadFire.peek().litToBoolean &&
          dut.io.bsReadFire.peek().litToBoolean &&
          dut.io.currentBeat.peek().litValue == 0
        dut.clock.step()
      }

      // Consumer under test: SourceC counter snapshot direct consumer.
      dut.io.snapshotValid.expect(true.B)
      val observedDataSnapshot =
        captureFirstSourceCData(
          dut.io.sourceCOut.valid.peek().litToBoolean,
          dut.io.sourceCOut.bits.data.peek().litValue,
          () => dut.clock.step()).getOrElse(BigInt(-1))

      val observedSnapshot = dut.io.snapshotData.peek().litValue
      val observedPayload = dut.io.sinkPayloadCounter.peek().litValue
      val observedPayloadValid = dut.io.sinkPayloadCounterValid.peek().litToBoolean
      val observedPayloadData = dut.io.sinkPayloadData.peek().litValue
      val sinkAcceptedBeat0Valid = dut.io.sinkAcceptedBeat0Valid.peek().litToBoolean
      val sinkAcceptedBeat0Data = dut.io.sinkAcceptedBeat0Data.peek().litValue
      val sinkAcceptedBeat1Valid = dut.io.sinkAcceptedBeat1Valid.peek().litToBoolean
      val sinkAcceptedBeat1Data = dut.io.sinkAcceptedBeat1Data.peek().litValue
      val sinkAcceptedCounter = dut.io.sinkAcceptedCounter.peek().litValue
      val sourceCFreezeFire = dut.io.sourceCFreezeFire.peek().litToBoolean
      val sourceCFreezeCommittedData = dut.io.sourceCFreezeCommittedData.peek().litValue
      val sourceCFreezeCommittedCounter = dut.io.sourceCFreezeCommittedCounter.peek().litValue

      // External assertion: under the current RTL/data-path semantics, SourceC keeps data+counter committed-aligned.
      withClue(
        s"expected SourceC snapshot to stay committed-aligned even though a younger SinkC payload exists. " +
          s"sinkAcceptedBeat0Valid=$sinkAcceptedBeat0Valid sinkAcceptedBeat0Data=0x${sinkAcceptedBeat0Data.toString(16)} " +
          s"sinkAcceptedBeat1Valid=$sinkAcceptedBeat1Valid sinkAcceptedBeat1Data=0x${sinkAcceptedBeat1Data.toString(16)} " +
          s"sinkAcceptedCounter=0x${sinkAcceptedCounter.toString(16)} " +
          s"dataSnapshot=0x${observedDataSnapshot.toString(16)} payloadData=0x${observedPayloadData.toString(16)} " +
          s"counterSnapshot=0x${observedSnapshot.toString(16)} payloadCounter=0x${observedPayload.toString(16)} " +
          s"payloadValid=$observedPayloadValid protectedWindow=$sawProtectedWindow " +
          s"sourceCFreezeFire=$sourceCFreezeFire sourceCFreezeCommittedData=0x${sourceCFreezeCommittedData.toString(16)} " +
          s"sourceCFreezeCommittedCounter=0x${sourceCFreezeCommittedCounter.toString(16)} " +
          s"committedData=0x${committedBeat0.toString(16)} committedCounter=0x${oldCounter.toString(16)}") {
        observedDataSnapshot shouldBe committedBeat0
        observedSnapshot shouldBe oldCounter
      }

      // Internal assertion: the younger SinkC payload really existed, but the protected freeze still took the committed pair.
      sinkAcceptedBeat0Valid shouldBe true
      sinkAcceptedBeat0Data shouldBe releaseBeat0
      sinkAcceptedBeat1Valid shouldBe true
      sinkAcceptedBeat1Data shouldBe releaseBeat1
      sinkAcceptedCounter shouldBe newCounter
      observedPayloadValid shouldBe true
      sawProtectedWindow shouldBe true
      sourceCFreezeFire shouldBe true
      sourceCFreezeCommittedData shouldBe committedBeat0
      sourceCFreezeCommittedCounter shouldBe oldCounter
      observedPayloadData shouldBe releaseBeat0
      observedPayload shouldBe newCounter
    }
  }

  it should "keep data and counter committed-aligned even when a younger SourceD pipeline source exists" in {
    test(new SourceDSourceCSnapshotHarness(testParams)) { dut =>
      // Initial state target: same line has a younger counter in SourceD pipeline while committed storage stays old.
      initPipelineHarness(dut)
      dut.clock.step()

      // Initialization transaction sequence: build a real SinkC two-beat payload, let SourceD consume it, then request SourceC snapshot.
      dut.io.sinkC.valid.poke(true.B)
      dut.io.sinkC.bits.data.poke(releaseBeat0.U)
      dut.io.sinkC.bits.user.lift(freechips.rocketchip.rocket.CacheCryptoWritebackMeta).foreach { u =>
        u.counter.poke(newCounter.U)
        u.cryptoLine.poke(true.B)
      }
      dut.clock.step()

      val putIdx = dut.io.sinkReqPut.peek().litValue
      dut.io.sinkC.bits.data.poke(releaseBeat1.U)
      dut.clock.step()
      dut.io.sinkC.valid.poke(false.B)

      dut.io.sourceDReq.valid.poke(true.B)
      dut.io.sourceDReq.bits.prio.foreach(_.poke(false.B))
      dut.io.sourceDReq.bits.prio(2).poke(true.B)
      dut.io.sourceDReq.bits.put.poke(putIdx.U)
      dut.clock.step()

      dut.io.sourceDReq.valid.poke(false.B)
      dut.clock.step()

      dut.io.sourceCReq.valid.poke(true.B)
      dut.clock.step()
      dut.io.sourceCReq.valid.poke(false.B)

      while (!dut.io.sourceDEvictSafe.peek().litToBoolean) {
        dut.clock.step()
      }
      var sawProtectedWindow = false
      for (_ <- 0 until 2) {
        sawProtectedWindow ||= dut.io.ctrReadFire.peek().litToBoolean &&
          dut.io.bsReadFire.peek().litToBoolean &&
          dut.io.currentBeat.peek().litValue == 0
        dut.clock.step()
      }

      // Consumer under test: SourceC snapshot after the protected SourceD window.
      dut.io.snapshotValid.expect(true.B)
      val observedDataSnapshot =
        captureFirstSourceCData(
          dut.io.sourceCOut.valid.peek().litToBoolean,
          dut.io.sourceCOut.bits.data.peek().litValue,
          () => dut.clock.step()).getOrElse(BigInt(-1))

      val observedSnapshot = dut.io.snapshotData.peek().litValue
      val bypassHit = dut.io.sourceDCounterBypassHit.peek().litToBoolean
      val bypassData = dut.io.sourceDCounterBypassData.peek().litValue
      val retiredVisible =
        dut.io.sourceDS5Valid.peek().litToBoolean ||
          dut.io.sourceDS6Valid.peek().litToBoolean ||
          dut.io.sourceDS7Valid.peek().litToBoolean
      val sourceCFreezeFire = dut.io.sourceCFreezeFire.peek().litToBoolean
      val sourceCFreezeCommittedData = dut.io.sourceCFreezeCommittedData.peek().litValue
      val sourceCFreezeCommittedCounter = dut.io.sourceCFreezeCommittedCounter.peek().litValue

      // External assertion: under the current RTL/data-path semantics, SourceC keeps data+counter committed-aligned.
      withClue(
        s"expected SourceC snapshot to stay committed-aligned even though a younger SourceD pipeline source exists. " +
          s"dataSnapshot=0x${observedDataSnapshot.toString(16)} expectedData=0x${releaseBeat0.toString(16)} " +
          s"counterSnapshot=0x${observedSnapshot.toString(16)} bypassHit=$bypassHit " +
          s"bypassData=0x${bypassData.toString(16)} retiredVisible=$retiredVisible " +
          s"sourceCFreezeFire=$sourceCFreezeFire sourceCFreezeCommittedData=0x${sourceCFreezeCommittedData.toString(16)} " +
          s"sourceCFreezeCommittedCounter=0x${sourceCFreezeCommittedCounter.toString(16)} " +
          s"protectedWindow=$sawProtectedWindow committedData=0x${committedBeat0.toString(16)} " +
          s"committedCounter=0x${oldCounter.toString(16)}") {
        observedDataSnapshot shouldBe committedBeat0
        observedSnapshot shouldBe oldCounter
      }

      // Internal assertion: a younger SourceD latest source really existed, but SourceC still froze committed inputs.
      sawProtectedWindow shouldBe true
      sourceCFreezeFire shouldBe true
      sourceCFreezeCommittedData shouldBe committedBeat0
      sourceCFreezeCommittedCounter shouldBe oldCounter
      (bypassHit || retiredVisible) shouldBe true
    }
  }
}
