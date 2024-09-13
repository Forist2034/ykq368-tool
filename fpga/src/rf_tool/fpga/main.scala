package rf_tool.fpga

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import rf_tool.fpga.transceiver.cc1101
import cc1101.Cc1101
import rf_tool.fpga.remote_control.atsmart.ykq368
import spinal.lib.io.InOutWrapper

class RfTool(val baudrate: Int = 115200, val spi_freq: HertzNumber = 1 MHz)
    extends Component {
  val io = new Bundle {
    val uart = master(Uart())
    val leds = out(Bits(8 bits))
    val beep = out(Bool())
    // active low
    val switches = in(Bits(4 bits))
    val key_val = in(Bits(4 bits))
    val key_sel = out(Bits(4 bits))
    val cc1101 = master(Cc1101())

    val output = out(Bool())
  }

  val uart: UartCtrl = UartCtrl(
    UartCtrlInitConfig(
      baudrate,
      dataLength = 7,
      parity = UartParityType.NONE,
      stop = UartStopType.ONE
    )
  )
  uart.io.uart <> io.uart

  val keypad = new MatrixKeypad
  keypad.io.key_val <> io.key_val
  keypad.io.key_sel <> io.key_sel

  // endpoint 0 is endpoint selection
  val endpoint_id = Reg(UInt(2 bits)) init (0)

  val cmd_stream = StreamDemux(uart.io.read.queue(1024), endpoint_id, 4)
  cmd_stream(2).ready := True

  val config_stream = Stream(Bits(8 bits))
  config_stream.ready := True
  cmd_stream(0) >> config_stream

  val output_stream = Vec(Stream(Bits(8 bits)), 4)
  output_stream(0).valid := False
  output_stream(0).payload := 0
  output_stream(2).valid := False
  output_stream(2).payload := 0
  output_stream(3).valid := False
  output_stream(3).payload := 0
  StreamMux(endpoint_id, output_stream).queue(1024) >> uart.io.write

  val cc1101_off = io.switches(3)
  val cc1101_endpoint =
    new ResetArea(
      cc1101_off ||
        keypad.io.data(12) || (keypad.io.data(15) && endpoint_id === 1),
      true
    ) {
      val endpoint = new cc1101.Cc1101CtrlEndpoint(spi_freq)
      endpoint.io.active := endpoint_id === 1
      cmd_stream(1) >> endpoint.io.uart_rx
      output_stream(1) << endpoint.io.uart_tx
      endpoint.io.cc1101 <> io.cc1101

      endpoint.io.gdo1.write := False
      endpoint.io.gdo2.write := False
    }.endpoint

  val ykq368_endpoint =
    new ResetArea(keypad.io.data(15) && endpoint_id === 3, true) {
      val endpoint = new ykq368.Ykq368Endpoint(ykq368.InstrConfig())
      endpoint.io.active := endpoint_id === 3
      cmd_stream(3) >> endpoint.io.uart_rx
      io.output <> endpoint.io.output
      cc1101_endpoint.io.gdo0.write <> endpoint.io.output
    }.endpoint

  io.beep := !io.switches(0) && endpoint_id.mux(
    3 -> ykq368_endpoint.io.sending,
    default -> False
  )

  io.leds(0) := !cc1101_endpoint.io.init_done
  io.leds(1) := endpoint_id === 0
  for (i <- 2 to 6) {
    io.leds(i) := True
  }
  io.leds(7) := !endpoint_id.mux(
    3 -> ykq368_endpoint.io.sending,
    default -> False
  )

  val exited = endpoint_id.mux(
    1 -> (cc1101_off || cc1101_endpoint.io.exited),
    3 -> ykq368_endpoint.io.exited,
    default -> True
  )

  when(endpoint_id === 0) {
    when(config_stream.valid) {
      when(config_stream.payload =/= B(0)) {
        endpoint_id := config_stream.payload(0, 2 bits).asUInt
      }
    }
  } otherwise {
    when(exited) {
      endpoint_id := 0
    }
  }
}

object Main extends App {
  SpinalConfig(
    mode = SystemVerilog,
    targetDirectory = "rtl-out",
    defaultClockDomainFrequency = FixedFrequency(25 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(resetActiveLevel = LOW)
  ).generate(InOutWrapper(new RfTool(spi_freq = 5 MHz)))
}
