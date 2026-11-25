//! Single-clear-HEVC-track CMAF input. Sample and parameter-set bytes borrow the
//! input. Container admission is distinct from HEVC picture compatibility.
pub use crate::bmff::Error;
use crate::bmff::{BoxView, Boxes, Reader, MAX_BYTES};
use crate::hevc_nal;

const MAX_SAMPLES: usize = 4096;

#[derive(Clone, Copy, Default)]
struct Defaults {
    duration: Option<u32>,
    size: Option<u32>,
    flags: Option<u32>,
}

#[derive(Clone, Copy)]
pub struct HevcConfiguration<'a> {
    record: &'a [u8],
    nal_length_size: usize,
    parameter_sets: [&'a [u8]; 3],
}

impl<'a> HevcConfiguration<'a> {
    pub fn record(&self) -> &'a [u8] {
        self.record
    }

    pub fn nal_length_size(&self) -> usize {
        self.nal_length_size
    }

    pub fn parameter_sets(&self) -> [&'a [u8]; 3] {
        self.parameter_sets
    }

    pub fn parse(record: &'a [u8]) -> Result<Self, Error> {
        if record.len() > MAX_BYTES {
            return Err(Error::Limit);
        }
        let mut reader = Reader::new(record);
        if reader.u8()? != 1 {
            return Err(Error::Metadata);
        }
        reader.take(20)?;
        let nal_length_size = usize::from(reader.u8()? & 3) + 1;
        let arrays = reader.u8()?;
        let mut parameter_sets = [&[][..]; 3];
        for _ in 0..arrays {
            let kind = reader.u8()? & 0x3f;
            let count = reader.u16()?;
            for _ in 0..count {
                let length = usize::from(reader.u16()?);
                let nal = reader.take(length)?;
                let header = hevc_nal::header(nal)?;
                if header.unit_type != kind || header.layer_id != 0 {
                    return Err(Error::Nal);
                }
                if (32..=34).contains(&kind) {
                    let slot = &mut parameter_sets[usize::from(kind - 32)];
                    if !slot.is_empty() || nal.len() < 3 {
                        return Err(Error::Metadata);
                    }
                    *slot = nal;
                }
            }
        }
        reader.finish()?;
        if parameter_sets.iter().any(|set| set.is_empty()) {
            return Err(Error::Metadata);
        }
        Ok(Self {
            record,
            nal_length_size,
            parameter_sets,
        })
    }
}

#[derive(Clone, Copy)]
pub struct Initialization<'a> {
    pub track_id: u32,
    pub timescale: u32,
    pub display_width: u16,
    pub display_height: u16,
    pub configuration: HevcConfiguration<'a>,
    defaults: Defaults,
}

impl<'a> Initialization<'a> {
    pub fn parse(bytes: &'a [u8]) -> Result<Self, Error> {
        let moov = Boxes::root(bytes)?.require(b"moov")?;
        let trak = moov.children().require(b"trak")?;
        let tkhd = trak.children().require(b"tkhd")?;
        let mut track = full_version_time(tkhd)?;
        let track_id = track.u32()?;
        let mdia = trak.children().require(b"mdia")?;
        let mdhd = mdia.children().require(b"mdhd")?;
        let mut media = full_version_time(mdhd)?;
        let timescale = media.u32()?;
        if track_id == 0 || timescale == 0 {
            return Err(Error::Metadata);
        }
        let mut handler = Reader::new(mdia.children().require(b"hdlr")?.payload);
        if handler.full()? != (0, 0) {
            return Err(Error::Unsupported);
        }
        handler.u32()?;
        if handler.take(4)? != b"vide" {
            return Err(Error::Unsupported);
        }
        let minf = mdia.children().require(b"minf")?;
        let stbl = minf.children().require(b"stbl")?;
        let stsd = stbl.children().require(b"stsd")?;
        let mut descriptions = Reader::new(stsd.payload);
        if descriptions.full()? != (0, 0) || descriptions.u32()? != 1 {
            return Err(Error::Unsupported);
        }
        let mut entries = Boxes::new(descriptions.rest(), stsd.payload_offset + 8);
        let entry = entries.next().ok_or(Error::Metadata)??;
        if entries.next().transpose()?.is_some() || !matches!(&entry.kind, b"hvc1" | b"hev1") {
            return Err(Error::Unsupported);
        }
        let mut fields = Reader::new(entry.payload);
        fields.take(24)?;
        let display_width = fields.u16()?;
        let display_height = fields.u16()?;
        fields.take(50)?;
        if display_width == 0 || display_height == 0 {
            return Err(Error::Metadata);
        }
        let hvcc = Boxes::new(fields.rest(), entry.payload_offset + 78).require(b"hvcC")?;
        let configuration = HevcConfiguration::parse(hvcc.payload)?;
        let defaults = match moov.children().optional(b"mvex")? {
            Some(mvex) => match mvex.children().optional(b"trex")? {
                Some(trex) => parse_trex(trex, track_id)?,
                None => Defaults::default(),
            },
            None => Defaults::default(),
        };
        Ok(Self {
            track_id,
            timescale,
            display_width,
            display_height,
            configuration,
            defaults,
        })
    }
}

fn full_version_time(view: BoxView<'_>) -> Result<Reader<'_>, Error> {
    let mut reader = Reader::new(view.payload);
    match reader.full()?.0 {
        0 => reader.take(8)?,
        1 => reader.take(16)?,
        _ => return Err(Error::Unsupported),
    };
    Ok(reader)
}

fn parse_trex(view: BoxView<'_>, track_id: u32) -> Result<Defaults, Error> {
    let mut reader = Reader::new(view.payload);
    if reader.full()? != (0, 0) || reader.u32()? != track_id || reader.u32()? != 1 {
        return Err(Error::Metadata);
    }
    let value = Defaults {
        duration: Some(reader.u32()?),
        size: Some(reader.u32()?),
        flags: Some(reader.u32()?),
    };
    reader.finish()?;
    Ok(value)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Sample<'a> {
    pub bytes: &'a [u8],
    pub duration: u32,
    pub flags: u32,
    pub composition_offset: i64,
    pub decode_time: i64,
    pub presentation_time: i64,
}

pub struct Fragment<'a> {
    pub sequence: u32,
    pub decode_time: i64,
    pub samples: Vec<Sample<'a>>,
}

impl<'a> Fragment<'a> {
    pub fn parse(bytes: &'a [u8], initialization: &Initialization<'_>) -> Result<Self, Error> {
        let mut moof = None;
        let mut mdat = None;
        for view in Boxes::root(bytes)? {
            let view = view?;
            match &view.kind {
                b"moof" if moof.is_none() && mdat.is_none() => moof = Some(view),
                b"mdat" if moof.is_some() && mdat.is_none() => mdat = Some(view),
                b"styp" | b"sidx" | b"emsg" | b"prft" | b"free" => (),
                _ => return Err(Error::Unsupported),
            }
        }
        let moof = moof.ok_or(Error::Metadata)?;
        let mdat = mdat.ok_or(Error::Metadata)?;
        let traf = moof.children().require(b"traf")?;
        let mfhd = moof.children().require(b"mfhd")?;
        let mut movie = Reader::new(mfhd.payload);
        if movie.full()? != (0, 0) {
            return Err(Error::Unsupported);
        }
        let sequence = movie.u32()?;
        movie.finish()?;
        let children = traf.children();
        let defaults = parse_tfhd(children.clone().require(b"tfhd")?, initialization)?;
        let decode_time = parse_tfdt(children.clone().require(b"tfdt")?)?;
        let trun = children.require(b"trun")?;
        let samples = parse_trun(trun, moof.offset, mdat, decode_time, defaults)?;
        Ok(Self {
            sequence,
            decode_time,
            samples,
        })
    }
}

fn parse_tfhd(view: BoxView<'_>, init: &Initialization<'_>) -> Result<Defaults, Error> {
    const FLAGS: u32 = 0x020000 | 0x000002 | 0x000008 | 0x000010 | 0x000020;
    let mut reader = Reader::new(view.payload);
    let (version, flags) = reader.full()?;
    if version != 0 || flags & !FLAGS != 0 || flags & 0x020000 == 0 {
        return Err(Error::Unsupported);
    }
    if reader.u32()? != init.track_id {
        return Err(Error::Metadata);
    }
    if flags & 2 != 0 && reader.u32()? != 1 {
        return Err(Error::Unsupported);
    }
    let mut defaults = init.defaults;
    if flags & 8 != 0 {
        defaults.duration = Some(reader.u32()?);
    }
    if flags & 0x10 != 0 {
        defaults.size = Some(reader.u32()?);
    }
    if flags & 0x20 != 0 {
        defaults.flags = Some(reader.u32()?);
    }
    reader.finish()?;
    Ok(defaults)
}

fn parse_tfdt(view: BoxView<'_>) -> Result<i64, Error> {
    let mut reader = Reader::new(view.payload);
    let time = match reader.full()? {
        (0, 0) => i64::from(reader.u32()?),
        (1, 0) => i64::try_from(reader.u64()?).map_err(|_| Error::Timing)?,
        _ => return Err(Error::Unsupported),
    };
    reader.finish()?;
    Ok(time)
}

fn parse_trun<'a>(
    view: BoxView<'_>,
    moof_offset: usize,
    mdat: BoxView<'a>,
    mut decode_time: i64,
    defaults: Defaults,
) -> Result<Vec<Sample<'a>>, Error> {
    const DATA_OFFSET: u32 = 1;
    const FIRST_FLAGS: u32 = 4;
    const DURATION: u32 = 0x100;
    const SIZE: u32 = 0x200;
    const FLAGS: u32 = 0x400;
    const CTO: u32 = 0x800;
    const SAMPLE_FIELDS: u32 = DURATION | SIZE | FLAGS | CTO;
    let mut run = Reader::new(view.payload);
    let (version, flags) = run.full()?;
    if version > 1
        || flags & DATA_OFFSET == 0
        || flags & !(DATA_OFFSET | FIRST_FLAGS | SAMPLE_FIELDS) != 0
        || flags & FIRST_FLAGS != 0 && flags & FLAGS != 0
    {
        return Err(Error::Unsupported);
    }
    let count = run.u32()? as usize;
    if !(1..=MAX_SAMPLES).contains(&count) {
        return Err(Error::Limit);
    }
    let offset = moof_offset
        .checked_add_signed(run.i32()? as isize)
        .ok_or(Error::Extent)?;
    let start = offset
        .checked_sub(mdat.payload_offset)
        .ok_or(Error::Extent)?;
    // The clear profile owns the entire payload. A gap before the first sample
    // is just as ambiguous as leftover bytes after the final sample.
    if start != 0 {
        return Err(Error::Extent);
    }
    let mut payload = Reader::new(mdat.payload);
    let first_flags = if flags & FIRST_FLAGS != 0 {
        Some(run.u32()?)
    } else {
        None
    };
    let bytes_per_sample = 4 * (flags & SAMPLE_FIELDS).count_ones() as usize;
    if run.rest().len() != count * bytes_per_sample {
        return Err(Error::Extent);
    }
    let mut samples = Vec::new();
    samples.try_reserve_exact(count).map_err(|_| Error::Limit)?;
    for index in 0..count {
        let duration = sample_field(&mut run, flags, DURATION, defaults.duration)?;
        let size = sample_field(&mut run, flags, SIZE, defaults.size)?;
        let flag_default = if index == 0 {
            first_flags.or(defaults.flags)
        } else {
            defaults.flags
        };
        let sample_flags = sample_field(&mut run, flags, FLAGS, flag_default)?;
        let composition_offset = match (flags & CTO, version) {
            (0, _) => 0,
            (_, 0) => i64::from(run.u32()?),
            _ => i64::from(run.i32()?),
        };
        if size == 0 || duration == 0 {
            return Err(Error::Metadata);
        }
        let bytes = payload.take(size as usize)?;
        let presentation_time = decode_time
            .checked_add(composition_offset)
            .ok_or(Error::Timing)?;
        samples.push(Sample {
            bytes,
            duration,
            flags: sample_flags,
            composition_offset,
            decode_time,
            presentation_time,
        });
        decode_time = decode_time
            .checked_add(i64::from(duration))
            .ok_or(Error::Timing)?;
    }
    run.finish()?;
    payload.finish()?;
    Ok(samples)
}

fn sample_field(
    run: &mut Reader<'_>,
    flags: u32,
    mask: u32,
    default: Option<u32>,
) -> Result<u32, Error> {
    if flags & mask != 0 {
        run.u32()
    } else {
        default.ok_or(Error::Metadata)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{clear_cmaf, cmaf_init};

    fn hvcc() -> Vec<u8> {
        let mut bytes = vec![1];
        bytes.extend_from_slice(&[0; 20]);
        bytes.extend_from_slice(&[3, 3]);
        for kind in 32..=34 {
            bytes.extend_from_slice(&[0x80 | kind, 0, 1, 0, 3, kind << 1, 1, 0x80]);
        }
        bytes
    }

    fn initialization() -> Vec<u8> {
        let record = hvcc();
        let request = cmaf_init::Request {
            track_id: 7,
            timescale: 90_000,
            width: 1920,
            height: 1080,
            handler_name: "ApexView HEVC",
            configuration: HevcConfiguration::parse(&record).unwrap(),
        };
        let size = request.size().unwrap();
        let mut bytes = vec![0xaa; size + 1];
        assert_eq!(request.write(&mut bytes[..size - 1]), Err(Error::Limit));
        assert!(bytes.iter().all(|&byte| byte == 0xaa));
        assert_eq!(request.write(&mut bytes), Ok(size));
        assert_eq!(bytes.pop(), Some(0xaa));
        bytes
    }

    fn fragment(decode_time: u64) -> Vec<u8> {
        let samples = [
            clear_cmaf::Sample {
                data: b"abc",
                duration: 3600,
                cto: -120,
                flags: 0x0200_0000,
            },
            clear_cmaf::Sample {
                data: b"defghi",
                duration: 7200,
                cto: 240,
                flags: 0x0101_0000,
            },
        ];
        let mut bytes = vec![0; clear_cmaf::size(&samples).unwrap()];
        clear_cmaf::write(7, 42, decode_time, &samples, &mut bytes).unwrap();
        bytes
    }

    fn box_bytes(kind: &[u8; 4], payload: &[u8]) -> Vec<u8> {
        [&((8 + payload.len()) as u32).to_be_bytes(), kind, payload].concat()
    }

    fn fields(words: &[u32]) -> Vec<u8> {
        words.iter().flat_map(|v| v.to_be_bytes()).collect()
    }

    #[test]
    fn clear_metadata_is_borrowed_and_signed_timestamps_are_preserved() {
        let bytes = initialization();
        let init = Initialization::parse(&bytes).unwrap();
        assert_eq!(
            (
                init.track_id,
                init.timescale,
                init.display_width,
                init.display_height
            ),
            (7, 90_000, 1920, 1080)
        );
        assert_eq!(init.configuration.nal_length_size(), 4);
        assert_eq!(
            init.configuration.parameter_sets(),
            [&[64, 1, 128][..], &[66, 1, 128], &[68, 1, 128]]
        );
        let data = fragment(90_000);
        let parsed = Fragment::parse(&data, &init).unwrap();
        assert_eq!((parsed.sequence, parsed.decode_time), (42, 90_000));
        assert_eq!(
            parsed.samples,
            [
                Sample {
                    bytes: b"abc",
                    duration: 3600,
                    flags: 0x0200_0000,
                    composition_offset: -120,
                    decode_time: 90_000,
                    presentation_time: 89_880
                },
                Sample {
                    bytes: b"defghi",
                    duration: 7200,
                    flags: 0x0101_0000,
                    composition_offset: 240,
                    decode_time: 93_600,
                    presentation_time: 93_840
                },
            ]
        );
        assert_eq!(
            parsed.samples[0].bytes.as_ptr(),
            data[data.len() - 9..].as_ptr()
        );
    }

    #[test]
    fn tfhd_and_trex_defaults_and_version_zero_composition_offsets() {
        let bytes = initialization();
        let mut init = Initialization::parse(&bytes).unwrap();
        init.defaults = Defaults {
            duration: Some(1800),
            size: Some(2),
            flags: Some(5),
        };
        let mfhd = box_bytes(b"mfhd", &fields(&[0, 7]));
        let tfhd = box_bytes(b"tfhd", &fields(&[0x020008, 7, 3600]));
        let tfdt = box_bytes(b"tfdt", &fields(&[0, 0x8000_0000]));
        // Final trun data offset is relative to moof and includes the mdat header.
        let offset = (8 + mfhd.len() + 8 + tfhd.len() + tfdt.len() + 32 + 8) as u32;
        let trun = box_bytes(b"trun", &fields(&[0x805, 2, offset, 1, 0x8000_000a, 12]));
        let traf = box_bytes(b"traf", &[tfhd, tfdt, trun].concat());
        let moof = box_bytes(b"moof", &[mfhd, traf].concat());
        let data = [
            box_bytes(b"free", b"skip"),
            moof,
            box_bytes(b"mdat", b"abcd"),
        ]
        .concat();
        let parsed = Fragment::parse(&data, &init).unwrap();
        assert_eq!(
            parsed.samples[0],
            Sample {
                bytes: b"ab",
                duration: 3600,
                flags: 1,
                composition_offset: 0x8000_000a,
                decode_time: 0x8000_0000,
                presentation_time: 0x1_0000_000a,
            }
        );
        assert_eq!(parsed.samples[1].flags, 5);
        assert_eq!(parsed.samples[1].decode_time, 0x8000_0000 + 3600);
    }

    #[test]
    fn truncated_oversized_and_inconsistent_cmaf_are_rejected() {
        let bytes = initialization();
        for end in 0..bytes.len() {
            assert!(
                Initialization::parse(&bytes[..end]).is_err(),
                "partial init at {end}"
            );
        }
        let init = Initialization::parse(&bytes).unwrap();
        let data = fragment(0);
        for end in 0..data.len() {
            assert!(
                Fragment::parse(&data[..end], &init).is_err(),
                "partial media at {end}"
            );
        }
        assert!(matches!(
            Fragment::parse(&fragment(i64::MAX as u64), &init),
            Err(Error::Timing)
        ));
        assert!(matches!(
            Fragment::parse(&vec![0; MAX_BYTES + 1], &init),
            Err(Error::Limit)
        ));
        for (kind, offset, value) in [
            (b"trun", 8, u32::MAX),
            (b"trun", 12, 0),
            (b"trun", 16, u32::MAX),
            (b"tfhd", 4, 8),
            (b"tfhd", 0, 1),
            (b"tfdt", 0, 0x0200_0000),
        ] {
            let mut changed = data.clone();
            let pos = changed
                .windows(4)
                .position(|window| window == kind)
                .unwrap()
                + 4
                + offset;
            changed[pos..pos + 4].copy_from_slice(&value.to_be_bytes());
            assert!(
                Fragment::parse(&changed, &init).is_err(),
                "malformed {kind:?} at {offset}"
            );
        }
    }

    #[test]
    fn clear_parser_rejects_encryption_and_group_signalling_without_reading_mdat() {
        let init_bytes = initialization();
        let init = Initialization::parse(&init_bytes).unwrap();
        let data = fragment(0);
        let pssh = box_bytes(b"pssh", &[0; 24]);
        assert!(matches!(
            Initialization::parse(&[pssh, init_bytes.clone()].concat()),
            Err(Error::Protected)
        ));
        for kind in [b"senc", b"saiz", b"saio", b"sbgp", b"sgpd", b"uuid"] {
            let moof_len = u32::from_be_bytes(data[..4].try_into().unwrap());
            let moof_end = moof_len as usize;
            let mut changed = data[..moof_end].to_vec();
            let box_type = changed
                .windows(4)
                .position(|window| window == b"traf")
                .unwrap();
            let traf_size = &mut changed[box_type - 4..box_type];
            let size = u32::from_be_bytes(traf_size.try_into().unwrap());
            traf_size.copy_from_slice(&(size + 8).to_be_bytes());
            changed[..4].copy_from_slice(&(moof_len + 8).to_be_bytes());
            changed.extend(box_bytes(kind, &[]));
            changed.extend(&data[moof_end..]);
            assert!(
                matches!(Fragment::parse(&changed, &init), Err(Error::Protected)),
                "{kind:?}"
            );
        }
        let mut protected_entry = init_bytes.clone();
        let hvc1 = protected_entry
            .windows(4)
            .rposition(|x| x == b"hvc1")
            .unwrap();
        protected_entry[hvc1..hvc1 + 4].copy_from_slice(b"encv");
        assert!(matches!(
            Initialization::parse(&protected_entry),
            Err(Error::Protected)
        ));
    }

    #[test]
    fn deterministic_mutations_never_escape_the_borrowed_sample_buffer() {
        let init_bytes = initialization();
        let init = Initialization::parse(&init_bytes).unwrap();
        let data = fragment(0);
        let mut state: u32 = 0x32c4_9745;
        for _ in 0..8192 {
            let mut changed = data.clone();
            for _ in 0..3 {
                state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                let index = state as usize % changed.len();
                changed[index] ^= (state >> 24) as u8;
            }
            if let Ok(value) = Fragment::parse(&changed, &init) {
                for sample in value.samples {
                    let offset = sample.bytes.as_ptr() as usize - changed.as_ptr() as usize;
                    assert!(offset + sample.bytes.len() <= changed.len());
                }
            }
        }
    }
}
