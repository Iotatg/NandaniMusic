package music.nandani.tg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Low-level TDLib session: connection, authorization, request/response
 * correlation and the update dispatch loop.
 *
 * <p>One instance per Telegram account (bot account + each assistant).
 * TDLib requires a distinct database directory per account.</p>
 */
public class TdSession implements AutoCloseable {

    protected static final ObjectMapper JSON = new ObjectMapper();

    protected final TdJson td;
    protected final int clientId;
    protected final AtomicLong extraSeq = new AtomicLong(1);
    protected final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    protected final AtomicBoolean running = new AtomicBoolean(true);
    protected final AtomicBoolean ready = new AtomicBoolean(false);
    protected final String name;

    protected volatile long myUserId = 0;
    protected volatile String myUsername = "";
    protected volatile String myFirstName = "";

    /** Update consumers keyed by @type. */
    protected final Map<String, Consumer<JsonNode>> updateHandlers = new ConcurrentHashMap<>();

    public TdSession(String name, String libName) {
        this.name = name;
        this.td = TdJson.load(libName);
        this.clientId = td.td_create_client_id();
    }

    public void setLogVerbosity(int level) {
        try {
            td.td_set_log_message_callback(level, (lvl, msg) -> {
                if (lvl <= 1) System.err.println("[" + name + ":tdlib] " + msg);
            });
        } catch (Throwable ignored) {
            // logging callback is best-effort
        }
    }

    protected void on(String type, Consumer<JsonNode> handler) {
        updateHandlers.put(type, handler);
    }

    /** Fire-and-forget request. */
    public void send(String request) {
        td.td_send(clientId, request);
    }

    /** Request with @extra correlation; waits up to {@code timeoutMs}. */
    public JsonNode request(ObjectNode req, long timeoutMs) {
        String extra = "x" + extraSeq.getAndIncrement();
        req.put("@extra", extra);
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(extra, f);
        td.td_send(clientId, req.toString());
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pending.remove(extra);
            return null;
        }
    }

    public JsonNode request(ObjectNode req) {
        return request(req, 30_000);
    }

    public static ObjectNode obj() {
        return JSON.createObjectNode();
    }

    /** Synchronous execute (no network round-trip). */
    public JsonNode execute(ObjectNode req) {
        try {
            String r = td.td_execute(req.toString());
            return r == null ? null : JSON.readTree(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Starts the receive loop. Blocks until authorization is ready or timeout. */
    public boolean start(long readyTimeoutMs) {
        Thread t = new Thread(this::receiveLoop, name + "-tdlib");
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + readyTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ready.get()) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return ready.get();
    }

    private void receiveLoop() {
        while (running.get()) {
            String raw;
            try {
                raw = td.td_receive(0.5);
            } catch (Throwable e) {
                System.err.println("[" + name + "] td_receive failed: " + e.getMessage());
                break;
            }
            if (raw == null || raw.isEmpty()) continue;
            JsonNode node;
            try {
                node = JSON.readTree(raw);
            } catch (Exception e) {
                continue;
            }
            String extra = node.path("@extra").asText("");
            if (!extra.isEmpty()) {
                CompletableFuture<JsonNode> f = pending.remove(extra);
                if (f != null) {
                    if ("error".equals(node.path("@type").asText())) {
                        System.err.println("[" + name + "] " + node.path("message").asText());
                    }
                    f.complete(node);
                    continue;
                }
            }
            try {
                handleUpdate(node);
            } catch (Throwable e) {
                System.err.println("[" + name + "] update handler error: " + e);
            }
        }
    }

    protected void handleUpdate(JsonNode node) {
        String type = node.path("@type").asText();
        switch (type) {
            case "updateAuthorizationState" -> onAuthState(node.path("authorization_state"));
            case "updateConnectionState" -> {
                String st = node.path("state").path("@type").asText();
                if ("connectionStateReady".equals(st)) ready.set(true);
            }
            case "updateUser" -> {
                JsonNode u = node.path("user");
                if (u.path("id").asLong() == myUserId) {
                    myFirstName = u.path("first_name").asText("");
                    myUsername = u.path("usernames").path("active_usernames").path(0).asText(
                            u.path("username").asText(""));
                }
            }
            default -> {
                Consumer<JsonNode> h = updateHandlers.get(type);
                if (h != null) h.accept(node);
            }
        }
    }

    /** Override for bot / assistant specific login flows. */
    protected void onAuthState(JsonNode state) {
        String t = state.path("@type").asText();
        switch (t) {
            case "authorizationStateWaitTdlibParameters" -> sendTdlibParameters();
            case "authorizationStateWaitPhoneNumber" -> {
                // subclasses decide
            }
            case "authorizationStateReady" -> {
                ready.set(true);
                onReady();
            }
            case "authorizationStateClosed" -> running.set(false);
            default -> { }
        }
    }

    protected void onReady() {
        JsonNode me = request(obj().put("@type", "getMe"), 15_000);
        if (me != null && me.has("id")) {
            myUserId = me.path("id").asLong();
            myFirstName = me.path("first_name").asText("");
            myUsername = me.path("usernames").path("active_usernames").path(0).asText(
                    me.path("username").asText(""));
        }
    }

    protected String apiId = "";
    protected String apiHash = "";
    protected String dbDir = "data/tdlib";
    protected String filesDir = "data/tdlib-files";

    public void configure(String apiId, String apiHash, String dbDir, String filesDir) {
        this.apiId = apiId;
        this.apiHash = apiHash;
        this.dbDir = dbDir;
        this.filesDir = filesDir;
    }

    protected void sendTdlibParameters() {
        ObjectNode req = obj();
        req.put("@type", "setTdlibParameters");
        ObjectNode p = req.putObject("parameters");
        p.put("use_test_dc", false);
        p.put("database_directory", dbDir);
        p.put("files_directory", filesDir);
        p.put("use_file_database", true);
        p.put("use_chat_info_database", true);
        p.put("use_message_database", true);
        p.put("use_secret_chats", false);
        p.put("api_id", Integer.parseInt(apiId));
        p.put("api_hash", apiHash);
        p.put("system_language_code", "en");
        p.put("device_model", "NandaniMusic");
        p.put("system_version", "Linux");
        p.put("application_version", "1.0.0");
        p.put("enable_storage_optimizer", true);
        p.put("ignore_file_names", false);
        send(req.toString());
    }

    public boolean isReady() { return ready.get(); }
    public long myUserId() { return myUserId; }
    public String myUsername() { return myUsername; }
    public String myFirstName() { return myFirstName; }

    @Override
    public void close() {
        running.set(false);
        ready.set(false);
        try {
            td.td_send(clientId, obj().put("@type", "close").toString());
        } catch (Throwable ignored) { }
    }
}
