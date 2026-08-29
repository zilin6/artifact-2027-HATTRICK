package chipyard.mysubsystem

import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.subsystem.{BaseSubsystem, PBUS, FBUS, SBUS}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._

import chipyard.peripherals.mydma._
import chipyard.peripherals.mydisk._
import chipyard.peripherals.iommu._

/** 把 MyDMA 插进 BaseSubsystem：ctrl 挂 PBUS；dma master 从 FBUS 发起（示例） */
trait CanHavePeripheryMyDMA { this: BaseSubsystem =>
  val mydma = p(MyDMAKey).map { params =>
    val m = LazyModule(new MyDMA(params))

    val mbus = locateTLBusWrapper(MBUS)
    val pbus = locateTLBusWrapper(PBUS)
    val fbus = locateTLBusWrapper(FBUS)

    m.clockNode := pbus.fixedClockNode
    // 1) 控制寄存器（MMIO manager）挂到 pbus
    pbus.coupleTo("my-dma-ctrl") { m.ctrlNode := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _ }

    // IOMMU control registers are present only in the IOMMU-enabled config.
    if (p(SimpleIOMMUKey).isDefined) {
      pbus.coupleTo("my-dma-iommu-ctrl") {
        m.iommuCtrlNode := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _
      }
    }

    // 2) DMA 数据口（TL client）从某条总线“发出去”
    //    这里示例用 fbus；你也可以换成 sbus/mbus，看你希望 DMA 访问谁更方便
    fbus.coupleFrom("my-dma-data") { _ := m.dmaNode }
    fbus.coupleFrom("my-dma-iommu-ptw") { _ := m.iommuPtwNode }

    m
  }
}

/** Attach the synthesizable pseudo-disk where both CPU and FBUS DMA masters can reach it. */
trait CanHavePeripheryMyDisk { this: BaseSubsystem =>
  val mydisk = p(MyDiskKey).map { params =>
    val m = LazyModule(new MyDisk(params))

    val sbus = locateTLBusWrapper(SBUS)
    m.clockNode := sbus.fixedClockNode
    sbus.coupleTo("my-pseudo-disk") {
      m.node := TLFragmenter(sbus.beatBytes, sbus.blockBytes) := _
    }

    m
  }
}
