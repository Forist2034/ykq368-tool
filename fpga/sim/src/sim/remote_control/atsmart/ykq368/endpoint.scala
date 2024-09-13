package sim.remote_control.atsmart.ykq368

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.sim._
import spinal.lib.sim.StreamDriver
import rf_tool.fpga.remote_control.atsmart.ykq368._

object EndpointSim extends App {
  val cfg = SimConfig
    .withConfig(
      SpinalConfig(
        mode = Verilog,
        defaultClockDomainFrequency = FixedFrequency((10 us).toHertz)
      )
    )
    .withWave
    .workspacePath("sim-test")
    .compile(
      new Ykq368Endpoint(
        InstrConfig(
          preamble_timing = PwmTiming(
            period = 40 us,
            high0 = 20 us,
            high1 = 10 us
          ),
          preamble_wait = 240 us,
          data_timing = PwmTiming(
            period = 40 us,
            high0 = 30 us,
            high1 = 10 us
          ),
          data_wait = 320 us
        )
      )
    )

  def simSerialCmd(name: String, cmdSeq: Seq[Seq[Seq[Int]]]) =
    cfg.doSim(name) { dut =>
      dut.io.active #= false
      dut.io.uart_rx.valid #= false
      dut.clockDomain.forkStimulus()

      dut.clockDomain.waitSampling()

      for (activeCmd <- cmdSeq) {
        dut.io.active #= true
        dut.clockDomain.waitSampling()

        val cmd_bytes =
          for (
            cmd <- (activeCmd :+ Seq(0, 0, 0, 0, 0, 0, 0, 0));
            byte <- cmd
          ) yield byte;
        var data_idx = 0
        StreamDriver(dut.io.uart_rx, dut.clockDomain) { payload =>
          if (data_idx < cmd_bytes.length) {
            payload #= cmd_bytes(data_idx)
            data_idx = data_idx + 1
            true
          } else {
            false
          }
        }

        dut.clockDomain.waitSamplingWhere(dut.io.exited.toBoolean)

        dut.io.active #= false
        dut.clockDomain.waitSampling()
      }

      dut.clockDomain.waitSampling(10)
    }

  val all_cmd = Seq(0xe0, 0x01, 0x00, 0x3b, 0x12, 0x34, 0x56, 0x78)
  val preamble_only_cmd = Seq(0xc0, 0x01, 0x00, 0x3b, 0x12, 0x34, 0x56, 0x78)
  val data_only_cmd = Seq(0xa0, 0x01, 0x00, 0x3b, 0x12, 0x34, 0x56, 0x78)

  simSerialCmd(
    "exit_immediately",
    Seq(Seq(), Seq())
  )

  simSerialCmd("send_all", Seq(Seq(all_cmd)))

  simSerialCmd(
    "send_all_then_data",
    Seq(
      Seq(all_cmd, data_only_cmd),
      Seq(preamble_only_cmd, all_cmd),
      Seq(data_only_cmd)
    )
  )
}
