package chat.lumiverse.test

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Uses shared Downloads, with a persisted, user-granted directory on devices
 * whose MediaProvider refuses to open an app-created download. */
class NativeExportStorage(private val activity: Activity) {
    data class Target(val uri: Uri, val output: OutputStream, val mediaStore: Boolean, val location: String)
    private val resolver = activity.contentResolver
    private val preferences = activity.getSharedPreferences("lumiverse-exports", Context.MODE_PRIVATE)
    private val stalePending = preferences.getStringSet("pending", emptySet()).orEmpty().toSet()
    private val lock = Any()
    private var folderRequest: CompletableFuture<Uri>? = null
    @Volatile private var destroyed = false

    fun recoverPending() {
        synchronized(lock) {
            for (uri in stalePending) {
                runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(uri)) }
            }
            val remaining = preferences.getStringSet("pending", emptySet()).orEmpty() - stalePending
            preferences.edit().putStringSet("pending", remaining).commit()
        }
    }

    private fun pending(uri: Uri, add: Boolean) = synchronized(lock) {
        val values = preferences.getStringSet("pending", emptySet()).orEmpty().toMutableSet()
        if (add) values.add(uri.toString()) else values.remove(uri.toString())
        check(preferences.edit().putStringSet("pending", values).commit()) { "Could not record the pending export." }
    }

    fun create(filename: String, requestedMimeType: String): Target {
        check(!destroyed) { "The app is closing." }
        // Android's providers append .zip to unrecognized portable extensions
        // when the server sends application/zip. Preserve the portable filename.
        val mimeType = NativeFileTypes.exportMimeType(filename, requestedMimeType)
        val failure: Exception
        val saved = preferences.getString("folder", null)?.let(Uri::parse)
        if (saved != null) {
            try { return createDocument(saved, filename, mimeType) }
            catch (error: Exception) {
                failure = error
                preferences.edit().remove("folder").commit()
            }
        } else {
            try { return createDownload(filename, mimeType) }
            catch (error: Exception) { failure = error }
        }
        Log.w("LumiverseDownloads", "Automatic export destination failed (${failure.javaClass.simpleName}); requesting a directory grant")
        val folder = chooseFolder(failure.message.orEmpty())
        check(!destroyed) { "The app is closing." }
        return createDocument(folder, filename, mimeType)
    }

    private fun createDownload(filename: String, mimeType: String): Target {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Lumiverse/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Android could not create the download.")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: error("Android could not open the download.")
            return Target(uri, output, true, "Downloads/Lumiverse")
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun createDocument(tree: Uri, filename: String, mimeType: String): Target = synchronized(lock) {
        check(!destroyed) { "The app is closing." }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(resolver, parent, mimeType, filename) ?: error("Android could not create a file in the selected folder.")
        try {
            // Only URIs created by this app are recorded for crash recovery.
            pending(uri, true)
            val output = resolver.openOutputStream(uri, "w") ?: error("Android could not open a file in the selected folder.")
            Target(uri, output, false, displayName(parent, "Selected export folder"))
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            pending(uri, false)
            throw error
        }
    }

    private fun chooseFolder(reason: String): Uri {
        val future = synchronized(lock) {
            preferences.getString("folder", null)?.let { return Uri.parse(it) }
            folderRequest ?: CompletableFuture<Uri>().also { request ->
                folderRequest = request
                activity.runOnUiThread {
                    val owner = activity as? MainActivity
                    if (destroyed || owner == null || activity.isFinishing) {
                        request.completeExceptionally(IllegalStateException("The app is closing."))
                    } else owner.requestExportDirectory(reason) { uri, error ->
                        if (uri != null && !destroyed && !request.isDone) {
                            if (preferences.edit().putString("folder", uri.toString()).commit()) request.complete(uri)
                            else request.completeExceptionally(IllegalStateException("Could not remember the export folder."))
                        } else request.completeExceptionally(IllegalStateException(error ?: "Export cancelled."))
                    }
                }
            }
        }
        try { return future.get(5, TimeUnit.MINUTES) }
        catch (error: java.util.concurrent.ExecutionException) { throw (error.cause as? Exception ?: error) }
        catch (error: java.util.concurrent.TimeoutException) {
            future.completeExceptionally(IllegalStateException("Export folder selection timed out. Please try again."))
            throw IllegalStateException("Export folder selection timed out. Please try again.")
        }
        finally { synchronized(lock) { if (folderRequest === future) folderRequest = null } }
    }

    fun publish(uri: Uri, mediaStore: Boolean) {
        if (mediaStore) check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1) { "Android could not finish the download." }
        else pending(uri, false)
    }

    fun remove(uri: Uri, mediaStore: Boolean) {
        runCatching {
            if (mediaStore) resolver.delete(uri, null, null) else DocumentsContract.deleteDocument(resolver, uri)
        }
        if (!mediaStore) runCatching { pending(uri, false) }
    }

    fun displayName(uri: Uri, fallback: String): String = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else fallback
        } ?: fallback
    }.getOrDefault(fallback)

    fun destroy() {
        destroyed = true
        synchronized(lock) { folderRequest?.completeExceptionally(IllegalStateException("The app is closing.")) }
    }
}
