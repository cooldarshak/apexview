package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactionTest {
    @Test fun utf8SizeMatchesExistingEncoderIncludingMalformedSurrogates() {
        val samples = listOf(
            "", "ASCII decoder evidence", "Apexview · 日本語",
            "é€", String(charArrayOf(0xD83D.toChar(), 0xDE80.toChar())),
            String(charArrayOf(0xD800.toChar())),
            String(charArrayOf(0xDC00.toChar(), 'x')),
            String(charArrayOf(0xD800.toChar(), 0xD800.toChar(), 0xDC00.toChar())),
            "é".repeat(64 * 1024),
        )
        for (sample in samples) {
            assertEquals(sample.toByteArray(Charsets.UTF_8).size, diagnosticUtf8Size(sample))
        }
        val random = java.util.Random(888L)
        repeat(1000) {
            val sample = String(CharArray(random.nextInt(128)) { random.nextInt(65536).toChar() })
            assertEquals(sample.toByteArray(Charsets.UTF_8).size, diagnosticUtf8Size(sample))
        }
    }

    @Test fun removesUrlsAndAuthenticationValuesButKeepsDecoderEvidence() {
        val log = "decoder=c2.qti.hevc.decoder url=https://example.test/video?token=secret Authorization: Bearer abc123"
        val safe = DiagnosticRedaction.redact(log)
        assertTrue(safe.contains("decoder=c2.qti.hevc.decoder"))
        assertFalse(safe.contains("example.test"))
        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("secret"))
    }

    @Test fun removesJsonTokenValuesAndCookies() {
        val safe = DiagnosticRedaction.redact("{\"access_token\":\"private123\"}\nCookie: first=one; second=two")
        assertFalse(safe.contains("private123"))
        assertFalse(safe.contains("first=one"))
        assertFalse(safe.contains("second=two"))
    }
}
