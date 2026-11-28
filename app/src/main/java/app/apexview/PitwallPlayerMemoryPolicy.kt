package app.apexview

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/** The only production ExoPlayer construction boundary. Each caller supplies
 * its own allocator, and an explicit role. Existing buffering time floors and
 * DRM/renderer/track configuration are intentionally preserved. The measured
 * archive policy may stop optional loading, never evict samples or override
 * a rebuffer/startup decision. */
@OptIn(UnstableApi::class)
internal object PitwallPlayerMemoryPolicy {
    enum class Role { FEED, RECEIVER, RADIO, CLEAR_COMPOSED_FIXTURE }

    fun build(
        builder: ExoPlayer.Builder,
        allocator: DefaultAllocator,
        role: Role,
        archive: ArchiveForwardBufferPolicy? = null,
    ): ExoPlayer {
        check((role == Role.FEED) == (archive != null))
        val control = DefaultLoadControl.Builder().setAllocator(allocator).apply {
            when (role) {
                Role.FEED -> setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, 1_000, 2_000)
                Role.RECEIVER -> setBufferDurationsMs(5_000, 15_000, 1_000, 2_000)
                Role.RADIO, Role.CLEAR_COMPOSED_FIXTURE -> Unit
            }
        }.build()
        val selected: LoadControl = if (archive != null)
            ArchiveMeasuredLoadControl(control, archive) { allocator.totalBytesAllocated.toLong() }
        else control
        return builder.setLoadControl(selected).build()
    }
}
