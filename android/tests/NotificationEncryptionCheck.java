package chat.lumiverse.prototype;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class NotificationEncryptionCheck {
    public static void main(String[] args) throws Exception {
        String aad = NotificationEncryption.associatedData(args[4], args[5], args[6], args[7], args[8], args[9]);
        if (!aad.equals(args[10])) throw new AssertionError("Backend and Android associated data differ");
        String clear = NotificationEncryption.decrypt(Base64.getDecoder().decode(args[0]), Base64.getDecoder().decode(args[1]),
            Base64.getDecoder().decode(args[2]), Base64.getDecoder().decode(args[3]), aad);
        String expectedClear = new String(Base64.getDecoder().decode(args[11]), StandardCharsets.UTF_8);
        if (!clear.equals(expectedClear)) throw new AssertionError("Backend notification did not decrypt on JVM");
        try {
            NotificationEncryption.decrypt(Base64.getDecoder().decode(args[0]), Base64.getDecoder().decode(args[1]),
                Base64.getDecoder().decode(args[2]), Base64.getDecoder().decode(args[3]), aad + "tampered");
            throw new AssertionError("Unauthenticated notification accepted");
        } catch (javax.crypto.AEADBadTagException expected) {}
    }
}
