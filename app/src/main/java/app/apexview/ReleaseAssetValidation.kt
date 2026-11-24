package app.apexview

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException

/** Narrow checksum verification for the one hardware-approved Pitwall APK. */
internal object ReleaseAssetValidation {
    fun validAssetName(name: String): Boolean =
        name.matches(Regex("pitwall-tv-1[.]0[.][0-9]+[.]apk"))

    // Bound the actual decoded response, not just Content-Length or a substring
    // of an already allocated response. Reject overflow instead of accepting truncation.
    fun readBoundedUtf8(input: InputStream, maxBytes: Int): String {
        require(maxBytes in 1..2 * 1024 * 1024)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(minOf(maxBytes + 1, 8192))
        var remaining = maxBytes
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
            if (count == -1) break
            if (count == 0) throw IOException("Empty update response read")
            if (count > remaining) throw IOException("Update response exceeds its size limit")
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun officialAssetUrl(repo: String, tag: String, name: String, url: String): Boolean =
        repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) &&
            tag.matches(Regex("v?1[.]0[.][0-9]+")) &&
            (name == "SHA256SUMS" || name == "pitwall-tv-${tag.removePrefix("v")}.apk") &&
            url == "https://github.com/$repo/releases/download/$tag/$name"

    fun expectedSha256(contents: String, assetName: String): String {
        require(validAssetName(assetName))
        // GNU sha256sum format: 64 hex characters followed by two spaces
        // (or space/asterisk for binary mode) and the unqualified file name.
        val entryPattern = Regex("^([a-fA-F0-9]{64}) [ *]([^/]+)$")
        val matches = contents.lineSequence().mapNotNull { line ->
            val entry = entryPattern.matchEntire(line.trimEnd())
            entry?.takeIf { it.groupValues[2] == assetName }?.groupValues?.get(1)
        }.toList()
        require(matches.size == 1) { "Missing or repeated official APK checksum" }
        return matches.single().lowercase()
    }
}
