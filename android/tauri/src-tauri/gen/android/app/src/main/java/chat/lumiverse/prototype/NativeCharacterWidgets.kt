package chat.lumiverse.test

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Shared by both providers and the origin-restricted native message bridge. */
object NativeCharacterWidgets {
    private fun prefs(context: Context) = context.getSharedPreferences("lumiverse-widget-selection", Context.MODE_PRIVATE)
    private data class Tap(val origin: String, val account: String, val raw: String)
    private var pending: Tap? = null
    private var confirmedAccount: String? = null
    private val revocations = Executors.newSingleThreadExecutor()
    fun selected(context: Context) = prefs(context).getString("origin", null)
    fun select(context: Context, url: String?, local: Boolean) {
        confirmedAccount = null
        val origin = NativeNotifications.origin(url)
        if (selected(context) != origin || prefs(context).getBoolean("local", false) != local) clear(context)
        prefs(context).edit().putString("origin", origin).putBoolean("local", local).commit()
        if (pending?.origin != origin) pending = null
    }
    private fun deviceId(context: Context): String = prefs(context).getString("deviceId", null)
        ?: UUID.randomUUID().toString().also { prefs(context).edit().putString("deviceId", it).commit() }
    fun status(context: Context): JSONObject {
        val active = CharacterWidgetStore.current(context)
        return JSONObject().put("available", true).put("deviceId", deviceId(context)).put("needsEnrollment",
            active == null || active.optString("serverOrigin") != selected(context)
                || active.optString("accountId") != prefs(context).getString("account", null)
                || active.optLong("expiresAt") <= System.currentTimeMillis() + 7L * 24 * 3600 * 1000)
    }
    fun configure(context: Context, input: JSONObject) {
        val account = input.optString("accountId").takeUnless { it.isBlank() || it == "null" }
        val origin = selected(context)
        require(input.optString("serverOrigin") == origin) { "The selected server changed." }
        val previous = CharacterWidgetStore.current(context)
        if (previous != null && (previous.optString("accountId") != account || previous.optString("serverOrigin") != origin)) clear(context)
        prefs(context).edit().putString("account", account).commit()
        confirmedAccount = account
        if (account == null || origin == null) { pending = null; clear(context); return }
        val palette = input.optJSONObject("palette") ?: JSONObject()
        for ((key, fallback) in listOf("background" to "#171321", "foreground" to "#f5f0ff", "accent" to "#bca6ff")) {
            if (!Regex("#[0-9a-fA-F]{6}").matches(palette.optString(key))) palette.put(key, fallback)
        }
        val enrollment = input.optJSONObject("enrollment")
        if (enrollment != null) {
            require(enrollment.getString("accountId") == account && CharacterWidgetLink.identifier(enrollment.getString("registrationId")))
            require(Regex("[A-Za-z0-9_-]{43}").matches(enrollment.getString("credential")))
            require(enrollment.getLong("expiresAt") in (System.currentTimeMillis() + 1)..(System.currentTimeMillis() + 91L * 24 * 3600 * 1000))
            if (previous?.optString("registrationId") != enrollment.getString("registrationId")) CharacterWidgetStore.invalidate(context, true)
            CharacterWidgetStore.activate(context, JSONObject(enrollment.toString()).put("serverOrigin", origin)
                .put("palette", palette).put("local", prefs(context).getBoolean("local", false)))
        } else CharacterWidgetStore.current(context)?.let { active ->
            if (active.optJSONObject("palette")?.toString() != palette.toString()) CharacterWidgetStore.activate(context, active.put("palette", palette))
        }
        CharacterWidgets.renderAll(context)
        CharacterWidgetWorker.schedule(context)
    }
    fun clear(context: Context) {
        val old = CharacterWidgetStore.current(context)
        CharacterWidgetStore.invalidate(context, true)
        CharacterWidgets.renderAll(context)
        if (old != null) revocations.execute { CharacterWidgetWorker.revoke(old) }
    }
    fun invalidate(context: Context) {
        CharacterWidgetStore.invalidate(context)
        CharacterWidgets.renderAll(context)
    }
    /** Capture before select() retires an enrollment whose bundled-server port changed. */
    fun captureTapFromPage(context: Context, intent: Intent, origin: String, local: Boolean) {
        // The packaged chooser and setup pages must leave a cold-launch tap intact.
        if (origin == selected(context)) captureTap(context, intent, origin, local)
    }
    fun captureTap(context: Context, intent: Intent, origin: String, local: Boolean) {
        val raw = intent.getStringExtra("lumiverse_widget") ?: return
        intent.removeExtra("lumiverse_widget")
        val active = CharacterWidgetStore.current(context) ?: return
        if (!NotificationPolicy.tapOriginMatches(active.optString("serverOrigin"), origin, active.optBoolean("local"), local)) return
        val snapshot = CharacterWidgetStore.snapshot(context, active) ?: return
        if (CharacterWidgetLink.destination(raw, active.getString("registrationId"), snapshot) == null) return
        pending = Tap(origin, active.getString("accountId"), raw)
    }
    fun openPending(context: Context, view: WebView) {
        val origin = NativeNotifications.origin(view.url) ?: return
        val path = pendingDestination(context, origin) ?: return
        val target = origin + path
        if (!NotificationPolicy.isCurrentChat(view.url, target)) view.loadUrl(target)
    }
    internal fun pendingDestination(context: Context, origin: String): String? {
        val tap = pending ?: return null
        if (origin != tap.origin || selected(context) != tap.origin) return null
        val account = confirmedAccount ?: return null
        if (account != tap.account) { pending = null; return null }
        val active = CharacterWidgetStore.current(context) ?: return null
        val snapshot = CharacterWidgetStore.snapshot(context, active) ?: return null
        // A fresh local enrollment has a new ID. Keep the collection/character,
        // then resolve it against the newly authenticated account's snapshot.
        val raw = tap.raw.replaceFirst(Regex("^lumiverse://widget/[^/]+/"), "lumiverse://widget/${active.getString("registrationId")}/")
        val path = CharacterWidgetLink.destination(raw, active.getString("registrationId"), snapshot)
        pending = null
        return path
    }
}
