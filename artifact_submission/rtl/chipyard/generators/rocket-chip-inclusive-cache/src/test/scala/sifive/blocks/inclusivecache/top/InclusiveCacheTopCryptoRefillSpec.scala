package sifive.blocks.inclusivecache.top

import chisel3._
import chiseltest._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheTopHarness._
import InclusiveCacheTopTestUtils._

class InclusiveCacheTopCryptoRefillSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  implicit val p: Parameters = InclusiveCacheTopHarness.p

  behavior of "top-level InclusiveCache crypto refill path"

  it should "propagate cryptoLine=1 refill counter metadata to inner GrantData beats" in {
    test(new InclusiveCacheTopHarnessWrapper(
      cache = InclusiveCacheTopHarness.fastCache,
      micro = InclusiveCacheTopHarness.fastMicro)) { dut =>
      clearTop(dut)
      dut.clock.step(InclusiveCacheTopHarness.fastCache.sets + 8)

      sendInnerAcquireBlock(dut, address = 0x0, source = 0, cryptoLine = true)

      val outerAddress = waitOuterAcquireBlock(dut)
      outerAddress shouldBe 0
      val outerSource = dut.io.outer.a.bits.source.peek().litValue

      respondOuterGrantData(
        dut,
        source = outerSource,
        sink = 0,
        beats = refillBeats,
        counter = refillCounter,
        cryptoLine = true)
      val firstGrantBeat = waitFirstInnerGrantMeta(dut, maxCycles = 40)

      firstGrantBeat.data shouldBe refillBeats.head
      firstGrantBeat.counter shouldBe refillCounter
      firstGrantBeat.cryptoLine shouldBe true
    }
  }
}
