package app.apexview.cast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES20
import android.opengl.GLUtils
import app.apexview.AppState
import app.apexview.Car
import app.apexview.carsAt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Draws app-owned timing data into the clear encoder target on the compositor thread. */
internal class FinishedCastOverlay(private val width: Int, private val height: Int) {
    data class Driver(val label: String, val color: Int)
    data class Row(val label: String, val detail: String, val color: Int)
    data class Snapshot(
        val sceneWidth: Float,
        val sceneHeight: Float,
        val map: CastTimingProtocol.Panel?,
        val tower: CastTimingProtocol.Panel?,
        val outline: FloatArray,
        val cars: List<Car>,
        val drivers: Map<String, Driver>,
        val rows: List<Row>,
    )

    @Volatile var snapshot: Snapshot? = null
    private var uploaded: Snapshot? = null
    private var texture = 0
    private var program = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val quad = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        rewind()
    }
    private var bitmap: Bitmap? = null

    fun draw() {
        val current = snapshot ?: return
        if (program == 0 || !GLES20.glIsProgram(program)) {
            initialize()
            uploaded = null
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (uploaded !== current) {
            val image = bitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
            image.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(image)
            canvas.scale(width / current.sceneWidth, height / current.sceneHeight)
            current.map?.let { drawMap(canvas, it, current) }
            current.tower?.let { drawTower(canvas, it, current.rows) }
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, image, 0)
            uploaded = current
        }
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        val position = GLES20.glGetAttribLocation(program, "position")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(uv)
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun releaseGl() {
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        bitmap?.recycle()
        bitmap = null
        texture = 0
        program = 0
        uploaded = null
    }

    private fun drawMap(canvas: Canvas, panel: CastTimingProtocol.Panel, state: Snapshot) {
        val coordinates = state.outline
        if (coordinates.size < 4) return
        val path = Path()
        path.moveTo(coordinates[0], coordinates[1])
        for (i in 2 until coordinates.size - 1 step 2) path.lineTo(coordinates[i], coordinates[i + 1])
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        val scale = 0.85f * minOf(panel.width / bounds.width(), panel.height / bounds.height())
        val dx = panel.x + panel.width / 2 - bounds.centerX() * scale
        val dy = panel.y + panel.height / 2 - bounds.centerY() * scale
        path.transform(android.graphics.Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) })
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.LTGRAY
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 10f
        for (car in state.cars) {
            val x = car.x * scale + dx
            val y = car.y * scale + dy
            val driver = state.drivers[car.number]
            paint.color = driver?.color ?: Color.WHITE
            canvas.drawCircle(x, y, 5f, paint)
            paint.color = Color.WHITE
            canvas.drawText(driver?.label ?: car.number, x + 7f, y + 3f, paint)
        }
    }

    private fun drawTower(canvas: Canvas, panel: CastTimingProtocol.Panel, rows: List<Row>) {
        canvas.save()
        canvas.clipRect(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height)
        val rowHeight = (panel.height / rows.size.coerceAtLeast(1)).coerceIn(14f, 30f)
        paint.textSize = rowHeight * 0.63f
        rows.forEachIndexed { index, row ->
            val y = panel.y + index * rowHeight
            paint.color = 0xdd14161a.toInt()
            canvas.drawRect(panel.x, y, panel.x + panel.width, y + rowHeight - 1f, paint)
            paint.color = row.color
            canvas.drawRect(panel.x, y, panel.x + 3f, y + rowHeight - 1f, paint)
            paint.color = Color.WHITE
            canvas.drawText(row.label, panel.x + 8f, y + rowHeight * 0.74f, paint)
            canvas.drawText(row.detail, panel.x + panel.width * 0.47f, y + rowHeight * 0.74f, paint)
        }
        canvas.restore()
    }

    fun update(app: AppState, sceneWidth: Float, sceneHeight: Float) {
        val timing = app.timing
        val now = app.mapTimeMs()
        if (timing == null || now == null || sceneWidth <= 0f || sceneHeight <= 0f) {
            snapshot = null
            return
        }
        val map = app.activeMapPanel
        val mapPanel = if (app.mapMode.inWindow && map.placed) {
            CastTimingProtocol.Panel(map.x, map.y, map.w, map.h)
        } else null
        val tower = app.towerSpot
        val towerPanel = if (app.towerOpen) app.castTowerBounds ?: tower.takeIf { it.w > 0 && it.h > 0 }
            ?.let { CastTimingProtocol.Panel(it.x, it.y, it.w, it.h) } else null
        val drivers = timing.drivers.mapValues { (_, driver) -> Driver(driver.tla, driver.color or Color.BLACK) }
        val rows = if (towerPanel != null) {
            timing.laps.mapNotNull { (number, laps) -> laps.at(now)?.let { number to it } }
                .sortedBy { it.second.position ?: Int.MAX_VALUE }
                .map { (number, state) ->
                    val driver = drivers[number]
                    val detail = when {
                        state.retired -> "OUT"
                        state.inPit -> "PIT"
                        else -> state.toLeader ?: state.gap ?: state.lastLap?.time.orEmpty()
                    }
                    Row("${state.position ?: "–"}  ${driver?.label ?: number}", detail, driver?.color ?: Color.WHITE)
                }
        } else emptyList()
        snapshot = Snapshot(sceneWidth, sceneHeight, mapPanel, towerPanel, timing.outline,
            if (mapPanel != null) carsAt(timing, now) else emptyList(), drivers, rows)
    }

    private fun initialize() {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val status = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                GLES20.glDeleteShader(id)
                error("Cast overlay shader compilation failed")
            }
            return id
        }

        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec2 position; attribute vec2 uv; varying vec2 t; void main(){gl_Position=vec4(position,0.,1.);t=uv;}")
        val fragment = try {
            shader(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; varying vec2 t; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,t);}")
        } catch (error: Exception) {
            GLES20.glDeleteShader(vertex)
            throw error
        }
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Cast overlay shader link failed" }
        GLES20.glGenTextures(1, status, 0)
        texture = status[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
}
