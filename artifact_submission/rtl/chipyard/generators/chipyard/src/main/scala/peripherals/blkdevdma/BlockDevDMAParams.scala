package chipyard.peripherals.blkdevdma
import freechips.rocketchip.subsystem.{BaseSubsystem, CBUS, FBUS,PBUS, ResetSynchronous, SubsystemResetSchemeKey, TLBusWrapperLocation}
import org.chipsalliance.cde.config.Field

// 你可以按需加更多参数
case class BlockDevDMAParams(fifoDepth: Int = 32)

// 用 Option 是为了“可选实例化”：默认 None 表示不生成这个外设
case object BlockDevDMAKey extends Field[Option[BlockDevDMAParams]](None)

case class BlockDevDMAAttachParams(
  slaveWhere: TLBusWrapperLocation = PBUS,
  masterWhere: TLBusWrapperLocation = FBUS
)
case object BlockDevDMAAttachKey extends Field[BlockDevDMAAttachParams](BlockDevDMAAttachParams())
