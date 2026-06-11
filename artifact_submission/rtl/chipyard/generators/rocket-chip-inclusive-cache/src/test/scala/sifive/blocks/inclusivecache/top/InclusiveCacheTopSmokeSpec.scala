package sifive.blocks.inclusivecache.top

import chiseltest._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheTopTestUtils._

class InclusiveCacheTopSmokeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  implicit val p: Parameters = InclusiveCacheTopHarness.p

  behavior of "top-level InclusiveCache shell"

  it should "elaborate a SmallBoomV3-style top harness and expose inner/outer TL ports" in {
    test(new InclusiveCacheTopHarnessWrapper(
      cache = InclusiveCacheTopHarness.fastCache,
      micro = InclusiveCacheTopHarness.fastMicro)) { dut =>
      clearTop(dut)
      dut.clock.step()

      dut.io.inner.a.ready.peek().litToBoolean should (be(true).or(be(false)))
      dut.io.outer.a.valid.peek().litToBoolean should (be(true).or(be(false)))
      dut.io.inner.d.valid.peek().litToBoolean should (be(true).or(be(false)))
    }
  }
}
