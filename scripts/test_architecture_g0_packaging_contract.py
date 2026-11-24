"""Ensure the G.0 debug-only probe and existing regression gates stay wired."""
import pathlib
import unittest
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
        self.assertIn('OK (3 tests)',script)
        self.assertIn("G0 signed upgrade/data retention PASS",script)

    def test_only_jni_module_can_use_unsafe(self):
        lib=(ROOT/"native/architecture-g/src/lib.rs").read_text()
        self.assertIn("#![deny(unsafe_code)]",lib)
        self.assertIn("#[allow(unsafe_code)]\nmod jni_bridge;",lib)
        annex=(ROOT/"native/architecture-g/src/annex_b.rs").read_text()
        self.assertNotIn("unsafe {",annex)

if __name__ == "__main__":
    unittest.main()
