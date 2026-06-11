package sifive.blocks.inclusivecache.top

import chisel3._
import chiseltest._
import chiseltest.VerilatorBackendAnnotation
import freechips.rocketchip.tilelink.TLPermissions._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheTopHarness._
import InclusiveCacheTopTestUtils._

class InclusiveCacheTopSinkCPlainEvictionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  implicit val p: Parameters = InclusiveCacheTopHarness.p

  private val evictCache = InclusiveCacheTopHarness.fastCache.copy(ways = 2)
  private val evictMicro = InclusiveCacheTopHarness.fastMicro
  private val blockBytes = evictCache.blockBytes
  private val blockOffsetBits = Integer.numberOfTrailingZeros(blockBytes)
  private val setStride = evictCache.sets * blockBytes
  private val beatCount = blockBytes / evictCache.beatBytes
  private val counterBase = BigInt("100000", 16)

  private def lineAddress(tag: Int, set: Int = 0): BigInt = BigInt(tag) * setStride + BigInt(set) * blockBytes

  private def lineBeats(tag: Int): Seq[BigInt] =
    Seq.tabulate(beatCount) { beat =>
      (BigInt(tag + 1) << 56) | BigInt(beat + 1)
    }

  private def refillLineBeats(tag: Int): Seq[BigInt] =
    Seq.tabulate(beatCount) { beat =>
      (BigInt("40", 16) + tag) << 56 | BigInt(beat + 1)
    }

  behavior of "top-level InclusiveCache plain SinkC eviction path"

  it should "evict a plain SinkC-written-back line without any counter writeback when a later miss overfills the set" in {
    test(new InclusiveCacheTopHarnessWrapper(
      cache = evictCache,
      micro = evictMicro))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearTop(dut)
      programCounterBase(dut, counterBase)
      dut.clock.step(evictCache.sets + 8)

      val resident = (0 until evictCache.ways).map { tag =>
        val addr = lineAddress(tag)
        val refill = refillLineBeats(tag)
        val dirty = lineBeats(tag)
        addr -> (refill, dirty)
      }.toMap

      // First fill the whole set through the normal SinkA/refill path.
      resident.toSeq.sortBy(_._1).zipWithIndex.foreach { case ((addr, (refillBeats, _)), idx) =>
        val grantResult = completeBoomLikeAcquireBlockMiss(
          dut,
          address = addr,
          source = idx % 4,
          beats = refillBeats,
          counter = 0,
          cryptoLine = false)
        grantResult.beats shouldBe refillBeats
      }

      // Then write back each resident line as a plain ReleaseData that drops L1 ownership.
      resident.toSeq.sortBy(_._1).zipWithIndex.foreach { case ((addr, (_, dirtyBeats)), idx) =>
        val sawAck = sendInnerReleaseData(
          dut,
          address = addr,
          beats = dirtyBeats,
          source = idx % 4,
          counter = 0,
          cryptoLine = false,
          param = TtoN.litValue)
        if (!sawAck) {
          waitInnerReleaseAck(dut, maxCycles = 2000)
        }
        dut.clock.step(8)
      }

      val overflowAddr = lineAddress(evictCache.ways)
      sendInnerAcquireBlock(
        dut,
        address = overflowAddr,
        source = 0,
        cryptoLine = false)

      val release = waitOuterReleaseData(dut)
      val expectedDirtyBeats =
        resident.getOrElse(release.address, fail(s"Observed release for unexpected address 0x${release.address.toString(16)}"))._2

      release.beats shouldBe expectedDirtyBeats
      assertNoOuterCounterPut(dut, maxCycles = 64)
    }
  }
}
