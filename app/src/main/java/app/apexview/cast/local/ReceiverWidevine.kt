package app.apexview.cast.local

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import app.apexview.F1TvApi
import app.apexview.Settings
import app.apexview.TokenStore
import org.json.JSONArray
import org.json.JSONObject

/** Content identifiers only. Phone credentials, licence URLs and licence responses are rejected. */
internal data class ReceiverLicenseRequest(val contentId: Long, val channels: List<Int>, val audioChannel: Int) {
    fun encode() = JSONObject().put("contentId", contentId).put("channels", JSONArray(channels)).put("audioChannel", audioChannel)
    companion object {
        fun direct(body: JSONObject, negotiated: Boolean): ReceiverLicenseRequest? {
            val custom = body.optJSONObject("metadata")?.optJSONObject("custom") ?: return null
            if (!custom.optBoolean("pitwallDirect")) return null
            require(negotiated && !body.has("url")) { "The TV resolves its own authorized direct-play URL" }
            return decode(custom.getJSONObject("pitwallLicense")).also {
                require(it.channels.size == 1) { "Direct playback requires one TV-authorized channel" }
            }
        }
        fun decode(j: JSONObject): ReceiverLicenseRequest {
            require(j.keys().asSequence().all { it in setOf("contentId", "channels", "audioChannel") }) { "Unexpected licence request fields" }
            val id = j.getLong("contentId"); require(id > 0)
            val a = j.getJSONArray("channels"); require(a.length() in 1..6)
            val channels = (0 until a.length()).map { a.getInt(it).also { n -> require(n > 0) } }
            require(channels.distinct().size == channels.size)
            val audio = j.getInt("audioChannel"); require(audio in channels)
            return ReceiverLicenseRequest(id, channels, audio)
        }
    }
}

internal class ReceiverWidevine private constructor(private val drm: FrameworkMediaDrm?,
    val sessions: DefaultDrmSessionManager?, val secureOutput: Boolean,
    val source: app.apexview.PlayInfo, val mediaHttp: DefaultHttpDataSource.Factory) : AutoCloseable {
    override fun close() { drm?.release() }
    companion object {
        /** Executed after TV Allow. Uses this installation's saved subscription and entitlement. */
        suspend fun authorize(context: Context, request: ReceiverLicenseRequest): ReceiverWidevine {
            val token = requireNotNull(TokenStore.load(context)) { "Sign in to F1 TV on this TV before receiving protected/live feeds" }
            val agent = WebSettings.getDefaultUserAgent(context).replace("; wv", "")
            val chrome = WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.') ?: "140"
            val settings = Settings(context)
            val api = F1TvApi(agent, chrome).also { it.platform = settings.platform }
            api.login(token)
            val streams = if (request.channels.size == 1) listOf(api.contentPlay(request.contentId, request.audioChannel)) else {
                val play = api.multiPlay(request.contentId, request.audioChannel)
                require(request.channels.all { it in play.feeds }) { "The TV subscription did not authorize every selected channel" }
                request.channels.map { requireNotNull(play.streamsByChannel[it]) }
            }
            val urls = streams.map { it.licenseUrl }
            val media = DefaultHttpDataSource.Factory().setUserAgent(agent).setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(mapOf("ascendontoken" to api.ascendon.orEmpty(), "entitlementtoken" to api.entitlement.orEmpty()))
            if (urls.all { it.isNullOrBlank() }) return ReceiverWidevine(null, null, false, streams.first(), media)
            require(urls.all { !it.isNullOrBlank() } && urls.distinct().size == 1) { "Selected channels do not share the TV's authorized Widevine licence" }
            val http = DefaultHttpDataSource.Factory().setUserAgent(agent).setAllowCrossProtocolRedirects(true)
            val callback = HttpMediaDrmCallback(urls.first(), http).apply {
                setKeyRequestProperty("ascendontoken", api.ascendon.orEmpty())
                setKeyRequestProperty("entitlementtoken", api.entitlement.orEmpty())
            }
            val drm = FrameworkMediaDrm.newInstance(C.WIDEVINE_UUID)
            try {
                if (settings.softwareDrm) runCatching { drm.setPropertyString("securityLevel", "L3") }
                val level = drm.getPropertyString("securityLevel")?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
                val provider = ExoMediaDrm.Provider { uuid ->
                    require(uuid == C.WIDEVINE_UUID); drm.acquire(); drm
                }
                val sessions = DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, provider)
                    .setMultiSession(false).build(callback)
                Log.i("PitwallReceiver", "TV_WIDEVINE_AUTHORIZED content=${request.contentId} channels=${request.channels.size} level=$level credentials=TV_OWNED")
                return ReceiverWidevine(drm, sessions, !level.equals("L3", true), streams.first(), media)
            } catch (error: Throwable) { drm.release(); throw error }
        }
    }
}
