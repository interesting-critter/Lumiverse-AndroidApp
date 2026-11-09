package chat.lumiverse.prototype

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** Gyro/accelerometer fusion, scoped to one selected main-frame document. */
class NativeDeviceMotion(private val activity: MainActivity, private val view: WebView) {
    private val manager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private var selected: String? = null
    private var documentID: String? = null
    private var id: String? = null
    private var listener: SensorEventListener? = null
    private var generation = 0
    private var pendingDelivery = false
    private var reset = true
    private var animationsEnabled = readAnimationPreference()
    private val animationObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // ValueAnimator's cached scale can update after this observer.
            animationsEnabled = readAnimationPreference()
            updateActivity()
        }
    }

    init {
        activity.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationObserver)
    }

    fun select(url: String?) {
        stop()
        documentID = null
        selected = NativeNotifications.origin(url)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.removeWebMessageListener(view, "lumiverseDeviceMotion")
        val origin = selected ?: return
        WebViewCompat.addWebMessageListener(view, "lumiverseDeviceMotion", setOf(origin)) { _, message, source, mainFrame, proxy ->
            if (!mainFrame || selected != origin || NativeNotifications.origin(source.toString()) != origin ||
                NativeNotifications.origin(view.url) != origin) return@addWebMessageListener
            try {
                val raw = message.data ?: return@addWebMessageListener
                if (raw.length > 2048) return@addWebMessageListener
                val request = JSONObject(raw)
                val requestID = request.getString("id")
                val page = request.getString("documentID")
                if (requestID.length > 64 || page.isBlank() || page.length > 100) return@addWebMessageListener
                val action = request.getString("action")
                if (action == "motion.document") {
                    stop()
                    documentID = page
                    return@addWebMessageListener
                }
                if (documentID != page) return@addWebMessageListener
                val payload = request.optJSONObject("payload") ?: JSONObject()
                val result: Any = when (action) {
                    "motion.start" -> JSONObject().put("available", start(payload.optString("id")))
                    "motion.stop" -> { stop(payload.optString("id").takeIf { it.isNotBlank() }); true }
                    else -> throw IllegalArgumentException("Unknown device motion action.")
                }
                proxy.postMessage(JSONObject().put("id", requestID).put("result", result).toString())
            } catch (_: Exception) { /* Ignore malformed or stale requests. */ }
        }
    }

    private fun start(streamID: String): Boolean {
        stop()
        if (streamID.isBlank() || streamID.length > 100 || documentID == null || !isSelectedPage() ||
            manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null ||
            (manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) == null &&
                manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null)) return false
        id = streamID
        return updateActivity()
    }

    fun stop(streamID: String? = null) {
        if (streamID != null && streamID != id) return
        id = null
        pause()
    }

    private fun pause() {
        generation++
        listener?.let { manager.unregisterListener(it) }
        listener = null
        pendingDelivery = false
    }

    private fun isSelectedPage() = selected != null && NativeNotifications.origin(view.url) == selected

    private fun readAnimationPreference(): Boolean = try {
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: RuntimeException) { ValueAnimator.areAnimatorsEnabled() }

    fun updateActivity(active: Boolean = activity.nativeActive): Boolean {
        if (!active || activity.isFinishing || activity.isDestroyed || !isSelectedPage() ||
            !animationsEnabled || id == null) {
            pause()
            return id != null
        }
        if (listener != null) return true
        val currentGeneration = ++generation
        val currentID = id
        val page = documentID
        reset = true
        val next = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                if (generation != currentGeneration || id != currentID || documentID != page) return
                if (!activity.nativeActive || !isSelectedPage() || !animationsEnabled) { pause(); return }
                if (pendingDelivery) return
                val q = quaternion(event.values) ?: return
                val detail = JSONObject().put("id", currentID).put("reset", reset).put("quaternion", q)
                reset = false
                pendingDelivery = true
                // One outstanding delivery bounds WebView work when rendering is busy.
                // The document token also protects a same-origin page during navigation.
                val script = "if(window.__LUMIVERSE_ANDROID_MOTION__?.documentID===${JSONObject.quote(page)}) {" +
                    "window.dispatchEvent(new CustomEvent('lumiverse:android-device-motion',{detail:$detail}));true;}else{false;}"
                view.evaluateJavascript(script) { delivered ->
                    if (generation == currentGeneration) {
                        pendingDelivery = false
                        if (delivered != "true") stop()
                    }
                }
            }
        }
        // Prefer relative gyro fusion without compass jumps. Older devices may
        // expose only the normal rotation vector, which still uses gyro fusion.
        for (type in intArrayOf(Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR)) {
            val sensor = manager.getDefaultSensor(type) ?: continue
            try {
                if (manager.registerListener(next, sensor, 16_667, handler)) {
                    listener = next
                    return true
                }
            } catch (_: RuntimeException) { /* Try the other fused sensor. */ }
        }
        val detail = JSONObject().put("id", currentID).put("available", false)
        view.evaluateJavascript("if(window.__LUMIVERSE_ANDROID_MOTION__?.documentID===${JSONObject.quote(page)})" +
            "window.dispatchEvent(new CustomEvent('lumiverse:android-device-motion',{detail:$detail}));", null)
        stop()
        return false
    }

    fun destroy() {
        stop()
        selected = null
        documentID = null
        activity.contentResolver.unregisterContentObserver(animationObserver)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.removeWebMessageListener(view, "lumiverseDeviceMotion")
    }

    companion object {
        fun quaternion(values: FloatArray): JSONObject? {
            if (values.size < 3 || values.take(4).any { !it.isFinite() }) return null
            val q = FloatArray(4)
            SensorManager.getQuaternionFromVector(q, values) // Android returns [w, x, y, z].
            val length = kotlin.math.sqrt(q.sumOf { it.toDouble() * it })
            if (!length.isFinite() || length < 0.5 || length > 1.5) return null
            return JSONObject().put("x", q[1] / length).put("y", q[2] / length)
                .put("z", q[3] / length).put("w", q[0] / length)
        }
    }
}
