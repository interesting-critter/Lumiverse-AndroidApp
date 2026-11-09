package chat.lumiverse.prototype;

public class NotificationPolicyCheck {
    static void equal(String actual, String expected) {
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        equal(NotificationPolicy.origin("https://EXAMPLE.com:443/chat/abc?x=1"), "https://example.com");
        equal(NotificationPolicy.origin("http://127.0.0.1:7860/"), "http://127.0.0.1:7860");
        equal(NotificationPolicy.origin("http://[::1]:7860/chat/abc"), "http://[::1]:7860");
        equal(NotificationPolicy.origin("http://example.com:80"), "http://example.com");
        for (String invalid : new String[] { "javascript:alert(1)", "file:///x", "https://user:pass@example.com", "https://example.com:0", "//example.com" }) equal(NotificationPolicy.origin(invalid), null);
        equal(NotificationPolicy.chatPath("chat_abc-123"), "/chat/chat_abc-123");
        for (String invalid : new String[] { "../admin", "//external.example", "abc?x=1", "abc#hash", "a/b", "" }) equal(NotificationPolicy.chatPath(invalid), "/");
        String target = "https://example.com/chat/chat-1";
        for (String current : new String[] { target, "https://example.com:443/chat/chat-1?panel=details#message-2", target + "/" }) {
            if (!NotificationPolicy.isCurrentChat(current, target)) throw new AssertionError("Open chat would reload: " + current);
        }
        for (String current : new String[] { "https://example.com/", "https://example.com/chat/chat-2", "https://other.example/chat/chat-1", "http://example.com/chat/chat-1", "https://example.com:8443/chat/chat-1" }) {
            if (NotificationPolicy.isCurrentChat(current, target)) throw new AssertionError("Different destination suppressed: " + current);
        }
        for (String next : new String[] { target + "?message=2", target + "#message-2", "https://example.com/" }) {
            if (NotificationPolicy.isCurrentChat(next, next)) throw new AssertionError("Explicit destination suppressed: " + next);
        }
        if (!NotificationPolicy.tapOriginMatches("http://127.0.0.1:4100", "http://127.0.0.1:5200", true, true)) throw new AssertionError("Local runtime restart lost its tap");
        if (!NotificationPolicy.tapOriginMatches("https://example.com", "https://example.com", false, false)) throw new AssertionError("Remote tap lost its origin");
        for (boolean[] modes : new boolean[][] { {false, false}, {true, false}, {false, true} }) {
            if (NotificationPolicy.tapOriginMatches("http://127.0.0.1:4100", "http://127.0.0.1:5200", modes[0], modes[1])) throw new AssertionError("Cross-server tap accepted");
        }
        if (NotificationPolicy.tapOriginMatches("https://example.com", "https://other.example", false, false)
            || NotificationPolicy.tapOriginMatches("http://127.0.0.1:4100", "http://other.example:5200", true, true)) throw new AssertionError("External tap accepted");
    }
}
