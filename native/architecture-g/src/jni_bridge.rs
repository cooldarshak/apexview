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
use crate::engine::{Budget,Engine,BACKPRESSURE,CLOSED,STALE};
use jni::sys::{jboolean,jlong};
use std::sync::{Mutex,OnceLock};
struct Registry {serial:i64,active:Option<(i64,Engine)>}
static ENGINES:OnceLock<Mutex<Registry>>=OnceLock::new();
fn registry()->&'static Mutex<Registry> {
    ENGINES.get_or_init(||Mutex::new(Registry{serial:0,active:None}))
}
fn guarded(f:impl FnOnce()->i64)->jlong {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(i64::from(PANIC))
}
fn engine_call(handle:jlong,f:impl FnOnce(&Engine)->i64)->i64 {
    let r=registry().lock().expect("engine registry");
    match r.active.as_ref() {
        Some((id,engine)) if *id==handle=>f(engine),
        _=>STALE,
    }
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeAbi(
    _env:JNIEnv<'_>,_class:JClass<'_>)->jint {1}
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
        let Some(engine)=Engine::new(budget) else {return BACKPRESSURE;};
        r.serial=id;r.active=Some((id,engine));id
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeSubmit(
    env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,generation:jlong,input:JByteBuffer<'_>,
    length:jint,prefix:jint,count:jint,foreground:jboolean,slack:jlong)->jlong {
    guarded(||{
        let (Ok(n),Ok(p),Ok(c),Ok(ms))=(usize::try_from(length),usize::try_from(prefix),
            usize::try_from(count),u64::try_from(slack)) else {return i64::from(INVALID);};
        if n==0 || n>MAX_BYTES || env.get_direct_buffer_capacity(&input).ok().is_none_or(|cap|n>cap){
            return i64::from(INVALID);
        }
        let Ok(ptr)=env.get_direct_buffer_address(&input) else {return i64::from(INVALID);};
        if ptr.is_null(){return i64::from(INVALID);}
        // SAFETY: Java's private input view is stable under its session monitor;
        // JNI local reference retains it. Engine copies into an owned native slot
        // synchronously; no address or local reference enters the worker queue.
        let bytes=unsafe{slice::from_raw_parts(ptr.cast_const(),n)};
        engine_call(handle,|e|e.submit(generation,bytes,p,c,foreground!=0,ms))
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
        // SAFETY: a private, writable Java direct buffer, synchronized with close
        // and other operations; the local reference keeps it live for this copy.
        let dst=unsafe{slice::from_raw_parts_mut(ptr,n)};
        engine_call(handle,|e|e.copy(token,offset,dst))
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
        if !r.active.as_ref().is_some_and(|(id,_)|*id==handle){return CLOSED;}
        // Keep admission closed until old worker joined and memory freed.
        let (_,mut engine)=r.active.take().expect("active engine");
        if engine.close(){1}else{i64::from(PANIC)}
    })
}
