package chat.lumiverse.prototype

import android.app.AlertDialog
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebView

/** Web permission is scoped to the selected server, independently of Android's app permission. */
class NativeMicrophone(private val activity: MainActivity, private val view: WebView) {
    private var selected: String? = null
    private var pending: PermissionRequest? = null
    private var dialog: AlertDialog? = null
    private val approved = mutableSetOf<String>()

    fun select(url: String?) {
        val next = origin(url)
        if (next != selected) clear()
        selected = next
    }

    fun request(request: PermissionRequest) {
        val origin = origin(request.origin.toString())
        if (!activity.nativeActive || pending != null || !allows(selected, view.url, request.origin.toString()) ||
            !request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) { request.deny(); return }
        pending = request
        val permit: () -> Unit = {
            dialog = null
            activity.requestMicrophonePermission { granted ->
                if (pending === request) {
                    pending = null
                    if (granted && !activity.isFinishing && !activity.isDestroyed && allows(selected, view.url, request.origin.toString())) {
                        approved.add(origin!!)
                        // Never grant the whole request: it may include camera or future resources.
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else request.deny()
                }
            }
        }
        if (origin in approved) permit()
        else dialog = AlertDialog.Builder(activity).setTitle("Allow microphone?")
            .setMessage("$origin wants to use your microphone for dictation or audio recording.")
            .setPositiveButton("Allow") { _, _ -> permit() }
            .setNegativeButton("Cancel") { _, _ -> clear() }
            .setOnCancelListener { clear() }.show()
    }

    fun cancel(request: PermissionRequest) {
        if (pending === request) {
            pending = null
            dialog?.dismiss(); dialog = null
        }
    }

    private fun clear() {
        val request = pending
        pending = null
        dialog?.dismiss(); dialog = null
        request?.deny()
    }

    fun destroy() { clear(); selected = null; approved.clear() }

    companion object {
        private fun origin(url: String?): String? = try {
            val uri = Uri.parse(url ?: "")
            val host = uri.host?.lowercase()
            if (uri.userInfo != null || host.isNullOrBlank() ||
                (uri.scheme != "https" && !(uri.scheme == "http" && host in setOf("127.0.0.1", "localhost", "[::1]", "::1")))) null
            else "${uri.scheme}://$host:${if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80}"
        } catch (_: Exception) { null }

        fun allows(selected: String?, current: String?, requested: String?): Boolean {
            val server = origin(selected) ?: return false
            return server == origin(current) && server == origin(requested)
        }
    }
}
