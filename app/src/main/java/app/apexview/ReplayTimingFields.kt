package app.apexview

import org.json.JSONArray
import org.json.JSONObject

/** Read-only Android domain adapter. Native canonical records retain only
 * spans into a temporary parser packet; unsupported JSON keeps the exact
 * platform parser. No packet or field view may escape a single record. */
internal interface ReplayTimingFields {
    val isArray: Boolean
    fun length(): Int
    fun keys(): Iterator<String>
    fun has(name: String): Boolean
    fun isNull(name: String): Boolean
    fun optString(name: String): String
    fun optInt(name: String, fallback: Int): Int
    fun optBoolean(name: String): Boolean
    fun opt(name: String): ReplayTimingFields?
    fun optJSONObject(name: String): ReplayTimingFields? = opt(name)?.takeUnless { it.isArray }
    fun optJSONObject(index: Int): ReplayTimingFields?
}

internal fun replayFields(value: Any?): ReplayTimingFields? = when (value) {
    is JSONObject -> ReferenceTimingFields(value, null)
    is JSONArray -> ReferenceTimingFields(null, value)
    else -> null
}

private class ReferenceTimingFields(private val objectValue: JSONObject?,
                                    private val arrayValue: JSONArray?) : ReplayTimingFields {
    override val isArray: Boolean get() = arrayValue != null
    override fun length() = objectValue?.length() ?: requireNotNull(arrayValue).length()
    override fun keys(): Iterator<String> = objectValue?.keys() ?: emptyList<String>().iterator()
    override fun has(name: String) = objectValue?.has(name) == true
    override fun isNull(name: String) = objectValue?.isNull(name) != false
    override fun optString(name: String) = objectValue?.optString(name).orEmpty()
    override fun optInt(name: String, fallback: Int) = objectValue?.optInt(name, fallback) ?: fallback
    override fun optBoolean(name: String) = objectValue?.optBoolean(name) == true
    override fun opt(name: String) = replayFields(objectValue?.opt(name))
    override fun optJSONObject(index: Int) = replayFields(arrayValue?.optJSONObject(index))
}

/** Five words per token: key span, kind, value span, subtree end, child count.
 * The same immutable view works for a full snapshot and keyed topic deltas. */
internal class NativeReplayTimingFields(private val source: String, private val words: LongArray,
                                        private val base: Int, private val node: Int = 0) : ReplayTimingFields {
    private fun field(index: Int, column: Int) = words[base + index * WORDS + column]
    private fun kind(index: Int) = field(index, 1).toInt()
    private fun end(index: Int) = field(index, 3).toInt()
    private fun span(value: Long) = source.substring((value ushr 32).toInt(), value.toInt())
    private fun child(index: Int) = NativeReplayTimingFields(source, words, base, index)
    private fun find(name: String): Int {
        if (isArray) return -1
        var i = node + 1
        while (i < end(node)) {
            val key = field(i, 0)
            val start = (key ushr 32).toInt()
            val count = key.toInt() - start
            if (count == name.length && source.regionMatches(start, name, 0, count)) return i
            i = end(i)
        }
        return -1
    }
    override val isArray: Boolean get() = kind(node) == ARRAY
    override fun length(): Int = field(node, 4).toInt()
    override fun keys(): Iterator<String> = object : Iterator<String> {
        private var i = if (isArray) end(node) else node + 1
        override fun hasNext() = i < end(node)
        override fun next(): String {
            if (!hasNext()) throw NoSuchElementException()
            val result = span(field(i, 0))
            i = end(i)
            return result
        }
    }
    override fun has(name: String) = find(name) >= 0
    override fun isNull(name: String) = !has(name) // Native admission excludes null values.
    override fun optString(name: String): String {
        val i = find(name)
        if (i < 0) return ""
        val value = span(field(i, 2))
        return when (kind(i)) {
            OBJECT -> JSONObject(value).toString() ?: ""
            ARRAY -> JSONArray(value).toString() ?: ""
            else -> value
        }
    }
    override fun optInt(name: String, fallback: Int): Int {
        val i = find(name)
        if (i < 0) return fallback
        return when (kind(i)) {
            INTEGER -> span(field(i, 2)).toIntOrNull() ?: fallback
            STRING -> span(field(i, 2)).toDoubleOrNull()?.toInt() ?: fallback
            else -> fallback
        }
    }
    override fun optBoolean(name: String): Boolean {
        val i = find(name)
        if (i < 0) return false
        return kind(i) == TRUE || kind(i) == STRING && span(field(i, 2)).equals("true", true)
    }
    override fun opt(name: String): ReplayTimingFields? {
        val i = find(name)
        return if (i >= 0 && (kind(i) == OBJECT || kind(i) == ARRAY)) child(i) else null
    }
    override fun optJSONObject(index: Int): ReplayTimingFields? {
        if (!isArray || index !in 0 until length()) return null
        var i = node + 1
        repeat(index) { i = end(i) }
        return if (kind(i) == OBJECT) child(i) else null
    }
    companion object {
        const val WORDS = 5
        const val MAX_RECORD_WORDS = 2048 * WORDS
        private const val OBJECT = 1
        private const val ARRAY = 2
        private const val STRING = 3
        private const val INTEGER = 4
        private const val TRUE = 5
    }
}
