#!/usr/bin/env bash
# Build 843 signed APK -> current debug APK; verify packaged native libraries,
# signing identity, application identity, ABI, 16 KB ZIP alignment and symbols.
set -euo pipefail
apk="${1:?candidate APK}"
baseline="${2:?Build 843 APK}"
report="${3:?G.0 evidence directory}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
tools="$sdk/build-tools/36.0.0"
ndk="$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$report/packaged"
"$tools/apksigner" verify --print-certs "$baseline" > "$report/baseline-signature.txt"
"$tools/apksigner" verify --print-certs "$apk" > "$report/candidate-signature.txt"
grep '^Signer #[0-9]* certificate SHA-256 digest:' "$report/baseline-signature.txt" > "$report/baseline-cert-sha256.txt"
grep '^Signer #[0-9]* certificate SHA-256 digest:' "$report/candidate-signature.txt" > "$report/candidate-cert-sha256.txt"
diff -u "$report/baseline-cert-sha256.txt" "$report/candidate-cert-sha256.txt"
"$tools/aapt" dump badging "$baseline" > "$report/baseline-badging.txt"
"$tools/aapt" dump badging "$apk" > "$report/candidate-badging.txt"
"$tools/zipalign" -c -P 16 -v 4 "$apk" > "$report/apk-zipalign.txt"
python3 - "$baseline" "$apk" "$report" <<'PY'
import pathlib, re, sys, zipfile
baseline, apk, directory = sys.argv[1:]
directory = pathlib.Path(directory)
def identity(name):
    data = (directory / (name + "-badging.txt")).read_text()
    m = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", data, re.M)
    assert m, name
    return m[1], int(m[2])
old, old_version = identity("baseline")
new, new_version = identity("candidate")
assert old == new == "app.apexview_custom" and new_version > old_version, (old,new,old_version,new_version)
abis = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
with zipfile.ZipFile(baseline) as previous, zipfile.ZipFile(apk) as current:
    def libraries(z):
        return {p for p in z.namelist() if re.fullmatch(r"lib/[^/]+/[^/]+\.so", p)}
    assert libraries(previous) <= libraries(current), "Existing native library missing"
    actual = {p.split("/")[1] for p in libraries(current) if p.endswith("/libpitwall_g0.so")}
    assert actual == abis, actual
    for abi in sorted(abis):
        name = f"lib/{abi}/libpitwall_g0.so"
        dest = directory/"packaged"/abi
        dest.mkdir(parents=True, exist_ok=True)
        (dest/"libpitwall_g0.so").write_bytes(current.read(name))
print("G0 APK identity, monotonic upgrade, existing libs and four diagnostic ABIs PASS")
PY
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    full="$report/abi/$abi/libpitwall_g0.so"
    packed="$report/packaged/$abi/libpitwall_g0.so"
    "$ndk/llvm-readelf" -n "$full" > "$report/$abi-unstripped-build-id.txt"
    "$ndk/llvm-readelf" -n "$packed" > "$report/$abi-packaged-build-id.txt"
    echo "G0 checking native build ID and symbolication: $abi"
    python3 scripts/architecture_g0_build_id.py \
        "$report/$abi-unstripped-build-id.txt" "$report/$abi-packaged-build-id.txt" \
        | tee "$report/$abi-build-id.txt"
    "$ndk/llvm-nm" --defined-only --dynamic "$full" > "$report/$abi-symbols.txt"
    for symbol in nativeAbi nativeOpen nativeBridge nativeSubmit nativeSubmitClearAtlas nativeAcquire nativeCopy nativeRelease nativeSeek nativeClose nativeProtectedHeader nativeProtectedPlan nativeFragmentAllocate nativeFragmentView nativeFragmentRelease nativeFragmentTrim nativeOpenAtlas nativeSubmitAtlasParts nativeSubmitAtlasOwnedParts nativeAdoptAtlasFragment nativeReadAtlasArray nativeFragmentAllocateBounded; do
        grep -Eq "[[:space:]]Java_app_apexview_tiled_NativeStitchingEngine_$symbol$" "$report/$abi-symbols.txt"
    done
    # Verify every declared NativeHotPath JNI method, not a stale hand-written list.
    "$ndk/llvm-nm" --defined-only --dynamic "$packed" > "$report/$abi-packaged-symbols.txt"
    python3 scripts/verify_native_hot_path_symbols.py \
        app/src/main/java/app/apexview/tiled/NativeHotPath.java \
        "$report/$abi-symbols.txt" "$report/$abi-packaged-symbols.txt"
    address="$(awk '$3=="Java_app_apexview_tiled_ArchitectureG0Probe_nativeConvert"{print $1}' "$report/$abi-symbols.txt")"
    if [[ ! "$address" =~ ^[0-9a-fA-F]+$ ]]; then
        echo "G0 $abi: missing or ambiguous nativeConvert symbol" >&2
        exit 1
    fi
    # ARM32 function symbols may carry the Thumb-state bit in st_value.
    if [[ "$abi" == armeabi-v7a ]]; then
        address="$(printf '%x' "$((16#$address & ~1))")"
    fi
    "$ndk/llvm-addr2line" -f -C -e "$full" "0x$address" > "$report/$abi-symbolication.txt"
    if ! grep -F 'jni_bridge.rs:' "$report/$abi-symbolication.txt"; then
        cat "$report/$abi-symbolication.txt" >&2
        echo "G0 $abi: nativeConvert did not resolve to its Rust source" >&2
        exit 1
    fi
done
echo "G0 packaging, signature, alignment and symbolication PASS"
