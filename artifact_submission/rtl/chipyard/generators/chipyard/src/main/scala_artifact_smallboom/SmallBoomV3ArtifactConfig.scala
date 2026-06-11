package chipyard

import org.chipsalliance.cde.config.Config

class SmallBoomV3Config extends Config(
  new boom.v3.common.WithBoomCommitLogPrintf ++
  new chipyard.config.WithCacheCrypto(enable = true) ++
  new freechips.rocketchip.subsystem.WithExtMemSbusBypass ++
  new boom.v3.common.WithNSmallBooms(1) ++
  new chipyard.config.AbstractConfig ++
  new chipyard.harness.WithSimBlockDevice ++
  new testchipip.iceblk.WithBlockDevice)
