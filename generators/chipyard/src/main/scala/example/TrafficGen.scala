package chipyard.example

import sys.process._

import chisel3._
import chisel3.util._
import chisel3.experimental.{IntParam, BaseModule}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.prci._
// import freechips.rocketchip.subsystem.{BaseSubsystem, PBUS, FBUS, SBUS}
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.{HasRegMap, RegField}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.UIntIsOneOf
import testchipip.util.{ClockedIO}


case class TrafficGenParams(
  address: BigInt = 0x5000,
  width: Int = 32,
  base: BigInt = 0x88000000L,
  size: BigInt = 500000000)

case object TrafficGenKey extends Field[Option[TrafficGenParams]](None)


// top-level signals of the TL module
class TrafficGenTopIO extends Bundle {
  // From Memory module to MMIO indicating ready status
  val memReady = Input(Bool())

  // from MMIO to Memory module to start operation
  val memStart = Output(Bool())
}

trait HasTrafficGenTopIO {
  def io: TrafficGenTopIO
}


class TrafficGenTL(params: TrafficGenParams, beatBytes: Int)(implicit p: Parameters) extends ClockSinkDomain(ClockSinkParameters())(p) {
  val device = new SimpleDevice("TrafficGenTL", Seq("ucbbar,TrafficGenTL")) 
  val node = TLRegisterNode(Seq(AddressSet(params.address, 4096-1)), device, "reg/control", beatBytes=beatBytes)

  override lazy val module = new TrafficGenImpl
  class TrafficGenImpl extends Impl with HasTrafficGenTopIO {
    val io = IO(new TrafficGenTopIO)
    withClockAndReset(clock, reset) {
      
      // MMIO registers for target
      val memReady = Wire(Bool())
      val memStart = RegInit(false.B)

      memReady := io.memReady
      io.memStart := memStart

      node.regmap(
        0x00 -> Seq(
          RegField.r(1, memReady)), // read-only register to read if Memory module is ready from the program
        0x04 -> Seq(
          RegField.w(1, memStart)) // write-only register to start Memory module from the program
      )
    }
  }
}

class TrafficGenMem(beatBytes: Int)(implicit p: Parameters) extends ClockSinkDomain(ClockSinkParameters())(p){
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLClientParameters(
    name = "trafficgenmem", sourceId = IdRange(0, 1))))))

  override lazy val module = new TrafficGenMemModuleImp(this)

  class TrafficGenMemModuleImp(outer: TrafficGenMem) extends Impl {
    val config = p(TrafficGenKey).get

    val io = IO(new Bundle{
      val memReady = Output(Bool())
      val memStart = Input(Bool())
    })   

    withClockAndReset(clock, reset) {

      // size (bytes) of a dummy tile that we will read/write to
      val tileBytes = 4096.U

      // cycles to wait between tile read and write
      val computeCycles = 10.U

      // mem connects to IO of this module, to actually send/recieve TL messages
      // edge represents edge of diplomacy graph and has methods for constructing TL messages and retrieving data from them
      val (mem, edge) = outer.node.out(0)
      val addrBits = edge.bundle.addressBits
      val blockBytes = p(CacheBlockBytes)

      val s_init :: s_read :: s_write :: s_chunk :: s_resp :: s_wait :: Nil = Enum(6)

      // state for FSM
      val state = RegInit(s_init)
      val prev_state = RegNext(state, s_init)

      // indicate if reading or writing
      val reading = RegInit(false.B)

      val addr = Reg(UInt(addrBits.W))
      val bytesLeft = RegInit(0.U(32.W))

      // buffers to hold read/write data
      val write_buffer  = RegInit(123.U(512.W))
      val read_buffer   = RegInit(0.U(512.W))

      // counter to keep track of how many bytes we have read or written
      val rw_bytes = RegInit(0.U(32.W))   

      // advance the address after this chunk of bytes are written
      addr := config.base.U + rw_bytes  

      // if we are in s_chunk state, it means we have a valid request for the memory 
      mem.a.valid := state === s_chunk          

      // Put writes data to memory, Get reads data from memory
      mem.a.bits := Mux(reading.asBool, 
                          edge.Get(fromSource = 0.U, toAddress = addr, lgSize = log2Ceil(blockBytes).U)._2,
                          edge.Put(fromSource = 0.U, toAddress = addr, lgSize = log2Ceil(blockBytes).U, data = write_buffer)._2)

      // we are ready to accept a response from the memory system after each block write
      mem.d.ready := state === s_resp               

      val waitCycles = RegInit(0.U(32.W))

      io.memReady := (state == s_init).asBool

      switch(state) {
        is(s_init) {

          rw_bytes := 0.U     
          reading := false.B

          when(io.memStart) {
            bytesLeft := tileBytes
            reading := true.B
            read_buffer := 0.U(512.W)
            state := s_chunk
          }
        }

        is(s_chunk) {
          // state to issue read or write requests in chunks of blockBytes
          when(edge.done(mem.a)) {
            rw_bytes := rw_bytes + blockBytes.U      
            bytesLeft := bytesLeft - blockBytes.U
            state := s_resp
          }
        }

        is(s_wait) {
            // when we have waited computeCycles, go to s_chunk to start writing
            when(waitCycles === 0.U) {
              state := s_chunk
            } .otherwise {
              waitCycles := waitCycles - 1.U
            }
        }

        is(s_resp) {
          when(mem.d.fire) {
            
            when(reading.asBool) {

              // when we have received all beats of AccessAckData, go to s_wait if that is the last chunk, or s_chunk if we have not read enough bytes yet 
              when (edge.done(mem.d)) {
                when (bytesLeft === 0.U) {
                  state := s_wait
                  rw_bytes := 0.U
                  bytesLeft := tileBytes
                  reading := false.B
                  waitCycles := computeCycles
                }.otherwise {
                  state := s_chunk
                }
              }.otherwise {
                state := s_resp
              }

            } .otherwise {

              // in a write, AccessAck response only takes 1 beat so we can determine what to do on the next cycle
              when (bytesLeft === 0.U) {
                state := s_init
              }.otherwise {
                state := s_chunk
              }

            }

          }
        }
      }
      
    }
  }

}

// this is a trait that instantiates the TL module defined above, and connects it to the TrafficGenMem module 
trait CanHaveTrafficGen { this: BaseSubsystem =>
  private val portName = "TrafficGenTL"

  // PeripheryBus used for MMIO
  private val pbus = locateTLBusWrapper(PBUS)

  // SystemBus used for L2 access
  private val sbus = locateTLBusWrapper(SBUS)


  p(TrafficGenKey).foreach { params =>

    // generate lazy module, which enables Diplomatic connections
    val trafficGenTL = LazyModule(new TrafficGenTL(params, pbus.beatBytes)(p))
    trafficGenTL.clockNode := pbus.fixedClockNode
    pbus.coupleTo(portName) { trafficGenTL.node := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _ }

    // instantiating the TrafficGenMem module
    val trafficGenMem = LazyModule(new TrafficGenMem(sbus.beatBytes)(p)) 
    trafficGenMem.clockNode := sbus.fixedClockNode
    sbus.coupleFrom("trafficgen-mem") { _ := trafficGenMem.node }

    InModuleBody {
      trafficGenMem.module.io.memStart := trafficGenTL.module.io.memStart
      trafficGenTL.module.io.memReady := trafficGenMem.module.io.memReady
    }

  }
}

// added this to TargetConfigs
class WithTrafficGen() extends Config((site, here, up) => {
  case TrafficGenKey => {
    Some(TrafficGenParams())
  }
})