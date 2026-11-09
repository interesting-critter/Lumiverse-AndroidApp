package chat.lumiverse.prototype

import android.content.Intent
import android.webkit.URLUtil
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.MimeTypeMap

/** Portable ZIP containers use extensions Android's MIME map does not know. */
internal object NativeFileTypes {
    private fun hasPortableExtension(filename: String) =
        filename.endsWith(".charx", ignoreCase = true) || filename.endsWith(".lumitheme", ignoreCase = true)

    fun exportMimeType(filename: String, requestedMimeType: String): String =
        if (hasPortableExtension(filename)) "application/octet-stream" else requestedMimeType

    fun pickerIntent(params: FileChooserParams): Intent {
        val types = params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .map { if (it.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.substring(1)) ?: "*/*" else it }
            .distinct()
        val accepts = if (types.isEmpty() || "*/*" in types) listOf("*/*") else types
        return params.createIntent().apply {
            type = if (accepts.size == 1) accepts.single() else "*/*"
            removeExtra(Intent.EXTRA_MIME_TYPES)
            if (accepts.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accepts.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun importParams(params: FileChooserParams): FileChooserParams {
        val acceptsCharx = params.acceptTypes.any { value ->
            value.split(',').any { it.trim().equals(".charx", ignoreCase = true) }
        }
        if (!acceptsCharx) return params

        // Providers classify .charx differently (ZIP, binary, or no MIME type).
        // A MIME allowlist cannot express this extension. Let the importer's
        // existing content validation decide whether the selected card is valid.
        // Wrap both paths: Wry rebuilds EXTRA_MIME_TYPES from getAcceptTypes().
        return object : FileChooserParams() {
            override fun getAcceptTypes() = arrayOf("*/*")
            override fun getMode() = params.mode
            override fun getTitle() = params.title
            override fun getFilenameHint() = params.filenameHint
            override fun isCaptureEnabled() = params.isCaptureEnabled
            override fun createIntent() = params.createIntent().apply {
                type = "*/*"
                removeExtra(Intent.EXTRA_MIME_TYPES)
            }
        }
    }

    fun downloadFilename(url: String, disposition: String?, mimeType: String?): String {
        // URLUtil may replace or append an extension based on application/zip.
        // Resolve once without a MIME hint to retain explicit portable filenames.
        val explicit = URLUtil.guessFileName(url, disposition, null)
        return if (hasPortableExtension(explicit)) explicit
            else URLUtil.guessFileName(url, disposition, mimeType)
    }
}
