package chat.lumiverse.test

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private enrollment, with its read-only bearer encrypted by Android Keystore. */
object CharacterWidgetStore {
    private const val KEY = "lumiverse-character-widgets-v1"
    private fun prefs(context: Context) = context.getSharedPreferences(KEY, Context.MODE_PRIVATE)
    private fun directory(context: Context) = File(context.noBackupFilesDir, "character-widgets").apply { mkdirs() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun current(context: Context): JSONObject? { return try {
        val bytes = Base64.decode(prefs(context).getString("active", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    } catch (_: Exception) { null } }
    @Synchronized fun activate(context: Context, active: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(active.toString().toByteArray(Charsets.UTF_8))
        check(prefs(context).edit().putString("active", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun revision(context: Context) = prefs(context).getString("revision", "")!!
    @Synchronized fun invalidate(context: Context, removeEnrollment: Boolean = false) {
        prefs(context).edit().putString("revision", UUID.randomUUID().toString()).apply {
            if (removeEnrollment) remove("active")
        }.commit()
        directory(context).listFiles()?.forEach { it.delete() }
    }
    @Synchronized fun snapshot(context: Context, active: JSONObject): WidgetSnapshot? = try {
        if (active.getLong("expiresAt") <= System.currentTimeMillis()) null
        else WidgetSnapshot.parse(JSONObject(AtomicFile(File(directory(context), "snapshot.json")).openRead().use { it.readBytes().toString(Charsets.UTF_8) }))
            .takeIf { it.accountId == active.getString("accountId") }
    } catch (_: Exception) { null }
    @Synchronized fun image(context: Context, character: String): ByteArray? {
        if (!CharacterWidgetLink.identifier(character)) return null
        return try { File(directory(context), "$character.jpg").takeIf { it.length() in 1..262144 }?.readBytes() } catch (_: Exception) { null }
    }
    @Synchronized fun commit(context: Context, active: JSONObject, revision: String, snapshot: WidgetSnapshot, images: Map<String, ByteArray>): Boolean {
        val current = current(context) ?: return false
        if (current.getString("registrationId") != active.getString("registrationId") || revision(context) != revision
            || snapshot.accountId != current.getString("accountId") || current.getLong("expiresAt") <= System.currentTimeMillis()
            || (snapshot(context, current)?.generatedAt ?: 0) > snapshot.generatedAt) return false
        // Readers share this lock, so no widget can see a mixed snapshot/image set.
        directory(context).listFiles()?.forEach { it.delete() }
        images.forEach { (id, bytes) -> File(directory(context), "$id.jpg").writeBytes(bytes) }
        val file = AtomicFile(File(directory(context), "snapshot.json"))
        val output = file.startWrite()
        try { output.write(snapshot.raw.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        return true
    }
}
