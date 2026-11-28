package app.apexview.scene

/** Independent platform retirement steps on the resource's owning looper.
 * A failing vendor release must not skip later independent owners. */
internal class SceneReleaseSteps(private val onFailure: (String, Exception) -> Unit) {
    var failedCount: Int = 0
        private set
    fun run(name: String, release: () -> Unit) {
        try {
            release()
        } catch (error: Exception) {
            failedCount++
            onFailure(name, error)
        }
    }
}
