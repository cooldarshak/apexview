package app.apexview

/** Conservative first-use ceiling; no runtime changes to a saved quality or DRM choice. */
internal object DevicePlaybackDefaults {
    fun quality(saved: String?, tv: Boolean, lowRam: Boolean): VideoQuality {
        if (saved != null) return VideoQuality.fromPreference(saved)
        return when {
            tv && lowRam -> VideoQuality.P540
            tv -> VideoQuality.P720
            else -> VideoQuality.AUTO
        }
    }
}
