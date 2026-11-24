package app.apexview.tiled

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import java.io.EOFException
import java.io.IOException

/**
 * Isolated one-player playback path for a composed clear HEVC CMAF grid.
 *
 * The Tizen implementation publishes one DASH presentation for the composed grid. This Android
 * port does the same through Media3 DashMediaSource and an in-memory resource DataSource. There
 * is one ExoPlayer, one video track, and no per-source FeedPlayer instances in this path.
 *
 * This adapter intentionally rejects protected CMAF. Do not attach F1 DRM configuration or use
 * it for encrypted source feeds until the protected-sample composer and Widevine packaging path
 * are independently implemented and validated.
 *
 * Construct and control this object on the player's application Looper (normally the main thread).
 * Attach the single output Surface before calling prepare().
 */
@OptIn(UnstableApi::class)
internal class HevcComposedPlayback(
    context: Context,
    initializationSegment: ByteArray,
    mediaFragments: List<ByteArray>
) : AutoCloseable {
    private val dashPackage = HevcComposedDashPackageBuilder.build(
        initializationSegment,
        mediaFragments
    )

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    init {
        val dataSourceFactory = DataSource.Factory {
            ComposedResourceDataSource(dashPackage.resources)
        }
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(MANIFEST_URI))
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()
        val mediaSource = DashMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)
        player.setMediaSource(mediaSource)
    }

    constructor(
        context: Context,
        output: HevcClearCmafGridComposer.Output
    ) : this(context, output.initializationSegment, output.mediaFragments)

    val outputWidth: Int get() = dashPackage.width
    val outputHeight: Int get() = dashPackage.height
    val sampleCount: Int get() = dashPackage.sampleCount
    val fragmentCount: Int get() = dashPackage.fragmentCount

    fun setVideoSurface(surface: Surface) {
        player.setVideoSurface(surface)
    }

    fun prepare(playWhenReady: Boolean = true) {
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    private val releaseGate = app.apexview.PlaybackReleaseGate()

    fun release() {
        if (releaseGate.beginRelease()) player.release()
    }

    override fun close() = release()

    private class ComposedResourceDataSource(
        private val resources: Map<String, ByteArray>
    ) : DataSource {
        private var currentBytes: ByteArray? = null
        private var currentPosition = 0
        private var endPosition = 0
        private var currentUri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        @Throws(IOException::class)
        override fun open(dataSpec: DataSpec): Long {
            val name = dataSpec.uri.lastPathSegment
                ?: throw IOException("Composed CMAF request has no resource name")
            val bytes = resources[name]
                ?: throw IOException("Composed CMAF resource not found: $name")
            val start = dataSpec.position
            if (start < 0L || start > bytes.size.toLong()) {
                throw EOFException("Composed CMAF byte range starts outside $name")
            }
            val available = bytes.size.toLong() - start
            val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                available
            } else {
                dataSpec.length
            }
            if (requested < 0L || requested > available || requested > Int.MAX_VALUE.toLong()) {
                throw EOFException("Composed CMAF byte range exceeds $name")
            }

            currentBytes = bytes
            currentPosition = start.toInt()
            endPosition = currentPosition + requested.toInt()
            currentUri = dataSpec.uri
            return requested
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val bytes = currentBytes ?: throw IOException("Composed CMAF data source is not open")
            if (currentPosition >= endPosition) return C.RESULT_END_OF_INPUT
            val count = minOf(length, endPosition - currentPosition)
            bytes.copyInto(buffer, offset, currentPosition, currentPosition + count)
            currentPosition += count
            return count
        }

        override fun getUri(): Uri? = currentUri

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() {
            currentBytes = null
            currentPosition = 0
            endPosition = 0
            currentUri = null
        }
    }

    private companion object {
        const val MANIFEST_URI = "https://apexview.invalid/composed/manifest.mpd"
    }
}
