package chipyard.peripherals.mydma

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.resources._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._
import freechips.rocketchip.util.PlusArg
import chipyard.peripherals.iommu._

case class MyDMAParams(
  ctrlAddress: BigInt = 0x10040000L,
  beatBytes: Int = 8,
  debugPrintf: Boolean = false)
case object MyDMAKey extends Field[Option[MyDMAParams]](None)

/** A single-outstanding-beat DMA with an optional simple IOMMU. */
class MyDMA(params: MyDMAParams)(implicit p: Parameters)
  extends ClockSinkDomain(ClockSinkParameters())(p) {

  private val device = new SimpleDevice("my-dma", Seq("tutorial,my-dma"))
  val ctrlNode = TLRegisterNode(
    address = Seq(AddressSet(params.ctrlAddress, 0xFFF)),
    device = device,
    beatBytes = params.beatBytes,
    concurrency = 1
  )

  private val iommuDevice = new SimpleDevice("simple-iommu", Seq("chipyard,simple-iommu"))
  private val iommuParams = p(SimpleIOMMUKey).getOrElse(SimpleIOMMUParams())
  val iommuCtrlNode = TLRegisterNode(
    address = Seq(AddressSet(iommuParams.ctrlAddress, 0xFFF)),
    device = iommuDevice,
    beatBytes = params.beatBytes,
    concurrency = 1
  )

  val dmaNode = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1(
      name = "my-dma-client",
      sourceId = IdRange(0, 1)
    ))
  )))

  // Physical page-table reads. This master is idle when IOMMU is disabled.
  val iommuPtwNode = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1(
      name = "my-dma-iommu-ptw",
      sourceId = IdRange(0, 1)
    ))
  )))

  override lazy val module = new Impl {
    withClockAndReset(clock, reset) {
      val src = RegInit(0.U(64.W))
      val dst = RegInit(0.U(64.W))
      val len = RegInit(0.U(32.W))
      val cmd = RegInit(0.U(32.W))
      val stat = RegInit(0.U(32.W))
      val debugLog = if (params.debugPrintf) {
        PlusArg("simple_iommu_debug", width = 1)(0).asBool
      } else {
        false.B
      }

      val cmdWriteFire = WireDefault(false.B)
      val cmdWriteData = WireDefault(0.U(32.W))
      ctrlNode.regmap(
        0x00 -> Seq(RegField(64, src)),
        0x08 -> Seq(RegField(64, dst)),
        0x10 -> Seq(RegField(32, len)),
        0x14 -> Seq(RegField.w(32, RegWriteFn { (valid, data) =>
          cmdWriteFire := valid
          cmdWriteData := data
          true.B
        })),
        0x18 -> Seq(RegField.r(32, stat))
      )

      val iommuPresent = p(SimpleIOMMUKey).isDefined
      val iommu = if (iommuPresent) Some(Module(new SimpleIOMMU(iommuParams))) else None
      val iommuRootPtBase = RegInit(0.U(64.W))
      val iommuEnable = RegInit(iommuParams.enableAtReset.B)
      val iommuFault = RegInit(false.B)
      val iommuFaultAddr = RegInit(0.U(64.W))
      val iommuFaultInfo = RegInit(0.U(32.W))
      val iommuFlush = WireDefault(false.B)
      val prevRootPtBase = RegNext(iommuRootPtBase, 0.U(64.W))
      val prevIommuEnable = RegNext(iommuEnable, iommuParams.enableAtReset.B)
      when (iommuRootPtBase =/= prevRootPtBase || iommuEnable =/= prevIommuEnable) {
        iommuFlush := true.B
      }

      val iommuBusy = iommu.map { u =>
        u.io.req.valid || u.io.resp.valid || u.io.ptwReq.valid || u.io.ptwResp.valid
      }.getOrElse(false.B)
      val iommuStatus = Cat(0.U(29.W), iommuBusy, iommuFault, iommuEnable)

      iommuCtrlNode.regmap(
        0x00 -> Seq(RegField(64, iommuRootPtBase)),
        0x08 -> Seq(RegField.w(32, RegWriteFn { (valid, data) =>
          when (valid) {
            // Bit 0 changes enable. Flush/clear commands preserve it.
            when (!(data(1) || data(2))) { iommuEnable := data(0) }
            when (data(1)) { iommuFlush := true.B }
            when (data(2)) { iommuFault := false.B }
          }
          true.B
        })),
        0x10 -> Seq(RegField.r(32, iommuStatus)),
        0x18 -> Seq(RegField.r(64, iommuFaultAddr)),
        0x20 -> Seq(RegField.r(32, iommuFaultInfo)),
        0x28 -> Seq(RegField.r(32, ((iommuParams.iotlbEntries << 16) | (1 << iommuParams.pageBits)).U))
      )

      val (mem, edge) = dmaNode.out(0)
      val (ptw, ptwEdge) = iommuPtwNode.out(0)
      val beatBytes = edge.bundle.dataBits / 8
      val ptwBeatBytes = ptwEdge.bundle.dataBits / 8
      require(ptwBeatBytes >= 8, "IOMMU PTW requires an 8-byte TileLink beat")
      val lgSize = log2Ceil(beatBytes).U
      val ptwLgSize = log2Ceil(8).U

      val translateReq = Wire(Decoupled(new SimpleIOMMUReq(iommuParams.iovaBits)))
      val translateResp = Wire(Decoupled(new SimpleIOMMUResp))
      translateReq.valid := false.B
      translateReq.bits := DontCare
      translateResp.ready := false.B
      translateResp.bits := DontCare

      ptw.a.valid := false.B
      ptw.a.bits := DontCare
      ptw.d.ready := false.B
      iommu.foreach { u =>
        u.io.enable := iommuEnable
        u.io.rootPtBase := iommuRootPtBase
        u.io.flush := iommuFlush
        u.io.req <> translateReq
        translateResp <> u.io.resp

        u.io.ptwReq.ready := ptw.a.ready
        ptw.a.valid := u.io.ptwReq.valid
        ptw.a.bits := ptwEdge.Get(0.U, u.io.ptwReq.bits.addr, ptwLgSize)._2
        u.io.ptwResp.valid := ptw.d.valid
        u.io.ptwResp.bits.data := ptw.d.bits.data
        u.io.ptwResp.bits.fault := ptw.d.bits.denied || ptw.d.bits.corrupt
        ptw.d.ready := u.io.ptwResp.ready
      }

      val sIdle :: sTranslateSrc :: sTranslateSrcResp :: sRead :: sReadResp :: sTranslateDst :: sTranslateDstResp :: sWrite :: sWriteResp :: sDone :: sErr :: Nil = Enum(11)
      val state = RegInit(sIdle)
      val curSrc = Reg(UInt(64.W))
      val curDst = Reg(UInt(64.W))
      val translatedSrc = Reg(UInt(64.W))
      val translatedDst = Reg(UInt(64.W))
      val bytesLeft = Reg(UInt(32.W))
      val readData = Reg(UInt((beatBytes * 8).W))

      mem.a.valid := false.B
      mem.a.bits := DontCare
      mem.d.ready := false.B

      val lenAligned = (len & (beatBytes.U - 1.U)) === 0.U
      val nonZeroLen = len =/= 0.U
      val pageBytes = 1 << iommuParams.pageBits
      val srcCrossesPage = curSrc(iommuParams.pageBits - 1, 0) > (pageBytes - beatBytes).U
      val dstCrossesPage = curDst(iommuParams.pageBits - 1, 0) > (pageBytes - beatBytes).U
      val srcCrossesPageAtStart = src(iommuParams.pageBits - 1, 0) > (pageBytes - beatBytes).U
      val dstCrossesPageAtStart = dst(iommuParams.pageBits - 1, 0) > (pageBytes - beatBytes).U
      val srcAlignedAtStart = src(log2Ceil(beatBytes) - 1, 0) === 0.U
      val dstAlignedAtStart = dst(log2Ceil(beatBytes) - 1, 0) === 0.U

      when (state === sTranslateSrc) {
        translateReq.valid := !srcCrossesPage && !dstCrossesPage
        translateReq.bits.iova := curSrc(iommuParams.iovaBits - 1, 0)
        translateReq.bits.write := false.B
      }
      when (state === sTranslateSrcResp) {
        translateResp.ready := true.B
      }
      when (state === sTranslateDst) {
        translateReq.valid := !dstCrossesPage
        translateReq.bits.iova := curDst(iommuParams.iovaBits - 1, 0)
        translateReq.bits.write := true.B
      }
      when (state === sTranslateDstResp) {
        translateResp.ready := true.B
      }

      val dmaSrcAddr = Mux(iommuEnable, translatedSrc, curSrc)
      val dmaDstAddr = Mux(iommuEnable, translatedDst, curDst)
      when (state === sRead) {
        mem.a.valid := true.B
        mem.a.bits := edge.Get(0.U, dmaSrcAddr, lgSize)._2
      }
      when (state === sReadResp) {
        mem.d.ready := true.B
      }
      when (state === sWrite) {
        mem.a.valid := true.B
        mem.a.bits := edge.Put(0.U, dmaDstAddr, lgSize, readData)._2
      }
      when (state === sWriteResp) {
        mem.d.ready := true.B
      }

      val canStart = state === sIdle || state === sDone || state === sErr
      val start = cmdWriteFire && cmdWriteData(0) && canStart
      when (start) {
        cmd := cmdWriteData
        when (!lenAligned || !nonZeroLen) {
          stat := "b100".U
          state := sErr
        }.elsewhen (srcCrossesPageAtStart || dstCrossesPageAtStart) {
          stat := "b100".U
          state := sErr
          when (iommuEnable) {
            iommuFault := true.B
            iommuFaultAddr := Mux(srcCrossesPageAtStart, src, dst)
            iommuFaultInfo := 6.U
          }
          when (debugLog) {
            printf("[MyDMA] FAULT: start beat crosses page src=0x%x dst=0x%x\n", src, dst)
          }
        }.elsewhen (!srcAlignedAtStart || !dstAlignedAtStart) {
          stat := "b100".U
          state := sErr
          when (iommuEnable) {
            iommuFault := true.B
            iommuFaultAddr := Mux(!srcAlignedAtStart, src, dst)
            iommuFaultInfo := 7.U
          }
          when (debugLog) {
            printf("[MyDMA] FAULT: unaligned beat src=0x%x dst=0x%x\n", src, dst)
          }
        }.otherwise {
          curSrc := src
          curDst := dst
          bytesLeft := len
          translatedSrc := src
          translatedDst := dst
          stat := "b010".U
          state := Mux(iommuEnable, sTranslateSrc, sRead)
          when (debugLog) {
            printf("[MyDMA] START: src=0x%x dst=0x%x len=%d iommu=%d\n", src, dst, len, iommuEnable)
          }
        }
      }

      when (state === sTranslateSrc && (srcCrossesPage || dstCrossesPage)) {
        iommuFault := true.B
        iommuFaultAddr := Mux(srcCrossesPage, curSrc, curDst)
        iommuFaultInfo := 6.U
        stat := "b100".U
        state := sErr
        when (debugLog) {
          printf("[MyDMA] FAULT: beat crosses page src=0x%x dst=0x%x\n", curSrc, curDst)
        }
      }
      when (state === sTranslateDst && dstCrossesPage) {
        iommuFault := true.B
        iommuFaultAddr := curDst
        iommuFaultInfo := 6.U
        stat := "b100".U
        state := sErr
        when (debugLog) { printf("[MyDMA] FAULT: destination beat crosses page addr=0x%x\n", curDst) }
      }

      when (translateReq.fire) {
        when (state === sTranslateSrc) { state := sTranslateSrcResp }
        when (state === sTranslateDst) { state := sTranslateDstResp }
      }
      when (translateResp.fire) {
        when (translateResp.bits.fault) {
          iommuFault := true.B
          iommuFaultAddr := Mux(state === sTranslateSrcResp, curSrc, curDst)
          iommuFaultInfo := Cat(0.U(24.W), translateResp.bits.faultCode)
          stat := "b100".U
          state := sErr
          when (debugLog) {
            printf("[MyDMA] FAULT: iova=0x%x code=%d\n",
              Mux(state === sTranslateSrcResp, curSrc, curDst), translateResp.bits.faultCode)
          }
        }.elsewhen (state === sTranslateSrcResp) {
          translatedSrc := translateResp.bits.pa
          state := sRead
        }.otherwise {
          translatedDst := translateResp.bits.pa
          state := sWrite
        }
      }

      when (mem.a.fire && state === sRead) {
        state := sReadResp
      }
      when (mem.d.fire && state === sReadResp) {
        when (mem.d.bits.denied || mem.d.bits.corrupt) {
          stat := "b100".U
          state := sErr
          when (debugLog) { printf("[MyDMA] FAULT: read response denied=%d corrupt=%d\n", mem.d.bits.denied, mem.d.bits.corrupt) }
        }.otherwise {
          readData := mem.d.bits.data
          state := Mux(iommuEnable, sTranslateDst, sWrite)
        }
      }
      when (mem.a.fire && state === sWrite) {
        state := sWriteResp
      }
      when (mem.d.fire && state === sWriteResp) {
        when (mem.d.bits.denied || mem.d.bits.corrupt) {
          stat := "b100".U
          state := sErr
          when (debugLog) { printf("[MyDMA] FAULT: write response denied=%d corrupt=%d\n", mem.d.bits.denied, mem.d.bits.corrupt) }
        }.otherwise {
          val nextBytesLeft = bytesLeft - beatBytes.U
          bytesLeft := nextBytesLeft
          curSrc := curSrc + beatBytes.U
          curDst := curDst + beatBytes.U
          when (nextBytesLeft === 0.U) {
            state := sDone
            stat := "b001".U
            when (debugLog) { printf("[MyDMA] DONE\n") }
          }.otherwise {
            state := Mux(iommuEnable, sTranslateSrc, sRead)
          }
        }
      }
    }
  }
}
