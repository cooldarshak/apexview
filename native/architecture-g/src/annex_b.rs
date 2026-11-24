//! Clear-only prefix conversion experiment; no slicing, stitching or DRM handling.
const MAX_NALS: usize = 4096;
pub(crate) const MAX_BYTES: usize = 8 * 1024 * 1024;

fn walk(input: &[u8], prefix: usize, mut visit: impl FnMut(&[u8]) -> Option<()>) -> Option<()> {
    if !(1..=4).contains(&prefix) || input.is_empty() || input.len() > MAX_BYTES {
        return None;
    }
    let mut pos = 0usize;
    let mut count = 0usize;
    while pos < input.len() {
        let end = pos.checked_add(prefix)?;
        let mut length = 0usize;
        for &byte in input.get(pos..end)? {
            length = length.checked_mul(256)?.checked_add(usize::from(byte))?;
        }
        if length < 2 || length > input.len() - end {
            return None;
        }
        pos = end.checked_add(length)?;
        let nal = &input[end..pos];
        // Same basic header checks as HevcBitstream.readNalHeader; this is
        // not a full HEVC conformance or parameter-set validator.
        if nal[0] & 0x80 != 0 || nal[1] & 7 == 0 {
            return None;
        }
        count += 1;
        if count > MAX_NALS {
            return None;
        }
        visit(nal)?;
    }
    Some(())
}

fn validated_size(input: &[u8], prefix: usize) -> Option<usize> {
    let mut needed = 0usize;
    walk(input, prefix, |nal| {
        needed = needed.checked_add(4)?.checked_add(nal.len())?;
        Some(())
    })?;
    (needed <= MAX_BYTES).then_some(needed)
}

fn write_validated(input: &[u8], prefix: usize, output: &mut [u8]) -> Option<usize> {
    let mut pos = 0usize;
    walk(input, prefix, |nal| {
        let end = pos.checked_add(4)?.checked_add(nal.len())?;
        let dst = output.get_mut(pos..end)?;
        dst[..4].copy_from_slice(&[0, 0, 0, 1]);
        dst[4..].copy_from_slice(nal);
        pos = end;
        Some(())
    })?;
    Some(pos)
}

/// Validate the whole sample before writing; malformed or undersized output
/// leaves output unchanged. Both buffers are caller-owned and nonoverlapping.
pub fn convert_length_prefixed(input: &[u8], prefix: usize, output: &mut [u8]) -> Option<usize> {
    let needed = validated_size(input, prefix)?;
    if needed > output.len() { return None; }
    write_validated(input, prefix, output)
}

/// Fixed-size independent samples in one JNI call. Validate every sample
/// before any write; each sample still has its own NAL-count/range checks.
/// No descriptor allocation, no merging of sample semantics. Diagnostic only.
pub fn convert_fixed_batch(input: &[u8], sample_len: usize, count: usize, prefix: usize, output: &mut [u8]) -> Option<usize> {
    if !(1..=64).contains(&count) || sample_len == 0 ||
        sample_len.checked_mul(count)? != input.len() || input.len() > MAX_BYTES {
        return None;
    }
    let mut needed = 0usize;
    for sample in input.chunks_exact(sample_len) {
        needed = needed.checked_add(validated_size(sample,prefix)?)?;
    }
    if needed > output.len() || needed > MAX_BYTES { return None; }
    let mut pos = 0usize;
    for sample in input.chunks_exact(sample_len) {
        pos = pos.checked_add(write_validated(sample,prefix,output.get_mut(pos..)?)?)?;
    }
    Some(pos)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn prefix_widths_and_exact_bytes() {
        let expected = [0,0,0,1,2,1,7,0,0,0,1,64,1];
        for prefix in 1..=4 {
            let mut sample = Vec::new();
            sample.extend_from_slice(&3u32.to_be_bytes()[4-prefix..]);
            sample.extend_from_slice(&[2,1,7]);
            sample.extend_from_slice(&2u32.to_be_bytes()[4-prefix..]);
            sample.extend_from_slice(&[64,1]);
            let mut output = [0x55; 16];
            assert_eq!(convert_length_prefixed(&sample,prefix,&mut output),Some(13));
            assert_eq!(&output[..13], &expected);
            assert_eq!(&output[13..], &[0x55;3]);
        }
    }

    #[test]
    fn batch_preserves_independent_samples_and_is_atomic_on_invalid_input() {
        let sample = [2,2,1];
        let mut out = [0x55; 24];
        assert_eq!(convert_fixed_batch(&sample.repeat(4),3,4,1,&mut out),Some(24));
        assert_eq!(out,[0,0,0,1,2,1].repeat(4).as_slice());
        let mut out = [0x55; 24];
        assert_eq!(convert_fixed_batch(&[2,2,1,3,2,1],3,2,1,&mut out),None);
        assert_eq!(convert_fixed_batch(&sample.repeat(4),3,4,1,&mut out[..23]),None);
        assert_eq!(convert_fixed_batch(&sample,3,2,1,&mut out),None);
        assert_eq!(convert_fixed_batch(&sample,3,0,1,&mut out),None);
        assert_eq!(convert_fixed_batch(&sample,usize::MAX,64,1,&mut out),None);
        assert_eq!(out,[0x55;24]);
    }
    #[test]
    fn rejects_without_partial_write() {
        let mut out = [0x55;16];
        for bad in [
            &[3,2,1,7,3,2,1][..],
            &[2,0x82,1],
            &[2,2,0],
            &[0],
            &[],
        ] {
            assert_eq!(convert_length_prefixed(bad,1,&mut out),None);
            assert_eq!(out,[0x55;16]);
        }
        assert_eq!(convert_length_prefixed(&[0,0,0,7,2,1],4,&mut out),None);
        assert_eq!(convert_length_prefixed(&[2,2,1],1,&mut out[..5]),None);
        assert_eq!(convert_length_prefixed(&[2,2,1].repeat(4097),1,&mut out),None);
        assert_eq!(out,[0x55;16]);
    }
}
