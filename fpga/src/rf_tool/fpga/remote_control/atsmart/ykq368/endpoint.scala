package rf_tool.fpga.remote_control.atsmart.ykq368

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import rf_tool.fpga.encoder

case class PwmTiming(
    period: TimeNumber,
    high0: TimeNumber,
    high1: TimeNumber
)
case class PwmData(val width: BitCount, val has_skip: Boolean) extends Bundle {
  val data = Bits(width)
  val skip = if (has_skip) { UInt(log2Up(width.value) bits) }
  else { null }
}
class PwmEncoder(
    val config: PwmTiming,
    val width: BitCount,
    val has_skip: Boolean
) extends Component {
  val io = new Bundle {
    val data = slave(Stream(PwmData(width, has_skip)))
    val output = out Bool ()
  }

  val (counter_bits, counter_val, bit0_high, bit1_high) = {
    val clock_time = ClockDomain.current.frequency.getValue.toTime
    val clock_cycles = config.period / clock_time
    val counter_val = clock_cycles - 1
    (
      log2Up(clock_cycles.toInt) bits,
      counter_val.toInt,
      (counter_val - config.high0 / clock_time).toInt,
      (counter_val - config.high1 / clock_time).toInt
    )
  }

  val bitCnt = Reg(UInt(log2Up(width.value) bits)) init (0)
  val run = Reg(Bool()) init (False)
  val shift_reg = Reg(Bits(width))
  val counter = Reg(UInt(counter_bits)) init (0)

  when(counter =/= 0) {
    io.data.ready := False

    counter := counter - 1
  } elsewhen (bitCnt =/= 0) {
    io.data.ready := False

    counter := counter_val
    shift_reg := shift_reg |<< 1
    bitCnt := bitCnt - 1
  } otherwise {
    io.data.ready := True

    when(io.data.valid) {
      run := True
      counter := counter_val
      bitCnt := (if (has_skip) { (width.value - 1) - io.data.payload.skip }
                 else { U(width.value - 1) })
      shift_reg := io.data.payload.data
    } otherwise {
      run := False
    }
  }

  io.output := run && (counter > (shift_reg.msb ? U(bit1_high) | U(bit0_high)))
}

case class InstrConfig(
    preamble_timing: PwmTiming = PwmTiming(
      period = 1 ms,
      high0 = 470 us,
      high1 = 200 us
    ),
    preamble_wait: TimeNumber = 6700 us,
    data_timing: PwmTiming = PwmTiming(
      period = 1 ms,
      high0 = 700 us,
      high1 = 200 us
    ),
    data_wait: TimeNumber = 6700 us
)

case class Instr() extends Bundle {
  val send_preamble = Bool()
  val send_data = Bool()
  // skip k bits in last data
  val skip = UInt(5 bits)
  val preamble = Bits(13 bits)
  val data = Bits(35 bits)
  val repeat = UInt(8 bits)
}

class InstrProc(val config: InstrConfig) extends Component {
  val io = new Bundle {
    val instr = slave(Stream(Instr()))
    val output = out(Bool())
  }

  val send_data = Reg(Bool())
  val skip = Reg(UInt(5 bits))
  val preamble = Reg(Bits(13 bits))
  val data = Reg(Bits(35 bits))
  val repeat = Reg(UInt(8 bits))

  val preamble_encoder = new PwmEncoder(config.preamble_timing, 13 bits, false)
  preamble_encoder.io.data.payload.data := preamble

  // output is 33/32 bits alternating
  val data_33bits = RegInit(True)

  val data_encoder = new PwmEncoder(config.data_timing, 35 bits, true)
  data_encoder.io.data.payload.data := data
  data_encoder.io.data.payload.skip :=
    ((repeat === 0) ? skip.expand | 0) + (data_33bits ? U(2) | U(0))

  val preamble_wait_cycle =
    config.preamble_wait / ClockDomain.current.frequency.getValue.toTime
  val preamble_counter_val = preamble_wait_cycle.toInt - 1
  val data_wait_cycle =
    config.data_wait / ClockDomain.current.frequency.getValue.toTime
  val data_counter_val = data_wait_cycle.toInt - 1

  val wait_counter = Reg(
    UInt(
      log2Up(
        scala.math.max(preamble_wait_cycle.toInt, data_wait_cycle.toInt)
      ) bits
    )
  ) init (0)

  val fsm = new StateMachine {
    val idle = makeInstantEntry().whenIsActive {
      io.output := False

      when(io.instr.valid) {
        when(
          io.instr.payload.repeat =/= 0 && (io.instr.payload.send_preamble || io.instr.payload.send_data)
        ) {
          send_data := io.instr.payload.send_data
          skip := io.instr.payload.skip
          preamble := io.instr.payload.preamble
          data_33bits := True
          data := io.instr.payload.data
          repeat := io.instr.payload.repeat - 1

          when(io.instr.payload.send_preamble) {
            goto(sending_preamble)
          } otherwise {
            goto(sending_data)
          }
        }
      }
    }

    val sending_preamble: State = new State {
      whenIsActive {
        io.output := preamble_encoder.io.output

        when(preamble_encoder.io.data.ready) {
          when(send_data) {
            goto(waiting_preamble)
          } elsewhen (repeat =/= 0) {
            repeat := repeat - 1
            goto(waiting_preamble)
          } otherwise {
            goto(finish_preamble)
          }
        }
      }
    }
    val waiting_preamble = new State {
      onEntry {
        wait_counter := preamble_counter_val
      }
      whenIsActive {
        io.output := preamble_encoder.io.output

        when(preamble_encoder.io.data.ready) {
          when(wait_counter =/= 0) {
            wait_counter := wait_counter - 1
          } otherwise {
            when(send_data) {
              goto(sending_data)
            } otherwise { // preamble only
              goto(sending_preamble)
            }
          }
        }
      }
    }
    val finish_preamble = new State {
      whenIsActive {
        io.output := preamble_encoder.io.output

        when(preamble_encoder.io.data.ready) {
          goto(idle)
        }
      }
    }

    val sending_data: State = new State {
      whenIsActive {
        io.output := data_encoder.io.output

        when(data_encoder.io.data.ready) {
          when(repeat =/= 0) {
            repeat := repeat - 1
            data_33bits := !data_33bits
            goto(waiting_data)
          } otherwise {
            goto(finish_data)
          }
        }
      }
    }
    val waiting_data = new State {
      onEntry {
        wait_counter := data_counter_val
      }
      whenIsActive {
        io.output := data_encoder.io.output

        when(data_encoder.io.data.ready) {
          when(wait_counter =/= 0) {
            wait_counter := wait_counter - 1
          } otherwise {
            goto(sending_data)
          }
        }
      }
    }
    val finish_data = new State {
      whenIsActive {
        io.output := data_encoder.io.output

        when(data_encoder.io.data.ready) {
          goto(idle)
        }
      }
    }
  }
  preamble_encoder.io.data.valid := fsm.isActive(fsm.sending_preamble)
  data_encoder.io.data.valid := fsm.isActive(fsm.sending_data)

  io.instr.ready := fsm.isActive(fsm.idle)
}

class Ykq368Endpoint(instr_config: InstrConfig) extends Component {
  val io = new Bundle {
    val uart_rx = slave(Stream(Bits(8 bits)))

    val active = in(Bool())
    val exited = out(Bool())
    val sending = out(Bool())
    val output = out(Bool())
  }

  val instr = Reg(Instr())

  val cmd_bits_stream = Stream(Bits(64 bits))
  StreamWidthAdapter(
    io.uart_rx,
    cmd_bits_stream,
    order = HIGHER_FIRST
  )
  val cmd_stream = cmd_bits_stream.map({ b =>
    val instr = Instr()
    instr.send_preamble := b(62)
    instr.send_data := b(61)
    instr.skip := b(56, 5 bits).asUInt
    instr.repeat := b(48, 8 bits).asUInt
    instr.preamble := b(35, 13 bits)
    instr.data := b(0, 35 bits)
    instr
  })

  val instr_proc = new InstrProc(instr_config)

  val fsm = new StateMachine {
    val idle: State = makeInstantEntry().whenIsActive {
      when(io.active) {
        goto(ready)
      }
    }

    val ready: State = new State {
      whenIsActive {
        when(cmd_stream.valid) {
          when(
            cmd_stream.payload.send_preamble || cmd_stream.payload.send_data
          ) {
            instr := cmd_stream.payload
            goto(waiting)
          } otherwise {
            goto(finishing)
          }
        }
      }
    }

    val waiting = new State {
      whenIsActive {
        when(instr_proc.io.instr.ready) {
          goto(ready)
        }
      }
    }

    val finishing = new State {
      whenIsActive {
        when(instr_proc.io.instr.ready) {
          goto(exited)
        }
      }
    }

    val exited = new State {
      whenIsActive {
        when(!io.active) {
          goto(idle)
        }
      }
    }
  }

  cmd_stream.ready := fsm.isActive(fsm.ready)

  instr_proc.io.instr.payload := instr
  instr_proc.io.instr.valid := fsm.isActive(fsm.waiting)

  io.sending := !instr_proc.io.instr.ready
  io.output <> instr_proc.io.output
  io.exited := fsm.isActive(fsm.exited)
}
