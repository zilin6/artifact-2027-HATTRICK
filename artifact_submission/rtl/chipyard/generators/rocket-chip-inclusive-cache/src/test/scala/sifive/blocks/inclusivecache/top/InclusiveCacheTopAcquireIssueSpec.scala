package sifive.blocks.inclusivecache.top

import chisel3._
import chiseltest._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheTopTestUtils._

class InclusiveCacheTopAcquireIssueSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  implicit val p: Parameters = InclusiveCacheTopHarness.p

  behavior of "top-level InclusiveCache issue path"

  it should "raise inner.a.ready after reset-time directory wipe completes" in {
    test(new InclusiveCacheTopHarnessWrapper(
      cache = InclusiveCacheTopHarness.fastCache,
      micro = InclusiveCacheTopHarness.fastMicro)) { dut =>
      clearTop(dut)
      dut.clock.step(InclusiveCacheTopHarness.fastCache.sets + 8)

      dut.io.inner.a.ready.expect(true.B)
    }
  }

  it should "accept an inner AcquireBlock and eventually issue an outer AcquireBlock" in {
    test(new InclusiveCacheTopHarnessWrapper(
      cache = InclusiveCacheTopHarness.fastCache,
      micro = InclusiveCacheTopHarness.fastMicro)) { dut =>
      clearTop(dut)
      dut.clock.step(InclusiveCacheTopHarness.fastCache.sets + 8)

      sendInnerAcquireBlock(dut, address = 0x0, source = 0, cryptoLine = false)

      val outerAddress = waitOuterAcquireBlock(dut)
      outerAddress shouldBe 0
      dut.io.outer.a.bits.opcode.peek().litValue shouldBe freechips.rocketchip.tilelink.TLMessages.AcquireBlock.litValue
    }
  }
}
