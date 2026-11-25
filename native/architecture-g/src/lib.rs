//! G.0 feasibility only. No production playback or protected payload processing.
#![deny(unsafe_code)]

mod annex_b;
mod atlas_job;
mod bmff;
pub mod clear_atlas;
pub mod cbcs_metadata;
pub mod cmaf_input;
pub mod cmaf_init;
pub mod hevc_nal;
pub mod hevc_output;
pub mod hevc_parameters;
pub mod hevc_poc;
pub mod hevc_prefix;
pub mod hevc_slice;
mod hevc_references;
pub mod feasibility;
pub mod clear_cmaf;
pub mod clear_slice;
pub mod engine;
pub use annex_b::{convert_length_prefixed, convert_fixed_batch};
use std::sync::atomic::{AtomicUsize, Ordering};
static NEXT_ARENA_ID: AtomicUsize = AtomicUsize::new(1);

#[cfg(target_os = "android")]
#[allow(unsafe_code)]
mod jni_bridge;

/// Mirrors the current JVM clear sizing kernel. Four-byte prefixes have a
/// sizing-only fast path; this function is NOT a NAL validator.
pub fn annex_b_size(sample: &[u8], prefix: usize) -> Option<usize> {
    if !(1..=4).contains(&prefix) || sample.len() > i32::MAX as usize {
        return None;
    }
    if prefix == 4 {
        return Some(sample.len());
    }
    let mut pos = 0usize;
    let mut count = 0usize;
    while pos < sample.len() {
        let end = pos.checked_add(prefix)?;
        let mut len = 0usize;
        for &b in sample.get(pos..end)? {
            len = (len << 8) | usize::from(b);
        }
        if len < 2 || len > sample.len() - end {
            return None;
        }
        pos = end + len;
        count += 1;
        if count > 4096 {
            return None;
        }
    }
    let size = sample.len().checked_add(count.checked_mul(4 - prefix)?)?;
    (size <= i32::MAX as usize).then_some(size)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Lease {
    arena_id: usize,
    slot: usize,
    serial: u64,
    generation: u64,
}
struct Slot {
    bytes: Box<[u8]>,
    len: usize,
    owner: Option<Lease>,
}

/// Single-owner experiment. Rust borrows guard slices; no raw JNI pointers,
/// shared ownership or production concurrency in this prototype.
pub struct Arena {
    id: usize,
    slots: Vec<Slot>,
    generation: u64,
    serial: u64,
}
impl Arena {
    pub fn new(slots: usize, capacity: usize) -> Option<Self> {
        if !(1..=8).contains(&slots) || capacity == 0
            || slots.checked_mul(capacity)? > 16 * 1024 * 1024 {
            return None;
        }
        let mut pool = Vec::new();
        pool.try_reserve_exact(slots).ok()?;
        for _ in 0..slots {
            let mut bytes = Vec::new();
            bytes.try_reserve_exact(capacity).ok()?;
            bytes.resize(capacity, 0);
            pool.push(Slot { bytes: bytes.into_boxed_slice(), len: 0, owner: None });
        }
        let id = NEXT_ARENA_ID.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| v.checked_add(1)).ok()?;
        Some(Self { id, slots: pool, generation: 0, serial: 0 })
    }
    pub fn publish(&mut self, data: &[u8]) -> Option<Lease> {
        let index = self.slots.iter().position(|s| s.owner.is_none() && data.len() <= s.bytes.len())?;
        self.serial = self.serial.checked_add(1)?;
        let lease = Lease { arena_id: self.id, slot: index, serial: self.serial, generation: self.generation };
        let slot = &mut self.slots[index];
        slot.bytes[..data.len()].copy_from_slice(data);
        slot.len = data.len();
        slot.owner = Some(lease);
        Some(lease)
    }
    pub fn read(&self, lease: Lease) -> Option<&[u8]> {
        let slot = self.slots.get(lease.slot)?;
        if slot.owner != Some(lease) || lease.generation != self.generation {
            return None;
        }
        Some(&slot.bytes[..slot.len])
    }
    pub fn release(&mut self, lease: Lease) -> bool {
        let Some(slot) = self.slots.get_mut(lease.slot) else { return false };
        if slot.owner != Some(lease) { return false; }
        slot.owner = None;
        slot.len = 0;
        true
    }
    // Stale leases reserve their slot until explicitly released.
    pub fn invalidate(&mut self) -> bool {
        let Some(next) = self.generation.checked_add(1) else { return false };
        self.generation = next;
        true
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sizing_matches_jvm_cases() {
        assert_eq!(annex_b_size(&[0,3,11,22,33,0,2,44,55],2), Some(13));
        assert_eq!(annex_b_size(&[2,11,12,3,13,14,15],1), Some(13));
        assert_eq!(annex_b_size(&[0,0,2,9,10],3), Some(6));
        assert_eq!(annex_b_size(&[0,0,0,2,9,10],4), Some(6));
        assert_eq!(annex_b_size(&[0,4,9,10],2), None);
        assert_eq!(annex_b_size(&[0],2), None);
        assert_eq!(annex_b_size(&[],5), None);
        assert_eq!(annex_b_size(&[2,9,10].repeat(4097),1), None);
    }
    #[test]
    fn independent_arenas_reject_foreign_leases() {
        let mut a = Arena::new(1, 32).unwrap();
        let mut b = Arena::new(1, 32).unwrap();
        let x = a.publish(b"a").unwrap();
        let y = b.publish(b"b").unwrap();
        assert_ne!(x,y);
        assert!(a.read(y).is_none());
        assert!(!a.release(y));
        assert!(b.read(x).is_none());
        assert!(!b.release(x));
        assert_eq!(a.read(x), Some(b"a".as_slice()));
        assert_eq!(b.read(y), Some(b"b".as_slice()));
    }
    #[test]
    fn serialized_concurrent_invalidation_and_release() {
        use std::sync::{Arc, Barrier, Mutex};
        use std::thread;
        let arena = Arc::new(Mutex::new(Arena::new(1, 32).unwrap()));
        let lease = arena.lock().unwrap().publish(b"old").unwrap();
        let barrier = Arc::new(Barrier::new(2));
        let a = Arc::clone(&arena);
        let b = Arc::clone(&barrier);
        let worker = thread::spawn(move || {
            b.wait();
            let mut locked = a.lock().unwrap();
            assert!(locked.read(lease).is_none());
            assert!(locked.release(lease));
            let new = locked.publish(b"new").unwrap();
            assert!(!locked.release(lease));
            assert_eq!(locked.read(new), Some(b"new".as_slice()));
            assert!(locked.release(new));
        });
        { let mut locked = arena.lock().unwrap(); assert!(locked.invalidate()); assert!(locked.publish(b"blocked").is_none()); }
        barrier.wait();
        worker.join().unwrap();
        assert!(arena.lock().unwrap().publish(b"done").is_some());
    }
    #[test]
    fn lease_generation_exhaustion_and_stale_release() {
        let mut arena = Arena::new(1,8).unwrap();
        assert!(Arena::new(8,16*1024*1024).is_none());
        assert!(arena.publish(&[0;9]).is_none());
        let a = arena.publish(b"abc").unwrap();
        assert_eq!(arena.read(a),Some(b"abc".as_slice()));
        assert!(arena.publish(b"def").is_none());
        assert!(arena.invalidate());
        assert!(arena.read(a).is_none());
        assert!(arena.publish(b"def").is_none());
        assert!(arena.release(a));
        let b = arena.publish(b"def").unwrap();
        assert!(!arena.release(a));
        assert_eq!(arena.read(b),Some(b"def".as_slice()));
        assert!(arena.release(b));
        assert!(!arena.release(b));
    }
}
