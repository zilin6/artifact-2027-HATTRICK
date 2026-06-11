package chipyard.peripherals.mydisk

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.resources._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._   // ClockSinkDomain

case class MyDiskParams(base: BigInt = 0x20000000L, size: BigInt = 0x00100000L, beatBytes: Int = 8)
case object MyDiskKey extends Field[Option[MyDiskParams]](None)

class MyDisk(params: MyDiskParams)(implicit p: Parameters)
  extends ClockSinkDomain(ClockSinkParameters())(p) {

  private val device = new SimpleDevice("my-disk", Seq("tutorial,my-disk"))

  val node = TLManagerNode(Seq(TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v1(
      address            = Seq(AddressSet(params.base, params.size - 1)),
      resources          = device.reg,
      regionType         = RegionType.IDEMPOTENT,
      executable         = false,
      supportsGet        = TransferSizes(1, params.beatBytes),
      supportsPutFull    = TransferSizes(1, params.beatBytes),
      supportsPutPartial = TransferSizes(1, params.beatBytes),
      fifoId             = Some(0)
    )),
    beatBytes  = params.beatBytes,
    minLatency = 1
  )))

  // ★关键：module 必须是 LazyModuleImpLike
  override lazy val module = new Impl 
  {
    // ★关键：显式用该 Domain 的 clock/reset
    withClockAndReset(clock, reset) 
    {
      val (in, edge) = node.in(0)

      val beatBytes = params.beatBytes
      val beatBits  = beatBytes * 8
      val depth     = (params.size / beatBytes).toInt
      require((params.size % beatBytes) == 0, "MyDisk size must be multiple of beatBytes")

      val mem = SyncReadMem(depth, UInt(beatBits.W))

      // 地址 -> word index（以 beatBytes 为粒度）
      // disk 区域是 [base, base+size)
      val addrOff = in.a.bits.address - params.base.U
      val idx     = (addrOff >> log2Ceil(beatBytes)).asUInt

      // TL 类型判断
      val isGet    = in.a.bits.opcode === TLMessages.Get
      val isPutF   = in.a.bits.opcode === TLMessages.PutFullData
      val isPutP   = in.a.bits.opcode === TLMessages.PutPartialData
      val isPut    = isPutF || isPutP

      // PutPartial 的 mask：按 byte
    //   val wmask = in.a.bits.mask  // width=beatBytes

      // SyncReadMem 读：下一拍出数据
      val doRead = isGet && in.a.fire
      val rdata  = mem.read(idx, doRead)

      // 写入：PutFull/PutPartial
      // SyncReadMem 支持带 mask 的写（Vec[Bool]）
      when (in.a.fire && isPut) 
      {
        // val maskVec = VecInit(wmask.asBools) // beatBytes 个 bool
        mem.write(idx, in.a.bits.data)
      }

      // 处理响应：我们做成 1 拍响应（Get: ackdata，Put: ack）
      val a_fire = in.a.fire
      val a_reg  = RegEnable(in.a.bits, a_fire)

      val respValid = RegInit(false.B)
      respValid := a_fire

      in.a.ready := !respValid || in.d.ready  // 简单 backpressure（1-deep）

      in.d.valid := respValid
      val d = Wire(chiselTypeOf(in.d.bits))
       d := edge.AccessAck(a_reg)                 // 先生成一个标准 AccessAck 模板

       when (a_reg.opcode === TLMessages.Get) {
        d.opcode := TLMessages.AccessAckData     // ★改成带数据的响应
        d.data   := rdata                        // ★填数据
        }

        in.d.bits := d


      // 其余通道（TL-UH 不用）
      in.b.valid := false.B
      in.c.ready := true.B
      in.e.ready := true.B

      // 可选：打印（调试用，别长期开）
      when (a_fire) 
      {
        when (isPut) 
        {
          printf("[MyDisk] PUT idx=%d data=0x%x\n",idx,in.a.bits.data)
        }.elsewhen (isGet) {
          printf("[MyDisk] GET idx=%d\n",idx)
        }
      }
    }
  }
}
