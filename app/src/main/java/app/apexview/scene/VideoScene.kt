package app.apexview.scene

/** Logical video windows. Sources refer to the fixed encoded atlas order, never layout order. */
data class VideoWindow(val id: Int, val source: Int, val x: Float, val y: Float,
                       val width: Float, val height: Float, val z: Float)

/** Pixel-space crop of one independently encoded source in a possibly mixed-size atlas. */
data class VideoSourceRect(val x: Int, val y: Int, val width: Int, val height: Int)

data class VideoScene(val width: Float, val height: Float, val atlasWidth: Int,
                      val atlasHeight: Int, val sources: Int, val windows: List<VideoWindow>,
                      val atlasColumns: Int = sources, val sourceCodedWidth: Int = atlasWidth / atlasColumns.coerceAtLeast(1),
                      val sourceCodedHeight: Int = atlasHeight, val sourceDisplayWidth: Int = sourceCodedWidth,
                      val sourceDisplayHeight: Int = sourceCodedHeight,
                      val sourceRects: List<VideoSourceRect> = emptyList()) {
    data class Quad(val id: Int, val positions: FloatArray, val texture: FloatArray)

    /** Same geometry is used by native GLES and the cast receiver. Overlap follows tile z order. */
    fun quads(): List<Quad> {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        require(atlasWidth > 0 && atlasHeight > 0 && sources > 0)
        require(atlasColumns in 1..sources && sourceCodedWidth > 0 && sourceCodedHeight > 0 &&
            sourceDisplayWidth in 1..sourceCodedWidth && sourceDisplayHeight in 1..sourceCodedHeight)
        if (sourceRects.isNotEmpty()) {
            require(sourceRects.size == sources) {
                "Atlas has ${sourceRects.size} source rectangles for $sources feeds"
            }
            sourceRects.forEachIndexed { index, rect ->
                require(rect.x >= 0 && rect.y >= 0 && rect.width > 0 && rect.height > 0 &&
                    rect.x.toLong() + rect.width <= atlasWidth &&
                    rect.y.toLong() + rect.height <= atlasHeight) {
                    "Feed $index crop (${rect.x},${rect.y}) ${rect.width}x${rect.height} " +
                        "exceeds visible atlas ${atlasWidth}x${atlasHeight}"
                }
            }
        }
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
            val col = cell.source % atlasColumns
            val row = cell.source / atlasColumns
            val rect = sourceRects.getOrNull(cell.source) ?: VideoSourceRect(
                col * sourceCodedWidth, row * sourceCodedHeight, sourceDisplayWidth, sourceDisplayHeight)
            val u0 = rect.x.toFloat() / atlasWidth + insetX
            val u1 = (rect.x + rect.width).toFloat() / atlasWidth - insetX
            val v0 = 1f - (rect.y + rect.height).toFloat() / atlasHeight + insetY
            val v1 = 1f - rect.y.toFloat() / atlasHeight - insetY
            require(u0 < u1 && v0 < v1 && u0 >= 0f && u1 <= 1f && v0 >= 0f && v1 <= 1f)
            Quad(cell.id, floatArrayOf(left, bottom, right, bottom, left, top, right, top),
                floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1))
        }
    }
}
