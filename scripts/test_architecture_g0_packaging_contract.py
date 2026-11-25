"""Ensure the G.0 debug-only probe and existing regression gates stay wired."""
import pathlib
import unittest
from architecture_g0_build_id import build_id, verify
ROOT = pathlib.Path(__file__).resolve().parents[1]

class ArchitectureG0PackagingContract(unittest.TestCase):
    def test_debug_scope_and_abi_gate(self):
        wf=(ROOT/".github/workflows/android-debug-apk.yml").read_text()
        gradle=(ROOT/"app/build.gradle.kts").read_text()
        self.assertIn('sourceSets.getByName("debug").jniLibs.srcDir(g0JniDir)',gradle)
        self.assertIn("PITWALL_G0_JNI_LIB_DIR=$report/abi",wf)
        self.assertIn("run-id: 45441485463",wf)
        self.assertIn("scripts/verify_architecture_g0_apk.sh",wf)
        self.assertIn("cargo test --locked",wf)
        self.assertIn("cargo clippy --locked",wf)
        for gate in ("Independently decode", "Execute native RenderNode", "Execute Android 12 mutual TLS", "Execute Android 16 mutual TLS"):
            self.assertIn(gate,wf)
        script=(ROOT/"scripts/run_rendernode_instrumentation.sh").read_text()
        self.assertIn("ArchitectureG0AndroidTest",script)
        self.assertIn("ArchitectureGAAndroidTest",script)
        self.assertIn("ga-asan-lsan.txt",wf)
        self.assertIn("detect_leaks=1:halt_on_error=1",wf)
        self.assertIn("cargo +nightly-2025-11-15 test --locked --lib",wf)
        self.assertIn("pitwall-ga-api-",script)
        self.assertIn('OK (4 tests)',script)
        self.assertIn("G0 signed upgrade/data retention PASS",script)

    def test_build_id_linker_and_early_gate(self):
        build = (ROOT/"native/architecture-g/build.rs").read_text()
        wf = (ROOT/".github/workflows/android-debug-apk.yml").read_text()
        verifier = (ROOT/"scripts/verify_architecture_g0_apk.sh").read_text()
        self.assertIn("cargo:rustc-link-arg-cdylib=-Wl,--build-id=sha1", build)
        self.assertIn('Ok("android")', build)
        self.assertLess(wf.index("architecture_g0_build_id.py"), wf.index("name: Set up Gradle"))
        self.assertIn("architecture_g0_build_id.py", verifier)
        self.assertIn("llvm-addr2line", verifier)

    def test_missing_build_id_reproduces_build_844_failure(self):
        # Build 844 unstripped and packaged notes contained only Android ident.
        notes = "Displaying notes found in: .note.android.ident\n  Android  0x00000084  NT_ANDROID_TYPE_IDENT\n"
        with self.assertRaisesRegex(ValueError, "found 0"):
            verify(notes, notes)

    def test_valid_id_and_package_mismatch(self):
        identity = "ab" * 20
        notes = "Displaying notes found in: .note.gnu.build-id\n    Build ID: " + identity + "\n"
        self.assertEqual(identity, verify(notes, notes.upper().replace("BUILD ID:", "Build ID:")))
        with self.assertRaisesRegex(ValueError, "differs"):
            verify(notes, notes.replace(identity, "cd" * 20))
        with self.assertRaisesRegex(ValueError, "found 0"):
            verify(notes, "Android ident only")
        for value in ("0" * 40, "bad", "ab" * 19):
            with self.assertRaises(ValueError):
                build_id("Build ID: " + value)
        with self.assertRaisesRegex(ValueError, "found 2"):
            build_id(notes + notes)

    def test_ci_keeps_all_checks_when_reusing_tools(self):
        wf=(ROOT/".github/workflows/android-debug-apk.yml").read_text()
        for required in ("testDebugUnitTest", ":app:assembleDebugAndroidTest",
                         ":app:assembleBenchmark", ":macrobenchmark:assembleBenchmark",
                         "api-level: 29", "api-level: 31", "api-level: 36",
                         "generate_hevc_tile_fixtures.py", "generate_hevc_tile_2x2_fixtures.py",
                         "generate_hevc_fixtures.py", "verify_architecture_g0_apk.sh",
                         "cargo test --locked", "cargo clippy --locked", "feasibility_bench"):
            self.assertIn(required,wf)
        self.assertEqual(wf.count("--daemon --no-build-cache"),2)
        self.assertIn("pitwall-kvazaar-v1-",wf)
        self.assertIn("c997f08c805bd7ec82233654e417e06fe7c83cba",wf)
        self.assertIn("success() && steps.assemble_apks.outcome == 'success' && env.APK_SIGNING_READY == 'true'",wf)
        self.assertIn("PITWALL_BUILT_AT",wf)
        self.assertIn("max-parallel: 3",wf)
        self.assertIn("fail-fast: false",wf)
        self.assertIn("needs: [build, g0_emulator]",wf)
        self.assertIn("success() && needs.g0_emulator.result == 'success'",wf)
        self.assertIn("g0-encrypted-stage-",wf)
        self.assertIn("architecture_g0_stage.py unpack",wf)
        self.assertIn("cmaf_bench",wf)
        self.assertIn("cmaf_control.cpp",wf)
        self.assertIn("slice_bench",wf)
        self.assertIn("PITWALL_G0_SLICE_DIR",wf)
        self.assertIn("pitwall-cargo-ndk-v1-",wf)
        self.assertIn("cargo install cargo-ndk --version 4.1.2 --locked",wf)

    def test_workflow_has_unique_job_and_native_gate_definitions(self):
        import re
        import subprocess
        wf=(ROOT/".github/workflows/android-debug-apk.yml").read_text()
        jobs=wf.split("\njobs:\n",1)[1]
        self.assertEqual(re.findall(r"^  ([a-z0-9_]+):$", jobs, re.M),
                         ["build","g0_emulator","g0_publish"])
        self.assertEqual(wf.count("name: Architecture G.0 host kernel and Android ABI feasibility"),1)
        self.assertEqual(wf.count("cargo clippy --locked --all-targets -- -D warnings"),1)
        self.assertEqual(wf.count("target/release/slice_bench "),1)
        lines=wf.splitlines()
        for i,line in enumerate(lines):
            if line.strip() != "run: |":
                continue
            indent=len(line)-len(line.lstrip())+2
            block=[]
            for child in lines[i+1:]:
                if child.strip() and len(child)-len(child.lstrip())<indent:
                    break
                block.append(child[indent:])
            p=subprocess.run(["bash","-n"],input="\n".join(block)+"\n",
                             text=True,capture_output=True)
            self.assertEqual(p.returncode,0,f"workflow line {i+1}: {p.stderr}")

    def test_only_jni_module_can_use_unsafe(self):
        lib=(ROOT/"native/architecture-g/src/lib.rs").read_text()
        self.assertIn("#![deny(unsafe_code)]",lib)
        self.assertIn("#[allow(unsafe_code)]\nmod jni_bridge;",lib)
        annex=(ROOT/"native/architecture-g/src/annex_b.rs").read_text()
        self.assertNotIn("unsafe {",annex)

if __name__ == "__main__":
    unittest.main()
