package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun checksumManifestBindsExactlyOneOfficialReleaseApk() {
        val digest = "f".repeat(64)
        val manifest = "$digest  pitwall-tv-1.0.706.apk\n" +
            "${"0".repeat(64)}  unrelated.apk\n"
        org.junit.Assert.assertEquals(digest,
            ReleaseAssetValidation.expectedSha256(manifest, "pitwall-tv-1.0.706.apk"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ReleaseAssetValidation.expectedSha256(manifest, "pitwall-tv-1.0.707.apk")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ReleaseAssetValidation.expectedSha256("invalid  pitwall-tv-1.0.706.apk",
                "pitwall-tv-1.0.706.apk")
        }
    }

    @Test
    fun versionsCompareByNumberNotText() {
        assertTrue(Updater.isNewer("0.36", "0.35"))
        assertTrue(Updater.isNewer("0.100", "0.99"))
        assertTrue(Updater.isNewer("1.0", "0.99"))
        assertTrue(Updater.isNewer("v0.35.1".removePrefix("v"), "0.35"))
        assertFalse(Updater.isNewer("0.35", "0.35"))
        assertFalse(Updater.isNewer("0.34", "0.35"))
        // A green debug build is even; the corresponding approved release is
        // odd; a subsequent debug build can still update over that release.
        assertTrue(Updater.isNewer("1.0.1465", "1.0.1464"))
        assertTrue(Updater.isNewer("1.0.1466", "1.0.1465"))
        assertFalse(Updater.isNewer("1.0.1464", "1.0.1465"))
    }
}
