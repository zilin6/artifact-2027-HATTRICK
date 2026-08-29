package chipyard.peripherals.mydisk

import chisel3.util.isPow2
import org.chipsalliance.cde.config.{Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.prci.{ClockSinkDomain, ClockSinkParameters}
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.tilelink.TLRAM

case class MyDiskParams(
  base: BigInt = 0x20000000L,
  size: BigInt = 64 * 1024,
  beatBytes: Int = 8,
  outputRegister: Boolean = true)

case object MyDiskKey extends Field[Option[MyDiskParams]](None)

/** A volatile, synthesizable TileLink pseudo-disk backed by inferred SRAM/BRAM. */
class MyDisk(params: MyDiskParams)(implicit p: Parameters)
  extends ClockSinkDomain(ClockSinkParameters())(p) {

  require(params.size >= params.beatBytes, "MyDisk size must contain at least one beat")
  require(isPow2(params.size), "MyDisk size must be a power of two")
  require(isPow2(params.beatBytes), "MyDisk beatBytes must be a power of two")
  require((params.base & (params.size - 1)) == 0, "MyDisk base must be aligned to its size")

  private val device = new SimpleDevice("my-pseudo-disk", Seq("chipyard,my-pseudo-disk"))
  private val ram = LazyModule(new TLRAM(
    address = AddressSet(params.base, params.size - 1),
    cacheable = false,
    executable = false,
    beatBytes = params.beatBytes,
    sramReg = params.outputRegister,
    devOverride = Some(device)))

  val node = ram.node

  override lazy val desiredName = "MyDisk"
}
