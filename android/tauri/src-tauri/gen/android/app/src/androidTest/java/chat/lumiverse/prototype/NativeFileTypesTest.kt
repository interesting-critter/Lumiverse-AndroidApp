package chat.lumiverse.test

import android.content.Intent
import android.webkit.WebChromeClient.FileChooserParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFileTypesTest {
    private fun params(types: Array<String>, multiple: Boolean = false) = object : FileChooserParams() {
        override fun getAcceptTypes() = types
        override fun getMode() = if (multiple) MODE_OPEN_MULTIPLE else MODE_OPEN
        override fun getTitle() = "Import card"
        override fun getFilenameHint() = "card.charx"
        override fun isCaptureEnabled() = false
        override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/png"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "application/json"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
    }

    @Test fun charxPickerIncludesFilesRegardlessOfProviderMimeType() {
        // Character browser, Weaver, slash command, extension-only and packed
        // accept lists must all survive Wry's MIME conversion.
        for (types in arrayOf(
            arrayOf(".json", ".png", ".charx", ".jpg", ".jpeg"),
            arrayOf(".png", ".json", ".charx", "application/json", "image/png"),
            arrayOf(".png", ".charx", ".jpg", ".jpeg", ".webp", ".json"),
            arrayOf(".CHARX"), arrayOf(".json, .CHARX, .png")
        )) {
            val original = params(types, multiple = true)
            val adjusted = NativeFileTypes.importParams(original)
            assertArrayEquals(arrayOf("*/*"), adjusted.acceptTypes)
            val intent = adjusted.createIntent()
            assertEquals("*/*", intent.type)
            assertFalse(intent.hasExtra(Intent.EXTRA_MIME_TYPES))
            assertEquals(Intent.ACTION_GET_CONTENT, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            assertEquals(original.mode, adjusted.mode)
            assertEquals(original.title, adjusted.title)
            assertEquals(original.filenameHint, adjusted.filenameHint)
            assertFalse(adjusted.isCaptureEnabled)
        }
        assertEquals(FileChooserParams.MODE_OPEN, NativeFileTypes.importParams(params(arrayOf(".charx"))).mode)
    }

    @Test fun otherPickersKeepTheirExistingFilteringAndCapture() {
        for (types in arrayOf(arrayOf("image/*"), arrayOf("application/json", ".json"), emptyArray(), arrayOf(".charx.json"))) {
            val original = params(types)
            assertSame(original, NativeFileTypes.importParams(original))
        }
    }

    @Test fun charxDownloadNamesSurviveZipMimeHints() {
        for (mime in arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", null)) {
            assertEquals("Alice.charx", NativeFileTypes.downloadFilename("https://example.com/export?format=charx", "attachment; filename=\"Alice.charx\"", mime))
            assertEquals("Alice.CHARX", NativeFileTypes.downloadFilename("https://example.com/Alice.CHARX?token=1", null, mime))
        }
    }

    @Test fun themeDownloadNamesSurviveZipMimeHints() {
        for (mime in arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", null)) {
            assertEquals("Starry.lumitheme", NativeFileTypes.downloadFilename("https://example.com/export", "attachment; filename=\"Starry.lumitheme\"", mime))
            assertEquals("Starry.LUMITHEME", NativeFileTypes.downloadFilename("https://example.com/Starry.LUMITHEME?token=1", null, mime))
        }
    }

    @Test fun portableExportMimeTypesPreserveCustomExtensions() {
        for (filename in arrayOf("Alice.charx", "Alice.CHARX", "Starry.lumitheme", "Starry.LUMITHEME")) {
            assertEquals("application/octet-stream", NativeFileTypes.exportMimeType(filename, "application/zip"))
        }
        for (filename in arrayOf("backup.zip", "theme.lumitheme.zip", "card.png", "book.json")) {
            assertEquals("application/zip", NativeFileTypes.exportMimeType(filename, "application/zip"))
        }
    }

    @Test fun ordinaryDownloadsStillInferTheirExtensions() {
        assertEquals("card.png", NativeFileTypes.downloadFilename("https://example.com/card", null, "image/png"))
        assertEquals("backup.zip", NativeFileTypes.downloadFilename("https://example.com/backup", null, "application/zip"))
        assertEquals("book.json", NativeFileTypes.downloadFilename("https://example.com/export", "attachment; filename=\"book.json\"", "application/json"))
    }

    @Test fun backupAndMigrationPickersNeverPassUnknownExtensionsAsMimeTypes() {
        for (types in arrayOf(arrayOf(".lvbak", ".zip", "application/zip", "application/octet-stream"),
            arrayOf(".lumiverse"), arrayOf(".jsonl,.zip"), emptyArray())) {
            val intent = NativeFileTypes.pickerIntent(params(types, multiple = true))
            assertEquals("*/*", intent.type)
            assertFalse(intent.hasExtra(Intent.EXTRA_MIME_TYPES))
            assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        }
        assertEquals("application/zip", NativeFileTypes.pickerIntent(params(arrayOf(".zip", "application/zip"))).type)
        assertEquals("application/json", NativeFileTypes.pickerIntent(params(arrayOf(".json", "application/json"))).type)
    }
}
