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
