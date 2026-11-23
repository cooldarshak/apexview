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
    fun installerResultIsBoundedAndKeepsItsOwnAppAction() {
        assertTrue(InstallStatusProtocol.uiAction("app.apexview_custom").startsWith("app.apexview_custom."))
        val longFailure = "No install permission " + "x".repeat(1000)
        val result = InstallStatusProtocol.failureMessage(longFailure)
        assertTrue(result.contains("No install permission"))
        assertTrue(result.length < 260)
    }

    @Test
    fun updateProgressOnlyReportsMeaningfulChangesToCompose() {
        assertTrue(UpdateProgressPolicy.shouldPublish(.01f, -1f, 0))
        assertFalse(UpdateProgressPolicy.shouldPublish(.02f, .01f, 5))
        assertTrue(UpdateProgressPolicy.shouldPublish(.06f, .01f, 5))
        assertFalse(UpdateProgressPolicy.shouldPublish(.059f, .01f, 5))
        assertTrue(UpdateProgressPolicy.shouldPublish(.02f, .01f, 250))
        assertTrue(UpdateProgressPolicy.shouldPublish(1f, .99f, 0))
        assertFalse(UpdateProgressPolicy.shouldPublish(1f, 1f, 500))
        assertFalse(UpdateProgressPolicy.shouldPublish(Float.NaN, 0f, 500))
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
