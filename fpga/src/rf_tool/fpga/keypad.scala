package rf_tool.fpga

import spinal.core._
import spinal.lib._

class KeypadRow extends Component {
  val io = new Bundle {
    val active = in Bool ()
    val keys = in Bits (4 bits)
    val data = out Bits (4 bits)
  }
  io.data.setAsReg().init(B(0x0))

  val keys0 = RegNextWhen(~io.keys, io.active) init (B(0x0))
  val keys1 = RegNextWhen(keys0, io.active) init (B(0x0))

  val counter_cycles =
    ((20 ms) / ClockDomain.current.frequency.getValue.toTime).toInt
  val counter_val = counter_cycles - 1
  val counter = Reg(UInt(log2Up(counter_cycles) bits)) init (counter_val)

  when(keys0 =/= keys1) {
    counter := counter_val
  } elsewhen (counter === 0) {
    io.data := keys1
    counter := counter_val
  } otherwise {
    counter := counter - 1
  }
}

class MatrixKeypad extends Component {
  val io = new Bundle {
    val key_val = in(Bits(4 bits))
    val key_sel = out(Bits(4 bits))
    val data = out(Bits(16 bits))
  }

  val state = Reg(Bits(4 bits)) init (B"1110")
  state := state.rotateLeft(1)

  for (i <- 0 to 3) yield {
    val row = new KeypadRow
    row.io.active := !state(i)
    row.io.keys <> io.key_val
    io.data(i * 4, 4 bits) <> row.io.data
  }

  io.key_sel := state
}
