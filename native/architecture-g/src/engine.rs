//! Bounded native execution for conversion controls and clear atlas media jobs.
use crate::{atlas_job, convert_fixed_batch};
use std::cell::UnsafeCell;
use std::sync::{Arc,Condvar,Mutex};
use std::sync::atomic::{AtomicUsize,Ordering};
static NEXT_TOKEN:AtomicUsize=AtomicUsize::new(1);

// Owned by the JNI registry. UnsafeCell makes external, scoped Java writes
// explicit; this type never creates a Rust reference to the external bytes.
pub struct BridgeBuffer { bytes:Vec<UnsafeCell<u8>> }
impl BridgeBuffer {
    pub fn new(capacity:usize)->Option<Self> {
        if !(1..=MAX_BUFFER).contains(&capacity){return None;}
        let mut bytes=Vec::new();
        bytes.try_reserve_exact(capacity).ok()?;
        bytes.resize_with(capacity,||UnsafeCell::new(0));
        Some(Self{bytes})
    }
    pub fn capacity(&self)->usize{self.bytes.len()}
    pub fn address(&self)->*mut u8{self.bytes.as_ptr().cast::<u8>().cast_mut()}
}
use std::thread::{self,JoinHandle};
use std::time::{Duration,Instant};

pub const INVALID:i64=-1;
pub const STALE:i64=-2;
pub const PANIC:i64=-3;
pub const BACKPRESSURE:i64=-4;
pub const DEADLINE:i64=-5;
pub const CLOSED:i64=-6;
pub const OUTPUT_CAPACITY:i64=-7;
const MAX_BUFFER:usize=crate::bmff::MAX_ATLAS_BYTES;
const MAX_BUDGET:usize=192*1024*1024;
const WORKER_STACK:usize=512*1024;

// Android platform FFI is confined to the existing audited bridge.
// Host tests retain the same scope API without linking Android libraries.
#[cfg(target_os = "android")]
pub(crate) use crate::jni_bridge::NativeTrace;
#[cfg(not(target_os = "android"))]
pub(crate) struct NativeTrace;
#[cfg(not(target_os = "android"))]
impl NativeTrace {
    pub(crate) fn begin(_name: &'static std::ffi::CStr) -> Self { Self }
}
#[cfg(not(target_os = "android"))]
impl Drop for NativeTrace {
    fn drop(&mut self) {}
}
const OVERHEAD_RESERVE:usize=64*1024;

#[derive(Clone,Copy,Debug)]
pub struct Budget {
    pub slots:usize,
    pub input:usize,
    pub output:usize,
    pub ceiling:usize,
}
impl Budget {
    /// Includes native payload buffers, two private JVM bridge buffers,
    /// worker stack reservation and conservative control overhead.
    pub fn reserved(self)->Option<usize> { self.reserved_with_bridge(true) }
    pub fn reserved_atlas(self)->Option<usize> { self.reserved_with_bridge(false) }
    fn reserved_with_bridge(self, bridge:bool)->Option<usize> {
        if !(2..=8).contains(&self.slots) ||
            !(1..=MAX_BUFFER).contains(&self.input) ||
            !(1..=MAX_BUFFER).contains(&self.output) ||
            self.ceiling>MAX_BUDGET {return None;}
        let bytes=(self.input.checked_add(self.output)?).checked_mul(self.slots+usize::from(bridge))?
            .checked_add(WORKER_STACK+OVERHEAD_RESERVE)?;
        (bytes<=self.ceiling).then_some(bytes)
    }
}
struct Buffers { input:Vec<u8>,output:Vec<u8> }
impl Buffers {
    fn new(budget:Budget)->Option<Self> {
        Some(Self{input:buffer(budget.input)?,output:buffer(budget.output)?})
    }
}
fn buffer(capacity:usize)->Option<Vec<u8>> {
    let mut out=Vec::new();
    out.try_reserve_exact(capacity).ok()?;
    out.resize(capacity,0);
    Some(out)
}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
enum Stage { Free,Queued,Running,Ready,Reading,Failed }
#[derive(Clone, Copy)]
enum Command {
    Convert { prefix: usize, count: usize },
    ClearAtlas,
}
#[derive(Clone,Copy)]
struct Work {
    token:i64,generation:i64,len:usize,command:Command,
    foreground:bool,deadline:Instant,
}
struct Slot {
    buffers:Option<Buffers>,
    stage:Stage,
    work:Option<Work>,
    result:i64,
}
impl Slot {
    fn recycle(&mut self) {
        debug_assert!(self.buffers.is_some());
        self.stage=Stage::Free;
        self.work=None;
        self.result=0;
    }
}
struct State {
    slots:Vec<Slot>,
    generation:i64,
    closed:bool,
    compact_atlas:bool,
}
impl State {
    fn index(&self, token:i64)->Option<usize> {
        if token<=0{return None;}
        self.slots.iter().position(|s|s.work.is_some_and(|w|w.token==token))
    }
    fn next(&self)->Option<usize> {
        self.slots.iter().enumerate().filter(|(_,s)|s.stage==Stage::Queued)
            .min_by_key(|(_,s)|{
                let w=s.work.expect("queued work");
                (!w.foreground,w.deadline,w.token)
            }).map(|(i,_)|i)
    }
}
type Shared=Arc<(Mutex<State>,Condvar)>;

/// Single reusable worker, deliberately conservative until target-device
/// measurements justify additional workers. No JNI references cross this boundary.
pub struct Engine {
    shared:Shared,
    worker:Option<JoinHandle<()>>,
    budget:Budget,
    reserved_bytes:usize,
}
impl Engine {
    pub fn new(budget:Budget)->Option<Self> {
        Self::new_reserved(budget, budget.reserved()?, false)
    }
    // Clear atlas JNI transfers directly to/from the owned slot, not the
    // separate G-A bridge. Keep the same queue, lease and workspace bounds.
    pub fn new_atlas(budget:Budget)->Option<Self> {
        Self::new_reserved(budget, budget.reserved_atlas()?, true)
    }
    fn new_reserved(budget:Budget,reserved_bytes:usize,compact_atlas:bool)->Option<Self> {
        let mut slots=Vec::new();
        slots.try_reserve_exact(budget.slots).ok()?;
        // The serialized production bridge normally uses one slot. Reserve
        // every logical slot in the admission ceiling, but materialize the
        // spare compact atlas payload pair only for genuine overlap.
        for index in 0..budget.slots {
            let buffers=if !compact_atlas || index==0 {Some(Buffers::new(budget)?)} else {None};
            slots.push(Slot{buffers,stage:Stage::Free,work:None,result:0});
        }
        let shared=Arc::new((Mutex::new(State{slots,generation:1,closed:false,compact_atlas}),Condvar::new()));
        let cloned=Arc::clone(&shared);
        let worker=thread::Builder::new().name("pitwall-native-stitch".into())
            .stack_size(WORKER_STACK).spawn(move || run_worker(cloned)).ok()?;
        Some(Self{shared,worker:Some(worker),budget,reserved_bytes})
    }
    pub fn generation(&self)->i64 {
        let state=self.shared.0.lock().expect("engine mutex");
        if state.closed {CLOSED} else {state.generation}
    }
    pub fn reserved(&self)->usize {self.reserved_bytes}
    /// Actual materialized payload pairs, including worker-owned Running slots.
    /// Does not include parser scratch, allocator overhead or resident pages.
    pub fn allocated_payload_bytes(&self)->usize {
        let s=self.shared.0.lock().expect("engine mutex");
        s.slots.iter().filter(|slot|slot.buffers.is_some() || slot.stage==Stage::Running).count()
            * (self.budget.input + self.budget.output)
    }
    /// Keep one warm free pair; never touch a queued, running or leased slot.
    fn trim_free_spares(state:&mut State) {
        if !state.compact_atlas {return;}
        let mut warm=false;
        for slot in &mut state.slots {
            if slot.stage==Stage::Free && slot.buffers.is_some() {
                if warm {slot.buffers=None;} else {warm=true;}
            }
        }
    }
    /// Caller derives deadline from media buffer slack. Speculative work
    /// cannot consume the final free slot; foreground wins among queued work.
    pub fn submit(&self,generation:i64,source:&[u8],prefix:usize,count:usize,
                  foreground:bool,slack_ms:u64)->i64 {
        if !(1..=4).contains(&prefix) || !(1..=64).contains(&count) ||
            source.len() > crate::annex_b::MAX_BYTES || !source.len().is_multiple_of(count) {
            return INVALID;
        }
        self.enqueue(generation, source, Command::Convert { prefix, count }, foreground, slack_ms)
    }
    /// Clear CMAF jobs require extra parser/rewrite headroom. Run with the same
    /// single worker, lease, queue priority, deadline and generation checks.
    pub fn submit_clear_atlas(&self, generation: i64, source: &[u8],
                              foreground: bool, slack_ms: u64) -> i64 {
        self.submit_clear_atlas_with(generation, source.len(), foreground, slack_ms, |dst| {
            dst.copy_from_slice(source); true
        })
    }
    pub fn submit_clear_atlas_with(&self, generation:i64, len:usize, foreground:bool,
                                    slack_ms:u64, fill:impl FnOnce(&mut [u8])->bool)->i64 {
        let remaining = self.budget.ceiling - self.reserved();
        if atlas_job::workspace_reserve(self.budget.input, self.budget.output)
            .is_none_or(|additional| additional > remaining) {
            return BACKPRESSURE;
        }
        self.enqueue_with(generation, len, Command::ClearAtlas, foreground, slack_ms, fill)
    }
    fn enqueue(&self, generation: i64, source: &[u8], command: Command,
               foreground: bool, slack_ms: u64) -> i64 {
        self.enqueue_with(generation,source.len(),command,foreground,slack_ms,|dst|{
            dst.copy_from_slice(source); true
        })
    }
    fn enqueue_with(&self,generation:i64,len:usize,command:Command,foreground:bool,
                    slack_ms:u64,fill:impl FnOnce(&mut [u8])->bool)->i64 {
        if len == 0 || len > self.budget.input || !(1..=60_000).contains(&slack_ms) {
            return INVALID;
        }
        let Some(deadline)=Instant::now().checked_add(Duration::from_millis(slack_ms)) else {return INVALID;};
        let mut s=self.shared.0.lock().expect("engine mutex");
        if s.closed {return CLOSED;}
        if generation!=s.generation {return STALE;}
        let free=s.slots.iter().filter(|slot|slot.stage==Stage::Free).count();
        if free==0 || (!foreground && free<2) {return BACKPRESSURE;}
        let Some(index)=s.slots.iter().position(|slot|slot.stage==Stage::Free && slot.buffers.is_some())
            .or_else(||s.slots.iter().position(|slot|slot.stage==Stage::Free)) else {return BACKPRESSURE;};
        // Free/unallocated is distinct from Running/temporarily owned by the
        // worker. No active slot can be selected or evicted here.
        if s.slots[index].buffers.is_none() {
            let Some(buffers)=Buffers::new(self.budget) else {return BACKPRESSURE;};
            s.slots[index].buffers=Some(buffers);
        }
        if Instant::now()>=deadline {return DEADLINE;}
        // Tokens never repeat across engines, including on 32-bit targets.
        let Ok(raw)=NEXT_TOKEN.fetch_update(Ordering::Relaxed,Ordering::Relaxed,
            |v|v.checked_add(1).filter(|next|i64::try_from(*next).is_ok())) else {return INVALID;};
        let token=i64::try_from(raw).expect("checked token");
        let slot=&mut s.slots[index];
        let destination=&mut slot.buffers.as_mut().expect("free buffers").input[..len];
        if !fill(destination) { destination.fill(0); return INVALID; }
        if Instant::now()>=deadline { destination.fill(0); return DEADLINE; }
        slot.work=Some(Work{token,generation,len,command,foreground,deadline});
        slot.stage=Stage::Queued;
        self.shared.1.notify_one();
        token
    }
    /// Pending=0; positive length grants the sole read lease. The caller must
    /// release READY/FAILED results, including stale acquired reads after seek.
    pub fn acquire(&self,token:i64)->i64 {
        let mut s=self.shared.0.lock().expect("engine mutex");
        if s.closed {return CLOSED;}
        let Some(i)=s.index(token) else {return STALE;};
        if s.slots[i].work.expect("active slot").generation!=s.generation {return STALE;}
        let slot=&mut s.slots[i];
        match slot.stage {
            Stage::Queued|Stage::Running=>0,
            Stage::Ready=>{slot.stage=Stage::Reading;slot.result},
            Stage::Failed=>slot.result,
            Stage::Reading=>INVALID,
            Stage::Free=>STALE,
        }
    }
    pub fn copy(&self,token:i64,offset:usize,destination:&mut [u8])->i64 {
        self.read_with(token,offset,destination.len(),|src|{destination.copy_from_slice(src);true})
    }
    pub fn read_with(&self,token:i64,offset:usize,count:usize,read:impl FnOnce(&[u8])->bool)->i64 {
        let s=self.shared.0.lock().expect("engine mutex");
        if s.closed{return CLOSED;}
        let Some(i)=s.index(token) else {return STALE;};
        let slot=&s.slots[i];
        if slot.work.expect("active slot").generation!=s.generation{return STALE;}
        if slot.stage!=Stage::Reading || count==0{return INVALID;}
        let len=slot.result as usize;
        if offset>len || count>len-offset{return INVALID;}
        if read(&slot.buffers.as_ref().expect("read buffers").output[offset..offset+count]) {
            count as i64
        } else { INVALID }
    }
    pub fn release(&self,token:i64)->i64 {
        let mut s=self.shared.0.lock().expect("engine mutex");
        if s.closed{return CLOSED;}
        let Some(i)=s.index(token) else {return STALE;};
        let slot=&mut s.slots[i];
        if !matches!(slot.stage,Stage::Reading|Stage::Ready|Stage::Failed) {return INVALID;}
        slot.recycle();
        Self::trim_free_spares(&mut s);
        1
    }
    /// Invalidate pending/ready old results; running work can finish only
    /// into its still-owned buffers and is discarded; reading slots stay
    /// reserved until release. No stale publication or pointer recycling.
    pub fn seek(&self)->i64 {
        let mut s=self.shared.0.lock().expect("engine mutex");
        if s.closed{return CLOSED;}
        let Some(generation)=s.generation.checked_add(1) else {return INVALID;};
        s.generation=generation;
        for slot in &mut s.slots {
            if matches!(slot.stage,Stage::Queued|Stage::Ready|Stage::Failed) {slot.recycle();}
        }
        Self::trim_free_spares(&mut s);
        self.shared.1.notify_all();
        generation
    }
    /// Join outside the state lock; caller must be on a background thread.
    /// JNI never exposes a raw native view, so no external pointer can outlive close.
    pub fn close(&mut self)->bool {
        {
            let mut s=self.shared.0.lock().expect("engine mutex");
            s.closed=true;
            self.shared.1.notify_all();
        }
        self.worker.take().is_none_or(|w|w.join().is_ok())
    }
}
impl Drop for Engine {fn drop(&mut self){self.close();}}
fn run_worker(shared:Shared) {
    let mut atlas = atlas_job::Dispatcher::default();
    loop {
        let (index,work,mut buffers)={
            let mut s=shared.0.lock().expect("engine mutex");
            loop {
                if s.closed{return;}
                atlas.invalidate(s.generation);
                if let Some(index)=s.next() {
                    let slot=&mut s.slots[index];
                    let work=slot.work.expect("queued work");
                    if Instant::now()>=work.deadline {
                        slot.stage=Stage::Failed;slot.result=DEADLINE;continue;
                    }
                    slot.stage=Stage::Running;
                    break (index,work,slot.buffers.take().expect("queued buffers"));
                }
                s=shared.1.wait(s).expect("engine condvar");
            }
        };
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{
            let input = &buffers.input[..work.len];
            match work.command {
                Command::Convert { prefix, count } =>
                    convert_fixed_batch(input, work.len / count, count, prefix, &mut buffers.output).ok_or(INVALID),
                Command::ClearAtlas => {
                    let _trace = NativeTrace::begin(c"PitwallNativeAtlas");
                    atlas.run(work.generation, input, &mut buffers.output)
                        .map_err(|error| if error == crate::bmff::Error::Capacity { OUTPUT_CAPACITY } else { INVALID })
                },
            }
        }));
        let mut s=shared.0.lock().expect("engine mutex");
        let obsolete=s.closed || work.generation!=s.generation;
        let slot=&mut s.slots[index];
        slot.buffers=Some(buffers);
        if obsolete {slot.recycle();Engine::trim_free_spares(&mut s);continue;}
        slot.result=if Instant::now()>=work.deadline {DEADLINE} else {
            match result {Ok(Ok(n))=>n as i64,Ok(Err(code))=>code,Err(_)=>PANIC}
        };
        slot.stage=if slot.result>0 {Stage::Ready} else {Stage::Failed};
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn budget()->Budget {Budget{slots:2,input:4096,output:8192,ceiling:1024*1024}}
    fn wait(engine:&Engine,token:i64)->i64 {
        let until=Instant::now()+Duration::from_secs(5);
        loop {
            let n=engine.acquire(token);
            if n!=0{return n;}
            assert!(Instant::now()<until,"worker did not finish");
            thread::yield_now();
        }
    }
    #[test]
    fn direct_atlas_slot_transfer_is_bounded_and_atomic() {
        let b=Budget{slots:2,input:4096,output:8192,ceiling:12*1024*1024};
        let mut engine=Engine::new_atlas(b).unwrap();
        assert_eq!(engine.reserved()+b.input+b.output,b.reserved().unwrap());
        let generation=engine.generation();
        assert_eq!(engine.submit_clear_atlas_with(generation,8,true,5000,|dst|{
            dst.fill(0x71);false
        }),INVALID);
        let token=engine.submit_clear_atlas_with(generation,4,true,5000,|dst|{
            dst.copy_from_slice(b"bad!");true
        });
        assert!(token>0);
        assert_eq!(engine.read_with(token,0,1,|_|panic!("unacquired output")),INVALID);
        assert!(engine.close());
    }

    #[test]
    fn compact_spare_slot_is_lazy_and_preserves_foreground_admission() {
        let b=Budget{slots:2,input:4096,output:8192,ceiling:12*1024*1024};
        let mut engine=Engine::new_atlas(b).unwrap();
        let g=engine.generation();
        assert_eq!(engine.reserved(),b.reserved_atlas().unwrap());
        {
            let s=engine.shared.0.lock().unwrap();
            assert!(s.slots[0].buffers.is_some());
            assert!(s.slots[1].buffers.is_none());
        }
        // A malformed atlas is safely rejected, but its failed result still
        // occupies the slot until release, just like a ready/read lease.
        for _ in 0..4 {
            let token=engine.submit_clear_atlas(g,b"GBC1",false,5000);
            assert!(token>0);
            assert_eq!(wait(&engine,token),INVALID);
            assert_eq!(engine.release(token),1);
            assert!(engine.shared.0.lock().unwrap().slots[1].buffers.is_none());
        }
        let first=engine.submit_clear_atlas(g,b"GBC1",false,5000);
        assert!(first>0);
        assert_eq!(wait(&engine,first),INVALID);
        assert_eq!(engine.submit_clear_atlas(g,b"GBC1",false,5000),BACKPRESSURE);
        let foreground=engine.submit_clear_atlas(g,b"GBC1",true,5000);
        assert!(foreground>first);
        assert_eq!(wait(&engine,foreground),INVALID);
        assert_eq!(engine.submit_clear_atlas(g,b"GBC1",true,5000),BACKPRESSURE);
        assert!(engine.shared.0.lock().unwrap().slots[1].buffers.is_some());
        assert_eq!(engine.release(first),1);
        assert_eq!(engine.release(foreground),1);
        assert_eq!(engine.seek(),g+1);
        assert!(engine.close());
    }

    #[test]
    fn compact_seek_trims_free_spares_but_not_active_slots() {
        let b=Budget{slots:2,input:4096,output:8192,ceiling:12*1024*1024};
        let mut engine=Engine::new_atlas(b).unwrap();
        let pair=b.input+b.output;
        assert_eq!(engine.allocated_payload_bytes(),pair);
        let g=engine.generation();
        let one=engine.submit(g,&[2,2,1],1,1,true,5000);
        assert!(one>0);
        assert_eq!(wait(&engine,one),6);
        let two=engine.submit(g,&[2,2,1],1,1,true,5000);
        assert!(two>0);
        assert_eq!(wait(&engine,two),6);
        assert_eq!(engine.allocated_payload_bytes(),2*pair);
        assert_eq!(engine.release(two),1);
        assert!(engine.seek()>g);
        assert_eq!(engine.allocated_payload_bytes(),2*pair);
        assert_eq!(engine.read_with(one,0,1,|_|panic!("stale read")),STALE);
        assert_eq!(engine.release(one),1);
        assert_eq!(engine.allocated_payload_bytes(),pair);
        assert_eq!(engine.reserved(),b.reserved_atlas().unwrap());
        assert!(engine.close());
    }

    #[test]
    fn real_four_feed_budget_is_admitted_without_weakening_workspace_checks() {
        let input = 6_300_000;
        let output = 7_800_000;
        let b = Budget { slots: 2, input, output, ceiling: 96 * 1024 * 1024 };
        let required = b.reserved().unwrap() + atlas_job::workspace_reserve(input, output).unwrap();
        assert!(required > 32 * 1024 * 1024 && required <= b.ceiling);
        let mut engine = Engine::new(b).unwrap();
        let token = engine.submit_clear_atlas(1, b"bad", true, 5000);
        assert!(token > 0);
        assert!(engine.close());
        assert!(Budget { ceiling: MAX_BUDGET + 1, ..b }.reserved().is_none());
        assert!(Budget { input: MAX_BUFFER + 1, ..b }.reserved().is_none());
    }

    #[test]
    fn clear_atlas_requires_workspace_headroom_beyond_fixed_slots() {
        let budget = Budget { slots: 2, input: 32, output: 32, ceiling: 1024 * 1024 };
        let engine = Engine::new(budget).unwrap();
        let generation = engine.generation();
        assert_eq!(engine.submit_clear_atlas(generation, b"GBC1", true, 5000), BACKPRESSURE);
        assert!(engine.submit(generation, &[2, 2, 1], 1, 1, true, 5000) > 0);
    }

    #[test]
    fn checked_budget_rejects_overflow_and_insufficient_headroom(){
        assert_eq!(budget().reserved(),Some(3*(4096+8192)+WORKER_STACK+OVERHEAD_RESERVE));
        assert!(Budget{slots:usize::MAX,..budget()}.reserved().is_none());
        assert!(Budget{input:usize::MAX,..budget()}.reserved().is_none());
        assert!(Budget{ceiling:4096,..budget()}.reserved().is_none());
        assert!(Budget{ceiling:MAX_BUDGET+1,..budget()}.reserved().is_none());
    }
    #[test]
    fn leases_backpressure_seek_and_close(){
        let mut e=Engine::new(budget()).unwrap();
        let g=e.generation();
        let a=e.submit(g,&[2,2,1],1,1,false,5000);
        assert!(a>0);
        assert_eq!(e.submit(g,&[2,2,1],1,1,false,5000),BACKPRESSURE);
        let b=e.submit(g,&[2,2,1],1,1,true,5000);
        assert!(b>a);
        assert_eq!(e.submit(g,&[2,2,1],1,1,true,5000),BACKPRESSURE);
        assert_eq!(wait(&e,a),6);
        assert_eq!(e.acquire(a),INVALID);
        let mut out=[0x55;6];
        assert_eq!(e.copy(a,0,&mut out),6);
        assert_eq!(out,[0,0,0,1,2,1]);
        assert_eq!(e.copy(a,5,&mut out),INVALID);
        assert_eq!(e.seek(),g+1);
        assert_eq!(e.copy(a,0,&mut out),STALE);
        assert_eq!(e.acquire(b),STALE);
        assert_eq!(e.submit(g,&[2,2,1],1,1,true,5000),STALE);
        assert_eq!(e.release(a),1);
        assert_eq!(e.release(a),STALE);
        assert!(e.close());assert!(e.close());
        assert_eq!(e.submit(g+1,&[2,2,1],1,1,true,5000),CLOSED);
    }
    #[test]
    fn malformed_batch_and_failed_lease_are_explicit(){
        let e=Engine::new(budget()).unwrap();
        let g=e.generation();
        let token=e.submit(g,&[2,2,1,3,2,1],1,2,true,5000);
        assert!(token>0);assert_eq!(wait(&e,token),INVALID);
        let mut out=[0x55;12];
        assert_eq!(e.copy(token,0,&mut out),INVALID);
        assert_eq!(out,[0x55;12]);
        assert_eq!(e.release(token),1);
        let valid=e.submit(g,&[2,2,1,2,2,1],1,2,true,5000);
        assert_eq!(wait(&e,valid),12);
        assert_eq!(e.copy(valid,6,&mut out[..6]),6);
        assert_eq!(&out[..6],&[0,0,0,1,2,1]);
        assert_eq!(e.release(valid),1);
    }
    #[test]
    fn deadline_priority_and_fifo_ties(){
        let now=Instant::now();
        let mk=|token,foreground,ms|Slot{buffers:None,stage:Stage::Queued,
            work:Some(Work{token,generation:1,len:3,command:Command::Convert{prefix:1,count:1},foreground,
                deadline:now+Duration::from_millis(ms)}),result:0};
        let mut s=State{slots:vec![mk(1,false,1),mk(2,true,40),mk(3,true,20),mk(4,true,20)],generation:1,closed:false,compact_atlas:false};
        assert_eq!(s.next(),Some(2));s.slots[2].stage=Stage::Running;
        assert_eq!(s.next(),Some(3));s.slots[3].stage=Stage::Running;
        assert_eq!(s.next(),Some(1));s.slots[1].stage=Stage::Running;
        assert_eq!(s.next(),Some(0));
    }
    #[test]
    fn randomized_seek_and_release_stress(){
        let e=Engine::new(budget()).unwrap();
        let mut seed=0x71f42c99u32;
        for _ in 0..200 {
            seed=seed.wrapping_mul(1664525).wrapping_add(1013904223);
            let g=e.generation();
            let token=e.submit(g,&[2,2,1],1,1,true,5000);
            if token==BACKPRESSURE {thread::yield_now();continue;}
            assert!(token>0);
            if seed&1==0 {assert_eq!(e.seek(),g+1);assert_eq!(e.acquire(token),STALE);}
            else {
                assert_eq!(wait(&e,token),6);
                let mut out=[0;6];
                if seed&2==0 {
                    assert_eq!(e.seek(),g+1);assert_eq!(e.copy(token,0,&mut out),STALE);
                }else{assert_eq!(e.copy(token,0,&mut out),6);}
                assert_eq!(e.release(token),1);
            }
        }
    }
    #[test]
    fn expired_queued_work_never_reaches_read_lease(){
        let e=Engine::new(budget()).unwrap();
        // Deterministically insert an expired job under the state lock.
        {
            let mut s=e.shared.0.lock().unwrap();
            let slot=&mut s.slots[0];
            slot.work=Some(Work{token:42,generation:1,len:3,command:Command::Convert{prefix:1,count:1},
                foreground:true,deadline:Instant::now()});
            slot.stage=Stage::Queued;
            e.shared.1.notify_one();
        }
        assert_eq!(wait(&e,42),DEADLINE);
        let mut bytes=[0x55;6];
        assert_eq!(e.copy(42,0,&mut bytes),INVALID);
        assert_eq!(bytes,[0x55;6]);
        assert_eq!(e.release(42),1);
    }
    #[test]
    fn repeated_native_bridge_and_worker_churn_releases_every_owner(){
        let mut previous=0;
        for _ in 0..64 {
            let input=BridgeBuffer::new(4096).unwrap();
            let output=BridgeBuffer::new(8192).unwrap();
            assert_eq!(input.capacity(),4096);
            assert_eq!(output.capacity(),8192);
            assert_ne!(input.address(),output.address());
            let mut e=Engine::new(budget()).unwrap();
            let token=e.submit(e.generation(),&[2,2,1],1,1,true,5000);
            assert!(token>previous);
            if previous>0 {assert_eq!(e.acquire(previous),STALE);}
            previous=token;
            assert_eq!(wait(&e,token),6);
            if token%2==0{assert_eq!(e.seek(),2);}
            // Teardown also owns abandoned read leases: no native pointer escaped.
            assert!(e.close());
        }
        assert!(BridgeBuffer::new(0).is_none());
        assert!(BridgeBuffer::new(MAX_BUFFER+1).is_none());
    }
    #[test]
    fn concurrent_seek_does_not_publish_old_generation(){
        use std::sync::Barrier;
        let e=Arc::new(Engine::new(budget()).unwrap());
        let barrier=Arc::new(Barrier::new(2));
        let g=e.generation();
        let token=e.submit(g,&[2,2,1],1,1,true,5000);
        let e2=Arc::clone(&e);let b2=Arc::clone(&barrier);
        let t=thread::spawn(move ||{b2.wait();assert_eq!(e2.acquire(token),STALE);});
        assert_eq!(e.seek(),g+1);barrier.wait();t.join().unwrap();
    }
}
