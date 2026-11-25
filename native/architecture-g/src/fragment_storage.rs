//! Bounded generic fragment memory, separate from the HEVC/DRM validators.
//! The JVM private owner serializes access to each view and retires the
//! final view before release. No native code reads or modifies a live view.
use std::collections::BTreeMap;
#[cfg(target_os = "android")]
use std::sync::{Mutex, OnceLock};

const MAX_FRAGMENT: usize = 64 * 1024 * 1024;
const MAX_BYTES: usize = 64 * 1024 * 1024;
const MAX_LIVE: usize = 12;
const MAX_FREE: usize = 2;
const MAX_FREE_BYTES: usize = 8 * 1024 * 1024;

#[derive(Default)]
struct Arena {
    serial: i64,
    live: BTreeMap<i64, Vec<u8>>,
    free: Vec<Vec<u8>>,
    charged: usize,
}
impl Arena {
    fn allocate(&mut self, size: usize) -> Option<i64> {
        if size == 0 || size > MAX_FRAGMENT || self.live.len() >= MAX_LIVE {
            return None;
        }
        let serial = self.serial.checked_add(1)?;
        let bytes = if let Some(i) = self.free.iter().position(|b| b.len() == size) {
            self.free.swap_remove(i)
        } else {
            while size > MAX_BYTES.saturating_sub(self.charged) {
                let old = self.free.pop()?;
                self.charged -= old.capacity();
            }
            let mut bytes = Vec::new();
            bytes.try_reserve_exact(size).ok()?;
            let charged = self.charged.checked_add(bytes.capacity())?;
            if charged > MAX_BYTES { return None; }
            bytes.resize(size, 0);
            self.charged = charged;
            bytes
        };
        self.serial = serial;
        self.live.insert(serial, bytes);
        Some(serial)
    }
    fn release(&mut self, id: i64) -> bool {
        let Some(mut bytes) = self.live.remove(&id) else { return false; };
        // Final Java owner has retired every view before calling release.
        bytes.fill(0);
        // No idle native arena survives the final history/reader lease.
        if self.live.is_empty() {
            self.charged -= bytes.capacity();
            self.trim();
            return true;
        }
        let cached = self.free.iter().map(Vec::capacity).sum::<usize>();
        if self.free.len() < MAX_FREE && bytes.capacity() <= MAX_FREE_BYTES.saturating_sub(cached) {
            self.free.push(bytes);
        } else {
            self.charged -= bytes.capacity();
        }
        true
    }
    fn trim(&mut self) {
        for bytes in self.free.drain(..) { self.charged -= bytes.capacity(); }
    }
}
#[cfg(target_os = "android")]
static ARENA: OnceLock<Mutex<Arena>> = OnceLock::new();
#[cfg(target_os = "android")]
fn arena() -> &'static Mutex<Arena> { ARENA.get_or_init(|| Mutex::new(Arena::default())) }

#[cfg(target_os = "android")]
pub fn allocate(size: usize) -> Option<i64> { arena().lock().ok()?.allocate(size) }
#[cfg(target_os = "android")]
pub fn release(id: i64) -> bool { arena().lock().is_ok_and(|mut a| a.release(id)) }
#[cfg(target_os = "android")]
pub fn trim() { if let Ok(mut a) = arena().lock() { a.trim(); } }

/// Used only for the private JNI view. Its caller must keep the allocation
/// alive until all Java views have been retired; no raw pointer is retained
/// by any Rust consumer.
#[cfg(target_os = "android")]
pub fn view(id: i64) -> Option<(*mut u8, usize)> {
    let mut a = arena().lock().ok()?;
    let bytes = a.live.get_mut(&id)?;
    Some((bytes.as_mut_ptr(), bytes.len()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_reuse_and_retirement() {
        let mut a = Arena::default();
        assert!(a.allocate(0).is_none());
        assert!(a.allocate(MAX_BYTES+1).is_none());
        let sentinel = a.allocate(1).unwrap();
        let id = a.allocate(32).unwrap();
        a.live.get_mut(&id).unwrap().fill(0xa5);
        assert!(a.release(id));
        assert!(!a.release(id));
        assert_eq!(a.free.len(), 1);
        let next = a.allocate(32).unwrap();
        assert_ne!(next,id);
        assert_eq!(a.live.get(&next).unwrap(), &[0;32]);
        assert!(a.release(next));
        assert!(a.release(sentinel));
        assert_eq!(a.charged, 0);
        let ids: Vec<_> = (0..MAX_LIVE).map(|_| a.allocate(1).unwrap()).collect();
        assert!(a.allocate(1).is_none());
        for id in ids { assert!(a.release(id)); }
        a.trim();
        assert_eq!(a.charged, 0);
    }
}
