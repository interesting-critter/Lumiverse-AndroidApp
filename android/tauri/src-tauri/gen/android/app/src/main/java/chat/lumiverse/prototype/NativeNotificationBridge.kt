package chat.lumiverse.test

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Origin and main-frame checks are enforced natively, independently of page JavaScript. */
class NativeNotificationBridge(private val activity: MainActivity, private val view: WebView) {
    private var selected: String? = null
    companion object {
        private var active: WeakReference<NativeNotificationBridge>? = null
        fun widgetsUpdated() { active?.get()?.let { bridge -> bridge.activity.runOnUiThread {
            NativeCharacterWidgets.openPending(bridge.activity, bridge.view)
        } } }
        fun emit(name: String) { active?.get()?.let { bridge -> bridge.activity.runOnUiThread {
            if (NativeNotifications.origin(bridge.view.url) == bridge.selected)
                bridge.view.evaluateJavascript("window.dispatchEvent(new Event(${JSONObject.quote(name)}))", null)
        } } }
    }
    init { active = WeakReference(this) }
    fun select(url: String, local: Boolean) {
        val origin = NativeNotifications.origin(url) ?: return
        NativeNotifications.select(activity, origin, local)
        NativeCharacterWidgets.select(activity, origin, local)
        if (selected == origin) return
        selected = origin
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.removeWebMessageListener(view, "lumiverseNotifications")
        WebViewCompat.addWebMessageListener(view, "lumiverseNotifications", setOf(origin)) { _, message, source, mainFrame, proxy ->
            if (!mainFrame || NativeNotifications.origin(source.toString()) != selected || NativeNotifications.origin(view.url) != selected) return@addWebMessageListener
            try {
                val raw = message.data ?: ""
                if (raw.length > 16384) return@addWebMessageListener
                val request = JSONObject(raw)
                val id = request.getString("id")
                if (id.length > 64) return@addWebMessageListener
                val requestOrigin = selected
                fun reply(value: Any?, error: String? = null) {
                    activity.runOnUiThread {
                        if (selected == requestOrigin && NativeNotifications.origin(view.url) == requestOrigin)
                            proxy.postMessage(JSONObject().put("id", id).put("result", value ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL).toString())
                    }
                }
                val payload = request.optJSONObject("payload") ?: JSONObject()
                try {
                    when (request.getString("action")) {
                        "widgets.status" -> reply(NativeCharacterWidgets.status(activity))
                        "widgets.configure" -> {
                            NativeCharacterWidgets.configure(activity, payload)
                            reply(NativeCharacterWidgets.status(activity))
                            NativeCharacterWidgets.openPending(activity, view)
                        }
                        "widgets.refresh" -> { CharacterWidgetWorker.request(activity); reply(true) }
                        "widgets.invalidate" -> { NativeCharacterWidgets.invalidate(activity); reply(true) }
                        "notifications.status" -> { lifecycle(!activity.isFinishing && activity.nativeActive); reply(NativeNotifications.status(activity)) }
                        "notifications.account" -> {
                            NativeNotifications.setAccount(activity, payload.optString("accountId").takeUnless { it.isBlank() || it == "null" })
                            reply(true)
                        }
                        "notifications.register" -> activity.requestNotificationPermission { granted ->
                            if (granted && selected == requestOrigin) NativeNotifications.register(activity, payload) { result, error -> reply(result, error) }
                            else reply(null)
                        }
                        "notifications.poll" -> FCMRelayEnrollment.poll(activity, payload.getString("enrollmentId")) { result, error -> reply(result, error) }
                        "notifications.cancel" -> { FCMRelayEnrollment.cancelPending(activity, payload.optString("enrollmentId").takeIf { it.isNotBlank() }); reply(true) }
                        "notifications.current" -> reply(NativeNotifications.current(activity, payload))
                        "notifications.save" -> { NativeNotifications.save(activity, payload); reply(true) }
                        "notifications.clear" -> {
                            val idToClear = payload.optString("destinationId")
                            if (idToClear.isBlank() || NativeNotifications.enrollment(activity)?.optString("destinationId") == idToClear) NativeNotifications.clear(activity)
                            reply(true)
                        }
                        else -> reply(null, "Unknown native notification action.")
                    }
                } catch (error: Exception) { reply(null, error.message ?: "Native notification request failed.") }
            } catch (_: Exception) { /* Unstructured messages have no reply capability. */ }
        }
    }
    fun lifecycle(active: Boolean) {
        if (NativeNotifications.origin(view.url) != selected) return
        val state = if (active) "active" else "background"
        val protected = ConnectionSessionService.active
        view.evaluateJavascript("window.__LUMIVERSE_ANDROID_ACTIVE__=$active; window.__LUMIVERSE_ANDROID_BACKGROUND_CONNECTION__=$protected; window.dispatchEvent(new CustomEvent('lumiverse:android-app-state',{detail:{state:'$state'}}));", null)
    }
    fun resume() { lifecycle(true); emit("lumiverse:android-resume") }
    fun destroy() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(view, "lumiverseNotifications")
        if (active?.get() === this) active = null
    }
}
