package boom.v3.util   // 你如果不想分包，也可以改成 package chipyard 或 package boom

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._  // SimpleDevice（有些版本在 diplomacy 里，这个保守起见留着）
import freechips.rocketchip.subsystem._   // HasTileLinkLocations, PBUS, FBUS, MBUS, SBUS 等都在这
import freechips.rocketchip.tilelink._
// ---------------------------
// A minimal Block Device DMA
// ---------------------------
class BlockDevDMA(implicit p: Parameters) extends LazyModule {

  // Control plane: a register-mapped TL slave on pbus
  val ctrlNode = TLRegisterNode(
    address   = Seq(AddressSet(0x20000000L, 0xFFF)),
    device    = new SimpleDevice("blkdev-dma", Seq("tutorial,blkdev-dma")),
    beatBytes = 8
  )

  // Data plane: a TL master client on fbus (or mbus, depends on how you hook it)
  val dmaNode = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1(
      name     = "blkdev-dma-client",
      sourceId = IdRange(0, 16)
    ))
  )))

  lazy val module = new LazyModuleImp(this) {
    // ----- Control regs -----
    val srcAddr  = RegInit(0.U(64.W))
    val dstAddr  = RegInit(0.U(64.W))
    val byteCnt  = RegInit(0.U(32.W))
    val cmd      = RegInit(0.U(32.W))
    val status   = RegInit(0.U(32.W))

    // Export ctrl TL bundle
    val (ctrl, edgeCtrl) = ctrlNode.in(0)

    // Export dma TL bundle (master side)
    val (dma, edgeDma) = dmaNode.out(0)

    // ---- Regmap ----
    // 注意：RegFieldDesc 可选
    ctrlNode.regmap(
      0x00 -> Seq(RegField(64, srcAddr)),
      0x08 -> Seq(RegField(64, dstAddr)),
      0x10 -> Seq(RegField(32, byteCnt)),
      0x18 -> Seq(RegField(32, cmd, RegFieldDesc("CMD", "Command Register"))),
      0x20 -> Seq(RegField.r(32, status))
    )

    // ---- Minimal DMA behavior stub ----
    // 先把所有 master 通道默认置为不发请求，保证综合/编译通过
    dma.a.valid := false.B
    dma.a.bits  := DontCare

    dma.d.ready := true.B

    // 如果你后面要支持 Put/Get：
    // - 用 edgeDma.Get / edgeDma.Put 生成 a.bits
    // - 用状态机拉高 a.valid，握手后等待 d.valid
    // - 用 sourceId 管理 outstanding

    // 一个很简单的“启动即 busy”的占位逻辑（你可按需改）
    when (cmd =/= 0.U) {
      status := 1.U
    } .otherwise {
      status := 0.U
    }
  }
}

// --------------------------------------
// Hook trait: attach ctrl to pbus, data to fbus
// --------------------------------------
trait CanHaveBlockDevDMA { this: BaseSubsystem with HasTileLinkLocations =>
  val blkdevDma = LazyModule(new BlockDevDMA)

  private val pbus = locateTLBusWrapper(PBUS)
  private val fbus = locateTLBusWrapper(FBUS) // 如果你没有 FBUS，用 SBUS/MBUS，见下文

  // 1) 把 regmap 外设（TLRegisterNode）挂到 pbus
  pbus.coupleTo("blkdev-dma-ctrl") { bus =>
    // 常见写法：外设节点在左，bus 在右
    blkdevDma.ctrlNode := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := bus
  }

  // 2) 把 DMA master（TLClientNode）作为 master 接入 fbus（从 fbus “coupleFrom”）
  fbus.coupleFrom("blkdev-dma-data") { bus =>
    bus := TLBuffer() := blkdevDma.dmaNode
  }
}