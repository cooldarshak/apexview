package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactionTest {
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
