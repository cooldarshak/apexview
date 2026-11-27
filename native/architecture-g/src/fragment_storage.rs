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
// A moved realloc copies the wiped old fragment into a new allocation, which
// has no value to an overwrite-only writer. Learn from this process allocator;
// do not penalize platforms that actually grow their buffers in place.
#[cfg(any(target_os = "android", test))]
const MAX_UNHELPFUL_ATLAS_GROWTHS: u8 = 4;
pub(crate) fn reusable_capacity(size:usize, capacity:usize)->bool {
    size <= capacity && capacity <= size.saturating_add((size / 8).min(MAX_REUSE_SLACK))
}

// Build 910: 172 outputs varied 7.6–9.5 MiB, 47 undersize misses.
// Round only already admitted multi-MiB allocation within original reuse
// slack, worker ceiling and idle permit. No new pool or larger cap.
#[cfg(any(target_os = "android", test))]
fn atlas_reservation(size: usize, ceiling: usize, idle_limit: usize) -> usize {
    const CLASS: usize = 512 * 1024;
    if size < 4 * 1024 * 1024 { return size; }
    let rounded = size.saturating_add(CLASS - 1) / CLASS * CLASS;
    if rounded <= ceiling && rounded <= idle_limit && reusable_capacity(size, rounded) {
        rounded
    } else { size }
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
    // Clear atlas output only. Never expose this cache to generic allocations.
    atlas_idle: Option<Vec<u8>>,
    atlas_idle_limit: usize,
    owned_miss_empty: u64,
    owned_miss_fit: u64,
    atlas_fit_small: u64,
    atlas_fit_large: u64,
    resize_attempts: u64,
    resize_same_address: u64,
    resize_moved: u64,
    resize_failed: u64,
    resize_bypassed: u64,
    resize_unhelpful_streak: u8,
    owned_miss_ceiling: u64,
    owned_alloc_failures: u64,
    owned_eligible: u64,
    owned_miss_pinned: u64,
    owned_miss_disabled: u64,
    adopt_rejected: u64,
    stale_releases: u64,
    peak_charged: usize,
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
            // Generic churn must not evict the independently admitted atlas
            // idle buffer if the new generic owner still fits the total cap.
            // Reclaim generic backing first; revoke atlas backing only when
            // the real combined byte limit requires it.
            self.trim_generic();
            if size > limit.saturating_sub(self.charged) { self.trim(); }
            if size > limit.saturating_sub(self.charged) { return None; }
            let mut bytes = Vec::new();
            bytes.try_reserve_exact(size).ok()?;
            let charged = self.charged.checked_add(bytes.capacity())?;
            if charged > limit { return None; }
            bytes.resize(size, 0);
            self.charged = charged;
            self.peak_charged=self.peak_charged.max(charged);
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
    fn note_atlas_growth(&mut self, in_place: bool) {
        self.resize_unhelpful_streak = if in_place { 0 } else {
            self.resize_unhelpful_streak.saturating_add(1)
        };
    }
    // The worker already has a bounded slot commitment. Move free backing
    // OUT of this arena before writing, so it is charged only to the worker.
    fn take_output(&mut self,size:usize,ceiling:usize)->Option<Vec<u8>> {
        if size==0 || size>ceiling || ceiling>crate::bmff::MAX_ATLAS_BYTES {return None;}
        if self.atlas_idle.as_ref().is_some_and(|b|b.capacity()<=ceiling && reusable_capacity(size,b.capacity())) ||
            self.best_free(size).is_some_and(|i|self.free[i].capacity()<=ceiling) {
            self.owned_eligible=self.owned_eligible.saturating_add(1);
        }
        let mut miss_ceiling=false;
        let mut miss_fit=false;
        let mut grow=None;
        if let Some(mut b)=self.atlas_idle.take() {
            self.charged-=b.capacity();
            if b.capacity()>ceiling {
                miss_ceiling=true;
            } else if !reusable_capacity(size,b.capacity()) {
                miss_fit=true;
                if b.capacity()<size {
                    self.atlas_fit_small=self.atlas_fit_small.saturating_add(1);
                    if size-b.capacity() <= (size/8).min(MAX_REUSE_SLACK) {grow=Some(b);}
                } else {
                    self.atlas_fit_large=self.atlas_fit_large.saturating_add(1);
                }
            } else {
                b.resize(size,0);
                self.owned_reuses=self.owned_reuses.saturating_add(1);
                return Some(b);
            }
        }
        let generic=self.best_free(size);
        if let Some(i)=generic.filter(|&i|self.free[i].capacity()<=ceiling) {
            let mut b=self.free.swap_remove(i);
            self.charged-=b.capacity();
            b.resize(size,0);
            self.owned_reuses=self.owned_reuses.saturating_add(1);
            Some(b)
        } else {
            // Classify one actual reuse miss, not each inspected candidate.
            // A generic backing may fit the requested bytes but exceed the
            // worker ceiling; this used to be an unclassified allocation.
            if miss_ceiling || generic.is_some() {
                self.owned_miss_ceiling=self.owned_miss_ceiling.saturating_add(1);
            } else if miss_fit || !self.free.is_empty() {
                self.owned_miss_fit=self.owned_miss_fit.saturating_add(1);
            } else if self.atlas_idle_limit==0 {
                self.owned_miss_disabled=self.owned_miss_disabled.saturating_add(1);
            } else if !self.ranges.is_empty() {
                self.owned_miss_pinned=self.owned_miss_pinned.saturating_add(1);
            } else {
                self.owned_miss_empty=self.owned_miss_empty.saturating_add(1);
            }
            self.trim();
            // Only the wiped, unleased atlas backing can be resized. No extra
            // reserved slack; the worker ceiling and arena caps stay unchanged.
            // A moved realloc is not counted as a reuse or physical memory saving.
            // A compatible generic backing has already won above. Only count
            // a bypass when we would otherwise attempt near-size atlas growth.
            // Drop the old allocation BEFORE reserving fresh backing so there
            // is no needless overlap/copy of two multi-MiB wiped buffers.
            if grow.is_some() && self.resize_unhelpful_streak >= MAX_UNHELPFUL_ATLAS_GROWTHS {
                self.resize_bypassed=self.resize_bypassed.saturating_add(1);
                drop(grow.take());
            }
            if let Some(mut b)=grow {
                self.resize_attempts=self.resize_attempts.saturating_add(1);
                let old=b.as_ptr() as usize;
                b.clear();
                if b.try_reserve_exact(size).is_ok() && b.capacity()<=ceiling && reusable_capacity(size,b.capacity()) {
                    let in_place=b.as_ptr() as usize==old;
                    if in_place {self.resize_same_address=self.resize_same_address.saturating_add(1);}
                    else {self.resize_moved=self.resize_moved.saturating_add(1);}
                    self.note_atlas_growth(in_place);
                    b.resize(size,0);
                    self.owned_allocations=self.owned_allocations.saturating_add(1);
                    return Some(b);
                }
                self.resize_failed=self.resize_failed.saturating_add(1);
                self.note_atlas_growth(false);
            }
            let mut b=Vec::new();
            let reservation = atlas_reservation(size, ceiling, self.atlas_idle_limit);
            if b.try_reserve_exact(reservation).is_err() || b.capacity()>ceiling ||
                !reusable_capacity(size,b.capacity()) {
                b=Vec::new();
                if reservation == size || b.try_reserve_exact(size).is_err() || b.capacity()>ceiling {
                    self.owned_alloc_failures=self.owned_alloc_failures.saturating_add(1);
                    return None;
                }
            }
            b.resize(size,0);
            self.owned_allocations=self.owned_allocations.saturating_add(1);
            Some(b)
        }
    }
    // Reserve arena capacity before destructively taking an engine output.
    // Capacity includes the small packet prefix and any reused backing slack.
    fn adopt(&mut self,capacity:usize,packet_len:usize,offset:usize,size:usize,
             incremental:usize,take:impl FnOnce()->Option<Vec<u8>>)->Option<i64> {
        let result=self.adopt_checked(capacity,packet_len,offset,size,incremental,take);
        if result.is_none() {
            self.adopt_rejected=self.adopt_rejected.saturating_add(1);
        }
        result
    }
    fn adopt_checked(&mut self,capacity:usize,packet_len:usize,offset:usize,size:usize,
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
        self.peak_charged=self.peak_charged.max(self.charged);
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
        let atlas=self.ranges.remove(&id).is_some();
        // Final Java owner has retired every view before calling release.
        // Wipe spare capacity as well, before any reuse across outputs.
        // Wipe initialized bytes once and initialize the spare tail once.
        // Resizing first would write the spare tail twice on every reuse.
        bytes.fill(0);
        bytes.resize(bytes.capacity(),0);
        // A lease active at an explicit pressure trim must not recreate
        // the idle backing that trim just reclaimed. New leases may still
        // use the normal bounded cache; active readers remain untouched.
        if id <= self.discard_through {
            self.stale_releases=self.stale_releases.saturating_add(1);
            self.charged -= bytes.capacity();
            // The last live lease can itself predate the pressure watermark.
            // New generic idle buffers may have accumulated while it was
            // pinned. Retire those too when no live owner remains, but keep
            // any separately admitted post-pressure atlas idle backing.
            if self.live.is_empty() {
                self.trim_generic();
            }
            return true;
        }
        if atlas {
            // Keep at most one idle atlas backing, within the latest sampled
            // incremental allowance. The active engine, not a sentinel
            // reader, owns the idle permit. Seek/close/pressure revoke it.
            if bytes.capacity()<=self.atlas_idle_limit {
                self.trim();
                self.atlas_idle=Some(bytes);
            } else {
                self.charged-=bytes.capacity();
            }
            return true;
        }
        if self.atlas_idle.is_some() || self.live.is_empty() {
            self.charged-=bytes.capacity();
            // Generic cache has no independent lifetime. Do not destroy
            // the active clear atlas permit merely because a generic reader ends.
            for free in self.free.drain(..){self.charged-=free.capacity();}
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
    fn set_atlas_idle_limit(&mut self,limit:usize) {
        self.atlas_idle_limit=limit.min(crate::bmff::MAX_ATLAS_BYTES);
        if self.atlas_idle_limit==0 {
            self.trim_pressure();
        } else if self.atlas_idle.as_ref().is_some_and(|b|b.capacity()>self.atlas_idle_limit) {
            if let Some(b)=self.atlas_idle.take(){self.charged-=b.capacity();}
        }
    }
    fn trim_generic(&mut self) {
        for bytes in self.free.drain(..) { self.charged -= bytes.capacity(); }
    }
    fn trim(&mut self) {
        self.trim_generic();
        if let Some(bytes)=self.atlas_idle.take(){self.charged-=bytes.capacity();}
    }
    fn trim_pressure(&mut self) {
        self.discard_through = self.serial;
        self.atlas_idle_limit=0;
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
pub fn snapshot() -> Option<[u64; 28]> {
    let a=arena().lock().ok()?;
    let [capacity,live,payload,count,allocations]=a.snapshot();
    Some([capacity,live,payload,count,allocations,a.owned_allocations,a.owned_reuses,a.adopted,a.adopted_bytes,
          a.atlas_idle_limit as u64,a.atlas_idle.as_ref().map_or(0,|b|b.capacity()) as u64,
          a.owned_miss_empty,a.owned_miss_fit,a.owned_miss_ceiling,a.owned_alloc_failures,
          a.owned_eligible,a.owned_miss_pinned,a.owned_miss_disabled,a.adopt_rejected,
          a.stale_releases,a.peak_charged as u64,a.atlas_fit_small,a.atlas_fit_large,
          a.resize_attempts,a.resize_same_address,a.resize_moved,a.resize_failed,a.resize_bypassed])
}
#[cfg(target_os = "android")]
pub(crate) fn adopt(capacity:usize,packet_len:usize,offset:usize,size:usize,
                    incremental:usize,cache_allowed:bool,take:impl FnOnce()->Option<Vec<u8>>)->Option<i64> {
    let mut a=arena().lock().ok()?;
    a.set_atlas_idle_limit(if cache_allowed {incremental} else {0});
    a.adopt(capacity,packet_len,offset,size,incremental,take)
}
pub(crate) fn output_buffer(size:usize,ceiling:usize)->Option<Vec<u8>> {
    if size==0 || size>ceiling {return None;}
    #[cfg(target_os = "android")]
    { arena().lock().ok()?.take_output(size,ceiling) }
    #[cfg(not(target_os = "android"))]
    { let mut b=Vec::new();b.try_reserve_exact(size).ok()?;if b.capacity()>ceiling{return None;}b.resize(size,0);Some(b) }
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
        arena.set_atlas_idle_limit(2048);
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
    fn adoption_rejections_count_once_and_preserve_live_ownership() {
        let mut a=Arena::default();
        let keep=a.allocate(32).unwrap();
        a.live.get_mut(&keep).unwrap().fill(0x5a);
        assert!(a.mark_viewed(keep));
        let mut output=a.take_output(1024,2048).unwrap();
        output[16..].fill(0x63);
        let cap=output.capacity();
        let ptr=output.as_ptr();
        let before=a.snapshot();
        // Neither a budget denial nor malformed/overflowing range may
        // invoke the destructive transfer callback.
        assert!(a.adopt(cap,1024,16,1008,cap-1,||panic!("budget denial consumed output")).is_none());
        assert_eq!(a.adopt_rejected,1);
        assert!(a.adopt(cap,1024,usize::MAX,1008,cap,||panic!("invalid range consumed output")).is_none());
        assert_eq!(a.adopt_rejected,2);
        // A stale engine slot can fail after admission; count that once too.
        assert!(a.adopt(cap,1024,16,1008,cap,||None).is_none());
        assert_eq!(a.adopt_rejected,3);
        assert_eq!(a.snapshot(),before);
        assert_eq!(a.adopted,0);
        assert_eq!(a.adopted_bytes,0);
        assert_eq!(a.live[&keep].as_slice(), &[0x5a;32]);
        let id=a.adopt(cap,1024,16,1008,cap,||Some(output)).unwrap();
        assert_eq!(a.adopt_rejected,3);
        assert_eq!(a.adopted,1);
        assert_eq!(a.adopted_bytes,1008);
        assert_eq!(a.charged,32+cap);
        assert_eq!(a.live[&id].as_ptr(),ptr);
        assert_eq!(&a.live[&id][16..], &[0x63;1008]);
        assert_eq!(a.live[&keep].as_slice(), &[0x5a;32]);
        assert!(a.release(id));
        assert!(a.release(keep));
        assert_eq!(a.charged,0);
        // Diagnostic counters must saturate, not wrap at their limit.
        a.adopt_rejected=u64::MAX;
        assert!(a.adopt(0,0,0,0,0,||panic!("empty transfer")).is_none());
        assert_eq!(a.adopt_rejected,u64::MAX);
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
    fn pressure_retired_last_lease_preserves_new_atlas_idle_permit() {
        let mut a=Arena::default();
        let old=a.allocate(32).unwrap();
        a.live.get_mut(&old).unwrap().fill(0x5a);
        assert!(a.mark_viewed(old));
        a.trim_pressure();
        a.set_atlas_idle_limit(4096);
        let b=a.take_output(2048,4096).unwrap();
        let cap=b.capacity();
        let ptr=b.as_ptr();
        let id=a.adopt(cap,2048,16,2032,MAX_BYTES,||Some(b)).unwrap();
        assert!(a.release(id));
        assert_eq!(a.live[&old].as_slice(), &[0x5a;32]);
        assert!(a.release(old));
        assert!(a.live.is_empty() && a.free.is_empty());
        assert_eq!(a.charged,cap);
        let next=a.take_output(2048,4096).unwrap();
        assert_eq!(next.as_ptr(),ptr);
        assert_eq!(a.charged,0);
        assert!(next.iter().all(|&v|v==0));
        a.trim_pressure();
        assert_eq!(a.charged,0);
    }

    #[test]
    fn measured_atlas_size_class_reuses_nearby_growth_within_original_cap() {
        let mib=1024*1024;
        let mut a=Arena::default();
        let ceiling=10*mib;
        a.set_atlas_idle_limit(ceiling);
        let size=8*mib+64*1024;
        let b=a.take_output(size,ceiling).unwrap();
        let cap=b.capacity(); let ptr=b.as_ptr();
        assert_eq!(cap,8*mib+512*1024);
        let id=a.adopt(cap,size,0,size,ceiling,||Some(b)).unwrap();
        assert!(a.release(id));
        let next=a.take_output(size+128*1024,ceiling).unwrap();
        assert_eq!(next.as_ptr(),ptr);
        assert_eq!(a.owned_reuses,1);
        assert_eq!(atlas_reservation(size,size,size),size);
        assert_eq!(atlas_reservation(size,ceiling,size),size);
        a.trim_pressure();
    }

    #[test]
    fn observed_large_atlas_backing_reuses_and_retires() {
        let mut a=Arena::default();
        let mib=1024*1024;
        a.set_atlas_idle_limit(10*mib);
        let keep=a.allocate(1).unwrap();
        let packet=9*mib;
        let b=a.take_output(packet,10*mib).unwrap();
        let cap=b.capacity();
        let ptr=b.as_ptr();
        let id=a.adopt(cap,packet,16,packet-16,MAX_BYTES,||Some(b)).unwrap();
        assert!(a.release(id));
        assert_eq!(a.snapshot()[0],(1+cap) as u64);
        assert_eq!(a.atlas_idle.as_ref().unwrap().as_ptr(),ptr);
        let mut next=a.take_output(packet-256*1024,10*mib).unwrap();
        assert_eq!(next.as_ptr(),ptr);
        assert!(next.iter().all(|&v|v==0));
        next[16]=0x61;
        let len=next.len();
        let cap=next.capacity();
        let id=a.adopt(cap,len,16,len-16,MAX_BYTES,||Some(next)).unwrap();
        assert!(a.mark_viewed(id));
        a.trim_pressure();
        assert_eq!(a.live[&id][16],0x61);
        a.set_atlas_idle_limit(10*mib);
        assert!(a.release(id));
        assert!(a.atlas_idle.is_none());
        assert!(a.release(keep));
        assert_eq!(a.charged,0);
        assert_eq!(a.owned_allocations,1);
        assert_eq!(a.owned_reuses,1);
    }
    #[test]
    fn small_atlas_growth_is_bounded_wiped_and_attributed() {
        let mut a=Arena::default();
        let ceiling=10*1024*1024;
        a.set_atlas_idle_limit(ceiling);
        let mut b=a.take_output(8*1024*1024,ceiling).unwrap();
        b.fill(0x5a);
        let cap=b.capacity(); let len=b.len();
        let id=a.adopt(cap,len,0,len,ceiling,||Some(b)).unwrap();
        assert!(a.release(id));
        let next=a.take_output(cap+64*1024,ceiling).unwrap();
        assert!(next.iter().all(|&x|x==0));
        assert!(next.capacity()<=ceiling);
        assert_eq!(a.atlas_fit_small,1);
        assert_eq!(a.resize_attempts,1);
        assert_eq!(a.resize_same_address+a.resize_moved+a.resize_failed,1);
        assert_eq!(a.owned_allocations,2);
        assert_eq!(a.snapshot()[0],0);
    }

    #[test]
    fn repeated_moved_growth_bypasses_copy_but_keeps_exact_fit_and_reader_safety() {
        let mut a=Arena::default();
        let ceiling=10*1024*1024;
        a.set_atlas_idle_limit(ceiling);
        // Deterministic observations, independent of the host allocator.
        for _ in 0..MAX_UNHELPFUL_ATLAS_GROWTHS-1 {a.note_atlas_growth(false);}
        a.note_atlas_growth(true);
        assert_eq!(a.resize_unhelpful_streak,0);
        for _ in 0..MAX_UNHELPFUL_ATLAS_GROWTHS {a.note_atlas_growth(false);}
        let len=8*1024*1024;
        let mut pinned=a.take_output(len,ceiling).unwrap();
        pinned.fill(0x61);
        let cap=pinned.capacity();
        let held=a.adopt(cap,len,16,len-16,MAX_BYTES,||Some(pinned)).unwrap();
        assert!(a.mark_viewed(held));
        let mut idle=a.take_output(len,ceiling).unwrap();
        idle.fill(0x5a);
        let cap=idle.capacity();
        let id=a.adopt(cap,len,16,len-16,MAX_BYTES,||Some(idle)).unwrap();
        assert!(a.release(id));
        let requested=cap+64*1024;
        let fresh=a.take_output(requested,ceiling).unwrap();
        assert_eq!(fresh.len(),requested);
        assert!(fresh.iter().all(|&v|v==0));
        assert!(fresh.capacity()<=ceiling);
        assert_eq!(a.resize_attempts,0);
        assert_eq!(a.resize_bypassed,1);
        assert_eq!(a.snapshot()[0],a.live[&held].capacity() as u64);
        assert_eq!(a.live[&held][16],0x61);
        let cap=fresh.capacity();
        let id=a.adopt(cap,requested,16,requested-16,MAX_BYTES,||Some(fresh)).unwrap();
        assert!(a.release(id));
        let ptr=a.atlas_idle.as_ref().unwrap().as_ptr();
        let again=a.take_output(requested,ceiling).unwrap();
        assert_eq!(ptr,again.as_ptr());
        assert_eq!(a.owned_reuses,1);
        assert_eq!(a.resize_bypassed,1);
        assert!(a.release(held));
        a.trim_pressure();
        assert_eq!(a.snapshot()[0],0);
    }

    #[test]
    fn reuse_miss_counters_classify_generic_fit_and_worker_ceiling_once() {
        for (requested,ceiling,expected_fit,expected_ceiling) in [
            (3900,4000,0,1),(2048,4096,1,0)
        ] {
            let mut a=Arena::default();
            a.set_atlas_idle_limit(8192);
            let sentinel=a.allocate(1).unwrap();
            let cached=a.allocate(4096).unwrap();
            assert!(a.release(cached));
            assert_eq!(a.free.len(),1);
            let output=a.take_output(requested,ceiling).unwrap();
            assert_eq!(output.len(),requested);
            assert_eq!(a.owned_allocations,1);
            assert_eq!(a.owned_eligible,0);
            assert_eq!(a.owned_reuses,0);
            assert_eq!(a.owned_miss_fit,expected_fit);
            assert_eq!(a.owned_miss_ceiling,expected_ceiling);
            assert_eq!(a.owned_miss_empty+a.owned_miss_pinned+a.owned_miss_disabled,0);
            assert!(a.release(sentinel));
            assert_eq!(a.charged,0);
        }
    }
    #[test]
    fn atlas_reuse_1000_serial_adoptions_and_exact_accounting() {
        let mut a=Arena::default();
        a.set_atlas_idle_limit(4096);
        let keep=a.allocate(1).unwrap();
        for _ in 0..1000 {
            let b=a.take_output(2048,4096).unwrap();
            let cap=b.capacity();
            let id=a.adopt(cap,2048,16,2032,MAX_BYTES,||Some(b)).unwrap();
            assert_eq!(a.charged,1+cap);
            assert!(a.release(id));
            assert_eq!(a.charged,1+cap);
        }
        assert_eq!(a.owned_allocations,1);
        assert_eq!(a.owned_reuses,999);
        assert_eq!(a.owned_eligible,999);
        assert!(a.release(keep));
        assert_eq!(a.charged,2048);
        a.trim_pressure();
        assert_eq!(a.charged,0);
    }
    #[test]
    fn active_engine_reuses_final_reader_backing_without_sentinel() {
        let mut a=Arena::default();
        let mib=1024*1024;
        a.set_atlas_idle_limit(10*mib);
        for size in [8_577_000usize,8_011_000,9_846_000,9_400_000] {
            let packet=size+1024;
            let b=a.take_output(packet,10*mib).unwrap();
            let cap=b.capacity();
            let id=a.adopt(cap,packet,1024,size,MAX_BYTES,||Some(b)).unwrap();
            assert!(a.release(id));
            assert_eq!(a.charged,cap);
            assert!(a.live.is_empty());
        }
        assert_eq!(a.owned_reuses,2);
        assert_eq!(a.owned_eligible,2);
        assert_eq!(a.owned_allocations,2);
        a.trim_pressure();
        assert_eq!(a.charged,0);
        assert_eq!(a.atlas_idle_limit,0);
    }
    #[test]
    fn generic_churn_does_not_evict_admitted_atlas_until_pressure() {
        let mut a=Arena::default();
        let packet=8*1024*1024;
        a.set_atlas_idle_limit(10*1024*1024);
        let b=a.take_output(packet,10*1024*1024).unwrap();
        let ptr=b.as_ptr();
        let cap=b.capacity();
        let id=a.adopt(cap,packet,16,packet-16,MAX_BYTES,||Some(b)).unwrap();
        assert!(a.release(id));
        // Audio/init/other delivery fragment allocations must not destroy
        // separately admitted atlas reuse while both owners fit the cap.
        for size in [4096,8192,65536,32768] {
            let other=a.allocate_headroom(size,size).unwrap();
            assert_eq!(a.atlas_idle.as_ref().unwrap().as_ptr(),ptr);
            assert!(a.charged<=cap+size);
            assert!(a.release(other));
            assert_eq!(a.charged,cap);
        }
        let reused=a.take_output(packet,10*1024*1024).unwrap();
        assert_eq!(reused.as_ptr(),ptr);
        let id=a.adopt(cap,packet,16,packet-16,MAX_BYTES,||Some(reused)).unwrap();
        assert!(a.release(id));
        // A real tighter combined bound must still reclaim idle atlas storage.
        let other=a.allocate_bounded(4096,4096).unwrap();
        assert!(a.atlas_idle.is_none());
        assert_eq!(a.charged,4096);
        assert!(a.release(other));
        assert_eq!(a.charged,0);
        assert_eq!(a.owned_reuses,1);
    }

    #[test]
    fn observed_size_reuse_with_two_pinned_readers_and_pressure_1000_cycles() {
        let mut a=Arena::default();
        let ceiling=10*1024*1024;
        let sizes=[8_011_000usize,8_577_000,9_846_000];
        let mut readers=std::collections::VecDeque::new();
        a.set_atlas_idle_limit(ceiling);
        for cycle in 0..1000 {
            if cycle%100==0 {
                // Old readers remain immutable; their releases cannot
                // recreate backing retired at this pressure watermark.
                a.trim_pressure();
                a.set_atlas_idle_limit(ceiling);
            }
            let len=sizes[(cycle/4)%sizes.len()]+1024;
            let expected=a.atlas_idle.as_ref()
                .filter(|v|v.capacity()<=ceiling && reusable_capacity(len,v.capacity()))
                .map(|v|v.as_ptr());
            let mut b=a.take_output(len,ceiling).unwrap();
            if let Some(ptr)=expected { assert_eq!(b.as_ptr(),ptr); }
            assert_eq!((b[0],b[1024],b[len-1]),(0,0,0));
            let value=(cycle%251+1) as u8;
            b[0]=value;
            b[1024]=value;
            b[len-1]=value;
            let cap=b.capacity();
            assert!(a.charged+cap<=MAX_BYTES);
            let id=a.adopt(cap,len,1024,len-1024,MAX_BYTES,||Some(b)).unwrap();
            assert!(a.mark_viewed(id));
            readers.push_back((id,len,value));
            for &(held,n,pattern) in &readers {
                assert_eq!((a.live[&held][0],a.live[&held][1024],a.live[&held][n-1]),
                    (pattern,pattern,pattern));
            }
            if readers.len()>2 {
                let (retired,_,_)=readers.pop_front().unwrap();
                assert!(a.release(retired));
            }
            assert_eq!(a.live.len(),readers.len());
            assert!(a.charged<=MAX_BYTES);
        }
        assert_eq!(a.adopted,1000);
        assert!(a.owned_reuses>0);
        assert_eq!(a.owned_eligible,a.owned_reuses);
        assert!(a.stale_releases>0);
        for (id,_,_) in readers {assert!(a.release(id));}
        a.trim_pressure();
        assert_eq!(a.charged,0);
        assert!(a.live.is_empty() && a.atlas_idle.is_none());
    }

    #[test]
    fn build_901_output_sizes_keep_pinned_readers_immutable_and_capacity_bounded() {
        // Sanitized size-only fixture from observed clear native fragments.
        let sizes=[9094863usize,8479970,8383367,8415481,8753698,8868748,8562506,
            8405957,8461691,8375390,8480302,8322116,9089322,9280169,8694034,
            9500298,8036384,8075126,8562678,8686738,8919003];
        let mut a=Arena::default();
        let ceiling=16*1024*1024;
        a.set_atlas_idle_limit(ceiling);
        let mut held=std::collections::VecDeque::new();
        for (i,size) in sizes.into_iter().enumerate() {
            let len=size+1024;
            let mut b=a.take_output(len,ceiling).unwrap();
            assert!(b.iter().all(|&v|v==0));
            let pattern=(i+1) as u8;
            b[1024]=pattern;
            let cap=b.capacity();
            assert!(a.charged+cap<=MAX_BYTES);
            let id=a.adopt(cap,len,1024,size,MAX_BYTES,||Some(b)).unwrap();
            assert!(a.mark_viewed(id));
            held.push_back((id,pattern));
            if held.len()>2 {
                let (old,value)=held.pop_front().unwrap();
                assert_eq!(a.live[&old][1024],value);
                assert!(a.release(old));
            }
            for &(reader,value) in &held {assert_eq!(a.live[&reader][1024],value);}
        }
        assert_eq!(a.adopted,21);
        assert_eq!(a.owned_allocations+a.owned_reuses,21);
        assert!(a.owned_reuses>0);
        assert_eq!(a.owned_eligible,a.owned_reuses);
        assert!(a.peak_charged<=40*1024*1024);
        for (id,_) in held {assert!(a.release(id));}
        a.trim_pressure();
        assert_eq!(a.charged,0);
    }

    #[test]
    fn build_886_fragment_sizes_replay_with_bounded_capacity() {
        let sizes=[7895615,6988629,6861401,6582662,6434449,6215677,6932098,6932098,
            6577768,7313173,6215467,7076904,7895615,6988629,6861401,6582662,6434449,
            6857997,6665906,6415811,6056382,6601462,6640173,6951403,6837369,6357568,
            6380200,6424259,6613333,7439555,6507094,7220002,6436775,7137153];
        let mut a=Arena::default();
        a.set_atlas_idle_limit(16*1024*1024);
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
        assert!(a.charged<=16*1024*1024);
        a.trim_pressure();
        assert_eq!(a.charged,0);
    }
}
