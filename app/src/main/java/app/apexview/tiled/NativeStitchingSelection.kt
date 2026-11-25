package app.apexview.tiled

/** Explicit requested/actual routing. Never treat an archive candidate as
 * verified clear, and never silently change DRM or decoder mode. */
internal object NativeStitchingSelection {
    const val LABEL = "Native Zero-Copy On-Device Stitching using Rust"
    enum class Route { JVM, RUST }
    enum class Path { CLEAR_CANDIDATE, VERIFIED_CLEAR, PROTECTED, LIVE, CAST, NON_COMPOSED }
    data class Decision(val requested: Boolean, val actual: Route, val reason: String) {
        fun diagnostic(path: Path) = "STITCHING_ENGINE requested=${if(requested) "RUST" else "JVM"} actual=$actual path=$path reason=$reason"
    }
    // G-A has no integrated Rust media pipeline. This constant is intentionally
    // changed only by G-B with real routing and differential acceptance.
    private const val CLEAR_PIPELINE_INTEGRATED = false
    fun resolve(requested: Boolean, path: Path): Decision {
        if (!requested) return Decision(false, Route.JVM, "USER_DISABLED")
        if (path != Path.VERIFIED_CLEAR) return Decision(true, Route.JVM, "PATH_NOT_NATIVE_VALIDATED")
        return if (CLEAR_PIPELINE_INTEGRATED) Decision(true, Route.RUST, "VALIDATED_CLEAR")
            else Decision(true, Route.JVM, "NATIVE_PIPELINE_NOT_INTEGRATED")
    }
    fun requiresRestart(previous: Decision, next: Decision) = previous.actual != next.actual
}
