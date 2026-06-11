package sifive.blocks.inclusivecache

import chisel3._
import chiseltest._
import freechips.rocketchip.rocket.CacheCryptoRefillMeta
import freechips.rocketchip.rocket.CacheCryptoWritebackMeta
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheCounterTestUtils._

class SourceDUnifiedValuePathSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private def dumpClosureState(dut: SinkCSourceDClosureHarness, label: String): Unit = {
    val outCounter = dut.io.sourceDOut.bits.user.lift(CacheCryptoRefillMeta).map(_.counter.peek().litValue).getOrElse(BigInt(0))
    println(
      s"[SourceDClosureTrace] $label " +
        s"sinkReqValid=${dut.io.sinkReqValid.peek().litToBoolean} sinkReqPut=${dut.io.sinkReqPut.peek().litValue} " +
        s"relPopValid=${dut.io.relPopValid.peek().litToBoolean} relPopReady=${dut.io.relPopReady.peek().litToBoolean} relPopIndex=${dut.io.relPopIndex.peek().litValue} " +
        s"relBeatData=0x${dut.io.sourceDRelBeatData.peek().litValue.toString(16)} " +
        s"relCounter=0x${dut.io.sourceDRelCounter.peek().litValue.toString(16)} relCounterValid=${dut.io.sourceDRelCounterValid.peek().litToBoolean} " +
        s"consumedRelFire=${dut.io.consumedRelFire.peek().litToBoolean} consumedLastRelFire=${dut.io.consumedLastRelFire.peek().litToBoolean} " +
        s"sourceDHitS2=${dut.io.sourceDHitS2.peek().litToBoolean} sourceDHitS3=${dut.io.sourceDHitS3.peek().litToBoolean} sourceDHitS4=${dut.io.sourceDHitS4.peek().litToBoolean} " +
        s"sourceDS2PayloadHit=${dut.io.sourceDS2PayloadHit.peek().litToBoolean} sourceDS2PipelineHit=${dut.io.sourceDS2PipelineHit.peek().litToBoolean} " +
        s"sourceDBypassHit=${dut.io.sourceDBypassHit.peek().litToBoolean} sourceDS3BypassMask=0x${dut.io.sourceDS3BypassMask.peek().litValue.toString(16)} " +
        s"sourceDS3Counter=0x${dut.io.sourceDS3Counter.peek().litValue.toString(16)} sourceDS4Counter=0x${dut.io.sourceDS4Counter.peek().litValue.toString(16)} " +
        s"capturedReadReqFire=${dut.io.capturedReadReqFire.peek().litToBoolean} capturedReadReqCounterBypassHit=${dut.io.capturedReadReqCounterBypassHit.peek().litToBoolean} " +
        s"outValid=${dut.io.sourceDOut.valid.peek().litToBoolean} outOpcode=0x${dut.io.sourceDOut.bits.opcode.peek().litValue.toString(16)} " +
        s"outData=0x${dut.io.sourceDOut.bits.data.peek().litValue.toString(16)} outCounter=0x${outCounter.toString(16)}")
  }

  private def initClosureHarness(dut: SinkCSourceDClosureHarness): Unit = {
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
  }

  private def initStorageHarness(dut: SinkCSourceDStorageFeedbackHarness): Unit = {
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
  }

  private def driveSinkCRelease(dut: SinkCSourceDClosureHarness, beat0: BigInt, beat1: BigInt, counter: BigInt, cryptoLine: Boolean, source: Int = 0): BigInt = {
    dut.io.sinkC.valid.poke(true.B)
    dut.io.sinkC.bits.source.poke(source.U)
    dut.io.sinkC.bits.data.poke(beat0.U)
    dut.io.sinkC.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter.poke(counter.U)
      u.cryptoLine.poke(cryptoLine.B)
    }
    val putIndexSignal = dut.io.sinkReqPut.peek().litValue
    dut.clock.step()

    dut.io.sinkC.bits.data.poke(beat1.U)
    dut.clock.step()
    dut.io.sinkC.valid.poke(false.B)
    putIndexSignal
  }

  private def driveSinkCReleaseStorage(dut: SinkCSourceDStorageFeedbackHarness, beat0: BigInt, beat1: BigInt, counter: BigInt, cryptoLine: Boolean, source: Int = 0): BigInt = {
    dut.io.sinkC.valid.poke(true.B)
    dut.io.sinkC.bits.source.poke(source.U)
    dut.io.sinkC.bits.data.poke(beat0.U)
    dut.io.sinkC.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter.poke(counter.U)
      u.cryptoLine.poke(cryptoLine.B)
    }
    val putIndexSignal = dut.io.sinkReqPut.peek().litValue
    dut.clock.step()

    dut.io.sinkC.bits.data.poke(beat1.U)
    dut.clock.step()
    dut.io.sinkC.valid.poke(false.B)
    putIndexSignal
  }

  private def issueSourceDReleaseReq(dut: SinkCSourceDClosureHarness, putIdx: BigInt, cryptoLine: Boolean): Unit = {
    dut.io.sourceDReq.valid.poke(true.B)
    dut.io.sourceDReq.bits.prio.foreach(_.poke(false.B))
    dut.io.sourceDReq.bits.prio(2).poke(true.B)
    dut.io.sourceDReq.bits.opcode.poke(ReleaseData)
    dut.io.sourceDReq.bits.param.poke(TtoT)
    dut.io.sourceDReq.bits.size.poke(blockLgSize.U)
    dut.io.sourceDReq.bits.source.poke(0.U)
    dut.io.sourceDReq.bits.tag.poke(0.U)
    dut.io.sourceDReq.bits.offset.poke(0.U)
    dut.io.sourceDReq.bits.put.poke(putIdx.U)
    dut.io.sourceDReq.bits.set.poke(0.U)
    dut.io.sourceDReq.bits.cryptoLine.poke(cryptoLine.B)
    dut.io.sourceDReq.bits.sink.poke(0.U)
    dut.io.sourceDReq.bits.way.poke(1.U)
    dut.io.sourceDReq.bits.bad.poke(false.B)
    dut.clock.step()
    dut.io.sourceDReq.valid.poke(false.B)
    dut.io.sourceDReq.bits.prio(2).poke(false.B)
  }

  private def issueSourceDReleaseReq(dut: SinkCSourceDStorageFeedbackHarness, putIdx: BigInt, cryptoLine: Boolean): Unit = {
    dut.io.sourceDReq.valid.poke(true.B)
    dut.io.sourceDReq.bits.prio.foreach(_.poke(false.B))
    dut.io.sourceDReq.bits.prio(2).poke(true.B)
    dut.io.sourceDReq.bits.opcode.poke(ReleaseData)
    dut.io.sourceDReq.bits.param.poke(TtoT)
    dut.io.sourceDReq.bits.size.poke(blockLgSize.U)
    dut.io.sourceDReq.bits.source.poke(0.U)
    dut.io.sourceDReq.bits.tag.poke(0.U)
    dut.io.sourceDReq.bits.offset.poke(0.U)
    dut.io.sourceDReq.bits.put.poke(putIdx.U)
    dut.io.sourceDReq.bits.set.poke(0.U)
    dut.io.sourceDReq.bits.cryptoLine.poke(cryptoLine.B)
    dut.io.sourceDReq.bits.sink.poke(0.U)
    dut.io.sourceDReq.bits.way.poke(1.U)
    dut.io.sourceDReq.bits.bad.poke(false.B)
    dut.clock.step()
    dut.io.sourceDReq.valid.poke(false.B)
    dut.io.sourceDReq.bits.prio(2).poke(false.B)
  }

  private def issueSourceDGetReq(dut: SinkCSourceDClosureHarness, cryptoLine: Boolean, offset: Int): Unit = {
    dut.io.sourceDReq.valid.poke(true.B)
    dut.io.sourceDReq.bits.prio.foreach(_.poke(false.B))
    dut.io.sourceDReq.bits.prio(0).poke(true.B)
    dut.io.sourceDReq.bits.opcode.poke(Get)
    dut.io.sourceDReq.bits.param.poke(0.U)
    dut.io.sourceDReq.bits.size.poke(beatLgSize.U)
    dut.io.sourceDReq.bits.source.poke(1.U)
    dut.io.sourceDReq.bits.tag.poke(0.U)
    dut.io.sourceDReq.bits.offset.poke(offset.U)
    dut.io.sourceDReq.bits.put.poke(0.U)
    dut.io.sourceDReq.bits.set.poke(0.U)
    dut.io.sourceDReq.bits.cryptoLine.poke(cryptoLine.B)
    dut.io.sourceDReq.bits.sink.poke(0.U)
    dut.io.sourceDReq.bits.way.poke(1.U)
    dut.io.sourceDReq.bits.bad.poke(false.B)
    dut.clock.step()
    dut.io.sourceDReq.valid.poke(false.B)
    dut.io.sourceDReq.bits.prio(0).poke(false.B)
  }

  private def issueSourceDGetReq(dut: SinkCSourceDStorageFeedbackHarness, cryptoLine: Boolean, offset: Int): Unit = {
    dut.io.sourceDReq.valid.poke(true.B)
    dut.io.sourceDReq.bits.prio.foreach(_.poke(false.B))
    dut.io.sourceDReq.bits.prio(0).poke(true.B)
    dut.io.sourceDReq.bits.opcode.poke(Get)
    dut.io.sourceDReq.bits.param.poke(0.U)
    dut.io.sourceDReq.bits.size.poke(beatLgSize.U)
    dut.io.sourceDReq.bits.source.poke(1.U)
    dut.io.sourceDReq.bits.tag.poke(0.U)
    dut.io.sourceDReq.bits.offset.poke(offset.U)
    dut.io.sourceDReq.bits.put.poke(0.U)
    dut.io.sourceDReq.bits.set.poke(0.U)
    dut.io.sourceDReq.bits.cryptoLine.poke(cryptoLine.B)
    dut.io.sourceDReq.bits.sink.poke(0.U)
    dut.io.sourceDReq.bits.way.poke(1.U)
    dut.io.sourceDReq.bits.bad.poke(false.B)
    dut.clock.step()
    dut.io.sourceDReq.valid.poke(false.B)
    dut.io.sourceDReq.bits.prio(0).poke(false.B)
  }

  behavior of "SourceD unified value path"

  it should "keep the second-beat payload counter valid even after the first beat has already entered the pipeline" in {
    test(new SinkCSourceDClosureHarness(testParams)) { dut =>
      initClosureHarness(dut)

      val putIdx = driveSinkCRelease(dut, releaseBeat0, releaseBeat1, newCounter, cryptoLine = true)
      dumpClosureState(dut, "after-sinkc-release")

      dut.io.sourceDReq.valid.poke(true.B)
      dut.io.sourceDReq.bits.prio.foreach(_.poke(false.B))
      dut.io.sourceDReq.bits.prio(2).poke(true.B)
      dut.io.sourceDReq.bits.opcode.poke(ReleaseData)
      dut.io.sourceDReq.bits.param.poke(TtoT)
      dut.io.sourceDReq.bits.size.poke(blockLgSize.U)
      dut.io.sourceDReq.bits.source.poke(0.U)
      dut.io.sourceDReq.bits.tag.poke(0.U)
      dut.io.sourceDReq.bits.offset.poke(0.U)
      dut.io.sourceDReq.bits.put.poke(putIdx.U)
      dut.io.sourceDReq.bits.set.poke(0.U)
      dut.io.sourceDReq.bits.cryptoLine.poke(true.B)
      dut.io.sourceDReq.bits.sink.poke(0.U)
      dut.io.sourceDReq.bits.way.poke(1.U)
      dut.io.sourceDReq.bits.bad.poke(false.B)

      dumpClosureState(dut, "before-sourceD-release-pop-step")
      dut.clock.step()
      dut.io.sourceDReq.valid.poke(false.B)
      dut.io.sourceDReq.bits.prio(2).poke(false.B)
      dumpClosureState(dut, "after-sourceD-release-pop-step")

      dumpClosureState(dut, "before-overlap-step")
      dut.clock.step()
      dumpClosureState(dut, "after-overlap-step")

      // External assertion: the release path progresses without tripping a payload/pipeline assertion.
      dut.io.consumedRelFire.expect(true.B)
      dut.io.consumedRelData.expect(releaseBeat0.U)

      // Internal assertion: by the second beat, payload remains the active source while an older beat is already visible in the pipeline.
      dut.io.sourceDS2PayloadHit.expect(true.B)
      dut.io.sourceDHitS3.expect(true.B)
      dut.io.sourceDRelCounterValid.expect(true.B)
      dut.io.sourceDRelCounter.expect(newCounter.U)
      dut.io.sourceDS3Counter.expect(newCounter.U)
    }
  }

  it should "prefer the younger SinkC payload-backed value over stale committed state inside the live bypass window" in {
    test(new SinkCSourceDClosureHarness(testParams)) { dut =>
      initClosureHarness(dut)

      val putIdx = driveSinkCRelease(dut, releaseBeat0, releaseBeat1, newCounter, cryptoLine = true)

      issueSourceDReleaseReq(dut, putIdx, cryptoLine = true)

      var sawPayloadConsume = false
      var sawLastPayloadConsume = false
      for (_ <- 0 until 3) {
        sawPayloadConsume ||= dut.io.consumedRelFire.peek().litToBoolean
        sawLastPayloadConsume ||= dut.io.consumedLastRelFire.peek().litToBoolean
        dut.clock.step()
      }

      issueSourceDGetReq(dut, cryptoLine = true, offset = testParams.cache.beatBytes)

      var seenResp = false
      var respData = BigInt(-1)
      var respCounter = BigInt(-1)
      for (_ <- 0 until 12 if !seenResp) {
        if (dut.io.sourceDOut.valid.peek().litToBoolean &&
            dut.io.sourceDOut.bits.opcode.peek().litValue == AccessAckData.litValue) {
          respData = dut.io.sourceDOut.bits.data.peek().litValue
          dut.io.sourceDOut.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
            respCounter = u.counter.peek().litValue
            u.cryptoLine.expect(true.B)
          }
          seenResp = true
        }
        dut.clock.step()
      }

      seenResp shouldBe true
      respData shouldBe releaseBeat1
      respCounter shouldBe newCounter
      sawPayloadConsume shouldBe true
      sawLastPayloadConsume shouldBe true
      dut.io.consumedRelData.expect(releaseBeat0.U)
      dut.io.consumedRelCounter.expect(newCounter.U)
      dut.io.consumedLastRelData.expect(releaseBeat1.U)
      dut.io.consumedLastRelCounter.expect(newCounter.U)
      dut.io.capturedReadReqFire.expect(true.B)
      dut.io.capturedReadReqDataBypassMask.expect("b1".U)
      dut.io.capturedReadReqCounterBypassHit.expect(true.B)
      dut.io.capturedRespData.expect(releaseBeat1.U)
      dut.io.capturedRespCounter.expect(newCounter.U)
      dut.io.capturedUsedPayloadData.expect(true.B)
      dut.io.capturedUsedCommittedData.expect(false.B)
    }
  }

  it should "keep using the younger pipeline-backed value after the payload slot has already been popped" in {
    test(new SinkCSourceDClosureHarness(testParams)) { dut =>
      initClosureHarness(dut)

      val putIdx = driveSinkCRelease(dut, releaseBeat0, releaseBeat1, newCounter, cryptoLine = true)
      issueSourceDReleaseReq(dut, putIdx, cryptoLine = true)

      var sawLastPayloadConsume = false
      var alignedPipelineWindow = false
      var waitedForWindow = 0
      while (!alignedPipelineWindow && waitedForWindow < 8) {
        sawLastPayloadConsume ||= dut.io.consumedLastRelFire.peek().litToBoolean
        alignedPipelineWindow =
          dut.io.consumedLastRelFire.peek().litToBoolean &&
            (dut.io.sourceDHitS3.peek().litToBoolean ||
              dut.io.sourceDHitS4.peek().litToBoolean ||
              dut.io.sourceDBypassHit.peek().litToBoolean)
        dumpClosureState(dut, "pipeline-after-pop-wait")
        if (!alignedPipelineWindow) {
          dut.clock.step()
          waitedForWindow += 1
        }
      }

      withClue(
        s"failed to place read inside the post-pop pipeline-visible window: " +
          s"sawLastPayloadConsume=$sawLastPayloadConsume " +
          s"sourceDHitS3=${dut.io.sourceDHitS3.peek().litToBoolean} " +
          s"sourceDHitS4=${dut.io.sourceDHitS4.peek().litToBoolean} " +
          s"sourceDBypassHit=${dut.io.sourceDBypassHit.peek().litToBoolean}") {
        alignedPipelineWindow shouldBe true
      }

      dumpClosureState(dut, "pipeline-before-get")
      issueSourceDGetReq(dut, cryptoLine = true, offset = 0)
      dumpClosureState(dut, "pipeline-after-get")

      var seenResp = false
      var respData = BigInt(-1)
      var respCounter = BigInt(-1)
      for (_ <- 0 until 12 if !seenResp) {
        dumpClosureState(dut, "pipeline-loop")
        if (dut.io.sourceDOut.valid.peek().litToBoolean &&
            dut.io.sourceDOut.bits.opcode.peek().litValue == AccessAckData.litValue) {
          respData = dut.io.sourceDOut.bits.data.peek().litValue
          dut.io.sourceDOut.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
            respCounter = u.counter.peek().litValue
            u.cryptoLine.expect(true.B)
          }
          seenResp = true
        }
        dut.clock.step()
      }

      withClue(
        s"pipeline-backed post-pop read mismatch: " +
          s"respData=0x${respData.toString(16)} expectedData=0x${releaseBeat0.toString(16)} " +
          s"respCounter=0x${respCounter.toString(16)} expectedCounter=0x${newCounter.toString(16)} " +
          s"sawLastPayloadConsume=$sawLastPayloadConsume " +
          s"consumedRelData=0x${dut.io.consumedRelData.peek().litValue.toString(16)} " +
          s"consumedRelCounter=0x${dut.io.consumedRelCounter.peek().litValue.toString(16)} " +
          s"consumedLastRelData=0x${dut.io.consumedLastRelData.peek().litValue.toString(16)} " +
          s"consumedLastRelCounter=0x${dut.io.consumedLastRelCounter.peek().litValue.toString(16)} " +
          s"alignedPipelineWindow=$alignedPipelineWindow waitedForWindow=$waitedForWindow " +
          s"capturedReadReqFire=${dut.io.capturedReadReqFire.peek().litToBoolean} " +
          s"capturedReadReqCounterBypassHit=${dut.io.capturedReadReqCounterBypassHit.peek().litToBoolean} " +
          s"capturedRespData=0x${dut.io.capturedRespData.peek().litValue.toString(16)} " +
          s"capturedRespCounter=0x${dut.io.capturedRespCounter.peek().litValue.toString(16)} " +
          s"capturedUsedPayloadData=${dut.io.capturedUsedPayloadData.peek().litToBoolean} " +
          s"capturedUsedCommittedData=${dut.io.capturedUsedCommittedData.peek().litToBoolean} " +
          s"sourceDHitS2=${dut.io.sourceDHitS2.peek().litToBoolean} " +
          s"sourceDHitS3=${dut.io.sourceDHitS3.peek().litToBoolean} " +
          s"sourceDHitS4=${dut.io.sourceDHitS4.peek().litToBoolean} " +
          s"sourceDS2PayloadHit=${dut.io.sourceDS2PayloadHit.peek().litToBoolean} " +
          s"sourceDS2PipelineHit=${dut.io.sourceDS2PipelineHit.peek().litToBoolean} " +
          s"sourceDBypassHit=${dut.io.sourceDBypassHit.peek().litToBoolean} " +
          s"sourceDS3Counter=0x${dut.io.sourceDS3Counter.peek().litValue.toString(16)} " +
          s"sourceDS4Counter=0x${dut.io.sourceDS4Counter.peek().litValue.toString(16)}") {
        seenResp shouldBe true
        respData shouldBe releaseBeat0
        respCounter shouldBe newCounter
        sawLastPayloadConsume shouldBe true
        dut.io.consumedLastRelData.expect(releaseBeat1.U)
        dut.io.consumedLastRelCounter.expect(newCounter.U)
        dut.io.capturedReadReqFire.expect(true.B)
        dut.io.capturedReadReqCounterBypassHit.expect(true.B)
        dut.io.capturedRespData.expect(releaseBeat0.U)
        dut.io.capturedRespCounter.expect(newCounter.U)
        dut.io.capturedUsedPayloadData.expect(true.B)
        dut.io.capturedUsedCommittedData.expect(false.B)
      }
    }
  }

  it should "return the resident-updated data and counter after storage feedback establishes the committed view" in {
    test(new SinkCSourceDStorageFeedbackHarness(testParams)) { dut =>
      initStorageHarness(dut)

      val putIdx = driveSinkCReleaseStorage(dut, releaseBeat0, releaseBeat1, newCounter, cryptoLine = true)
      issueSourceDReleaseReq(dut, putIdx, cryptoLine = true)

      var sawDataWriteBeat0 = false
      var sawDataWriteBeat1 = false
      var sawCounterWrite = false
      for (_ <- 0 until 16) {
        if (dut.io.dataWriteFire.peek().litToBoolean) {
          if (dut.io.dataWriteBeat.peek().litValue == 0) sawDataWriteBeat0 = true
          else sawDataWriteBeat1 = true
        }
        sawCounterWrite ||= dut.io.counterWriteFire.peek().litToBoolean
        dut.clock.step()
      }

      issueSourceDGetReq(dut, cryptoLine = true, offset = testParams.cache.beatBytes)

      var seenResp = false
      var respData = BigInt(-1)
      var respCounter = BigInt(-1)
      for (_ <- 0 until 12 if !seenResp) {
        if (dut.io.sourceDOut.valid.peek().litToBoolean &&
            dut.io.sourceDOut.bits.opcode.peek().litValue == AccessAckData.litValue) {
          respData = dut.io.sourceDOut.bits.data.peek().litValue
          dut.io.sourceDOut.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
            respCounter = u.counter.peek().litValue
            u.cryptoLine.expect(true.B)
          }
          seenResp = true
        }
        dut.clock.step()
      }

      seenResp shouldBe true
      respData shouldBe releaseBeat1
      respCounter shouldBe newCounter
      sawDataWriteBeat0 shouldBe true
      sawDataWriteBeat1 shouldBe true
      sawCounterWrite shouldBe true
      dut.io.feedbackBeat0Valid.expect(true.B)
      dut.io.feedbackBeat1Valid.expect(true.B)
      dut.io.feedbackCounterValid.expect(true.B)
      dut.io.feedbackBeat1Data.expect(releaseBeat1.U)
      dut.io.feedbackCounterData.expect(newCounter.U)
    }
  }

  it should "emit a committed counter write when a crypto release retires through the real SinkC to SourceD path" in {
    test(new SinkCSourceDStorageFeedbackHarness(testParams)) { dut =>
      initStorageHarness(dut)

      val putIdx = driveSinkCReleaseStorage(dut, releaseBeat0, releaseBeat1, newCounter, cryptoLine = true)
      issueSourceDReleaseReq(dut, putIdx, cryptoLine = true)

      var sawCounterWrite = false
      var observedCounterWrite = BigInt(-1)
      var sawDataWrite = false
      for (_ <- 0 until 16) {
        sawDataWrite ||= dut.io.dataWriteFire.peek().litToBoolean
        if (dut.io.counterWriteFire.peek().litToBoolean) {
          sawCounterWrite = true
          observedCounterWrite = dut.io.counterWriteData.peek().litValue
        }
        dut.clock.step()
      }

      sawCounterWrite shouldBe true
      observedCounterWrite shouldBe newCounter
      sawDataWrite shouldBe true
      dut.io.consumedLastRelFire.expect(true.B)
      dut.io.consumedLastRelData.expect(releaseBeat1.U)
      dut.io.consumedLastRelCounter.expect(newCounter.U)
    }
  }

  it should "not emit a committed counter write for a plain release even though the data writeback still retires" in {
    test(new SinkCSourceDStorageFeedbackHarness(testParams)) { dut =>
      initStorageHarness(dut)

      val putIdx = driveSinkCReleaseStorage(dut, releaseBeat0, releaseBeat1, newCounter, cryptoLine = false)
      issueSourceDReleaseReq(dut, putIdx, cryptoLine = false)

      var sawCounterWrite = false
      var sawDataWriteBeat0 = false
      var sawDataWriteBeat1 = false
      for (_ <- 0 until 16) {
        if (dut.io.dataWriteFire.peek().litToBoolean) {
          if (dut.io.dataWriteBeat.peek().litValue == 0) sawDataWriteBeat0 = true
          else sawDataWriteBeat1 = true
        }
        sawCounterWrite ||= dut.io.counterWriteFire.peek().litToBoolean
        dut.clock.step()
      }

      sawCounterWrite shouldBe false
      sawDataWriteBeat0 shouldBe true
      sawDataWriteBeat1 shouldBe true
      dut.io.feedbackCounterValid.expect(false.B)
      dut.io.consumedRelCounterValid.expect(false.B)
      dut.io.consumedLastRelCounterValid.expect(false.B)
    }
  }

  it should "not attach active crypto counter metadata to a plain-line refill consumer" in {
    test(new SinkCSourceDClosureHarness(testParams)) { dut =>
      initClosureHarness(dut)

      val putIdx = driveSinkCRelease(dut, releaseBeat0, releaseBeat1, newCounter, cryptoLine = true)
      issueSourceDReleaseReq(dut, putIdx, cryptoLine = true)
      dut.clock.step()

      issueSourceDGetReq(dut, cryptoLine = false, offset = testParams.cache.beatBytes)

      var seenResp = false
      var respData = BigInt(-1)
      var respCounter = BigInt(-1)
      var respCryptoLine = true
      for (_ <- 0 until 12 if !seenResp) {
        if (dut.io.sourceDOut.valid.peek().litToBoolean &&
            dut.io.sourceDOut.bits.opcode.peek().litValue == AccessAckData.litValue) {
          respData = dut.io.sourceDOut.bits.data.peek().litValue
          dut.io.sourceDOut.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
            respCounter = u.counter.peek().litValue
            respCryptoLine = u.cryptoLine.peek().litToBoolean
          }
          seenResp = true
        }
        dut.clock.step()
      }

      seenResp shouldBe true
      respData shouldBe releaseBeat1
      respCounter shouldBe 0
      respCryptoLine shouldBe false
      dut.io.consumedLastRelFire.expect(true.B)
      dut.io.consumedLastRelCounter.expect(newCounter.U)
      dut.io.capturedReadReqFire.expect(true.B)
      dut.io.capturedReadReqCounterBypassHit.expect(true.B)
    }
  }
}
