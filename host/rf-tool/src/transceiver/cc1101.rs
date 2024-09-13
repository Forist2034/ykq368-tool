use std::{
    fmt::Debug,
    io::{self, Read, Write},
    num::NonZeroU8,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReadWrite {
    Read,
    Write,
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConfigRegAddr {
    /// `GDO2` output pin configuration
    IOCFG2 = 0x00,
    /// `GDO1` output pin configuration
    IOCFG1 = 0x01,
    /// `GDO0` output pin configuration
    IOCFG0 = 0x02,
    /// RX FIFO and TX FIFO thresholds
    FIFOTHR = 0x03,
    /// Sync word, high byte
    SYNC1 = 0x04,
    /// Sync word, low byte
    SYNC0 = 0x05,
    /// Packet length
    PKTLEN = 0x06,
    /// Packet automation control
    PKTCTRL1 = 0x07,
    /// Packet automation control
    PKTCTRL0 = 0x08,
    /// Device address
    ADDR = 0x09,
    /// Channel number
    CHANNR = 0x0A,
    /// Frequency synthesizer control
    FSCTRL1 = 0x0B,
    /// Frequency synthesizer control
    FSCTRL0 = 0x0C,
    /// Frequency control word, high byte
    FREQ2 = 0x0D,
    /// Frequency control word, middle byte
    FREQ1 = 0x0E,
    /// Frequency control word, low byte
    FREQ0 = 0x0F,
    /// Modem configuration
    MDMCFG4 = 0x10,
    /// Modem configuration
    MDMCFG3 = 0x11,
    /// Modem configuration
    MDMCFG2 = 0x12,
    /// Modem configuration
    MDMCFG1 = 0x13,
    /// Modem configuration
    MDMCFG0 = 0x14,
    /// Modem deviation setting
    DEVIATN = 0x15,
    /// Main Radio Control State Machine configuration
    MCSM2 = 0x16,
    /// Main Radio Control State Machine configuration
    MCSM1 = 0x17,
    /// Main Radio Control State Machine configuration
    MCSM0 = 0x18,
    /// Frequency Offset Compensation configuration
    FOCCFG = 0x19,
    /// Bit Synchronization configuration
    BSCFG = 0x1A,
    /// AGC control
    AGCTRL2 = 0x1B,
    /// AGC control
    AGCTRL1 = 0x1C,
    /// AGC control
    AGCTRL0 = 0x1D,
    /// High byte Event 0 timeout
    WOREVT1 = 0x1E,
    /// Low byte Event 0 timeout
    WOREVT0 = 0x1F,
    /// Wake On Radio control
    WORCTRL = 0x20,
    /// Front end RX configuration
    FREND1 = 0x21,
    /// Front end TX configuration
    FREND0 = 0x22,
    /// Frequency synthesizer calibration
    FSCAL3 = 0x23,
    /// Frequency synthesizer calibration
    FSCAL2 = 0x24,
    /// Frequency synthesizer calibration
    FSCAL1 = 0x25,
    /// Frequency synthesizer calibration
    FSCAL0 = 0x26,
    /// RC oscillator configuration
    RCCTRL1 = 0x27,
    /// RC oscillator configuration
    RCCTRL0 = 0x28,
    /// Frequency synthesizer calibration control
    FSTEST = 0x29,
    /// Production test
    PTEST = 0x2A,
    /// AGC test
    AGCTEST = 0x2B,
    /// Various test settings
    TEST2 = 0x2C,
    /// Various test settings
    TEST1 = 0x2D,
    /// Various test settings
    TEST0 = 0x2E,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum CommandStrobe {
    /// Reset chip
    SRES = 0x30,
    /// Enable and calibrate frequency synthesizer (if `MCSM0.FS_AUTOCAL=1`). If in RX (with CCA):
    /// Go to a wait state where only the synthesizer is running (for quick RX / TX turnaround).
    SFSTXON = 0x31,
    /// Turn off crystal oscillator.
    SXOFF = 0x32,
    /// Calibrate frequency synthesizer and turn it off. `SCAL` can be strobed from IDLE mode without
    /// setting manual calibration mode (`MCSM0.FS_AUTOCAL=0`)
    SCAL = 0x33,
    /// Enable RX. Perform calibration first if coming from IDLE and `MCSM0.FS_AUTOCAL=1`.
    SRX = 0x34,
    /** In IDLE state: Enable TX. Perform calibration first if `MCSM0.FS_AUTOCAL=1`.
    If in RX state and CCA is enabled: Only go to TX if channel is clear. */
    STX = 0x35,
    /// Exit RX / TX, turn off frequency synthesizer and exit Wake-On-Radio mode if applicable.
    SIDLE = 0x36,
    /** Start automatic RX polling sequence (Wake-on-Radio) as described in Section 19.5 if
    `WORCTRL.RC_PD=0`. */
    SWOR = 0x38,
    /// Enter power down mode when CSn goes high.
    SPWD = 0x39,
    /// Flush the RX FIFO buffer. Only issue `SFRX` in IDLE or RXFIFO_OVERFLOW states.
    SFRX = 0x3A,
    /// Flush the TX FIFO buffer. Only issue `SFTX` in IDLE or TXFIFO_UNDERFLOW states.
    SFTX = 0x3B,
    /// Reset real time clock to Event1 value.
    SWORRST = 0x3C,
    /// No operation. May be used to get access to the chip status byte.
    SNOP = 0x3D,
}

#[allow(non_camel_case_types)]
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StatusRegAddr {
    /// Part number for CC1101
    PARTNUM = 0x30,
    /// Current version number
    VERSION = 0x31,
    /// Frequency Offset Estimate
    FREQEST = 0x32,
    /// Demodulator estimate for Link Quality
    LQI = 0x33,
    /// Received signal strength indication
    RSSI = 0x34,
    /// Control state machine state
    MARCSTATE = 0x35,
    /// High byte of WOR timer
    WORTIME1 = 0x36,
    /// Low byte of WOR timer
    WORTIME0 = 0x37,
    /// Current GDOx status and packet status
    PKTSTATUS = 0x38,
    /// Current setting from PLL calibration module
    VCO_VC_DAC = 0x39,
    /// Underflow and number of bytes in the TX FIFO
    TXBYTES = 0x3A,
    /// Overflow and number of bytes in the RX FIFO
    RXBYTES = 0x3B,
    /// Last RC oscillator calibration result
    RCCTRL1_STATUS = 0x3C,
    /// Last RC oscillator calibration result
    RCCTRL0_STATUS = 0x3D,
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GdoCfg {
    /** Associated
     to the RX FIFO: Asserts when RX FIFO is filled at or above the RX FIFO threshold. De-asserts when RX FIFO
    is drained below the same threshold. */
    RxFifoThreshold = 0x00,
    /** Associated to the RX FIFO: Asserts when RX FIFO is filled at or above the RX FIFO threshold or the end of packet is
    reached. De-asserts when the RX FIFO is empty. */
    RxFifoThresholdOrEndOfPacket = 0x01,
    /** Associated to the TX FIFO: Asserts when the TX FIFO is filled at or above the TX FIFO threshold. De-asserts when the TX
    FIFO is below the same threshold. */
    TxFifoThreshold = 0x02,
    /** Associated to the TX FIFO: Asserts when TX FIFO is full. De-asserts when the TX FIFO is drained below the TX FIFO
    threshold. */
    TxFifoFull = 0x03,
    /** Asserts when the RX FIFO has overflowed. De-asserts when the FIFO has been flushed. */
    RxFifoOverflow = 0x04,
    /** Asserts when the TX FIFO has underflowed. De-asserts when the FIFO is flushed. */
    TxFifoUnderflow = 0x05,
    /** Asserts when sync word has been sent / received, and de-asserts at the end of the packet. In RX, the pin will also de-
    assert when a packet is discarded due to address or maximum length filtering or when the radio enters
    RXFIFO_OVERFLOW state. In TX the pin will de-assert if the TX FIFO underflows. */
    SyncWord = 0x06,
    /** Asserts when a packet has been received with CRC OK. De-asserts when the first byte is read from the RX FIFO. */
    PacketCrcOkReceived = 0x07,
    /** Preamble Quality Reached. Asserts when the PQI is above the programmed PQT value. De-asserted when the chip re-
    enters RX state (`MARCSTATE=0x0D`) or the PQI gets below the programmed PQT value. */
    PreambleQualityReached = 0x08,
    /** Clear channel assessment. High when RSSI level is below threshold (dependent on the current CCA_MODE setting). */
    ClearChannelAssessment = 0x09,
    /** Lock detector output. The PLL is in lock if the lock detector output has a positive transition or is constantly logic high. To
    check for PLL lock the lock detector output should be used as an interrupt for the MCU. */
    LockDetectorOutput = 0x0A,
    /** Serial Clock. Synchronous to the data in synchronous serial mode.
    In RX mode, data is set up on the falling edge by CC1101 when `GDOx_INV=0`.
    In TX mode, data is sampled by CC1101 on the rising edge of the serial clock when `GDOx_INV=0`. */
    SerialClock = 0x0B,
    /** Serial Synchronous Data Output. Used for synchronous serial mode. */
    SerialSynchronousDataOutput = 0x0C,
    /** Serial Data Output. Used for asynchronous serial mode. */
    SerialDataOutput = 0x0D,
    /** Carrier sense. High if RSSI level is above threshold. Cleared when entering IDLE mode. */
    CarrierSense = 0x0E,
    /** CRC_OK. The last CRC comparison matched. Cleared when entering/restarting RX mode. */
    CrcOk = 0x0F,
    /** RX_HARD_DATA[1]. Can be used together with RX_SYMBOL_TICK for alternative serial RX output. */
    RxHardData1 = 0x16,
    /** RX_HARD_DATA[0]. Can be used together with RX_SYMBOL_TICK for alternative serial RX output. */
    RxHardData0 = 0x17,
    /** PA_PD. Note: PA_PD will have the same signal level in SLEEP and TX states. To control an external PA or RX/TX switch
    in applications where the SLEEP state is used it is recommended to use `GDOx_CFGx=0x2F` instead. */
    PaPd = 0x1B,
    /** LNA_PD. Note: LNA_PD will have the same signal level in SLEEP and RX states. To control an external LNA or RX/TX
    switch in applications where the SLEEP state is used it is recommended to use `GDOx_CFGx=0x2F` instead. */
    LnaPd = 0x1C,
    /** RX_SYMBOL_TICK. Can be used together with RX_HARD_DATA for alternative serial RX output. */
    RxSymbolTick = 0x1D,
    WorEvnt0 = 0x24,
    WorEvnt1 = 0x25,
    Clk256 = 0x26,
    Clk32k = 0x27,
    ChipRdy = 0x29,
    XoscStable = 0x2B,
    HighImpedance = 0x2E,
    /** HW to 0 (HW1 achieved by setting GDOx_INV=1). Can be used to control an external LNA/PA or RX/TX switch. */
    Zero = 0x2F,
    ClkXosc1 = 0x30,
    ClkXosc1_5 = 0x31,
    ClkXosc2 = 0x32,
    ClkXosc3 = 0x33,
    ClkXosc4 = 0x34,
    ClkXosc6 = 0x35,
    ClkXosc8 = 0x36,
    ClkXosc12 = 0x37,
    ClkXosc16 = 0x38,
    ClkXosc24 = 0x39,
    ClkXosc32 = 0x3A,
    ClkXosc48 = 0x3B,
    ClkXosc64 = 0x3C,
    ClkXosc96 = 0x3D,
    ClkXosc128 = 0x3E,
    ClkXosc192 = 0x3F,
}

#[inline]
const fn to_nonzero_u8<const N: usize>() -> NonZeroU8 {
    if N > 128 {
        panic!()
    } else {
        match NonZeroU8::new(N as u8) {
            Some(v) => v,
            None => panic!(),
        }
    }
}

pub struct Status(pub u8);
impl Debug for Status {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:02x}", self.0)
    }
}

#[derive(Debug, Clone, Copy)]
pub struct TransferCmd(pub [u8; 2]);
impl TransferCmd {
    const fn single(close: bool, rw: ReadWrite, addr: u8) -> Self {
        Self([
            if close { 0xc0 } else { 0x80 },
            match rw {
                ReadWrite::Read => addr | 0x80,
                ReadWrite::Write => addr,
            },
        ])
    }
    const fn burst(rw: ReadWrite, count: u8, addr: u8) -> Self {
        Self([
            count | 0x80,
            match rw {
                ReadWrite::Read => addr | 0xc0,
                ReadWrite::Write => addr | 0x40,
            },
        ])
    }
    pub const fn config_reg(close: bool, rw: ReadWrite, addr: ConfigRegAddr) -> Self {
        Self::single(close, rw, addr as u8)
    }
    pub const fn config_reg_burst(rw: ReadWrite, start_addr: ConfigRegAddr, count: u8) -> Self {
        Self::burst(rw, count, start_addr as u8)
    }
    pub const fn command_strobe(close: bool, rw: ReadWrite, cmd: CommandStrobe) -> Self {
        Self::single(close, rw, cmd as u8)
    }
    pub const fn status_reg(close: bool, addr: StatusRegAddr) -> Self {
        Self([if close { 0xc0 } else { 0x80 }, (addr as u8) | 0xc0])
    }
    pub const fn pa_table(rw: ReadWrite, count: u8) -> Self {
        Self::burst(rw, count, 0x3e)
    }
    pub const fn fifo(close: bool, rw: ReadWrite) -> Self {
        Self::single(close, rw, 0x3f)
    }
    pub const fn fifo_burst(rw: ReadWrite, count: u8) -> Self {
        Self::burst(rw, count, 0x3f)
    }
}

#[inline]
fn arr_to_status<const N: usize>(arr: [u8; N]) -> [Status; N] {
    std::array::from_fn(|idx| Status(arr[idx]))
}

pub struct Cc1101<'a, P>(pub &'a mut P);
impl<'a, P: Read + Write> Cc1101<'a, P> {
    pub fn new(port: &'a mut P) -> Self {
        Self(port)
    }

    fn read_raw(&mut self, cmd: TransferCmd) -> io::Result<(Status, u8)> {
        self.0.write_all(&cmd.0)?;
        let mut buf = [0, 0];
        self.0.read_exact(&mut buf)?;
        Ok((Status(buf[0]), buf[1]))
    }
    fn read_raw_burst<const N: usize>(
        &mut self,
        cmd: TransferCmd,
    ) -> io::Result<(Status, [u8; N])> {
        self.0.write_all(&cmd.0)?;
        let mut status = [0; 1];
        let mut ret = [0; N];
        self.0.read_exact(&mut status)?;
        self.0.read_exact(&mut ret)?;
        Ok((Status(status[0]), ret))
    }
    fn write_raw(&mut self, cmd: TransferCmd, data: u8) -> io::Result<(Status, Status)> {
        self.0.write_all(&[cmd.0[0], cmd.0[1], data])?;
        let mut buf = [0, 0];
        self.0.read_exact(&mut buf)?;
        Ok((Status(buf[0]), Status(buf[1])))
    }
    fn write_raw_burst<const N: usize>(
        &mut self,
        cmd: TransferCmd,
        data: &[u8; N],
    ) -> io::Result<(Status, [Status; N])> {
        self.0.write_all(&cmd.0)?;
        self.0.write_all(data)?;
        let mut status = [0; 1];
        let mut ret = [0; N];
        self.0.read_exact(&mut status)?;
        self.0.read_exact(&mut ret)?;
        Ok((Status(status[0]), arr_to_status(ret)))
    }

    pub fn read_raw_status_reg(
        &mut self,
        close: bool,
        addr: StatusRegAddr,
    ) -> io::Result<(Status, u8)> {
        self.read_raw(TransferCmd::status_reg(close, addr))
    }
    pub fn read_raw_config_reg(
        &mut self,
        close: bool,
        addr: ConfigRegAddr,
    ) -> io::Result<(Status, u8)> {
        self.read_raw(TransferCmd::config_reg(close, ReadWrite::Read, addr))
    }
    pub fn read_raw_config_burst<const N: usize>(
        &mut self,
        start_addr: ConfigRegAddr,
    ) -> io::Result<(Status, [u8; N])> {
        self.read_raw_burst(TransferCmd::config_reg_burst(
            ReadWrite::Read,
            start_addr,
            to_nonzero_u8::<N>().get(),
        ))
    }
    pub fn write_raw_config_burst<const N: usize>(
        &mut self,
        start_addr: ConfigRegAddr,
        data: &[u8; N],
    ) -> io::Result<(Status, [Status; N])> {
        self.write_raw_burst(
            TransferCmd::config_reg_burst(ReadWrite::Write, start_addr, to_nonzero_u8::<N>().get()),
            data,
        )
    }
    pub fn write_raw_config_reg(
        &mut self,
        close: bool,
        addr: ConfigRegAddr,
        data: u8,
    ) -> io::Result<(Status, Status)> {
        self.write_raw(TransferCmd::config_reg(close, ReadWrite::Write, addr), data)
    }

    pub fn command_strobe(
        &mut self,
        close: bool,
        rw: ReadWrite,
        cmd: CommandStrobe,
    ) -> io::Result<Status> {
        self.0
            .write_all(&TransferCmd::command_strobe(close, rw, cmd).0)?;
        let mut buf = [0];
        self.0.read_exact(&mut buf)?;
        Ok(Status(buf[0]))
    }

    pub fn read_fifo(&mut self, close: bool) -> io::Result<(Status, u8)> {
        self.read_raw(TransferCmd::fifo(close, ReadWrite::Read))
    }
    pub fn read_fifo_burst<const N: usize>(&mut self) -> io::Result<(Status, [u8; N])> {
        self.read_raw_burst(TransferCmd::fifo_burst(
            ReadWrite::Read,
            to_nonzero_u8::<N>().get(),
        ))
    }
    pub fn write_fifo(&mut self, close: bool, data: u8) -> io::Result<(Status, Status)> {
        self.write_raw(TransferCmd::fifo(close, ReadWrite::Write), data)
    }
    pub fn write_fifo_burst<const N: usize>(
        &mut self,
        data: &[u8; N],
    ) -> io::Result<(Status, [Status; N])> {
        self.write_raw_burst(
            TransferCmd::fifo_burst(ReadWrite::Write, to_nonzero_u8::<N>().get()),
            data,
        )
    }

    pub fn read_pa_table(&mut self) -> io::Result<(Status, [u8; 8])> {
        self.read_raw_burst(TransferCmd::pa_table(ReadWrite::Read, 8))
    }
    pub fn write_pa_table(&mut self, data: &[u8; 8]) -> io::Result<(Status, [Status; 8])> {
        self.write_raw_burst(TransferCmd::pa_table(ReadWrite::Write, 8), data)
    }

    pub fn exit(self) -> io::Result<()> {
        self.0.write_all(&[0x00])
    }
}
