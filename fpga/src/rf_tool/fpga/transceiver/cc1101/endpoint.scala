package rf_tool.fpga.transceiver.cc1101

import spinal.core._
import spinal.lib._
import spinal.lib.io.TriState
import spinal.lib.fsm._
import rf_tool.fpga.transceiver.cc1101._

case class GdoPort() extends Bundle with IMasterSlave {
  val read = Bool()
  val write = Bool()

  def asMaster(): Unit = {
    in(read)
    out(write)
  }
}

class Cc1101CtrlEndpoint(val spi_freq: HertzNumber) extends Component {
  val io = new Bundle {
    val cc1101 = master(Cc1101())

    val uart_rx = slave(Stream(Bits(8 bits)))
    val uart_tx = master(Stream(Bits(8 bits)))

    val gdo0 = slave(GdoPort())
    val gdo1 = slave(GdoPort())
    val gdo2 = slave(GdoPort())

    val init_done = out(Bool())

    val active = in(Bool())
    val exited = out(Bool())
  }

  val init_done = RegInit(False)
  val start = Reg(Bool()) init (False)
  val writeEnable = RegInit(Vec(False, False, False))

  val spi = new Cc1101Spi(spi_freq)
  spi.io.start := start
  spi.io.read.throwWhen(!init_done) <> io.uart_tx
  spi.io.sclk <> io.cc1101.sclk
  spi.io.cs <> io.cc1101.cs
  spi.io.si <> io.cc1101.si
  spi.io.so <> io.cc1101.gdo1.read

  // burst: byte count, not burst: arg(6) is whether stop transfer
  val transArg = Reg(new Union {
    val rawBits = newElement(Bits(7 bits))
    val byteCount = newElement(UInt(7 bits))
    val stopOnFinish = newElement(Bool())
  })

  val fsm = new StateMachine {
    val init = makeInstantEntry().whenIsActive {
      start := True
      io.uart_rx.ready := False
      spi.io.write.valid := True
      spi.io.write.payload := 0x30

      when(spi.io.write.ready) {
        goto(wait_reset)
      }
    }

    val idle = new State {
      whenIsActive {
        io.uart_rx.ready := False
        spi.io.write.valid := False
        spi.io.write.payload := 0

        when(io.active) {
          goto(ready)
        }
      }
    }

    def startCmd() = {
      io.uart_rx.ready := True

      when(io.uart_rx.valid) {
        when(io.uart_rx.payload(7)) {
          transArg.rawBits := io.uart_rx.payload(0, 7 bits)
          when(io.uart_rx.payload === B(0)) { // invalid
            goto(ready)
          } otherwise {
            goto(send_header)
          }
        } otherwise {
          switch(io.uart_rx.payload(4, 3 bits)) {
            is(0) {
              goto(finishing)
            }
            is(1) { // set io direction
              writeEnable(0) := io.uart_rx.payload(0)
              writeEnable(1) := io.uart_rx.payload(1)
              writeEnable(2) := io.uart_rx.payload(2)
              goto(ready)
            }
            default {
              goto(ready)
            }
          }
        }
      } otherwise {
        goto(ready)
      }
    }
    val ready: State = new State {
      whenIsActive {
        spi.io.write.valid := False
        spi.io.write.payload := 0

        startCmd()
      }
    }

    val send_header: State = new State {
      onEntry {
        start := True
      }
      whenIsActive {
        io.uart_rx >> spi.io.write

        when(spi.io.write.fire) {
          val data = io.uart_rx.payload
          val is_read = data(7)
          val burst = data(6)
          val addr = data(0, 6 bits).asUInt
          when(addr >= 0x30 && addr <= 0x3d) { // strobe or status
            val stopOnFinish = transArg.rawBits(6)
            transArg.stopOnFinish := stopOnFinish
            when(is_read && burst) { // status register
              goto(read)
            } otherwise { // command strobe
              when(addr === 0x30) { // reset
                goto(wait_reset)
              } otherwise {
                when(stopOnFinish) {
                  goto(stop_transfer)
                } otherwise {
                  goto(ready)
                }
              }
            }
          } otherwise {
            when(burst) {
              transArg.byteCount := transArg.rawBits.asUInt - 1
              when(is_read) {
                goto(burst_read)
              } otherwise {
                goto(burst_write)
              }
            } otherwise {
              transArg.stopOnFinish := transArg.rawBits(6)
              when(is_read) {
                goto(read)
              } otherwise {
                goto(write)
              }
            }
          }
        }
      }
    }

    val read = new State {
      whenIsActive {
        io.uart_rx.ready := False
        spi.io.write.valid := True
        spi.io.write.payload := 0

        when(spi.io.write.ready) {
          when(transArg.stopOnFinish) {
            goto(stop_transfer)
          } otherwise {
            startCmd()
          }
        }
      }
    }
    val burst_read = new State {
      whenIsActive {
        io.uart_rx.ready := False
        spi.io.write.valid := True
        spi.io.write.payload := 0

        when(spi.io.write.ready) {
          when(transArg.byteCount =/= 0) {
            transArg.byteCount := transArg.byteCount - 1
          } otherwise {
            goto(stop_transfer)
          }
        }
      }
    }

    val write = new State {
      whenIsActive {
        io.uart_rx >> spi.io.write
        when(spi.io.write.fire) {
          when(transArg.stopOnFinish) {
            goto(stop_transfer)
          } otherwise {
            goto(ready)
          }
        }
      }
    }
    val burst_write = new State {
      whenIsActive {
        io.uart_rx >> spi.io.write
        when(spi.io.write.fire) {
          when(transArg.byteCount =/= 0) {
            transArg.byteCount := transArg.byteCount - 1
          } otherwise {
            goto(stop_transfer)
          }
        }
      }
    }
    val wait_reset = new State {
      onEntry {
        writeEnable := Vec(False, False, False)
      }
      whenIsActive {
        io.uart_rx.ready := False
        spi.io.write.valid := False
        spi.io.write.payload := 0

        when(spi.io.write.ready && !io.cc1101.gdo1.read) {
          goto(stop_transfer)
        }
      }
    }

    val stop_transfer = new State {
      onEntry {
        start := False
      }
      whenIsActive {
        io.uart_rx.ready := False
        spi.io.write.valid := False
        spi.io.write.payload := 0

        when(!spi.io.started) {
          when(init_done) {
            startCmd()
          } otherwise {
            init_done := True
            goto(idle)
          }
        }
      }
    }

    val finishing = new State {
      onEntry {
        start := False
      }
      whenIsActive {
        io.uart_rx.ready := False
        spi.io.write.payload := 0
        spi.io.write.valid := False

        when(!spi.io.started) {
          goto(exited)
        }
      }
    }
    val exited = new State {
      whenIsActive {
        io.uart_rx.ready := False
        spi.io.write.payload := 0
        spi.io.write.valid := False

        when(!io.active) {
          goto(idle)
        }
      }
    }
  }

  io.init_done := init_done
  io.exited := fsm.isActive(fsm.exited)

  io.cc1101.gdo0.writeEnable := writeEnable(0)
  // allow write only cs is low
  io.cc1101.gdo1.writeEnable := spi.io.cs && writeEnable(1)
  io.cc1101.gdo2.writeEnable := writeEnable(2)

  io.gdo0.read <> io.cc1101.gdo0.read
  io.gdo0.write <> io.cc1101.gdo0.write

  io.gdo1.read <> io.cc1101.gdo1.read
  io.gdo1.write <> io.cc1101.gdo1.write

  io.gdo2.read <> io.cc1101.gdo2.read
  io.gdo2.write <> io.cc1101.gdo2.write
}
