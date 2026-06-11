package sifive.blocks.inclusivecache

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import MetaData._
import InclusiveCacheCounterTestUtils._

class DirectoryModeMismatchSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Directory mode-mismatch lookup"

  it should "pick the resident same-tag way for rebuild when crypto mode mismatches" in {
    test(new Directory(testParams)) { dut =>
      // Initial state target: a resident crypto line exists for the requested tag.
      clearDirectory(dut)
      while (!dut.io.ready.peek().litToBoolean) {
        dut.clock.step()
      }

      // Initialization transaction sequence: memory -> L2 resident metadata, then same-tag plain lookup.
      dut.io.write.valid.poke(true.B)
      dut.io.write.bits.set.poke(0.U)
      dut.io.write.bits.way.poke(1.U)
      dut.io.write.bits.data.dirty.poke(true.B)
      dut.io.write.bits.data.state.poke(TIP)
      dut.io.write.bits.data.clients.poke(0.U)
      dut.io.write.bits.data.tag.poke(1.U)
      dut.io.write.bits.data.cryptoLine.poke(true.B)
      dut.io.write.bits.data.counterValid.poke(true.B)
      dut.clock.step()
      dut.io.write.valid.poke(false.B)
      dut.clock.step()

      dut.io.read.valid.poke(true.B)
      dut.io.read.bits.set.poke(0.U)
      dut.io.read.bits.tag.poke(1.U)
      dut.io.read.bits.cryptoLine.poke(false.B)
      dut.clock.step()
      dut.io.read.valid.poke(false.B)

      // Consumer under test: directory lookup for same-tag, opposite-mode request.
      var seen = false
      for (_ <- 0 until 4 if !seen) {
        if (dut.io.result.valid.peek().litToBoolean) {
          // External assertion: lookup returns the resident same-tag way for rebuild.
          dut.io.result.bits.hit.expect(false.B)
          dut.io.result.bits.way.expect(1.U)
          dut.io.result.bits.tag.expect(1.U)

          // Internal assertion: resident victim metadata keeps the original crypto/counter state.
          dut.io.result.bits.cryptoLine.expect(true.B)
          dut.io.result.bits.counterValid.expect(true.B)
          seen = true
        }
        dut.clock.step()
      }

      seen shouldBe true
    }
  }

  it should "report a normal hit when tag and crypto mode match" in {
    test(new Directory(testParams)) { dut =>
      // Initial state target: resident metadata exactly matches the incoming lookup mode.
      clearDirectory(dut)
      while (!dut.io.ready.peek().litToBoolean) {
        dut.clock.step()
      }

      // Initialization transaction sequence: memory -> L2 resident metadata, then same-tag same-mode lookup.
      dut.io.write.valid.poke(true.B)
      dut.io.write.bits.set.poke(0.U)
      dut.io.write.bits.way.poke(1.U)
      dut.io.write.bits.data.dirty.poke(true.B)
      dut.io.write.bits.data.state.poke(TIP)
      dut.io.write.bits.data.clients.poke(0.U)
      dut.io.write.bits.data.tag.poke(2.U)
      dut.io.write.bits.data.cryptoLine.poke(true.B)
      dut.io.write.bits.data.counterValid.poke(true.B)
      dut.clock.step()
      dut.io.write.valid.poke(false.B)
      dut.clock.step()

      dut.io.read.valid.poke(true.B)
      dut.io.read.bits.set.poke(0.U)
      dut.io.read.bits.tag.poke(2.U)
      dut.io.read.bits.cryptoLine.poke(true.B)
      dut.clock.step()
      dut.io.read.valid.poke(false.B)

      // Consumer under test: directory lookup for exact resident mode.
      var seen = false
      for (_ <- 0 until 4 if !seen) {
        if (dut.io.result.valid.peek().litToBoolean) {
          // External assertion: a normal hit is reported.
          dut.io.result.bits.hit.expect(true.B)
          dut.io.result.bits.way.expect(1.U)
          dut.io.result.bits.tag.expect(2.U)

          // Internal assertion: hit metadata preserves the resident crypto mode.
          dut.io.result.bits.cryptoLine.expect(true.B)
          seen = true
        }
        dut.clock.step()
      }

      seen shouldBe true
    }
  }
}
