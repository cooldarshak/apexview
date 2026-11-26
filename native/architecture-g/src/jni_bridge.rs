//! Audited synchronous JNI boundary for the debug-only G.0 diagnostic.
//! Java owns the two private direct buffers. Never retain raw addresses.
use crate::{annex_b::MAX_BYTES, convert_fixed_batch};
use jni::{objects::{JByteBuffer,JClass},sys::jint,JNIEnv};
use std::{panic::{catch_unwind,AssertUnwindSafe},slice};

// Android API 23+ (our minimum is 29). Keep platform FFI inside the
// existing audited unsafe boundary, not the safe stitching engine.
#[link(name = "android")]
unsafe extern "C" {
    fn ATrace_isEnabled() -> bool;
    fn ATrace_beginSection(name: *const std::ffi::c_char);
    fn ATrace_endSection();
}

/// Thread-bound RAII trace section. No JNI object, media data, or raw
/// pointer is retained. The Rc marker prevents moving the guard to a
/// different worker or sharing it across threads.
pub(crate) struct NativeTrace {
    active: bool,
    _thread_bound: std::marker::PhantomData<std::rc::Rc<()>>,
}
impl NativeTrace {
    pub(crate) fn begin(name: &'static std::ffi::CStr) -> Self {
        // SAFETY: these NDK functions are available on every supported
        // Android API level. CStr provides a valid NUL-terminated name
        // with static lifetime. Begin/end execute on the same thread.
        let active = unsafe { ATrace_isEnabled() };
        if active { unsafe { ATrace_beginSection(name.as_ptr()) }; }
        Self { active, _thread_bound: std::marker::PhantomData }
    }
}
impl Drop for NativeTrace {
    fn drop(&mut self) {
        // SAFETY: this guard cannot cross threads and only ends a section
        // that was begun. Normal returns and unwinding both close the scope.
        if self.active { unsafe { ATrace_endSection() }; }
    }
}

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
    input:Option<BridgeBuffer>,
    output:Option<BridgeBuffer>,
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
    open_session(slots,input,output,ceiling,false)
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeOpenAtlas(
    _env:JNIEnv<'_>,_class:JClass<'_>,slots:jint,input:jint,output:jint,ceiling:jlong)->jlong {
    open_session(slots,input,output,ceiling,true)
}
fn open_session(slots:jint,input:jint,output:jint,ceiling:jlong,compact:bool)->jlong {
    guarded(||{
        let (Ok(slots),Ok(input),Ok(output),Ok(ceiling))=(usize::try_from(slots),usize::try_from(input),
            usize::try_from(output),usize::try_from(ceiling)) else {return i64::from(INVALID);};
        let budget=Budget{slots,input,output,ceiling};
        let reserved=if compact {budget.reserved_atlas()} else {budget.reserved()};
        if reserved.is_none(){return i64::from(INVALID);}
        let mut r=registry().lock().expect("engine registry");
        if r.active.is_some(){return BACKPRESSURE;}
        // Do not retain idle fragment backing while admitting a new atlas.
        // Live immutable leases remain untouched.
        crate::fragment_storage::trim();
        let Some(id)=r.serial.checked_add(1) else {return i64::from(INVALID);};
        let (input,output)=if compact {(None,None)} else {
            let Some(input)=BridgeBuffer::new(input) else {return BACKPRESSURE;};
            let Some(output)=BridgeBuffer::new(output) else {return BACKPRESSURE;};
            (Some(input),Some(output))
        };
        let Some(engine)=(if compact {Engine::new_atlas(budget)} else {Engine::new(budget)}) else {return BACKPRESSURE;};
        r.serial=id;r.active=Some(Session{id,engine,input,output});id
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeBridge(
    mut env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,output:jboolean)->jobject {
    catch_unwind(AssertUnwindSafe(||{
        let r=registry().lock().expect("engine registry");
        let Some(session)=r.active.as_ref().filter(|s|s.id==handle) else {return std::ptr::null_mut();};
        let Some(bridge)=(if output!=0{session.output.as_ref()}else{session.input.as_ref()}) else {return std::ptr::null_mut();};
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
    env: &JNIEnv<'_>, handle: jlong, input: &JByteBuffer<'_>, length: jint, limit: usize,
    submit: impl FnOnce(&Engine, &[u8]) -> jlong,
) -> jlong {
    let Ok(n) = usize::try_from(length) else { return i64::from(INVALID); };
    if n == 0 || n > limit || env.get_direct_buffer_capacity(input).ok().is_none_or(|cap| n > cap) {
        return i64::from(INVALID);
    }
    let Ok(ptr) = env.get_direct_buffer_address(input) else { return i64::from(INVALID); };
    if ptr.is_null() { return i64::from(INVALID); }
    session_call(handle, |session| {
        let Some(bridge)=session.input.as_ref() else {return i64::from(INVALID);};
        if ptr != bridge.address() || n > bridge.capacity() { return i64::from(INVALID); }
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
        submit_from_bridge(&env, handle, &input, length, MAX_BYTES,
            |engine, bytes| engine.submit(generation, bytes, p, c, foreground != 0, ms))
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeSubmitClearAtlas(
    env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,generation:jlong,input:JByteBuffer<'_>,
    length:jint,foreground:jboolean,slack:jlong)->jlong {
    guarded(||{
        let Ok(ms) = u64::try_from(slack) else { return i64::from(INVALID); };
        submit_from_bridge(&env, handle, &input, length, crate::bmff::MAX_ATLAS_BYTES,
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
        if n==0 || n>crate::bmff::MAX_ATLAS_BYTES || env.get_direct_buffer_capacity(&output).ok().is_none_or(|cap|n>cap){
            return i64::from(INVALID);
        }
        if !matches!(env.call_method(&output,"isReadOnly","()Z",&[]).and_then(|v|v.z()),Ok(false)){
            return i64::from(INVALID);
        }
        let Ok(ptr)=env.get_direct_buffer_address(&output) else {return i64::from(INVALID);};
        if ptr.is_null(){return i64::from(INVALID);}
        session_call(handle,|session|{
            let Some(bridge)=session.output.as_ref() else {return i64::from(INVALID);};
            if ptr!=bridge.address() || n>bridge.capacity(){return i64::from(INVALID);}
            // SAFETY: exact live private writable output allocation. Java
            // monitor and registry lock exclude other access and destruction.
            let dst=unsafe{slice::from_raw_parts_mut(ptr,n)};
            session.engine.copy(token,offset,dst)
        })
    })
}
// No pinned Java arrays, no raw pointer retained, and no aggregate heap
// packet or extra bridge buffer. JNI copies each source region directly into
// the exclusive unpublished native input slot during this synchronous call.
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeSubmitAtlasParts(
    mut env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,generation:jlong,
    parts:jni::objects::JObjectArray<'_>,length:jint,foreground:jboolean,slack:jlong)->jlong {
    submit_atlas_parts(&mut env,handle,generation,parts,length,foreground,slack,false)
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeSubmitAtlasOwnedParts(
    mut env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,generation:jlong,
    parts:jni::objects::JObjectArray<'_>,length:jint,foreground:jboolean,slack:jlong)->jlong {
    submit_atlas_parts(&mut env,handle,generation,parts,length,foreground,slack,true)
}
#[allow(clippy::too_many_arguments)]
fn submit_atlas_parts(env:&mut JNIEnv<'_>,handle:jlong,generation:jlong,parts:jni::objects::JObjectArray<'_>,
                      length:jint,foreground:jboolean,slack:jlong,owned:bool)->jlong {
    guarded(||{
        let (Ok(len),Ok(ms))=(usize::try_from(length),u64::try_from(slack)) else {return i64::from(INVALID);};
        if len==0 || len>crate::bmff::MAX_ATLAS_BYTES{return i64::from(INVALID);}
        let Ok(count)=env.get_array_length(&parts) else {return i64::from(INVALID);};
        if !(1..=256).contains(&count){return i64::from(INVALID);}
        engine_call(handle,|engine|{
            let fill=|dst:&mut [u8]|{
            let mut offset=0usize;
            for index in 0..count {
                let Ok(obj)=env.get_object_array_element(&parts,index) else {return false;};
                let array=jni::objects::JByteArray::from(obj);
                if array.is_null(){return false;}
                let Ok(n)=env.get_array_length(&array) else {return false;};
                let Ok(n)=usize::try_from(n) else {return false;};
                let Some(end)=offset.checked_add(n) else {return false;};
                if n==0 || end>dst.len(){return false;}
                // SAFETY: i8 and u8 have identical byte layout and alignment.
                // The native slot is exclusively borrowed until this call returns.
                let target=unsafe{slice::from_raw_parts_mut(dst[offset..end].as_mut_ptr().cast::<i8>(),n)};
                let ok=env.get_byte_array_region(&array,0,target).is_ok();
                let released=env.delete_local_ref(array).is_ok();
                if !ok || !released{return false;}
                offset=end;
            }
            offset==dst.len()
            };
            if owned {engine.submit_clear_atlas_owned_with(generation,len,foreground!=0,ms,fill)}
            else {engine.submit_clear_atlas_with(generation,len,foreground!=0,ms,fill)}
        })
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeAdoptAtlasFragment(
    _env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,token:jlong,offset:jint,length:jint,incremental:jlong)->jlong {
    guarded(|| {
        let (Ok(offset),Ok(size),Ok(incremental))=(usize::try_from(offset),usize::try_from(length),usize::try_from(incremental))
            else {return i64::from(INVALID);};
        session_call(handle,|s|{
            let Some((capacity,packet_len))=s.engine.owned_output_dimensions(token,offset,size)
                else {return BACKPRESSURE;};
            let bridge=s.input.as_ref().map_or(0,BridgeBuffer::capacity)+s.output.as_ref().map_or(0,BridgeBuffer::capacity);
            let spare=incremental.saturating_sub(s.engine.future_commitment(bridge));
            // Moving the buffer adds no resident allocation NOW, but reserve
            // enough incremental headroom for the next bounded output job.
            crate::fragment_storage::adopt(capacity,packet_len,offset,size,spare,
                ||s.engine.take_owned_output(token,capacity,packet_len)).unwrap_or(BACKPRESSURE)
        })
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeReadAtlasArray(
    env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,token:jlong,offset:jint,
    target:jni::objects::JByteArray<'_>,target_offset:jint,length:jint)->jlong {
    guarded(||{
        let (Ok(offset),Ok(start),Ok(n))=(usize::try_from(offset),usize::try_from(target_offset),
            usize::try_from(length)) else {return i64::from(INVALID);};
        if n==0 || n>crate::bmff::MAX_ATLAS_BYTES{return i64::from(INVALID);}
        let Ok(cap)=env.get_array_length(&target) else {return i64::from(INVALID);};
        let Ok(cap)=usize::try_from(cap) else {return i64::from(INVALID);};
        if start>cap || n>cap-start{return i64::from(INVALID);}
        engine_call(handle,|engine|engine.read_with(token,offset,n,|src|{
            // SAFETY: signed byte reinterpretation, borrowed read lease remains
            // locked for the complete synchronous JNI region copy.
            let signed=unsafe{slice::from_raw_parts(src.as_ptr().cast::<i8>(),src.len())};
            env.set_byte_array_region(&target,target_offset,signed).is_ok()
        }))
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeReadAtlasFragment(
    _env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,token:jlong,offset:jint,id:jlong,length:jint)->jlong {
    guarded(|| {
        let (Ok(offset),Ok(n))=(usize::try_from(offset),usize::try_from(length)) else {return i64::from(INVALID);};
        if n==0 || n>crate::bmff::MAX_ATLAS_BYTES {return i64::from(INVALID);}
        engine_call(handle,|engine| {
            // JVM owns the not-yet-published fragment exclusively. Registry,
            // then arena, then engine slot: no inverse lock acquisition.
            crate::fragment_storage::write_unpublished(id,n,|dst|engine.copy(token,offset,dst))
                .unwrap_or(i64::from(INVALID))
        })
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeRelease(
    _env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong,token:jlong)->jlong {
    guarded(||engine_call(handle,|e|e.release(token)))
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeAllocatedPayloadBytes(
    _env:JNIEnv<'_>,_class:JClass<'_>,handle:jlong)->jlong {
    guarded(||engine_call(handle,|e|e.allocated_payload_bytes() as i64))
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

// Synchronous bounded metadata-only call. No direct buffer or ciphertext,
// no native engine registry/arena, no retained JNI references.
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeProtectedHeader(
    env: JNIEnv<'_>, _class: JClass<'_>, packet: jni::objects::JByteArray<'_>,
) -> jni::sys::jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let len = usize::try_from(env.get_array_length(&packet).ok()?).ok()?;
        if len == 0 || len > crate::protected_header::MAX_PACKET {
            return None;
        }
        let mut input = env.convert_byte_array(&packet).ok()?;
        let generated = crate::protected_header::write(&input);
        input.fill(0);
        let mut output = generated.ok()?;
        let result = env.byte_array_from_slice(&output).ok().map(|value| value.into_raw());
        output.fill(0);
        result
    }));
    result.ok().flatten().unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeProtectedPlan(
    env: JNIEnv<'_>, _class: JClass<'_>, packet: jni::objects::JByteArray<'_>, kind: jint,
) -> jni::sys::jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let len = usize::try_from(env.get_array_length(&packet).ok()?).ok()?;
        if len == 0 || len > crate::protected_plan::MAX_PACKET {
            return None;
        }
        let mut input = env.convert_byte_array(&packet).ok()?;
        let generated = match kind {
            1 => crate::protected_plan::slice_prefix(&input),
            2 => crate::protected_plan::sample_map(&input),
            3 => crate::protected_plan::slice_batch(&input),
            4 => crate::protected_plan::output_parameters(&input),
            _ => Err(crate::bmff::Error::Unsupported),
        };
        input.fill(0);
        let mut output = generated.ok()?;
        let result = env.byte_array_from_slice(&output).ok().map(|value| value.into_raw());
        output.fill(0);
        result
    }));
    result.ok().flatten().unwrap_or(std::ptr::null_mut())
}

// Private generic fragment memory. Java serializes lifetime of each direct
// view and never exposes it to Media3 or other callers; only bounded reads
// are permitted after publication. The handle is not an address.
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeFragmentAllocate(
    _env: JNIEnv<'_>, _class: JClass<'_>, size: jint,
) -> jlong {
    guarded(|| usize::try_from(size).ok().and_then(crate::fragment_storage::allocate).unwrap_or(BACKPRESSURE))
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeFragmentAllocateBounded(
    _env: JNIEnv<'_>, _class: JClass<'_>, size: jint, limit:jlong,
) -> jlong {
    guarded(|| {
        let (Ok(size),Ok(limit))=(usize::try_from(size),usize::try_from(limit)) else {return i64::from(INVALID);};
        crate::fragment_storage::allocate_bounded(size,limit).unwrap_or(BACKPRESSURE)
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeFragmentAllocateHeadroom(
    _env: JNIEnv<'_>, _class: JClass<'_>, size:jint, incremental:jlong,
)->jlong {
    guarded(|| {
        let (Ok(size),Ok(incremental))=(usize::try_from(size),usize::try_from(incremental)) else {return i64::from(INVALID);};
        let r=registry().lock().expect("engine registry");
        let committed=r.active.as_ref().map_or(0,|s| {
            let bridge=s.input.as_ref().map_or(0,BridgeBuffer::capacity)+s.output.as_ref().map_or(0,BridgeBuffer::capacity);
            s.engine.future_commitment(bridge)
        });
        crate::fragment_storage::allocate_headroom(size,incremental.saturating_sub(committed)).unwrap_or(BACKPRESSURE)
    })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeFragmentView(
    mut env: JNIEnv<'_>, _class: JClass<'_>, id: jlong,
) -> jobject {
    catch_unwind(AssertUnwindSafe(|| {
        let Some((ptr, len)) = crate::fragment_storage::view(id) else { return std::ptr::null_mut(); };
        // SAFETY: a private Java owner holds the handle until every view is
        // retired; the native arena does not access live allocations.
        match unsafe { env.new_direct_byte_buffer(ptr, len) } {
            Ok(buffer) => jni::objects::JObject::from(buffer).into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    })).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeFragmentRelease(
    _env: JNIEnv<'_>, _class: JClass<'_>, id: jlong,
) -> jlong {
    guarded(|| if crate::fragment_storage::release(id) { 1 } else { STALE })
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeEngineMemorySnapshot(
    env: JNIEnv<'_>, _class: JClass<'_>,
) -> jni::sys::jlongArray {
    catch_unwind(AssertUnwindSafe(|| {
        let r = registry().lock().expect("engine registry");
        let values = r.active.as_ref().map_or([0; 10], |s| {
            let bridge=s.input.as_ref().map_or(0,BridgeBuffer::capacity)+s.output.as_ref().map_or(0,BridgeBuffer::capacity);
            let stats=s.engine.payload_stats().map(|v|i64::try_from(v).unwrap_or(i64::MAX));
            [s.id,s.engine.reserved() as i64,s.engine.allocated_payload_bytes() as i64,bridge as i64,
                stats[0],stats[1],stats[2],stats[3],s.engine.future_commitment(bridge) as i64,s.engine.owned_running() as i64]
        });
        let Ok(array) = env.new_long_array(values.len() as jint) else { return std::ptr::null_mut(); };
        if env.set_long_array_region(&array, 0, &values).is_err() { return std::ptr::null_mut(); }
        array.into_raw()
    })).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeFragmentMemorySnapshot(
    env: JNIEnv<'_>, _class: JClass<'_>,
) -> jni::sys::jlongArray {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(snapshot) = crate::fragment_storage::snapshot() else { return std::ptr::null_mut(); };
        let values = snapshot.map(|v| i64::try_from(v).unwrap_or(i64::MAX));
        let Ok(array) = env.new_long_array(values.len() as jint) else { return std::ptr::null_mut(); };
        if env.set_long_array_region(&array, 0, &values).is_err() { return std::ptr::null_mut(); }
        array.into_raw()
    })).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeStitchingEngine_nativeFragmentTrim(
    _env: JNIEnv<'_>, _class: JClass<'_>,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(r) = registry().lock() {
            if let Some(s) = r.active.as_ref() { s.engine.trim_idle_payload(); }
        }
        crate::fragment_storage::trim();
        crate::protected_plan::clear_configuration_cache();
    }));
}


// G-C audio and telemetry. JNI references are scoped to one call; the
// bounded primitive arrays carry cursor state and results without mirroring
// the complete session histories. A failure never publishes partial data.
use jni::objects::{JFloatArray,JIntArray,JLongArray,JObjectArray,JByteArray};
use crate::hot_path::{self,PositionCursor,MAX_DRIVERS,MAX_HISTORY};

fn history_time(env:&JNIEnv<'_>, array:&JLongArray<'_>, at:usize)->Option<i64>{
    let mut v=[0i64;1];
    env.get_long_array_region(array,i32::try_from(at).ok()?,&mut v).ok()?;
    Some(v[0])
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeSamplePositions(
    mut env:JNIEnv<'_>,_class:JClass<'_>,times:JObjectArray<'_>,
    xs:JObjectArray<'_>,ys:JObjectArray<'_>,counts:JIntArray<'_>,
    now:jlong,state:JLongArray<'_>,xy:JFloatArray<'_>,active:JIntArray<'_>,
)->jint{
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        let n=usize::try_from(env.get_array_length(&counts).ok()?).ok()?;
        if n>MAX_DRIVERS || env.get_array_length(&times).ok()? as usize!=n ||
            env.get_array_length(&xs).ok()? as usize!=n || env.get_array_length(&ys).ok()? as usize!=n ||
            env.get_array_length(&state).ok()? as usize!=3*n ||
            env.get_array_length(&xy).ok()? as usize!=2*n ||
            env.get_array_length(&active).ok()? as usize!=n{return None;}
        let mut sizes=[0i32;MAX_DRIVERS];
        let mut cursors=[0i64;3*MAX_DRIVERS];
        let mut coords=[0f32;2*MAX_DRIVERS];
        let mut visible=[0i32;MAX_DRIVERS];
        env.get_int_array_region(&counts,0,&mut sizes[..n]).ok()?;
        env.get_long_array_region(&state,0,&mut cursors[..3*n]).ok()?;
        for i in 0..n{
            let count=usize::try_from(sizes[i]).ok()?;
            if count>MAX_HISTORY{return None;}
            let ts=JLongArray::from(env.get_object_array_element(&times,i as i32).ok()?);
            let x=JFloatArray::from(env.get_object_array_element(&xs,i as i32).ok()?);
            let y=JFloatArray::from(env.get_object_array_element(&ys,i as i32).ok()?);
            if ts.is_null() || x.is_null() || y.is_null() ||
                (env.get_array_length(&ts).ok()? as usize)<count ||
                (env.get_array_length(&x).ok()? as usize)<count ||
                (env.get_array_length(&y).ok()? as usize)<count{return None;}
            let mut cursor=PositionCursor{lower:i32::try_from(cursors[3*i]).ok()?,
                previous:cursors[3*i+1],seeks:cursors[3*i+2]};
            let sample=hot_path::position_at(count,now,&mut cursor,
                |at|history_time(&env,&ts,at),
                |at|{
                    let mut xx=[0f32;1];let mut yy=[0f32;1];
                    env.get_float_array_region(&x,at as i32,&mut xx).ok()?;
                    env.get_float_array_region(&y,at as i32,&mut yy).ok()?;
                    Some((xx[0],yy[0]))
                })?;
            if let Some((xx,yy))=sample{
                if !xx.is_finite()||!yy.is_finite(){return None;}
                visible[i]=1;coords[2*i]=xx;coords[2*i+1]=yy;
            }
            cursors[3*i]=cursor.lower as i64;cursors[3*i+1]=cursor.previous;cursors[3*i+2]=cursor.seeks;
            env.delete_local_ref(ts).ok()?;
            env.delete_local_ref(x).ok()?;
            env.delete_local_ref(y).ok()?;
        }
        env.set_long_array_region(&state,0,&cursors[..3*n]).ok()?;
        env.set_float_array_region(&xy,0,&coords[..2*n]).ok()?;
        env.set_int_array_region(&active,0,&visible[..n]).ok()?;
        Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeFloorIndices(
    mut env:JNIEnv<'_>,_class:JClass<'_>,times:JObjectArray<'_>,
    counts:JIntArray<'_>,now:jlong,indices:JIntArray<'_>,
)->jint{
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        let n=usize::try_from(env.get_array_length(&counts).ok()?).ok()?;
        if n>MAX_DRIVERS||env.get_array_length(&times).ok()? as usize!=n||
            env.get_array_length(&indices).ok()? as usize!=n{return None;}
        let mut sizes=[0i32;MAX_DRIVERS];let mut out=[-1i32;MAX_DRIVERS];
        env.get_int_array_region(&counts,0,&mut sizes[..n]).ok()?;
        for i in 0..n{
            let count=usize::try_from(sizes[i]).ok()?;
            let ts=JLongArray::from(env.get_object_array_element(&times,i as i32).ok()?);
            if ts.is_null()||(env.get_array_length(&ts).ok()? as usize)<count{return None;}
            out[i]=hot_path::floor_index(count,now,|at|history_time(&env,&ts,at))?;
            env.delete_local_ref(ts).ok()?;
        }
        env.set_int_array_region(&indices,0,&out[..n]).ok()?;Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeRowOrder(
    env:JNIEnv<'_>,_class:JClass<'_>,positions:JIntArray<'_>,count:jint,order:JIntArray<'_>,
)->jint{
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        let n=usize::try_from(count).ok()?;
        if n>MAX_DRIVERS||(env.get_array_length(&positions).ok()? as usize)<n||
            (env.get_array_length(&order).ok()? as usize)<n{return None;}
        let mut input=[0i32;MAX_DRIVERS];let mut out=[0i32;MAX_DRIVERS];
        env.get_int_array_region(&positions,0,&mut input[..n]).ok()?;
        hot_path::row_order(&input[..n],&mut out[..n])?;
        env.set_int_array_region(&order,0,&out[..n]).ok()?;Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeAdts(
    env:JNIEnv<'_>,_class:JClass<'_>,input:JByteBuffer<'_>,len:jint,
    rate:jint,channels:jint,output:JByteArray<'_>,
)->jint{
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        let size=usize::try_from(len).ok()?;
        let header=hot_path::adts_header(size,rate,channels)?;
        if env.get_direct_buffer_capacity(&input).ok()?<size||
            env.get_array_length(&output).ok()? as usize!=size+7{return None;}
        let ptr=env.get_direct_buffer_address(&input).ok()?;
        if ptr.is_null(){return None;}
        // SAFETY: synchronous private caller retains the direct buffer and
        // does not mutate it during this call. Only checked bytes are read.
        let source=unsafe{slice::from_raw_parts(ptr.cast::<i8>(),size)};
        let header=header.map(|b|b as i8);
        env.set_byte_array_region(&output,0,&header).ok()?;
        env.set_byte_array_region(&output,7,source).ok()?;Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeScaleUs(
    env:JNIEnv<'_>,_class:JClass<'_>,us:jlong,timescale:jlong,out:JLongArray<'_>,
)->jint{
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        if env.get_array_length(&out).ok()?!=1{return None;}
        env.set_long_array_region(&out,0,&[hot_path::scale_us(us,timescale)?]).ok()?;Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}


#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativePrepareGeometry(
    mut env:JNIEnv<'_>,_class:JClass<'_>,outline:JFloatArray<'_>,output:JByteBuffer<'_>,
)->jint{
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        let n=usize::try_from(env.get_array_length(&outline).ok()?).ok()?;
        if !n.is_multiple_of(2){return None;}
        let length=crate::overlay_geometry::packet_size(n/2)?;
        if env.get_direct_buffer_capacity(&output).ok()?!=length||
            env.call_method(&output,"isReadOnly","()Z",&[]).ok()?.z().ok()?{return None;}
        let ptr=env.get_direct_buffer_address(&output).ok()?;
        if ptr.is_null(){return None;}
        let mut points=Vec::new();points.try_reserve_exact(n).ok()?;points.resize(n,0f32);
        env.get_float_array_region(&outline,0,&mut points).ok()?;
        // SAFETY: private output buffer, exact checked capacity, synchronous
        // preparation before any immutable packet view becomes visible.
        let bytes=unsafe{slice::from_raw_parts_mut(ptr,length)};
        crate::overlay_geometry::prepare(&points,bytes)
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeProjectGeometry(
    env:JNIEnv<'_>,_class:JClass<'_>,packet:JByteBuffer<'_>,xy:JFloatArray<'_>,count:jint,
    fractions:JFloatArray<'_>,order:JIntArray<'_>,
)->jint{
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        let n=usize::try_from(count).ok()?;
        if n>MAX_DRIVERS||(env.get_array_length(&xy).ok()? as usize)<2*n||
            (env.get_array_length(&fractions).ok()? as usize)<n||
            (env.get_array_length(&order).ok()? as usize)<n{return None;}
        let length=env.get_direct_buffer_capacity(&packet).ok()?;
        if length>crate::overlay_geometry::packet_size(crate::overlay_geometry::MAX_VERTICES)?{return None;}
        let ptr=env.get_direct_buffer_address(&packet).ok()?;
        if ptr.is_null()||length==0{return None;}
        // SAFETY: the caller retains a private, prepared immutable direct
        // packet. No pointer or reference survives this call.
        let bytes=unsafe{slice::from_raw_parts(ptr.cast_const(),length)};
        let geometry=crate::overlay_geometry::Geometry::new(bytes)?;
        let mut coords=[0f32;2*MAX_DRIVERS];let mut out=[0f32;MAX_DRIVERS];
        let mut indices=[0i32;MAX_DRIVERS];
        env.get_float_array_region(&xy,0,&mut coords[..2*n]).ok()?;
        for i in 0..n{out[i]=geometry.progress(coords[2*i],coords[2*i+1])?;indices[i]=i as i32;}
        for i in 1..n{
            let id=indices[i];let mut j=i;
            while j>0&&out[indices[j-1] as usize]>out[id as usize]{indices[j]=indices[j-1];j-=1;}
            indices[j]=id;
        }
        env.set_float_array_region(&fractions,0,&out[..n]).ok()?;
        env.set_int_array_region(&order,0,&indices[..n]).ok()?;Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeInterpolateMotion(
    env:JNIEnv<'_>,_class:JClass<'_>,points:JFloatArray<'_>,counts:JIntArray<'_>,
    steps:jni::sys::jfloat,xy:JFloatArray<'_>,
)->jint{
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        let n=usize::try_from(env.get_array_length(&counts).ok()?).ok()?;
        if n>MAX_DRIVERS||env.get_array_length(&points).ok()? as usize!=14*n||
            env.get_array_length(&xy).ok()? as usize!=2*n{return None;}
        let mut input=[0f32;14*MAX_DRIVERS];let mut sizes=[0i32;MAX_DRIVERS];
        let mut out=[0f32;2*MAX_DRIVERS];
        env.get_float_array_region(&points,0,&mut input[..14*n]).ok()?;
        env.get_int_array_region(&counts,0,&mut sizes[..n]).ok()?;
        for i in 0..n{
            let count=usize::try_from(sizes[i]).ok()?;
            if !(2..=7).contains(&count){return None;}
            let (x,y)=hot_path::interpolate_motion(&input[14*i..14*i+2*count],steps)?;
            out[2*i]=x;out[2*i+1]=y;
        }
        env.set_float_array_region(&xy,0,&out[..2*n]).ok()?;Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}


#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativePointAt(
    env:JNIEnv<'_>,_class:JClass<'_>,packet:JByteBuffer<'_>,f:jni::sys::jfloat,out:JFloatArray<'_>,
)->jint{
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        if env.get_array_length(&out).ok()?!=4{return None;}
        let len=env.get_direct_buffer_capacity(&packet).ok()?;
        if len==0||len>crate::overlay_geometry::packet_size(crate::overlay_geometry::MAX_VERTICES)?{return None;}
        let ptr=env.get_direct_buffer_address(&packet).ok()?;
        if ptr.is_null(){return None;}
        // SAFETY: caller owns the immutable packet for this synchronous call.
        let bytes=unsafe{slice::from_raw_parts(ptr.cast_const(),len)};
        let result=crate::overlay_geometry::Geometry::new(bytes)?.point_at(f)?;
        env.set_float_array_region(&out,0,&result).ok()?;Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}


#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeFlags(
    env:JNIEnv<'_>,_class:JClass<'_>,history:JByteBuffer<'_>,now:jlong,out:JLongArray<'_>,
)->jint{
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        if env.get_array_length(&out).ok()?!=67{return None;}
        let len=env.get_direct_buffer_capacity(&history).ok()?;
        if !(40..=327720).contains(&len)||!len.is_multiple_of(8){return None;}
        let ptr=env.get_direct_buffer_address(&history).ok()?;
        if ptr.is_null()||!ptr.cast::<i64>().is_aligned(){return None;}
        // SAFETY: immutable native-order direct buffer is fully initialized
        // by the caller; capacity/alignment checked. No retained references.
        let words=unsafe{slice::from_raw_parts(ptr.cast::<i64>(),len/8)};
        let mut at=5usize;
        let mut sections=[&[][..];5];
        for (i,section) in sections.iter_mut().enumerate(){
            let n=usize::try_from(words[i]).ok()?;
            if n>8192{return None;}
            let end=at.checked_add(n)?;
            *section=words.get(at..end)?;at=end;
        }
        if at!=words.len(){return None;}
        let mut values=[0i64;67];
        hot_path::flag_picture(sections[0],sections[1],sections[2],sections[3],sections[4],now,&mut values)?;
        env.set_long_array_region(&out,0,&values).ok()?;Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeAudioAdmit(
    env:JNIEnv<'_>,_class:JClass<'_>,metadata:JLongArray<'_>,count:jint,last:jlong,
    remaining:jlong,slots:jint,
)->jlong {
    let result=catch_unwind(AssertUnwindSafe(||->Option<i64>{
        let count=usize::try_from(count).ok()?;
        let slots=usize::try_from(slots).ok()?;
        if count>2048||usize::try_from(env.get_array_length(&metadata).ok()?).ok()?<2*count{return None;}
        let mut scratch=[0i64;4096];
        env.get_long_array_region(&metadata,0,&mut scratch[..2*count]).ok()?;
        hot_path::audio_admit(&scratch[..2*count],last,remaining,slots)
    }));
    match result{Ok(Some(bytes))=>bytes,Ok(None)=>i64::from(INVALID),Err(_)=>i64::from(PANIC)}
}


#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeAudioMetadata(
    mut env:JNIEnv<'_>,_class:JClass<'_>,parts:JObjectArray<'_>,defaults:JLongArray<'_>,
    out:JLongArray<'_>,
)->jint {
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        let count=usize::try_from(env.get_array_length(&parts).ok()?).ok()?;
        if !(3..=66).contains(&count)||env.get_array_length(&defaults).ok()?!=3||
            env.get_array_length(&out).ok()?!=4{return None;}
        let mut values=[0i64;3];env.get_long_array_region(&defaults,0,&mut values).ok()?;
        let optional=|v:i64|if v<0{Some(None)}else{Some(Some(u32::try_from(v).ok()?))};
        let defaults=crate::audio_metadata::Defaults{duration:optional(values[0])?,size:optional(values[1])?,flags:optional(values[2])?};
        let mut parsed_header=None;
        let mut start=0i64;let mut end=0i64;let mut samples=0u32;
        // Only bounded metadata extents are copied, one scratch allocation
        // reused for all box payloads in this fragment.
        let mut scratch:Vec<i8>=Vec::new();
        for i in 0..count{
            let array=JByteArray::from(env.get_object_array_element(&parts,i as i32).ok()?);
            let n=usize::try_from(env.get_array_length(&array).ok()?).ok()?;
            if !(8..=2*1024*1024).contains(&n){return None;}
            scratch.try_reserve(n.saturating_sub(scratch.len())).ok()?;scratch.resize(n,0);
            env.get_byte_array_region(&array,0,&mut scratch).ok()?;
            env.delete_local_ref(array).ok()?;
            // SAFETY: i8 and u8 have identical size and alignment; immutable
            // view of initialized scratch, confined to the synchronous call.
            let bytes=unsafe{slice::from_raw_parts(scratch.as_ptr().cast::<u8>(),n)};
            match i{
                0=>{parsed_header=Some(crate::audio_metadata::header(bytes,defaults).ok()?);}
                1=>{start=crate::audio_metadata::decode_time(bytes).ok()?;end=start;}
                _=>{
                    let (number,next)=crate::audio_metadata::run(bytes,parsed_header?.defaults,end).ok()?;
                    samples=samples.checked_add(number)?;if samples>100_000{return None;}end=next;
                }
            }
        }
        let h=parsed_header?;
        if h.empty||samples==0{return None;}
        env.set_long_array_region(&out,0,&[i64::from(h.track),start,end.checked_sub(start)?,i64::from(samples)]).ok()?;
        Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}


#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeTransport(
    mut env:JNIEnv<'_>,_class:JClass<'_>,parts:JObjectArray<'_>,metadata:JLongArray<'_>,
    continuity:JIntArray<'_>,output:JByteBuffer<'_>,
)->jint {
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        let n=usize::try_from(env.get_array_length(&parts).ok()?).ok()?;
        if !(2..=2397).contains(&n)||env.get_array_length(&metadata).ok()? as usize!=3*n||
            env.get_array_length(&continuity).ok()?!=4{return None;}
        let len=env.get_direct_buffer_capacity(&output).ok()?;
        if len==0||len>64*1024*1024||env.call_method(&output,"isReadOnly","()Z",&[]).ok()?.z().ok()?{return None;}
        let ptr=env.get_direct_buffer_address(&output).ok()?;
        if ptr.is_null(){return None;}
        let mut initial=[0i32;4];env.get_int_array_region(&continuity,0,&mut initial).ok()?;
        if initial.iter().any(|v|!(0..=15).contains(v)){return None;}
        // SAFETY: unpublished private lease, checked extent. Caller will
        // expose only after successful complete writing and continuity update.
        let bytes=unsafe{slice::from_raw_parts_mut(ptr,len)};
        let mut writer=crate::ts_mux::Writer::new(bytes,initial.map(|v|v as u8))?;
        writer.tables()?;
        let mut prior_pts=-1i64;let mut previous_video=true;
        let mut cache=[0i8;32*1024];
        for i in 0..n{
            let mut meta=[0i64;3];env.get_long_array_region(&metadata,(3*i)as i32,&mut meta).ok()?;
            let video=meta[2]&1!=0;let key=meta[2]&2!=0;
            if !(0..=3).contains(&meta[2])||meta[0]<prior_pts||
                meta[0]==prior_pts&&video&&!previous_video{return None;}
            prior_pts=meta[0];previous_video=video;
            let array=JByteArray::from(env.get_object_array_element(&parts,i as i32).ok()?);
            let size=usize::try_from(meta[1]).ok()?;
            if env.get_array_length(&array).ok()? as usize!=size{return None;}
            let mut cache_start=0usize;let mut cache_end=0usize;
            writer.sample(meta[0],size,video,key,|mut offset,mut dst|{
                while !dst.is_empty(){
                    if offset<cache_start||offset>=cache_end{
                        cache_start=offset;cache_end=offset.checked_add((size.checked_sub(offset)?).min(cache.len()))?;
                        if cache_end==cache_start{return None;}
                        env.get_byte_array_region(&array,offset as i32,&mut cache[..cache_end-cache_start]).ok()?;
                    }
                    let count=dst.len().min(cache_end-offset);
                    let source=&cache[offset-cache_start..offset-cache_start+count];
                    // SAFETY: i8/u8 same extent and alignment, private initialized cache.
                    let source=unsafe{slice::from_raw_parts(source.as_ptr().cast::<u8>(),count)};
                    dst[..count].copy_from_slice(source);
                    offset+=count;dst=&mut dst[count..];
                }
                Some(())
            })?;
            env.delete_local_ref(array).ok()?;
        }
        let final_counts=writer.finish()?.map(i32::from);
        env.set_int_array_region(&continuity,0,&final_counts).ok()?;Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}

#[no_mangle]
pub extern "system" fn Java_app_apexview_tiled_NativeHotPath_nativeNormalizeSectors(
    env:JNIEnv<'_>,_class:JClass<'_>,input:JIntArray<'_>,counts:JIntArray<'_>,output:JIntArray<'_>,
)->jint {
    let result=catch_unwind(AssertUnwindSafe(||->Option<()>{
        if env.get_array_length(&input).ok()?!=192||env.get_array_length(&counts).ok()?!=6||
            env.get_array_length(&output).ok()?!=192{return None;}
        let mut values=[0i32;192];let mut sizes=[0i32;6];let mut out=[0i32;192];
        env.get_int_array_region(&input,0,&mut values).ok()?;
        env.get_int_array_region(&counts,0,&mut sizes).ok()?;
        hot_path::normalize_sectors(&values,&sizes,&mut out)?;
        env.set_int_array_region(&output,0,&out).ok()?;Some(())
    }));
    match result{Ok(Some(()))=>1,Ok(None)=>INVALID,Err(_)=>PANIC}
}
