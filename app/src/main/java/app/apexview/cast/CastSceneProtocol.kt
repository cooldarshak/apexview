package app.apexview.cast

import app.apexview.scene.VideoScene
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
            .put("windows", JSONArray().apply { scene.windows.forEach { w ->
                put(JSONObject().put("id", w.id).put("source", w.source).put("x", w.x).put("y", w.y)
                    .put("width", w.width).put("height", w.height).put("z", w.z))
            } })
    }
}
