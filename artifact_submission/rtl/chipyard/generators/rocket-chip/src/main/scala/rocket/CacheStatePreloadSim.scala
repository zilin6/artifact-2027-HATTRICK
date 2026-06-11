package freechips.rocketchip.rocket

import chisel3._
import chisel3.experimental.{IntParam, StringParam}
import chisel3.util.{Enum, HasBlackBoxResource, is, switch}

class CacheStatePreloadFileMem(plusarg: String, addrBits: Int = 32)
    extends BlackBox(Map(
      "PLUSARG" -> StringParam(plusarg),
      "ADDR_BITS" -> IntParam(addrBits),
      "DATA_BYTES" -> IntParam(8)))
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val mem_present = Output(Bool())
    val mem_req_valid = Input(Bool())
    val mem_req_addr = Input(UInt(addrBits.W))
    val mem_req_data = Input(UInt(64.W))
    val mem_req_r_wb = Input(Bool())
    val mem_resp_data = Output(UInt(64.W))
  })

  addResource("/rocket/CacheStatePreloadFileMem.sv")
  addResource("/testchipip/csrc/plusarg_file_mem.cc")
  addResource("/testchipip/csrc/plusarg_file_mem.h")
}

class CacheStatePreloadSequencer(plusarg: String) extends Module {
  val io = IO(new Bundle {
    val readyForStart = Input(Bool())
    val cmdAck = Input(Bool())
    val hold = Output(Bool())
    val active = Output(Bool())
    val valid = Output(Bool())
    val header = Output(UInt(64.W))
    val payload = Output(UInt(64.W))
  })

  val mem = Module(new CacheStatePreloadFileMem(plusarg))
  mem.io.clock := clock
  mem.io.reset := reset.asBool

  val sIdle :: sHeaderReq :: sHeaderResp :: sPayloadReq :: sPayloadResp :: sIssue :: Nil = Enum(6)
  val state = RegInit(sIdle)
  val cursor = RegInit(0.U(32.W))
  val headerReg = RegInit(0.U(64.W))
  val payloadReg = RegInit(0.U(64.W))
  val preloadDone = RegInit(false.B)

  mem.io.mem_req_valid := false.B
  mem.io.mem_req_addr := 0.U
  mem.io.mem_req_data := 0.U
  mem.io.mem_req_r_wb := true.B

  switch(state) {
    is(sIdle) {
      when (mem.io.mem_present && !preloadDone && io.readyForStart) {
        state := sHeaderReq
      }
    }
    is(sHeaderReq) {
      mem.io.mem_req_valid := true.B
      mem.io.mem_req_addr := cursor
      state := sHeaderResp
    }
    is(sHeaderResp) {
      headerReg := mem.io.mem_resp_data
      state := sPayloadReq
    }
    is(sPayloadReq) {
      mem.io.mem_req_valid := true.B
      mem.io.mem_req_addr := cursor + CacheStatePreloadFields.HeaderBytes.U
      state := sPayloadResp
    }
    is(sPayloadResp) {
      payloadReg := mem.io.mem_resp_data
      when (CacheStatePreloadFields.target(headerReg) === CacheStatePreloadTarget.End) {
        preloadDone := true.B
        state := sIdle
      } .otherwise {
        state := sIssue
      }
    }
    is(sIssue) {
      when (io.cmdAck) {
        cursor := cursor + CacheStatePreloadFields.RecordBytes.U
        state := sHeaderReq
      }
    }
  }

  io.hold := mem.io.mem_present && !preloadDone
  io.active := state =/= sIdle
  io.valid := state === sIssue
  io.header := headerReg
  io.payload := payloadReg
}
