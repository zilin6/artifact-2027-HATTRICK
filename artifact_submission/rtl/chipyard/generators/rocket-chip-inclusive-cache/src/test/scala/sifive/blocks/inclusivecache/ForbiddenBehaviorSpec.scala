package sifive.blocks.inclusivecache

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import InclusiveCacheCounterTestUtils._

class ForbiddenBehaviorSpec extends AnyFlatSpec with Matchers {
  private val rtlDir = Paths.get("/path/to/chipyard/generators/rocket-chip-inclusive-cache/design/craft/inclusivecache/src")
  private def allScalaFiles(root: Path): Seq[Path] =
    Files.walk(root).iterator().asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala")).toSeq

  private def slurp(paths: Seq[Path]): String =
    paths.map(p => Files.readString(p)).mkString("\n")

  private lazy val rtlCorpus = slurp(allScalaFiles(rtlDir))
  private lazy val sourceAChirrtl = ChiselStage.emitCHIRRTL(new SourceA(testParams))
  private lazy val sourceCChirrtl = ChiselStage.emitCHIRRTL(new SourceC(testParams))
  private lazy val mshrChirrtl = ChiselStage.emitCHIRRTL(new MSHR(testParams))

  behavior of "Forbidden counter-path regressions"

  it should "not reintroduce forbidden owner or victim-presence structures" in {
    rtlCorpus should not include "hasCounterOwner"
    rtlCorpus should not include "victimCounterPresent"
    rtlCorpus should not include "victimCounterBufIdx"
    rtlCorpus should not include "raw counter owner"
    rtlCorpus should not include "MSHR raw counter owner"
  }

  it should "not reintroduce SourceA latest-lookup bypass assumptions" in {
    rtlCorpus should not include "ctr_bypass"
    rtlCorpus should not include "latest sidecar reread"
    rtlCorpus should not include "SourceA 自己做 unified latest lookup"
  }

  it should "keep SourceA structurally snapshot-only instead of sidecar reread driven" in {
    sourceAChirrtl should include ("ctr_snapshot_data")
    sourceAChirrtl should include ("ctr_snapshot_valid")
    sourceAChirrtl should include ("ctr_snapshot_pop")

    sourceAChirrtl should not include "ctr_radr"
    sourceAChirrtl should not include "ctr_rdat"
    sourceAChirrtl should not include "ctr_bypass"
    sourceAChirrtl should not include "reread"
    sourceAChirrtl should not include "counterOwner"
    sourceAChirrtl should not include "victimCounterPresent"
  }

  it should "keep SourceC structurally on snapshot export rather than explicit victim-presence bookkeeping" in {
    sourceCChirrtl should include ("ctr_snapshot_data")
    sourceCChirrtl should include ("ctr_snapshot_valid")
    sourceCChirrtl should include ("ctr_snapshot_pop")

    sourceCChirrtl should not include "victimCounterPresent"
    sourceCChirrtl should not include "victimCounterBufIdx"
    sourceCChirrtl should not include "hasCounterOwner"
    sourceCChirrtl should not include "counterOwner"
  }

  it should "keep MSHR structurally on dual metadata plus need_counter_put instead of owner fields" in {
    mshrChirrtl should include ("victimMeta")
    mshrChirrtl should include ("refillMeta")
    mshrChirrtl should include ("need_counter_put")

    mshrChirrtl should not include "hasCounterOwner"
    mshrChirrtl should not include "victimCounterPresent"
    mshrChirrtl should not include "victimCounterBufIdx"
    mshrChirrtl should not include "counterOwner"
  }
}
