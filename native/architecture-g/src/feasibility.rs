//! Bounded diagnostic kernels. No production player or protected payload processing.
const LIMIT: usize = 8 * 1024 * 1024;

pub struct Bits<'a> {
    data: &'a [u8],
    offset: usize,
}
impl<'a> Bits<'a> {
    pub fn new(data: &'a [u8]) -> Option<Self> {
        (data.len() <= LIMIT).then_some(Self { data, offset: 0 })
    }
    pub fn read(&mut self, count: usize) -> Option<u32> {
        if count > 32 || count > self.data.len() * 8 - self.offset { return None; }
        let mut value = 0u32;
        for _ in 0..count {
            value = (value << 1) | u32::from((self.data[self.offset / 8] >> (7 - self.offset % 8)) & 1);
            self.offset += 1;
        }
        Some(value)
    }
    pub fn ue(&mut self) -> Option<u64> {
        let mut zeros = 0usize;
        while self.read(1)? == 0 {
            zeros += 1;
            if zeros > 31 { return None; }
        }
        Some((1u64 << zeros) - 1 + u64::from(self.read(zeros)?))
    }
    pub fn position(&self) -> usize { self.offset }
}

/// Exact top-level ISO-BMFF extent scan. Not a complete CMAF inspector.
pub fn top_level_boxes(data: &[u8]) -> Option<usize> {
    if !(8..=LIMIT).contains(&data.len()) { return None; }
    let mut pos = 0usize;
    let mut count = 0;
    while pos < data.len() {
        let remaining = data.get(pos..)?;
        let size = u32::from_be_bytes(remaining.get(..4)?.try_into().ok()?);
        remaining.get(..8)?;
        let (length, header) = match size {
            0 => (remaining.len(),8),
            1 => (usize::try_from(u64::from_be_bytes(remaining.get(8..16)?.try_into().ok()?)).ok()?,16),
            n => (usize::try_from(n).ok()?,8),
        };
        if length < header || length > remaining.len() { return None; }
        count += 1;
        if count > 20_000 { return None; }
        pos += length;
    }
    Some(count)
}

/// Synthetic metadata-only accounting experiment. Accepts one NAL's clear
/// header run and lengths of subsequent opaque encrypted subsample ranges.
/// Never receives ciphertext, keys, IVs, DRM state or live media.
pub fn synthetic_subsamples(prefix: usize, clear: usize, encrypted: &[u32],
                            out: &mut [(u16,u32)]) -> Option<usize> {
    if !(1..=4).contains(&prefix) || clear < 2 || encrypted.is_empty() ||
        encrypted.len() > 4096 || encrypted.contains(&0) { return None; }
    let mut size = clear;
    for &n in encrypted { size = size.checked_add(usize::try_from(n).ok()?)?; }
    if size > LIMIT || (prefix < 4 && size >= (1usize << (prefix * 8))) { return None; }
    let clear_total = clear.checked_add(prefix)?;
    let leading = (clear_total - 1) / 65535;
    let count = leading.checked_add(encrypted.len())?;
    if count > out.len() { return None; }
    out[..leading].fill((65535,0));
    let rest = u16::try_from(clear_total - leading * 65535).ok()?;
    for (i,&n) in encrypted.iter().enumerate() {
        out[leading+i] = (if i == 0 {rest} else {0}, n);
    }
    Some(count)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bits_reject_truncation_and_large_codes() {
        let mut b = Bits::new(&[0xa6]).unwrap();
        assert_eq!(b.read(3),Some(5));
        assert_eq!(b.position(),3);
        assert_eq!(b.read(6),None);
        assert_eq!(b.position(),3);
        assert_eq!(Bits::new(&[0;8]).unwrap().ue(),None);
        assert_eq!(Bits::new(&[0x80]).unwrap().ue(),Some(0));
    }
    #[test]
    fn boxes_reject_bounds_and_trailing_bytes() {
        assert_eq!(top_level_boxes(b"\0\0\0\x08free"),Some(1));
        assert_eq!(top_level_boxes(b"\0\0\0\x07free"),None);
        assert_eq!(top_level_boxes(b"\0\0\0\x08freeX"),None);
        assert_eq!(top_level_boxes(b"\0\0\0\x01free"),None);
    }
    #[test]
    fn metadata_preserves_boundaries_and_rejects_before_write() {
        let mut out = [(99,99);4];
        assert_eq!(synthetic_subsamples(4,70000,&[16,32],&mut out),Some(3));
        assert_eq!(&out[..3],&[(65535,0),(4469,16),(0,32)]);
        let before = out;
        assert_eq!(synthetic_subsamples(1,70000,&[16],&mut out),None);
        assert_eq!(synthetic_subsamples(4,2,&[0],&mut out),None);
        assert_eq!(synthetic_subsamples(4,2,&[16,32],&mut out[..1]),None);
        assert_eq!(out,before);
    }
}
