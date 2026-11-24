use pitwall_g0::clear_cmaf::{size,write,Sample};
use std::{env,fs,hint::black_box,path::PathBuf,time::Instant};
fn main() {
    let root=PathBuf::from(env::args_os().nth(1).expect("fixture directory"));
    let mut dirs:Vec<_>=fs::read_dir(root).unwrap().map(|v|v.unwrap().path()).filter(|p|p.is_dir()).collect();
    dirs.sort();
    assert_eq!(dirs.len(),3,"all three JVM fixtures must exist");
    for dir in dirs {
        let manifest=fs::read_to_string(dir.join("manifest.txt")).unwrap();
        let mut rows=manifest.lines();
        let header:Vec<_>=rows.next().unwrap().split_whitespace().collect();
        let track=header[0].parse().unwrap(); let seq=header[1].parse().unwrap(); let time=header[2].parse().unwrap();
        let records:Vec<_>=rows.map(|row| {
            let v:Vec<_>=row.split_whitespace().collect();
            (fs::read(dir.join(v[0])).unwrap(),v[1].parse().unwrap(),v[2].parse().unwrap(),v[3].parse().unwrap())
        }).collect();
        let samples:Vec<_>=records.iter().map(|(data,duration,cto,flags)|Sample{data,duration:*duration,cto:*cto,flags:*flags}).collect();
        let expected=fs::read(dir.join("expected.m4s")).unwrap();
        let mut out=vec![0;size(&samples).unwrap()];
        assert_eq!(write(track,seq,time,&samples,&mut out),Some(expected.len()));
        assert_eq!(out,expected,"native bytes must equal exact JVM output");
        let mut times=Vec::with_capacity(101);
        for _ in 0..32 { assert_eq!(write(track,seq,time,&samples,&mut out),Some(expected.len())); }
        for _ in 0..101 {
            let start=Instant::now();
            for _ in 0..16 {
                assert_eq!(write(track,seq,time,black_box(&samples),black_box(&mut out)),Some(expected.len()));
            }
            times.push(start.elapsed().as_nanos()/16);
        }
        times.sort_unstable();
        assert_eq!(out,expected);
        println!("G0 CMAF rust case={} samples={} bytes={} p50Ns={} p95Ns={} maxNs={} exactMatch=true outputReused=true",dir.file_name().unwrap().to_string_lossy(),samples.len(),out.len(),times[50],times[95],times[100]);
    }
}
