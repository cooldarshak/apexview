package app.apexview
import org.junit.Assert.assertEquals
import org.junit.Test
class CodecEnvelopeAuditTest {
    @Test fun distinguishUnknownCodedCropAndAdaptiveEnvelope() {
        assertEquals("UNREPORTED", CodecEnvelopeAudit.classify(1920, 2176, -1, -1))
        assertEquals("EXACT_CODED", CodecEnvelopeAudit.classify(1920, 2176, 1920, 2176))
        assertEquals("LARGER_THAN_CODED", CodecEnvelopeAudit.classify(1920, 2176, 4096, 2176))
        assertEquals("BELOW_CODED", CodecEnvelopeAudit.classify(1920, 2176, 1920, 2160))
    }
}
