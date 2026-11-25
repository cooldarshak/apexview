//! Audited synchronous JNI boundary for the debug-only G.0 diagnostic.
//! Java owns the two private direct buffers. Never retain raw addresses.
use crate::{annex_b::MAX_BYTES, convert_fixed_batch};
use jni::{objects::{JByteBuffer,JClass},sys::jint,JNIEnv};
use std::{panic::{catch_unwind,AssertUnwindSafe},slice};

const INVALID: jint = -1;
const PANIC: jint = -3;

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_ArchitectureG0Probe_nativeVersion(
    _env: JNIEnv<'_>, _class: JClass<'_>,
) -> jint {
    2
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_ArchitectureG0Probe_nativeConvert(
    mut env: JNIEnv<'_>, _class: JClass<'_>,
    input: JByteBuffer<'_>, input_len: jint, prefix: jint,
    output: JByteBuffer<'_>, output_limit: jint, batch_count: jint,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| -> Option<jint> {
        let len = usize::try_from(input_len).ok()?;
        let limit = usize::try_from(output_limit).ok()?;
        let width = usize::try_from(prefix).ok()?;
        let count = usize::try_from(batch_count).ok()?;
        if !(1..=64).contains(&count) || len % count != 0 { return None; }
        if len == 0 || limit == 0 || len > MAX_BYTES || limit > MAX_BYTES || !(1..=4).contains(&width) {
            return None;
        }
        if len > env.get_direct_buffer_capacity(&input).ok()? ||
            limit > env.get_direct_buffer_capacity(&output).ok()? {
            return None;
        }
        if env.call_method(&output, "isReadOnly", "()Z", &[]).ok()?.z().ok()? {
            return None;
        }
        let input_ptr = env.get_direct_buffer_address(&input).ok()?;
        let output_ptr = env.get_direct_buffer_address(&output).ok()?;
        if input_ptr.is_null() || output_ptr.is_null() { return None; }
        let start_in = input_ptr as usize;
        let start_out = output_ptr as usize;
        let end_in = start_in.checked_add(len)?;
        let end_out = start_out.checked_add(limit)?;
        if start_in < end_out && start_out < end_in { return None; }
        // SAFETY: the private Java caller owns two allocated direct buffers,
        // serializes all access and close, and never shares their references.
        // Local JNI refs keep them live until return. Requested sizes were
        // checked against their capacities and the two ranges cannot alias.
        // No borrowed slice, address or JNI reference leaves this scope.
        let source = unsafe { slice::from_raw_parts(input_ptr.cast_const(), len) };
        let destination = unsafe { slice::from_raw_parts_mut(output_ptr, limit) };
        jint::try_from(convert_fixed_batch(source,len / count,count,width,destination)?).ok()
    }));
    match result {
        Ok(Some(size)) => size,
        Ok(None) => INVALID,
        Err(_) => PANIC,
    }
}

// G-A session registry. One engine per process until measured headroom supports
// overlapping sessions. Handles are monotonically assigned, never raw pointers.
use crate::engine::{Budget,BridgeBuffer,Engine,BACKPRESSURE,CLOSED,STALE};
use jni::sys::{jboolean,jlong,jobject};
use std::sync::{Mutex,OnceLock};
struct Session {
    id:i64,
    engine:Engine,
    input:BridgeBuffer,
    output:BridgeBuffer,
}
struct Registry {serial:i64,active:Option<Session>}
static ENGINES:OnceLock<Mutex<Registry>>=OnceLock::new();
fn registry()->&'static Mutex<Registry> {
    ENGINES.get_or_init(||Mutex::new(Registry{serial:0,active:None}))
}
fn guarded(f:impl FnOnce()->i64)->jlong {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(i64::from(PANIC))
}
fn session_call(handle:jlong,f:impl FnOnce(&Session)->i64)->i64 {
    let r=registry().lock().expect("engine registry");
    match r.active.as_ref() {
        Some(session) if session.id==handle=>f(session),
        _=>STALE,
    }
}
fn engine_call(handle:jlong,f:impl FnOnce(&Engine)->i64)->i64 {
    session_call(handle,|session|f(&session.engine))
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeAbi(
    _env:JNIEnv<'_>,_class:JClass<'_>)->jint {2}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeOpen(
    _env:JNIEnv<'_>,_class:JClass<'_>,slots:jint,input:jint,output:jint,ceiling:jlong)->jlong {
    guarded(||{
        let (Ok(slots),Ok(input),Ok(output),Ok(ceiling))=(usize::try_from(slots),usize::try_from(input),
            usize::try_from(output),usize::try_from(ceiling)) else {return i64::from(INVALID);};
        let budget=Budget{slots,input,output,ceiling};
        if budget.reserved().is_none(){return i64::from(INVALID);}
        let mut r=registry().lock().expect("engine registry");
        if r.active.is_some(){return BACKPRESSURE;}
        let Some(id)=r.serial.checked_add(1) else {return i64::from(INVALID);};
        let Some(input)=BridgeBuffer::new(input) else {return BACKPRESSURE;};
        let Some(output)=BridgeBuffer::new(output) else {return BACKPRESSURE;};
        let Some(engine)=Engine::new(budget) else {return BACKPRESSURE;};
        r.serial=id;r.active=Some(Session{id,engine,input,output});id
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeBridge(
    mut env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,output:jboolean)->jobject {
    catch_unwind(AssertUnwindSafe(||{
        let r=registry().lock().expect("engine registry");
        let Some(session)=r.active.as_ref().filter(|s|s.id==handle) else {return std::ptr::null_mut();};
        let bridge=if output!=0{&session.output}else{&session.input};
        // SAFETY: these are nonmoving registry-owned UnsafeCell allocations.
        // Only the private Java session holds the two views; it synchronizes
        // operations and drops both references before nativeClose. The worker
        // only accesses separate owned slot buffers. No view is returned to
        // Media3 or arbitrary callers, and no Java view survives session close.
        let result=unsafe{env.new_direct_byte_buffer(bridge.address(),bridge.capacity())};
        match result {
            Ok(buffer)=>jni::objects::JObject::from(buffer).into_raw(),
            Err(_)=>std::ptr::null_mut(),
        }
    })).unwrap_or(std::ptr::null_mut())
}
fn submit_from_bridge(
    env: &JNIEnv<'_>, handle: jlong, input: &JByteBuffer<'_>, length: jint,
    submit: impl FnOnce(&Engine, &[u8]) -> jlong,
) -> jlong {
    let Ok(n) = usize::try_from(length) else { return i64::from(INVALID); };
    if n == 0 || n > MAX_BYTES || env.get_direct_buffer_capacity(input).ok().is_none_or(|cap| n > cap) {
        return i64::from(INVALID);
    }
    let Ok(ptr) = env.get_direct_buffer_address(input) else { return i64::from(INVALID); };
    if ptr.is_null() { return i64::from(INVALID); }
    session_call(handle, |session| {
        if ptr != session.input.address() || n > session.input.capacity() { return i64::from(INVALID); }
        // SAFETY: exact live registry buffer; the Java session monitor excludes
        // writes and close. The registry lock retains its backing allocation.
        let bytes = unsafe { slice::from_raw_parts(ptr.cast_const(), n) };
        submit(&session.engine, bytes)
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeSubmit(
    env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,generation:jlong,input:JByteBuffer<'_>,
    length:jint,prefix:jint,count:jint,foreground:jboolean,slack:jlong)->jlong {
    guarded(||{
        let (Ok(p),Ok(c),Ok(ms)) = (usize::try_from(prefix),usize::try_from(count),
            u64::try_from(slack)) else { return i64::from(INVALID); };
        submit_from_bridge(&env, handle, &input, length,
            |engine, bytes| engine.submit(generation, bytes, p, c, foreground != 0, ms))
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeSubmitClearAtlas(
    env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,generation:jlong,input:JByteBuffer<'_>,
    length:jint,foreground:jboolean,slack:jlong)->jlong {
    guarded(||{
        let Ok(ms) = u64::try_from(slack) else { return i64::from(INVALID); };
        submit_from_bridge(&env, handle, &input, length,
            |engine, bytes| engine.submit_clear_atlas(generation, bytes, foreground != 0, ms))
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeAcquire(
    _env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,token:jlong)->jlong {
    guarded(||engine_call(handle,|e|e.acquire(token)))
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeCopy(
    mut env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,token:jlong,offset:jint,
    output:JByteBuffer<'_>,length:jint)->jlong {
    guarded(||{
        let (Ok(offset),Ok(n))=(usize::try_from(offset),usize::try_from(length)) else {return i64::from(INVALID);};
        if n==0 || n>MAX_BYTES || env.get_direct_buffer_capacity(&output).ok().is_none_or(|cap|n>cap){
            return i64::from(INVALID);
        }
        if !matches!(env.call_method(&output,"isReadOnly","()Z",&[]).and_then(|v|v.z()),Ok(false)){
            return i64::from(INVALID);
        }
        let Ok(ptr)=env.get_direct_buffer_address(&output) else {return i64::from(INVALID);};
        if ptr.is_null(){return i64::from(INVALID);}
        session_call(handle,|session|{
            if ptr!=session.output.address() || n>session.output.capacity(){return i64::from(INVALID);}
            // SAFETY: exact live private writable output allocation. Java
            // monitor and registry lock exclude other access and destruction.
            let dst=unsafe{slice::from_raw_parts_mut(ptr,n)};
            session.engine.copy(token,offset,dst)
        })
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeRelease(
    _env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,token:jlong)->jlong {
    guarded(||engine_call(handle,|e|e.release(token)))
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeSeek(
    _env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong)->jlong {
    guarded(||engine_call(handle,Engine::seek))
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeClose(
    _env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong)->jlong {
    guarded(||{
        let mut r=registry().lock().expect("engine registry");
        if !r.active.as_ref().is_some_and(|s|s.id==handle){return CLOSED;}
        // Java has dropped private views. Join while admission remains locked,
        // then drop the slots and both native bridge allocations together.
        let mut session=r.active.take().expect("active engine");
        let joined=session.engine.close();
        drop(session);
        if joined{1}else{i64::from(PANIC)}
    })
}
