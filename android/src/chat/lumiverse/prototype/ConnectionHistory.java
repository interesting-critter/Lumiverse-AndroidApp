package chat.lumiverse.prototype;

import java.util.ArrayList;
import java.util.List;

/** Device-local successful remote connections, ordered most recent first. */
public final class ConnectionHistory {
    private static final int LIMIT = 10;
    private final List<String> servers = new ArrayList<>();

    public ConnectionHistory(List<String> saved) {
        for (String value : saved) {
            try {
                String server = ServerAddress.normalize(value);
                if (servers.size() < LIMIT && !servers.contains(server)) servers.add(server);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public void remember(String value) {
        String server = ServerAddress.normalize(value);
        servers.remove(server);
        servers.add(0, server);
        if (servers.size() > LIMIT) servers.remove(servers.size() - 1);
    }

    public void forget(String value) { servers.remove(ServerAddress.normalize(value)); }
    public List<String> snapshot() { return new ArrayList<>(servers); }
}
