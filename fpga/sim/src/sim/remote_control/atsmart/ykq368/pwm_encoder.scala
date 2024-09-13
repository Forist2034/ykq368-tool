package sim.remote_control.atsmart.ykq368

import spinal.core._
import spinal.core.sim._
import spinal.sim._
import rf_tool.fpga.remote_control.atsmart.ykq368.{PwmEncoder, PwmTiming}

object BitsEncoderSim extends App {
  SimConfig
    .withConfig(
      SpinalConfig(
        mode = Verilog,
        defaultClockDomainFrequency = FixedFrequency((160 us).toHertz)
      )
    )
    .withVcdWave
    .workspacePath("sim-test")
    .compile(
      new PwmEncoder(
        config = PwmTiming(
          period = 1280 us,
          high0 = 640 us,
          high1 = 320 us
        ),
        width = 32 bits,
        has_skip = true
      )
    )
    .doSim { dut =>
      dut.clockDomain.forkStimulus()

      dut.clockDomain.waitSampling()
      dut.io.data.payload.data #= 0x2da80000L
      dut.io.data.payload.skip #= 8
      dut.io.data.valid #= true

      dut.clockDomain.waitSampling()
      dut.io.data.valid #= false

      dut.clockDomain.waitSamplingWhere(dut.io.data.ready.toBoolean)
      dut.clockDomain.waitSampling(10)
    }
}
