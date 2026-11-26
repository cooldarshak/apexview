package app.apexview.scene

/** Independent platform retirement steps on the resource's owning looper.
 * A failing vendor release must not skip later independent owners. */
internal class SceneReleaseSteps(private val onFailure: (String, Exception) -> Unit) {
    fun run(name: String, release: () -> Unit) {
        try {
            release()
        } catch (error: Exception) {
            onFailure(name, error)
        }
    }
}
