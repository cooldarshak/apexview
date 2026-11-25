//! Clear-prefix and range-metadata differential kernels for the existing
//! authorized protected composer. No content keys, opaque media, PSSH or
//! license data cross these boundaries.
use crate::bmff::{Error, Reader};
use crate::clear_slice;
use crate::hevc_parameters::Parameters;
use std::sync::{Arc, Mutex, OnceLock};

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

/// GBO1: clear VPS/SPS/PPS template plus checked output geometry and rate.
/// Share the native output writer with the clear atlas. No sample data,
/// protected init container, PSSH, key, IV or license enters this command.
pub fn output_parameters(packet: &[u8]) -> Result<Vec<u8>, Error> {
    if packet.len() > MAX_PACKET { return Err(Error::Limit); }
    let mut r = Reader::new(packet);
    if r.take(4)? != b"GBO1" { return Err(Error::Unsupported); }
    let ids = [r.u32()?, r.u32()?, r.u32()?];
    let width = r.u32()?;
    let height = r.u32()?;
    let frame_rate = (r.u32()?, r.u32()?);
    let flags = r.u32()?;
    let columns = r.u32()? as usize;
    let rows = r.u32()? as usize;
    if flags & !3 != 0 || !(1..=20).contains(&columns) || !(1..=22).contains(&rows) {
        return Err(Error::Limit);
    }
    let mut col_widths = [0;20];
    let mut row_heights = [0;22];
    for v in &mut col_widths[..columns] { *v = r.u32()?; }
    for v in &mut row_heights[..rows] { *v = r.u32()?; }
    let sets = [read_nal(&mut r)?, read_nal(&mut r)?, read_nal(&mut r)?];
    r.finish()?;
    let output = crate::hevc_output::Request {
        ids, width, height, columns: &col_widths[..columns],
        rows: &row_heights[..rows], frame_rate,
        loop_across_tiles: flags & 1 != 0, uncropped: flags & 2 != 0,
    }.generate(sets)?;
    let mut result = Vec::new();
    let size = 12 + output.nals.iter().map(Vec::len).sum::<usize>();
    if size > MAX_PACKET { return Err(Error::Limit); }
    result.try_reserve_exact(size).map_err(|_| Error::Limit)?;
    for nal in output.nals {
        result.extend_from_slice(&(nal.len() as u32).to_be_bytes());
        result.extend_from_slice(&nal);
    }
    Ok(result)
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
    rewrite_one(&source, &output, destination, opaque, filter, clear)
}

fn rewrite_one(
    source: &Parameters, output: &Parameters, destination: u32, opaque: bool,
    filter: Option<bool>, clear: &[u8],
) -> Result<Vec<u8>, Error> {
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

// Exact raw clear triplets, not a hash or representation label, define
// reusable analysis. One process-wide immutable entry, bounded by the GBS2
// packet limits; no media bytes, protection identity or license is cached.
struct Configuration {
    sets: Vec<[Vec<u8>; 3]>,
    parsed: Vec<Parameters>,
}
impl Configuration {
    fn matches(&self, sets: &[[&[u8]; 3]]) -> bool {
        self.sets.len() == sets.len() && self.sets.iter().zip(sets).all(|(old, new)|
            old.iter().zip(new).all(|(a,b)| a.as_slice() == *b))
    }

    fn new(sets: &[[&[u8]; 3]]) -> Result<Self, Error> {
        let mut raw = Vec::new();
        let mut parsed = Vec::new();
        raw.try_reserve_exact(sets.len()).map_err(|_| Error::Limit)?;
        parsed.try_reserve_exact(sets.len()).map_err(|_| Error::Limit)?;
        for set in sets {
            parsed.push(Parameters::parse(*set)?);
            let mut owned = [Vec::new(), Vec::new(), Vec::new()];
            for (dest, src) in owned.iter_mut().zip(set) {
                dest.try_reserve_exact(src.len()).map_err(|_| Error::Limit)?;
                dest.extend_from_slice(src);
            }
            raw.push(owned);
        }
        Ok(Self { sets: raw, parsed })
    }
}
#[derive(Default)]
struct ConfigurationCache {
    active: Option<Arc<Configuration>>,
    hits: u64,
    misses: u64,
}
impl ConfigurationCache {
    fn get(&mut self, sets: &[[&[u8]; 3]]) -> Result<Arc<Configuration>, Error> {
        if let Some(active) = self.active.as_ref().filter(|a| a.matches(sets)) {
            self.hits = self.hits.saturating_add(1);
            return Ok(Arc::clone(active));
        }
        // A malformed new set must not silently reuse an older parse.
        let parsed = Arc::new(Configuration::new(sets)?);
        self.active = Some(Arc::clone(&parsed));
        self.misses = self.misses.saturating_add(1);
        Ok(parsed)
    }
}
static CONFIGURATION_CACHE: OnceLock<Mutex<ConfigurationCache>> = OnceLock::new();
fn configuration_cache() -> &'static Mutex<ConfigurationCache> {
    CONFIGURATION_CACHE.get_or_init(|| Mutex::new(ConfigurationCache::default()))
}
pub fn clear_configuration_cache() {
    if let Ok(mut cache) = configuration_cache().lock() { cache.active = None; }
}
/// Counts successful exact-key cache hits and newly parsed configurations;
/// used by independently encoded host fixtures. No parameter bytes exposed.
pub fn configuration_cache_counts() -> (u64, u64) {
    configuration_cache().lock().map_or((0,0), |c| (c.hits,c.misses))
}

/// GBS2: bounded whole-picture clear-header batch. Each feed's parameter
/// triplet and the output triplet are parsed once, then borrowed for every
/// checked placement. No opaque media is accepted. Output consists of the
/// count followed by length-prefixed GBS1 results, in raster placement order.
pub fn slice_batch(packet: &[u8]) -> Result<Vec<u8>, Error> {
    if packet.len() > MAX_PACKET { return Err(Error::Limit); }
    let mut r = Reader::new(packet);
    if r.take(4)? != b"GBS2" { return Err(Error::Unsupported); }
    let feeds = r.u32()? as usize;
    let count = r.u32()? as usize;
    if !(1..=8).contains(&feeds) || !(1..=440).contains(&count) {
        return Err(Error::Limit);
    }
    let mut sets = Vec::new();
    sets.try_reserve_exact(feeds+1).map_err(|_| Error::Limit)?;
    for _ in 0..=feeds {
        sets.push([read_nal(&mut r)?, read_nal(&mut r)?, read_nal(&mut r)?]);
    }
    let configuration = configuration_cache().lock().map_err(|_| Error::Metadata)?.get(&sets)?;
    let sources = &configuration.parsed[..feeds];
    let output = &configuration.parsed[feeds];
    let mut out = Vec::new();
    out.extend_from_slice(&(count as u32).to_be_bytes());
    let mut last = None;
    for _ in 0..count {
        let feed = r.u32()? as usize;
        let destination = r.u32()?;
        if last.is_some_and(|address| destination <= address) { return Err(Error::Metadata); }
        last = Some(destination);
        let opaque = match r.u32()? { 0 => false, 1 => true, _ => return Err(Error::Unsupported) };
        let filter = match r.u32()? {
            0 => None, 1 => Some(false), 2 => Some(true), _ => return Err(Error::Unsupported),
        };
        let clear = read_nal(&mut r)?;
        let mut rewritten = rewrite_one(sources.get(feed).ok_or(Error::Metadata)?,
            output, destination, opaque, filter, clear)?;
        let required = 4 + rewritten.len();
        if required > MAX_PACKET - out.len() {
            rewritten.fill(0);
            return Err(Error::Limit);
        }
        out.try_reserve_exact(required).map_err(|_| Error::Limit)?;
        out.extend_from_slice(&(rewritten.len() as u32).to_be_bytes());
        out.extend_from_slice(&rewritten);
        rewritten.fill(0);
    }
    r.finish()?;
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
    fn batch_rejects_bad_count_and_missing_parameter_sets() {
        for (feeds,count) in [(0u32,1u32),(9,1),(1,0),(1,441),(1,1)] {
            let mut packet=b"GBS2".to_vec();
            packet.extend_from_slice(&feeds.to_be_bytes());
            packet.extend_from_slice(&count.to_be_bytes());
            assert!(slice_batch(&packet).is_err());
            for end in 0..packet.len() {
                assert!(slice_batch(&packet[..end]).is_err());
            }
        }
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
