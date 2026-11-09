package chat.lumiverse.prototype

import android.app.Activity
import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/** A loaded main document can acknowledge only the origin selected natively. */
class NativeConnectionHistory(private val activity: Activity, private val view: WebView) {
    private val preferences get() = activity.getSharedPreferences("lumiverse-connection", Context.MODE_PRIVATE)
    private var pending: String? = null

    private fun read(): ConnectionHistory {
        val saved = try { JSONArray(preferences.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        return ConnectionHistory((0 until saved.length()).map { saved.optString(it) })
    }

    fun entries(): JSONArray = JSONArray().apply {
        read().snapshot().forEach { put(JSONObject().put("server", it)) }
    }

    fun forget(server: String) {
        val history = read()
        history.forget(server)
        preferences.edit().putString("history", JSONArray(history.snapshot()).toString()).apply()
    }

    fun select(server: String?) {
        pending = server
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.removeWebMessageListener(view, "lumiverseConnectionHistory")
        if (server == null) return
        val origin = NativeNotifications.origin(server) ?: return
        // A standard HTTP-to-HTTPS redirect still belongs to the selected connection.
        val origins = setOf(origin, origin.replaceFirst(Regex("^http:"), "https:"))
        WebViewCompat.addWebMessageListener(view, "lumiverseConnectionHistory", origins) { _, message, source, mainFrame, _ ->
            if (!mainFrame || message.data != "ready" || pending != server ||
                NativeNotifications.origin(source.toString()) !in origins || NativeNotifications.origin(view.url) !in origins) return@addWebMessageListener
            pending = null
            val history = read()
            history.remember(server)
            preferences.edit().putString("history", JSONArray(history.snapshot()).toString()).apply()
        }
    }

    fun destroy() { select(null) }
}
