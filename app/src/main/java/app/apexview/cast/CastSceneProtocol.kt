package app.apexview.cast

import app.apexview.scene.VideoScene
import app.apexview.scene.VideoWindow
import app.apexview.scene.VideoSourceRect
import org.json.JSONArray
import org.json.JSONObject

internal object CastSceneProtocol {
    const val NAMESPACE = "urn:x-cast:app.pitwall.scene"
    fun encode(scene: VideoScene): JSONObject {
        scene.quads() // Validate coordinates and source boundaries before sending.
        return JSONObject().put("version", 1).put("width", scene.width).put("height", scene.height)
            .put("atlasWidth", scene.atlasWidth).put("atlasHeight", scene.atlasHeight)
            .put("sources", scene.sources).put("atlasColumns", scene.atlasColumns)
            .put("sourceCodedWidth", scene.sourceCodedWidth).put("sourceCodedHeight", scene.sourceCodedHeight)
            .put("sourceDisplayWidth", scene.sourceDisplayWidth).put("sourceDisplayHeight", scene.sourceDisplayHeight)
            .put("sourceRects", JSONArray().apply { scene.sourceRects.forEach { rect ->
                put(JSONObject().put("x", rect.x).put("y", rect.y).put("width", rect.width).put("height", rect.height))
            } })
            .put("windows", JSONArray().apply { scene.windows.forEach { w ->
                put(JSONObject().put("id", w.id).put("source", w.source).put("x", w.x).put("y", w.y)
                    .put("width", w.width).put("height", w.height).put("z", w.z))
            } })
    }

    /** Treat LAN messages as untrusted; fail before allocating a player/GL scene. */
    fun decode(json: JSONObject): VideoScene {
        require(json.getInt("version") == 1) { "Unsupported Pitwall layout version" }
        fun dimension(key: String) = json.getInt(key).also { require(it in 1..32768) { "Invalid $key" } }
        fun coordinate(obj: JSONObject, key: String): Float = obj.getDouble(key).toFloat().also {
            require(it.isFinite() && kotlin.math.abs(it) <= 100_000f) { "Invalid $key" }
        }
        val sources = json.getInt("sources").also { require(it in 1..24) }
        val windows = json.getJSONArray("windows")
        require(windows.length() in 1..24)
        val scene = VideoScene(coordinate(json, "width"), coordinate(json, "height"),
            dimension("atlasWidth"), dimension("atlasHeight"), sources,
            List(windows.length()) { i -> windows.getJSONObject(i).let { w ->
                VideoWindow(w.getInt("id"), w.getInt("source"), coordinate(w, "x"), coordinate(w, "y"),
                    coordinate(w, "width"), coordinate(w, "height"), coordinate(w, "z"))
            } }, json.getInt("atlasColumns"), dimension("sourceCodedWidth"), dimension("sourceCodedHeight"),
            dimension("sourceDisplayWidth"), dimension("sourceDisplayHeight"),
            json.optJSONArray("sourceRects")?.let { array -> List(array.length()) { i ->
                array.getJSONObject(i).let { obj -> VideoSourceRect(obj.getInt("x"), obj.getInt("y"),
                    obj.getInt("width"), obj.getInt("height")) }
            } }.orEmpty())
        require(scene.atlasWidth.toLong() * scene.atlasHeight <= 35_651_584L) { "Atlas exceeds supported picture size" }
        require(scene.atlasColumns in 1..sources)
        if (scene.sourceRects.isEmpty()) {
            require(scene.atlasWidth.toLong() in ((scene.atlasColumns - 1L) * scene.sourceCodedWidth + scene.sourceDisplayWidth)..(scene.atlasColumns.toLong() * scene.sourceCodedWidth)) { "Atlas columns do not match the stream" }
            val rows = (sources + scene.atlasColumns - 1) / scene.atlasColumns
            require(scene.atlasHeight.toLong() in ((rows - 1L) * scene.sourceCodedHeight + scene.sourceDisplayHeight)..(rows.toLong() * scene.sourceCodedHeight)) { "Atlas rows do not match the stream" }
        }
        scene.quads()
        return scene
    }

}
