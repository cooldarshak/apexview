//! Metadata-only CBCS fragment-header writer for the already authorized JVM
//! composition route. No media payload, content keys, decryption or DRM policy
//! enters this module. JVM remains responsible for entitlement, exact input
//! protection identities, slice planning and independent output inspection.
use crate::bmff::{Error, Reader};
use crate::cbcs_metadata::{validate, Auxiliary};

pub const MAX_PACKET: usize = 2 * 1024 * 1024;
const MAX_MDAT: u64 = 56 * 1024 * 1024;
const MAX_SAMPLE: u32 = 8 * 1024 * 1024;

struct Sample<'a> {
    duration: u32,
    size: u32,
    flags: u32,
    cto: u32,
    record: &'a [u8],
}

fn u32_out(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_be_bytes());
}
fn box_header(out: &mut Vec<u8>, size: u32, kind: &[u8; 4]) {
    u32_out(out, size);
    out.extend_from_slice(kind);
}
fn full(out: &mut Vec<u8>, size: u32, kind: &[u8; 4], vf: u32) {
    box_header(out, size, kind);
    u32_out(out, vf);
}

/// Packet: GBP1, track, sequence, u64 DTS, u32 IV width, u32 sample count,
/// then per sample: duration, size, flags, signed CTS, exact inline auxiliary
/// record (IV if present, u16 count, u16/u32 ranges). No ciphertext.
pub fn write(packet: &[u8]) -> Result<Vec<u8>, Error> {
    if packet.len() > MAX_PACKET {
        return Err(Error::Limit);
    }
    let mut r = Reader::new(packet);
    if r.take(4)? != b"GBP1" {
        return Err(Error::Unsupported);
    }
    let track = r.u32()?;
    let sequence = r.u32()?;
    let dts = r.u64()?;
    let iv = r.u32()?;
    let count = r.u32()?;
    if track == 0 || sequence == 0 || dts > i64::MAX as u64 ||
        !matches!(iv, 0 | 8 | 16) || !(1..=4096).contains(&count) {
        return Err(Error::Metadata);
    }
    let mut samples = Vec::new();
    let mut sizes = Vec::new();
    samples.try_reserve_exact(count as usize).map_err(|_| Error::Limit)?;
    sizes.try_reserve_exact(count as usize).map_err(|_| Error::Limit)?;
    let mut mdat = 0u64;
    let mut aux_total = 0u32;
    for _ in 0..count {
        let duration = r.u32()?;
        let size = r.u32()?;
        let flags = r.u32()?;
        let cto = r.u32()?;
        if duration == 0 || !(1..=MAX_SAMPLE).contains(&size) {
            return Err(Error::Timing);
        }
        let start = r.rest();
        r.take(iv as usize)?;
        let ranges = u32::from(r.u16()?);
        let record_len = iv + 2 + 6 * ranges;
        if ranges == 0 || record_len > 255 {
            return Err(Error::Limit);
        }
        let mut covered = 0u32;
        let mut encrypted = 0u32;
        for _ in 0..ranges {
            let clear = u32::from(r.u16()?);
            let protected = r.u32()?;
            covered = covered.checked_add(clear).and_then(|n| n.checked_add(protected))
                .ok_or(Error::Extent)?;
            encrypted = encrypted.checked_add(protected).ok_or(Error::Extent)?;
        }
        if covered != size || encrypted == 0 {
            return Err(Error::Metadata);
        }
        mdat = mdat.checked_add(u64::from(size)).ok_or(Error::Limit)?;
        aux_total = aux_total.checked_add(record_len).ok_or(Error::Limit)?;
        sizes.push(size);
        samples.push(Sample { duration, size, flags, cto, record: &start[..record_len as usize] });
    }
    r.finish()?;
    if mdat == 0 || mdat > MAX_MDAT {
        return Err(Error::Limit);
    }
    let tfdt_size = if dts <= u64::from(u32::MAX) { 16 } else { 20 };
    let trun_size = 20 + 16 * count;
    let senc_size = 16 + aux_total;
    let saiz_size = 17 + count;
    let traf_size = 8 + 20 + tfdt_size + trun_size + senc_size + saiz_size + 20;
    let moof_size = 8 + 16 + traf_size;
    if moof_size as usize > MAX_PACKET {
        return Err(Error::Limit);
    }
    let mut out = Vec::new();
    out.try_reserve_exact(moof_size as usize).map_err(|_| Error::Limit)?;
    box_header(&mut out, moof_size, b"moof");
    full(&mut out, 16, b"mfhd", 0);
    u32_out(&mut out, sequence);
    box_header(&mut out, traf_size, b"traf");
    full(&mut out, 20, b"tfhd", 0x020002);
    u32_out(&mut out, track);
    u32_out(&mut out, 1);
    if tfdt_size == 16 {
        full(&mut out, 16, b"tfdt", 0);
        u32_out(&mut out, dts as u32);
    } else {
        full(&mut out, 20, b"tfdt", 0x01000000);
        out.extend_from_slice(&dts.to_be_bytes());
    }
    full(&mut out, trun_size, b"trun", 0x01000f01);
    u32_out(&mut out, count);
    u32_out(&mut out, moof_size + 8);
    for s in &samples {
        for value in [s.duration, s.size, s.flags, s.cto] {
            u32_out(&mut out, value);
        }
    }
    let senc = out.len();
    full(&mut out, senc_size, b"senc", 2);
    u32_out(&mut out, count);
    let first_record = out.len();
    for s in &samples {
        out.extend_from_slice(s.record);
    }
    let saiz = out.len();
    full(&mut out, saiz_size, b"saiz", 0);
    out.push(0);
    u32_out(&mut out, count);
    for s in &samples {
        out.push(s.record.len() as u8);
    }
    let saio = out.len();
    full(&mut out, 20, b"saio", 0);
    u32_out(&mut out, 1);
    u32_out(&mut out, first_record as u32);
    // Use the independent read-only CBCS validator for the exact emitted
    // records, not simply the input descriptors. No protected payload is read.
    let valid = out.len() == moof_size as usize && validate(
        &out[senc + 8..saiz], &sizes, iv as u8,
        Some(Auxiliary {
            sizes: &out[saiz + 8..saio],
            offsets: &out[saio + 8..],
            first_record_offset: first_record as u64,
        }),
    ).is_ok();
    if !valid {
        out.fill(0);
        return Err(Error::Metadata);
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn packet(iv: u32, dts: u64) -> Vec<u8> {
        let mut p = b"GBP1".to_vec();
        u32_out(&mut p, 7);
        u32_out(&mut p, 9);
        p.extend_from_slice(&dts.to_be_bytes());
        u32_out(&mut p, iv);
        u32_out(&mut p, 2);
        for cto in [(-3i32) as u32, 5] {
            for v in [1000, 52, 0x02000000, cto] {
                u32_out(&mut p, v);
            }
            p.extend(vec![0x23; iv as usize]); // synthetic metadata only
            p.extend_from_slice(&[0, 2, 0, 4, 0, 0, 0, 16, 0, 0, 0, 0, 0, 32]);
        }
        p
    }

    #[test]
    fn metadata_only_header_checks_all_iv_modes_and_tfdt_versions() {
        for iv in [0, 8, 16] {
            for dts in [u64::from(u32::MAX), u64::from(u32::MAX) + 1] {
                let p = packet(iv, dts);
                let out = write(&p).unwrap();
                assert_eq!(&out[4..8], b"moof");
                assert_eq!(u32::from_be_bytes(out[..4].try_into().unwrap()) as usize, out.len());
                let tfdt = if dts <= u64::from(u32::MAX) { 16 } else { 20 };
                assert_eq!(out.len(), 8 + 16 + 8 + 20 + tfdt + 52 + 16 + 2*(iv as usize+14) + 19 + 20);
                for end in 0..p.len() {
                    assert!(write(&p[..end]).is_err());
                }
            }
        }
    }

    #[test]
    fn rejects_invalid_coverage_timing_and_unbounded_records() {
        let p = packet(8, 0);
        for (offset, value) in [(4, 0u32), (8, 0), (20, 12), (24, 4097), (28, 0), (32, 53)] {
            let mut bad = p.clone();
            bad[offset..offset+4].copy_from_slice(&value.to_be_bytes());
            assert!(write(&bad).is_err());
        }
        let mut bad = p.clone();
        bad[52..54].copy_from_slice(&u16::MAX.to_be_bytes());
        assert!(write(&bad).is_err());
        let mut trailing = p;
        trailing.push(0);
        assert!(write(&trailing).is_err());
    }
}
