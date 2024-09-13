package sim.transceiver.cc1101

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import spinal.sim._
import rf_tool.fpga.transceiver.cc1101.Cc1101Spi

object SpiSim extends App {
  val cfg = SimConfig
    .withConfig(
      SpinalConfig(
        mode = Verilog,
        defaultClockDomainFrequency = FixedFrequency(4 MHz)
      )
    )
    .withWave
    .workspacePath("sim-test")
    .compile(new Cc1101Spi(spi_freq = 2 MHz))

  def simSeq(name: String, writeSeq: Seq[Seq[Int]]) =
    cfg.doSim(name) { dut =>
      dut.io.start #= false
      dut.io.so #= true
      dut.io.write.valid #= false

      dut.clockDomain.forkStimulus()
      dut.clockDomain.waitSampling()

      StreamReadyRandomizer(dut.io.read, dut.clockDomain)

      for (transaction <- writeSeq) {
        var byte_idx = 0
        dut.io.start #= true

        StreamDriver(dut.io.write, dut.clockDomain) { payload =>
          if (byte_idx < transaction.length) {
            payload #= transaction(byte_idx)
            byte_idx = byte_idx + 1
            true
          } else {
            false
          }
        }

        dut.clockDomain.waitSampling(2)
        dut.io.so #= false

        // wait next sclk
        dut.clockDomain.waitSamplingWhere { dut.io.sclk.toBoolean }
        dut.clockDomain.waitSamplingWhere { !dut.io.sclk.toBoolean }
        dut.io.so #= true

        dut.clockDomain.waitSamplingWhere { byte_idx == transaction.length }
        dut.clockDomain.waitSamplingWhere(dut.io.write.ready.toBoolean)

        dut.io.start #= false
        dut.clockDomain.waitSamplingWhere { !dut.io.started.toBoolean }
        dut.clockDomain.waitSampling(5)
      }

      dut.clockDomain.waitSampling(10)
    }

  simSeq("test", Seq(Seq(0x72, 0x2f), Seq(0xf3)))
}
