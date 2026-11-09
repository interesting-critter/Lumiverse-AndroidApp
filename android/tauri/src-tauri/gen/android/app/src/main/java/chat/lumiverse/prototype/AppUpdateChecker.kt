package chat.lumiverse.prototype

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/** APK-only checks run in the foreground, even on the chooser or an offline server page. */
class AppUpdateChecker(private val activity: MainActivity) {
    private val preferences = activity.getSharedPreferences("lumiverse-app-updates", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).build()
    private var call: Call? = null
    private var destroyed = false
    private var foreground = false
    private var manual = false
    private var lastAttempt = -1L
    private var failed = false
    private var etag: String? = null
    private var cached: AppUpdatePolicy.Release? = null
    private var pending: AppUpdatePolicy.Release? = null
    private var pendingMessage: String? = null
    private var dialog: AlertDialog? = null
    private val poll = object : Runnable {
        override fun run() {
            if (!foreground || destroyed) return
            check()
            handler.postDelayed(this, AppUpdatePolicy.RETRY_INTERVAL_MS)
        }
    }

    fun resume() {
        foreground = true
        handler.removeCallbacks(poll)
        // Let startup/permission dialogs settle before presenting an update.
        handler.postDelayed(poll, 3_000)
        presentPending()
    }

    fun pause() { foreground = false; handler.removeCallbacks(poll) }

    fun check(manually: Boolean = false) {
        if (destroyed) return
        if (manually) {
            manual = true
            Toast.makeText(activity, "Checking for app updates…", Toast.LENGTH_SHORT).show()
        }
        if (call != null) return
        val now = SystemClock.elapsedRealtime()
        if (!manually && !AppUpdatePolicy.shouldCheck(lastAttempt, now, failed)) return
        lastAttempt = now
        val request = Request.Builder().url(AppUpdatePolicy.RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Lumiverse-Android/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .apply { etag?.let { header("If-None-Match", it) } }.build()
        call = client.newCall(request).also { task -> task.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { finish(null, null, e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val release = if (it.code == 304) cached ?: throw IOException("Missing cached release")
                        else {
                            if (!it.isSuccessful) throw IOException("GitHub returned ${it.code}")
                            val source = it.body?.source() ?: throw IOException("Empty release list")
                            if (source.request(1_048_577)) throw IOException("Release list is too large")
                            parseReleases(source.readUtf8())
                        }
                        finish(release, it.header("ETag"), null)
                    }
                } catch (error: Exception) { finish(null, null, error) }
            }
        }) }
    }

    private fun finish(release: AppUpdatePolicy.Release?, responseEtag: String?, error: Exception?) {
        handler.post {
            if (destroyed) return@post
            call = null
            failed = error != null
            val requested = manual
            manual = false
            if (error != null) {
                if (requested) pendingMessage = "Could not check for Android app updates. Check your internet connection and try again."
            } else if (release != null) {
                cached = release
                etag = responseEtag
                pending = null
                if (release.versionCode > BuildConfig.VERSION_CODE.toLong()) {
                    if (requested || shouldNotify(release)) pending = release
                } else if (requested) {
                    pendingMessage = "Your Android APK is up to date (${BuildConfig.VERSION_NAME})."
                }
            }
            presentPending()
        }
    }

    private fun shouldNotify(release: AppUpdatePolicy.Release) = AppUpdatePolicy.shouldNotify(
        release.versionCode, BuildConfig.VERSION_CODE.toLong(), preferences.getLong("notified-code", 0),
        preferences.getLong("notified-at", 0), System.currentTimeMillis())

    fun presentPending() {
        if (!foreground || destroyed || activity.isFinishing || activity.isDestroyed
            || !activity.hasWindowFocus() || dialog?.isShowing == true) return
        val release = pending
        if (release != null) {
            pending = null
            pendingMessage = null
            preferences.edit().putLong("notified-code", release.versionCode)
                .putLong("notified-at", System.currentTimeMillis()).apply()
            dialog = AlertDialog.Builder(activity).setTitle("Android app update available")
                .setMessage("A new nightly APK is available: ${release.version}\n\nInstalled: ${BuildConfig.VERSION_NAME}\n\nOpen the release page to download and install the APK. Installing it over this app preserves your data.")
                .setPositiveButton("View update") { _, _ ->
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    catch (_: RuntimeException) { Toast.makeText(activity, "Could not open the release page.", Toast.LENGTH_LONG).show() }
                }
                .setNegativeButton("Later", null).show()
        } else pendingMessage?.let { message ->
            pendingMessage = null
            dialog = AlertDialog.Builder(activity).setTitle("Android app updates")
                .setMessage(message).setPositiveButton("OK", null).show()
        }
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        call?.cancel(); call = null
        dialog?.dismiss(); dialog = null
    }

    companion object {
        internal fun parseReleases(json: String): AppUpdatePolicy.Release {
            val releases = JSONArray(json)
            var newest: AppUpdatePolicy.Release? = null
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val candidate = AppUpdatePolicy.candidate(release.optString("tag_name"), release.optString("body"),
                        release.optBoolean("draft", true), release.optString("published_at"),
                        asset.optString("name"), asset.optString("state"), asset.optLong("size"),
                        asset.optString("browser_download_url")) ?: continue
                    if (newest == null || candidate.versionCode > newest.versionCode) newest = candidate
                }
            }
            return newest ?: throw IOException("No published Android nightly APK was found")
        }
    }
}
