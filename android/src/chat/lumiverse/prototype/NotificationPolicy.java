package chat.lumiverse.prototype;

import java.net.URI;

/** Pure URL/navigation policy shared by the bridge and device-independent tests. */
public final class NotificationPolicy {
    public static String origin(String raw) {
        try {
            URI input = new URI(raw == null ? "" : raw);
            URI value = new URI(ServerAddress.normalize(input.getScheme() + "://" + input.getRawAuthority()));
            int port = value.getPort();
            boolean defaultPort = ("https".equals(value.getScheme()) && port == 443)
                || ("http".equals(value.getScheme()) && port == 80);
            return value.getScheme() + "://" + value.getHost() + (port == -1 || defaultPort ? "" : ":" + port);
        } catch (Exception error) { return null; }
    }

    public static String chatPath(String chatId) {
        return chatId != null && chatId.matches("[A-Za-z0-9_-]{1,128}") ? "/chat/" + chatId : "/";
    }

    /** Notification taps on the displayed chat must not reload the WebView. */
    public static boolean isCurrentChat(String currentUrl, String targetUrl) {
        try {
            URI current = new URI(currentUrl), target = new URI(targetUrl);
            String targetOrigin = origin(targetUrl);
            String path = target.getPath();
            return targetOrigin != null && targetOrigin.equals(origin(currentUrl))
                && target.getRawQuery() == null && target.getRawFragment() == null
                && path != null && path.matches("/chat/[A-Za-z0-9_-]{1,128}/?")
                && path.replaceAll("/$", "").equals(current.getPath().replaceAll("/$", ""));
        } catch (Exception error) { return false; }
    }

    /** Only the app's embedded runtime can move a tap between loopback ports. */
    public static boolean tapOriginMatches(String enrolled, String target, boolean wasLocal, boolean targetLocal) {
        if (wasLocal != targetLocal || enrolled == null || target == null) return false;
        if (enrolled.equals(target)) return true;
        return wasLocal && loopback(enrolled) && loopback(target);
    }

    private static boolean loopback(String raw) {
        try {
            URI value = new URI(raw);
            return "http".equals(value.getScheme()) && "127.0.0.1".equals(value.getHost()) && value.getPort() > 0;
        } catch (Exception error) { return false; }
    }
}
