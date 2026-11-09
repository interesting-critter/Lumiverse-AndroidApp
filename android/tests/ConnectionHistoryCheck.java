package chat.lumiverse.prototype;

import java.util.Arrays;
import java.util.Collections;

public final class ConnectionHistoryCheck {
    public static void main(String[] args) {
        ConnectionHistory history = new ConnectionHistory(Arrays.asList("https://EXAMPLE.com:443", "https://example.com/",
            "https://user:secret@example.com", "file:///data", "http://lan.local:8000/", "https://example.com:8443"));
        check(history.snapshot().equals(Arrays.asList("https://example.com/", "http://lan.local:8000/", "https://example.com:8443/")));
        history.remember("HTTP://lan.local:8000");
        check(history.snapshot().get(0).equals("http://lan.local:8000/"));
        check(history.snapshot().size() == 3);
        history.forget("http://lan.local:8000");
        check(!new ConnectionHistory(history.snapshot()).snapshot().contains("http://lan.local:8000/"));
        history.snapshot().clear();
        check(history.snapshot().size() == 2);
        history = new ConnectionHistory(Collections.emptyList());
        for (int index = 0; index < 12; index++) history.remember("https://server" + index + ".example");
        check(history.snapshot().size() == 10);
        check(history.snapshot().get(0).equals("https://server11.example/"));
        check(history.snapshot().get(9).equals("https://server2.example/"));
    }
    private static void check(boolean valid) { if (!valid) throw new AssertionError("Invalid connection history"); }
}
