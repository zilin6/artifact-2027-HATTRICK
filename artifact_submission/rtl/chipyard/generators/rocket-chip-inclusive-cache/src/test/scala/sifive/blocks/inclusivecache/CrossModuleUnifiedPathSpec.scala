package sifive.blocks.inclusivecache

import chisel3._
import chiseltest._
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, CacheCryptoWritebackMeta}
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheCounterTestUtils._

class CrossModuleUnifiedPathSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
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

  behavior of "Cross-module unified counter path"

  it should "return new data and new counter when the read stays inside the live bypass window" in {
    test(new SinkCSourceDClosureHarness(testParams)) { dut =>
      // Initial state target: committed storage is stale, but the read is forced into SourceD's short live bypass window.
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

      // Initialization transaction sequence: L1 -> L2 ReleaseData through SinkC, then SourceD consumes payload.
      dut.io.sinkC.valid.poke(true.B)
      dut.io.sinkC.bits.data.poke(releaseBeat0.U)
      dut.io.sinkC.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
        u.counter.poke(newCounter.U)
        u.cryptoLine.poke(true.B)
      }
      dut.io.sinkReqValid.expect(true.B)
      val realPutIndex = dut.io.sinkReqPut.peek().litValue
      dut.clock.step()

      dut.io.sinkC.bits.data.poke(releaseBeat1.U)
      dut.clock.step()
      dut.io.sinkC.valid.poke(false.B)

      // First let SourceD consume the real SinkC payload through its C-path request.
      dut.io.sourceDReq.valid.poke(true.B)
      dut.io.sourceDReq.bits.put.poke(realPutIndex.U)
      dut.io.sourceDReq.bits.prio(2).poke(true.B)
      dut.clock.step()
      dut.io.sourceDReq.valid.poke(false.B)
      dut.io.sourceDReq.bits.prio(2).poke(false.B)

      var sawRealPayloadConsume = false
      for (_ <- 0 until 2) {
        sawRealPayloadConsume ||= dut.io.relPopValid.peek().litToBoolean || dut.io.relPopReady.peek().litToBoolean
        dut.clock.step()
      }

      // Then immediately issue the read consumer while the live bypass/retired window should still be active.
      dut.io.sourceDReq.valid.poke(true.B)
      dut.io.sourceDReq.bits.prio(0).poke(true.B)
      dut.io.sourceDReq.bits.opcode.poke(Get)
      dut.io.sourceDReq.bits.param.poke(0.U)
      dut.io.sourceDReq.bits.size.poke(beatLgSize.U)
      dut.io.sourceDReq.bits.source.poke(1.U)
      dut.io.sourceDReq.bits.offset.poke(testParams.cache.beatBytes.U)
      dut.clock.step()
      dut.io.sourceDReq.valid.poke(false.B)
      dut.io.sourceDReq.bits.prio(0).poke(false.B)

      // Consumer under test: SourceD read inside the bypass window.
      var seen = false
      var observedRespData = BigInt(-1)
      var observedRespCounter = BigInt(-1)
      var consumedRelFire = false
      var consumedRelIdx = BigInt(-1)
      var consumedRelData = BigInt(-1)
      var consumedRelCounter = BigInt(-1)
      var consumedRelCounterValid = false
      var consumedLastRelFire = false
      var consumedLastRelIdx = BigInt(-1)
      var consumedLastRelData = BigInt(-1)
      var consumedLastRelCounter = BigInt(-1)
      var consumedLastRelCounterValid = false
      var capturedReadReqFire = false
      var capturedReadReqDataBypassMask = BigInt(-1)
      var capturedReadReqCounterBypassHit = false
      for (_ <- 0 until 12 if !seen) {
        if (dut.io.consumedRelFire.peek().litToBoolean) {
          consumedRelFire = true
          consumedRelIdx = dut.io.consumedRelIdx.peek().litValue
          consumedRelData = dut.io.consumedRelData.peek().litValue
          consumedRelCounter = dut.io.consumedRelCounter.peek().litValue
          consumedRelCounterValid = dut.io.consumedRelCounterValid.peek().litToBoolean
        }
        if (dut.io.consumedLastRelFire.peek().litToBoolean) {
          consumedLastRelFire = true
          consumedLastRelIdx = dut.io.consumedLastRelIdx.peek().litValue
          consumedLastRelData = dut.io.consumedLastRelData.peek().litValue
          consumedLastRelCounter = dut.io.consumedLastRelCounter.peek().litValue
          consumedLastRelCounterValid = dut.io.consumedLastRelCounterValid.peek().litToBoolean
        }
        if (dut.io.capturedReadReqFire.peek().litToBoolean) {
          capturedReadReqFire = true
          capturedReadReqDataBypassMask = dut.io.capturedReadReqDataBypassMask.peek().litValue
          capturedReadReqCounterBypassHit = dut.io.capturedReadReqCounterBypassHit.peek().litToBoolean
        }
        if (dut.io.sourceDOut.valid.peek().litToBoolean &&
            dut.io.sourceDOut.bits.opcode.peek().litValue == AccessAckData.litValue) {
          observedRespData = dut.io.sourceDOut.bits.data.peek().litValue
          dut.io.sourceDOut.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
            observedRespCounter = u.counter.peek().litValue
            u.cryptoLine.expect(true.B)
          }
          seen = true
        }
        dut.clock.step()
      }

      // External assertion: inside the bypass window, SourceD should return the younger data+counter pair.
      withClue(
        s"expected bypass-window read to return younger payload-backed data+counter. " +
          s"sawRealPayloadConsume=$sawRealPayloadConsume " +
          s"consumedRelFire=$consumedRelFire consumedRelIdx=$consumedRelIdx " +
          s"consumedRelData=0x${consumedRelData.toString(16)} consumedRelCounter=0x${consumedRelCounter.toString(16)} " +
          s"consumedRelCounterValid=$consumedRelCounterValid " +
          s"consumedLastRelFire=$consumedLastRelFire consumedLastRelIdx=$consumedLastRelIdx " +
          s"consumedLastRelData=0x${consumedLastRelData.toString(16)} consumedLastRelCounter=0x${consumedLastRelCounter.toString(16)} " +
          s"consumedLastRelCounterValid=$consumedLastRelCounterValid " +
          s"capturedReadReqFire=$capturedReadReqFire " +
          s"capturedReadReqDataBypassMask=0x${capturedReadReqDataBypassMask.toString(16)} " +
          s"capturedReadReqCounterBypassHit=$capturedReadReqCounterBypassHit " +
          s"respData=0x${observedRespData.toString(16)} expectedData=0x${releaseBeat1.toString(16)} " +
          s"respCounter=0x${observedRespCounter.toString(16)} expectedCounter=0x${newCounter.toString(16)} " +
          s"committedData=0x${committedBeat0.toString(16)} committedCounter=0x${oldCounter.toString(16)}") {
        consumedRelFire shouldBe true
        consumedRelIdx shouldBe realPutIndex
        consumedRelData shouldBe releaseBeat0
        consumedRelCounter shouldBe newCounter
        consumedRelCounterValid shouldBe true
        consumedLastRelFire shouldBe true
        consumedLastRelIdx shouldBe realPutIndex
        consumedLastRelData shouldBe releaseBeat1
        consumedLastRelCounter shouldBe newCounter
        consumedLastRelCounterValid shouldBe true
        capturedReadReqFire shouldBe true
        capturedReadReqDataBypassMask should not be 0
        capturedReadReqCounterBypassHit shouldBe true
        observedRespData shouldBe releaseBeat1
        observedRespCounter shouldBe newCounter
      }
      seen shouldBe true
    }
  }

  it should "return new data and new counter after storage feedback updates the resident view" in {
    test(new SinkCSourceDStorageFeedbackHarness(testParams)) { dut =>
      // Initial state target: committed resident state is old, then SourceD writes back the release and later reads through the resident view.
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
      dut.io.initialBeat0.poke(committedBeat0.U)
      dut.io.initialBeat1.poke(committedBeat1.U)
      dut.io.initialCounter.poke(oldCounter.U)
      dut.clock.step()

      // Initialization transaction sequence: build payload through SinkC, consume it through SourceD, then wait for resident feedback writes.
      dut.io.sinkC.valid.poke(true.B)
      dut.io.sinkC.bits.data.poke(releaseBeat0.U)
      dut.io.sinkC.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
        u.counter.poke(newCounter.U)
        u.cryptoLine.poke(true.B)
      }
      dut.io.sinkReqValid.expect(true.B)
      val realPutIndex = dut.io.sinkReqPut.peek().litValue
      dut.clock.step()

      dut.io.sinkC.bits.data.poke(releaseBeat1.U)
      dut.clock.step()
      dut.io.sinkC.valid.poke(false.B)

      dut.io.sourceDReq.valid.poke(true.B)
      dut.io.sourceDReq.bits.put.poke(realPutIndex.U)
      dut.io.sourceDReq.bits.prio(2).poke(true.B)
      dut.clock.step()
      dut.io.sourceDReq.valid.poke(false.B)
      dut.io.sourceDReq.bits.prio(2).poke(false.B)

      var sawDataWriteBeat0 = false
      var sawDataWriteBeat1 = false
      var sawCounterWrite = false
      for (_ <- 0 until 16) {
        if (dut.io.dataWriteFire.peek().litToBoolean) {
          if (dut.io.dataWriteBeat.peek().litValue == 0) {
            sawDataWriteBeat0 = true
          } else {
            sawDataWriteBeat1 = true
          }
        }
        sawCounterWrite ||= dut.io.counterWriteFire.peek().litToBoolean
        dut.clock.step()
      }

      // Then issue a later read that must come back from resident feedback, not from the short live bypass window.
      dut.io.sourceDReq.valid.poke(true.B)
      dut.io.sourceDReq.bits.prio(0).poke(true.B)
      dut.io.sourceDReq.bits.opcode.poke(Get)
      dut.io.sourceDReq.bits.param.poke(0.U)
      dut.io.sourceDReq.bits.size.poke(beatLgSize.U)
      dut.io.sourceDReq.bits.source.poke(1.U)
      dut.io.sourceDReq.bits.offset.poke(testParams.cache.beatBytes.U)
      dut.clock.step()
      dut.io.sourceDReq.valid.poke(false.B)
      dut.io.sourceDReq.bits.prio(0).poke(false.B)

      var seen = false
      var observedRespData = BigInt(-1)
      var observedRespCounter = BigInt(-1)
      for (_ <- 0 until 12 if !seen) {
        if (dut.io.sourceDOut.valid.peek().litToBoolean &&
            dut.io.sourceDOut.bits.opcode.peek().litValue == AccessAckData.litValue) {
          observedRespData = dut.io.sourceDOut.bits.data.peek().litValue
          dut.io.sourceDOut.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
            observedRespCounter = u.counter.peek().litValue
            u.cryptoLine.expect(true.B)
          }
          seen = true
        }
        dut.clock.step()
      }

      // External assertion: after resident feedback is established, the later read should still return new data + new counter.
      withClue(
        s"expected storage-feedback read to return resident-updated data+counter. " +
          s"sawDataWriteBeat0=$sawDataWriteBeat0 sawDataWriteBeat1=$sawDataWriteBeat1 sawCounterWrite=$sawCounterWrite " +
          s"feedbackBeat0Valid=${dut.io.feedbackBeat0Valid.peek().litToBoolean} feedbackBeat0Data=0x${dut.io.feedbackBeat0Data.peek().litValue.toString(16)} " +
          s"feedbackBeat1Valid=${dut.io.feedbackBeat1Valid.peek().litToBoolean} feedbackBeat1Data=0x${dut.io.feedbackBeat1Data.peek().litValue.toString(16)} " +
          s"feedbackCounterValid=${dut.io.feedbackCounterValid.peek().litToBoolean} feedbackCounterData=0x${dut.io.feedbackCounterData.peek().litValue.toString(16)} " +
          s"respData=0x${observedRespData.toString(16)} expectedData=0x${releaseBeat1.toString(16)} " +
          s"respCounter=0x${observedRespCounter.toString(16)} expectedCounter=0x${newCounter.toString(16)}") {
        sawDataWriteBeat0 shouldBe true
        sawDataWriteBeat1 shouldBe true
        sawCounterWrite shouldBe true
        dut.io.feedbackBeat1Valid.expect(true.B)
        dut.io.feedbackCounterValid.expect(true.B)
        observedRespData shouldBe releaseBeat1
        observedRespCounter shouldBe newCounter
      }
      seen shouldBe true
    }
  }

  it should "keep SourceC snapshot and SourceA send committed-aligned even when a younger SinkC payload exists" in {
    test(new SinkCSourceCSnapshotClosureHarness(testParams)) { dut =>
      // Initial state target: active SinkC payload is newer than committed storage and should flow to SourceA through SourceC snapshot.
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
      dut.io.sourceCReq.valid.poke(false.B)
      dut.io.sourceAReq.valid.poke(false.B)
      dut.io.sourceCOut.ready.poke(true.B)
      dut.io.sourceAOut.ready.poke(true.B)
      dut.io.committedData.poke(committedBeat0.U)
      dut.io.committedCounter.poke(oldCounter.U)
      dut.io.evictSafe.poke(false.B)
      dut.io.snapshotIdx.poke(1.U)
      dut.clock.step()

      // Initialization transaction sequence: SinkC builds payload, SourceC freezes snapshot, SourceA sends counterPut.
      dut.io.sinkC.valid.poke(true.B)
      dut.io.sinkC.bits.data.poke(releaseBeat0.U)
      dut.io.sinkC.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
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
      val observedDataSnapshot =
        captureFirstSourceCData(
          dut.io.sourceCOut.valid.peek().litToBoolean,
          dut.io.sourceCOut.bits.data.peek().litValue,
          () => dut.clock.step()).getOrElse(BigInt(-1))

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

      // Consumer under test: cross-module SinkC -> SourceC -> SourceA closure.
      dut.io.sourceAOut.valid.expect(true.B)

      val sentCounter = dut.io.sourceAOut.bits.data.peek().litValue
      val snapshotCounter = dut.io.snapshotData.peek().litValue
      val sinkCounter = dut.io.sinkPayloadCounter.peek().litValue
      val sinkCounterValid = dut.io.sinkPayloadCounterValid.peek().litToBoolean
      val observedPayloadData = dut.io.sinkPayloadData.peek().litValue
      val sinkAcceptedBeat0Valid = dut.io.sinkAcceptedBeat0Valid.peek().litToBoolean
      val sinkAcceptedBeat0Data = dut.io.sinkAcceptedBeat0Data.peek().litValue
      val sinkAcceptedBeat1Valid = dut.io.sinkAcceptedBeat1Valid.peek().litToBoolean
      val sinkAcceptedBeat1Data = dut.io.sinkAcceptedBeat1Data.peek().litValue
      val sinkAcceptedCounter = dut.io.sinkAcceptedCounter.peek().litValue
      val sourceCFreezeFire = dut.io.sourceCFreezeFire.peek().litToBoolean
      val sourceCFreezeCommittedData = dut.io.sourceCFreezeCommittedData.peek().litValue
      val sourceCFreezeCommittedCounter = dut.io.sourceCFreezeCommittedCounter.peek().litValue

      // External assertion: SourceC freezes a committed-aligned snapshot, and SourceA sends exactly that frozen counter.
      withClue(
        s"expected SinkC payload -> SourceC snapshot -> SourceA send closure to remain committed-aligned under the current RTL/data-path semantics. " +
          s"sinkAcceptedBeat0Valid=$sinkAcceptedBeat0Valid sinkAcceptedBeat0Data=0x${sinkAcceptedBeat0Data.toString(16)} " +
          s"sinkAcceptedBeat1Valid=$sinkAcceptedBeat1Valid sinkAcceptedBeat1Data=0x${sinkAcceptedBeat1Data.toString(16)} " +
          s"sinkAcceptedCounter=0x${sinkAcceptedCounter.toString(16)} " +
          s"dataSnapshot=0x${observedDataSnapshot.toString(16)} payloadData=0x${observedPayloadData.toString(16)} " +
          s"sent=0x${sentCounter.toString(16)} counterSnapshot=0x${snapshotCounter.toString(16)} " +
          s"sinkCounter=0x${sinkCounter.toString(16)} sinkValid=$sinkCounterValid " +
          s"sourceCFreezeFire=$sourceCFreezeFire " +
          s"sourceCFreezeCommittedData=0x${sourceCFreezeCommittedData.toString(16)} " +
          s"sourceCFreezeCommittedCounter=0x${sourceCFreezeCommittedCounter.toString(16)} " +
          s"protectedWindow=$sawProtectedWindow committedData=0x${committedBeat0.toString(16)} " +
          s"committedCounter=0x${oldCounter.toString(16)}") {
        observedDataSnapshot shouldBe committedBeat0
        snapshotCounter shouldBe oldCounter
        sentCounter shouldBe oldCounter
      }

      // Internal assertion: the younger SinkC payload really existed, but SourceC froze the committed pair and SourceA forwarded it.
      sinkAcceptedBeat0Valid shouldBe true
      sinkAcceptedBeat0Data shouldBe releaseBeat0
      sinkAcceptedBeat1Valid shouldBe true
      sinkAcceptedBeat1Data shouldBe releaseBeat1
      sinkAcceptedCounter shouldBe newCounter
      sawProtectedWindow shouldBe true
      sourceCFreezeFire shouldBe true
      sourceCFreezeCommittedData shouldBe committedBeat0
      sourceCFreezeCommittedCounter shouldBe oldCounter
      sinkCounterValid shouldBe true
      observedPayloadData shouldBe releaseBeat0
      sinkCounter shouldBe newCounter
      dut.io.snapshotPop.expect(true.B)
    }
  }
}
