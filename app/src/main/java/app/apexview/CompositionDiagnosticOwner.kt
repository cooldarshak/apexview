package app.apexview

/**
 * A composition attempt, not its coroutine, owns the visible playback diagnostic.
 * Invalidating the owner before cancellation prevents an obsolete finally/catch
 * from publishing CANCELLED or a historical feed count over the live session.
 * All access is on AppState's UI coroutine dispatcher.
 */
internal class CompositionDiagnosticOwner {
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null

    fun begin(): Long {
        val generation = ++nextGeneration
        activeGeneration = generation
        return generation
    }

    fun isOwner(generation: Long): Boolean = activeGeneration == generation

    fun invalidate() {
        nextGeneration++
        activeGeneration = null
    }
}
