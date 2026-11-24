package app.apexview.cast

import app.apexview.tiled.ProtectedHevcComposedDashPackageBuilder

/** Stages one video window and its audio/text AdaptationSets until the relay accepts it. */
internal class CastDashPublication(
    private val video: ProtectedHevcComposedDashPackageBuilder.Package,
) {
    private val adaptationSets = StringBuilder()
    private val shared = linkedMapOf<String, SharedRelayResource>()
    val reusedBytes: Long get() = shared.values.sumOf { it.size.toLong() }

    fun addResource(name: String, bytes: SharedRelayResource) {
        check(name !in video.resources && name !in shared)
        shared[name] = bytes
    }

    fun addAdaptationSet(xml: String) {
        adaptationSets.append(xml)
    }

    fun publish(relay: ComposedCmafRelay) {
        val manifest = video.manifest.replace("</Period>", "$adaptationSets</Period>").toByteArray()
        try {
            relay.publishResources(video.resources + ("manifest.mpd" to manifest), shared)
        } catch (failure: Throwable) {
            manifest.fill(0)
            throw failure
        }
        video.resources.getValue("manifest.mpd").fill(0)
    }
}
