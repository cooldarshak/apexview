package app.apexview

/** A trial never changes codec identity, protected output, or coded HEVC data.
 * API/capability selection is independent from whether a driver actually saves
 * memory. Keep the established path when the optional feature is unavailable. */
internal object ComposedCodecBufferPolicy {
    fun eligible(api: Int, enabled: Boolean, composed: Boolean, hevc: Boolean,
                 hardware: Boolean, protected: Boolean, surface: Boolean,
                 supported: Boolean, existingHint: Boolean): Boolean =
        api >= 30 && enabled && composed && hevc && hardware && !protected &&
            surface && supported && !existingHint

    /** Media3's default sync/async factories release an unsuccessful codec
     * before throwing. Retry the unmodified configuration exactly once. */
    fun <T> create(trial: () -> T, baseline: () -> T, rejected: (Exception) -> Unit): T {
        try { return trial() }
        catch (first: Exception) {
            rejected(first)
            try { return baseline() }
            catch (second: Exception) {
                if (first !== second) second.addSuppressed(first)
                throw second
            }
        }
    }
}

/** Terminal owner retirement must continue even when one platform release fails. */
internal fun <T> retirePlaybackOwners(owners: Iterable<T>, retire: (T) -> Unit,
                                      failure: (Exception) -> Unit) {
    for (owner in owners) {
        try { retire(owner) }
        catch (error: Exception) { failure(error) }
    }
}
