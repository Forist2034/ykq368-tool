use std::{
    env::args,
    fs::File,
    io::{self, Read},
    iter::once,
};

struct DataStream {
    data_str: String,
    count: u8,
}
impl DataStream {
    fn no_signal(&mut self, start: usize, end: usize) {
        println!("_ [{start}, {end}]: {}", end - start);
        self.data_str.push('\n');
        self.count = 0;
    }
    fn bit(&mut self, val: char, pos_edge: usize, neg_edge: usize, end: usize) {
        println!(
            "{val} [{pos_edge}, {end}]: {} ({}, {})",
            end - pos_edge,
            neg_edge - pos_edge,
            end - neg_edge
        );
        if self.count == 4 {
            self.data_str.push(' ');
            self.count = 0;
        }
        self.data_str.push(val);
        self.count += 1;
    }
}

fn main() {
    let input_path = {
        let mut a = args();
        a.next().expect("missing program path");
        a.next().expect("missing input file")
    };

    let input = io::BufReader::new(File::open(input_path).unwrap());

    let mut last_val = false;
    let mut last_pos_edge = 0;
    let mut last_neg_edge = 0;

    let mut data = DataStream {
        data_str: String::new(),
        count: 0,
    };

    for (byte_idx, byte) in input.bytes().chain(once(Ok(1))).enumerate() {
        let mut byte = byte.unwrap();
        for bit_idx in 0..8 {
            let v = (byte & 0x80) != 0;
            if v != last_val {
                let current_time = (byte_idx << 3) | bit_idx;
                if v {
                    let pos_time = last_neg_edge - last_pos_edge;
                    if pos_time < 100 {
                        let bit_end_max = last_pos_edge + 110;
                        if current_time > pos_time + 90 {
                            let (bit_end, is_last_bit) = if current_time < bit_end_max {
                                (current_time, false)
                            } else {
                                (bit_end_max, true)
                            };
                            match pos_time {
                                10..=29 => data.bit('1', last_pos_edge, last_neg_edge, bit_end),
                                40..=79 => data.bit('0', last_pos_edge, last_neg_edge, bit_end),
                                _ => data.bit('?', last_pos_edge, last_neg_edge, bit_end),
                            }
                            if is_last_bit {
                                data.no_signal(bit_end, current_time);
                            }
                        } else {
                            data.bit('?', last_pos_edge, last_neg_edge, current_time);
                        }
                    } else {
                        data.bit('?', last_pos_edge, last_neg_edge, current_time);
                    }
                    last_pos_edge = current_time;
                    // }
                } else {
                    // negative edge
                    last_neg_edge = current_time;
                }
                last_val = v;
            }
            byte <<= 1;
        }
    }

    println!("\ndata:\n{}", data.data_str);
}
