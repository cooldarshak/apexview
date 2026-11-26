//! Bounded generic fragment memory, separate from the HEVC/DRM validators.
//! The JVM private owner serializes access to each view and retires the
//! final view before release. Direct native writes are allowed only before
//! the first view is exposed; no native code modifies a published view.
use std::collections::{BTreeMap, BTreeSet};
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
    viewed: BTreeSet<i64>,
    free: Vec<Vec<u8>>,
    charged: usize,
    allocations: u64,
}
impl Arena {
    fn allocate(&mut self, size: usize) -> Option<i64> { self.allocate_bounded(size, MAX_BYTES) }
    fn allocate_bounded(&mut self, size: usize, limit: usize) -> Option<i64> {
        let limit=limit.min(MAX_BYTES);
        if size == 0 || size > MAX_FRAGMENT || size > limit || self.live.len() >= MAX_LIVE {
            return None;
        }
        let serial = self.serial.checked_add(1)?;
        if self.charged>limit { self.trim(); }
        if self.charged>limit {return None;}
        // Fragment sizes vary slightly even at fixed video quality. Reuse the
        // smallest suitable free backing, with strictly bounded slack.
        // Expose only the requested length; charge the entire capacity.
        let max_capacity = size + (size / 8).min(64 * 1024);
        let reusable = self.free.iter().enumerate()
            .filter(|(_, b)| (size..=max_capacity).contains(&b.capacity()))
            .min_by_key(|(_, b)| b.capacity()).map(|(i, _)| i);
        let bytes = if let Some(i) = reusable {
            let mut bytes = self.free.swap_remove(i);
            bytes.resize(size, 0);
            bytes
        } else {
            while size > limit.saturating_sub(self.charged) {
                let old = self.free.pop()?;
                self.charged -= old.capacity();
            }
            let mut bytes = Vec::new();
            bytes.try_reserve_exact(size).ok()?;
            let charged = self.charged.checked_add(bytes.capacity())?;
            if charged > limit { return None; }
            bytes.resize(size, 0);
            self.charged = charged;
            self.allocations = self.allocations.saturating_add(1);
            bytes
        };
        self.serial = serial;
        self.live.insert(serial, bytes);
        Some(serial)
    }
    fn allocate_headroom(&mut self, size:usize, incremental:usize)->Option<i64> {
        // Resident backing is already reflected in the OS headroom sample.
        // Charge new capacity, while retaining the original hard arena cap.
        let ceiling=self.charged.saturating_add(incremental).min(MAX_BYTES);
        self.allocate_bounded(size,ceiling)
    }
    fn write_unpublished(&mut self, id:i64, size:usize, write:impl FnOnce(&mut [u8])->i64)->Option<i64> {
        if self.viewed.contains(&id) {return None;}
        let dst=self.live.get_mut(&id)?;
        if dst.len()!=size {return None;}
        Some(write(dst))
    }
    fn mark_viewed(&mut self,id:i64)->bool {
        if !self.live.contains_key(&id) {return false;}
        self.viewed.insert(id);
        true
    }
    fn release(&mut self, id: i64) -> bool {
        let Some(mut bytes) = self.live.remove(&id) else { return false; };
        self.viewed.remove(&id);
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
    // Unique capacity and logical live payload are deliberately separate.
    fn snapshot(&self) -> [u64; 5] {
        [self.charged as u64,
         self.live.values().map(Vec::capacity).sum::<usize>() as u64,
         self.live.values().map(Vec::len).sum::<usize>() as u64,
         self.live.len() as u64, self.allocations]
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
pub fn allocate_bounded(size: usize, limit: usize) -> Option<i64> { arena().lock().ok()?.allocate_bounded(size,limit) }
#[cfg(target_os = "android")]
pub fn allocate_headroom(size:usize, incremental:usize)->Option<i64> {
    arena().lock().ok()?.allocate_headroom(size,incremental)
}
#[cfg(target_os = "android")]
pub fn write_unpublished(id:i64, size:usize, write:impl FnOnce(&mut [u8])->i64)->Option<i64> {
    arena().lock().ok()?.write_unpublished(id,size,write)
}
#[cfg(target_os = "android")]
pub fn release(id: i64) -> bool { arena().lock().is_ok_and(|mut a| a.release(id)) }
#[cfg(target_os = "android")]
pub fn trim() { if let Ok(mut a) = arena().lock() { a.trim(); } }
#[cfg(target_os = "android")]
pub fn snapshot() -> Option<[u64; 5]> { Some(arena().lock().ok()?.snapshot()) }

/// Used only for the private JNI view. Its caller must keep the allocation
/// alive until all Java views have been retired; no raw pointer is retained
/// by any Rust consumer.
#[cfg(target_os = "android")]
pub fn view(id: i64) -> Option<(*mut u8, usize)> {
    let mut a = arena().lock().ok()?;
    if !a.mark_viewed(id) {return None;}
    let bytes = a.live.get_mut(&id)?;
    Some((bytes.as_mut_ptr(), bytes.len()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn incremental_headroom_charges_existing_backing_once() {
        let mut a=Arena::default();
        let keep=a.allocate_headroom(16,16).unwrap();
        let free=a.allocate_headroom(32,32).unwrap();
        assert!(a.allocate_headroom(1,0).is_none());
        assert!(a.release(free));
        let reused=a.allocate_headroom(32,0).unwrap();
        assert_eq!(a.snapshot()[0],48);
        assert_eq!(a.snapshot()[4],2);
        assert!(a.release(reused));
        assert!(a.release(keep));
        assert_eq!(a.snapshot()[0],0);
    }
    #[test]
    fn native_transfer_cannot_modify_exposed_or_retired_backing() {
        let mut a=Arena::default();
        let id=a.allocate(4).unwrap();
        assert_eq!(a.write_unpublished(id,3,|_|panic!("wrong size")),None);
        assert_eq!(a.write_unpublished(id,4,|dst|{dst.copy_from_slice(b"test");4}),Some(4));
        assert!(a.mark_viewed(id));
        assert_eq!(a.write_unpublished(id,4,|_|panic!("published")),None);
        assert_eq!(a.live.get(&id).unwrap().as_slice(),b"test");
        assert!(a.release(id));
        assert!(!a.mark_viewed(id));
        assert_eq!(a.write_unpublished(id,4,|_|panic!("retired")),None);
    }
    #[test]
    fn shrinking_headroom_never_evicts_live_readers() {
        let mut a=Arena::default();
        let keep=a.allocate_bounded(32,64).unwrap();
        let retired=a.allocate_bounded(32,64).unwrap();
        a.live.get_mut(&keep).unwrap().fill(0x63);
        assert!(a.release(retired));
        assert!(a.allocate_bounded(16,31).is_none());
        assert_eq!(a.charged,32);
        assert_eq!(a.live.get(&keep).unwrap(), &[0x63;32]);
        let next=a.allocate_bounded(16,48).unwrap();
        assert_eq!(a.charged,48);
        assert!(a.release(keep)); assert!(a.release(next));
        assert_eq!(a.charged,0);
    }

    #[test]
    fn nearby_sizes_reuse_only_bounded_slack_and_charge_capacity() {
        let mut a=Arena::default();
        let keep=a.allocate(1).unwrap();
        let old=a.allocate(1024).unwrap();
        a.live.get_mut(&old).unwrap().fill(0x61);
        assert!(a.release(old));
        let near=a.allocate(960).unwrap();
        assert_eq!(a.live.get(&near).unwrap().as_slice(), &[0;960]);
        assert_eq!(a.snapshot(),[1025,1025,961,2,2]);
        assert!(a.release(near));
        let small=a.allocate(64).unwrap();
        assert_eq!(a.snapshot()[4],3);
        assert_eq!(a.live.get(&small).unwrap().len(),64);
        assert!(a.release(small));
        assert!(a.release(keep));
        assert_eq!(a.snapshot(),[0,0,0,0,3]);
    }

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
