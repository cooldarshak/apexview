package app.apexview.cast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.apexview.cast.local.FcastConnection
import kotlinx.coroutines.Job

internal data class DirectCastRoute(val tileId: Int, val contentId: Long, val channel: Int, val label: String)

/** Main-thread owner of Cast media routes, connection and asynchronous producers.
 * A replacement keeps the previous media route until actual receiver rendering.
 * The AppState facade still owns the UI and playback decisions, not resource lifetimes. */
internal class CastResourceCoordinator {
    var archive by mutableStateOf<SceneCastRelay?>(null)
    var baked by mutableStateOf<BakedCastLiveSession?>(null)
    private var bakedProducer: AutoCloseable? = null
    fun installBakedProducer(session: BakedCastLiveSession, producer: AutoCloseable) {
        check(baked === session && bakedProducer == null)
        bakedProducer = producer
    }
    fun stopBakedProducer() {
        val previous = bakedProducer
        bakedProducer = null
        closeOwner(previous)
    }
    var native by mutableStateOf<NativeComposedCastSession?>(null)
    var direct by mutableStateOf<DirectCastRoute?>(null)
    var preparingNative: NativeComposedCastSession? = null
    var connection: FcastConnection? = null
    var connectionJob: Job? = null
    var preparationJob: Job? = null
    var sceneJob: Job? = null
    var nativePump: Job? = null

    private val retiredArchive = mutableListOf<SceneCastRelay>()
    private val retiredNative = mutableListOf<NativeComposedCastSession>()
    private val retiredBaked = mutableListOf<BakedCastLiveSession>()
    val active get() = archive != null || baked != null || native != null || direct != null
    val hasRetiredMedia get() = retiredArchive.isNotEmpty() || retiredNative.isNotEmpty() || retiredBaked.isNotEmpty()

    /** A second replacement before confirmation would accumulate unbounded media. */
    fun ensureReplacementCapacity() {
        check(!hasRetiredMedia) { "Previous TV media transition has not been confirmed" }
    }

    fun retireArchive(except: SceneCastRelay? = null) {
        archive?.takeIf { it !== except }?.let { retiredArchive += it }
        archive = null
    }

    fun retireBaked() {
        stopBakedProducer()
        baked?.let { retiredBaked += it }; baked = null
    }

    fun retireNative() {
        nativePump?.cancel(); nativePump = null
        native?.let { retiredNative += it }
        native = null
    }

    private fun closeOwner(owner: AutoCloseable?) {
        try { owner?.close() }
        catch (failure: Exception) { android.util.Log.e("PitwallCast", "CAST_OWNER_RELEASE_FAILED type=${failure.javaClass.simpleName}") }
    }

    fun confirmRendering() {
        val oldArchive = retiredArchive.toList(); retiredArchive.clear()
        val oldNative = retiredNative.toList(); retiredNative.clear()
        val oldBaked = retiredBaked.toList(); retiredBaked.clear()
        oldBaked.forEach(::closeOwner)
        oldArchive.forEach(::closeOwner)
        oldNative.forEach(::closeOwner)
    }

    fun retainedCompositionGroups(): Set<String> = buildSet {
        native?.let { add(it.compositionGroup) }
        preparingNative?.let { add(it.compositionGroup) }
        retiredNative.forEach { add(it.compositionGroup) }
    }

    fun releaseMedia() {
        preparationJob?.cancel(); preparationJob = null
        sceneJob?.cancel(); sceneJob = null
        nativePump?.cancel(); nativePump = null
        stopBakedProducer()
        val oldBaked = baked; baked = null
        val oldNative = native; native = null
        val oldArchive = archive; archive = null
        direct = null
        closeOwner(oldBaked)
        closeOwner(oldNative)
        closeOwner(oldArchive)
        confirmRendering()
    }

    fun closeConnection() {
        connectionJob?.cancel(); connectionJob = null
        val previous = connection; connection = null
        closeOwner(previous)
    }
}
