use pitwall_g0::clear_slice::{rewrite,Syntax};
use std::{env,fs,hint::black_box,path::PathBuf,time::Instant};
fn main() {
    let root=PathBuf::from(env::args_os().nth(1).expect("fixture directory"));
    let mut dirs:Vec<_>=fs::read_dir(root).unwrap().map(|x|x.unwrap().path()).filter(|x|x.is_dir()).collect();
    dirs.sort();
    assert_eq!(dirs.len(),6);
    for d in dirs {
        let descriptor=fs::read_to_string(d.join("syntax.txt")).unwrap();
        let f:Vec<usize>=descriptor.split_whitespace().map(|x|x.parse().unwrap()).collect();
        assert_eq!(f.len(),11);
        let s=Syntax{address_end:f[0],entry_start:f[1],entry_end:f[2],alignment:f[3],header_bytes:f[4],
            destination:f[5] as u32,address_bits:f[6],pps:f[7] as u32,dependent_flag_present:f[8]!=0,tiles:f[9]!=0,no_output:f[10]!=0};
        let source=fs::read(d.join("source.bin")).unwrap();
        let expected=fs::read(d.join("expected.bin")).unwrap();
        let mut output=vec![0;expected.len()];
        let mut times=Vec::with_capacity(101);
        for _ in 0..32 {assert_eq!(rewrite(&source,s,&mut output),Some(expected.len()));}
        for _ in 0..101 {
            let start=Instant::now();
            for _ in 0..16 {assert_eq!(rewrite(black_box(&source),black_box(s),black_box(&mut output)),Some(expected.len()));}
            times.push(start.elapsed().as_nanos()/16);
        }
        assert_eq!(output,expected);
        times.sort_unstable();
        println!("G0 clear slice rust case={} bytes={} p50Ns={} p95Ns={} p99Ns={} maxNs={} exactMatch=true prevalidatedSyntax=true",d.file_name().unwrap().to_string_lossy(),source.len(),times[50],times[95],times[99],times[100]);
    }
}
