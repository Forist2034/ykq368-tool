package sim.transceiver.cc1101

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import spinal.sim._
import rf_tool.fpga.transceiver.cc1101._

object EndpointSim extends App {
  val cfg = SimConfig
    .withConfig(
      SpinalConfig(
        mode = Verilog,
        defaultClockDomainFrequency = FixedFrequency(4 MHz)
      )
    )
    .withWave
    .workspacePath("sim-test")
    .compile(new Cc1101CtrlEndpoint(2 MHz))

  def simSerialCmd(name: String, inputs: Seq[Seq[Int]]) =
    cfg.doSim(name) { dut =>
      dut.io.active #= false
      dut.io.uart_rx.valid #= false
      dut.io.cc1101.gdo1.read #= false

      dut.clockDomain.forkStimulus()
      dut.clockDomain.waitSampling()

      SimTimeout(2000000000)

      StreamReadyRandomizer(dut.io.uart_tx, dut.clockDomain)

      for (activeCmd <- inputs) {
        dut.io.active #= true
        dut.clockDomain.waitSampling()

        var cmd_idx = 0
        StreamDriver(dut.io.uart_rx, dut.clockDomain) { payload =>
          if (cmd_idx < activeCmd.length) {
            payload #= activeCmd(cmd_idx)
            cmd_idx = cmd_idx + 1
            true
          } else if (cmd_idx == activeCmd.length) {
            payload #= 0 // exit
            cmd_idx = cmd_idx + 1
            true
          } else { false }
        }

        dut.clockDomain.waitSamplingWhere({ cmd_idx == activeCmd.length + 1 })
        dut.io.active #= false
        dut.clockDomain.waitSamplingWhere(dut.io.exited.toBoolean)
      }

      dut.clockDomain.waitSampling()
    }

  val set_gdo_output = Seq(0x13)
  simSerialCmd("set_gdo_output", Seq(set_gdo_output))

  val read = Seq(0x80, 0x82)
  simSerialCmd("read", Seq(read))

  val read_close = Seq(0xc0, 0x84)
  simSerialCmd("read_close", Seq(read_close))

  val read_burst = Seq(0x84, 0xc0)
  simSerialCmd("read_burst", Seq(read_burst))

  simSerialCmd(
    "read_multi",
    Seq(read ++ read_close ++ read_burst, read_close ++ read ++ read_burst)
  )

  val write = Seq(0x80, 0x02, 0xff)
  simSerialCmd("write", Seq(write))

  val write_close = Seq(0xc0, 0x03, 0x7f)
  simSerialCmd("write_close", Seq(write_close))

  val write_burst = Seq(0x82, 0x4d, 0x01, 0x02)
  simSerialCmd("write_burst", Seq(write_burst))
  simSerialCmd(
    "write_multi",
    Seq(
      write ++ write_close ++ write_burst,
      write_close ++ write ++ write_burst
    )
  )

  val cmd_strobe = Seq(0x80, 0x34)
  simSerialCmd("cmd_strobe", Seq(cmd_strobe))

  val cmd_strobe_close = Seq(0xc0, 0x35)
  simSerialCmd("cmd_strobe_close", Seq(cmd_strobe_close))

  val read_fifo = Seq(0x80, 0xbe)
  simSerialCmd("read_fifo", Seq(read_fifo))

  val read_fifo_close = Seq(0xc0, 0xbe)
  simSerialCmd("read_fifo_close", Seq(read_fifo_close))

  val read_fifo_burst = Seq(0x82, 0xfe)
  simSerialCmd("read_fifo_burst", Seq(read_fifo_burst))

  val write_fifo = Seq(0x80, 0x3e, 0xa0)
  simSerialCmd("write_fifo", Seq(write_fifo))

  val write_fifo_close = Seq(0xc0, 0x3e, 0xa1)
  simSerialCmd("write_fifo_close", Seq(write_fifo_close))

  val write_fifo_burst = Seq(0x82, 0x7e, 0x01, 0x02)
  simSerialCmd("write_fifo_burst", Seq(write_fifo_burst))

  val read_pa = Seq(0x80, 0xbf)
  simSerialCmd("read_pa", Seq(read_pa))

  val read_pa_close = Seq(0xc0, 0xbf)
  simSerialCmd("read_pa_close", Seq(read_pa_close))

  val read_pa_burst = Seq(0x82, 0xff)
  simSerialCmd("read_pa_burst", Seq(read_pa_burst))

  val write_pa = Seq(0x80, 0x3f, 0xa0)
  simSerialCmd("write_pa", Seq(write_pa))

  val write_pa_close = Seq(0xc0, 0x3f, 0xb0)
  simSerialCmd("write_pa_close", Seq(write_pa_close))

  val write_pa_burst = Seq(0x82, 0x7f, 0xc0, 0xc1)
  simSerialCmd("write_pa_burst", Seq(write_pa_burst))

  val read_status = Seq(0x80, 0x70)
  simSerialCmd("read_status", Seq(read_status))

  simSerialCmd(
    "all",
    Seq(
      read ++ read_pa ++ write ++ read_fifo ++ write_fifo_burst ++ read_status,
      set_gdo_output ++ read_close ++ write_pa_burst ++ write_burst ++ read_fifo_close ++ write_fifo ++ cmd_strobe,
      read_burst ++ write_pa_close ++ set_gdo_output ++ write_close ++ read_fifo_burst ++ write_fifo_close ++ cmd_strobe_close
    )
  )

}
