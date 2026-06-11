package sifive.blocks.inclusivecache

import chisel3._
import chiseltest._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import MetaData._
import InclusiveCacheCounterTestUtils._

class MSHRSchedulerRepeatSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private def driveCounterAllocate(
    dut: MSHRObservationHarness,
    tag: Int,
    offset: Int,
    cryptoLine: Boolean = true): Unit = {
    dut.io.allocate.valid.poke(true.B)
    dut.io.allocate.bits.prio.foreach(_.poke(false.B))
    dut.io.allocate.bits.prio(0).poke(true.B)
    dut.io.allocate.bits.control.poke(false.B)
    dut.io.allocate.bits.opcode.poke(AcquireBlock)
    dut.io.allocate.bits.param.poke(NtoT)
    dut.io.allocate.bits.size.poke(blockLgSize.U)
    dut.io.allocate.bits.source.poke(0.U)
    dut.io.allocate.bits.tag.poke(tag.U)
    dut.io.allocate.bits.offset.poke(offset.U)
    dut.io.allocate.bits.put.poke(0.U)
    dut.io.allocate.bits.set.poke(0.U)
    dut.io.allocate.bits.cryptoLine.poke(cryptoLine.B)
    dut.io.allocate.bits.repeat.poke(false.B)
  }

  private def driveInvalidDirectory(dut: MSHRObservationHarness): Unit = {
    dut.io.directory.valid.poke(true.B)
    dut.io.directory.bits.dirty.poke(false.B)
    dut.io.directory.bits.state.poke(INVALID)
    dut.io.directory.bits.clients.poke(0.U)
    dut.io.directory.bits.tag.poke(0.U)
    dut.io.directory.bits.cryptoLine.poke(false.B)
    dut.io.directory.bits.counterValid.poke(false.B)
    dut.io.directory.bits.hit.poke(false.B)
    dut.io.directory.bits.way.poke(1.U)
  }

  private def driveSinkDResp(
    dut: MSHRObservationHarness,
    opcode: UInt,
    isCounter: Boolean,
    last: Boolean): Unit = {
    dut.io.sinkd.valid.poke(true.B)
    dut.io.sinkd.bits.last.poke(last.B)
    dut.io.sinkd.bits.opcode.poke(opcode)
    dut.io.sinkd.bits.param.poke(toT)
    dut.io.sinkd.bits.source.poke(0.U)
    dut.io.sinkd.bits.sink.poke(0.U)
    dut.io.sinkd.bits.denied.poke(false.B)
    dut.io.sinkd.bits.isCounter.poke(isCounter.B)
  }

  private def clearObservedMSHR(dut: MSHRObservationHarness): Unit = {
    dut.io.allocate.valid.poke(false.B)
    dut.io.allocate.bits.prio.foreach(_.poke(false.B))
    dut.io.allocate.bits.control.poke(false.B)
    dut.io.allocate.bits.opcode.poke(0.U)
    dut.io.allocate.bits.param.poke(0.U)
    dut.io.allocate.bits.size.poke(blockLgSize.U)
    dut.io.allocate.bits.source.poke(0.U)
    dut.io.allocate.bits.tag.poke(0.U)
    dut.io.allocate.bits.offset.poke(0.U)
    dut.io.allocate.bits.put.poke(0.U)
    dut.io.allocate.bits.set.poke(0.U)
    dut.io.allocate.bits.cryptoLine.poke(false.B)
    dut.io.allocate.bits.repeat.poke(false.B)

    dut.io.directory.valid.poke(false.B)
    dut.io.directory.bits.dirty.poke(false.B)
    dut.io.directory.bits.state.poke(INVALID)
    dut.io.directory.bits.clients.poke(0.U)
    dut.io.directory.bits.tag.poke(0.U)
    dut.io.directory.bits.cryptoLine.poke(false.B)
    dut.io.directory.bits.counterValid.poke(false.B)
    dut.io.directory.bits.hit.poke(false.B)
    dut.io.directory.bits.way.poke(0.U)

    dut.io.schedule.ready.poke(true.B)
    dut.io.sinkc.valid.poke(false.B)
    dut.io.sinkd.valid.poke(false.B)
    dut.io.sinke.valid.poke(false.B)
    dut.io.nestedwb.set.poke(0.U)
    dut.io.nestedwb.tag.poke(0.U)
    dut.io.nestedwb.b_toN.poke(false.B)
    dut.io.nestedwb.b_toB.poke(false.B)
    dut.io.nestedwb.b_clr_dirty.poke(false.B)
    dut.io.nestedwb.c_set_dirty.poke(false.B)
  }

  behavior of "MSHR repeat path and dual metadata"

  it should "keep schedule.d invalid when data grant arrives before the counter fetch response" in {
    test(new MSHRObservationHarness(testParams)) { dut =>
      // Initial state target: a crypto refill request exists, but its counter fetch has not completed yet.
      clearObservedMSHR(dut)
      dut.clock.step()

      // Initialization transaction sequence: miss allocate, issue data acquire first, then let normal grant beat arrive before counter grant.
      driveCounterAllocate(dut, tag = 6, offset = 0)
      driveInvalidDirectory(dut)
      dut.clock.step()
      dut.io.allocate.valid.poke(false.B)
      dut.io.directory.valid.poke(false.B)

      // The first scheduled A must be the normal data acquire, not a counter request.
      dut.io.schedule.valid.expect(true.B)
      dut.io.schedule.bits.a.valid.expect(true.B)
      dut.io.schedule.bits.a.bits.isCounter.expect(false.B)
      dut.clock.step()

      // After the data acquire has gone out, the next A should be the counter get.
      dut.io.schedule.valid.expect(true.B)
      dut.io.schedule.bits.a.valid.expect(true.B)
      dut.io.schedule.bits.a.bits.isCounter.expect(true.B)

      driveSinkDResp(dut, GrantData, isCounter = false, last = true)
      dut.clock.step()
      dut.io.sinkd.valid.poke(false.B)

      // Consumer under test: MSHR->Scheduler D gating before counter fetch is done.
      dut.io.schedule.bits.d.valid.expect(false.B)

      // Internal assertion: refillMeta has not yet marked the counter as fetched.
      dut.io.refillMetaCounterValid.expect(false.B)

      // Once the counter AccessAckData arrives, D may become visible.
      driveSinkDResp(dut, AccessAckData, isCounter = true, last = true)
      dut.clock.step()
      dut.io.sinkd.valid.poke(false.B)

      // External assertion: schedule.d is only released after counter fetch completes.
      dut.io.schedule.bits.d.valid.expect(true.B)
      dut.io.schedule.bits.d.bits.cryptoLine.expect(true.B)

      // Internal assertion: the refill side now carries a valid crypto counter view.
      dut.io.refillMetaCounterValid.expect(true.B)
      dut.io.refillMetaCryptoLine.expect(true.B)
    }
  }

  it should "keep schedule.d blocked until the last serial grant beat even when the counter fetch returns early" in {
    test(new MSHRObservationHarness(testParams)) { dut =>
      // Initial state target: a crypto refill request needs a counter fetch, and the data grant will arrive serially over multiple beats.
      clearObservedMSHR(dut)
      dut.clock.step()

      // Initialization transaction sequence: miss allocate with non-zero offset to force serial grant completion.
      driveCounterAllocate(dut, tag = 7, offset = testParams.cache.beatBytes)
      driveInvalidDirectory(dut)
      dut.clock.step()
      dut.io.allocate.valid.poke(false.B)
      dut.io.directory.valid.poke(false.B)

      // First schedule slot sends the normal data acquire.
      dut.io.schedule.valid.expect(true.B)
      dut.io.schedule.bits.a.valid.expect(true.B)
      dut.io.schedule.bits.a.bits.isCounter.expect(false.B)
      dut.clock.step()

      // Second schedule slot sends the counter get.
      dut.io.schedule.valid.expect(true.B)
      dut.io.schedule.bits.a.valid.expect(true.B)
      dut.io.schedule.bits.a.bits.isCounter.expect(true.B)
      dut.clock.step()

      // Counter grant returns before the data grant beats.
      driveSinkDResp(dut, AccessAckData, isCounter = true, last = true)
      dut.clock.step()
      dut.io.sinkd.valid.poke(false.B)

      // Internal assertion: counter side is already marked valid, but D must still wait for data grant completion.
      dut.io.refillMetaCounterValid.expect(true.B)
      dut.io.schedule.bits.d.valid.expect(false.B)

      // First serial grant beat arrives, but last=false keeps the data grant incomplete.
      driveSinkDResp(dut, GrantData, isCounter = false, last = false)
      dut.clock.step()
      dut.io.sinkd.valid.poke(false.B)

      dut.io.schedule.bits.d.valid.expect(false.B)

      // Final grant beat closes the data side.
      driveSinkDResp(dut, GrantData, isCounter = false, last = true)
      dut.clock.step()
      dut.io.sinkd.valid.poke(false.B)

      // External assertion: D only becomes schedulable after the last serial data grant beat.
      dut.io.schedule.bits.d.valid.expect(true.B)
      dut.io.schedule.bits.d.bits.cryptoLine.expect(true.B)

      // Internal assertion: the refill side keeps the early counter fetch metadata through the serial data window.
      dut.io.refillMetaCounterValid.expect(true.B)
      dut.io.refillMetaCryptoLine.expect(true.B)
    }
  }

  it should "split victim and refill metadata on same-tag mode mismatch" in {
    test(new MSHRObservationHarness(testParams)) { dut =>
      // Initial state target: resident victim is crypto, incoming refill request is plain same-tag.
      clearObservedMSHR(dut)
      dut.clock.step()

      // Initialization transaction sequence: memory -> L2 resident metadata, then same-tag opposite-mode allocate.
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.bits.prio.foreach(_.poke(false.B))
      dut.io.allocate.bits.prio(0).poke(true.B)
      dut.io.allocate.bits.control.poke(false.B)
      dut.io.allocate.bits.opcode.poke(AcquireBlock)
      dut.io.allocate.bits.param.poke(NtoT)
      dut.io.allocate.bits.size.poke(blockLgSize.U)
      dut.io.allocate.bits.source.poke(0.U)
      dut.io.allocate.bits.tag.poke(1.U)
      dut.io.allocate.bits.offset.poke(0.U)
      dut.io.allocate.bits.put.poke(0.U)
      dut.io.allocate.bits.set.poke(0.U)
      dut.io.allocate.bits.cryptoLine.poke(false.B)
      dut.io.allocate.bits.repeat.poke(false.B)

      dut.io.directory.valid.poke(true.B)
      dut.io.directory.bits.dirty.poke(true.B)
      dut.io.directory.bits.state.poke(TIP)
      dut.io.directory.bits.clients.poke(0.U)
      dut.io.directory.bits.tag.poke(1.U)
      dut.io.directory.bits.cryptoLine.poke(true.B)
      dut.io.directory.bits.counterValid.poke(true.B)
      dut.io.directory.bits.hit.poke(true.B)
      dut.io.directory.bits.way.poke(1.U)
      dut.clock.step()

      dut.io.allocate.valid.poke(false.B)
      dut.io.directory.valid.poke(false.B)

      // Consumer under test: MSHR metadata planner.
      dut.io.status.valid.expect(true.B)

      // External assertion: refill plan switches to the new plain-line semantics.
      dut.io.schedule.valid.expect(true.B)
      dut.io.schedule.bits.d.valid.expect(true.B)
      dut.io.schedule.bits.d.bits.cryptoLine.expect(false.B)

      // Internal assertion: victim/refill metadata are split rather than reused as one view.
      dut.io.victimMetaCryptoLine.expect(true.B)
      dut.io.refillMetaCryptoLine.expect(false.B)
    }
  }

  it should "reuse the previous meta result on a same-tag same-mode repeat reload" in {
    test(new MSHRObservationHarness(testParams)) { dut =>
      // Initial state target: one MSHR already computed final_meta_writeback for a crypto line.
      clearObservedMSHR(dut)
      dut.clock.step()

      // Initialization transaction sequence: first request establishes final_meta_writeback, second request repeats same tag+mode.
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.bits.prio.foreach(_.poke(false.B))
      dut.io.allocate.bits.prio(2).poke(true.B)
      dut.io.allocate.bits.control.poke(false.B)
      dut.io.allocate.bits.opcode.poke(ReleaseData)
      dut.io.allocate.bits.param.poke(TtoT)
      dut.io.allocate.bits.size.poke(blockLgSize.U)
      dut.io.allocate.bits.source.poke(0.U)
      dut.io.allocate.bits.tag.poke(3.U)
      dut.io.allocate.bits.offset.poke(0.U)
      dut.io.allocate.bits.put.poke(0.U)
      dut.io.allocate.bits.set.poke(0.U)
      dut.io.allocate.bits.cryptoLine.poke(true.B)
      dut.io.allocate.bits.repeat.poke(false.B)

      dut.io.directory.valid.poke(true.B)
      dut.io.directory.bits.dirty.poke(true.B)
      dut.io.directory.bits.state.poke(TIP)
      dut.io.directory.bits.clients.poke(0.U)
      dut.io.directory.bits.tag.poke(3.U)
      dut.io.directory.bits.cryptoLine.poke(true.B)
      dut.io.directory.bits.counterValid.poke(true.B)
      dut.io.directory.bits.hit.poke(true.B)
      dut.io.directory.bits.way.poke(1.U)
      dut.clock.step()

      dut.io.allocate.valid.poke(false.B)
      dut.io.directory.valid.poke(false.B)
      dut.io.status.valid.expect(true.B)
      dut.io.status.bits.reload_base_crypto_line.expect(true.B)
      dut.clock.step()

      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.bits.prio.foreach(_.poke(false.B))
      dut.io.allocate.bits.prio(2).poke(true.B)
      dut.io.allocate.bits.control.poke(false.B)
      dut.io.allocate.bits.opcode.poke(ReleaseData)
      dut.io.allocate.bits.param.poke(TtoT)
      dut.io.allocate.bits.size.poke(blockLgSize.U)
      dut.io.allocate.bits.source.poke(1.U)
      dut.io.allocate.bits.tag.poke(3.U)
      dut.io.allocate.bits.offset.poke(0.U)
      dut.io.allocate.bits.put.poke(0.U)
      dut.io.allocate.bits.set.poke(0.U)
      dut.io.allocate.bits.cryptoLine.poke(true.B)
      dut.io.allocate.bits.repeat.poke(true.B)
      dut.clock.step()

      dut.io.allocate.valid.poke(false.B)

      // Consumer under test: repeat = true fast path.
      dut.io.status.valid.expect(true.B)

      // External assertion: scheduler reloads directly on repeat path.
      dut.io.schedule.valid.expect(true.B)
      dut.io.schedule.bits.reload.expect(true.B)
      dut.io.schedule.bits.d.valid.expect(true.B)
      dut.io.schedule.bits.d.bits.cryptoLine.expect(true.B)

      // Internal assertion: repeat path reuses the already-computed final metadata.
      dut.io.finalMetaCryptoLine.expect(true.B)
      dut.io.refillMetaCryptoLine.expect(true.B)
      dut.io.newSameTagModeMismatch.expect(false.B)
    }
  }

  it should "separate victim and refill metadata on a miss with a valid victim" in {
    test(new MSHRObservationHarness(testParams)) { dut =>
      // Initial state target: a resident dirty crypto victim exists, and the new request misses on a different tag.
      clearObservedMSHR(dut)
      dut.clock.step()

      // Initialization transaction sequence: allocate a miss against a resident valid victim.
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.bits.prio.foreach(_.poke(false.B))
      dut.io.allocate.bits.prio(0).poke(true.B)
      dut.io.allocate.bits.control.poke(false.B)
      dut.io.allocate.bits.opcode.poke(AcquireBlock)
      dut.io.allocate.bits.param.poke(NtoB)
      dut.io.allocate.bits.size.poke(blockLgSize.U)
      dut.io.allocate.bits.source.poke(0.U)
      dut.io.allocate.bits.tag.poke(2.U)
      dut.io.allocate.bits.offset.poke(0.U)
      dut.io.allocate.bits.put.poke(0.U)
      dut.io.allocate.bits.set.poke(0.U)
      dut.io.allocate.bits.cryptoLine.poke(false.B)
      dut.io.allocate.bits.repeat.poke(false.B)

      dut.io.directory.valid.poke(true.B)
      dut.io.directory.bits.dirty.poke(true.B)
      dut.io.directory.bits.state.poke(TIP)
      dut.io.directory.bits.clients.poke(0.U)
      dut.io.directory.bits.tag.poke(1.U)
      dut.io.directory.bits.cryptoLine.poke(true.B)
      dut.io.directory.bits.counterValid.poke(true.B)
      dut.io.directory.bits.hit.poke(false.B)
      dut.io.directory.bits.way.poke(1.U)
      dut.clock.step()

      dut.io.allocate.valid.poke(false.B)
      dut.io.directory.valid.poke(false.B)

      // Consumer under test: miss planning through MSHR dual metadata.
      dut.io.status.valid.expect(true.B)

      // External assertion: the MSHR produced an execution plan for the miss.
      dut.io.schedule.valid.expect(true.B)

      // Internal assertion: victim side still reflects the resident crypto victim and still needs counter put.
      dut.io.victimMetaTag.expect(1.U)
      dut.io.victimMetaCryptoLine.expect(true.B)
      dut.io.refillMetaTag.expect(2.U)
      dut.io.refillMetaCryptoLine.expect(false.B)
      dut.io.needCounterPut.expect(true.B)
    }
  }

  it should "keep victim and refill metadata separated when nested SinkC updates the victim line" in {
    test(new MSHRObservationHarness(testParams)) { dut =>
      // Initial state target: same-tag mode mismatch is active, then nested SinkC updates the victim side.
      clearObservedMSHR(dut)
      dut.clock.step()

      // Initialization transaction sequence: build an active same-tag mode-mismatch plan.
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.bits.prio.foreach(_.poke(false.B))
      dut.io.allocate.bits.prio(0).poke(true.B)
      dut.io.allocate.bits.control.poke(false.B)
      dut.io.allocate.bits.opcode.poke(AcquireBlock)
      dut.io.allocate.bits.param.poke(NtoT)
      dut.io.allocate.bits.size.poke(blockLgSize.U)
      dut.io.allocate.bits.source.poke(0.U)
      dut.io.allocate.bits.tag.poke(4.U)
      dut.io.allocate.bits.offset.poke(0.U)
      dut.io.allocate.bits.put.poke(0.U)
      dut.io.allocate.bits.set.poke(0.U)
      dut.io.allocate.bits.cryptoLine.poke(false.B)
      dut.io.allocate.bits.repeat.poke(false.B)

      dut.io.directory.valid.poke(true.B)
      dut.io.directory.bits.dirty.poke(true.B)
      dut.io.directory.bits.state.poke(TIP)
      dut.io.directory.bits.clients.poke(1.U)
      dut.io.directory.bits.tag.poke(4.U)
      dut.io.directory.bits.cryptoLine.poke(true.B)
      dut.io.directory.bits.counterValid.poke(true.B)
      dut.io.directory.bits.hit.poke(true.B)
      dut.io.directory.bits.way.poke(1.U)
      dut.clock.step()

      dut.io.allocate.valid.poke(false.B)
      dut.io.directory.valid.poke(false.B)

      // Inject a nested SinkC response for the victim line.
      dut.io.sinkc.valid.poke(true.B)
      dut.io.sinkc.bits.last.poke(true.B)
      dut.io.sinkc.bits.set.poke(0.U)
      dut.io.sinkc.bits.tag.poke(4.U)
      dut.io.sinkc.bits.source.poke(0.U)
      dut.io.sinkc.bits.param.poke(TtoT)
      dut.io.sinkc.bits.data.poke(true.B)
      dut.io.sinkc.bits.cryptoLine.poke(true.B)
      dut.io.sinkc.bits.counter.poke(newCounter.U)
      dut.clock.step()
      dut.io.sinkc.valid.poke(false.B)

      // Consumer under test: victim-side nested SinkC update while refill side stays on request semantics.
      dut.io.status.valid.expect(true.B)

      // External assertion: refill-visible plan still targets the request's plain mode.
      dut.io.schedule.bits.d.bits.cryptoLine.expect(false.B)

      // Internal assertion: nested SinkC updates victim-side metadata without collapsing victim/refill split.
      dut.io.victimMetaTag.expect(4.U)
      dut.io.victimMetaCryptoLine.expect(true.B)
      dut.io.refillMetaTag.expect(4.U)
      dut.io.refillMetaCryptoLine.expect(false.B)
    }
  }
}
