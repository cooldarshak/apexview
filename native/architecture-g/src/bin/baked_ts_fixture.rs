//! Host-only synthetic baked AVC/AAC fixture. The Android mux is the same Writer.
use pitwall_g0::ts_mux::{verify_packets, Writer};
use std::{env, fs, path::Path};

fn main() {
    let args: Vec<_> = env::args_os().collect();
    assert_eq!(args.len(), 2, "usage: baked_ts_fixture <fixture-directory>");
    let dir = Path::new(&args[1]);
    let mut continuity = [0u8; 4];
    for segment in 0..2 {
        let input = fs::read(dir.join(format!("segment-{segment}.samples"))).expect("fixture input");
        let mut pos = 0usize;
        let mut samples = Vec::new();
        let mut packets = 2usize;
        while pos < input.len() {
            let header = input.get(pos..pos + 13).expect("sample header");
            let pts = i64::from_be_bytes(header[..8].try_into().unwrap());
            let size = u32::from_be_bytes(header[8..12].try_into().unwrap()) as usize;
            let flags = header[12];
            assert!(pts >= 0 && size > 0 && flags <= 3);
            let video = flags & 1 != 0;
            let key = flags & 2 != 0;
            assert!(!key || video);
            pos += 13;
            let bytes = input.get(pos..pos.checked_add(size).unwrap()).expect("sample extent");
            pos += size;
            let first_capacity = if video { 176 } else { 184 };
            packets += 1 + (size + 14).saturating_sub(first_capacity).div_ceil(184);
            samples.push((pts, bytes, video, key));
        }
        let mut output = vec![0u8; packets * 188];
        let initial = continuity;
        let mut writer = Writer::new(&mut output, initial).unwrap();
        writer.tables().unwrap();
        for (pts, data, video, key) in samples {
            writer.sample(pts, data.len(), video, key, |offset, target| {
                target.copy_from_slice(data.get(offset..offset + target.len())?);
                Some(())
            }).unwrap();
        }
        continuity = writer.finish().unwrap();
        assert_eq!(verify_packets(&output, initial), Some(continuity));
        fs::write(dir.join(format!("segment-{segment}.ts")), &output).expect("fixture output");
    }
}
