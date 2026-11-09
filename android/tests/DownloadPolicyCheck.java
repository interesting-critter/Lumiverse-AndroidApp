package chat.lumiverse.prototype;

public final class DownloadPolicyCheck {
    public static void main(String[] args) {
        equal(DownloadPolicy.trustedOrigin("http://EXAMPLE.com:80/", "https://example.com/login"), "https://example.com");
        equal(DownloadPolicy.trustedOrigin("https://example.com", "https://EXAMPLE.com:443/card?format=charx"), "https://example.com");
        equal(DownloadPolicy.trustedOrigin("http://127.0.0.1:1234", "http://127.0.0.1:5678/card"), null);
        equal(DownloadPolicy.trustedOrigin("https://example.com", "http://example.com/card"), null);
        equal(DownloadPolicy.trustedOrigin("http://example.com", "https://other.example.com/card"), null);
        equal(DownloadPolicy.trustedOrigin("http://example.com:8080", "https://example.com:8080/card"), null);
        equal(DownloadPolicy.trustedOrigin("http://example.com", "https://example.com:8443/card"), null);
        equal(DownloadPolicy.trustedOrigin(null, "https://example.com"), null);
        for (String invalid : new String[] { "blob:https://example.com/id", "file:///x", "https://user:pass@example.com", "//example.com/card" }) {
            equal(DownloadPolicy.trustedOrigin("https://example.com", invalid), null);
        }
        if (!DownloadPolicy.allowsRedirect("http://example.com", "https://example.com/card")) throw new AssertionError("HTTPS upgrade blocked");
        if (DownloadPolicy.allowsRedirect("https://example.com", "http://example.com/card")) throw new AssertionError("HTTPS downgrade allowed");
    }
    private static void equal(String actual, String expected) {
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected);
    }
}
