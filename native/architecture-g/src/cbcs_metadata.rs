//! Read-only checks for the existing CBCS profile's inline auxiliary metadata.
//! The caller supplies video sample sizes and the effective IV mode already
//! resolved by the authorized track/sample-group validator. No media is read.
use crate::bmff::{Error, Reader, MAX_BYTES};

pub struct Auxiliary<'a> {
    pub sizes: &'a [u8],
    pub offsets: &'a [u8],
    /// Offset of the first inline record relative to that sample run's moof.
    pub first_record_offset: u64,
}

pub fn validate(
    senc: &[u8],
    sample_sizes: &[u32],
    per_sample_iv_size: u8,
    auxiliary: Option<Auxiliary<'_>>,
) -> Result<(), Error> {
    if !matches!(per_sample_iv_size, 0 | 8 | 16) {
        return Err(Error::Unsupported);
    }
    if senc.len() > MAX_BYTES || !(1..=4096).contains(&sample_sizes.len()) {
        return Err(Error::Limit);
    }
    let mut encryption = Reader::new(senc);
    if encryption.full()? != (0, 2) {
        return Err(Error::Unsupported); // Explicit subsamples; no PIFF override.
    }
    if encryption.u32()? as usize != sample_sizes.len() {
        return Err(Error::Metadata);
    }
    let mut sizes = match auxiliary {
        Some(info) => {
            validate_offset(info.offsets, info.first_record_offset)?;
            let mut sizes = Reader::new(info.sizes);
            auxiliary_info(&mut sizes, false)?;
            let default = sizes.u8()?;
            if sizes.u32()? as usize != sample_sizes.len() {
                return Err(Error::Metadata);
            }
            Some((default, sizes))
        }
        None => None,
    };
    for &sample_size in sample_sizes {
        encryption.take(usize::from(per_sample_iv_size))?;
        let subsamples = encryption.u16()?;
        if sample_size == 0 || subsamples == 0 {
            return Err(Error::Metadata);
        }
        let mut covered = 0u32;
        for _ in 0..subsamples {
            let clear = u32::from(encryption.u16()?);
            let protected = encryption.u32()?;
            covered = covered
                .checked_add(clear)
                .and_then(|n| n.checked_add(protected))
                .ok_or(Error::Extent)?;
            if covered > sample_size {
                return Err(Error::Extent);
            }
        }
        if covered != sample_size {
            return Err(Error::Extent);
        }
        if let Some((default, sizes)) = &mut sizes {
            let size = if *default == 0 { sizes.u8()? } else { *default };
            let record = u32::from(per_sample_iv_size) + 2 + 6 * u32::from(subsamples);
            if record != u32::from(size) {
                return Err(Error::Metadata);
            }
        }
    }
    if let Some((_, sizes)) = sizes {
        sizes.finish()?;
    }
    encryption.finish()
}

fn auxiliary_info(reader: &mut Reader<'_>, allow_version_one: bool) -> Result<u8, Error> {
    let (version, flags) = reader.full()?;
    if version > u8::from(allow_version_one) || flags & !1 != 0 {
        return Err(Error::Unsupported);
    }
    if flags == 1 && (reader.take(4)? != b"cbcs" || reader.u32()? != 0) {
        return Err(Error::Unsupported);
    }
    Ok(version)
}

fn validate_offset(offsets: &[u8], expected: u64) -> Result<(), Error> {
    let mut offsets = Reader::new(offsets);
    let version = auxiliary_info(&mut offsets, true)?;
    if offsets.u32()? != 1 {
        return Err(Error::Unsupported); // This profile has one inline auxiliary layout.
    }
    let offset = if version == 0 {
        u64::from(offsets.u32()?)
    } else {
        offsets.u64()?
    };
    offsets.finish()?;
    if offset != expected {
        return Err(Error::Metadata);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn records(iv_size: u8) -> Vec<u8> {
        let mut data = vec![0, 0, 0, 2, 0, 0, 0, 2];
        for pattern in [0x17, 0x29] {
            data.extend(vec![pattern; usize::from(iv_size)]);
            data.extend([0, 2, 0, 4, 0, 0, 0, 16, 0, 0, 0, 0, 0, 32]);
        }
        data
    }

    #[test]
    fn checks_inline_coverage_per_sample_iv_modes_and_auxiliary_sizes() {
        for iv_size in [0, 8, 16] {
            let senc = records(iv_size);
            let entry = iv_size + 14;
            let sizes = [
                0, 0, 0, 1, b'c', b'b', b'c', b's', 0, 0, 0, 0, 0, 0, 0, 0, 2, entry, entry,
            ];
            let offsets = [1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 80];
            let aux = |offset| Auxiliary {
                sizes: &sizes,
                offsets: &offsets,
                first_record_offset: offset,
            };
            assert_eq!(validate(&senc, &[52, 52], iv_size, Some(aux(80))), Ok(()));
            assert_eq!(
                validate(&senc, &[52, 52], iv_size, Some(aux(81))),
                Err(Error::Metadata)
            );
            assert_eq!(
                validate(&senc, &[52, 53], iv_size, None),
                Err(Error::Extent)
            );
            for end in 0..senc.len() {
                assert!(validate(&senc[..end], &[52, 52], iv_size, None).is_err());
            }
        }
    }

    #[test]
    fn rejects_overflow_and_unsupported_protected_layouts() {
        let mut data = records(0);
        data[12..16].copy_from_slice(&u32::MAX.to_be_bytes());
        assert_eq!(
            validate(&data, &[u32::MAX, 52], 0, None),
            Err(Error::Extent)
        );
        data[3] = 3;
        assert_eq!(validate(&data, &[52, 52], 0, None), Err(Error::Unsupported));
        assert_eq!(
            validate(&records(8), &[52, 52], 12, None),
            Err(Error::Unsupported)
        );
    }
}
