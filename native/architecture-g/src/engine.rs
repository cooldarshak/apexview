//! G-A bounded native execution foundation. Only the already validated clear
//! Annex-B conversion kernel is dispatched in this phase. No player or DRM calls.
use crate::convert_fixed_batch;
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
const MAX_BUFFER:usize=8*1024*1024;
const MAX_BUDGET:usize=32*1024*1024;
const WORKER_STACK:usize=512*1024;
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
    pub fn reserved(self)->Option<usize> {
        if !(2..=8).contains(&self.slots) ||
            !(1..=MAX_BUFFER).contains(&self.input) ||
            !(1..=MAX_BUFFER).contains(&self.output) ||
            self.ceiling>MAX_BUDGET {return None;}
        let bytes=(self.input.checked_add(self.output)?).checked_mul(self.slots+1)?
            .checked_add(WORKER_STACK+OVERHEAD_RESERVE)?;
        (bytes<=self.ceiling).then_some(bytes)
    }
}
struct Buffers { input:Vec<u8>,output:Vec<u8> }
fn buffer(capacity:usize)->Option<Vec<u8>> {
    let mut out=Vec::new();
    out.try_reserve_exact(capacity).ok()?;
    out.resize(capacity,0);
    Some(out)
}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
enum Stage { Free,Queued,Running,Ready,Reading,Failed }
#[derive(Clone,Copy)]
struct Work {
    token:i64,generation:i64,len:usize,prefix:usize,count:usize,
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
}
impl Engine {
    pub fn new(budget:Budget)->Option<Self> {
        budget.reserved()?;
        let mut slots=Vec::new();
        slots.try_reserve_exact(budget.slots).ok()?;
        for _ in 0..budget.slots {
            slots.push(Slot{buffers:Some(Buffers{
                input:buffer(budget.input)?,output:buffer(budget.output)?}),
                stage:Stage::Free,work:None,result:0});
        }
        let shared=Arc::new((Mutex::new(State{slots,generation:1,closed:false}),Condvar::new()));
        let cloned=Arc::clone(&shared);
        let worker=thread::Builder::new().name("pitwall-native-stitch".into())
            .stack_size(WORKER_STACK).spawn(move || run_worker(cloned)).ok()?;
        Some(Self{shared,worker:Some(worker),budget})
    }
    pub fn generation(&self)->i64 {
        let state=self.shared.0.lock().expect("engine mutex");
        if state.closed {CLOSED} else {state.generation}
    }
    pub fn reserved(&self)->usize {self.budget.reserved().expect("validated budget")}
    /// Caller derives deadline from media buffer slack. Speculative work
    /// cannot consume the final free slot; foreground wins among queued work.
    pub fn submit(&self,generation:i64,source:&[u8],prefix:usize,count:usize,
                  foreground:bool,slack_ms:u64)->i64 {
        if source.is_empty() || source.len()>self.budget.input ||
            !(1..=4).contains(&prefix) || !(1..=64).contains(&count) ||
            !source.len().is_multiple_of(count) || !(1..=60_000).contains(&slack_ms) {
            return INVALID;
        }
        let Some(deadline)=Instant::now().checked_add(Duration::from_millis(slack_ms)) else {return INVALID;};
        let mut s=self.shared.0.lock().expect("engine mutex");
        if s.closed {return CLOSED;}
        if generation!=s.generation {return STALE;}
        let free=s.slots.iter().filter(|slot|slot.stage==Stage::Free).count();
        if free==0 || (!foreground && free<2) {return BACKPRESSURE;}
        let Some(index)=s.slots.iter().position(|slot|slot.stage==Stage::Free) else {return BACKPRESSURE;};
        // Tokens never repeat across engines, including on 32-bit targets.
        let Ok(raw)=NEXT_TOKEN.fetch_update(Ordering::Relaxed,Ordering::Relaxed,
            |v|v.checked_add(1).filter(|next|i64::try_from(*next).is_ok())) else {return INVALID;};
        let token=i64::try_from(raw).expect("checked token");
        let slot=&mut s.slots[index];
        slot.buffers.as_mut().expect("free buffers").input[..source.len()].copy_from_slice(source);
        slot.work=Some(Work{token,generation,len:source.len(),prefix,count,foreground,deadline});
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
        let s=self.shared.0.lock().expect("engine mutex");
        if s.closed{return CLOSED;}
        let Some(i)=s.index(token) else {return STALE;};
        let slot=&s.slots[i];
        if slot.work.expect("active slot").generation!=s.generation{return STALE;}
        if slot.stage!=Stage::Reading || destination.is_empty(){return INVALID;}
        let len=slot.result as usize;
        if offset>len || destination.len()>len-offset{return INVALID;}
        destination.copy_from_slice(&slot.buffers.as_ref().expect("read buffers").output[offset..offset+destination.len()]);
        destination.len() as i64
    }
    pub fn release(&self,token:i64)->i64 {
        let mut s=self.shared.0.lock().expect("engine mutex");
        if s.closed{return CLOSED;}
        let Some(i)=s.index(token) else {return STALE;};
        let slot=&mut s.slots[i];
        if !matches!(slot.stage,Stage::Reading|Stage::Ready|Stage::Failed) {return INVALID;}
        slot.recycle();
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
    loop {
        let (index,work,mut buffers)={
            let mut s=shared.0.lock().expect("engine mutex");
            loop {
                if s.closed{return;}
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
            convert_fixed_batch(&buffers.input[..work.len],work.len/work.count,
                work.count,work.prefix,&mut buffers.output)
        }));
        let mut s=shared.0.lock().expect("engine mutex");
        let obsolete=s.closed || work.generation!=s.generation;
        let slot=&mut s.slots[index];
        slot.buffers=Some(buffers);
        if obsolete {slot.recycle();continue;}
        slot.result=if Instant::now()>=work.deadline {DEADLINE} else {
            match result {Ok(Some(n))=>n as i64,Ok(None)=>INVALID,Err(_)=>PANIC}
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
            work:Some(Work{token,generation:1,len:3,prefix:1,count:1,foreground,
                deadline:now+Duration::from_millis(ms)}),result:0};
        let mut s=State{slots:vec![mk(1,false,1),mk(2,true,40),mk(3,true,20),mk(4,true,20)],generation:1,closed:false};
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
            slot.work=Some(Work{token:42,generation:1,len:3,prefix:1,count:1,
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
