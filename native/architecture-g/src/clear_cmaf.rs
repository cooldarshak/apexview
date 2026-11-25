//! Bounded clear fragment packaging, matching HevcClearCmafFragmentWriter.
use crate::bmff::MAX_ATLAS_BYTES;
use crate::clear_slice::{Parts, SlabPart};

const MAX_SAMPLES: usize = 4096;
const MAX_NALS_PER_SAMPLE: usize = 4096;

pub struct Sample<'a> {
    pub data: &'a [u8],
    pub duration: u32,
    pub cto: i32,
    pub flags: u32,
}

pub struct NalSample<'a> {
    pub nals: &'a [Parts<'a>],
    pub duration: u32,
    pub cto: i32,
    pub flags: u32,
}

pub(crate) struct SlabNalSample<'a> {
    pub nals: &'a [SlabPart<'a>],
    pub duration: u32,
    pub cto: i32,
    pub flags: u32,
}

#[derive(Clone, Copy)]
struct Layout {
    size: usize,
    duration: u32,
    cto: i32,
    flags: u32,
}

impl Sample<'_> {
    fn layout(&self) -> Option<Layout> {
        Some(Layout {
            size: self.data.len(),
            duration: self.duration,
            cto: self.cto,
            flags: self.flags,
        })
    }
}

impl NalSample<'_> {
    fn layout(&self, length_size: usize) -> Option<Layout> {
        if self.nals.is_empty() || self.nals.len() > MAX_NALS_PER_SAMPLE {
            return None;
        }
        let size = nal_size_total(self.nals.iter().map(|nal| Some((nal.header.len(), nal.tail.len()))), length_size)?;
        Some(Layout {
            size,
            duration: self.duration,
            cto: self.cto,
            flags: self.flags,
        })
    }
}

impl SlabNalSample<'_> {
    fn layout(&self, slab: &[u8], length_size: usize) -> Option<Layout> {
        if self.nals.is_empty() || self.nals.len() > MAX_NALS_PER_SAMPLE {
            return None;
        }
        let size = nal_size_total(self.nals.iter().map(|nal|
            Some((slab.get(nal.header.clone())?.len(), nal.tail.len()))), length_size)?;
        Some(Layout { size, duration: self.duration, cto: self.cto, flags: self.flags })
    }
}

fn nal_size_total(mut parts: impl Iterator<Item = Option<(usize, usize)>>, length_size: usize) -> Option<usize> {
    parts.try_fold(0usize, |total, part| {
        let (header, tail) = part?;
        let nal_size = header.checked_add(tail)?;
        if length_size < 4 && nal_size >= 1usize << (8 * length_size) {
            return None;
        }
        total.checked_add(length_size)?.checked_add(nal_size)
    })
}

pub(crate) fn slab_nal_parts_size(samples: &[SlabNalSample<'_>], slab: &[u8], length_size: usize) -> Option<usize> {
    if !(1..=4).contains(&length_size) { return None; }
    total_size(samples.iter().map(|sample| sample.layout(slab, length_size)))
}

fn total_size(mut layout: impl ExactSizeIterator<Item = Option<Layout>>) -> Option<usize> {
    let count = layout.len();
    if !(1..=MAX_SAMPLES).contains(&count) {
        return None;
    }
    let base = 100 + 16 * count;
    let total = layout.try_fold(base, |total, sample| {
        let sample = sample?;
        if sample.size == 0 || sample.duration == 0 {
            return None;
        }
        total.checked_add(sample.size)
    })?;
    (total <= MAX_ATLAS_BYTES).then_some(total)
}

pub fn size(samples: &[Sample<'_>]) -> Option<usize> {
    total_size(samples.iter().map(Sample::layout))
}

pub fn nal_parts_size(samples: &[NalSample<'_>], length_size: usize) -> Option<usize> {
    if !(1..=4).contains(&length_size) {
        return None;
    }
    total_size(samples.iter().map(|sample| sample.layout(length_size)))
}

struct Writer<'a> {
    dst: &'a mut [u8],
    pos: usize,
}
impl Writer<'_> {
    fn bytes(&mut self, bytes: &[u8]) {
        let end = self.pos + bytes.len();
        self.dst[self.pos..end].copy_from_slice(bytes);
        self.pos = end;
    }

    fn u32(&mut self, v: u32) {
        self.bytes(&v.to_be_bytes());
    }
    fn header(&mut self, size: u32, kind: &[u8; 4]) {
        self.u32(size);
        self.bytes(kind);
    }

    fn full(&mut self, size: u32, kind: &[u8; 4], version_flags: u32) {
        self.header(size, kind);
        self.u32(version_flags);
    }
}

fn start_fragment<'a>(
    track: u32,
    sequence: u32,
    decode_time: u64,
    layout: impl ExactSizeIterator<Item = Option<Layout>>,
    total: usize,
    output: &'a mut [u8],
) -> Option<Writer<'a>> {
    if track == 0 || sequence == 0 || decode_time > i64::MAX as u64 || output.len() < total {
        return None;
    }
    let count = layout.len() as u32;
    let moof = 92 + 16 * count;
    let mut w = Writer {
        dst: output,
        pos: 0,
    };
    w.header(moof, b"moof");
    w.full(16, b"mfhd", 0);
    w.u32(sequence);
    w.header(68 + 16 * count, b"traf");
    w.full(20, b"tfhd", 0x0002_0002);
    w.u32(track);
    w.u32(1);
    w.full(20, b"tfdt", 0x0100_0000);
    w.bytes(&decode_time.to_be_bytes());
    w.full(20 + 16 * count, b"trun", 0x0100_0f01);
    w.u32(count);
    w.u32(moof + 8);
    for sample in layout {
        let sample = sample.expect("layout was checked before writing");
        w.u32(sample.duration);
        w.u32(sample.size as u32);
        w.u32(sample.flags);
        w.bytes(&sample.cto.to_be_bytes());
    }
    w.header((total - moof as usize) as u32, b"mdat");
    Some(w)
}

pub fn write(
    track: u32,
    sequence: u32,
    decode_time: u64,
    samples: &[Sample<'_>],
    output: &mut [u8],
) -> Option<usize> {
    let total = size(samples)?;
    let mut w = start_fragment(
        track,
        sequence,
        decode_time,
        samples.iter().map(Sample::layout),
        total,
        output,
    )?;
    for sample in samples {
        w.bytes(sample.data);
    }
    debug_assert_eq!(w.pos, total);
    Some(total)
}

/// Copies each escaped header and borrowed tail straight to the final mdat.
/// No intermediate sample or full rewritten VCL buffer is needed.
pub fn write_nal_parts(
    track: u32,
    sequence: u32,
    decode_time: u64,
    samples: &[NalSample<'_>],
    length_size: usize,
    output: &mut [u8],
) -> Option<usize> {
    let total = nal_parts_size(samples, length_size)?;
    let layout = samples.iter().map(|sample| sample.layout(length_size));
    let mut w = start_fragment(track, sequence, decode_time, layout, total, output)?;
    for sample in samples {
        for nal in sample.nals {
            let size = (nal.header.len() + nal.tail.len()) as u32;
            w.bytes(&size.to_be_bytes()[4 - length_size..]);
            w.bytes(&nal.header);
            w.bytes(nal.tail);
        }
    }
    debug_assert_eq!(w.pos, total);
    Some(total)
}

/// Direct final mdat publication from validated header ranges and borrowed tails.
pub(crate) fn write_slab_nal_parts(
    track: u32, sequence: u32, decode_time: u64, samples: &[SlabNalSample<'_>],
    slab: &[u8], length_size: usize, output: &mut [u8],
) -> Option<usize> {
    let total = slab_nal_parts_size(samples, slab, length_size)?;
    let layout = samples.iter().map(|sample| sample.layout(slab, length_size));
    let mut w = start_fragment(track, sequence, decode_time, layout, total, output)?;
    for sample in samples {
        for nal in sample.nals {
            let header = &slab[nal.header.clone()]; // ranges checked before any output write
            let size = (header.len() + nal.tail.len()) as u32;
            w.bytes(&size.to_be_bytes()[4 - length_size..]);
            w.bytes(header);
            w.bytes(nal.tail);
        }
    }
    debug_assert_eq!(w.pos, total);
    Some(total)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_offsets_and_atomic_rejection() {
        let samples = [Sample {
            data: &[1, 2, 3],
            duration: 3600,
            cto: -1,
            flags: 0x0200_0000,
        }];
        let mut out = [0x55; 128];
        assert_eq!(write(1, 2, 3, &samples, &mut out), Some(119));
        assert_eq!(&out[..8], &[0, 0, 0, 108, b'm', b'o', b'o', b'f']);
        assert_eq!(
            &out[108..119],
            &[0, 0, 0, 11, b'm', b'd', b'a', b't', 1, 2, 3]
        );
        assert_eq!(&out[119..], &[0x55; 9]);
        let before = out;
        assert_eq!(write(0, 2, 3, &samples, &mut out), None);
        assert_eq!(write(1, 2, 3, &samples, &mut out[..118]), None);
        assert_eq!(write(1, 2, 3, &[], &mut out), None);
        assert_eq!(out, before);
    }

    #[test]
    fn slab_parts_match_owned_parts_and_reject_invalid_ranges_atomically() {
        let slab = [2, 1, 0xcc, 2, 1, 0x11];
        let parts = [
            SlabPart { header: 0..3, tail: &[0xab, 0xce] },
            SlabPart { header: 3..6, tail: &[0xdd] },
        ];
        let owned = [
            Parts { header: slab[..3].to_vec(), tail: &[0xab, 0xce] },
            Parts { header: slab[3..].to_vec(), tail: &[0xdd] },
        ];
        let samples = [SlabNalSample { nals: &parts, duration: 17, cto: -5, flags: 3 }];
        let reference = [NalSample { nals: &owned, duration: 17, cto: -5, flags: 3 }];
        for width in 1..=4 {
            let mut expected = [0x77; 150];
            let mut actual = expected;
            assert_eq!(write_slab_nal_parts(9, 2, 40, &samples, &slab, width, &mut actual),
                write_nal_parts(9, 2, 40, &reference, width, &mut expected));
            assert_eq!(actual, expected);
        }
        let invalid = [SlabPart { header: 0..7, tail: &[1] }];
        let samples = [SlabNalSample { nals: &invalid, duration: 17, cto: -5, flags: 3 }];
        let mut out = [0x77; 150];
        assert_eq!(write_slab_nal_parts(9, 2, 40, &samples, &slab, 4, &mut out), None);
        assert_eq!(out, [0x77; 150]);
    }

    #[test]
    fn borrowed_parts_match_contiguous_samples_for_all_prefix_widths() {
        let nals = [
            Parts {
                header: vec![2, 1, 0xcc],
                tail: &[0xab, 0xce],
            },
            Parts {
                header: vec![2, 1, 0x11],
                tail: &[0xdd],
            },
        ];
        let samples = [NalSample {
            nals: &nals,
            duration: 17,
            cto: -5,
            flags: 3,
        }];
        for width in 1..=4 {
            let bytes = [
                &5u32.to_be_bytes()[4 - width..],
                &[2, 1, 0xcc, 0xab, 0xce],
                &4u32.to_be_bytes()[4 - width..],
                &[2, 1, 0x11, 0xdd],
            ]
            .concat();
            let contiguous = [Sample {
                data: &bytes,
                duration: 17,
                cto: -5,
                flags: 3,
            }];
            let mut expected = [0; 150];
            let mut direct = [0; 150];
            assert_eq!(
                write_nal_parts(9, 2, 40, &samples, width, &mut direct),
                write(9, 2, 40, &contiguous, &mut expected)
            );
            assert_eq!(direct, expected);
        }
        let mut short = [0x77; 100];
        assert_eq!(write_nal_parts(9, 2, 40, &samples, 4, &mut short), None);
        assert_eq!(short, [0x77; 100]);
        let large = [Parts {
            header: vec![2, 1],
            tail: &[0; 254],
        }];
        let large_samples = [NalSample {
            nals: &large,
            duration: 17,
            cto: 0,
            flags: 3,
        }];
        assert_eq!(nal_parts_size(&large_samples, 1), None);
        assert!(nal_parts_size(&large_samples, 2).is_some());
    }
}
