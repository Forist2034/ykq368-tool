use std::{
    fs,
    io::{self, Read},
    process::ExitCode,
};

use anyhow::Context;
use clap::Parser;

const VAR_NAME: &str = "sig";

#[derive(Debug, Parser)]
struct Cli {
    #[arg(long)]
    time_scale: i32,
    gnu_radio_file: String,
    output_file: String,
}

/// [fstapi::Error] does not implement [std::error::Error], so wrap it
struct FstError(fstapi::Error);
impl std::fmt::Debug for FstError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(f)
    }
}
impl std::fmt::Display for FstError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(f)
    }
}
impl std::error::Error for FstError {}

fn run() -> anyhow::Result<()> {
    let cli = Cli::parse();
    let input = io::BufReader::new(
        fs::File::open(cli.gnu_radio_file).context("failed to open source file")?,
    );
    let mut output = fstapi::Writer::create(cli.output_file, true)
        .map_err(FstError)
        .context("failed to open output file")?
        .timescale(cli.time_scale);
    let var = output
        .create_var(
            fstapi::var_type::VCD_REG,
            fstapi::var_dir::INPUT,
            1,
            VAR_NAME,
            None,
        )
        .map_err(FstError)
        .context("failed to create fst var")?;
    let mut last_state = false;

    output
        .emit_value_change(var, &[b'0'])
        .map_err(FstError)
        .context("failed to write init value")?;

    for (byte_idx, packed) in input.bytes().enumerate() {
        let mut packed = packed.context("failed to read input")?;
        for bit_idx in 0..8 {
            let b = (packed & 0b1000_0000) != 0;
            if b != last_state {
                last_state = b;
                output
                    .emit_time_change(((byte_idx << 3) | bit_idx) as u64)
                    .map_err(FstError)
                    .context("failed to write fst time")?;
                output
                    .emit_value_change(var, &[if b { b'1' } else { b'0' }])
                    .map_err(FstError)
                    .context("failed to write value change")?;
            }
            packed <<= 1;
        }
    }
    output.flush();
    Ok(())
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("{e:?}");
            ExitCode::FAILURE
        }
    }
}
