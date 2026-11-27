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


/// Exact archive positions: a 256-sample block has two signed 32-bit
/// origins and two unsigned 16-bit offsets per point. No quantization.
/// Fractional, nonfinite or wide blocks fall back to the reference arrays.
pub struct CompactCoordinates {
    bytes: Box<[u8]>,
    count: usize,
}
impl CompactCoordinates {
    const BLOCK: usize = 256;
    pub fn storage_len(count: usize) -> Option<usize> {
        if count == 0 || count > MAX_HISTORY { return None; }
        count.checked_mul(4)?.checked_add(count.div_ceil(Self::BLOCK).checked_mul(8)?)
    }
    fn exact_integer(value: f32) -> Option<i32> {
        if !value.is_finite() || value.abs() > 16_777_216.0 { return None; }
        let integer = value as i32;
        ((integer as f32).to_bits() == value.to_bits()).then_some(integer)
    }
    pub fn from_reader(count: usize, mut read: impl FnMut(usize, &mut [f32], &mut [f32]) -> Option<()>) -> Option<Self> {
        let len = Self::storage_len(count)?;
        let mut bytes = Vec::new();
        bytes.try_reserve_exact(len).ok()?;
        bytes.resize(len, 0);
        let mut xs = [0f32; Self::BLOCK];
        let mut ys = [0f32; Self::BLOCK];
        for first in (0..count).step_by(Self::BLOCK) {
            let n = (count - first).min(Self::BLOCK);
            read(first, &mut xs[..n], &mut ys[..n])?;
            let mut low = [i32::MAX; 2];
            let mut high = [i32::MIN; 2];
            for (&x, &y) in xs[..n].iter().zip(&ys[..n]) {
                let values = [Self::exact_integer(x)?, Self::exact_integer(y)?];
                for (axis, value) in values.into_iter().enumerate() {
                    low[axis] = low[axis].min(value);
                    high[axis] = high[axis].max(value);
                }
            }
            if high[0].checked_sub(low[0])? > i32::from(u16::MAX) ||
                high[1].checked_sub(low[1])? > i32::from(u16::MAX) { return None; }
            let start = (first / Self::BLOCK) * (8 + Self::BLOCK * 4);
            bytes[start..start + 4].copy_from_slice(&low[0].to_le_bytes());
            bytes[start + 4..start + 8].copy_from_slice(&low[1].to_le_bytes());
            for (i, (&x, &y)) in xs[..n].iter().zip(&ys[..n]).enumerate() {
                let dx = u16::try_from(Self::exact_integer(x)?.checked_sub(low[0])?).ok()?;
                let dy = u16::try_from(Self::exact_integer(y)?.checked_sub(low[1])?).ok()?;
                let at = start + 8 + i * 4;
                bytes[at..at + 2].copy_from_slice(&dx.to_le_bytes());
                bytes[at + 2..at + 4].copy_from_slice(&dy.to_le_bytes());
            }
        }
        Some(Self { bytes: bytes.into_boxed_slice(), count })
    }
    pub fn len(&self) -> usize { self.count }
    pub fn is_empty(&self) -> bool { self.count == 0 }
    pub fn bytes(&self) -> &[u8] { &self.bytes }
    pub fn at(&self, index: usize) -> Option<(f32, f32)> {
        if index >= self.count { return None; }
        let start = (index / Self::BLOCK) * (8 + Self::BLOCK * 4);
        let at = start + 8 + (index % Self::BLOCK) * 4;
        let bx = i32::from_le_bytes(self.bytes.get(start..start + 4)?.try_into().ok()?);
        let by = i32::from_le_bytes(self.bytes.get(start + 4..start + 8)?.try_into().ok()?);
        let dx = u16::from_le_bytes(self.bytes.get(at..at + 2)?.try_into().ok()?);
        let dy = u16::from_le_bytes(self.bytes.get(at + 2..at + 4)?.try_into().ok()?);
        Some((bx.checked_add(i32::from(dx))? as f32, by.checked_add(i32::from(dy))? as f32))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_coordinate_blocks_preserve_negative_and_large_origins() {
        let xs: Vec<f32> = (0..1027).map(|i| (-100_000 + i * 317) as f32).collect();
        let ys: Vec<f32> = (0..1027).map(|i| (700_000 - i * 19) as f32).collect();
        // This track has wide individual blocks; use a local span within each block.
        let xs: Vec<f32> = xs.into_iter().enumerate().map(|(i, x)| x - (i % 256) as f32 * 100.0).collect();
        let packet = CompactCoordinates::from_reader(xs.len(), |first, x, y| {
            x.copy_from_slice(&xs[first..first + x.len()]);
            y.copy_from_slice(&ys[first..first + y.len()]);
            Some(())
        }).unwrap();
        assert_eq!(packet.bytes().len(), 4 * xs.len() + 8 * xs.len().div_ceil(256));
        for i in (0..xs.len()).rev() { assert_eq!(packet.at(i), Some((xs[i], ys[i]))); }
        assert_eq!(packet.at(xs.len()), None);
    }

    #[test]
    fn coordinate_pack_rejects_unrepresentable_values_without_rounding() {
        for value in [0.5, f32::NAN, -0.0, 16_777_218.0, 65_536.0] {
            assert!(CompactCoordinates::from_reader(2, |_, x, y| {
                x.copy_from_slice(&[0.0, value]); y.fill(0.0); Some(())
            }).is_none());
        }
    }

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
