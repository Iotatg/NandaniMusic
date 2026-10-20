package music.nandani.health;

import com.sun.net.httpserver.HttpServer;
import music.nandani.modules.Core;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Tiny HTTP health endpoint (same role as bot/src/health.rs). */
public final class HealthServer {

    private final HttpServer server;

    public HealthServer(int port) throws Exception {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", ex -> respond(ex, 200, "{\"status\":\"ok\"}"));
        server.createContext("/ready", ex -> {
            boolean ok = Core.botReady;
            respond(ex, ok ? 200 : 503, "{\"ready\":" + ok + "}");
        });
        server.createContext("/metrics", ex -> {
            String body = "nandani_rooms " + Core.rooms.count() + "\n"
                    + "nandani_assistants " + (Core.assistants == null ? 0 : Core.assistants.count()) + "\n"
                    + "nandani_uptime_seconds " + (Core.cfg == null ? 0 : Core.cfg.uptimeSeconds()) + "\n";
            respond(ex, 200, body);
        });
        server.setExecutor(null);
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    public void stop() {
        server.stop(0);
    }
}
