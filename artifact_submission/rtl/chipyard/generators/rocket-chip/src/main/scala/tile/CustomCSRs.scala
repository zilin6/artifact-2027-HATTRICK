// See LICENSE.SiFive for license details.

package freechips.rocketchip.tile

import chisel3._

import org.chipsalliance.cde.config.Parameters

case class CustomCSR(id: Int, mask: BigInt, init: Option[BigInt])

object CustomCSR {
  def constant(id: Int, value: BigInt): CustomCSR = CustomCSR(id, BigInt(0), Some(value))
}

class CustomCSRIO(implicit p: Parameters) extends CoreBundle {
  val ren = Output(Bool())          // set by CSRFile, indicates an instruction is reading the CSR
  val wen = Output(Bool())          // set by CSRFile, indicates an instruction is writing the CSR
  val wdata = Output(UInt(xLen.W))  // wdata provided by instruction writing CSR
  val value = Output(UInt(xLen.W))  // current value of CSR in CSRFile

  val stall = Input(Bool())         // reads and writes to this CSR should stall (must be bounded)

  val set = Input(Bool())           // set/sdata enables external agents to set the value of this CSR
  val sdata = Input(UInt(xLen.W))
}

class CustomCSRs(implicit p: Parameters) extends CoreBundle 
{
  // Not all cores have these CSRs, but those that do should follow the same
  // numbering conventions.  So we list them here but default them to None.
  protected def bpmCSRId = 0x7c0
  protected def bpmCSR: Option[CustomCSR] = None

  protected def chickenCSRId = 0x7c1
  protected def chickenCSR: Option[CustomCSR] = None

  protected def cus_reg_addr_mode_Id: Int = 0x3f0
  protected def cus_reg_addr_mode: Option[CustomCSR] = Some(CustomCSR(cus_reg_addr_mode_Id, 0x7, Some(0)))

  protected def cus_reg_data_mode_Id: Int = 0x3f1
  protected def cus_reg_data_mode: Option[CustomCSR] = Some(CustomCSR(cus_reg_data_mode_Id, 0x7, Some(0)))

  protected def cus_reg_counter_base_Id: Int = 0x3f3
  protected def cus_reg_counter_base: Option[CustomCSR] = Some(CustomCSR(cus_reg_counter_base_Id, BigInt("ffffffffffffffff", 16), Some(0)))

  // If you override this, you'll want to concatenate super.decls
  def decls: Seq[CustomCSR] = cus_reg_addr_mode.toSeq ++ cus_reg_data_mode.toSeq ++ cus_reg_counter_base

  val csrs = Vec(decls.size, new CustomCSRIO)

  def flushBTB = getOrElse(bpmCSR, _.wen, false.B)
  def bpmStatic = getOrElse(bpmCSR, _.value(0), false.B)
  def disableDCacheClockGate = getOrElse(chickenCSR, _.value(0), false.B)
  def disableICacheClockGate = getOrElse(chickenCSR, _.value(1), false.B)
  def disableCoreClockGate = getOrElse(chickenCSR, _.value(2), false.B)
  def disableSpeculativeICacheRefill = getOrElse(chickenCSR, _.value(3), false.B)
  def suppressCorruptOnGrantData = getOrElse(chickenCSR, _.value(9), false.B)
  private def decodeAddrCryptoLoadEnable(value: UInt): Bool = value(0)
  private def decodeAddrCryptoStoreEnable(value: UInt): Bool = value(1)
  private def decodeFetchAddrCryptoEnable(value: UInt): Bool = value(2)
  private def decodeCacheCryptoLoadEnable(value: UInt): Bool = value(0)
  private def decodeCacheCryptoStoreEnable(value: UInt): Bool = value(1)
  private def decodeICacheCryptoEnable(value: UInt): Bool = value(2)

  def addrCryptoLoadEnableCurrent =
    getOrElse(cus_reg_addr_mode, c => decodeAddrCryptoLoadEnable(Mux(c.wen, c.wdata, c.value)), false.B)
  def addrCryptoStoreEnableCurrent =
    getOrElse(cus_reg_addr_mode, c => decodeAddrCryptoStoreEnable(Mux(c.wen, c.wdata, c.value)), false.B)
  def fetchAddrCryptoEnableCurrent =
    getOrElse(cus_reg_addr_mode, c => decodeFetchAddrCryptoEnable(Mux(c.wen, c.wdata, c.value)), false.B)

  def cacheCryptoEnableCSR =
    getOrElse(cus_reg_data_mode, c => decodeCacheCryptoLoadEnable(c.value) || decodeCacheCryptoStoreEnable(c.value), false.B)
  def cacheCryptoLoadEnableCSR =
    getOrElse(cus_reg_data_mode, c => decodeCacheCryptoLoadEnable(c.value), false.B)
  def cacheCryptoStoreEnableCSR =
    getOrElse(cus_reg_data_mode, c => decodeCacheCryptoStoreEnable(c.value), false.B)

  def cacheCryptoEnableCurrent = getOrElse(cus_reg_data_mode, c => {
    val value = Mux(c.wen, c.wdata, c.value)
    decodeCacheCryptoLoadEnable(value) || decodeCacheCryptoStoreEnable(value)
  }, false.B)
  def cacheCryptoLoadEnableCurrent =
    getOrElse(cus_reg_data_mode, c => decodeCacheCryptoLoadEnable(Mux(c.wen, c.wdata, c.value)), false.B)
  def cacheCryptoStoreEnableCurrent =
    getOrElse(cus_reg_data_mode, c => decodeCacheCryptoStoreEnable(Mux(c.wen, c.wdata, c.value)), false.B)
  def cacheCryptoEnableCSRWen = getOrElse(cus_reg_data_mode, _.wen, false.B)
  def cacheCryptoCounterBaseCSR = getOrElse(cus_reg_counter_base, _.value, 0.U)
  def cacheCryptoCounterBaseCurrent = getOrElse(cus_reg_counter_base, c => Mux(c.wen, c.wdata, c.value), 0.U)
  def cacheCryptoCounterBaseCSRWen = getOrElse(cus_reg_counter_base, _.wen, false.B)
  def iCacheCryptoEnableCSR =
    getOrElse(cus_reg_data_mode, c => decodeICacheCryptoEnable(c.value), false.B)
  def iCacheCryptoEnableCurrent =
    getOrElse(cus_reg_data_mode, c => decodeICacheCryptoEnable(Mux(c.wen, c.wdata, c.value)), false.B)
  def iCacheCryptoEnableCSRWen = getOrElse(cus_reg_data_mode, _.wen, false.B)

  protected def getByIdOrElse[T](id: Int, f: CustomCSRIO => T, alt: T): T = {
    val idx = decls.indexWhere(_.id == id)
    if (idx < 0) alt else f(csrs(idx))
  }

  def printCSRs(): Unit = {
    printf("Number of CSRs: %d\n", decls.size.U)
    csrs.zipWithIndex.foreach { case (csr, index) =>
      printf("CSR[%d] value: 0x%x\n", index.U, csr.value)
    }
  }

  protected def getOrElse[T](csr: Option[CustomCSR], f: CustomCSRIO => T, alt: T): T =
    csr.map(c => getByIdOrElse(c.id, f, alt)).getOrElse(alt)
}
