package sifive.blocks.inclusivecache.top

import chisel3._
import chiseltest._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheTopHarness._
import InclusiveCacheTopTestUtils._

class InclusiveCacheTopMissRefillSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  implicit val p: Parameters = InclusiveCacheTopHarness.p

  behavior of "top-level InclusiveCache"

  it should "complete a minimal plain-line AcquireBlock miss -> GrantData -> inner GrantData refill loop" in {
    test(new InclusiveCacheTopHarnessWrapper(
      cache = InclusiveCacheTopHarness.fastCache,
      micro = InclusiveCacheTopHarness.fastMicro)) { dut =>
      clearTop(dut)
      dut.clock.step(InclusiveCacheTopHarness.fastCache.sets + 8)

      sendInnerAcquireBlock(dut, address = 0x0, source = 0, cryptoLine = false)

      val outerAddress = waitOuterAcquireBlock(dut)
      outerAddress shouldBe 0
      val outerSource = dut.io.outer.a.bits.source.peek().litValue

      val beats = respondOuterGrantDataAndCollectInnerGrantData(
        dut,
        source = outerSource,
        sink = 0,
        beats = refillBeats,
        counter = 0,
        cryptoLine = false)
      beats shouldBe refillBeats
    }
  }
}
