package chipyard.peripherals.mydma

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.resources._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._   // ★新增：ClockSinkDomain / ClockSinkParameters

/** 参数：只做骨架，后面你再加（fifoDepth、burstBytes 等） */
case class MyDMAParams(ctrlAddress: BigInt = 0x10040000L, beatBytes: Int = 8)
case object MyDMAKey extends Field[Option[MyDMAParams]](None)

/** DMA：一个 TL client (发起搬运) + 一个 TLRegisterNode (控制寄存器) */
class MyDMA(params: MyDMAParams)(implicit p: Parameters)
  extends ClockSinkDomain(ClockSinkParameters())(p) {

  // 控制寄存器：MMIO manager
  private val device = new SimpleDevice("my-dma", Seq("tutorial,my-dma"))

  val ctrlNode = TLRegisterNode(
    address     = Seq(AddressSet(params.ctrlAddress, 0xFFF)),
    device      = device,
    beatBytes   = params.beatBytes,
    concurrency = 1
  )

  // DMA 数据口：TL client（master）
  val dmaNode = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1(
      name     = "my-dma-client",
      sourceId = IdRange(0, 1) // 单 outstanding：给 1 个 sourceId 就够
    ))
  )))

 override lazy val module = new Impl {
    withClockAndReset(clock, reset) {
      val src  = RegInit(0.U(64.W))
      val dst  = RegInit(0.U(64.W))
      val len  = RegInit(0.U(32.W))
      val cmd  = RegInit(0.U(32.W))

      // stat: bit0 done, bit1 busy, bit2 error
      val stat = RegInit(0.U(32.W))

      // 抓 cmd 写入（用于 start 触发）
      val cmdWriteFire = WireDefault(false.B)
      val cmdWriteData = WireDefault(0.U(32.W))

      ctrlNode.regmap(
        0x00 -> Seq(RegField(64, src)),
        0x08 -> Seq(RegField(64, dst)),
        0x10 -> Seq(RegField(32, len)),
        0x14 -> Seq(RegField.w(32, RegWriteFn { (valid, data) =>
          cmdWriteFire := valid
          cmdWriteData := data
          true.B
        })),
        0x18 -> Seq(RegField.r(32, stat)),
      )

      val (mem, edge) = dmaNode.out(0)
      val beatBytes   = edge.bundle.dataBits / 8

      // ----------------------------
      // 简单约束：len 必须按 beat 对齐
      // ----------------------------
      // 你不想在 elaboration 直接 require 也行，可以运行时检测置 error
      val lenAligned = (len & (beatBytes.U - 1.U)) === 0.U

      // ----------------------------
      // FSM（风格对齐 InitZero）
      // ----------------------------
      val s_idle :: s_read :: s_rresp :: s_write :: s_wresp :: s_done :: s_err :: Nil = Enum(7)
      val state = RegInit(s_idle)

      val curSrc     = Reg(UInt(64.W))
      val curDst     = Reg(UInt(64.W))
      val bytesLeft  = Reg(UInt(32.W))
      val readData   = Reg(UInt((beatBytes * 8).W))

      // 默认握手
      mem.a.valid := false.B
      mem.a.bits  := DontCare
      mem.d.ready := false.B

      // TL 请求构造：每次搬一个 beat
      val lgSize = log2Ceil(beatBytes).U

      // READ 请求：Get
      when (state === s_read) {
        mem.a.valid := true.B
        mem.a.bits  := edge.Get(
          fromSource = 0.U,
          toAddress  = curSrc,
          lgSize     = lgSize
        )._2
      }

      // 等待读响应
      when (state === s_rresp) {
        mem.d.ready := true.B
      }

      // WRITE 请求：PutFullData
      when (state === s_write) {
        mem.a.valid := true.B
        mem.a.bits  := edge.Put(
          fromSource = 0.U,
          toAddress  = curDst,
          lgSize     = lgSize,
          data       = readData
        )._2
      }

      // 等待写响应
      when (state === s_wresp) {
        mem.d.ready := true.B
      }

      // ----------------------------
      // 启动逻辑（写 CMD bit0）
      // ----------------------------
      val start = cmdWriteFire && cmdWriteData(0)

      when (start) {
        // latch cmd（可选）
        cmd := cmdWriteData

        // 若不对齐，进入 error
        when (!lenAligned) {
          stat := "b100".U // error=1
          state := s_err
          printf("[MyDMA] ERROR: len not aligned. len=%d beatBytes=%d\n",len,beatBytes.U)
        }.otherwise {
          curSrc    := src
          curDst    := dst
          bytesLeft := len
          stat      := "b010".U // busy=1
          state     := s_read
          printf("[MyDMA] START: src=0x%x dst=0x%x len=%d\n",src,dst,len)
        }
      }

      // ----------------------------
      // 状态推进（仿照 InitZero 的 done/fire 风格）
      // ----------------------------

      // 发出 read 请求成功（a handshake）
      when (mem.a.fire && state === s_read) {
        state := s_rresp
        printf("[MyDMA] GET sent: addr=0x%x\n",curSrc)
      }

      // 收到 read 响应
      when (mem.d.fire && state === s_rresp) {
        readData := mem.d.bits.data
        state := s_write
        printf("[MyDMA] GET resp: data=0x%x\n",mem.d.bits.data)
      }

      // 发出 write 请求成功
      when (mem.a.fire && state === s_write) {
        state := s_wresp
        printf("[MyDMA] PUT sent: addr=0x%x data=0x%x\n",curDst,readData)
      }

      // 收到 write 响应：完成一个 beat
      when (mem.d.fire && state === s_wresp) {
        val nextBytesLeft = bytesLeft - beatBytes.U
        bytesLeft := nextBytesLeft
        curSrc    := curSrc + beatBytes.U
        curDst    := curDst + beatBytes.U

        when (nextBytesLeft === 0.U) {
          state := s_done
          stat  := "b001".U // done=1
          printf("[MyDMA] DONE\n")
        }.otherwise {
          state := s_read
        }
      }

      // idle/done 行为：done 保持直到下一次 start（你也可以实现 W1C 清除）
      when (state === s_done) {
        // stay
      }
      when (state === s_err) {
        // stay
      }

      // 额外：状态变化 log（可选）
      val prev = RegNext(state)
      when (prev =/= state) {
        printf("[MyDMA] state %d -> %d, left=%d\n",prev,state,bytesLeft)
      }
    }
  }
}
