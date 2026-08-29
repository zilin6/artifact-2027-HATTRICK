/*
 * Copyright 2019 SiFive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You should have received a copy of LICENSE.Apache2 along with
 * this software. If not, you may obtain a copy at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.PlusArg

class SinkEResponse(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val sink = UInt(params.inner.bundle.sinkBits.W)
}

class SinkE(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val resp = Valid(new SinkEResponse(params))
    val e = Flipped(Decoupled(new TLBundleE(params.inner.bundle)))
  })
  val debugLogEnable = PlusArg("inclusive_cache_debug_log", default = 0, width = 1) =/= 0.U

  if (params.firstLevel) {
    // Tie off unused ports
    io.resp.valid := false.B
    io.resp.bits := DontCare
    io.e.ready := true.B
  } else {
    // No restrictions on buffer
    val e = params.micro.innerBuf.e(io.e)

    e.ready := true.B
    io.resp.valid := e.valid
    io.resp.bits.sink := e.bits.sink
    when (debugLogEnable && e.valid) {
      printf(p"[SINKE-TRACE] sink=0x${Hexadecimal(e.bits.sink)} ready=${e.ready} fire=${e.fire}\n")
    }
  }
}
