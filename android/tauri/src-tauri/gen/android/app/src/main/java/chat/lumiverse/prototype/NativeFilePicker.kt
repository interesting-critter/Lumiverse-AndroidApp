package chat.lumiverse.prototype

import android.app.Activity
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.result.contract.ActivityResultContracts

/** Owns and clears every WebView callback, including cancellation and launch failures. */
class NativeFilePicker(private val activity: MainActivity) {
    private var pending: ValueCallback<Array<Uri>>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val reply = pending
        pending = null
        val data = result.data
        val files = if (result.resultCode != Activity.RESULT_OK) null
            else data?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                ?: data?.data?.let { arrayOf(it) }
        reply?.onReceiveValue(files)
    }

    fun open(reply: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        pending?.onReceiveValue(null)
        pending = reply
        try {
            check(!activity.isFinishing && !activity.isDestroyed) { "The app is closing." }
            launcher.launch(NativeFileTypes.pickerIntent(params))
        } catch (_: RuntimeException) {
            pending = null
            reply.onReceiveValue(null)
        }
        return true
    }

    fun destroy() { val reply = pending; pending = null; reply?.onReceiveValue(null) }
}
