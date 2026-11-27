//! Lossless archive timestamp storage. The absolute UTC millisecond base is
//! retained once; each sample uses an unsigned 32-bit millisecond offset.
//! No precision loss, and out-of-range histories stay on the original path.
use crate::hot_path::MAX_HISTORY;

pub struct CompactTimes {
    bytes: Box<[u8]>,
    count: usize,
}
impl CompactTimes {
    pub fn storage_len(count: usize) -> Option<usize> {
        if count == 0 || count > MAX_HISTORY { return None; }
        count.checked_mul(4)?.checked_add(8)
    }
    pub fn from_reader(count: usize, mut read: impl FnMut(usize, &mut [i64]) -> Option<()>) -> Option<Self> {
        let len = Self::storage_len(count)?;
        let mut bytes = Vec::new();
        bytes.try_reserve_exact(len).ok()?;
        bytes.resize(len, 0);
        let mut block = [0i64; 1024];
        let mut base = 0i64;
        let mut previous = i64::MIN;
        for first in (0..count).step_by(block.len()) {
            let n = (count - first).min(block.len());
            read(first, &mut block[..n])?;
            if first == 0 {
                base = block[0];
                bytes[..8].copy_from_slice(&base.to_le_bytes());
            }
            for (i, &value) in block[..n].iter().enumerate() {
                if value < previous { return None; }
                let delta = u32::try_from(value.checked_sub(base)?).ok()?;
                let at = 8 + (first + i) * 4;
                bytes[at..at + 4].copy_from_slice(&delta.to_le_bytes());
                previous = value;
            }
        }
        Some(Self { bytes: bytes.into_boxed_slice(), count })
    }
    pub fn len(&self) -> usize { self.count }
    pub fn is_empty(&self) -> bool { self.count == 0 }
    pub fn bytes(&self) -> &[u8] { &self.bytes }
    pub fn at(&self, index: usize) -> Option<i64> {
        if index >= self.count { return None; }
        let base = i64::from_le_bytes(self.bytes[..8].try_into().ok()?);
        let start = 8 + index * 4;
        let delta = u32::from_le_bytes(self.bytes[start..start + 4].try_into().ok()?);
        base.checked_add(i64::from(delta))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn pack(values: &[i64]) -> Option<CompactTimes> {
        CompactTimes::from_reader(values.len(), |start, out| {
            out.copy_from_slice(values.get(start..start + out.len())?);
            Some(())
        })
    }
    #[test]
    fn exact_millisecond_roundtrip_and_boundary() {
        let base = 1_800_000_000_123i64;
        let values = [base, base, base + 1, base + 5001, base + i64::from(u32::MAX)];
        let compact = pack(&values).unwrap();
        assert_eq!(compact.bytes().len(), 8 + 4 * values.len());
        for (i, value) in values.iter().enumerate() { assert_eq!(compact.at(i), Some(*value)); }
        assert_eq!(compact.at(values.len()), None);
        assert_eq!(crate::hot_path::floor_index(compact.len(), base + 1, |i| compact.at(i)), Some(2));
    }
    #[test]
    fn rejects_wide_unsorted_and_empty_history_without_truncation() {
        assert!(pack(&[]).is_none());
        assert!(pack(&[0, i64::from(u32::MAX) + 1]).is_none());
        assert!(pack(&[i64::MIN, i64::MAX]).is_none());
        assert!(pack(&[10, 9]).is_none());
    }
    #[test]
    fn bounded_reader_preserves_every_block_and_backward_seek() {
        let values: Vec<i64> = (0..4101).map(|i| 1_800_000_000_000 + i * 137).collect();
        let mut largest = 0;
        let compact = CompactTimes::from_reader(values.len(), |start, out| {
            largest = largest.max(out.len());
            out.copy_from_slice(&values[start..start + out.len()]); Some(())
        }).unwrap();
        assert_eq!(largest, 1024);
        assert_eq!(compact.bytes().len(), 8 + values.len() * 4);
        for i in (0..values.len()).rev() { assert_eq!(compact.at(i), Some(values[i])); }
        assert_eq!(crate::hot_path::floor_index_hint(compact.len(), values[11], 4000, |i| compact.at(i)), Some(11));
    }
}
