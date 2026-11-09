package chat.lumiverse.test

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import org.json.JSONObject

@InvokeArg
class ConnectionArgs {
    var action: String = "info"
    var server: String? = null
    var port: Int? = null
}

@TauriPlugin
class RuntimePlugin(private val activity: Activity) : Plugin(activity) {
    private var runtimeMessenger: Messenger? = null
    private var runtimeClient: Messenger? = null
    private var serviceConnection: ServiceConnection? = null
    private var webView: WebView? = null
    private var downloads: NativeDownloads? = null
    private var browserWindows: NativeBrowserWindows? = null
    private var connectionHistory: NativeConnectionHistory? = null
    private var launcherUrl = "http://tauri.localhost/index.html"
    private var entryHistoryIndex = 0
    private var localAttempt = 0
    private var pendingLocal: Invoke? = null
    private var openingRestore = false
    private var controls: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private val preferences get() = activity.getSharedPreferences("lumiverse-connection", Context.MODE_PRIVATE)

    override fun load(webView: WebView) {
        this.webView = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        browserWindows = NativeBrowserWindows(activity, webView)
        connectionHistory = NativeConnectionHistory(activity, webView)
        installControls(webView)
        downloads = NativeDownloads(activity, webView)
        (activity as? MainActivity)?.let {
            it.connectionWebView = webView
            ConnectionSessionService.onClose = { activity.finish() }
            ConnectionSessionService.onFailure = { message ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    showConnectionPage()
                    AlertDialog.Builder(activity).setTitle("Connection stopped").setMessage(message).setPositiveButton("OK", null).show()
                }
            }
            it.notificationBridge = NativeNotificationBridge(it, webView)
            it.deviceMotion = NativeDeviceMotion(it, webView)
            it.notificationNavigation = { openPendingNotification() }
        }
    }

    @Command
    fun connection(invoke: Invoke) {
        val args = invoke.parseArgs(ConnectionArgs::class.java)
        activity.runOnUiThread {
            try {
                val view = webView ?: throw IllegalStateException("The app is closing.")
                // This command is only exposed to the packaged connection page.
                launcherUrl = view.url?.substringBefore('?') ?: launcherUrl
                when (args.action) {
                    "info" -> RuntimeService.query(activity.applicationContext) { remoteAccess -> invoke.resolve(JSObject().apply {
                        openingRestore = activity.intent.getBooleanExtra(RuntimeService.OPEN_RESTORE, false)
                        if (openingRestore) activity.intent.removeExtra(RuntimeService.OPEN_RESTORE)
                        put("mode", if (openingRestore) "local" else preferences.getString("mode", null))
                        put("server", preferences.getString("server", null))
                        put("history", connectionHistory?.entries())
                        put("remoteAccess", remoteAccess)
                        put("port", preferences.getInt("remote-port", 7860))
                    }) }
                    "remote-access-info" -> RuntimeService.query(activity.applicationContext) { invoke.resolve(JSObject(it.toString())) }
                    "remote-on" -> setRemoteAccess(true, args.port ?: 7860, invoke)
                    "remote-off" -> setRemoteAccess(false, 0, invoke)
                    "close" -> { invoke.resolve(); activity.finish() }
                    "battery-settings" -> {
                        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        invoke.resolve()
                    }
                    "forget" -> {
                        connectionHistory?.forget(ServerAddress.normalize(args.server))
                        invoke.resolve(JSObject().apply { put("history", connectionHistory?.entries()) })
                    }
                    "remote" -> {
                        val server = ServerAddress.normalize(args.server)
                        ConnectionSessionService.start(activity)
                        stopLocal()
                        preferences.edit().putString("mode", "remote").putString("server", server).apply()
                        invoke.resolve()
                        navigate(server, remote = true)
                    }
                    "local" -> startLocal(invoke)
                    else -> invoke.reject("Unknown connection action.")
                }
            } catch (error: Exception) { invoke.reject(error.message ?: "Could not connect.") }
        }
    }

    private fun navigate(url: String, remote: Boolean = false) {
        val view = webView ?: return
        val native = activity as? MainActivity
        val local = !remote && preferences.getString("mode", null) == "local"
        connectionHistory?.select(if (remote) url else null)
        val origin = NativeNotifications.origin(url)
        origin?.let { NativeCharacterWidgets.captureTap(activity, activity.intent, it, local) }
        // Consume before selecting: a restarted embedded backend has a new port.
        // Selection still clears its obsolete credential; the authenticated page reconciles it.
        val target = origin?.let { selected -> NativeNotifications.consumeTap(activity, activity.intent, selected, local)?.let { selected + it } } ?: url
        native?.notificationBridge?.select(url, local)
        native?.deviceMotion?.select(url)
        downloads?.select(url)
        browserWindows?.select(url)
        entryHistoryIndex = view.copyBackForwardList().currentIndex + 1
        view.loadUrl(target)
    }

    private fun openPendingNotification() {
        val view = webView ?: return
        if (activity.intent.getBooleanExtra(RuntimeService.OPEN_RESTORE, false)) {
            // A cold launch first needs the packaged connection page's command.
            if (view.url?.startsWith(launcherUrl) == true) return
            activity.intent.removeExtra(RuntimeService.OPEN_RESTORE)
            openingRestore = true
            startLocal(null)
            return
        }
        val origin = NativeNotifications.origin(view.url) ?: return
        // A cold launch first displays the packaged chooser/setup document.
        // Keep the tap until navigate() has the actual backend origin.
        NativeCharacterWidgets.captureTapFromPage(activity, activity.intent, origin, NativeNotifications.isLocal(activity))
        NativeCharacterWidgets.openPending(activity, view)
        val path = NativeNotifications.consumeTap(activity, activity.intent, origin) ?: return
        val target = origin + path
        if (!NotificationPolicy.isCurrentChat(view.url, target)) view.loadUrl(target)
    }

    private fun startLocal(invoke: Invoke?, remotePort: Int? = null, showFrontend: Boolean = true) {
        if (showFrontend) ConnectionSessionService.start(activity)
        stopLocal()
        pendingLocal = invoke
        val attempt = localAttempt
        val listener = object : RuntimeHost.Listener {
            override fun importActivity(activity: JSONObject) {
                val native = this@RuntimePlugin.activity as? MainActivity ?: return
                if (native.nativeActive && NativeNotifications.permission(native) == "default") native.requestNotificationPermission { }
            }
            override fun ready(url: String) { activity.runOnUiThread {
                if (attempt != localAttempt) return@runOnUiThread
                if (!showFrontend) return@runOnUiThread
                preferences.edit().putString("mode", "local").apply()
                pendingLocal?.resolve(); pendingLocal = null
                // This is the setup server, not the authenticated backend.
                // Preserve pending notification taps until backendReady selects it.
                val view = webView ?: return@runOnUiThread
                browserWindows?.select(null)
                (activity as? MainActivity)?.deviceMotion?.select(null)
                entryHistoryIndex = view.copyBackForwardList().currentIndex + 1
                view.loadUrl(url)
            } }
            override fun backendReady(url: String) { activity.runOnUiThread {
                if (attempt != localAttempt) return@runOnUiThread
                if (!showFrontend) {
                    pendingLocal?.resolve(); pendingLocal = null
                    return@runOnUiThread
                }
                preferences.edit().putString("mode", "local").apply()
                pendingLocal?.resolve(); pendingLocal = null
                val target = if (openingRestore) "$url?restoreImport=1" else url
                openingRestore = false
                navigate(target)
            } }
            override fun failed(message: String) { activity.runOnUiThread {
                if (attempt != localAttempt) return@runOnUiThread
                pendingLocal?.reject(message); pendingLocal = null
                if (!showFrontend) {
                    if (invoke == null && !activity.isFinishing)
                        AlertDialog.Builder(activity).setTitle("Remote access").setMessage(message).setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                // A crash on the runtime page must leave a way to recover.
                if (webView?.url?.startsWith(launcherUrl) == true) {
                    webView?.evaluateJavascript("document.getElementById('status')?.replaceChildren(document.createTextNode(${JSONObject.quote(message)}))", null)
                } else {
                    showConnectionPage()
                    AlertDialog.Builder(activity).setTitle("Local server stopped").setMessage(message)
                        .setPositiveButton("OK", null).show()
                }
            } }
        }
        val client = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (attempt != localAttempt) return
                when (message.what) {
                    RuntimeService.READY -> message.data.getString("url")?.let { listener.ready(it) }
                    RuntimeService.BACKEND_READY -> message.data.getString("url")?.let { listener.backendReady(it) }
                    RuntimeService.FAILED -> message.data.getString("message")?.let { listener.failed(it) }
                    RuntimeService.IMPORT_STARTED -> message.data.getString("activity")?.let { listener.importActivity(JSONObject(it)) }
                }
            }
        })
        runtimeClient = client
        val context = activity.applicationContext
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (attempt != localAttempt) return
                runtimeMessenger = Messenger(binder)
                try { runtimeMessenger?.send(Message.obtain(null, RuntimeService.ATTACH).apply { replyTo = client }) }
                catch (_: RemoteException) { listener.failed("Could not attach to the local server service.") }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (attempt != localAttempt) return
                runtimeMessenger = null
                listener.failed("The local server stopped. Open Lumiverse again to recover the restore.")
            }
        }
        try {
            val intent = Intent(context, RuntimeService::class.java)
            when {
                remotePort != null && remotePort > 0 -> ContextCompat.startForegroundService(context, intent.setAction(RuntimeService.REMOTE_ON).putExtra("port", remotePort))
                remotePort == 0 -> context.startService(intent.setAction(RuntimeService.REMOTE_OFF))
                else -> context.startService(intent)
            }
            check(context.bindService(Intent(context, RuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)) { "Could not connect to the local server service." }
            serviceConnection = connection
        } catch (error: Exception) { listener.failed(error.message ?: "Could not start the local server.") }
    }

    private fun setRemoteAccess(enabled: Boolean, port: Int, invoke: Invoke? = null) {
        fun fail(message: String) {
            if (invoke != null) invoke.reject(message)
            else AlertDialog.Builder(activity).setTitle("Remote access").setMessage(message).setPositiveButton("OK", null).show()
        }
        if (enabled && port !in 1024..65535) { fail("Choose a port between 1024 and 65535."); return }
        if (enabled && !RuntimeHost.hasConfiguredOwner(activity)) {
            fail("Choose Use this device and create your local account before enabling remote access."); return
        }
        RuntimeService.query(activity.applicationContext) { state ->
            if (activity.isFinishing || activity.isDestroyed) { invoke?.reject("The app is closing."); return@query }
            if (state.optBoolean("busy")) { fail("Wait for the backup restore to finish before changing remote access."); return@query }
            if (state.optBoolean("enabled") == enabled && (!enabled || state.optInt("port") == port)) {
                invoke?.resolve(); return@query
            }
            if (enabled && HostingNetwork.urls(activity, port).isEmpty()) {
                fail("Connect to Wi-Fi, Ethernet, or a VPN before enabling remote access."); return@query
            }
            if (enabled) try {
                java.net.ServerSocket().use { it.reuseAddress = true; it.bind(java.net.InetSocketAddress("0.0.0.0", port)) }
            } catch (_: java.io.IOException) { fail("Port $port is unavailable. Choose another port."); return@query }
            // Notification selection can retain the last local origin on the chooser.
            // Only an actually open loopback page should reload after rebinding.
            val showFrontend = NativeNotifications.isLocal(activity) && webView?.url?.startsWith("http://127.0.0.1:") == true
            val begin = {
                if (enabled) preferences.edit().putInt("remote-port", port).apply()
                startLocal(invoke, if (enabled) port else 0, showFrontend)
            }
            val native = activity as? MainActivity
            if (enabled && native != null && NativeNotifications.permission(native) == "default")
                native.requestNotificationPermission { if (!activity.isFinishing && !activity.isDestroyed) begin() else invoke?.reject("The app is closing.") }
            else begin()
        }
    }

    private fun showRemoteAccess() {
        RuntimeService.query(activity.applicationContext) { state ->
            if (activity.isFinishing || activity.isDestroyed) return@query
            val padding = (20 * activity.resources.displayMetrics.density).toInt()
            val enabled = CheckBox(activity).apply { text = "Allow other devices to connect"; isChecked = state.optBoolean("enabled") }
            val port = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((if (state.optBoolean("enabled")) state.optInt("port") else preferences.getInt("remote-port", 7860)).toString())
                contentDescription = "Remote access port"
            }
            val addresses = state.optJSONArray("urls")
            val description = TextView(activity).apply {
                text = buildString {
                    if (addresses != null) for (i in 0 until addresses.length()) append(addresses.getString(i)).append('\n')
                    if (state.optBoolean("networkChanged")) append("Network changed. Turn remote access off and on to update its addresses.\n")
                    append("Sign in with your local Lumiverse account. HTTP is unencrypted. With remote access enabled, Close app leaves the server running. Battery optimization can interrupt access while the phone sleeps.")
                }
                setTextIsSelectable(true)
            }
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, 0)
                addView(enabled); addView(TextView(activity).apply { text = "Port (1024–65535)" }); addView(port); addView(description)
            }
            AlertDialog.Builder(activity).setTitle("Remote access").setView(ScrollView(activity).apply { addView(layout) })
                .setPositiveButton("Apply") { _, _ -> setRemoteAccess(enabled.isChecked, port.text.toString().toIntOrNull() ?: 0) }
                .setNeutralButton("Battery settings") { _, _ -> activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun stopLocal() {
        if (NativeNotifications.isLocal(activity)) LocalNotificationTransport.stop()
        localAttempt += 1
        runtimeClient?.let { client ->
            try { runtimeMessenger?.send(Message.obtain(null, RuntimeService.DETACH).apply { replyTo = client }) }
            catch (_: RemoteException) { /* the service has already stopped */ }
        }
        serviceConnection?.let { activity.applicationContext.unbindService(it) }
        runtimeMessenger = null; runtimeClient = null; serviceConnection = null
        pendingLocal?.reject("Connection changed."); pendingLocal = null
    }

    private fun showConnectionPage(history: Boolean = false) {
        ConnectionSessionService.stop(activity)
        stopLocal()
        NativeNotifications.clear(activity)
        NativeCharacterWidgets.select(activity, null, false)
        navigate("$launcherUrl?changeServer=1" + if (history) "&history=1" else "")
    }

    private fun showControls() {
        if (controls?.isShowing == true || activity.isFinishing) return
        controls = AlertDialog.Builder(activity).setTitle("Lumiverse")
            .setItems(arrayOf("Reload", "Change server", "Connection history", "Remote access", "Check for app updates", "Close app")) { _, which ->
                when (which) {
                    0 -> webView?.reload()
                    1 -> showConnectionPage()
                    2 -> showConnectionPage(history = true)
                    3 -> showRemoteAccess()
                    4 -> (activity as? MainActivity)?.appUpdates?.check(manually = true)
                    5 -> activity.finish()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installControls(view: WebView) {
        // Native controls also work on a failed/offline page. Android Back is
        // the accessible alternative to the optional two-finger gesture.
        val owner = activity as? AppCompatActivity
        owner?.onBackPressedDispatcher?.addCallback(owner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (view.canGoBack() && view.copyBackForwardList().currentIndex > entryHistoryIndex) view.goBack()
                    else showControls()
                }
            })
        var startX = 0f
        var startY = 0f
        var secondX = 0f
        var secondY = 0f
        val hold = Runnable { showControls() }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2) {
                startX = event.getX(0); startY = event.getY(0)
                secondX = event.getX(1); secondY = event.getY(1)
                handler.removeCallbacks(hold)
                handler.postDelayed(hold, 650)
            } else if (event.pointerCount != 2 || event.actionMasked != MotionEvent.ACTION_MOVE
                || kotlin.math.abs(event.getX(0) - startX) > 18 * activity.resources.displayMetrics.density
                || kotlin.math.abs(event.getY(0) - startY) > 18 * activity.resources.displayMetrics.density
                || kotlin.math.abs(event.getX(1) - secondX) > 18 * activity.resources.displayMetrics.density
                || kotlin.math.abs(event.getY(1) - secondY) > 18 * activity.resources.displayMetrics.density) {
                handler.removeCallbacks(hold)
            }
            false
        }
    }

    override fun onPause() { handler.removeCallbacksAndMessages(null) }

    override fun onDestroy(activity: AppCompatActivity) {
        handler.removeCallbacksAndMessages(null)
        controls?.dismiss()
        browserWindows?.destroy(); browserWindows = null
        connectionHistory?.destroy(); connectionHistory = null
        downloads?.destroy(); downloads = null
        stopLocal()
        (activity as? MainActivity)?.let {
            it.deviceMotion?.destroy(); it.deviceMotion = null
            it.notificationBridge?.destroy(); it.notificationBridge = null; it.notificationNavigation = null
        }
        webView = null
    }
}
