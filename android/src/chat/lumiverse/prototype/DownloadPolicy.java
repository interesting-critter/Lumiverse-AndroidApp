package chat.lumiverse.prototype;

import java.util.LinkedHashSet;
import java.util.Set;

/** Downloads may follow the selected origin's standard HTTP to HTTPS upgrade. */
public final class DownloadPolicy {
    public static Set<String> trustedOrigins(String selected) {
        Set<String> origins = new LinkedHashSet<>();
        String origin = NotificationPolicy.origin(selected);
        if (origin == null) return origins;
        origins.add(origin);
        if (origin.startsWith("http://")) {
            String secure = "https://" + origin.substring("http://".length());
            // Only standard ports: never infer a different service on a custom port.
            if (java.net.URI.create(origin).getPort() == -1) origins.add(secure);
        }
        return origins;
    }

    public static String trustedOrigin(String selected, String url) {
        String origin = NotificationPolicy.origin(url);
        return origin != null && trustedOrigins(selected).contains(origin) ? origin : null;
    }

    public static boolean allowsRedirect(String from, String to) {
        return trustedOrigin(from, to) != null;
    }
}
