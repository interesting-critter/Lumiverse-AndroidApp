package chat.lumiverse.prototype;

public final class ServerAddressCheck {
    public static void main(String[] args) {
        accepts(" https://Example.COM ", "https://example.com/");
        accepts("http://192.168.1.12:8000/", "http://192.168.1.12:8000/");
        accepts("http://[::1]:8000", "http://[::1]:8000/");
        accepts("HTTP://localhost:8000", "http://localhost:8000/");
        for (String input : new String[] { "", "example.com", "javascript:alert(1)", "file:///data/data", "https://user:pass@example.com",
            "https://example.com/path", "https://example.com/?token=secret", "https://example.com/#fragment", "http://example.com:65536", "http://example.com:0" }) {
            try { ServerAddress.normalize(input); throw new AssertionError("Accepted invalid address: " + input); }
            catch (IllegalArgumentException expected) { }
        }
    }
    private static void accepts(String input, String expected) {
        String actual = ServerAddress.normalize(input);
        if (!expected.equals(actual)) throw new AssertionError(actual + " != " + expected);
    }
}
