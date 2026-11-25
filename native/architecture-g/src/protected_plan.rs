//! Clear-prefix and range-metadata differential kernels for the existing
//! authorized protected composer. No content keys, opaque media, PSSH or
//! license data cross these boundaries.
use crate::bmff::{Error, Reader};
use crate::clear_slice;
use crate::hevc_parameters::Parameters;

pub const MAX_PACKET: usize = 2 * 1024 * 1024;
const MAX_PREFIX: usize = 16 * 1024;
const MAX_RANGES: usize = 4096;

fn read_nal<'a>(r: &mut Reader<'a>) -> Result<&'a [u8], Error> {
    let n = r.u32()? as usize;
    if !(3..=MAX_PREFIX).contains(&n) {
        return Err(Error::Limit);
    }
    r.take(n)
}

/// GBS1, destination, opaque flag, filter override (0 absent, 1 false,
/// 2 true), then six length-prefixed parameter sets and a clear NAL prefix.
/// Independently parse HEVC syntax, never interpret data beyond the prefix.
/// Result: source splice offset followed by escaped clear output prefix.
pub fn slice_prefix(packet: &[u8]) -> Result<Vec<u8>, Error> {
    if packet.len() > MAX_PACKET {
        return Err(Error::Limit);
    }
    let mut r = Reader::new(packet);
    if r.take(4)? != b"GBS1" {
        return Err(Error::Unsupported);
    }
    let destination = r.u32()?;
    let opaque = match r.u32()? {
        0 => false,
        1 => true,
        _ => return Err(Error::Unsupported),
    };
    let filter = match r.u32()? {
        0 => None,
        1 => Some(false),
        2 => Some(true),
        _ => return Err(Error::Unsupported),
    };
    let source = Parameters::parse([read_nal(&mut r)?, read_nal(&mut r)?, read_nal(&mut r)?])?;
    let output = Parameters::parse([read_nal(&mut r)?, read_nal(&mut r)?, read_nal(&mut r)?])?;
    let clear = read_nal(&mut r)?;
    r.finish()?;
    let slice = source.read_slice(clear)?;
    let syntax = output.rewrite_syntax(&slice, destination)?;
    let filter = match filter {
        Some(value) => Some((slice.loop_filter_bit.ok_or(Error::Metadata)?, value)),
        None => None,
    };
    let (prefix, tail_offset) =
        clear_slice::rewrite_checked_prefix(clear, syntax, filter, opaque).ok_or(Error::Nal)?;
    let expected_clear_end = tail_offset.checked_add(if opaque { 0 } else { 1 }).ok_or(Error::Extent)?;
    if expected_clear_end != clear.len() {
        return Err(Error::Extent);
    }
    let mut out = Vec::new();
    out.try_reserve_exact(4 + prefix.len()).map_err(|_| Error::Limit)?;
    out.extend_from_slice(&(tail_offset as u32).to_be_bytes());
    out.extend_from_slice(&prefix);
    Ok(out)
}

/// GBM1, output NAL prefix width and NAL count, then per NAL its size,
/// contiguous clear/opaque range count, and (length, clear flag) descriptors.
/// Independently plan exact subsample boundaries from metadata only.
pub fn sample_map(packet: &[u8]) -> Result<Vec<u8>, Error> {
    if packet.len() > MAX_PACKET {
        return Err(Error::Limit);
    }
    let mut r = Reader::new(packet);
    if r.take(4)? != b"GBM1" {
        return Err(Error::Unsupported);
    }
    let width = r.u32()?;
    let nals = r.u32()?;
    if !(1..=4).contains(&width) || !(1..=4096).contains(&nals) {
        return Err(Error::Limit);
    }
    let mut entries: Vec<(u16, u32)> = Vec::new();
    let mut clear = 0u64;
    let mut total = 0u64;
    let mut encrypted = 0u64;
    fn emit(entries: &mut Vec<(u16, u32)>, clear: &mut u64, encrypted: u32) -> Result<(), Error> {
        while *clear > u64::from(u16::MAX) {
            push(entries, (u16::MAX, 0))?;
            *clear -= u64::from(u16::MAX);
        }
        push(entries, (*clear as u16, encrypted))?;
        *clear = 0;
        Ok(())
    }
    fn push(entries: &mut Vec<(u16, u32)>, entry: (u16, u32)) -> Result<(), Error> {
        if entries.len() >= MAX_RANGES {
            return Err(Error::Limit);
        }
        entries.try_reserve(1).map_err(|_| Error::Limit)?;
        entries.push(entry);
        Ok(())
    }
    for _ in 0..nals {
        let size = r.u32()?;
        let count = r.u32()?;
        if !(2..=8*1024*1024).contains(&size) || !(1..=4096).contains(&count) ||
            (width < 4 && size >= 1u32 << (8*width)) {
            return Err(Error::Limit);
        }
        total = total.checked_add(u64::from(width) + u64::from(size)).ok_or(Error::Limit)?;
        if total > 8*1024*1024 {
            return Err(Error::Limit);
        }
        clear += u64::from(width);
        let mut covered = 0u32;
        for i in 0..count {
            let length = r.u32()?;
            let is_clear = r.u32()?;
            if length == 0 || is_clear > 1 || (i == 0 && (is_clear != 1 || length < 2)) {
                return Err(Error::Metadata);
            }
            covered = covered.checked_add(length).ok_or(Error::Extent)?;
            if covered > size {
                return Err(Error::Extent);
            }
            if is_clear == 1 {
                clear += u64::from(length);
            } else {
                emit(&mut entries, &mut clear, length)?;
                encrypted += u64::from(length);
            }
        }
        if covered != size {
            return Err(Error::Extent);
        }
    }
    r.finish()?;
    if clear > 0 {
        emit(&mut entries, &mut clear, 0)?;
    }
    if entries.is_empty() || entries.iter().map(|&(a,b)| u64::from(a)+u64::from(b)).sum::<u64>() != total ||
        entries.iter().map(|&(_,b)| u64::from(b)).sum::<u64>() != encrypted {
        return Err(Error::Metadata);
    }
    let mut out = Vec::new();
    out.try_reserve_exact(8 + 6*entries.len()).map_err(|_| Error::Limit)?;
    out.extend_from_slice(&(total as u32).to_be_bytes());
    out.extend_from_slice(&(entries.len() as u32).to_be_bytes());
    for (a,b) in entries {
        out.extend_from_slice(&a.to_be_bytes());
        out.extend_from_slice(&b.to_be_bytes());
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn packet(width: u32, ranges: &[(u32,u32)]) -> Vec<u8> {
        let mut bytes=b"GBM1".to_vec();
        for v in [width,1,ranges.iter().map(|&(n,_)|n).sum(),ranges.len() as u32] {
            bytes.extend_from_slice(&v.to_be_bytes());
        }
        for &(n,c) in ranges {
            bytes.extend_from_slice(&n.to_be_bytes());
            bytes.extend_from_slice(&c.to_be_bytes());
        }
        bytes
    }

    #[test]
    fn metadata_map_preserves_boundaries_and_long_clear_ranges() {
        for width in 3..=4 {
            let p=packet(width,&[(65536,1),(16,0),(32,0),(7,1)]);
            let out=sample_map(&p).unwrap();
            let mut r=Reader::new(&out);
            assert_eq!(r.u32().unwrap(),width+65536+16+32+7);
            assert_eq!(r.u32().unwrap(),4);
            assert_eq!((r.u16().unwrap(),r.u32().unwrap()),(65535,0));
            assert_eq!((r.u16().unwrap(),r.u32().unwrap()),((width+1) as u16,16));
            assert_eq!((r.u16().unwrap(),r.u32().unwrap()),(0,32));
            assert_eq!((r.u16().unwrap(),r.u32().unwrap()),(7,0));
            r.finish().unwrap();
            for end in 0..p.len() {assert!(sample_map(&p[..end]).is_err());}
        }
        assert!(sample_map(&packet(1,&[(256,1)])).is_err());
        assert!(sample_map(&packet(4,&[(2,0)])).is_err());
        assert!(sample_map(&packet(4,&[(2,1),(0,0)])).is_err());
        assert!(sample_map(&packet(4,&[(2,2)])).is_err());
    }
}
