use pitwall_g0::clear_slice::Syntax;
use std::{fs, path::Path};

pub fn slice_syntax(path: &Path) -> Syntax {
    let descriptor = fs::read_to_string(path).expect("JVM slice syntax descriptor");
    let fields: Vec<_> = descriptor
        .split_whitespace()
        .map(|word| word.parse().unwrap())
        .collect();
    let [address_end, entry_start, entry_end, alignment, header_bytes, destination, address_bits, pps, dependent, tiles, no_output] =
        fields[..]
    else {
        panic!("unexpected slice syntax descriptor");
    };
    assert!(dependent <= 1 && tiles <= 1 && no_output <= 1);
    Syntax {
        address_end,
        entry_start,
        entry_end,
        alignment,
        header_bytes,
        destination: u32::try_from(destination).unwrap(),
        address_bits,
        pps: u32::try_from(pps).unwrap(),
        dependent_flag_present: dependent == 1,
        tiles: tiles == 1,
        no_output: no_output == 1,
    }
}
