package chat.lumiverse.prototype;

import java.net.URI;
import java.net.URISyntaxException;

/** A hosted Lumiverse runs at an HTTP(S) origin; credentials stay in its login page. */
public final class ServerAddress {
    public static String normalize(String input) {
        try {
            URI uri = new URI(input == null ? "" : input.trim()).normalize();
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Enter an HTTP or HTTPS server address without credentials, a path, or query parameters.");
            }
            int port = uri.getPort();
            if (("https".equalsIgnoreCase(scheme) && port == 443) || ("http".equalsIgnoreCase(scheme) && port == 80)) port = -1;
            return new URI(scheme.toLowerCase(java.util.Locale.ROOT), null,
                uri.getHost().toLowerCase(java.util.Locale.ROOT), port, "/", null, null).toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Enter a valid HTTP or HTTPS server address.");
        }
    }
}
