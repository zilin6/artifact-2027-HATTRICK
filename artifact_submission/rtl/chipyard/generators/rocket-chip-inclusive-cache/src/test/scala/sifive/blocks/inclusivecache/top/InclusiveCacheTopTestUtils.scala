package sifive.blocks.inclusivecache.top

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import freechips.rocketchip.rocket.{CacheCryptoRefillMeta, CacheCryptoWritebackMeta}
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._

object InclusiveCacheTopTestUtils {
  case class InnerGrantBeat(data: BigInt, counter: BigInt, cryptoLine: Boolean)
  case class InnerGrantResult(beats: Seq[BigInt], sink: BigInt)
  case class OuterCounterPut(address: BigInt, data: BigInt)
  case class OuterRelease(address: BigInt, beats: Seq[BigInt])
  case class OuterEviction(counterPut: OuterCounterPut, release: OuterRelease)

  val maxWaitCycles = 50
  val maxGrantWaitCycles = 400
  val boomLikeRetireCycles = 32
  val refillBeats = Seq(
    BigInt("deadbeefcafef001", 16),
    BigInt("deadbeefcafef002", 16),
    BigInt("deadbeefcafef003", 16),
    BigInt("deadbeefcafef004", 16),
    BigInt("deadbeefcafef005", 16),
    BigInt("deadbeefcafef006", 16),
    BigInt("deadbeefcafef007", 16),
    BigInt("deadbeefcafef008", 16))
  val refillCounter = BigInt("8877665544332211", 16)

  def programCounterBase(dut: InclusiveCacheTopHarnessWrapper, base: BigInt): Unit = {
    dut.io.cus_base_address.poke(base.U)
    dut.io.cus_base_wen.poke(true.B)
    dut.clock.step()
    dut.io.cus_base_wen.poke(false.B)
  }

  def clearTop(dut: InclusiveCacheTopHarnessWrapper): Unit = {
    dut.io.cus_base_address.poke(0.U)
    dut.io.cus_base_wen.poke(false.B)

    val inner = dut.io.inner
    val outer = dut.io.outer

    inner.a.valid.poke(false.B)
    inner.a.bits.opcode.poke(AcquireBlock)
    inner.a.bits.param.poke(NtoT)
    inner.a.bits.size.poke(log2Ceil(InclusiveCacheTopHarness.cache.blockBytes).U)
    inner.a.bits.source.poke(0.U)
    inner.a.bits.address.poke(0.U)
    inner.a.bits.mask.poke("hff".U)
    inner.a.bits.data.poke(0.U)
    inner.a.bits.corrupt.poke(false.B)
    inner.a.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
    }

    inner.c.valid.poke(false.B)
    inner.c.bits.opcode.poke(ReleaseData)
    inner.c.bits.param.poke(TtoT)
    inner.c.bits.size.poke(log2Ceil(InclusiveCacheTopHarness.cache.blockBytes).U)
    inner.c.bits.source.poke(0.U)
    inner.c.bits.address.poke(0.U)
    inner.c.bits.data.poke(0.U)
    inner.c.bits.corrupt.poke(false.B)
    inner.c.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
    }

    inner.e.valid.poke(false.B)
    inner.e.bits.sink.poke(0.U)

    inner.b.ready.poke(true.B)
    inner.d.ready.poke(true.B)

    outer.a.ready.poke(true.B)
    outer.c.ready.poke(true.B)
    outer.e.ready.poke(true.B)

    outer.d.valid.poke(false.B)
    outer.d.bits.opcode.poke(GrantData)
    outer.d.bits.param.poke(toT)
    outer.d.bits.size.poke(log2Ceil(InclusiveCacheTopHarness.cache.blockBytes).U)
    outer.d.bits.source.poke(0.U)
    outer.d.bits.sink.poke(0.U)
    outer.d.bits.denied.poke(false.B)
    outer.d.bits.data.poke(0.U)
    outer.d.bits.corrupt.poke(false.B)
    outer.d.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
      u.counter.poke(0.U)
      u.cryptoLine.poke(false.B)
    }

    outer.b.valid.poke(false.B)
    outer.b.bits.opcode.poke(Probe)
    outer.b.bits.param.poke(toN)
    outer.b.bits.size.poke(log2Ceil(InclusiveCacheTopHarness.cache.blockBytes).U)
    outer.b.bits.source.poke(0.U)
    outer.b.bits.address.poke(0.U)
    outer.b.bits.mask.poke("hff".U)
    outer.b.bits.data.poke(0.U)
    outer.b.bits.corrupt.poke(false.B)
  }

  def sendInnerAcquireBlock(
    dut: InclusiveCacheTopHarnessWrapper,
    address: BigInt,
    source: Int = 0,
    cryptoLine: Boolean = false): Unit = {
    val inner = dut.io.inner
    inner.a.valid.poke(true.B)
    inner.a.bits.opcode.poke(AcquireBlock)
    inner.a.bits.param.poke(NtoT)
    inner.a.bits.size.poke(log2Ceil(InclusiveCacheTopHarness.cache.blockBytes).U)
    inner.a.bits.source.poke(source.U)
    inner.a.bits.address.poke(address.U)
    inner.a.bits.mask.poke("hff".U)
    inner.a.bits.data.poke(0.U)
    inner.a.bits.corrupt.poke(false.B)
    inner.a.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter.poke(0.U)
      u.cryptoLine.poke(cryptoLine.B)
    }
    var cycles = 0
    while (!inner.a.ready.peek().litToBoolean && cycles < maxWaitCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(inner.a.ready.peek().litToBoolean, "Timed out waiting for inner.a.ready")
    dut.clock.step()
    inner.a.valid.poke(false.B)
  }

  def sendInnerReleaseData(
    dut: InclusiveCacheTopHarnessWrapper,
    address: BigInt,
    beats: Seq[BigInt],
    source: Int = 0,
    counter: BigInt = 0,
    cryptoLine: Boolean = false,
    param: BigInt = TtoT.litValue): Boolean = {
    val inner = dut.io.inner
    val blockSize = log2Ceil(InclusiveCacheTopHarness.cache.blockBytes).U
    require(beats.size == InclusiveCacheTopHarness.cache.blockBytes / InclusiveCacheTopHarness.cache.beatBytes)
    var sawReleaseAck = false

    def captureReleaseAck(): Unit = {
      if (inner.d.valid.peek().litToBoolean &&
          inner.d.bits.opcode.peek().litValue == ReleaseAck.litValue) {
        sawReleaseAck = true
      }
    }

    inner.c.valid.poke(true.B)
    inner.c.bits.opcode.poke(ReleaseData)
    inner.c.bits.param.poke(param.U)
    inner.c.bits.size.poke(blockSize)
    inner.c.bits.source.poke(source.U)
    inner.c.bits.address.poke(address.U)
    inner.c.bits.corrupt.poke(false.B)
    inner.c.bits.user.lift(CacheCryptoWritebackMeta).foreach { u =>
      u.counter.poke(counter.U)
      u.cryptoLine.poke(cryptoLine.B)
    }

    beats.foreach { beat =>
      inner.c.bits.data.poke(beat.U)
      var cycles = 0
      while (!inner.c.ready.peek().litToBoolean && cycles < maxGrantWaitCycles) {
        captureReleaseAck()
        dut.clock.step()
        cycles += 1
      }
      assert(inner.c.ready.peek().litToBoolean, "Timed out waiting for inner.c.ready")
      captureReleaseAck()
      dut.clock.step()
      captureReleaseAck()
    }

    inner.c.valid.poke(false.B)
    sawReleaseAck
  }

  def waitInnerReleaseAck(
    dut: InclusiveCacheTopHarnessWrapper,
    source: Option[Int] = None,
    maxCycles: Int = maxGrantWaitCycles): Unit = {
    val inner = dut.io.inner
    var seen = false
    var cycles = 0
    while (!seen && cycles < maxCycles) {
      if (inner.d.valid.peek().litToBoolean &&
          inner.d.bits.opcode.peek().litValue == ReleaseAck.litValue &&
          source.forall(s => inner.d.bits.source.peek().litValue == s)) {
        seen = true
      }
      dut.clock.step()
      cycles += 1
    }
    assert(seen, s"Timed out waiting for inner ReleaseAck after $maxCycles cycles")
  }

  def waitOuterAcquireBlock(
    dut: InclusiveCacheTopHarnessWrapper,
    maxCycles: Int = maxWaitCycles): BigInt = {
    val outer = dut.io.outer
    var cycles = 0
    while (!(outer.a.valid.peek().litToBoolean &&
             outer.a.bits.opcode.peek().litValue == AcquireBlock.litValue) &&
           cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(outer.a.valid.peek().litToBoolean, s"Timed out waiting for outer AcquireBlock after $maxCycles cycles")
    outer.a.bits.address.peek().litValue
  }

  def respondOuterGrantData(
    dut: InclusiveCacheTopHarnessWrapper,
    source: BigInt,
    sink: Int,
    beats: Seq[BigInt],
    counter: BigInt,
    cryptoLine: Boolean): Unit = {
    val blockSize = log2Ceil(InclusiveCacheTopHarness.cache.blockBytes).U
    require(beats.size == InclusiveCacheTopHarness.cache.blockBytes / InclusiveCacheTopHarness.cache.beatBytes)
    val outer = dut.io.outer

    outer.d.valid.poke(true.B)
    outer.d.bits.opcode.poke(GrantData)
    outer.d.bits.param.poke(toT)
    outer.d.bits.size.poke(blockSize)
    outer.d.bits.source.poke(source.U)
    outer.d.bits.sink.poke(sink.U)
    outer.d.bits.denied.poke(false.B)
    outer.d.bits.corrupt.poke(false.B)
    outer.d.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
      u.counter.poke(counter.U)
      u.cryptoLine.poke(cryptoLine.B)
    }

    beats.foreach { beat =>
      outer.d.bits.data.poke(beat.U)
      var cycles = 0
      while (!outer.d.ready.peek().litToBoolean && cycles < maxWaitCycles) {
        dut.clock.step()
        cycles += 1
      }
      assert(outer.d.ready.peek().litToBoolean, "Timed out waiting for outer.d.ready")
      dut.clock.step()
    }

    outer.d.valid.poke(false.B)
  }

  def respondOuterGrantDataAndCollectInnerGrantData(
    dut: InclusiveCacheTopHarnessWrapper,
    source: BigInt,
    sink: Int,
    beats: Seq[BigInt],
    counter: BigInt,
    cryptoLine: Boolean): Seq[BigInt] = {
    val blockSize = log2Ceil(InclusiveCacheTopHarness.cache.blockBytes).U
    require(beats.size == InclusiveCacheTopHarness.cache.blockBytes / InclusiveCacheTopHarness.cache.beatBytes)
    val outer = dut.io.outer
    val inner = dut.io.inner
    var captured = Seq.empty[BigInt]

    def captureInnerBeat(): Unit = {
      if (inner.d.valid.peek().litToBoolean &&
          inner.d.bits.opcode.peek().litValue == GrantData.litValue) {
        captured = captured :+ inner.d.bits.data.peek().litValue
      }
    }

    outer.d.valid.poke(true.B)
    outer.d.bits.opcode.poke(GrantData)
    outer.d.bits.param.poke(toT)
    outer.d.bits.size.poke(blockSize)
    outer.d.bits.source.poke(source.U)
    outer.d.bits.sink.poke(sink.U)
    outer.d.bits.denied.poke(false.B)
    outer.d.bits.corrupt.poke(false.B)
    outer.d.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
      u.counter.poke(counter.U)
      u.cryptoLine.poke(cryptoLine.B)
    }

    beats.foreach { beat =>
      outer.d.bits.data.poke(beat.U)
      var cycles = 0
      while (!outer.d.ready.peek().litToBoolean && cycles < maxGrantWaitCycles) {
        dut.clock.step()
        captureInnerBeat()
        cycles += 1
      }
      assert(outer.d.ready.peek().litToBoolean, "Timed out waiting for outer.d.ready")
      dut.clock.step()
      captureInnerBeat()
    }

    outer.d.valid.poke(false.B)

    val expectedBeats = beats.size
    var cycles = 0
    while (captured.size < expectedBeats && cycles < maxGrantWaitCycles) {
      dut.clock.step()
      captureInnerBeat()
      cycles += 1
    }
    assert(captured.size == expectedBeats, s"Timed out waiting for inner GrantData beats, got ${captured.size}/$expectedBeats")
    captured
  }

  def respondOuterGrantDataAndCollectInnerGrantResult(
    dut: InclusiveCacheTopHarnessWrapper,
    source: BigInt,
    sink: Int,
    beats: Seq[BigInt],
    counter: BigInt,
    cryptoLine: Boolean): InnerGrantResult = {
    val blockSize = log2Ceil(InclusiveCacheTopHarness.cache.blockBytes).U
    require(beats.size == InclusiveCacheTopHarness.cache.blockBytes / InclusiveCacheTopHarness.cache.beatBytes)
    val outer = dut.io.outer
    val inner = dut.io.inner
    var captured = Seq.empty[BigInt]
    var observedSink: Option[BigInt] = None

    def captureInnerBeat(): Unit = {
      if (inner.d.valid.peek().litToBoolean &&
          inner.d.bits.opcode.peek().litValue == GrantData.litValue) {
        captured = captured :+ inner.d.bits.data.peek().litValue
        if (observedSink.isEmpty) {
          observedSink = Some(inner.d.bits.sink.peek().litValue)
        }
      }
    }

    outer.d.valid.poke(true.B)
    outer.d.bits.opcode.poke(GrantData)
    outer.d.bits.param.poke(toT)
    outer.d.bits.size.poke(blockSize)
    outer.d.bits.source.poke(source.U)
    outer.d.bits.sink.poke(sink.U)
    outer.d.bits.denied.poke(false.B)
    outer.d.bits.corrupt.poke(false.B)
    outer.d.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
      u.counter.poke(counter.U)
      u.cryptoLine.poke(cryptoLine.B)
    }

    beats.foreach { beat =>
      outer.d.bits.data.poke(beat.U)
      var cycles = 0
      while (!outer.d.ready.peek().litToBoolean && cycles < maxGrantWaitCycles) {
        dut.clock.step()
        captureInnerBeat()
        cycles += 1
      }
      assert(outer.d.ready.peek().litToBoolean, "Timed out waiting for outer.d.ready")
      dut.clock.step()
      captureInnerBeat()
    }

    outer.d.valid.poke(false.B)

    val expectedBeats = beats.size
    var cycles = 0
    while (captured.size < expectedBeats && cycles < maxGrantWaitCycles) {
      dut.clock.step()
      captureInnerBeat()
      cycles += 1
    }
    assert(captured.size == expectedBeats, s"Timed out waiting for inner GrantData beats, got ${captured.size}/$expectedBeats")
    assert(observedSink.nonEmpty, "Timed out observing inner GrantData sink id")
    InnerGrantResult(captured, observedSink.get)
  }

  def sendInnerGrantAck(
    dut: InclusiveCacheTopHarnessWrapper,
    sink: BigInt): Unit = {
    val inner = dut.io.inner
    inner.e.valid.poke(true.B)
    inner.e.bits.sink.poke(sink.U)
    var cycles = 0
    while (!inner.e.ready.peek().litToBoolean && cycles < maxWaitCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(inner.e.ready.peek().litToBoolean, "Timed out waiting for inner.e.ready")
    dut.clock.step()
    inner.e.valid.poke(false.B)
  }

  def completeBoomLikeAcquireBlockMiss(
    dut: InclusiveCacheTopHarnessWrapper,
    address: BigInt,
    source: Int,
    beats: Seq[BigInt],
    counter: BigInt = 0,
    cryptoLine: Boolean = false,
    outerSink: Int = 0,
    settleCycles: Int = boomLikeRetireCycles): InnerGrantResult = {
    sendInnerAcquireBlock(dut, address = address, source = source, cryptoLine = cryptoLine)
    val observedOuterAddr = waitOuterAcquireBlock(dut, maxCycles = maxGrantWaitCycles * 5)
    assert(observedOuterAddr == address, f"Observed outer AcquireBlock address 0x$observedOuterAddr%x, expected 0x$address%x")
    val outerSource = dut.io.outer.a.bits.source.peek().litValue
    val grantResult = respondOuterGrantDataAndCollectInnerGrantResult(
      dut,
      source = outerSource,
      sink = outerSink,
      beats = beats,
      counter = counter,
      cryptoLine = cryptoLine)
    sendInnerGrantAck(dut, grantResult.sink)
    dut.clock.step(settleCycles)
    grantResult
  }

  def respondOuterGrantDataAndCollectInnerGrantMeta(
    dut: InclusiveCacheTopHarnessWrapper,
    source: BigInt,
    sink: Int,
    beats: Seq[BigInt],
    counter: BigInt,
    cryptoLine: Boolean): Seq[InnerGrantBeat] = {
    val blockSize = log2Ceil(InclusiveCacheTopHarness.cache.blockBytes).U
    require(beats.size == InclusiveCacheTopHarness.cache.blockBytes / InclusiveCacheTopHarness.cache.beatBytes)
    val outer = dut.io.outer

    outer.d.valid.poke(true.B)
    outer.d.bits.opcode.poke(GrantData)
    outer.d.bits.param.poke(toT)
    outer.d.bits.size.poke(blockSize)
    outer.d.bits.source.poke(source.U)
    outer.d.bits.sink.poke(sink.U)
    outer.d.bits.denied.poke(false.B)
    outer.d.bits.corrupt.poke(false.B)
    outer.d.bits.user.lift(CacheCryptoRefillMeta).foreach { u =>
      u.counter.poke(counter.U)
      u.cryptoLine.poke(cryptoLine.B)
    }

    beats.foreach { beat =>
      outer.d.bits.data.poke(beat.U)
      var cycles = 0
      while (!outer.d.ready.peek().litToBoolean && cycles < maxGrantWaitCycles) {
        dut.clock.step()
        cycles += 1
      }
      assert(outer.d.ready.peek().litToBoolean, "Timed out waiting for outer.d.ready")
      dut.clock.step()
    }

    outer.d.valid.poke(false.B)

    waitInnerGrantMeta(dut, expectedBeats = beats.size)
  }

  def waitInnerGrantData(dut: InclusiveCacheTopHarnessWrapper): Seq[BigInt] = {
    val inner = dut.io.inner
    var beats = Seq.empty[BigInt]
    val expectedBeats = InclusiveCacheTopHarness.cache.blockBytes / InclusiveCacheTopHarness.cache.beatBytes
    var cycles = 0
    while (beats.size < expectedBeats && cycles < maxGrantWaitCycles) {
      if (inner.d.valid.peek().litToBoolean &&
          inner.d.bits.opcode.peek().litValue == GrantData.litValue) {
        beats = beats :+ inner.d.bits.data.peek().litValue
      }
      dut.clock.step()
      cycles += 1
    }
    assert(beats.size == expectedBeats, s"Timed out waiting for inner GrantData beats, got ${beats.size}/$expectedBeats")
    beats
  }

  def waitInnerGrantMeta(
    dut: InclusiveCacheTopHarnessWrapper,
    expectedBeats: Int = InclusiveCacheTopHarness.cache.blockBytes / InclusiveCacheTopHarness.cache.beatBytes,
    maxCycles: Int = maxGrantWaitCycles): Seq[InnerGrantBeat] = {
    val inner = dut.io.inner
    val meta = inner.d.bits.user.lift(CacheCryptoRefillMeta)
    var beats = Vector.empty[InnerGrantBeat]
    var cycles = 0
    while (beats.size < expectedBeats && cycles < maxCycles) {
      if (inner.d.valid.peek().litToBoolean &&
          inner.d.bits.opcode.peek().litValue == GrantData.litValue) {
        beats = beats :+ InnerGrantBeat(
          data = inner.d.bits.data.peek().litValue,
          counter = meta.map(_.counter.peek().litValue).getOrElse(BigInt(0)),
          cryptoLine = meta.exists(_.cryptoLine.peek().litToBoolean))
      }
      dut.clock.step()
      cycles += 1
    }
    assert(beats.size == expectedBeats, s"Timed out waiting for inner GrantData beats, got ${beats.size}/$expectedBeats after $maxCycles cycles")
    beats
  }

  def waitFirstInnerGrantMeta(
    dut: InclusiveCacheTopHarnessWrapper,
    maxCycles: Int = maxGrantWaitCycles): InnerGrantBeat = {
    waitInnerGrantMeta(dut, expectedBeats = 1, maxCycles = maxCycles).head
  }

  def waitOuterEviction(
    dut: InclusiveCacheTopHarnessWrapper,
    maxCycles: Int = maxGrantWaitCycles * 4): OuterEviction = {
    val outer = dut.io.outer
    val expectedReleaseBeats = InclusiveCacheTopHarness.cache.blockBytes / InclusiveCacheTopHarness.cache.beatBytes
    var counterPut: Option[OuterCounterPut] = None
    var releaseAddr: Option[BigInt] = None
    var releaseBeats = Vector.empty[BigInt]
    var cycles = 0

    while ((counterPut.isEmpty || releaseBeats.size < expectedReleaseBeats) && cycles < maxCycles) {
      if (outer.a.valid.peek().litToBoolean &&
          outer.a.bits.opcode.peek().litValue == PutFullData.litValue &&
          counterPut.isEmpty) {
        counterPut = Some(OuterCounterPut(
          address = outer.a.bits.address.peek().litValue,
          data = outer.a.bits.data.peek().litValue))
      }

      if (outer.c.valid.peek().litToBoolean &&
          outer.c.bits.opcode.peek().litValue == ReleaseData.litValue &&
          releaseBeats.size < expectedReleaseBeats) {
        val addr = outer.c.bits.address.peek().litValue
        releaseAddr match {
          case Some(existing) => assert(existing == addr, s"Observed mixed outer ReleaseData addresses: 0x${existing.toString(16)} vs 0x${addr.toString(16)}")
          case None => releaseAddr = Some(addr)
        }
        releaseBeats = releaseBeats :+ outer.c.bits.data.peek().litValue
      }

      dut.clock.step()
      cycles += 1
    }

    assert(counterPut.nonEmpty, s"Timed out waiting for outer counter PutFullData after $maxCycles cycles")
    assert(releaseAddr.nonEmpty && releaseBeats.size == expectedReleaseBeats,
      s"Timed out waiting for outer ReleaseData after $maxCycles cycles, got ${releaseBeats.size}/$expectedReleaseBeats beats")

    OuterEviction(
      counterPut = counterPut.get,
      release = OuterRelease(releaseAddr.get, releaseBeats))
  }

  def waitOuterReleaseData(
    dut: InclusiveCacheTopHarnessWrapper,
    maxCycles: Int = maxGrantWaitCycles * 4): OuterRelease = {
    val outer = dut.io.outer
    val expectedReleaseBeats = InclusiveCacheTopHarness.cache.blockBytes / InclusiveCacheTopHarness.cache.beatBytes
    var releaseAddr: Option[BigInt] = None
    var releaseBeats = Vector.empty[BigInt]
    var cycles = 0

    while (releaseBeats.size < expectedReleaseBeats && cycles < maxCycles) {
      if (outer.c.valid.peek().litToBoolean &&
          outer.c.bits.opcode.peek().litValue == ReleaseData.litValue) {
        val addr = outer.c.bits.address.peek().litValue
        releaseAddr match {
          case Some(existing) => assert(existing == addr, s"Observed mixed outer ReleaseData addresses: 0x${existing.toString(16)} vs 0x${addr.toString(16)}")
          case None => releaseAddr = Some(addr)
        }
        releaseBeats = releaseBeats :+ outer.c.bits.data.peek().litValue
      }
      dut.clock.step()
      cycles += 1
    }

    assert(releaseAddr.nonEmpty && releaseBeats.size == expectedReleaseBeats,
      s"Timed out waiting for outer ReleaseData after $maxCycles cycles, got ${releaseBeats.size}/$expectedReleaseBeats beats")
    OuterRelease(releaseAddr.get, releaseBeats)
  }

  def assertNoOuterCounterPut(
    dut: InclusiveCacheTopHarnessWrapper,
    maxCycles: Int = maxGrantWaitCycles): Unit = {
    val outer = dut.io.outer
    var cycles = 0
    while (cycles < maxCycles) {
      assert(
        !(outer.a.valid.peek().litToBoolean &&
          outer.a.bits.opcode.peek().litValue == PutFullData.litValue),
        s"Observed unexpected outer counter PutFullData at cycle $cycles")
      dut.clock.step()
      cycles += 1
    }
  }
}
