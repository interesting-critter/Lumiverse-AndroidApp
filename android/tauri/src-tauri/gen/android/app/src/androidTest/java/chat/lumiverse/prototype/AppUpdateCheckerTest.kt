package chat.lumiverse.prototype

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AppUpdateCheckerTest {
    private fun release(run: Int): JSONObject {
        val tag = "android-v0.3.5-nightly.20261028.$run"
        return JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", true)
            .put("published_at", "2026-10-28T13:42:34Z")
            .put("body", "Nightly bundled Android ARM64 APK.\n\n- Version code: ${1_000_000_000 + run}\n")
            .put("assets", JSONArray().put(JSONObject().put("name", AppUpdatePolicy.APK_NAME)
                .put("state", "uploaded").put("size", 270776203)
                .put("browser_download_url", "${AppUpdatePolicy.REPOSITORY}/releases/download/$tag/${AppUpdatePolicy.APK_NAME}")))
    }

    @Test fun includesNightlyPrereleasesAndChoosesHighestInstallVersionRegardlessOfOrder() {
        val json = JSONArray().put(release(19)).put(release(21)).put(release(20))
        val latest = AppUpdateChecker.parseReleases(json.toString())
        assertEquals(1_000_000_021L, latest.versionCode)
        assertEquals("0.3.5-nightly.20261028.21", latest.version)
        assertEquals("${AppUpdatePolicy.REPOSITORY}/releases/tag/android-v0.3.5-nightly.20261028.21", latest.url)
    }

    @Test fun ignoresDraftsMissingApksAndIncompleteUploads() {
        val draft = release(23).put("draft", true)
        val missing = release(24).put("assets", JSONArray())
        val uploading = release(25).apply { getJSONArray("assets").getJSONObject(0).put("state", "starter") }
        val metadata = release(26).apply { getJSONArray("assets").getJSONObject(0).put("name", AppUpdatePolicy.APK_NAME + ".json") }
        val json = JSONArray().put(draft).put(missing).put(uploading).put(metadata).put(release(22))
        assertEquals(1_000_000_022L, AppUpdateChecker.parseReleases(json.toString()).versionCode)
    }

    @Test fun malformedEntriesDoNotHideAValidRelease() {
        val json = JSONArray().put(JSONObject()).put(JSONObject.NULL).put("invalid")
            .put(release(24).put("assets", JSONArray().put("invalid")))
            .put(release(23).put("draft", JSONObject.NULL)).put(release(20))
        assertEquals(1_000_000_020L, AppUpdateChecker.parseReleases(json.toString()).versionCode)
    }

    @Test fun unusableFeedsFailInsteadOfClaimingTheAppIsCurrent() {
        for (feed in listOf(JSONArray(), JSONArray().put(release(20).put("draft", true)),
            JSONArray().put(release(20).put("body", "Release notes without an install version")))) {
            try {
                AppUpdateChecker.parseReleases(feed.toString())
                fail("An unusable release feed must fail the check")
            } catch (_: IOException) { }
        }
    }
}
