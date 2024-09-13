pub mod ykq368 {
    use std::{fmt::Debug, io};

    use serde::{Deserialize, Serialize};

    #[derive(Debug, Clone, Copy, Serialize, Deserialize)]
    #[serde(rename_all = "snake_case")]
    pub enum SendParts {
        Preamble,
        Data,
        All,
    }

    #[derive(Clone, Copy)]
    pub struct Preamble(pub u16);
    impl Debug for Preamble {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{:04x}", self.0)
        }
    }
    impl Serialize for Preamble {
        fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
        where
            S: serde::Serializer,
        {
            serializer.serialize_bytes(&self.0.to_be_bytes())
        }
    }

    #[derive(Clone, Copy)]
    pub struct Data(pub u64);
    impl Debug for Data {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{:09x}", self.0)
        }
    }
    impl Serialize for Data {
        fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
        where
            S: serde::Serializer,
        {
            serializer.serialize_bytes(&self.0.to_be_bytes()[3..])
        }
    }

    #[derive(Debug, Clone, Copy, Serialize)]
    pub struct SendInstr {
        pub send: SendParts,
        pub skip: u8,
        pub preamble: Preamble,
        pub data: Data,
        pub repeat: u8,
    }

    #[derive(Clone, Copy, PartialEq, Eq)]
    pub struct Command([u8; 8]);
    impl Debug for Command {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "Command(")?;
            for v in self.0 {
                write!(f, "{v:02x}")?;
            }
            f.write_str(")")
        }
    }

    impl SendInstr {
        pub const fn to_command(self) -> Command {
            let body = (self.preamble.0 as u64) << 35 | (self.data.0 & 0x7ffff_ffff);
            let body = body.to_be_bytes();
            Command([
                match self.send {
                    SendParts::Preamble => 0b110_00000,
                    SendParts::Data => 0b101_00000,
                    SendParts::All => 0b111_00000,
                } | (self.skip & 0x1f),
                self.repeat,
                body[2],
                body[3],
                body[4],
                body[5],
                body[6],
                body[7],
            ])
        }
    }

    pub struct Ykq368Endpoint<'a, W>(&'a mut W);
    impl<'a, W: io::Write> Ykq368Endpoint<'a, W> {
        pub fn new(inner: &'a mut W) -> Self {
            Self(inner)
        }
        pub fn send(&mut self, cmd: Command) -> io::Result<()> {
            self.0.write_all(&cmd.0)
        }
        pub fn exit(self) -> io::Result<()> {
            self.0.write_all(&[0; 8])
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn send_command() {
            assert_eq!(
                SendInstr {
                    send: SendParts::All,
                    skip: 0,
                    repeat: 1,
                    preamble: Preamble(0x007),
                    data: Data(0x312345678)
                }
                .to_command(),
                Command([0xe0, 0x01, 0x00, 0x3b, 0x12, 0x34, 0x56, 0x78])
            )
        }
    }
}
