use pitwall_g0::annex_b_size;
use std::{env, fs, hint::black_box, time::Instant};
fn main() {
    let args: Vec<_> = env::args().collect();
    assert_eq!(args.len(),4,"bench FILE PREFIX EXPECTED");
    let bytes=fs::read(&args[1]).unwrap();
    let prefix:usize=args[2].parse().unwrap();
    let expected:usize=args[3].parse().unwrap();
    assert_eq!(annex_b_size(&bytes,prefix),Some(expected));
    let work=|| {
        for _ in 0..256 {
            assert_eq!(black_box(annex_b_size(black_box(&bytes),black_box(prefix))),Some(expected));
        }
    };
    for _ in 0..30 {work();}
    let mut times=Vec::with_capacity(101);
    for _ in 0..101 {
        let start=Instant::now();work();times.push(start.elapsed().as_nanos()/256);
    }
    times.sort_unstable();
    println!("G0 rust prefix={prefix} bytes={} result={expected} p50_ns={} p95_ns={} p99_ns={} max_ns={}",bytes.len(),times[50],times[95],times[99],times[100]);
}
