package app.apexview.tiled

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.view.Surface
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Isolated single-player entry point for the output of [HevcClearCmafGridComposer].
 *
 * This deliberately accepts clear CMAF only. It owns one ExoPlayer, one video renderer and one
 * caller-supplied output Surface. It does not create source-feed players or DRM sessions.
 *
 * Protected F1 composition remains disabled until an authorised Widevine packaging/session model
 * has been proven. Do not pass encrypted media to this class.
 */
internal class HevcComposedMediaPlayer private constructor(
    val player: ExoPlayer,
    val diagnostics: Diagnostics,
    val outputSurfaceCount: Int
) : AutoCloseable {
    data class Diagnostics(
        @Volatile var decoderInstancesCreated: Int = 0,
        @Volatile var decoderName: String? = null,
        @Volatile var hardwareAccelerated: Boolean? = null,
        @Volatile var outputWidth: Int = 0,
        @Volatile var outputHeight: Int = 0,
        @Volatile var firstFrameRendered: Boolean = false,
        @Volatile var renderedFirstFrameCount: Int = 0,
        @Volatile var droppedFrames: Int = 0,
        @Volatile var playbackError: String? = null
    )

    override fun close() {
        player.clearVideoSurface()
        player.release()
    }

    companion object {
        private const val TAG = "ApexviewComposed"

        /**
         * Create a Media3 player for one already-composed clear CMAF output.
         *
         * The output bytes are held only for this playback session. Each Media3 data-source open
         * receives a fresh ByteArrayDataSource over the same immutable byte array.
         */
        fun create(
            context: Context,
            output: HevcClearCmafGridComposer.Output,
            surface: Surface
        ): HevcComposedMediaPlayer {
            require(output.initializationSegment.isNotEmpty()) { "Composed CMAF init segment is empty" }
            require(output.mediaFragments.isNotEmpty()) { "Composed CMAF has no media fragments" }
            val bytes = output.initializationSegment + output.mediaFragments.fold(ByteArray(0)) { acc, next ->
                acc + next
            }
            return createFromClearCmaf(context, bytes, surface)
        }

        /** Instrumentation and offline validation entry point for a complete clear CMAF file. */
        fun createFromClearCmaf(
            context: Context,
            cmafBytes: ByteArray,
            surface: Surface
        ): HevcComposedMediaPlayer {
            require(cmafBytes.isNotEmpty()) { "Composed CMAF input is empty" }
            val report = CmafBoxInspector.inspect(cmafBytes)
            require(report.errors.isEmpty()) {
                "Composed CMAF is invalid: ${report.errors.joinToString("; ")}"
            }
            require(report.tracks.size == 1) { "Composed playback requires exactly one video track" }
            val track = report.tracks.single()
            require(track.handler == "vide" && track.sampleEntry in setOf("hvc1", "hev1")) {
                "Composed playback requires a single HEVC video track"
            }
            require(!track.encrypted && track.protectionScheme == null && track.trackEncryption == null &&
                !track.sampleEncryptionBoxPresent && !track.sampleAuxiliarySizesPresent &&
                !track.sampleAuxiliaryOffsetsPresent && !track.sampleGroupDescriptionPresent &&
                !track.sampleToGroupPresent) {
                "Protected CMAF is not supported by the experimental clear composed player"
            }
            require(report.fragments.isNotEmpty() && report.fragments.all { fragment ->
                fragment.trackId == track.trackId && fragment.timingComplete &&
                    !fragment.sampleEncryptionBoxPresent && !fragment.sampleAuxiliarySizesPresent &&
                    !fragment.sampleAuxiliaryOffsetsPresent && !fragment.sampleGroupDescriptionPresent &&
                    !fragment.sampleToGroupPresent && !fragment.sampleGroupEncryptionPresent
            }) { "Composed CMAF fragments are incomplete, mismatched or protected" }

            val diagnostics = Diagnostics()
            val dataSourceFactory = DataSource.Factory {
                ByteArrayDataSource(cmafBytes)
            }
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            val player = ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
            player.setVideoSurface(surface)
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long
                ) {
                    diagnostics.decoderInstancesCreated += 1
                    diagnostics.decoderName = decoderName
                    diagnostics.hardwareAccelerated = MediaCodecList(MediaCodecList.ALL_CODECS)
                        .codecInfos.firstOrNull { it.name == decoderName }
                        ?.isHardwareAccelerated
                    Log.i(
                        TAG,
                        "decoder=$decoderName hardware=${diagnostics.hardwareAccelerated} " +
                            "instances=${diagnostics.decoderInstancesCreated}"
                    )
                }

                override fun onRenderedFirstFrame(
                    eventTime: AnalyticsListener.EventTime,
                    output: Any,
                    renderTimeMs: Long
                ) {
                    diagnostics.firstFrameRendered = true
                    diagnostics.renderedFirstFrameCount += 1
                }

                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long
                ) {
                    diagnostics.droppedFrames += droppedFrames
                }
            })
            player.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    diagnostics.outputWidth = videoSize.width
                    diagnostics.outputHeight = videoSize.height
                }

                override fun onPlayerError(error: PlaybackException) {
                    diagnostics.playbackError = "${error.errorCodeName}: ${error.message}"
                    Log.e(TAG, "Composed clear CMAF playback failed", error)
                }
            })
            val item = MediaItem.Builder()
                .setUri(Uri.parse("apexview-composed://clear/grid.mp4"))
                .setMimeType(MimeTypes.VIDEO_MP4)
                .build()
            player.setMediaItem(item)
            player.playWhenReady = true
            player.prepare()
            return HevcComposedMediaPlayer(player, diagnostics, outputSurfaceCount = 1)
        }
    }
}
