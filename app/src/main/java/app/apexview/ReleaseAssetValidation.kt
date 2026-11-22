package app.apexview

/** Narrow checksum verification for the one hardware-approved Pitwall APK. */
internal object ReleaseAssetValidation {
    fun validAssetName(name: String): Boolean =
        name.matches(Regex("pitwall-tv-1[.]0[.][0-9]+[.]apk"))

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
