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

class InclusiveCacheTopSinkCEvictionSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
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

  behavior of "top-level InclusiveCache SinkC eviction path"

  it should "evict a SinkC-dirtied crypto line and write back its matching counter when a later miss overfills the set" in {
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
        val counter = BigInt("abc000", 16) + tag
        addr -> (refill, dirty, counter)
      }.toMap

      resident.toSeq.sortBy(_._1).zipWithIndex.foreach { case ((addr, (refillBeats, dirtyBeats, counter)), idx) =>
        sendInnerAcquireBlock(
          dut,
          address = addr,
          source = idx % 4,
          cryptoLine = false)
        waitOuterAcquireBlock(dut, maxCycles = 2000) shouldBe addr
        val outerSource = dut.io.outer.a.bits.source.peek().litValue
        val grantResult = respondOuterGrantDataAndCollectInnerGrantResult(
          dut,
          source = outerSource,
          sink = 0,
          beats = refillBeats,
          counter = 0,
          cryptoLine = false)
        grantResult.beats shouldBe refillBeats
        sendInnerGrantAck(dut, grantResult.sink)

        val sawAck = sendInnerReleaseData(
          dut,
          address = addr,
          beats = dirtyBeats,
          source = idx % 4,
          counter = counter,
          cryptoLine = true,
          param = TtoT.litValue)
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

      val eviction = waitOuterEviction(dut)
      val evictedAddr = eviction.release.address
      val (_, expectedDirtyBeats, expectedCounter) =
        resident.getOrElse(evictedAddr, fail(s"Observed eviction for unexpected address 0x${evictedAddr.toString(16)}"))
      val expectedCounterAddr = counterBase + ((evictedAddr >> blockOffsetBits) << 3)

      eviction.release.beats shouldBe expectedDirtyBeats
      eviction.counterPut.address shouldBe expectedCounterAddr
      eviction.counterPut.data shouldBe expectedCounter
    }
  }
}
