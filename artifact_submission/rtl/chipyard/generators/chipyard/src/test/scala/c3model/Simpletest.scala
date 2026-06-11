package chipyard.c3model

import chipyard._
import org.chipsalliance.cde.config.{Config,Parameters}
import chisel3._
import chisel3.util._
import chiseltest._

import org.scalatest.flatspec.AnyFlatSpec

class c3modelConfig extends Config(
  new boom.v3.common.WithNSmallBooms(1) ++ 
  new chipyard.config.AbstractConfig  // 包含默认的系统级配置
)


class SmallBoomV3Config extends Config(
  new boom.v3.common.WithNSmallBooms(1) ++                          // small boom config
  new chipyard.config.AbstractConfig)


class MyBoomTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "MyBoom"

  it should "run custom asm" in {

  val p: Parameters = new c3modelConfig().toInstance
  val lazyDigitalTop = LazyModule(new DigitalTop()(p))
  val digitalTopModule = lazyDigitalTop.module
  test(digitalTopModule).withAnnotations(Seq(WriteVcdAnnotation))  { c =>
      // 初始化 memory
      //c.clock.setTimeout(1000)

      // reset操作
      //c.reset.poke(true.B)
      println("Reset is asserted.")
      //c.clock.step(1)
      //c.reset.poke(false.B)
      println("Reset is deasserted.")
      //c.clock.setTimeout(1000)

      // 写入 ld sd addi 汇编指令到 memory（poke memory interface）

      // reset
      //c.reset.poke(true.B)
      //c.clock.step(1)
      //c.reset.poke(false.B)

      // 一步一步执行
      //for (_ <- 0 until 10) {
        // 打印状态（peek rob, sbuffer, lsu, …）
      //  c.clock.step(1)
      //}
    }
  }
}