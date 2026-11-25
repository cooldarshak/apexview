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

/// Encryption identities are borrowed and compared without logging or
/// interpreting KIDs or IVs. The single-identity output can represent aliases
/// only when effective sample groups exactly equal the authorized tenc default.
#[derive(Clone, Copy, PartialEq, Eq)]
struct Protection<'a> {
    iv_size: u8,
    pattern: u8,
    protected: bool,
    kid: &'a [u8],
    constant_iv: &'a [u8],
}

impl<'a> Protection<'a> {
    fn read(fields: &mut Reader<'a>, pattern: u8) -> Result<Self, Error> {
        let protected = match fields.u8()? {
            0 => false,
            1 => true,
            _ => return Err(Error::Metadata),
        };
        let iv_size = fields.u8()?;
        if !matches!(iv_size, 0 | 8 | 16) || (!protected && iv_size != 0) {
            return Err(Error::Unsupported);
        }
        let kid = fields.take(16)?;
        let constant_iv = if protected && iv_size == 0 {
            let width = fields.u8()?;
            if !matches!(width, 8 | 16) {
                return Err(Error::Unsupported);
            }
            fields.take(usize::from(width))?
        } else {
            &[]
        };
        Ok(Self {
            iv_size,
            pattern,
            protected,
            kid,
            constant_iv,
        })
    }

    fn tenc(payload: &'a [u8]) -> Result<Self, Error> {
        let mut fields = Reader::new(payload);
        let (version, flags) = fields.full()?;
        if version > 1 || flags != 0 || fields.u8()? != 0 {
            return Err(Error::Unsupported);
        }
        let pattern = if version == 1 { fields.u8()? } else { 0 };
        let output = Self::read(&mut fields, pattern)?;
        fields.finish()?;
        if !output.protected {
            return Err(Error::Unsupported);
        }
        Ok(output)
    }

    fn seig(fields: &mut Reader<'a>) -> Result<Self, Error> {
        if fields.u8()? != 0 {
            return Err(Error::Unsupported); // Multi-key/reserved flags.
        }
        let pattern = fields.u8()?;
        Self::read(fields, pattern)
    }
}

#[derive(Default)]
struct Descriptions<'a> {
    version: u8,
    default: u32,
    entries: Vec<Protection<'a>>,
}

impl<'a> Descriptions<'a> {
    fn parse(payload: Option<&'a [u8]>) -> Result<Self, Error> {
        let Some(payload) = payload else {
            return Ok(Self::default());
        };
        let mut fields = Reader::new(payload);
        let (version, flags) = fields.full()?;
        if version > 2 || flags != 0 {
            return Err(Error::Unsupported);
        }
        if fields.take(4)? != b"seig" {
            return Ok(Self::default()); // roll, rap and other groups do not change protection.
        }
        let default_length = if version > 0 { fields.u32()? } else { 0 };
        let default = if version == 2 { fields.u32()? } else { 0 };
        let count = fields.u32()?;
        if count > 4096 || default > count {
            return Err(Error::Limit);
        }
        let mut entries = Vec::new();
        entries
            .try_reserve_exact(count as usize)
            .map_err(|_| Error::Limit)?;
        for _ in 0..count {
            let entry = if version == 0 {
                Protection::seig(&mut fields)?
            } else {
                let size = if default_length == 0 {
                    fields.u32()?
                } else {
                    default_length
                };
                if !(20..=256).contains(&size) {
                    return Err(Error::Metadata);
                }
                let mut entry = Reader::new(fields.take(size as usize)?);
                let value = Protection::seig(&mut entry)?;
                entry.finish()?;
                value
            };
            entries.push(entry);
        }
        fields.finish()?;
        Ok(Self {
            version,
            default,
            entries,
        })
    }
}

pub struct Groups<'a> {
    pub track_description: Option<&'a [u8]>,
    pub fragment_description: Option<&'a [u8]>,
    pub fragment_mapping: Option<&'a [u8]>,
}

/// Verify the ordinary single-key CBCS metadata admitted by the JVM. Inputs
/// are exact, independently identified box payloads, never media bytes. The
/// caller still owns track entitlement, PSSH checks, and other group boxes.
pub fn validate_with_groups(
    tenc: &[u8],
    groups: Groups<'_>,
    senc: &[u8],
    sample_sizes: &[u32],
    auxiliary: Option<Auxiliary<'_>>,
) -> Result<(), Error> {
    if !(1..=4096).contains(&sample_sizes.len()) {
        return Err(Error::Limit);
    }
    let protection = Protection::tenc(tenc)?;
    let track = Descriptions::parse(groups.track_description)?;
    let fragment = Descriptions::parse(groups.fragment_description)?;
    let require_alias = |index| {
        let found = match index {
            0 => return Ok(()),
            1..=0xffff => track.entries.get(index as usize - 1),
            0x10001..=0x1ffff => fragment.entries.get(index as usize - 0x10001),
            _ => return Err(Error::Unsupported),
        };
        if found != Some(&protection) {
            return Err(Error::Protected);
        }
        Ok(())
    };
    // An output init must keep default track protection when it does not carry
    // the original mapping. A real key, IV or pattern transition is ineligible.
    require_alias(track.default)?;
    let mut mapped = 0usize;
    if let Some(mapping) = groups.fragment_mapping {
        let mut fields = Reader::new(mapping);
        let (version, flags) = fields.full()?;
        if version > 1 || flags != 0 {
            return Err(Error::Unsupported);
        }
        if fields.take(4)? == b"seig" {
            if version == 1 {
                fields.u32()?; // grouping_type_parameter
            }
            let count = fields.u32()? as usize;
            if !(1..=sample_sizes.len()).contains(&count) {
                return Err(Error::Metadata);
            }
            for _ in 0..count {
                let count = fields.u32()? as usize;
                let index = fields.u32()?;
                if count == 0 || count > sample_sizes.len() - mapped {
                    return Err(Error::Metadata);
                }
                require_alias(index)?;
                mapped += count;
            }
            fields.finish()?;
        }
    }
    if mapped < sample_sizes.len() {
        let default = if fragment.version == 2 {
            if fragment.default == 0 {
                0
            } else {
                0x10000 + fragment.default
            }
        } else {
            track.default
        };
        require_alias(default)?;
    }
    validate(senc, sample_sizes, protection.iv_size, auxiliary)
}

#[cfg(test)]
mod group_tests {
    use super::*;

    fn protection_entry(kid: u8) -> Vec<u8> {
        let mut fields = vec![0, 0x19, 1, 8];
        fields.extend([kid; 16]);
        fields
    }

    #[test]
    fn equivalent_groups_preserve_track_identity_and_rotation_is_rejected() {
        let mut tenc = vec![1, 0, 0, 0];
        tenc.extend(protection_entry(0x31));
        let mut description = b"\x02\0\0\0seig\0\0\0\x14\0\0\0\x01\0\0\0\x01".to_vec();
        description.extend(protection_entry(0x31));
        let mapping = b"\0\0\0\0seig\0\0\0\x01\0\0\0\x01\0\x01\0\x01";
        let mut senc = vec![0, 0, 0, 2, 0, 0, 0, 2];
        for _ in 0..2 {
            senc.extend([0x49; 8]);
            senc.extend([0, 1, 0, 4, 0, 0, 0, 16]);
        }
        let check = |description: &[u8], map| {
            validate_with_groups(
                &tenc,
                Groups {
                    track_description: None,
                    fragment_description: Some(description),
                    fragment_mapping: map,
                },
                &senc,
                &[20, 20],
                None,
            )
        };
        assert_eq!(check(&description, Some(mapping)), Ok(()));
        assert_eq!(check(&description, None), Ok(()));
        *description.last_mut().unwrap() ^= 1;
        assert_eq!(check(&description, Some(mapping)), Err(Error::Protected));
        assert_eq!(check(&description, None), Err(Error::Protected));
    }
}
