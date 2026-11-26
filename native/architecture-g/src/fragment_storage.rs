//! Bounded generic fragment memory, separate from the HEVC/DRM validators.
//! The JVM private owner serializes access to each view and retires the
//! final view before release. Direct native writes are allowed only before
//! the first view is exposed; no native code modifies a published view.
#[cfg(any(target_os = "android", test))]
use std::collections::{BTreeMap, BTreeSet};
#[cfg(target_os = "android")]
use std::sync::{Mutex, OnceLock};

#[cfg(any(target_os = "android", test))]
const MAX_FRAGMENT: usize = 64 * 1024 * 1024;
#[cfg(any(target_os = "android", test))]
const MAX_BYTES: usize = 64 * 1024 * 1024;
#[cfg(any(target_os = "android", test))]
const MAX_LIVE: usize = 12;
#[cfg(any(target_os = "android", test))]
const MAX_FREE: usize = 2;
#[cfg(any(target_os = "android", test))]
const MAX_FREE_BYTES: usize = 8 * 1024 * 1024;
const MAX_REUSE_SLACK: usize = 1024 * 1024;
pub(crate) fn reusable_capacity(size:usize, capacity:usize)->bool {
    size <= capacity && capacity <= size.saturating_add((size / 8).min(MAX_REUSE_SLACK))
}

#[cfg(any(target_os = "android", test))]
#[derive(Default)]
struct Arena {
    serial: i64,
    // Explicit pressure trims also retire backing from existing leases once
    // their last reader closes. A monotone watermark needs no per-fragment set.
    discard_through: i64,
    live: BTreeMap<i64, Vec<u8>>,
    viewed: BTreeSet<i64>,
    ranges: BTreeMap<i64, std::ops::Range<usize>>,
    owned_allocations: u64,
    owned_reuses: u64,
    adopted: u64,
    adopted_bytes: u64,
    free: Vec<Vec<u8>>,
    charged: usize,
    allocations: u64,
}
#[cfg(any(target_os = "android", test))]
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
        let reusable = self.best_free(size);
        let bytes = if let Some(i) = reusable {
            let mut bytes = self.free.swap_remove(i);
            bytes.resize(size, 0);
            bytes
        } else {
            // Unsuitable old backing is not a second reserve beside new backing.
            self.trim();
            if size > limit.saturating_sub(self.charged) { return None; }
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
    fn best_free(&self,size:usize)->Option<usize> {
        self.free.iter().enumerate().filter(|(_,b)|reusable_capacity(size,b.capacity()))
            .min_by_key(|(_,b)|b.capacity()).map(|(i,_)|i)
    }
    // The worker already has a bounded slot commitment. Move free backing
    // OUT of this arena before writing, so it is charged only to the worker.
    fn take_output(&mut self,size:usize,ceiling:usize)->Option<Vec<u8>> {
        if size==0 || size>ceiling || ceiling>crate::bmff::MAX_ATLAS_BYTES {return None;}
        if let Some(i)=self.best_free(size).filter(|&i|self.free[i].capacity()<=ceiling) {
            let mut b=self.free.swap_remove(i);
            self.charged-=b.capacity();
            b.resize(size,0);
            self.owned_reuses=self.owned_reuses.saturating_add(1);
            Some(b)
        } else {
            self.trim();
            let mut b=Vec::new();
            b.try_reserve_exact(size).ok()?;
            b.resize(size,0);
            self.owned_allocations=self.owned_allocations.saturating_add(1);
            Some(b)
        }
    }
    // Reserve arena capacity before destructively taking an engine output.
    // Capacity includes the small packet prefix and any reused backing slack.
    fn adopt(&mut self,capacity:usize,packet_len:usize,offset:usize,size:usize,
             incremental:usize,take:impl FnOnce()->Option<Vec<u8>>)->Option<i64> {
        if size==0 || offset.checked_add(size)?!=packet_len || packet_len>capacity ||
            capacity>MAX_BYTES || capacity>incremental || self.live.len()>=MAX_LIVE ||
            !reusable_capacity(packet_len,capacity) {return None;}
        let serial=self.serial.checked_add(1)?;
        if capacity>MAX_BYTES.saturating_sub(self.charged) {self.trim();}
        if capacity>MAX_BYTES.saturating_sub(self.charged) {return None;}
        let bytes=take()?;
        // The engine validates these exact immutable slot dimensions while
        // taking the Vec. A mismatch is a programming error, not a fallback.
        assert_eq!(bytes.capacity(),capacity);
        assert_eq!(bytes.len(),packet_len);
        self.charged+=capacity;
        self.serial=serial;
        self.live.insert(serial,bytes);
        self.ranges.insert(serial,offset..packet_len);
        self.adopted=self.adopted.saturating_add(1);
        self.adopted_bytes=self.adopted_bytes.saturating_add(size as u64);
        Some(serial)
    }
    fn allocate_headroom(&mut self, size:usize, incremental:usize)->Option<i64> {
        // Resident backing is already reflected in the OS headroom sample.
        // Charge new capacity, while retaining the original hard arena cap.
        let ceiling=self.charged.saturating_add(incremental).min(MAX_BYTES);
        self.allocate_bounded(size,ceiling)
    }
    fn write_unpublished(&mut self, id:i64, size:usize, write:impl FnOnce(&mut [u8])->i64)->Option<i64> {
        if self.viewed.contains(&id) || self.ranges.contains_key(&id) {return None;}
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
        self.ranges.remove(&id);
        // Final Java owner has retired every view before calling release.
        bytes.fill(0);
        // No idle native arena survives the final history/reader lease.
        if self.live.is_empty() {
            self.charged -= bytes.capacity();
            self.trim();
            return true;
        }
        // A lease active at an explicit pressure trim must not recreate
        // the idle backing that trim just reclaimed. New leases may still
        // use the normal bounded cache; active readers remain untouched.
        if id <= self.discard_through {
            self.charged -= bytes.capacity();
            return true;
        }
        // Prefer recent size observations over a stale backing that survived
        // several incompatible fragments. Keep the same total free cap.
        while self.free.len()>=MAX_FREE || bytes.capacity()>MAX_FREE_BYTES.saturating_sub(
            self.free.iter().map(Vec::capacity).sum::<usize>()) {
            if self.free.is_empty() || bytes.capacity()>MAX_FREE_BYTES {break;}
            let old=self.free.remove(0);
            self.charged-=old.capacity();
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
         self.live.iter().map(|(id,b)|self.ranges.get(id).map_or(b.len(),|r|r.len())).sum::<usize>() as u64,
         self.live.len() as u64, self.allocations]
    }
    fn trim(&mut self) {
        for bytes in self.free.drain(..) { self.charged -= bytes.capacity(); }
    }
    fn trim_pressure(&mut self) {
        self.discard_through = self.serial;
        self.trim();
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
pub fn trim() { if let Ok(mut a) = arena().lock() { a.trim_pressure(); } }
#[cfg(target_os = "android")]
pub fn snapshot() -> Option<[u64; 9]> {
    let a=arena().lock().ok()?;
    let [capacity,live,payload,count,allocations]=a.snapshot();
    Some([capacity,live,payload,count,allocations,a.owned_allocations,a.owned_reuses,a.adopted,a.adopted_bytes])
}
#[cfg(target_os = "android")]
pub(crate) fn adopt(capacity:usize,packet_len:usize,offset:usize,size:usize,
                    incremental:usize,take:impl FnOnce()->Option<Vec<u8>>)->Option<i64> {
    arena().lock().ok()?.adopt(capacity,packet_len,offset,size,incremental,take)
}
pub(crate) fn output_buffer(size:usize,ceiling:usize)->Option<Vec<u8>> {
    if size==0 || size>ceiling {return None;}
    #[cfg(target_os = "android")]
    { arena().lock().ok()?.take_output(size,ceiling) }
    #[cfg(not(target_os = "android"))]
    { let mut b=Vec::new();b.try_reserve_exact(size).ok()?;b.resize(size,0);Some(b) }
}

/// Used only for the private JNI view. Its caller must keep the allocation
/// alive until all Java views have been retired; no raw pointer is retained
/// by any Rust consumer.
#[cfg(target_os = "android")]
pub fn view(id: i64) -> Option<(*mut u8, usize)> {
    let mut a = arena().lock().ok()?;
    if !a.mark_viewed(id) {return None;}
    let range=a.ranges.get(&id).cloned();
    let bytes = a.live.get_mut(&id)?;
    let view=match range { Some(r)=>bytes.get_mut(r)?,None=>bytes.as_mut_slice() };
    Some((view.as_mut_ptr(), view.len()))
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
    #[test]
    fn owned_output_adoption_keeps_same_pointer_and_charges_once() {
        let mut arena=Arena::default();
        let keep=arena.allocate(1).unwrap();
        let mut output=arena.take_output(1024,2048).unwrap();
        output[16..].fill(7);
        let ptr=output.as_ptr();
        let capacity=output.capacity();
        assert!(arena.adopt(capacity,1024,16,1008,capacity-1,||panic!("rejected transfer consumed output")).is_none());
        let id=arena.adopt(capacity,1024,16,1008,capacity,||Some(output)).unwrap();
        assert_eq!(arena.live[&id].as_ptr(),ptr);
        assert_eq!(arena.ranges[&id],16..1024);
        assert_eq!(arena.snapshot()[2],1009);
        assert_eq!(arena.adopted,1);
        assert_eq!(arena.adopted_bytes,1008);
        assert!(arena.write_unpublished(id,1008,|_|panic!("adopted bytes are immutable")).is_none());
        assert!(arena.release(id));
        let reused=arena.take_output(960,2048).unwrap();
        assert_eq!(reused.as_ptr(),ptr);
        assert!(reused.iter().all(|&b|b==0));
        assert_eq!(arena.owned_allocations,1);
        assert_eq!(arena.owned_reuses,1);
        assert!(arena.release(keep));
        assert_eq!(arena.snapshot()[0],0);
    }

    #[test]
    fn explicit_pressure_trim_retires_existing_backing_only_after_last_lease() {
        let mut a=Arena::default();
        let sentinel=a.allocate(1).unwrap();
        let old=a.allocate(1024).unwrap();
        a.live.get_mut(&old).unwrap().fill(0x5a);
        assert!(a.mark_viewed(old));
        let idle=a.allocate(64).unwrap();
        assert!(a.release(idle));
        assert_eq!(a.snapshot()[0],1089);
        a.trim_pressure();
        assert_eq!(a.snapshot()[0],1025);
        assert_eq!(a.live[&old].as_slice(), &[0x5a;1024]);
        let fresh=a.allocate(512).unwrap();
        assert!(a.release(old));
        assert_eq!(a.snapshot()[0],513);
        assert!(a.free.is_empty());
        assert!(a.release(fresh));
        assert_eq!(a.free.len(),1);
        assert!(a.release(sentinel));
        assert_eq!(a.snapshot()[0],0);
    }

    #[test]
    fn build_886_fragment_sizes_replay_with_bounded_capacity() {
        let sizes=[7895615,6988629,6861401,6582662,6434449,6215677,6932098,6932098,
            6577768,7313173,6215467,7076904,7895615,6988629,6861401,6582662,6434449,
            6857997,6665906,6415811,6056382,6601462,6640173,6951403,6837369,6357568,
            6380200,6424259,6613333,7439555,6507094,7220002,6436775,7137153];
        let mut a=Arena::default();
        let mut cached=std::collections::VecDeque::new();
        let mut peak=0;
        for size in sizes {
            let packet_len=size+1024;
            let output=a.take_output(packet_len,16*1024*1024).unwrap();
            let capacity=output.capacity();
            peak=peak.max(a.charged+capacity);
            let id=a.adopt(capacity,packet_len,1024,size,MAX_BYTES,||Some(output)).unwrap();
            cached.push_back(id);
            if cached.len()>3 {assert!(a.release(cached.pop_front().unwrap()));}
            assert!(a.charged<=MAX_BYTES);
        }
        assert!(a.owned_allocations<=20);
        assert!(peak<=30*1024*1024);
        assert_eq!(a.adopted,34);
        for id in cached {assert!(a.release(id));}
        assert_eq!(a.charged,0);
    }
}
