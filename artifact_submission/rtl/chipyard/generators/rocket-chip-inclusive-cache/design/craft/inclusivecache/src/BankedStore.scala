/*
 * Copyright 2019 SiFive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You should have received a copy of LICENSE.Apache2 along with
 * this software. If not, you may obtain a copy at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.DescribedSRAM
import freechips.rocketchip.util.PlusArg

import scala.math.{max, min}

abstract class BankedStoreAddress(val inner: Boolean, params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val noop = Bool() // do not actually use the SRAMs, just block their use
  val way  = UInt(params.wayBits.W)
  val set  = UInt(params.setBits.W)
  val beat = UInt((if (inner) params.innerBeatBits else params.outerBeatBits).W)
  val mask = UInt((if (inner) params.innerMaskBits else params.outerMaskBits).W)
}

trait BankedStoreRW
{
  val write = Bool()
}

class BankedStoreOuterAddress(params: InclusiveCacheParameters) extends BankedStoreAddress(false, params)
class BankedStoreInnerAddress(params: InclusiveCacheParameters) extends BankedStoreAddress(true, params)
class BankedStoreInnerAddressRW(params: InclusiveCacheParameters) extends BankedStoreInnerAddress(params) with BankedStoreRW

abstract class BankedStoreData(val inner: Boolean, params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val data = UInt(((if (inner) params.inner.manager.beatBytes else params.outer.manager.beatBytes)*8).W)
}

class BankedStoreOuterData(params: InclusiveCacheParameters) extends BankedStoreData(false, params)
class BankedStoreInnerData(params: InclusiveCacheParameters) extends BankedStoreData(true,  params)
class BankedStoreInnerPoison(params: InclusiveCacheParameters) extends BankedStoreInnerData(params)
class BankedStoreOuterPoison(params: InclusiveCacheParameters) extends BankedStoreOuterData(params)
class BankedStoreInnerDecoded(params: InclusiveCacheParameters) extends BankedStoreInnerData(params)
class BankedStoreOuterDecoded(params: InclusiveCacheParameters) extends BankedStoreOuterData(params)

class BankedStore(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val sinkC_adr = Flipped(Decoupled(new BankedStoreInnerAddress(params)))
    val sinkC_dat = Flipped(new BankedStoreInnerPoison(params))
    val sinkD_adr = Flipped(Decoupled(new BankedStoreOuterAddress(params)))
    val sinkD_dat = Flipped(new BankedStoreOuterPoison(params))
    val sourceC_adr = Flipped(Decoupled(new BankedStoreOuterAddress(params)))
    val sourceC_dat = new BankedStoreOuterDecoded(params)
    val sourceD_radr = Flipped(Decoupled(new BankedStoreInnerAddress(params)))
    val sourceD_rdat = new BankedStoreInnerDecoded(params)
    val sourceD_wadr = Flipped(Decoupled(new BankedStoreInnerAddress(params)))
    val sourceD_wdat = Flipped(new BankedStoreInnerPoison(params))
    val counterReadA = Flipped(Decoupled(new CounterSidecarAddress(params)))
    val counterReadAData = Output(UInt(params.outer.bundle.dataBits.W))
    val counterReadD = Flipped(Decoupled(new CounterSidecarAddress(params)))
    val counterReadDData = Output(UInt(params.outer.bundle.dataBits.W))
    val sinkC_counterWrite = Flipped(Decoupled(new CounterSidecarWrite(params)))
    val sinkD_counterWrite = Flipped(Decoupled(new CounterSidecarWrite(params)))
    val sourceD_counterWrite = Flipped(Decoupled(new CounterSidecarWrite(params)))
  })

  val innerBytes = params.inner.manager.beatBytes
  val outerBytes = params.outer.manager.beatBytes
  val rowBytes = params.micro.portFactor * max(innerBytes, outerBytes)
  val debugLogEnable = PlusArg("inclusive_cache_debug_log", default = 0, width = 1) =/= 0.U
  private val debugDataWatchSetStartValue = BigInt("080", 16)
  private val debugDataWatchSetEndValue = BigInt("18c", 16)
  private def truncToWidth(x: BigInt, width: Int): UInt = (x & ((BigInt(1) << width) - 1)).U(width.W)
  val debugDataWatchSetStart = truncToWidth(debugDataWatchSetStartValue, params.setBits)
  val debugDataWatchSetEnd = truncToWidth(debugDataWatchSetEndValue, params.setBits)
  require (rowBytes < params.cache.sizeBytes)
  val rowEntries = params.cache.sizeBytes / rowBytes
  val rowBits = log2Ceil(rowEntries)
  val numBanks = rowBytes / params.micro.writeBytes
  val codeBits = 8*params.micro.writeBytes

  val cc_banks = Seq.tabulate(numBanks) {
    i =>
    DescribedSRAM(
      name = s"cc_banks_$i",
      desc = "Banked Store",
      size = rowEntries,
        data = UInt(codeBits.W)
      )
  }
  // These constraints apply on the port priorities:
  //  sourceC > sinkD     outgoing Release > incoming Grant      (we start eviction+refill concurrently)
  //  sinkC > sourceC     incoming ProbeAck > outgoing ProbeAck  (we delay probeack writeback by 1 cycle for QoR)
  //  sinkC > sourceDr    incoming ProbeAck > SourceD read       (we delay probeack writeback by 1 cycle for QoR)
  //  sourceDw > sourceDr modified data visible on next cycle    (needed to ensure SourceD forward progress)
  //  sinkC > sourceC     inner ProbeAck > outer ProbeAck        (make wormhole routing possible [not yet implemented])
  //  sinkC&D > sourceD*  beat arrival > beat read|update        (make wormhole routing possible [not yet implemented])

  // Combining these restrictions yields a priority scheme of:
  //  sinkC > sourceC > sinkD > sourceDw > sourceDr
  //          ^^^^^^^^^^^^^^^ outer interface

  // Requests have different port widths, but we don't want to allow cutting in line.
  // Suppose we have requests A > B > C requesting ports --A-, --BB, ---C.
  // The correct arbitration is to allow --A- only, not --AC.
  // Obviously --A-, BB--, ---C should still be resolved to BBAC.
  val cc_counters = DescribedSRAM(
    name = "cc_counters",
    desc = "Banked Counter Sidecar",
    size = params.cache.sets,
    data = Vec(params.cache.ways, UInt(params.outer.bundle.dataBits.W)))

  class Request extends Bundle {
    val wen      = Bool()
    val index    = UInt(rowBits.W)
    val bankSel  = UInt(numBanks.W)
    val bankSum  = UInt(numBanks.W) // OR of all higher priority bankSels
    val bankEn   = UInt(numBanks.W) // ports actually activated by request
    val data     = Vec(numBanks, UInt(codeBits.W))
  }

  object CounterAccessTag {
    val width = 3
    // 用于在同步读返回后一拍把 sidecar 数据送回正确的 consumer。
    def sinkC: UInt = 0.U(width.W)
    def readA: UInt = 1.U(width.W)
    def sinkD: UInt = 2.U(width.W)
    def sourceDw: UInt = 3.U(width.W)
    def readD: UInt = 4.U(width.W)
  }

  class CounterAccess extends Bundle {
    // counter sidecar 的统一内部访问抽象；读写都先收敛成这种 request
    // 再做固定优先级仲裁。
    val write = Bool()
    val set = UInt(params.setBits.W)
    val way = UInt(params.wayBits.W)
    val data = UInt(params.outer.bundle.dataBits.W)
    val tag = UInt(CounterAccessTag.width.W)
  }

  def req[T <: BankedStoreAddress](b: DecoupledIO[T], write: Bool, d: UInt): Request = {
    val beatBytes = if (b.bits.inner) innerBytes else outerBytes
    val ports = beatBytes / params.micro.writeBytes
    val bankBits = log2Ceil(numBanks / ports)
    val words = Seq.tabulate(ports) { i =>
      d((i + 1) * 8 * params.micro.writeBytes - 1, i * 8 * params.micro.writeBytes)
    }
    val a = if (params.cache.blockBytes == beatBytes) Cat(b.bits.way, b.bits.set) else Cat(b.bits.way, b.bits.set, b.bits.beat)
    val m = b.bits.mask
    val out = Wire(new Request)

    val select = UIntToOH(a(bankBits-1, 0), numBanks/ports)
    val ready  = Cat(Seq.tabulate(numBanks/ports) { i => !(out.bankSum((i+1)*ports-1, i*ports) & m).orR } .reverse)
    b.ready := ready(a(bankBits-1, 0))

    out.wen      := write
    out.index    := a >> bankBits
    out.bankSel  := Mux(b.valid, FillInterleaved(ports, select) & Fill(numBanks/ports, m), 0.U)
    out.bankEn   := Mux(b.bits.noop, 0.U, out.bankSel & FillInterleaved(ports, ready))
    out.data     := Seq.fill(numBanks/ports) { words }.flatten

    out
  }

  val innerData = 0.U((8*innerBytes).W)
  val outerData = 0.U((8*outerBytes).W)
  val W = true.B
  val R = false.B

  val sinkC_req    = req(io.sinkC_adr,    W, io.sinkC_dat.data)
  val sinkD_req    = req(io.sinkD_adr,    W, io.sinkD_dat.data)
  val sourceC_req  = req(io.sourceC_adr,  R, outerData)
  val sourceD_rreq = req(io.sourceD_radr, R, innerData)
  val sourceD_wreq = req(io.sourceD_wadr, W, io.sourceD_wdat.data)
  def inDebugSetRange(set: UInt): Bool = set >= debugDataWatchSetStart && set < debugDataWatchSetEnd

  // See the comments above for why this prioritization is used
  val reqs = Seq(sinkC_req, sourceC_req, sinkD_req, sourceD_wreq, sourceD_rreq)

  // Connect priorities; note that even if a request does not go through due to failing
  // to obtain a needed subbank, it still blocks overlapping lower priority requests.
  reqs.foldLeft(0.U) { case (sum, req) =>
    req.bankSum := sum
    req.bankSel | sum
  }
  // Access the banks
  val regout = VecInit(cc_banks.zipWithIndex.map { case (b, i) =>
    val en  = reqs.map(_.bankEn(i)).reduce(_||_)
    val sel = reqs.map(_.bankSel(i))
    val wen = PriorityMux(sel, reqs.map(_.wen))
    val idx = PriorityMux(sel, reqs.map(_.index))
    val data= PriorityMux(sel, reqs.map(_.data(i)))

    when (wen && en) { b.write(idx, data) }
    RegEnable(b.read(idx, !wen && en), RegNext(!wen && en))
  })

  val regsel_sourceC = RegNext(RegNext(sourceC_req.bankEn))
  val regsel_sourceD = RegNext(RegNext(sourceD_rreq.bankEn))
  // counter sidecar 访问优先级：
  // SinkC 提交写 > old victim committed 读 > SinkD 提交写 > SourceD 提交写 > SourceD committed 读
  val sinkC_counterReq = Wire(new CounterAccess)
  sinkC_counterReq.write := true.B
  sinkC_counterReq.set := io.sinkC_counterWrite.bits.set
  sinkC_counterReq.way := io.sinkC_counterWrite.bits.way
  sinkC_counterReq.data := io.sinkC_counterWrite.bits.counter
  sinkC_counterReq.tag := CounterAccessTag.sinkC

  val readA_counterReq = Wire(new CounterAccess)
  readA_counterReq.write := false.B
  readA_counterReq.set := io.counterReadA.bits.set
  readA_counterReq.way := io.counterReadA.bits.way
  readA_counterReq.data := 0.U
  readA_counterReq.tag := CounterAccessTag.readA

  val sinkD_counterReq = Wire(new CounterAccess)
  sinkD_counterReq.write := true.B
  sinkD_counterReq.set := io.sinkD_counterWrite.bits.set
  sinkD_counterReq.way := io.sinkD_counterWrite.bits.way
  sinkD_counterReq.data := io.sinkD_counterWrite.bits.counter
  sinkD_counterReq.tag := CounterAccessTag.sinkD

  val sourceDw_counterReq = Wire(new CounterAccess)
  sourceDw_counterReq.write := true.B
  sourceDw_counterReq.set := io.sourceD_counterWrite.bits.set
  sourceDw_counterReq.way := io.sourceD_counterWrite.bits.way
  sourceDw_counterReq.data := io.sourceD_counterWrite.bits.counter
  sourceDw_counterReq.tag := CounterAccessTag.sourceDw

  val readD_counterReq = Wire(new CounterAccess)
  readD_counterReq.write := false.B
  readD_counterReq.set := io.counterReadD.bits.set
  readD_counterReq.way := io.counterReadD.bits.way
  readD_counterReq.data := 0.U
  readD_counterReq.tag := CounterAccessTag.readD

  val counterReqValids = Seq(
    io.sinkC_counterWrite.valid,
    io.counterReadA.valid,
    io.sinkD_counterWrite.valid,
    io.sourceD_counterWrite.valid,
    io.counterReadD.valid)
  val counterReqs = Seq(
    sinkC_counterReq,
    readA_counterReq,
    sinkD_counterReq,
    sourceDw_counterReq,
    readD_counterReq)

  val counterSelSinkCW = counterReqValids(0)
  val counterSelReadA  = !counterSelSinkCW && counterReqValids(1)
  val counterSelSinkDW = !counterSelSinkCW && !counterSelReadA && counterReqValids(2)
  val counterSelSrcDW  = !counterSelSinkCW && !counterSelReadA && !counterSelSinkDW && counterReqValids(3)
  val counterSelReadD  = !counterSelSinkCW && !counterSelReadA && !counterSelSinkDW && !counterSelSrcDW && counterReqValids(4)
  val counterSelOH = Seq(counterSelSinkCW, counterSelReadA, counterSelSinkDW, counterSelSrcDW, counterSelReadD)
  val counterSelValid = counterSelOH.reduce(_||_)
  // 这里显式展开优先级链，而不是用 PriorityEncoderOH，是为了避免低优先级
  // valid 经过 ready 路径反向卷回高优先级 source，形成组合环。
  val counterSelReq = WireInit(0.U.asTypeOf(new CounterAccess))
  counterSelReq := Mux(counterSelSinkCW, counterReqs(0),
                   Mux(counterSelReadA,  counterReqs(1),
                   Mux(counterSelSinkDW, counterReqs(2),
                   Mux(counterSelSrcDW,  counterReqs(3),
                                           counterReqs(4)))))

  assert(PopCount(counterSelOH) <= 1.U)
  assert(counterSelValid === counterSelOH.reduce(_||_))

  io.sinkC_counterWrite.ready := counterSelSinkCW
  io.counterReadA.ready := counterSelReadA
  io.sinkD_counterWrite.ready := counterSelSinkDW
  io.sourceD_counterWrite.ready := counterSelSrcDW
  io.counterReadD.ready := counterSelReadD


  val counterWriteFire = counterSelValid && counterSelReq.write
  val counterReadFire = counterSelValid && !counterSelReq.write

  when (counterWriteFire) {
    // sidecar committed store 只执行仲裁后的 winner 请求，不在这里做 latest 修正。
    cc_counters.write(
      counterSelReq.set,
      VecInit.fill(params.cache.ways) { counterSelReq.data },
      UIntToOH(counterSelReq.way, params.cache.ways).asBools)
  }

  val counterReadMem = cc_counters.read(counterSelReq.set, counterReadFire)
  // Counter sidecar read mirrors the data-bank read structure:
  //   1. synchronous SRAM read
  //   2. one registered data stage
  //   3. two-cycle aligned selector/tag pipeline
  //   4. final consumer split between readA and readD
  val counterReadValid_s1 = RegNext(counterReadFire, false.B)
  val counterReadWayOH_s1 = RegEnable(UIntToOH(counterSelReq.way, params.cache.ways), counterReadFire)
  val counterReadTag_s1 = RegEnable(counterSelReq.tag, counterReadFire)
  val counterReadData_s1 = RegEnable(counterReadMem, counterReadValid_s1)

  val counterReadValid_s2 = RegNext(counterReadValid_s1, false.B)
  val counterReadWayOH_s2 = RegEnable(counterReadWayOH_s1, counterReadValid_s1)
  val counterReadTag_s2 = RegEnable(counterReadTag_s1, counterReadValid_s1)

  val counterReadData = Mux1H(counterReadWayOH_s2, counterReadData_s1)
  io.counterReadAData :=
    Mux(counterReadValid_s2 && counterReadTag_s2 === CounterAccessTag.readA, counterReadData, 0.U)
  io.counterReadDData :=
    Mux(counterReadValid_s2 && counterReadTag_s2 === CounterAccessTag.readD, counterReadData, 0.U)

  params.ccover(io.sinkC_counterWrite.valid && !io.sinkC_counterWrite.ready, "COUNTER_ARB_STALL_SINKC", "Counter sidecar write from SinkC stalled behind higher priority access")
  params.ccover(io.counterReadA.valid && !io.counterReadA.ready, "COUNTER_ARB_STALL_READA", "Counter sidecar read for SourceA stalled behind higher priority access")
  params.ccover(io.sinkD_counterWrite.valid && !io.sinkD_counterWrite.ready, "COUNTER_ARB_STALL_SINKD", "Counter sidecar write from SinkD stalled behind higher priority access")
  params.ccover(io.sourceD_counterWrite.valid && !io.sourceD_counterWrite.ready, "COUNTER_ARB_STALL_SOURCEDW", "Counter sidecar write from SourceD stalled behind higher priority access")
  params.ccover(io.counterReadD.valid && !io.counterReadD.ready, "COUNTER_ARB_STALL_READD", "Counter sidecar read for SourceD stalled behind higher priority access")

  val decodeC = regout.zipWithIndex.map {
    case (r, i) => Mux(regsel_sourceC(i), r, 0.U)
  }.grouped(outerBytes/params.micro.writeBytes).toList.transpose.map(s => s.reduce(_|_))

  io.sourceC_dat.data := Cat(decodeC.reverse)

  val decodeD = regout.zipWithIndex.map {
    // Intentionally not Mux1H and/or an indexed-mux b/c we want it 0 when !sel to save decode power
    case (r, i) => Mux(regsel_sourceD(i), r, 0.U)
  }.grouped(innerBytes/params.micro.writeBytes).toList.transpose.map(s => s.reduce(_|_))

  io.sourceD_rdat.data := Cat(decodeD.reverse)

  private def banks = cc_banks.map("\"" + _.pathName + "\"").mkString(",")
  def json: String = s"""{"widthBytes":${params.micro.writeBytes},"mem":[${banks}]}"""
}
