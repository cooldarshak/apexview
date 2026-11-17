package app.apexview.scene

/** Logical video windows. Sources refer to the fixed encoded atlas order, never layout order. */
data class VideoWindow(val id: Int, val source: Int, val x: Float, val y: Float,
                       val width: Float, val height: Float, val z: Float)

data class VideoScene(val width: Float, val height: Float, val atlasWidth: Int,
                      val atlasHeight: Int, val sources: Int, val windows: List<VideoWindow>) {
    data class Quad(val id: Int, val positions: FloatArray, val texture: FloatArray)

    /** Same geometry is used by native GLES and the cast receiver. Overlap follows tile z order. */
    fun quads(): List<Quad> {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        require(atlasWidth > 0 && atlasHeight > 0 && sources > 0)
        require(windows.map { it.id }.distinct().size == windows.size)
        return windows.sortedBy { it.z }.map { cell ->
            require(cell.source in 0 until sources)
            require(listOf(cell.x, cell.y, cell.width, cell.height, cell.z).all(Float::isFinite))
            require(cell.width > 0 && cell.height > 0)
            val left = cell.x * 2 / width - 1
            val right = (cell.x + cell.width) * 2 / width - 1
            val top = 1 - cell.y * 2 / height
            val bottom = 1 - (cell.y + cell.height) * 2 / height
            // Clamp sampling inside this feed. Linear filtering must not bleed an adjacent camera.
            val insetX = 0.5f / atlasWidth
            val insetY = 0.5f / atlasHeight
            val u0 = cell.source.toFloat() / sources + insetX
            val u1 = (cell.source + 1f) / sources - insetX
            Quad(cell.id, floatArrayOf(left, bottom, right, bottom, left, top, right, top),
                floatArrayOf(u0, insetY, u1, insetY, u0, 1 - insetY, u1, 1 - insetY))
        }
    }
}
