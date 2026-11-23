package app.apexview

/**
 * One final output layer for one clear composed HEVC decoder. This does not
 * allocate a second MediaCodec, ExoPlayer, DRM session or output SurfaceTexture.
 * Preserve window PixelCopy compatibility for baked Cast and replay capture.
 */
internal enum class ClearSceneOutputMode { TEXTURE_VIEW, SURFACE_VIEW }

internal fun selectClearSceneOutput(
    experimentalSurfaceEnabled: Boolean,
    protectedRollingScene: Boolean,
    windowCaptureRequired: Boolean,
): ClearSceneOutputMode =
    if (experimentalSurfaceEnabled && !protectedRollingScene && !windowCaptureRequired)
        ClearSceneOutputMode.SURFACE_VIEW
    else
        ClearSceneOutputMode.TEXTURE_VIEW
