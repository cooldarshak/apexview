use pitwall_g0::feasibility::{Bits,top_level_boxes,synthetic_subsamples};
use std::{env,fs,hint::black_box,time::Instant};

fn hex(s: &str) -> Vec<u8> {
    s.as_bytes().chunks_exact(2).map(|v|u8::from_str_radix(std::str::from_utf8(v).unwrap(),16).unwrap()).collect()
}
fn timed(mut work: impl FnMut()) -> (u128,u128,u128) {
    for _ in 0..32 { work(); }
    let mut values = Vec::with_capacity(101);
    for _ in 0..101 {
        let start = Instant::now();
        for _ in 0..128 { work(); }
        values.push(start.elapsed().as_nanos()/128);
    }
    values.sort_unstable();
    (values[50],values[95],values[100])
}
fn main() {
    let path=env::args().nth(1).expect("fixture manifest");
    let manifest=fs::read_to_string(path).unwrap();
    for row in manifest.lines() {
        let f:Vec<_>=row.split_whitespace().collect();
        let (p50,p95,max)=match f[0] {
            "bits" => {
                let input=hex(f[2]); let count:usize=f[3].parse().unwrap();
                let expected:u64=f[4].parse().unwrap(); let offset:usize=f[5].parse().unwrap();
                timed(|| {
                    let mut b=Bits::new(black_box(&input)).unwrap();
                    let v=if count==0 {b.ue().unwrap()} else {u64::from(b.read(black_box(count)).unwrap())};
                    assert_eq!(black_box(v),expected);
                    assert_eq!(b.position(),offset);
                })
            },
            "boxes" => {
                let input=hex(f[2]); let expected:usize=f[3].parse().unwrap();
                timed(||assert_eq!(black_box(top_level_boxes(black_box(&input))),Some(expected)))
            },
            "meta" => {
                let prefix:usize=f[2].parse().unwrap(); let clear:usize=f[3].parse().unwrap();
                let encrypted:Vec<u32>=f[4].split(',').map(|v|v.parse().unwrap()).collect();
                let expected:Vec<(u16,u32)>=f[5].split(',').map(|v|{let (a,b)=v.split_once(':').unwrap();(a.parse().unwrap(),b.parse().unwrap())}).collect();
                let mut out=vec![(0,0);expected.len()];
                timed(||{
                    assert_eq!(synthetic_subsamples(black_box(prefix),black_box(clear),black_box(&encrypted),&mut out),Some(expected.len()));
                    assert_eq!(black_box(&out),&expected);
                })
            },
            _=>panic!("unexpected fixture {}",f[0]),
        };
        println!("G0 kernel rust case={} p50Ns={p50} p95Ns={p95} maxNs={max}",f[1]);
    }
}
