package chat.lumiverse.prototype

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Real child browsing contexts preserve window.open/close and opener semantics
 * for extensions and SSO without navigating away from the Lumiverse WebView. */
class NativeBrowserWindows(private val activity: Activity, private val main: WebView) {
    private val original = main.webChromeClient
    private val windows = linkedMapOf<WebView, Dialog>()
    private val microphone = (activity as? MainActivity)?.let { NativeMicrophone(it, main) }

    fun select(url: String?) { microphone?.select(url) }

    init {
        main.settings.setSupportMultipleWindows(true)
        // Extensions can obtain the link asynchronously before window.open.
        main.settings.javaScriptCanOpenWindowsAutomatically = true
        main.webChromeClient = Chrome(original)
    }

    private fun create(resultMsg: Message): Boolean {
        if (activity.isFinishing || activity.isDestroyed || windows.size >= 4) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        // Plain WebViews intentionally have no Tauri IPC or native JS bridges.
        val child = WebView(activity)
        child.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        val dialog = Dialog(activity)
        val address = TextView(activity).apply {
            text = "Authorization"
            setPadding(16, 8, 16, 8)
            setTextIsSelectable(true)
            maxLines = 2
        }
        child.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.scheme !in listOf("http", "https") && request.url.toString() != "about:blank"
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // Display the current origin, never OAuth query parameters.
                val uri = Uri.parse(url)
                address.text = if (uri.host != null) "${uri.scheme}://${uri.authority}" else "Authorization"
            }
        }
        child.webChromeClient = Chrome(null)
        val close = Button(activity).apply {
            text = "Close"
            contentDescription = "Close browser window"
            setOnClickListener { dialog.dismiss() }
        }
        val toolbar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar)
            addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        dialog.setContentView(content)
        dialog.setOnDismissListener {
            windows.remove(child)
            child.stopLoading()
            (child.parent as? ViewGroup)?.removeView(child)
            child.destroy()
        }
        windows[child] = dialog
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        transport.webView = child
        resultMsg.sendToTarget()
        return true
    }

    fun destroy() {
        microphone?.destroy()
        windows.values.toList().asReversed().forEach { it.dismiss() }
        main.webChromeClient = original
    }

    // Keep Wry's capture, permission, dialog, fullscreen and title handlers.
    // MainActivity owns import callbacks so cancelled/failed launches cannot stick.
    private inner class Chrome(private val delegate: WebChromeClient?) : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message) = create(resultMsg)
        override fun onCloseWindow(window: WebView) { windows[window]?.dismiss() }
        override fun onReceivedTitle(view: WebView, title: String) { delegate?.onReceivedTitle(view, title) }
        override fun onProgressChanged(view: WebView, progress: Int) { delegate?.onProgressChanged(view, progress) }
        override fun onConsoleMessage(message: ConsoleMessage) = delegate?.onConsoleMessage(message) ?: false
        override fun onShowFileChooser(view: WebView, reply: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            if (delegate == null) return false
            if (!params.isCaptureEnabled && activity is MainActivity) return activity.filePicker.open(reply, params)
            return delegate.onShowFileChooser(view, reply, NativeFileTypes.importParams(params))
        }
        override fun onPermissionRequest(request: PermissionRequest) {
            if (delegate != null && request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                if (microphone != null) microphone.request(request) else request.deny()
            } else if (delegate != null) delegate.onPermissionRequest(request) else request.deny()
        }
        override fun onPermissionRequestCanceled(request: PermissionRequest) { microphone?.cancel(request); delegate?.onPermissionRequestCanceled(request) }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (delegate != null) delegate.onGeolocationPermissionsShowPrompt(origin, callback) else callback.invoke(origin, false, false)
        }
        override fun onGeolocationPermissionsHidePrompt() { delegate?.onGeolocationPermissionsHidePrompt() }
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (delegate != null) delegate.onShowCustomView(view, callback) else callback.onCustomViewHidden()
        }
        override fun onHideCustomView() { delegate?.onHideCustomView() }
        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult) =
            delegate?.onJsAlert(view, url, message, result) ?: false
        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult) =
            delegate?.onJsConfirm(view, url, message, result) ?: false
        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult) =
            delegate?.onJsPrompt(view, url, message, defaultValue, result) ?: false
    }
}
