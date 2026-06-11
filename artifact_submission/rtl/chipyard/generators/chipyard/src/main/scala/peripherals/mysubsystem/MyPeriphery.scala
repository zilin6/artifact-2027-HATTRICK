package chipyard.mysubsystem

import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.subsystem.{BaseSubsystem, MBUS,PBUS,FBUS}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._

import chipyard.peripherals.mydma._
import chipyard.peripherals.mydisk._

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

    // 2) DMA 数据口（TL client）从某条总线“发出去”
    //    这里示例用 fbus；你也可以换成 sbus/mbus，看你希望 DMA 访问谁更方便
    fbus.coupleFrom("my-dma-data") { _ := m.dmaNode }

    m
  }
}

/** 把 MyDisk 插进 BaseSubsystem：作为 TL manager，挂到 pbus（示例） */
trait CanHavePeripheryMyDisk { this: BaseSubsystem =>
  val mydisk = p(MyDiskKey).map { params =>
    val m = LazyModule(new MyDisk(params))

    val mbus = locateTLBusWrapper(MBUS)
    val pbus = locateTLBusWrapper(PBUS)
    val fbus = locateTLBusWrapper(FBUS)

    m.clockNode := pbus.fixedClockNode
    // Disk 管一段地址空间：挂到 pbus（也可考虑 mbus/sbus，后面你再决定）
    pbus.coupleTo("my-disk") { m.node := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _ }

    m
  }
}