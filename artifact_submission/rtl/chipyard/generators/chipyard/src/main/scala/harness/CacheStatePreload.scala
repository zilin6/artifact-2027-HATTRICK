package chipyard.harness

import chisel3._
import chisel3.experimental.{IntParam, StringParam}
import chisel3.util.experimental.BoringUtils
import chisel3.util.{Enum, HasBlackBoxResource, switch, is}

import freechips.rocketchip.rocket.{
  CacheCryptoBasePreloadBoringNames,
  CacheStatePreloadBoringNames,
  CacheStatePreloadFields,
  CacheStatePreloadTarget
}
import freechips.rocketchip.util.PlusArg

class CachePreloadFileMem(plusarg: String, addrBits: Int = 32)
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

  addResource("/chipyard/harness/CachePreloadFileMem.sv")
  addResource("/testchipip/csrc/plusarg_file_mem.cc")
  addResource("/testchipip/csrc/plusarg_file_mem.h")
}

class CacheStatePreloadController extends Module {
  val io = IO(new Bundle {})

  val l1Ready = WireDefault(false.B)
  val l2Ready = WireDefault(false.B)
  val ackL1 = WireDefault(false.B)
  val ackL2 = WireDefault(false.B)
  BoringUtils.addSink(l1Ready, CacheStatePreloadBoringNames.ReadyL1)
  BoringUtils.addSink(l2Ready, CacheStatePreloadBoringNames.ReadyL2)
  BoringUtils.addSink(ackL1, CacheStatePreloadBoringNames.AckL1)
  BoringUtils.addSink(ackL2, CacheStatePreloadBoringNames.AckL2)

  val mem = Module(new CachePreloadFileMem("cachepreload"))
  mem.io.clock := clock
  mem.io.reset := reset.asBool

  val sIdle :: sHeaderReq :: sHeaderResp :: sPayloadReq :: sPayloadResp :: sIssue :: Nil = Enum(6)
  val state = RegInit(sIdle)
  val cursor = RegInit(0.U(32.W))
  val headerReg = RegInit(0.U(64.W))
  val payloadReg = RegInit(0.U(64.W))

  val preloadActive = state =/= sIdle
  val preloadValid = state === sIssue
  val preloadActiveSource = dontTouch(WireInit(preloadActive))
  val preloadValidSource = dontTouch(WireInit(preloadValid))
  val preloadHeaderSource = dontTouch(WireInit(headerReg))
  val preloadPayloadSource = dontTouch(WireInit(payloadReg))

  BoringUtils.addSource(preloadActiveSource, CacheStatePreloadBoringNames.Active)
  BoringUtils.addSource(preloadValidSource, CacheStatePreloadBoringNames.Valid)
  BoringUtils.addSource(preloadHeaderSource, CacheStatePreloadBoringNames.Header)
  BoringUtils.addSource(preloadPayloadSource, CacheStatePreloadBoringNames.Payload)

  val target = CacheStatePreloadFields.target(headerReg)
  val expectedAck = Mux(CacheStatePreloadFields.targetsL1(target), ackL1, ackL2)

  mem.io.mem_req_valid := false.B
  mem.io.mem_req_addr := 0.U
  mem.io.mem_req_data := 0.U
  mem.io.mem_req_r_wb := true.B

  switch(state) {
    is(sIdle) {
      when (mem.io.mem_present && l1Ready && l2Ready) {
        state := sHeaderReq
      }
    }

    is(sHeaderReq) {
      mem.io.mem_req_valid := true.B
      mem.io.mem_req_addr := cursor
      when (true.B) {
        state := sHeaderResp
      }
    }

    is(sHeaderResp) {
      headerReg := mem.io.mem_resp_data
      state := sPayloadReq
    }

    is(sPayloadReq) {
      mem.io.mem_req_valid := true.B
      mem.io.mem_req_addr := cursor + CacheStatePreloadFields.HeaderBytes.U
      when (true.B) {
        state := sPayloadResp
      }
    }

    is(sPayloadResp) {
      payloadReg := mem.io.mem_resp_data
      when (CacheStatePreloadFields.target(headerReg) === CacheStatePreloadTarget.End) {
        state := sIdle
      } .otherwise {
        state := sIssue
      }
    }

    is(sIssue) {
      when (expectedAck) {
        cursor := cursor + CacheStatePreloadFields.RecordBytes.U
        state := sHeaderReq
      }
    }
  }
}

class CacheCryptoBasePreloadController extends Module {
  val io = IO(new Bundle {})

  val enable = PlusArg(
    "cache_crypto_base_preload_enable",
    width = 1,
    docstring = "Enable one-shot cache-crypto counter-base preload before cache state preload starts")
  val value = PlusArg(
    "cache_crypto_base_preload_value",
    width = 64,
    docstring = "64-bit value to preload into cache-crypto counter-base registers before cache preload")

  val sInit :: sPulse :: sDone :: Nil = Enum(3)
  val state = RegInit(sInit)

  switch(state) {
    is(sInit) {
      state := Mux(enable.orR, sPulse, sDone)
    }
    is(sPulse) {
      state := sDone
    }
    is(sDone) {
      state := sDone
    }
  }

  val wenSource = dontTouch(WireInit(state === sPulse))
  val valueSource = dontTouch(WireInit(value))
  val doneSource = dontTouch(WireInit(state === sDone))

  BoringUtils.addSource(valueSource, CacheCryptoBasePreloadBoringNames.Value)
  BoringUtils.addSource(wenSource, CacheCryptoBasePreloadBoringNames.Wen)
  BoringUtils.addSource(doneSource, CacheCryptoBasePreloadBoringNames.Done)
}
