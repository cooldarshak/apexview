use pitwall_g0::clear_slice::{rewrite,rewrite_scalar,Syntax};
use std::{env,fs,hint::black_box,path::PathBuf,time::Instant};
fn main() {
    let root=PathBuf::from(env::args_os().nth(1).expect("fixture directory"));
    let mut dirs:Vec<_>=fs::read_dir(root).unwrap().map(|x|x.unwrap().path()).filter(|x|x.is_dir()).collect();
    dirs.sort();
    assert_eq!(dirs.len(),106);
    let mut encoded_verified=0usize;
    let mut synthetic_verified=0usize;
    for d in dirs {
        let descriptor=fs::read_to_string(d.join("syntax.txt")).unwrap();
        let f:Vec<usize>=descriptor.split_whitespace().map(|x|x.parse().unwrap()).collect();
        assert_eq!(f.len(),11);
        let s=Syntax{address_end:f[0],entry_start:f[1],entry_end:f[2],alignment:f[3],header_bytes:f[4],
            destination:f[5] as u32,address_bits:f[6],pps:f[7] as u32,dependent_flag_present:f[8]!=0,tiles:f[9]!=0,no_output:f[10]!=0};
        let source=fs::read(d.join("source.bin")).unwrap();
        let expected=fs::read(d.join("expected.bin")).unwrap();
        let mut output=vec![0;expected.len()];
        let mut scalar=vec![0;expected.len()];
        assert_eq!(rewrite(&source,s,&mut output),Some(expected.len()));
        assert_eq!(rewrite_scalar(&source,s,&mut scalar),Some(expected.len()));
        assert_eq!(output,expected);
        assert_eq!(scalar,expected);
        let case=d.file_name().unwrap().to_string_lossy();
        if case.starts_with("encoded-") {
            encoded_verified+=1;
            // Byte equality is mandatory on all 100 slices. Time only the
            // first IDR and following inter picture for each independent feed.
            if !(case.ends_with("-frame-0") || case.ends_with("-frame-1")) {continue;}
        } else {synthetic_verified+=1;}
        let mut times=Vec::with_capacity(101);
        let mut scalar_times=Vec::with_capacity(101);
        for _ in 0..32 {assert_eq!(rewrite(&source,s,&mut output),Some(expected.len()));}
        for _ in 0..101 {
            let start=Instant::now();
            for _ in 0..16 {assert_eq!(rewrite(black_box(&source),black_box(s),black_box(&mut output)),Some(expected.len()));}
            times.push(start.elapsed().as_nanos()/16);
            let start=Instant::now();
            for _ in 0..16 {assert_eq!(rewrite_scalar(black_box(&source),black_box(s),black_box(&mut scalar)),Some(expected.len()));}
            scalar_times.push(start.elapsed().as_nanos()/16);
        }
        assert_eq!(output,expected);
        assert_eq!(scalar,expected);
        times.sort_unstable();
        scalar_times.sort_unstable();
        println!("G0 clear slice rust case={} bytes={} p50Ns={} p95Ns={} p99Ns={} maxNs={} exactMatch=true prevalidatedSyntax=true",case,source.len(),times[50],times[95],times[99],times[100]);
        println!("G0 clear slice scalar-control case={} bytes={} p50Ns={} p95Ns={} p99Ns={} maxNs={} exactMatch=true",case,source.len(),scalar_times[50],scalar_times[95],scalar_times[99],scalar_times[100]);
    }
    assert_eq!(encoded_verified,100);
    assert_eq!(synthetic_verified,6);
    println!("G0 clear slice differential PASS synthetic={synthetic_verified} realEncoded={encoded_verified}");
}
