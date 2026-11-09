package chat.lumiverse.prototype

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object FCMRelayEnrollment {
    private val workers = Executors.newFixedThreadPool(2)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(28, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    private fun now() = System.currentTimeMillis() / 1000
    private fun random() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private fun id() = Base64.encodeToString(random(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun request(url: String, method: String, body: JSONObject? = null, credential: String? = null): Pair<Int, JSONObject> {
        val builder = Request.Builder().url(url)
        credential?.let { builder.header("Authorization", "Bearer $it") }
        builder.method(method, body?.toString()?.toRequestBody(jsonType))
        http.newCall(builder.build()).execute().use { response ->
            val stream = response.body?.byteStream()
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            if (stream != null) while (true) {
                val count = stream.read(chunk); if (count < 0) break
                require(buffer.size() + count <= 8192) { "Invalid relay response." }; buffer.write(chunk, 0, count)
            }
            val bytes = buffer.toByteArray()
            require(bytes.size <= 8192) { "Invalid relay response." }
            val value = if (bytes.isEmpty()) JSONObject() else try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
            return response.code to value
        }
    }
    private fun matches(context: Context, value: JSONObject) = value.optString("serverOrigin") == NativeNotifications.selectedOrigin(context)
        && value.optString("accountId") == NativeNotifications.account(context) && !NativeNotifications.isLocal(context)
    private fun publicRegistration(context: Context, value: JSONObject): JSONObject = JSONObject().apply {
        put("deviceId", NativeNotifications.deviceId(context)); put("accountId", value.getString("accountId"))
        put("transport", "fcm"); put("delivery", value.getString("delivery")); put("projectId", value.getString("projectId"))
        put("packageName", value.getString("packageName")); put("enrollmentId", value.getString("id"))
        put("encryption", JSONObject().put("key", value.getString("key")).put("keyId", value.getString("keyId")))
        if (value.getString("delivery") == "relay") put("relayCredential", value.getString("deliveryCredential"))
        else put("deviceToken", value.getString("deviceToken"))
    }
    private fun same(a: JSONObject, b: JSONObject) = listOf("deviceToken", "serverOrigin", "accountId", "serverInstanceId", "delivery", "relayUrl", "projectId", "packageName")
        .all { a.optString(it) == b.optString(it) }
    private fun pendingReply(value: JSONObject) = JSONObject().put("pending", true).put("enrollmentId", value.getString("id"))

    fun begin(context: Context, token: String, options: JSONObject, reply: (JSONObject?, String?) -> Unit) {
        val app = context.applicationContext
        val selected = NativeNotifications.selectedOrigin(app) ?: run { reply(null, "Select a server first."); return }
        val account = NativeNotifications.account(app) ?: run { reply(null, "Sign in before enabling notifications."); return }
        val firebase = NativeNotifications.firebase(app) ?: run { reply(null, "Firebase is unavailable."); return }
        val delivery = options.optString("delivery", "direct")
        val relayUrl = if (delivery == "relay") options.optString("relayUrl") else ""
        val instance = options.optString("serverInstanceId")
        require(delivery == "relay" || delivery == "direct")
        require(Regex("lvdi_[A-Za-z0-9_-]{32}").matches(instance)) { "Invalid server notification identity." }
        if (delivery == "relay") require(NativeNotifications.origin(relayUrl) == relayUrl && relayUrl.startsWith("https://")) { "Use an HTTPS relay origin." }
        val candidate = JSONObject().put("deviceToken", token).put("serverOrigin", selected).put("accountId", account)
            .put("serverInstanceId", instance).put("delivery", delivery).put("relayUrl", relayUrl)
            .put("projectId", firebase.options.projectId).put("packageName", app.packageName)
        workers.execute {
            var replied = false
            try {
                val active = NotificationSecrets.get(app, "active")
                if (active != null && same(active, candidate) && active.optLong("expiresAt") > now() + 7 * 86400 && matches(app, candidate)) {
                    val valid = delivery != "relay" || request(relayUrl + "/v1/registration", "GET", credential = active.getString("managementCredential")).first.let {
                        if (it >= 500 || it == 429) throw IllegalStateException("The notification relay is temporarily unavailable.")
                        it == 200
                    }
                    if (valid) { reply(publicRegistration(app, active), null); return@execute }
                }
                synchronized(this) {
                    check(matches(app, candidate)) { "The selected server or account changed." }
                    val existing = NotificationSecrets.get(app, "pending")
                    if (existing != null && same(existing, candidate) && existing.optLong("pendingExpiresAt") > now()) {
                        reply(if (existing.has("deliveryCredential") || delivery == "direct") publicRegistration(app, existing) else pendingReply(existing), null)
                        return@execute
                    }
                    cancelPending(app)
                    val key = random()
                    candidate.put("id", id()).put("key", Base64.encodeToString(key, Base64.NO_WRAP))
                        .put("keyId", MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }.take(32))
                        .put("pendingExpiresAt", now() + 120).put("expiresAt", now() + 90 * 86400)
                    NotificationSecrets.put(app, "pending", candidate)
                }
                if (delivery == "direct") { reply(publicRegistration(app, candidate), null); return@execute }
                reply(pendingReply(candidate), null); replied = true
                val body = JSONObject().apply {
                    for (name in listOf("id", "deviceToken", "projectId", "packageName", "serverOrigin", "serverInstanceId", "keyId")) put(name, candidate.get(name))
                }
                val (code, response) = request(relayUrl + "/v1/enrollments", "POST", body)
                check(code == 202) { response.optString("error", "Relay verification could not start.") }
                require(response.optString("id") == candidate.getString("id") && Regex("[A-Za-z0-9_-]{43}").matches(response.getString("pollToken")))
                val retained = synchronized(this) {
                    val pending = NotificationSecrets.get(app, "pending")
                    if (pending != null && pending.optString("id") == candidate.getString("id") && matches(app, pending)) {
                        pending.put("pollToken", response.getString("pollToken")); NotificationSecrets.put(app, "pending", pending)
                        true
                    } else false
                }
                if (!retained) request(relayUrl + "/v1/enrollments/" + candidate.getString("id"), "DELETE", credential = response.getString("pollToken"))
            } catch (error: Exception) {
                synchronized(this) {
                    val pending = NotificationSecrets.get(app, "pending")
                    if (pending != null && same(pending, candidate)) {
                        pending.put("error", error.message ?: "Relay enrollment failed."); NotificationSecrets.put(app, "pending", pending)
                    }
                }
                // A reply already sent for relay verification is surfaced through polling.
                if (!replied) reply(null, error.message ?: "Notification registration failed.")
            }
        }
    }
    fun poll(context: Context, enrollmentId: String, reply: (JSONObject?, String?) -> Unit) {
        val app = context.applicationContext
        workers.execute {
            try {
                val pending = synchronized(this) { NotificationSecrets.get(app, "pending") }
                check(pending != null && pending.optString("id") == enrollmentId && matches(app, pending)) { "Notification enrollment was cancelled." }
                check(pending.optLong("pendingExpiresAt") > now()) { "Notification verification expired. Try again." }
                check(!pending.has("error")) { pending.optString("error") }
                if (pending.has("deliveryCredential")) { reply(publicRegistration(app, pending), null); return@execute }
                if (!pending.has("pollToken")) { reply(pendingReply(pending), null); return@execute }
                val (code, value) = request(pending.getString("relayUrl") + "/v1/enrollments/" + enrollmentId, "GET", credential = pending.getString("pollToken"))
                if (code == 202) { reply(pendingReply(pending), null); return@execute }
                check(code == 200 && value.optBoolean("verified")) { value.optString("error", "Relay verification failed.") }
                for (field in listOf("deliveryCredential", "managementCredential")) require(Regex("lvf_[A-Za-z0-9_-]{43}").matches(value.getString(field)))
                require(value.getLong("expiresAt") > now())
                synchronized(this) {
                    check(NotificationSecrets.get(app, "pending")?.optString("id") == enrollmentId && matches(app, pending)) { "The selected server or account changed." }
                    for (field in listOf("deliveryCredential", "managementCredential", "expiresAt")) pending.put(field, value.get(field))
                    NotificationSecrets.put(app, "pending", pending)
                }
                reply(publicRegistration(app, pending), null)
            } catch (error: Exception) { reply(null, error.message ?: "Relay verification failed.") }
        }
    }
    fun challenge(context: Context, raw: String) {
        if (raw.length > 4096) return
        try {
            val proof = JSONObject(raw)
            val pending = synchronized(this) { NotificationSecrets.get(context, "pending") } ?: return
            if (!matches(context, pending) || pending.optString("delivery") != "relay" || proof.optLong("expiresAt") <= now()
                || proof.optLong("expiresAt") > now() + 120 || pending.optLong("pendingExpiresAt") <= now()) return
            if (!listOf("id", "projectId", "packageName", "serverOrigin", "serverInstanceId", "keyId").all { proof.optString(it) == pending.optString(it) }) return
            val challenge = proof.getString("challenge")
            if (!Regex("[A-Za-z0-9_-]{43}").matches(challenge)) return
            request(pending.getString("relayUrl") + "/v1/enrollments/" + pending.getString("id") + "/confirm", "POST", JSONObject().put("challenge", challenge))
        } catch (_: Exception) { /* Pending enrollment can retry; unsolicited proof creates no native state. */ }
    }
    private fun retire(value: JSONObject) {
        if (value.optString("delivery") != "relay") return
        workers.execute { try {
            if (value.has("managementCredential")) request(value.getString("relayUrl") + "/v1/registration", "DELETE", credential = value.getString("managementCredential"))
            else if (value.has("pollToken")) request(value.getString("relayUrl") + "/v1/enrollments/" + value.getString("id"), "DELETE", credential = value.getString("pollToken"))
        } catch (_: Exception) { /* Native state is already revoked. */ } }
    }
    @Synchronized fun cancelPending(context: Context, id: String? = null) {
        val pending = NotificationSecrets.get(context, "pending") ?: return
        if (id != null && pending.optString("id") != id) return
        NotificationSecrets.put(context, "pending", null); retire(pending)
    }
    @Synchronized fun clear(context: Context) {
        cancelPending(context)
        NotificationSecrets.get(context, "active")?.let { retire(it) }
        NotificationSecrets.put(context, "active", null)
    }
    @Synchronized fun activate(context: Context, input: JSONObject) {
        val id = input.optString("enrollmentId")
        if (id.isBlank()) { clear(context); return }
        val value = NotificationSecrets.get(context, "pending")?.takeIf { it.optString("id") == id }
            ?: NotificationSecrets.get(context, "active")?.takeIf { it.optString("id") == id }
            ?: throw IllegalArgumentException("Notification encryption state expired. Try again.")
        val destination = input.getJSONObject("destination")
        require(matches(context, value) && value.getString("serverInstanceId") == input.getString("serverInstanceId"))
        require(value.getString("projectId") == destination.getString("project_id") && value.getString("delivery") == destination.getString("delivery_mode"))
        if (value.getString("delivery") == "relay") require(value.has("managementCredential") && value.optLong("expiresAt") > now())
        val old = NotificationSecrets.get(context, "active")
        value.put("destinationId", destination.getString("id"))
        NotificationSecrets.put(context, "active", value); NotificationSecrets.put(context, "pending", null)
        if (old != null && old.optString("id") != id) retire(old)
    }
    @Synchronized fun current(context: Context, input: JSONObject): Boolean {
        val value = NotificationSecrets.get(context, "active") ?: return false
        return value.optString("id") == input.optString("enrollmentId") && value.optLong("expiresAt") > now() + 7 * 86400
            && value.optString("deviceToken") == context.getSharedPreferences("lumiverse-notifications", Context.MODE_PRIVATE).getString("fcmToken", null)
    }
    @Synchronized fun decrypt(context: Context, data: Map<String, String>, enrollment: JSONObject): JSONObject? = try {
        val value = NotificationSecrets.get(context, "active")
        if (value == null || !matches(context, value) || value.optLong("expiresAt") <= now() || value.optString("id") != data["enrollmentId"]
            || value.optString("destinationId") != enrollment.optString("destinationId")) null
        else {
            val raw = data["lvEncrypted"] ?: ""
            require(raw.toByteArray(Charsets.UTF_8).size <= 3800)
            val envelope = JSONObject(raw)
            require(envelope.getInt("version") == 1 && envelope.getString("keyId") == value.getString("keyId"))
            val aad = NotificationEncryption.associatedData(value.getString("projectId"), value.getString("packageName"), value.getString("id"),
                value.getString("serverOrigin"), value.getString("serverInstanceId"), value.getString("keyId"))
            val clear = JSONObject(NotificationEncryption.decrypt(Base64.decode(value.getString("key"), Base64.NO_WRAP),
                Base64.decode(envelope.getString("nonce"), Base64.NO_WRAP), Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP),
                Base64.decode(envelope.getString("tag"), Base64.NO_WRAP), aad))
            require(clear.getInt("version") == 1 && clear.getLong("issuedAt") <= now() + 60 && clear.getLong("expiresAt") > now()
                && clear.getLong("expiresAt") <= clear.getLong("issuedAt") + 3600 && clear.getLong("issuedAt") >= now() - 3600)
            require(listOf("accountId", "serverOrigin", "serverInstanceId", "destinationId").all { clear.getString(it) == value.getString(it) })
            val messageId = clear.getString("messageId"); require(Regex("[a-f0-9]{32}").matches(messageId))
            val seen = value.optJSONObject("seen") ?: JSONObject()
            require(!seen.has(messageId))
            val keys = seen.keys().asSequence().toList()
            for (key in keys) if (seen.optLong(key) <= now()) seen.remove(key)
            if (seen.length() >= 256) seen.remove(seen.keys().next())
            seen.put(messageId, clear.getLong("expiresAt")); value.put("seen", seen); NotificationSecrets.put(context, "active", value)
            clear.getJSONObject("notification")
        }
    } catch (_: Exception) { null }
}
