package sifive.blocks.inclusivecache

import chisel3._
import chiseltest._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheCounterTestUtils._

class SourceASnapshotSendSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "SourceA snapshot send path"

  it should "send the frozen snapshot directly on counter Put" in {
    test(new SourceASnapshotSendHarness(testParams)) { dut =>
      // Initial state target: SourceC has already frozen an old victim counter snapshot.
      dut.io.sourceAReq.valid.poke(false.B)
      dut.io.sourceAOut.ready.poke(true.B)
      dut.io.snapshotData.poke(oldCounter.U)
      dut.io.snapshotValid.poke(true.B)
      dut.clock.step()

      // Initialization transaction sequence: SourceA gets a counterPut request for the same MSHR.
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

      // Consumer under test: SourceA outer A sender.
      dut.io.sourceAOut.valid.expect(true.B)

      // External assertion: outer A payload is a counter Put carrying the frozen snapshot.
      dut.io.sourceAOut.bits.opcode.expect(PutFullData)
      dut.io.sourceAOut.bits.data.expect(oldCounter.U)

      // Internal assertion: SourceA drives the exact snapshot value and pops it after send.
      dut.io.observedCounterValue.expect(oldCounter.U)
      dut.io.snapshotPop.expect(true.B)
    }
  }

  it should "wait for snapshot_valid instead of re-reading a latest counter value" in {
    test(new SourceASnapshotSendHarness(testParams)) { dut =>
      // Initial state target: counterPut request arrives before SourceC marks the snapshot valid.
      dut.io.sourceAReq.valid.poke(false.B)
      dut.io.sourceAOut.ready.poke(true.B)
      dut.io.snapshotData.poke(oldCounter.U)
      dut.io.snapshotValid.poke(false.B)
      dut.clock.step()

      // Initialization transaction sequence: SourceA receives counterPut, snapshot becomes valid later.
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

      // Consumer under test: staged SourceA counterPut request.
      dut.io.sourceAOut.valid.expect(false.B)

      // External assertion: SourceA does not send until a snapshot is present.
      dut.io.snapshotPop.expect(false.B)

      // Internal assertion: the eventual send still uses the frozen snapshot path.
      dut.io.snapshotValid.poke(true.B)
      dut.io.sourceAOut.valid.expect(true.B)
      dut.io.sourceAOut.bits.opcode.expect(PutFullData)
      dut.io.sourceAOut.bits.data.expect(oldCounter.U)
      dut.io.snapshotPop.expect(true.B)
    }
  }

  it should "emit a counter Get immediately for lookup requests" in {
    test(new SourceA(testParams)) { dut =>
      // Initial state target: a direct outer counter lookup request.
      clearSourceA(dut)
      dut.clock.step()

      // Initialization transaction sequence: SourceA receives a counterGet request.
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.isCounter.poke(true.B)
      dut.io.req.bits.isCounterWrite.poke(false.B)
      dut.io.req.bits.source.poke(3.U)

      // Consumer under test: SourceA outer A sender.
      dut.io.a.valid.expect(true.B)

      // External assertion: request is encoded as a counter Get.
      dut.io.a.bits.opcode.expect(Get)
      dut.io.a.bits.source.expect(3.U)
      dut.io.a.bits.address.expect(0.U)

      // Internal assertion: no snapshot staging is consumed for lookup requests.
      dut.io.ctr_snapshot_pop.expect(false.B)
      dut.clock.step()
    }
  }

  it should "leave normal non-counter outer Acquire requests unchanged" in {
    test(new SourceA(testParams)) { dut =>
      // Initial state target: a normal non-counter outer A request.
      clearSourceA(dut)
      dut.clock.step()

      // Initialization transaction sequence: SourceA receives an AcquirePerm request.
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.block.poke(false.B)
      dut.io.req.bits.param.poke(NtoB)
      dut.io.req.bits.source.poke(1.U)

      // Consumer under test: SourceA outer A sender.
      dut.io.a.valid.expect(true.B)

      // External assertion: the normal outer Acquire encoding is preserved.
      dut.io.a.bits.opcode.expect(AcquirePerm)
      dut.io.a.bits.param.expect(NtoB)
      dut.io.a.bits.size.expect(blockLgSize.U)
      dut.io.a.bits.address.expect(0.U)

      // Internal assertion: this path never touches snapshot send state.
      dut.io.ctr_snapshot_pop.expect(false.B)
      dut.clock.step()
    }
  }
}
