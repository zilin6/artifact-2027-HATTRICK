package chipyard

import chisel3._

import freechips.rocketchip.subsystem._
import freechips.rocketchip.system._
import org.chipsalliance.cde.config.Parameters

class DigitalTop(implicit p: Parameters) extends ChipyardSystem
  with testchipip.tsi.CanHavePeripheryUARTTSI
  with testchipip.boot.CanHavePeripheryCustomBootPin
  with testchipip.cosim.CanHaveTraceIO
  with testchipip.soc.CanHaveSubsystemInjectors
  with testchipip.soc.CanHaveSwitchableOffchipBus
  with testchipip.iceblk.CanHavePeripheryBlockDevice
  with testchipip.serdes.CanHavePeripheryTLSerial
  with testchipip.serdes.old.CanHavePeripheryTLSerial
  with testchipip.soc.CanHavePeripheryChipIdPin
  with sifive.blocks.devices.i2c.HasPeripheryI2C
  with sifive.blocks.devices.timer.HasPeripheryTimer
  with sifive.blocks.devices.pwm.HasPeripheryPWM
  with sifive.blocks.devices.uart.HasPeripheryUART
  with sifive.blocks.devices.gpio.HasPeripheryGPIO
  with sifive.blocks.devices.spi.HasPeripherySPIFlash
  with sifive.blocks.devices.spi.HasPeripherySPI
  with icenet.CanHavePeripheryIceNIC
  with chipyard.example.CanHavePeripheryGCD
  with chipyard.clocking.HasChipyardPRCI
  with chipyard.clocking.CanHaveClockTap
  with chipyard.mysubsystem.CanHavePeripheryMyDMA
  with chipyard.mysubsystem.CanHavePeripheryMyDisk
{
  override lazy val module = new DigitalTopModule(this)
}

class DigitalTopModule(l: DigitalTop) extends ChipyardSystemModule(l)
  with freechips.rocketchip.util.DontTouch
