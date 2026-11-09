package chat.lumiverse.prototype;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Shared wire encoding; no Android dependencies so real JVM interoperability is testable. */
public final class NotificationEncryption {
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') out.append('\\').append(ch);
            else if (ch < 32) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }
    public static String associatedData(String project, String packageName, String enrollment,
            String origin, String instance, String keyId) {
        return "[\"lumiverse-fcm\",1," + quote(project) + "," + quote(packageName) + "," + quote(enrollment)
            + "," + quote(origin) + "," + quote(instance) + "," + quote(keyId) + "]";
    }
    public static String decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] tag, String aad) throws Exception {
        if (key.length != 32 || nonce.length != 12 || tag.length != 16 || ciphertext.length < 1 || ciphertext.length > 2400)
            throw new IllegalArgumentException("Invalid notification envelope");
        byte[] joined = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, joined, 0, ciphertext.length);
        System.arraycopy(tag, 0, joined, ciphertext.length, tag.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(joined), StandardCharsets.UTF_8);
    }
    private NotificationEncryption() {}
}
