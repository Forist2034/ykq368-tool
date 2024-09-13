use std::{io::Write, process::ExitCode, time::Duration};

use anyhow::{Context, Result};
use clap::Parser;
use dialoguer::theme::ColorfulTheme;
use rustix::{
    fd::{AsFd, BorrowedFd},
    fs::{Mode, OFlags},
};
use strum::VariantArray;

use rf_tool::remote_control::atsmart::ykq368::{Data, Preamble, SendInstr, SendParts};
use ykq368_tester::{
    wait_or_interrupt, CycleTester, Key, KeyConfig, TestResult, Tester, WaitResult,
};

// fill in data from decoder
const PREAMBLE: Preamble = panic!("missing preamble");

const CLOSE: Data = panic!("missing close data");
const OPEN: Data = panic!("missing open data");
const LOCK: Data = panic!("missing lock data");
const STOP: Data = panic!("missing stop data");

const fn default_instr(has_preamble: bool, data: Data) -> SendInstr {
    SendInstr {
        send: if has_preamble {
            SendParts::All
        } else {
            SendParts::Data
        },
        skip: 0,
        preamble: PREAMBLE,
        data,
        repeat: 3,
    }
}
const fn default_keys(has_preamble: bool) -> KeyConfig<SendInstr> {
    KeyConfig {
        close: default_instr(has_preamble, CLOSE),
        open: default_instr(has_preamble, OPEN),
        lock: default_instr(has_preamble, LOCK),
        stop: default_instr(has_preamble, STOP),
    }
}

#[derive(Debug, Clone, Copy, clap::ValueEnum)]
enum Encoding {
    PreambleAndData,
    DataOnly,
    Random,
}

#[derive(Debug, clap::Parser)]
struct Cli {
    #[arg(long)]
    count: usize,
    #[arg(long)]
    encoding: Encoding,
    #[arg(long)]
    port: String,
    /// directory to write results to
    #[arg(long)]
    dest: String,
}

fn before_start(dur: Duration) -> Result<bool> {
    #[derive(strum::Display, strum::VariantArray)]
    #[strum(serialize_all = "snake_case")]
    enum Op {
        Start,
        Skip,
        Wait,
    }
    loop {
        match wait_or_interrupt(dur)? {
            WaitResult::Normal => return Ok(true),
            WaitResult::Interrupted => {
                match Op::VARIANTS[dialoguer::Select::with_theme(&ColorfulTheme::default())
                    .with_prompt("skip or start")
                    .items(Op::VARIANTS)
                    .interact()?]
                {
                    Op::Start => return Ok(true),
                    Op::Skip => return Ok(false),
                    Op::Wait => (),
                }
            }
        }
    }
}

enum Direction {
    Open,
    Close,
}
fn test_go<W: std::io::Write>(
    direction: Direction,
    count: u8,
    cycle: &mut CycleTester<W>,
) -> Result<bool> {
    let mut has_fail = false;

    if let Direction::Open = direction {
        if let TestResult::Fail = cycle.send(Key::Lock, Duration::from_secs(10))? {
            has_fail = true;
        }
    }
    let key = match direction {
        Direction::Open => Key::Open,
        Direction::Close => Key::Close,
    };

    let mut stop_pass = true;
    for _ in 0..count {
        if stop_pass {
            if let TestResult::Fail = cycle.send(key, Duration::from_secs(5))? {
                has_fail = true;
                stop_pass = true;
                continue;
            }
        }
        match cycle.send(Key::Stop, Duration::from_secs(5))? {
            TestResult::Pass => stop_pass = true,
            TestResult::Fail => {
                has_fail = true;
                stop_pass = false;
            }
        }
    }

    let mut wait = Duration::from_secs(if has_fail { 20 } else { 10 });
    let theme = ColorfulTheme::default();
    loop {
        #[derive(strum::Display, strum::VariantArray)]
        #[strum(serialize_all = "snake_case")]
        enum Op {
            Skip,
            Retry,
        }
        if let TestResult::Pass = cycle.send(key, wait)? {
            if dialoguer::Confirm::with_theme(&ColorfulTheme::default())
                .with_prompt(match direction {
                    Direction::Open => "fully opened",
                    Direction::Close => "fully closed",
                })
                .default(true)
                .interact()?
            {
                return Ok(true);
            }
        }
        match Op::VARIANTS[dialoguer::Select::with_theme(&theme)
            .with_prompt("retry or skip test")
            .items(Op::VARIANTS)
            .interact()?]
        {
            Op::Skip => return Ok(false),
            Op::Retry => (),
        }
        wait = Duration::from_secs(20);
    }
}
fn run_test<W: std::io::Write>(
    root: BorrowedFd,
    instr: KeyConfig<SendInstr>,
    writer: &mut W,
) -> Result<()> {
    let mut tester = Tester::new(instr, writer).context("failed to start new round")?;
    for _ in 0..4 {
        if !before_start(Duration::from_secs(5))? {
            continue;
        }
        if !tester.with_cycle(|cycle| {
            if !test_go(Direction::Open, 2, cycle)? {
                return Ok(false);
            }
            if !(test_go(Direction::Close, 3, cycle))? {
                return Ok(false);
            }
            Ok(true)
        })? {
            break;
        }
    }
    let report = tester.finish().context("failed to close tester")?;

    std::fs::File::from(
        rustix::fs::openat(
            root,
            format!("{}.bin", report.id),
            OFlags::CREATE | OFlags::EXCL | OFlags::WRONLY,
            Mode::from_raw_mode(0o444),
        )
        .context("failed to open report file")?,
    )
    .write_all(&{
        let mut ret = Vec::new();
        ciborium::into_writer(&report, &mut ret).unwrap();
        ret
    })
    .context("failed to write report file")?;

    Ok(())
}
fn run(cli: Cli) -> Result<()> {
    let mut port = serialport::new(cli.port, 115200)
        .open_native()
        .context("failed to open serial port")?;

    let root = rustix::fs::open(cli.dest, OFlags::DIRECTORY | OFlags::PATH, Mode::empty())
        .context("failed to open dest dir")?;

    let config = match cli.encoding {
        Encoding::PreambleAndData => {
            let mut ret = Vec::with_capacity(cli.count);
            ret.resize(cli.count, true);
            ret
        }
        Encoding::DataOnly => {
            let mut ret = Vec::with_capacity(cli.count);
            ret.resize(cli.count, false);
            ret
        }
        Encoding::Random => {
            let mut ret = Vec::with_capacity(cli.count);
            for i in 0..cli.count {
                ret.push((i & 1) == 1);
            }
            rand::prelude::SliceRandom::shuffle(ret.as_mut_slice(), &mut rand::thread_rng());
            ret
        }
    };

    for cfg in config {
        if !before_start(Duration::from_secs(240))? {
            break;
        }
        run_test(root.as_fd(), default_keys(cfg), &mut port)?;
    }

    Ok(())
}

fn main() -> ExitCode {
    match run(Cli::parse()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("{e:?}");
            ExitCode::FAILURE
        }
    }
}
