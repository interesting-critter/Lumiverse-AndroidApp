package chat.lumiverse.prototype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.PowerManager
import org.json.JSONObject
import org.json.JSONArray

/** Owns the local Bun process independently of any activity or WebView. */
class RuntimeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<RuntimeHost.Listener>()
    private val clients = mutableMapOf<IBinder, Pair<RuntimeHost.Listener, IBinder.DeathRecipient>>()
    private val inbox = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != applicationInfo.uid) return
            val client = message.replyTo ?: return
            when (message.what) {
                ATTACH -> attachClient(client)
                DETACH -> detachClient(client.binder)
                QUERY -> try { client.send(Message.obtain(null, STATE).apply {
                    data = Bundle().apply { putString("state", state().toString()) }
                }) } catch (_: RemoteException) { }
            }
        }
    })
    private var runtime: RuntimeHost? = null
    private var setupUrl: String? = null
    private var backendUrl: String? = null
    private var failure: String? = null
    private var task: JSONObject? = null
    private var foreground = false
    private var timedOut = false
    private var destroyed = false
    private var generation = 0
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var hostingWakeLock: PowerManager.WakeLock
    private var remotePort: Int? = null
    private var remoteUrls = emptyList<String>()
    private val hostTimeout = Runnable {
        if (remotePort != null && backendUrl == null) runtimeFailed("The backend did not become ready. Remote access was turned off; open Lumiverse to try again.")
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { refreshHostingNotification() }
        override fun onLost(network: Network) { refreshHostingNotification() }
    }
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private val stopIdle = Runnable { if (listeners.isEmpty() && !busy() && remotePort == null) stopSelf() }
    private val renewHostingWake = object : Runnable {
        override fun run() {
            if (remotePort != null && !destroyed) {
                hostingWakeLock.acquire(10 * 60 * 1000L)
                handler.postDelayed(this, 5 * 60 * 1000L)
            }
        }
    }
    private val renewWake = object : Runnable {
        override fun run() {
            if (working() && !destroyed && !timedOut) {
                wakeLock.acquire(10 * 60 * 1000L)
                handler.postDelayed(this, 5 * 60 * 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Backup restores", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(HOST_CHANNEL, "Remote access", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lumiverse::BackupRestore")
        wakeLock.setReferenceCounted(false)
        hostingWakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lumiverse::RemoteAccess")
        hostingWakeLock.setReferenceCounted(false)
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().clearCapabilities().build(), networkCallback, handler)
    }

    override fun onBind(intent: Intent): IBinder = inbox.binder

    private fun attachClient(client: Messenger) {
        if (clients.containsKey(client.binder)) return
        fun send(what: Int, key: String, value: String) {
            try { client.send(Message.obtain(null, what).apply { data = Bundle().apply { putString(key, value) } }) }
            catch (_: RemoteException) { detachClient(client.binder) }
        }
        val listener = object : RuntimeHost.Listener {
            override fun ready(url: String) { send(READY, "url", url) }
            override fun backendReady(url: String) { send(BACKEND_READY, "url", url) }
            override fun failed(message: String) { send(FAILED, "message", message) }
            override fun importActivity(activity: JSONObject) { send(IMPORT_STARTED, "activity", activity.toString()) }
        }
        val death = IBinder.DeathRecipient { handler.post { detachClient(client.binder) } }
        try { client.binder.linkToDeath(death, 0) } catch (_: RemoteException) { return }
        clients[client.binder] = listener to death
        attach(listener)
    }

    private fun detachClient(binder: IBinder) {
        val client = clients.remove(binder) ?: return
        binder.unlinkToDeath(client.second, 0)
        detach(client.first)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        failure = null
        if (intent?.action == REMOTE_ON) {
            val port = intent.getIntExtra("port", 7860)
            // A remote client can start a restore after the activity's preflight query.
            // Its existing foreground service must continue without restarting Bun.
            if (busy() && remotePort != port) {
                failure = "Wait for the backup restore to finish before changing remote access."
                listeners.toList().forEach { it.failed(failure!!) }
                return if (remotePort != null) START_REDELIVER_INTENT else START_NOT_STICKY
            }
            try {
                check(port in 1024..65535) { "Choose a port between 1024 and 65535." }
                check(RuntimeHost.hasConfiguredOwner(this)) { "Use this device to create your local account before enabling remote access." }
                val urls = HostingNetwork.urls(this, port)
                check(urls.isNotEmpty()) { "Connect to Wi-Fi, Ethernet, or a VPN before enabling remote access." }
                // Promotion happens before payload extraction or any backend startup work.
                startForeground(HOST_NOTIFICATION, hostingNotification(port, urls, backendUrl != null && remotePort == port),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                if (remotePort != port) {
                    stopRuntime()
                    handler.removeCallbacks(renewHostingWake)
                    remotePort = port
                    remoteUrls = urls
                    handler.removeCallbacks(stopIdle)
                    renewHostingWake.run()
                }
                ensureRuntime()
                if (backendUrl == null) {
                    handler.removeCallbacks(hostTimeout)
                    handler.postDelayed(hostTimeout, 120_000)
                }
            } catch (error: Exception) {
                runtimeFailed(error.message ?: "Could not enable remote access.")
            }
        } else if (intent?.action == REMOTE_OFF || intent?.action == STOP_SERVER) {
            if (remotePort != null) {
                if (busy() && intent.action != STOP_SERVER) {
                    listeners.toList().forEach { it.failed("Wait for the backup restore to finish before changing remote access.") }
                } else if (intent.action == STOP_SERVER) {
                    runtimeFailed("The server was stopped from the notification.")
                } else {
                    stopRuntime()
                    finishWork()
                    finishHosting()
                    if (listeners.isNotEmpty()) ensureRuntime()
                    else stopSelf()
                }
            }
        }
        if (intent?.action == CANCEL) {
            val requested = intent.getStringExtra("jobId")
            if (requested != null && requested == task?.optString("jobId") && busy()) runtime?.cancelImport(requested)
        }
        // Only explicit remote access may recover after system process reclamation.
        // Force-stop and boot still require the user to open the app again.
        return if (remotePort != null) START_REDELIVER_INTENT else START_NOT_STICKY
    }

    private fun attach(listener: RuntimeHost.Listener) {
        handler.removeCallbacks(stopIdle)
        listeners.add(listener)
        failure?.let { listener.failed(it); return }
        ensureRuntime()
        if (backendUrl != null) listener.backendReady(backendUrl!!)
        else if (setupUrl != null) listener.ready(setupUrl!!)
    }

    private fun ensureRuntime() {
        if (runtime == null) {
            timedOut = false
            task = null
            val attempt = ++generation
            runtime = RuntimeHost(applicationContext, "tauri", object : RuntimeHost.Listener {
                override fun ready(url: String) { handler.post {
                    if (!destroyed && attempt == generation) { setupUrl = url; listeners.toList().forEach { it.ready(url) } }
                } }
                override fun backendReady(url: String) { handler.post {
                    if (!destroyed && attempt == generation) {
                        backendUrl = url
                        handler.removeCallbacks(hostTimeout)
                        refreshHostingNotification()
                        listeners.toList().forEach { it.backendReady(url) }
                    }
                } }
                override fun importActivity(activity: JSONObject) { handler.post { if (!destroyed && attempt == generation) updateTask(activity) } }
                override fun failed(message: String) { handler.post {
                    if (!destroyed && attempt == generation) {
                        runtimeFailed(message)
                    }
                } }
            }, remotePort ?: 0, remoteUrls.joinToString(",")).also { it.start() }
        }
    }

    private fun stopRuntime() {
        handler.removeCallbacks(hostTimeout)
        generation++
        runtime?.stop(); runtime = null; setupUrl = null; backendUrl = null
    }

    private fun runtimeFailed(message: String) {
        stopRuntime()
        finishWork()
        finishHosting()
        failure = message
        task?.takeIf { busy() }?.let {
            task = JSONObject(it.toString()).put("status", "interrupted")
            notifications.notify(NOTIFICATION, notification(task!!))
        }
        listeners.toList().forEach { it.failed(message) }
        stopSelf()
    }

    private fun state(): JSONObject {
        val currentUrls = remotePort?.let { HostingNetwork.urls(this, it) }.orEmpty()
        return JSONObject().put("enabled", remotePort != null).put("ready", backendUrl != null)
            .put("port", remotePort ?: 7860).put("busy", busy())
            .put("urls", JSONArray(remoteUrls.filter { it in currentUrls }))
            .put("networkChanged", remotePort != null && currentUrls != remoteUrls)
    }

    private fun refreshHostingNotification() {
        val port = remotePort ?: return
        val currentUrls = HostingNetwork.urls(this, port)
        notifications.notify(HOST_NOTIFICATION, hostingNotification(port, remoteUrls.filter { it in currentUrls }, backendUrl != null, currentUrls != remoteUrls))
    }

    private fun hostingNotification(port: Int, urls: List<String>, ready: Boolean, networkChanged: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 3, Intent(this, RuntimeService::class.java).setAction(STOP_SERVER), PendingIntent.FLAG_IMMUTABLE)
        val text = if (networkChanged) "Network changed. Turn remote access off and on in Lumiverse to update its addresses."
            else if (ready) urls.joinToString(" · ") else "Starting remote access on port $port…"
        return Notification.Builder(this, HOST_CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Lumiverse remote access").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop server", stop).build()).build()
    }

    private fun finishHosting() {
        val wasHosting = remotePort != null
        remotePort = null
        remoteUrls = emptyList()
        handler.removeCallbacks(renewHostingWake)
        if (hostingWakeLock.isHeld) hostingWakeLock.release()
        if (wasHosting) stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(HOST_NOTIFICATION)
    }

    private fun detach(listener: RuntimeHost.Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty() && !busy() && remotePort == null) handler.postDelayed(stopIdle, 30_000)
    }

    private fun busy(): Boolean = task?.optString("status") in setOf("uploading", "queued", "running", "awaiting_ticket")
    private fun working(): Boolean = busy() && task?.optString("status") != "awaiting_ticket"

    private fun notification(activity: JSONObject): Notification {
        val status = activity.optString("status")
        val active = status in setOf("uploading", "queued", "running", "awaiting_ticket")
        val phase = activity.optString("phase")
        val title = when (status) {
            "complete" -> "Backup restored"
            "failed", "interrupted" -> "Backup restore interrupted"
            "cancelled" -> "Backup restore cancelled"
            "awaiting_ticket" -> "Backup restore needs your ticket"
            else -> "Restoring backup"
        }
        val text = when {
            timedOut -> "Android's background time limit was reached. Open Lumiverse to resume."
            status == "complete" -> "Your restored data is ready."
            status == "cancelled" -> "Already restored data has been kept."
            status == "failed" || status == "interrupted" -> "Open Lumiverse to check the restore."
            status == "awaiting_ticket" -> "Open Lumiverse to provide the decryption ticket or skip API keys."
            phase == "uploading" -> "Receiving backup archive…"
            phase == "extracting" -> "Extracting archive…"
            phase == "files" -> "Restoring files…"
            phase == "table" || phase == "table_done" -> "Restoring account data…"
            else -> "Processing archive…"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra(OPEN_RESTORE, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(active).setAutoCancel(!active).setCategory(Notification.CATEGORY_PROGRESS)
        if (active) {
            val bytesTotal = activity.optDouble("bytesTotal", 0.0)
            val total = if (bytesTotal > 0) bytesTotal else activity.optDouble("total", 0.0)
            val done = activity.optDouble(if (bytesTotal > 0) "bytesProcessed" else "processed", 0.0)
            if (status != "awaiting_ticket") builder.setProgress(100, if (total > 0) (100 * done / total).toInt().coerceIn(0, 100) else 0, total <= 0)
            val cancel = PendingIntent.getService(this, 1, Intent(this, RuntimeService::class.java).setAction(CANCEL)
                .putExtra("jobId", activity.optString("jobId")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, "Cancel restore", cancel).build())
        }
        return builder.build()
    }

    private fun updateTask(activity: JSONObject) {
        if (timedOut) return
        val starting = !busy()
        task = activity
        if (busy()) {
            handler.removeCallbacks(stopIdle)
            try {
                val notice = notification(activity)
                if (remotePort != null) {
                    notifications.notify(NOTIFICATION, notice)
                } else if (!foreground) {
                    startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    foreground = true
                } else notifications.notify(NOTIFICATION, notice)
                if (working()) { if (!wakeLock.isHeld) renewWake.run() } else releaseWake()
                if (starting) listeners.toList().forEach { it.importActivity(activity) }
            } catch (error: RuntimeException) {
                // If Android declines foreground execution, stop this work
                // cleanly instead of claiming it is protected in the background.
                runtime?.cancelImport(activity.optString("jobId"))
                finishWork()
                listeners.toList().forEach { it.failed("Android could not keep the restore running: ${error.message}") }
            }
        } else {
            finishWork()
            notifications.notify(NOTIFICATION, notification(activity))
        }
    }

    private fun releaseWake() {
        handler.removeCallbacks(renewWake)
        if (wakeLock.isHeld) wakeLock.release()
    }
    private fun finishWork() {
        releaseWake()
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        if (listeners.isEmpty() && remotePort == null) handler.postDelayed(stopIdle, 30_000)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        generation++
        // The saved active job becomes resumable after this process stops.
        runtime?.stop(); runtime = null; setupUrl = null; backendUrl = null
        finishWork()
        task?.let {
            task = JSONObject(it.toString()).put("status", "interrupted")
            notifications.notify(NOTIFICATION, notification(task!!))
        }
        listeners.toList().forEach { it.failed("Android paused the restore after its background time limit. Reopen Lumiverse to resume.") }
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        generation++
        handler.removeCallbacksAndMessages(null)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        releaseWake()
        finishHosting()
        runtime?.stop(); runtime = null
        clients.forEach { (binder, client) -> binder.unlinkToDeath(client.second, 0) }
        clients.clear()
        listeners.clear()
        super.onDestroy()
    }

    companion object {
        const val ATTACH = 1
        const val DETACH = 2
        const val READY = 3
        const val BACKEND_READY = 4
        const val FAILED = 5
        const val IMPORT_STARTED = 6
        private const val QUERY = 7
        private const val STATE = 8
        const val REMOTE_ON = "chat.lumiverse.prototype.REMOTE_ON"
        const val REMOTE_OFF = "chat.lumiverse.prototype.REMOTE_OFF"
        private const val STOP_SERVER = "chat.lumiverse.prototype.STOP_SERVER"
        const val OPEN_RESTORE = "chat.lumiverse.prototype.OPEN_RESTORE"
        private const val CHANNEL = "lumiverse-backup-restores"
        private const val NOTIFICATION = 23017
        private const val CANCEL = "chat.lumiverse.prototype.CANCEL_RESTORE"
        private const val HOST_CHANNEL = "lumiverse-remote-access"
        private const val HOST_NOTIFICATION = 23018

        /** Read live state across processes without starting an idle service or runtime. */
        @Suppress("DEPRECATION") // Android still exposes the caller's own services for management UI.
        fun query(context: Context, reply: (JSONObject) -> Unit) {
            fun idle() = JSONObject().put("enabled", false).put("ready", false).put("busy", false).put("urls", JSONArray())
            // Binding without AUTO_CREATE can return true for a declared but stopped
            // service and never connect. Avoid adding the full timeout to every cold
            // connection page just to learn that remote hosting is off.
            val running = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == RuntimeService::class.java.name }
            if (!running) { reply(idle()); return }
            val handler = Handler(Looper.getMainLooper())
            var done = false
            var bound = false
            lateinit var connection: ServiceConnection
            fun finish(state: JSONObject) {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                if (bound) context.unbindService(connection)
                reply(state)
            }
            val client = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(message: Message) {
                    if (message.what == STATE) try { finish(JSONObject(message.data.getString("state") ?: "{}")) }
                    catch (_: Exception) { finish(idle()) }
                }
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    try { Messenger(binder).send(Message.obtain(null, QUERY).apply { replyTo = client }) }
                    catch (_: RemoteException) { finish(idle()) }
                }
                override fun onServiceDisconnected(name: ComponentName) { finish(idle()) }
                override fun onNullBinding(name: ComponentName) { finish(idle()) }
                override fun onBindingDied(name: ComponentName) { finish(idle()) }
            }
            try {
                bound = context.bindService(Intent(context, RuntimeService::class.java), connection, 0)
                if (!bound) finish(idle())
                else handler.postDelayed({ finish(idle()) }, 5_000)
            } catch (_: Exception) { finish(idle()) }
        }
    }
}
