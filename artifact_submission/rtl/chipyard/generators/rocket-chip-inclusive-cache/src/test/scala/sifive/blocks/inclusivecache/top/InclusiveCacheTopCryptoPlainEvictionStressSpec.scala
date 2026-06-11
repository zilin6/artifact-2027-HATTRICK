package sifive.blocks.inclusivecache.top

import chisel3._
import chiseltest._
import chiseltest.VerilatorBackendAnnotation
import freechips.rocketchip.tilelink.TLPermissions._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

import InclusiveCacheTopHarness._
import InclusiveCacheTopTestUtils._

class InclusiveCacheTopCryptoPlainEvictionStressSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  implicit val p: Parameters = InclusiveCacheTopHarness.p

  private val stressCache = InclusiveCacheTopHarness.fastCache.copy(ways = 2, sets = 8)
  private val stressMicro = InclusiveCacheTopHarness.fastMicro
  private val blockBytes = stressCache.blockBytes
  private val blockOffsetBits = Integer.numberOfTrailingZeros(blockBytes)
  private val setStride = stressCache.sets * blockBytes
  private val beatCount = blockBytes / stressCache.beatBytes
  private val counterBase = BigInt("100000", 16)
  private val sameSet = 0
  private val lineCount = 6
  private val rounds = 3

  private case class LineState(beats: Seq[BigInt], counter: BigInt)

  private def lineAddress(tag: Int): BigInt =
    BigInt(tag) * setStride + BigInt(sameSet) * blockBytes

  private def counterAddrForLine(address: BigInt): BigInt =
    counterBase + ((address >> blockOffsetBits) << 3)

  private def initialLineState(tag: Int): LineState = {
    val beats = Seq.tabulate(beatCount) { beat =>
      (BigInt("40", 16) + tag) << 56 | BigInt(beat + 1)
    }
    val counter = BigInt("abc000", 16) + tag
    LineState(beats, counter)
  }

  private def mutatedLineState(tag: Int, version: Int, prevCounter: BigInt, crypto: Boolean): LineState = {
    val beats = Seq.tabulate(beatCount) { beat =>
      (BigInt("70", 16) + version) << 56 | (BigInt(tag) << 16) | BigInt(beat + 1)
    }
    val nextCounter = if (crypto) prevCounter + 1 else prevCounter
    LineState(beats, nextCounter)
  }

  private def fillDirtySet(
    dut: InclusiveCacheTopHarnessWrapper,
    residentTags: Seq[Int],
    shadow: mutable.Map[BigInt, LineState],
    crypto: Boolean): mutable.LinkedHashSet[BigInt] = {
    val resident = mutable.LinkedHashSet.empty[BigInt]
    residentTags.zipWithIndex.foreach { case (tag, source) =>
      val addr = lineAddress(tag)
      val state = shadow(addr)
      sendInnerAcquireBlock(dut, address = addr, source = source % 4, cryptoLine = crypto)
      waitOuterAcquireBlock(dut, maxCycles = 10000) shouldBe addr
      val outerSource = dut.io.outer.a.bits.source.peek().litValue
      val grantResult = respondOuterGrantDataAndCollectInnerGrantResult(
        dut,
        source = outerSource,
        sink = 0,
        beats = state.beats,
        counter = if (crypto) state.counter else 0,
        cryptoLine = crypto)
      grantResult.beats shouldBe state.beats
      sendInnerGrantAck(dut, grantResult.sink)

      val sawAck = sendInnerReleaseData(
        dut,
        address = addr,
        beats = state.beats,
        source = source % 4,
        counter = if (crypto) state.counter else 0,
        cryptoLine = crypto,
        param = TtoN.litValue)
      if (!sawAck) dut.clock.step(256)
      dut.clock.step(64)
      resident += addr
    }
    resident
  }

  private def handleOverflowIfAny(
    dut: InclusiveCacheTopHarnessWrapper,
    resident: mutable.LinkedHashSet[BigInt],
    shadow: mutable.Map[BigInt, LineState],
    crypto: Boolean): Unit = {
    if (resident.size < stressCache.ways) return

    if (crypto) {
      val eviction = waitOuterEviction(dut)
      val prev = shadow.getOrElse(eviction.release.address,
        fail(s"Observed crypto eviction for unexpected address 0x${eviction.release.address.toString(16)}"))
      shadow(eviction.release.address) = prev.copy(
        beats = eviction.release.beats,
        counter = eviction.counterPut.data)
      eviction.counterPut.address shouldBe counterAddrForLine(eviction.release.address)
      resident -= eviction.release.address
    } else {
      val release = waitOuterReleaseData(dut)
      val prev = shadow.getOrElse(release.address,
        fail(s"Observed plain eviction for unexpected address 0x${release.address.toString(16)}"))
      shadow(release.address) = prev.copy(beats = release.beats)
      assertNoOuterCounterPut(dut, maxCycles = 32)
      resident -= release.address
    }
  }

  private def acquireAndObserve(
    dut: InclusiveCacheTopHarnessWrapper,
    address: BigInt,
    source: Int,
    shadow: mutable.Map[BigInt, LineState],
    resident: mutable.LinkedHashSet[BigInt],
    crypto: Boolean,
    observations: mutable.ArrayBuffer[Seq[BigInt]]): Unit = {
    sendInnerAcquireBlock(dut, address = address, source = source, cryptoLine = crypto)
    if (!resident.contains(address)) {
      handleOverflowIfAny(dut, resident, shadow, crypto)
    }

    waitOuterAcquireBlock(dut, maxCycles = 10000) shouldBe address
    val outerSource = dut.io.outer.a.bits.source.peek().litValue
    val state = shadow(address)
    val grantResult = respondOuterGrantDataAndCollectInnerGrantResult(
      dut,
      source = outerSource,
      sink = 0,
      beats = state.beats,
      counter = if (crypto) state.counter else 0,
      cryptoLine = crypto)
    grantResult.beats shouldBe state.beats
    observations += grantResult.beats
    sendInnerGrantAck(dut, grantResult.sink)
    dut.clock.step(64)
    resident += address
  }

  private def releaseMutated(
    dut: InclusiveCacheTopHarnessWrapper,
    address: BigInt,
    source: Int,
    state: LineState,
    crypto: Boolean): Unit = {
    val sawAck = sendInnerReleaseData(
      dut,
      address = address,
      beats = state.beats,
      source = source,
      counter = if (crypto) state.counter else 0,
      cryptoLine = crypto,
      param = TtoN.litValue)
    if (!sawAck) dut.clock.step(256)
    dut.clock.step(8)
  }

  private def runScenario(crypto: Boolean): Seq[Seq[BigInt]] = {
    val observations = mutable.ArrayBuffer.empty[Seq[BigInt]]
    val shadow = mutable.Map((0 until lineCount).map(tag => lineAddress(tag) -> initialLineState(tag)): _*)

    test(new InclusiveCacheTopHarnessWrapper(cache = stressCache, micro = stressMicro))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      clearTop(dut)
      programCounterBase(dut, counterBase)
      dut.clock.step(stressCache.sets + 8)

      val resident = fillDirtySet(dut, Seq(0, 1), shadow, crypto)

      var version = 0
      for (round <- 0 until rounds) {
        val hotTags = Seq(0, 1, 2, 3, 4, 5).map(t => (t + round) % lineCount)
        hotTags.zipWithIndex.foreach { case (tag, idx) =>
          val addr = lineAddress(tag)
          acquireAndObserve(dut, addr, source = idx % 4, shadow, resident, crypto, observations)

          version += 1
          val prev = shadow(addr)
          val mutated = mutatedLineState(tag, version, prev.counter, crypto)
          shadow(addr) = mutated
          releaseMutated(dut, addr, source = idx % 4, mutated, crypto)
        }
      }
    }

    observations.toSeq
  }

  behavior of "top-level InclusiveCache crypto/plain paired eviction stress"

  it should "match plain data results for every crypto refill under repeated same-set dirty evictions" in {
    val plainObs = runScenario(crypto = false)
    val cryptoObs = runScenario(crypto = true)

    cryptoObs should have size plainObs.size.toLong
    cryptoObs.zip(plainObs).zipWithIndex.foreach { case ((cryptoBeats, plainBeats), idx) =>
      withClue(s"grant-beat mismatch at observation $idx") {
        cryptoBeats shouldBe plainBeats
      }
    }

    println(s"[CryptoPlainEvictionStress] compared ${cryptoObs.size} paired refill observations")
  }
}
