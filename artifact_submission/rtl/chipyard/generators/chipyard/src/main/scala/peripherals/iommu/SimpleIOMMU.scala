package chipyard.peripherals.iommu

import chisel3._
import chisel3.util._

class SimpleIOMMUReq(val iovaBits: Int) extends Bundle {
  val iova = UInt(iovaBits.W)
  val write = Bool()
}

class SimpleIOMMUResp extends Bundle {
  val pa = UInt(64.W)
  val fault = Bool()
  val faultCode = UInt(8.W)
}

class SimpleIOMMUPtwReq extends Bundle {
  val addr = UInt(64.W)
}

class SimpleIOMMUPtwResp extends Bundle {
  val data = UInt(64.W)
  val fault = Bool()
}

class SimpleIOMMUIO(val params: SimpleIOMMUParams) extends Bundle {
  val req = Flipped(Decoupled(new SimpleIOMMUReq(params.iovaBits)))
  val resp = Decoupled(new SimpleIOMMUResp)
  val ptwReq = Decoupled(new SimpleIOMMUPtwReq)
  val ptwResp = Flipped(Decoupled(new SimpleIOMMUPtwResp))

  val enable = Input(Bool())
  val rootPtBase = Input(UInt(64.W))
  val flush = Input(Bool())
}

/**
  * Minimal single-request Sv39 IOMMU. It intentionally has no superpages,
  * ASIDs, A/D-bit updates, or multiple device domains.
  */
class SimpleIOMMU(val params: SimpleIOMMUParams) extends Module {
  require(params.iovaBits == 39, "SimpleIOMMU currently implements Sv39 IOVA decoding")
  require(params.pageBits == 12, "SimpleIOMMU currently supports 4 KiB pages")
  require(params.pteBytes == 8, "SimpleIOMMU currently supports 64-bit PTEs")
  require(isPow2(params.iotlbEntries), "SimpleIOMMU IOTLB entries must be a power of two")

  private val vpnBits = params.iovaBits - params.pageBits
  private val ppnBits = 64 - params.pageBits
  private val entries = params.iotlbEntries
  private val entryIdxBits = math.max(1, log2Ceil(entries))

  val io = IO(new SimpleIOMMUIO(params))

  val sIdle :: sWalkReq :: sWalkResp :: sResp :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val reqIova = Reg(UInt(params.iovaBits.W))
  val reqWrite = Reg(Bool())
  val walkLevel = Reg(UInt(2.W))
  val walkTableBase = Reg(UInt(64.W))
  val respPa = Reg(UInt(64.W))
  val respFault = Reg(Bool())
  val respFaultCode = Reg(UInt(8.W))

  val tlbValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val tlbVpn = Reg(Vec(entries, UInt(vpnBits.W)))
  val tlbPpn = Reg(Vec(entries, UInt(ppnBits.W)))
  val tlbRead = Reg(Vec(entries, Bool()))
  val tlbWrite = Reg(Vec(entries, Bool()))
  val replacement = RegInit(0.U(entryIdxBits.W))

  val lookupVpn = io.req.bits.iova(params.iovaBits - 1, params.pageBits)
  val hitVec = VecInit((0 until entries).map(i => tlbValid(i) && tlbVpn(i) === lookupVpn))
  val hit = hitVec.asUInt.orR
  val hitIdx = Wire(UInt(entryIdxBits.W))
  hitIdx := 0.U
  for (i <- 0 until entries) {
    when (hitVec(i)) { hitIdx := i.U }
  }

  io.req.ready := state === sIdle
  io.resp.valid := state === sResp
  io.resp.bits.pa := respPa
  io.resp.bits.fault := respFault
  io.resp.bits.faultCode := respFaultCode

  val ptwIndex = Wire(UInt(9.W))
  ptwIndex := 0.U
  when (walkLevel === 2.U) {
    ptwIndex := reqIova(38, 30)
  }.elsewhen (walkLevel === 1.U) {
    ptwIndex := reqIova(29, 21)
  }.otherwise {
    ptwIndex := reqIova(20, 12)
  }

  io.ptwReq.valid := state === sWalkReq
  io.ptwReq.bits.addr := walkTableBase + (ptwIndex << 3)
  io.ptwResp.ready := state === sWalkResp

  when (io.flush) {
    for (i <- 0 until entries) {
      tlbValid(i) := false.B
    }
    replacement := 0.U
  }

  when (io.req.fire) {
    reqIova := io.req.bits.iova
    reqWrite := io.req.bits.write
    respFault := false.B
    respFaultCode := 0.U

    when (!io.enable) {
      respPa := io.req.bits.iova
      state := sResp
    }.elsewhen (hit) {
      when ((io.req.bits.write && !tlbWrite(hitIdx)) ||
            (!io.req.bits.write && !tlbRead(hitIdx))) {
        respPa := 0.U
        respFault := true.B
        respFaultCode := 3.U // permission fault
      }.otherwise {
        respPa := Cat(tlbPpn(hitIdx), io.req.bits.iova(params.pageBits - 1, 0))
      }
      state := sResp
    }.otherwise {
      walkLevel := 2.U
      walkTableBase := io.rootPtBase
      state := sWalkReq
    }
  }

  when (io.ptwReq.fire) {
    state := sWalkResp
  }

  when (io.ptwResp.fire) {
    val pte = io.ptwResp.bits.data
    val valid = pte(0)
    val read = pte(1)
    val write = pte(2)
    val exec = pte(3)
    val leaf = read || write || exec
    val invalidPerm = write && !read

    when (io.ptwResp.bits.fault || !valid || invalidPerm) {
      respPa := 0.U
      respFault := true.B
      respFaultCode := Mux(io.ptwResp.bits.fault, 5.U, 1.U)
      state := sResp
    }.elsewhen (leaf) {
      when (walkLevel =/= 0.U) {
        respPa := 0.U
        respFault := true.B
        respFaultCode := 4.U // unsupported superpage
      }.elsewhen ((reqWrite && !write) || (!reqWrite && !read)) {
        respPa := 0.U
        respFault := true.B
        respFaultCode := 3.U // permission fault
      }.otherwise {
        respPa := Cat(pte(53, 10), reqIova(params.pageBits - 1, 0))
        tlbValid(replacement) := true.B
        tlbVpn(replacement) := reqIova(params.iovaBits - 1, params.pageBits)
        tlbPpn(replacement) := pte(53, 10)
        tlbRead(replacement) := read
        tlbWrite(replacement) := write
        replacement := replacement + 1.U
      }
      state := sResp
    }.elsewhen (walkLevel === 0.U) {
      respPa := 0.U
      respFault := true.B
      respFaultCode := 2.U // invalid leaf
      state := sResp
    }.otherwise {
      walkTableBase := Cat(pte(53, 10), 0.U(12.W))
      walkLevel := walkLevel - 1.U
      state := sWalkReq
    }
  }

  when (io.resp.fire) {
    state := sIdle
  }
}
