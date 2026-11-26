package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class NativeDiagnosticReadinessTest {
    @Test fun kotlinDiagnosticsNeverLoadNativeLibrary() {
        // Local JVM has no Android native library, so accidental eager loading
        // would fail this test instead of being hidden by an Android fallback.
        assertFalse(NativeStitchingEngine.isLibraryReady())
        assertNull(NativeStitchingEngine.engineMemorySnapshot())
        assertNull(NativeStitchingEngine.fragmentMemorySnapshot())
        NativeStitchingEngine.trimFragments()
        assertFalse(NativeStitchingEngine.isLibraryReady())
    }
}
