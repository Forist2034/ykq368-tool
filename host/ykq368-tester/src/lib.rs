use std::{
    io::Write,
    ops::Index,
    time::{Duration, SystemTime},
};

use anyhow::{Context, Result};
use rf_tool::remote_control::atsmart::ykq368::{SendInstr, Ykq368Endpoint};
use rustix::{
    event::{poll, PollFd, PollFlags},
    termios::{tcgetattr, tcsetattr, LocalModes, OptionalActions},
};
use serde::{Deserialize, Serialize};
use strum::VariantArray;
use uuid::Uuid;

#[derive(Debug)]
pub enum WaitResult {
    Normal,
    Interrupted,
}
/// returns false on interrupt
pub fn wait_or_interrupt(dur: Duration) -> Result<WaitResult> {
    let stdin_fd = rustix::stdio::stdin();
    let mut stdout = console::Term::stdout();

    let orig_termios = tcgetattr(stdin_fd)?;
    tcsetattr(stdin_fd, OptionalActions::Drain, &{
        let mut new = orig_termios.clone();
        new.local_modes
            .remove(LocalModes::ICANON | LocalModes::ECHO);
        new
    })?;

    std::write!(
        &mut stdout,
        "Waiting {} s, press any key to interrupt",
        dur.as_secs_f32()
    )?;
    let ret = poll(
        &mut [PollFd::from_borrowed_fd(stdin_fd, PollFlags::IN)],
        dur.as_millis() as i32,
    )?;

    tcsetattr(stdin_fd, OptionalActions::Flush, &orig_termios)?;
    stdout.clear_line()?;

    Ok(if ret != 0 {
        WaitResult::Interrupted
    } else {
        WaitResult::Normal
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Key {
    Close,
    Open,
    Lock,
    Stop,
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct KeyConfig<T> {
    pub close: T,
    pub open: T,
    pub lock: T,
    pub stop: T,
}
impl<T> Index<Key> for KeyConfig<T> {
    type Output = T;
    fn index(&self, index: Key) -> &Self::Output {
        match index {
            Key::Close => &self.close,
            Key::Open => &self.open,
            Key::Lock => &self.lock,
            Key::Stop => &self.stop,
        }
    }
}

#[derive(
    Debug, Clone, Copy, strum::Display, PartialEq, Eq, strum::VariantArray, Serialize, Deserialize,
)]
#[serde(rename_all = "snake_case")]
#[strum(serialize_all = "snake_case")]
pub enum TestResult {
    Pass,
    Fail,
}

#[derive(Debug, Clone, Serialize)]
pub struct TestKey {
    pub key: Key,
    pub result: TestResult,
    pub timestamp: SystemTime,
    pub instr: SendInstr,
}
#[derive(Debug, Serialize)]
pub struct TestCycle {
    pub start_time: SystemTime,
    pub end_time: SystemTime,
    pub test_keys: Vec<TestKey>,
}
#[derive(Debug, Serialize)]
pub struct TestRun {
    pub id: Uuid,
    pub instr: KeyConfig<SendInstr>,
    pub start_time: SystemTime,
    pub end_time: SystemTime,
    pub cycles: Vec<TestCycle>,
}

pub struct Tester<'a, W> {
    id: Uuid,
    instr: KeyConfig<SendInstr>,
    start: SystemTime,
    sender: Ykq368Endpoint<'a, W>,
    cycles: Vec<TestCycle>,
}
impl<'a, W: std::io::Write> Tester<'a, W> {
    pub fn new(instr: KeyConfig<SendInstr>, writer: &'a mut W) -> Result<Self> {
        let start = SystemTime::now();
        let id = Uuid::new_v7({
            let dur = start.duration_since(SystemTime::UNIX_EPOCH).unwrap();
            uuid::Timestamp::from_unix(uuid::NoContext, dur.as_secs(), dur.subsec_nanos())
        });
        println!("======== test {id} ========\ninstr config: {instr:#?}");
        writer
            .write_all(&[0x03])
            .context("failed to select endpoint")?;
        Ok(Self {
            id,
            instr,
            start,
            sender: Ykq368Endpoint::new(writer),
            cycles: Vec::new(),
        })
    }
    pub fn with_cycle<'t, T>(
        &'t mut self,
        f: impl FnOnce(&mut CycleTester<W>) -> Result<T>,
    ) -> Result<T> {
        println!("==== test cycle {} ====", self.cycles.len());
        let mut tester = CycleTester {
            tester: self,
            start_time: SystemTime::now(),
            tests: Vec::new(),
            failed: Vec::new(),
        };
        let ret = f(&mut tester)?;
        tester.finish();
        Ok(ret)
    }
    pub fn finish(self) -> Result<TestRun> {
        self.sender.exit().context("failed to exit endpoint")?;

        Ok(TestRun {
            id: self.id,
            instr: self.instr,
            start_time: self.start,
            end_time: SystemTime::now(),
            cycles: self.cycles,
        })
    }
}

pub struct CycleTester<'t, 'a, W> {
    tester: &'t mut Tester<'a, W>,
    start_time: SystemTime,
    tests: Vec<TestKey>,
    failed: Vec<TestKey>,
}
impl<'t, 'a, W: std::io::Write> CycleTester<'t, 'a, W> {
    pub fn send(&mut self, key: Key, wait: Duration) -> Result<TestResult> {
        let timestamp = SystemTime::now();
        println!(
            "[{}] {key:?} {:?}",
            timestamp
                .duration_since(self.tester.start)
                .unwrap()
                .as_secs_f32(),
            self.tester.instr[key]
        );
        self.tester
            .sender
            .send(self.tester.instr[key].to_command())
            .context("failed to send command")?;
        let result = match wait_or_interrupt(wait).context("failed to wait on input")? {
            WaitResult::Normal => TestResult::Pass,
            WaitResult::Interrupted => {
                TestResult::VARIANTS[dialoguer::Select::with_theme(
                    &dialoguer::theme::ColorfulTheme::default(),
                )
                .with_prompt("does this test passed")
                .items(TestResult::VARIANTS)
                .interact()?]
            }
        };
        let test = TestKey {
            key,
            timestamp,
            result,
            instr: self.tester.instr[key].clone(),
        };
        if let TestResult::Fail = result {
            self.failed.push(test.clone());
        }
        self.tests.push(test);
        Ok(result)
    }
    pub fn finish(self) {
        let end_time = SystemTime::now();
        let total = self.tests.len();
        let failed = self.failed.len();
        let test_cycle = TestCycle {
            start_time: self.start_time,
            end_time,
            test_keys: self.tests,
        };
        println!(
            "Cycle report: {total} total; {} passed; {failed} failed",
            total - failed
        );
        self.tester.cycles.push(test_cycle);
    }
}
