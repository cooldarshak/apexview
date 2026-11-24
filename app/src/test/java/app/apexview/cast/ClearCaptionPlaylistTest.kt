package app.apexview.cast

import org.junit.Assert.*
import org.junit.Test

class ClearCaptionPlaylistTest {
    private val valid = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:5.760,source metadata
        https://example.invalid/sub-1.vtt?token=not-a-real-token
        #EXT-X-DISCONTINUITY
        #EXTINF:4.240,
        sub-2.vtt
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test fun preservesTimingAndOnlyEmitsOpaqueLocalNames() {
        val result = ClearCaptionPlaylist.rewrite(valid, 1)
        assertEquals(2, result.sourceUris.size)
        assertTrue(result.text.contains("#EXTINF:5.76,"))
        assertTrue(result.text.contains("cc-1-0.vtt"))
        assertTrue(result.text.contains("#EXT-X-DISCONTINUITY"))
        assertFalse(result.text.contains("https://"))
        assertFalse(result.text.contains("token"))
        assertFalse(result.text.contains("source metadata"))
        val tag = ClearCaptionPlaylist.masterTag(1, "en", "English\"\nCC")
        assertTrue(tag.contains("NAME=\"EnglishCC\""))
        assertTrue(tag.contains("URI=\"cc-1.m3u8\""))
    }

    @Test fun rejectsProtectedOrNonFiniteOrAmbiguousSegmentDeclarations() {
        for (text in listOf(valid.replace("#EXT-X-VERSION:3", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\""),
            valid.replace("#EXT-X-VERSION:3", "#EXT-X-MAP:URI=\"init\""),
            valid.replace("#EXT-X-VERSION:3", "#EXT-X-BYTERANGE:200@0"),
            valid.replace("#EXT-X-ENDLIST", ""), valid.replace("#EXTINF:5.760", "#EXTINF:NaN"),
            valid.replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:token=secret"),
            valid.replace("#EXT-X-VERSION:3", "#EXT-X-ENDLIST"),
            valid.replace("#EXT-X-VERSION:3", "#EXT-X-DEFINE:NAME=secret,VALUE=secret"))) {
            assertThrows(IllegalArgumentException::class.java) { ClearCaptionPlaylist.rewrite(text, 0) }
        }
    }

    @Test fun onlyActualWebVttHeadersQualify() {
        assertTrue(ClearCaptionPlaylist.isWebVtt("WEBVTT\n\n".toByteArray()))
        assertTrue(ClearCaptionPlaylist.isWebVtt("\uFEFFWEBVTT\r\n".toByteArray()))
        assertFalse(ClearCaptionPlaylist.isWebVtt("WEBVTTfake".toByteArray()))
        assertFalse(ClearCaptionPlaylist.isWebVtt(byteArrayOf(0, 0, 0, 16, 109, 100, 97, 116)))
    }
}
