package chat.lumiverse.test

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class CharacterWidgetWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val active = CharacterWidgetStore.current(context) ?: run { CharacterWidgets.renderAll(context); return Result.success() }
        if (active.optLong("expiresAt") <= System.currentTimeMillis()) {
            NativeCharacterWidgets.clear(context); return Result.success()
        }
        val revision = CharacterWidgetStore.revision(context)
        val base = "/api/ios-widgets/v1/registrations/" + active.getString("registrationId")
        val started = System.nanoTime()
        try {
            val raw = download(active, "$base/snapshot", 64 * 1024, "application/json") ?: return Result.success()
            val snapshot = WidgetSnapshot.parse(JSONObject(String(raw, Charsets.UTF_8)))
            require(snapshot.accountId == active.getString("accountId"))
            val images = mutableMapOf<String, ByteArray>()
            for (id in (snapshot.recent + snapshot.favorites).map { it.characterId }.distinct()) {
                if (isStopped) return Result.success()
                if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(24)) break
                try {
                    val bytes = download(active, "$base/characters/$id/portrait", 256 * 1024, "image/jpeg") ?: continue
                    if (boundedJPEG(bytes)) images[id] = bytes
                } catch (_: java.io.IOException) { /* A missing portrait must not discard collection metadata. */ }
            }
            if (!isStopped) CharacterWidgetStore.commit(context, active, revision, snapshot, images)
            CharacterWidgets.renderAll(context)
            NativeNotificationBridge.widgetsUpdated()
            return Result.success()
        } catch (_: Revoked) {
            // A response from a retired request must not clear a new account.
            synchronized(CharacterWidgetStore) {
                if (CharacterWidgetStore.current(context)?.optString("registrationId") == active.optString("registrationId"))
                    NativeCharacterWidgets.clear(context)
            }
            return Result.success()
        } catch (_: Exception) {
            CharacterWidgets.renderAll(context)
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
    companion object {
        private const val PERIODIC = "lumiverse-character-widgets-periodic"
        private const val REFRESH = "lumiverse-character-widgets-refresh"
        // Never attach cookies or forward the scoped bearer through a redirect.
        private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(4, TimeUnit.SECONDS).build()
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (!CharacterWidgets.hasWidgets(context)) {
                manager.cancelUniqueWork(PERIODIC); return
            }
            // No network constraint: loopback works even with Wi-Fi and cellular off.
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CharacterWidgetWorker>(30, TimeUnit.MINUTES).build())
        }
        fun request(context: Context) {
            schedule(context)
            WorkManager.getInstance(context).enqueueUniqueWork(REFRESH, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CharacterWidgetWorker>().build())
        }
        private class Revoked : Exception()
        private fun download(active: JSONObject, path: String, max: Int, mime: String): ByteArray? {
            val request = Request.Builder().url(active.getString("serverOrigin") + path)
                .header("Authorization", "Bearer " + active.getString("credential")).build()
            return http.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) throw Revoked()
                if (!response.isSuccessful || response.header("Content-Type")?.substringBefore(';') != mime) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > max) return@use null
                body.byteStream().use stream@{ stream ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read == -1) break
                        if (bytes.size() + read > max) return@stream null
                        bytes.write(buffer, 0, read)
                    }
                    bytes.toByteArray()
                }
            }
        }
        fun boundedJPEG(bytes: ByteArray): Boolean {
            if (bytes.size > 256 * 1024) return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            return bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..192 && bounds.outHeight in 1..256
        }
        fun revoke(active: JSONObject) {
            try {
                http.newCall(Request.Builder().url(active.getString("serverOrigin") + "/api/ios-widgets/v1/registrations/" + active.getString("registrationId"))
                    .header("Authorization", "Bearer " + active.getString("credential")).delete().build()).execute().close()
            } catch (_: Exception) { /* Offline credentials also expire server-side. */ }
        }
    }
}
