package chat.lumiverse.prototype

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Persisted enrollment is private to the app; no browser cookies are needed for receipt. */
object NativeNotifications {
    private const val COMPLETE = "lumiverse-generation-complete-v1"
    private const val ERROR = "lumiverse-generation-error-v1"
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(2, TimeUnit.SECONDS).build()
    private fun prefs(context: Context) = context.getSharedPreferences("lumiverse-notifications", Context.MODE_PRIVATE)
    fun enrollment(context: Context): JSONObject? = try { JSONObject(prefs(context).getString("enrollment", null) ?: "null") } catch (_: Exception) { null }
    fun deviceId(context: Context): String {
        val store = prefs(context)
        return store.getString("deviceId", null) ?: UUID.randomUUID().toString().also { store.edit().putString("deviceId", it).commit() }
    }
    fun selectedOrigin(context: Context): String? = prefs(context).getString("selectedOrigin", null)
    fun isLocal(context: Context) = prefs(context).getBoolean("local", false)
    fun account(context: Context): String? = prefs(context).getString("accountId", null)
    fun origin(raw: String?): String? = NotificationPolicy.origin(raw)
    fun clear(context: Context) {
        FCMRelayEnrollment.clear(context)
        prefs(context).edit().remove("enrollment").commit()
        LocalNotificationTransport.stop()
        NotificationManagerCompat.from(context).cancelAll()
    }
    fun select(context: Context, raw: String, local: Boolean) {
        val origin = origin(raw) ?: return
        if (selectedOrigin(context) != origin || isLocal(context) != local) clear(context)
        prefs(context).edit().putString("selectedOrigin", origin).putBoolean("local", local).commit()
    }
    fun setAccount(context: Context, accountId: String?) {
        if (account(context) != accountId) clear(context)
        prefs(context).edit().putString("accountId", accountId).commit()
    }
    fun firebase(context: Context): FirebaseApp? = try { FirebaseApp.initializeApp(context) ?: FirebaseApp.getInstance() } catch (_: Exception) { null }
    fun permission(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return if (prefs(context).getBoolean("asked", false)) "denied" else "default"
        return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "granted" else "denied"
    }
    fun markPermissionAsked(context: Context) { prefs(context).edit().putBoolean("asked", true).apply() }
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(COMPLETE, "Generation complete", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(ERROR, "Generation errors", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun status(context: Context): JSONObject {
        channels(context)
        val app = firebase(context)
        val local = isLocal(context)
        val current = enrollment(context)
        return JSONObject().apply {
            put("available", local || app != null)
            put("relayAvailable", true); put("encryptionVersion", 1); put("packageName", context.packageName)
            put("permission", permission(context)); put("deviceId", deviceId(context))
            put("transport", if (local) "local" else "fcm")
            put("projectId", app?.options?.projectId ?: JSONObject.NULL)
            put("reason", if (local || app != null) JSONObject.NULL else "This APK needs Firebase client configuration for remote notifications.")
            put("connected", LocalNotificationTransport.connected)
            put("enrollment", current?.let { JSONObject().apply {
                for (key in listOf("destinationId", "serverOrigin", "serverInstanceId", "accountId")) put(key, it.optString(key))
            } } ?: JSONObject.NULL)
        }
    }
    fun register(context: Context, options: JSONObject, reply: (JSONObject?, String?) -> Unit) {
        if (permission(context) != "granted") { reply(null, null); return }
        val value = JSONObject().put("deviceId", deviceId(context)).put("accountId", account(context)).put("transport", if (isLocal(context)) "local" else "fcm")
        if (isLocal(context)) { reply(value, null); return }
        val app = firebase(context) ?: run { reply(null, "Firebase client configuration is unavailable."); return }
        val requestedOrigin = selectedOrigin(context)
        val requestedAccount = account(context)
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (selectedOrigin(context) != requestedOrigin || account(context) != requestedAccount) {
                reply(null, "The selected server or account changed."); return@addOnCompleteListener
            }
            if (!task.isSuccessful) reply(null, "Could not register with Google Play services. Check connectivity and try again.")
            else {
                prefs(context).edit().putString("fcmToken", task.result).apply()
                if (options.optInt("encryptionVersion") == 1) {
                    try { FCMRelayEnrollment.begin(context, task.result, options, reply) }
                    catch (error: Exception) { reply(null, error.message ?: "Notification registration failed.") }
                } else reply(value.put("deviceToken", task.result).put("projectId", app.options.projectId), null)
            }
        }
    }
    fun save(context: Context, input: JSONObject) {
        val destination = input.getJSONObject("destination")
        require(input.getString("serverOrigin") == selectedOrigin(context)) { "The selected server changed." }
        require(input.getString("accountId") == account(context) && destination.getString("user_id") == account(context)) { "The account changed." }
        require(destination.getString("device_id") == deviceId(context) && destination.getString("type") == "android_native")
        val local = isLocal(context)
        require(destination.getString("transport") == if (local) "local" else "fcm")
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(destination.getString("id")))
        require(Regex("lva_[A-Za-z0-9_-]{43}").matches(input.getString("credential")))
        require(Regex("lvdi_[A-Za-z0-9_-]{32}").matches(input.getString("serverInstanceId")))
        val stored = JSONObject().apply {
            put("destinationId", destination.getString("id")); put("serverOrigin", input.getString("serverOrigin"))
            put("serverInstanceId", input.getString("serverInstanceId")); put("accountId", input.getString("accountId"))
            put("credential", input.getString("credential")); put("transport", destination.getString("transport"))
            put("deviceToken", input.optString("deviceToken", ""))
        }
        if (!local && input.optString("enrollmentId").isNotBlank()) stored.put("enrollmentId", input.getString("enrollmentId"))
        FCMRelayEnrollment.activate(context, input)
        LocalNotificationTransport.stop()
        NotificationManagerCompat.from(context).cancelAll()
        prefs(context).edit().putString("enrollment", stored.toString()).commit()
        if (local) LocalNotificationTransport.start(context.applicationContext, stored)
    }
    fun current(context: Context, input: JSONObject): Boolean {
        val value = enrollment(context) ?: return false
        val matches = value.optString("destinationId") == input.optString("destinationId")
            && value.optString("serverInstanceId") == input.optString("serverInstanceId")
            && value.optString("serverOrigin") == selectedOrigin(context) && value.optString("accountId") == account(context)
            && (isLocal(context) || if (input.optString("enrollmentId").isNotBlank()) FCMRelayEnrollment.current(context, input)
                else value.optString("enrollmentId").isBlank() && value.optString("deviceToken") == input.optString("deviceToken"))
        if (matches && isLocal(context) && !LocalNotificationTransport.running) LocalNotificationTransport.start(context.applicationContext, value)
        return matches
    }
    fun tokenChanged(context: Context, token: String) {
        prefs(context).edit().putString("fcmToken", token).apply()
        NativeNotificationBridge.emit("lumiverse:android-push-token")
    }
    fun receive(context: Context, data: Map<String, String>) {
        data["relayChallenge"]?.let { FCMRelayEnrollment.challenge(context, it); return }
        val current = enrollment(context) ?: return
        if (current.optString("enrollmentId").isNotBlank()) {
            FCMRelayEnrollment.decrypt(context, data, current)?.let { present(context, it, current) }
            return
        }
        if (current.optString("transport") != "fcm" || data["destinationId"] != current.optString("destinationId")
            || data["serverOrigin"] != current.optString("serverOrigin") || data["serverInstanceId"] != current.optString("serverInstanceId")) return
        val raw = data["payload"] ?: return
        if (raw.length > 8000) return
        try { present(context, JSONObject(raw), current) } catch (_: Exception) { /* Invalid messages are discarded. */ }
    }
    private fun matches(context: Context, value: JSONObject): Boolean {
        val current = enrollment(context) ?: return false
        return current.optString("destinationId") == value.optString("destinationId")
            && current.optString("accountId") == account(context) && current.optString("serverOrigin") == selectedOrigin(context)
    }
    fun present(context: Context, payload: JSONObject, value: JSONObject) {
        if (!matches(context, value) || permission(context) != "granted") return
        val title = payload.optString("title").take(100)
        val body = payload.optString("body").take(500)
        if (title.isBlank()) return
        val data = payload.optJSONObject("data") ?: JSONObject()
        val error = data.optBoolean("error", false)
        val chatId = data.optString("chatId").takeIf { Regex("[A-Za-z0-9_-]{1,128}").matches(it) }
        val key = "${value.optString("destinationId")}:${payload.optString("tag").ifEmpty { UUID.randomUUID().toString() }}"
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            this.data = Uri.parse("lumiverse-notification://${value.optString("destinationId")}/${Uri.encode(key)}")
            putExtra("lumiverse_destination", value.optString("destinationId"))
            putExtra("lumiverse_origin", value.optString("serverOrigin"))
            putExtra("lumiverse_chat", chatId ?: "")
        }
        val tap = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val revision = UUID.randomUUID().toString()
        val builder = NotificationCompat.Builder(context, if (error) ERROR else COMPLETE)
            .setSmallIcon(R.drawable.ic_stat_lumiverse).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(tap)
            .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addExtras(android.os.Bundle().apply { putString("lumiverse_revision", revision) })
        channels(context)
        val manager = NotificationManagerCompat.from(context)
        manager.notify(key, 1, builder.build())
        val path = payload.optString("avatarPath")
        if (!Regex("/api/android-notifications/v1/avatar/[A-Za-z0-9_-]{1,128}/[A-Za-z0-9_-]{1,128}\\?expires=[0-9]+&signature=[A-Za-z0-9_-]{43}").matches(path)) return
        // Both FCM and the local socket call this on their native worker thread.
        // Finish bounded enrichment before returning to FCM's short callback.
        run {
            try {
                val request = Request.Builder().url(value.getString("serverOrigin") + path).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful || response.header("Content-Type")?.substringBefore(';') != "image/jpeg") return@run
                    val stream = response.body?.byteStream() ?: return@run
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 256 * 1024) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    val bytes = output.toByteArray()
                    if (bytes.size > 256 * 1024) return@run
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512) return@run
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@run
                    if (!matches(context, value) || manager.activeNotifications.none {
                        it.tag == key && it.id == 1 && it.notification.extras.getString("lumiverse_revision") == revision
                    }) return@run
                    builder.setLargeIcon(bitmap)
                    if (!error && chatId != null) {
                        val sender = Person.Builder().setName(data.optString("characterName").ifBlank { title })
                            .setIcon(IconCompat.createWithBitmap(bitmap)).setBot(true).build()
                        builder.setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
                            .addMessage(body, System.currentTimeMillis(), sender))
                    }
                    manager.notify(key, 1, builder.build())
                }
            } catch (_: Exception) { /* The text notification is already visible. */ }
        }
    }
    /** Only app-created, still-enrolled notification intents can navigate the selected server. */
    fun consumeTap(context: Context, intent: Intent, origin: String, targetLocal: Boolean = isLocal(context)): String? {
        val value = enrollment(context) ?: return null
        if (intent.getStringExtra("lumiverse_destination") != value.optString("destinationId")
            || intent.getStringExtra("lumiverse_origin") != value.optString("serverOrigin")
            || value.optString("accountId") != account(context)
            || !NotificationPolicy.tapOriginMatches(value.optString("serverOrigin"), origin, isLocal(context), targetLocal)) return null
        intent.removeExtra("lumiverse_destination")
        val chat = intent.getStringExtra("lumiverse_chat") ?: ""
        return NotificationPolicy.chatPath(chat)
    }
}
