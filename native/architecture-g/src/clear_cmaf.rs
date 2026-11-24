//! Clear-only, bounded diagnostic writer matching HevcClearCmafFragmentWriter.write.
//! Does not parse, decrypt or accept protected media metadata.
const MAX_BYTES: usize = 8 * 1024 * 1024;
const MAX_SAMPLES: usize = 4096;
pub struct Sample<'a> {
    pub data: &'a [u8],
    pub duration: u32,
    pub cto: i32,
    pub flags: u32,
}

pub fn size(samples: &[Sample<'_>]) -> Option<usize> {
    if samples.is_empty() || samples.len() > MAX_SAMPLES { return None; }
    let mut total = 100usize.checked_add(16usize.checked_mul(samples.len())?)?;
    for s in samples {
        if s.data.is_empty() || s.duration == 0 { return None; }
        total = total.checked_add(s.data.len())?;
    }
    (total <= MAX_BYTES).then_some(total)
}

struct Writer<'a> { dst: &'a mut [u8], pos: usize }
impl Writer<'_> {
    fn bytes(&mut self, bytes: &[u8]) {
        let end = self.pos + bytes.len();
        self.dst[self.pos..end].copy_from_slice(bytes);
        self.pos = end;
    }
    fn u32(&mut self, v: u32) { self.bytes(&v.to_be_bytes()); }
    fn header(&mut self, size: u32, kind: &[u8;4]) { self.u32(size); self.bytes(kind); }
    fn full(&mut self, size: u32, kind: &[u8;4], version_flags: u32) {
        self.header(size,kind); self.u32(version_flags);
    }
}

pub fn write(track: u32, sequence: u32, decode_time: u64, samples: &[Sample<'_>],
             output: &mut [u8]) -> Option<usize> {
    if track == 0 || sequence == 0 || decode_time > i64::MAX as u64 { return None; }
    let total = size(samples)?;
    if output.len() < total { return None; }
    let count = u32::try_from(samples.len()).ok()?;
    let moof = 92 + 16 * count;
    let mut w = Writer { dst: output, pos: 0 };
    w.header(moof,b"moof");
    w.full(16,b"mfhd",0); w.u32(sequence);
    w.header(68 + 16 * count,b"traf");
    w.full(20,b"tfhd",0x0002_0002); w.u32(track); w.u32(1);
    w.full(20,b"tfdt",0x0100_0000); w.bytes(&decode_time.to_be_bytes());
    w.full(20 + 16 * count,b"trun",0x0100_0f01);
    w.u32(count); w.u32(moof + 8);
    for s in samples {
        w.u32(s.duration); w.u32(s.data.len() as u32); w.u32(s.flags);
        w.bytes(&s.cto.to_be_bytes());
    }
    w.header((total - moof as usize) as u32,b"mdat");
    for s in samples { w.bytes(s.data); }
    debug_assert_eq!(w.pos,total);
    Some(total)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_offsets_and_atomic_rejection() {
        let samples=[Sample {data: &[1,2,3],duration:3600,cto:-1,flags:0x0200_0000}];
        let mut out=[0x55;128];
        assert_eq!(write(1,2,3,&samples,&mut out),Some(119));
        assert_eq!(&out[..8],&[0,0,0,108,b'm',b'o',b'o',b'f']);
        assert_eq!(&out[108..119],&[0,0,0,11,b'm',b'd',b'a',b't',1,2,3]);
        assert_eq!(&out[119..],&[0x55;9]);
        let before=out;
        assert_eq!(write(0,2,3,&samples,&mut out),None);
        assert_eq!(write(1,2,3,&samples,&mut out[..118]),None);
        assert_eq!(write(1,2,3,&[],&mut out),None);
        assert_eq!(out,before);
    }
}
