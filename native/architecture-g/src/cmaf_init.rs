//! Exact clear initialization packaging. Callers supply a validated hvcC
//! record with the output VPS/SPS/PPS; this layer does no HEVC geometry edits.
use crate::bmff::MAX_BYTES;
use crate::cmaf_input::{Error, HevcConfiguration};

// Sum of all fixed boxes and fields, excluding hvcC payload and the hdlr name.
const FIXED_BYTES: usize = 607;
const MATRIX: [u32; 9] = [0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000];

pub struct Request<'a> {
    pub track_id: u32,
    pub timescale: u32,
    pub width: u16,
    pub height: u16,
    pub handler_name: &'a str,
    pub configuration: HevcConfiguration<'a>,
}

impl Request<'_> {
    pub fn size(&self) -> Result<usize, Error> {
        let name = self.handler_name;
        if self.track_id == 0
            || self.track_id == u32::MAX
            || self.timescale == 0
            || self.width == 0
            || self.height == 0
            || name.trim().is_empty()
            || name.len() > 31
            || !name.bytes().all(|c| (0x20..=0x7e).contains(&c))
        {
            return Err(Error::Metadata);
        }
        let total = FIXED_BYTES + name.len() + self.configuration.record().len();
        if total > MAX_BYTES {
            return Err(Error::Limit);
        }
        Ok(total)
    }

    pub fn write(&self, output: &mut [u8]) -> Result<usize, Error> {
        let total = self.size()?;
        let output = output.get_mut(..total).ok_or(Error::Limit)?;
        let mut writer = Writer { output, pos: 0 };
        writer.iso_box(b"ftyp", |w| {
            w.bytes(b"iso6");
            w.u32(1);
            w.bytes(b"iso6cmfchvc1");
        });
        writer.iso_box(b"moov", |w| {
            w.full_box(b"mvhd", 0, |w| {
                w.zeros(8);
                w.u32(1000);
                w.u32(0);
                w.u32(0x10000);
                w.u16(0x100);
                w.zeros(10);
                w.matrix();
                w.zeros(24);
                w.u32(self.track_id + 1);
            });
            self.track(w);
            w.iso_box(b"mvex", |w| {
                w.full_box(b"trex", 0, |w| {
                    w.u32(self.track_id);
                    w.u32(1);
                    w.zeros(12);
                });
            });
        });
        debug_assert_eq!(writer.pos, total);
        Ok(total)
    }

    fn track(&self, w: &mut Writer<'_>) {
        w.iso_box(b"trak", |w| {
            w.full_box(b"tkhd", 7, |w| {
                w.zeros(8);
                w.u32(self.track_id);
                w.zeros(24);
                w.matrix();
                w.u32(u32::from(self.width) << 16);
                w.u32(u32::from(self.height) << 16);
            });
            w.iso_box(b"mdia", |w| {
                w.full_box(b"mdhd", 0, |w| {
                    w.zeros(8);
                    w.u32(self.timescale);
                    w.u32(0);
                    w.u16(0x55c4);
                    w.u16(0);
                });
                w.full_box(b"hdlr", 0, |w| {
                    w.u32(0);
                    w.bytes(b"vide");
                    w.zeros(12);
                    w.bytes(self.handler_name.as_bytes());
                    w.zeros(1);
                });
                self.media_information(w);
            });
        });
    }

    fn media_information(&self, w: &mut Writer<'_>) {
        w.iso_box(b"minf", |w| {
            w.full_box(b"vmhd", 1, |w| w.zeros(8));
            w.iso_box(b"dinf", |w| {
                w.full_box(b"dref", 0, |w| {
                    w.u32(1);
                    w.full_box(b"url ", 1, |_| {});
                });
            });
            w.iso_box(b"stbl", |w| {
                w.full_box(b"stsd", 0, |w| {
                    w.u32(1);
                    self.visual_sample_entry(w);
                });
                for kind in [b"stts", b"stsc", b"stsz", b"stco"] {
                    w.full_box(kind, 0, |w| w.zeros(if kind == b"stsz" { 8 } else { 4 }));
                }
            });
        });
    }

    fn visual_sample_entry(&self, w: &mut Writer<'_>) {
        w.iso_box(b"hvc1", |w| {
            w.zeros(6);
            w.u16(1);
            w.zeros(16);
            w.u16(self.width);
            w.u16(self.height);
            w.u32(0x00480000);
            w.u32(0x00480000);
            w.u32(0);
            w.u16(1);
            w.bytes(&[self.handler_name.len() as u8]);
            w.bytes(self.handler_name.as_bytes());
            w.zeros(31 - self.handler_name.len());
            w.u16(0x18);
            w.u16(0xffff);
            w.iso_box(b"hvcC", |w| w.bytes(self.configuration.record()));
        });
    }
}

struct Writer<'a> {
    output: &'a mut [u8],
    pos: usize,
}

impl Writer<'_> {
    fn bytes(&mut self, bytes: &[u8]) {
        let end = self.pos + bytes.len();
        self.output[self.pos..end].copy_from_slice(bytes);
        self.pos = end;
    }

    fn zeros(&mut self, count: usize) {
        let end = self.pos + count;
        self.output[self.pos..end].fill(0);
        self.pos = end;
    }

    fn u16(&mut self, value: u16) {
        self.bytes(&value.to_be_bytes());
    }

    fn u32(&mut self, value: u32) {
        self.bytes(&value.to_be_bytes());
    }

    fn matrix(&mut self) {
        for coefficient in MATRIX {
            self.u32(coefficient);
        }
    }

    fn iso_box(&mut self, kind: &[u8; 4], body: impl FnOnce(&mut Self)) {
        let start = self.pos;
        self.u32(0);
        self.bytes(kind);
        body(self);
        self.output[start..start + 4].copy_from_slice(&((self.pos - start) as u32).to_be_bytes());
    }

    fn full_box(&mut self, kind: &[u8; 4], version_and_flags: u32, body: impl FnOnce(&mut Self)) {
        self.iso_box(kind, |w| {
            w.u32(version_and_flags);
            body(w);
        });
    }
}
