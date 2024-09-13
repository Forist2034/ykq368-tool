package sim.remote_control.atsmart.ykq368

import spinal.core._
import spinal.core.sim._
import spinal.sim._
import rf_tool.fpga.remote_control.atsmart.ykq368._
import spinal.lib.sim.StreamDriver
import spinal.lib.sim.RandomGen
import spinal.core.internals.Operator.Formal.RandomExp

object InstrProcSim extends App {
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
      new InstrProc(
        InstrConfig(
          preamble_timing = PwmTiming(
            period = 60 us,
            high0 = 30 us,
            high1 = 10 us
          ),
          preamble_wait = 120 us,
          data_timing = PwmTiming(
            period = 60 us,
            high0 = 40 us,
            high1 = 20 us
          ),
          data_wait = 120 us
        )
      )
    )

  case class TestInstr(
      send_preamble: Boolean,
      send_data: Boolean,
      skip: Int,
      preamble: Short,
      data: Long,
      repeat: Int
  )

  def simInstrSeq(name: String, seq: Seq[TestInstr]) = {
    cfg.doSim(name)({ dut =>
      dut.io.instr.valid #= false
      dut.clockDomain.forkStimulus()

      var data_count = 0
      StreamDriver(dut.io.instr, dut.clockDomain) { payload =>
        if (data_count < seq.length) {
          val ti = seq(data_count)
          payload.send_preamble #= ti.send_preamble
          payload.send_data #= ti.send_data
          payload.skip #= ti.skip
          payload.preamble #= ti.preamble
          payload.data #= ti.data
          payload.repeat #= ti.repeat
          data_count = data_count + 1
          true
        } else {
          false
        }
      }

      dut.clockDomain.waitSamplingWhere({ data_count == seq.length })
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSamplingWhere(dut.io.instr.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitActiveEdgeWhere(dut.io.instr.ready.toBoolean)
      dut.clockDomain.waitSampling(20)
    })
  }

  val preamble_data: Short = 0x0007
  val main_data = 0x123456789L

  val preamble_only = TestInstr(true, false, 0, preamble_data, main_data, 1)
  simInstrSeq("preamble_only", Seq(preamble_only))

  val data_only = TestInstr(false, true, 0, preamble_data, main_data, 1)
  simInstrSeq("data_only", Seq(data_only))

  val preamble_and_data = TestInstr(true, true, 0, preamble_data, main_data, 1)
  simInstrSeq("preamble_and_data", Seq(preamble_and_data))

  val repeat_preamble = TestInstr(true, false, 0, preamble_data, main_data, 3)
  simInstrSeq("repeat_preamble", Seq(repeat_preamble))

  val repeat_data = TestInstr(false, true, 0, preamble_data, main_data, 3)
  simInstrSeq("repeat_data", Seq(repeat_data))

  val repeat_all = TestInstr(true, true, 0, preamble_data, main_data, 3)
  simInstrSeq("repeat_all", Seq(repeat_all))

  val preamble_with_skip =
    TestInstr(true, false, 3, preamble_data, main_data, 3)
  simInstrSeq("preamble_with_skip", Seq(preamble_with_skip))

  val data_with_skip = TestInstr(false, true, 8, preamble_data, main_data, 3)
  simInstrSeq("data_with_skip", Seq(data_with_skip))

  val all_with_skip = TestInstr(true, true, 8, preamble_data, main_data, 3)
  simInstrSeq("all_with_skip", Seq(all_with_skip))

  simInstrSeq(
    "everything",
    Seq(
      preamble_only,
      data_only,
      preamble_and_data,
      repeat_preamble,
      repeat_data,
      repeat_all,
      preamble_with_skip,
      data_with_skip,
      all_with_skip
    )
  )
}
