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
    // Runtime proof belongs to the actual archive producer, not a global
    // capability flag. Merely requesting Rust never reports it as active.
    fun resolve(requested: Boolean, path: Path, clearPublished: Boolean = false,
                fallbackReason: String? = null): Decision {
        if (!requested) return Decision(false, Route.JVM, "USER_DISABLED")
        if (path != Path.VERIFIED_CLEAR) return Decision(true, Route.JVM, "PATH_NOT_NATIVE_VALIDATED")
        if (fallbackReason != null) return Decision(true, Route.JVM, fallbackReason)
        return if (clearPublished) Decision(true, Route.RUST, "VALIDATED_CLEAR")
            else Decision(true, Route.JVM, "CLEAR_RUNTIME_VERIFICATION_PENDING")
    }
    fun requiresRestart(previous: Decision, next: Decision) = previous.actual != next.actual
}
