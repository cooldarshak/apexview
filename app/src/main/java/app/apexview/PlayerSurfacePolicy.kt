package app.apexview

/**
 * A single visible L3 feed can use SurfaceView so Android composes video independently of the
 * Compose timing/map overlays. Multiple L3 feeds keep TextureView because arbitrary tile overlap
 * and z-order must be preserved. L1 always uses SurfaceView for secure output.
 */
internal fun useTextureViewForFeed(usesSoftwareDrm: Boolean, visibleFeedCount: Int): Boolean =
    usesSoftwareDrm && visibleFeedCount > 1
