package chipyard.peripherals.iommu

import org.chipsalliance.cde.config.Field

case class SimpleIOMMUParams(
  ctrlAddress: BigInt = 0x10041000L,
  iovaBits: Int = 39,
  pageBits: Int = 12,
  iotlbEntries: Int = 16,
  pteBytes: Int = 8,
  enableAtReset: Boolean = false
)

case object SimpleIOMMUKey extends Field[Option[SimpleIOMMUParams]](None)
