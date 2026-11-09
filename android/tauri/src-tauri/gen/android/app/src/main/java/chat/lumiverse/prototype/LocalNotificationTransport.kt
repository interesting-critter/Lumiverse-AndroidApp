package chat.lumiverse.prototype

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** App-owned socket stays independent of the WebView; it does not extend backend lifetime. */
object LocalNotificationTransport {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    @Volatile var connected = false; private set
    @Volatile var running = false; private set
    @Volatile private var generation = 0
    private var socket: WebSocket? = null
    fun stop() { generation++; running = false; connected = false; socket?.cancel(); socket = null; handler.removeCallbacksAndMessages(null) }
    fun start(context: Context, enrollment: JSONObject) {
        stop()
        running = true
        val attempt = generation
        connect(context, enrollment, attempt)
    }
    private fun connect(context: Context, enrollment: JSONObject, attempt: Int) {
        executor.execute {
            if (attempt != generation) return@execute
            try {
                val origin = enrollment.getString("serverOrigin")
                require(NativeNotifications.isLocal(context) && NativeNotifications.selectedOrigin(context) == origin)
                require(java.net.URI(origin).host in listOf("127.0.0.1", "localhost", "[::1]"))
                // The private ticket endpoint checks server identity and revocation.
                val request = Request.Builder().url("$origin/api/android-notifications/v1/ticket")
                    .header("Authorization", "Bearer ${enrollment.getString("credential")}").post(ByteArray(0).toRequestBody()).build()
                val ticket = client.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        if (attempt == generation) NativeNotifications.clear(context)
                        return@execute
                    }
                    check(response.isSuccessful)
                    val result = JSONObject(response.body!!.string())
                    check(result.getString("serverInstanceId") == enrollment.getString("serverInstanceId"))
                    result.getString("ticket")
                }
                if (attempt != generation) return@execute
                val wsUrl = origin.replaceFirst("http", "ws") + "/api/ws?notificationTicket=" + java.net.URLEncoder.encode(ticket, "UTF-8")
                val newSocket = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (attempt != generation || text.length > 16384) return
                        try {
                            val message = JSONObject(text)
                            when (message.optString("event")) {
                                "CONNECTED" -> {
                                    check(message.getJSONObject("payload").getString("destinationId") == enrollment.getString("destinationId"))
                                    socket = webSocket
                                    connected = true
                                    heartbeat(webSocket, attempt)
                                }
                                "DESKTOP_NOTIFICATION" -> NativeNotifications.present(context, message.getJSONObject("payload"), enrollment)
                            }
                        } catch (_: Exception) { /* Invalid frames do not reach the renderer. */ }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { retry(context, enrollment, attempt) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { retry(context, enrollment, attempt) }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                })
                if (attempt == generation) socket = newSocket else newSocket.cancel()
            } catch (_: Exception) { retry(context, enrollment, attempt) }
        }
    }
    private fun retry(context: Context, enrollment: JSONObject, attempt: Int) {
        if (attempt != generation) return
        connected = false
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (attempt == generation) connect(context, enrollment, attempt) }, 5000)
    }
    private fun heartbeat(webSocket: WebSocket, attempt: Int) {
        if (attempt != generation || !connected || socket !== webSocket) return
        webSocket.send("{\"type\":\"ping\"}")
        handler.postDelayed({ heartbeat(webSocket, attempt) }, 25000)
    }
}
