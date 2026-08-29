package chipyard

import org.chipsalliance.cde.config.Config
import chipyard.peripherals.mydisk._

class WithFPGAPseudoDisk extends Config((site, here, up) => {
  case MyDiskKey => Some(MyDiskParams(
    base = 0x20000000L,
    size = 64 * 1024,
    beatBytes = 8,
    outputRegister = true))
})

/** Small BOOM V3 demo config with synthesizable DMA, IOMMU, and BRAM pseudo-disk. */
class SmallBoomV3DmaDiskConfig extends Config(
  new chipyard.WithMyDMA ++
  new chipyard.WithSimpleIOMMU ++
  new chipyard.WithFPGAPseudoDisk ++
  new chipyard.config.WithCacheCrypto(enable = true) ++
  new freechips.rocketchip.subsystem.WithExtMemSbusBypass ++
  new boom.v3.common.WithNSmallBooms(1) ++
  new chipyard.config.AbstractConfig)
