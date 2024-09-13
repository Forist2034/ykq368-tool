package rf_tool.fpga.transceiver.cc1101

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

class Cc1101Spi(val spi_freq: HertzNumber) extends Component {
  val io = new Bundle {
    // start transfer
    val start = in(Bool())
    val write = slave(Stream(Bits(8 bits)))
    val read = master(Stream(Bits(8 bits)))
    val started = out(Bool())
    val cs = out(Bool())
    val sclk = out(Bool())
    val si = out(Bool())
    val so = in(Bool())
  }

  val run = RegInit(False)
  val bit_count = Reg(UInt(3 bits)) init (0)
  val output_reg = Reg(Bits(8 bits))
  val input_reg = Reg(Bits(7 bits))

  val counter_cycles = (ClockDomain.current.frequency.getValue / spi_freq).toInt
  val counter_val = counter_cycles - 1
  val counter = Reg(UInt(log2Up(counter_cycles) bits)) init (0)

  val cs_high_wait = new Area {
    val need_timer = (20 ns) > ClockDomain.current.frequency.getValue.toTime
    val timer = if (need_timer) { Timeout(20 ns) }
    else { null }

    def clear() = if (need_timer) { timer.clear() }
    else {}

    def timeout: Bool = if (need_timer) { timer }
    else { True }
  } // wait before cs goes high

  val fsm = new StateMachine {
    val cs_high: State = makeInstantEntry().whenIsActive {
      io.cs := True
      io.read.valid := False
      io.write.ready := False
      when(io.start) {
        goto(wait_so)
      }
    }
    val wait_so = new State {
      whenIsActive {
        io.cs := False
        io.read.valid := False
        when(!io.so) {
          io.write.ready := True
          when(io.write.valid) {
            goto(transfer)
          } otherwise {
            goto(ready)
          }
        } otherwise {
          io.write.ready := False
        }
      }
    }

    val transfer: State = new State {
      onEntry {
        counter := counter_val
        bit_count := 7
        output_reg := io.write.payload
      }
      whenIsActive {
        io.cs := False
        io.read.valid := False
        io.write.ready := False

        when(counter =/= 0) {
          counter := counter - 1
        } elsewhen (bit_count =/= 0) {
          bit_count := bit_count - 1
          counter := counter_val

          input_reg := input_reg(0, 6 bits) ## io.so
          output_reg := output_reg |<< 1
        } otherwise {
          io.read.valid := True
          when(io.read.ready) {
            when(io.start) {
              io.write.ready := True
              when(io.write.valid) {
                counter := counter_val
                bit_count := 7
                output_reg := io.write.payload
              } otherwise {
                goto(ready)
              }
            } otherwise {
              io.write.ready := False
              cs_high_wait.clear()
              goto(stopping)
            }
          }
        }
      }
    }

    val ready = new State {
      onEntry {
        cs_high_wait.clear()
      }
      whenIsActive {
        io.cs := False
        io.read.valid := False
        when(io.start) {
          io.write.ready := True
          when(io.write.valid) {
            goto(transfer)
          }
        } otherwise {
          io.write.ready := False
          goto(stopping)
        }
      }
    }

    val stopping = new State {
      whenIsActive {
        io.cs := False
        io.read.valid := False
        io.write.ready := False
        when(cs_high_wait.timeout) {
          goto(cs_high)
        }
      }
    }
  }

  io.read.payload := input_reg ## io.so
  io.started := !fsm.isActive(fsm.cs_high)
  io.si := fsm.isActive(fsm.transfer) && output_reg.msb
  io.sclk := fsm.isActive(fsm.transfer) && counter < (counter_cycles / 2)
}

case class Cc1101() extends Bundle with IMasterSlave {
  val sclk = Bool()
  val gdo1 = io.TriState(Bool())
  val gdo2 = io.TriState(Bool())
  val gdo0 = io.TriState(Bool())
  val cs = Bool()
  val si = Bool()

  override def asMaster(): Unit = {
    out(cs, sclk, si)
    master(gdo0, gdo1, gdo2)
  }
}
