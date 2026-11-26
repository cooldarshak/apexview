package app.apexview.cast.local

import android.content.Context
import java.security.MessageDigest

/** Local authorization is tied to both exact TLS installation certificates.
 * Never trust a discovery name, IP, network membership or advertised capability.
 * A caller may remember only after local physical approval or an existing pin
 * and the authenticated peer's successful protocol negotiation. */
internal object PitwallRememberedPeers {
    private const val STORE = "pitwall_tls_remembered_v1"
    private const val KEY = "certificate_pairs"
    private const val MAX_PEERS = 32
    fun key(local: ByteArray, peer: ByteArray): String {
        val md=MessageDigest.getInstance("SHA-256")
        val a=md.digest(local)
        val b=md.digest(peer)
        md.update("Pitwall remembered local/peer v1".toByteArray(Charsets.US_ASCII))
        md.update(a); md.update(b)
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    internal fun add(old: Set<String>, next: String): Set<String> {
        require(next.matches(Regex("[0-9a-f]{64}")))
        return (old.filter { it != next && it.matches(Regex("[0-9a-f]{64}")) }
            .sorted().takeLast(MAX_PEERS-1) + next).toSet()
    }
    private fun prefs(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    @Synchronized fun contains(context: Context, key: String): Boolean =
        key in (prefs(context).getStringSet(KEY, emptySet()) ?: emptySet())
    @Synchronized fun remember(context: Context, key: String) {
        val p=prefs(context)
        p.edit().putStringSet(KEY,add(p.getStringSet(KEY,emptySet()) ?: emptySet(),key)).apply()
    }
    @Synchronized fun forgetAll(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
