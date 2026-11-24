package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.StreamKey

/** Actual source master and selected variant, not a guessed subtitle endpoint. */
internal data class ArchiveCaptionRendition(val uri: Uri, val language: String, val label: String)

internal data class ArchiveCaptionSource(
    val masterUri: Uri,
    val streamKeys: List<StreamKey>,
    val renditions: List<ArchiveCaptionRendition>
)
