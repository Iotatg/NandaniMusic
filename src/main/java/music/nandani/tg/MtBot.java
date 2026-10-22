package music.nandani.tg;

import music.nandani.mtproto.MtProto;
import music.nandani.mtproto.TlNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bot transport over raw MTProto:
 * no HTTP Bot API anywhere — TL requests go straight to the Telegram DC.
 */
public final class MtBot implements Bot {

    private final int apiId;
    private final String apiHash;
    private final String token;
    private final int dc;
    private MtProto mt;

    /** Raw client, for diagnostics. */
    public MtProto mtproto() {
        return mt;
    }

    private volatile boolean ready;
    private long myId;
    private String myUsername = "";
    private String myFirstName = "";

    private final Map<Long, Long> accessHashes = new ConcurrentHashMap<>();
    private final Map<Long, String> usernames = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> broadcastChat = new ConcurrentHashMap<>();
    private final Map<Long, String> chatTitles = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> userIsBot = new ConcurrentHashMap<>();
    private final Map<Long, String> userNames = new ConcurrentHashMap<>();
    private volatile long lastPeerRefresh;

    private final List<Consumer<TgMessage>> messageHandlers = new CopyOnWriteArrayList<>();
    private final List<java.util.function.BiConsumer<Long, VoiceChatEvent>> vcHandlers = new CopyOnWriteArrayList<>();
    private final Map<String, List<Consumer<TgMessage>>> commandHandlers = new ConcurrentHashMap<>();
    private final List<Map.Entry<String, List<Consumer<TgCallback>>>> callbackHandlers = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService pinger;

    public MtBot(int apiId, String apiHash, String token, int dc) {
        this.apiId = apiId;
        this.apiHash = apiHash;
        this.token = token;
        this.dc = dc;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public boolean start(long timeoutMs) {
        try {
            mt = new MtProto(apiId, apiHash, dc);
            mt.onUpdate(this::onUpdates);
            mt.onDisconnect(() -> ready = false);

            TlNode user = null;
            // Load first: the stored key also pins the data centre, so the
            // socket must be opened to the DC the key belongs to.
            boolean resumed = loadSession();
            mt.connect();
            if (resumed) {
                mt.startReader();
                try {
                    user = selfUser();
                    System.out.println("[mtproto] session resumed (dc" + mt.currentDc() + ")");
                } catch (java.io.IOException e) {
                    System.out.println("[mtproto] stored session rejected (" + e.getMessage() + "), re-authorising");
                    deleteSession();
                    mt.close();
                    mt.connect();
                    resumed = false;
                }
            }
            if (!resumed) {
                mt.handshake();
                TlNode auth = authorize();
                if (auth == null) throw new IOException("bot authorization failed after migration");
                user = auth.node("user");
                saveSession();
            }
            if (user == null) throw new IOException("bot authorization returned no user");

            myId = user.l("id");
            myUsername = user.str("username");
            myFirstName = user.str("first_name");
            userIsBot.put(myId, true);
            accessHashes.put(myId, user.l("access_hash"));
            userNames.put(myId, myFirstName);

            ready = true;
            refreshPeers();
            startPinger();
            startUpdater();
            return true;
        } catch (Throwable e) {
            System.err.println("[mtproto] bot login failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ── session persistence ───────────────────────────────────────────────────

    private java.nio.file.Path sessionPath() {
        return java.nio.file.Path.of(System.getenv().getOrDefault("BOT_SESSION", "data/bot.session"));
    }

    private boolean loadSession() {
        try {
            java.nio.file.Path p = sessionPath();
            if (!java.nio.file.Files.isRegularFile(p)) return false;
            return mt.importSession(java.nio.file.Files.readString(p));
        } catch (Exception e) {
            return false;
        }
    }

    private void saveSession() {
        try {
            String s = mt.exportSession();
            if (s == null) return;
            java.nio.file.Path p = sessionPath();
            if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, s);
            System.out.println("[mtproto] session saved to " + p);
        } catch (Exception e) {
            System.err.println("[mtproto] cannot save session: " + e.getMessage());
        }
    }

    private void deleteSession() {
        try {
            java.nio.file.Files.deleteIfExists(sessionPath());
        } catch (Exception ignored) {
        }
    }

    private TlNode selfUser() throws java.io.IOException {
        TlNode res = mt.invoke(new TlNode("users.getFullUser")
                .put("id", new TlNode("inputUserSelf")), 20);
        // userFull carries the id; the user object itself is in `users`.
        for (Object o : res.list("users")) {
            if (o instanceof TlNode u && "user".equals(u.name)) return u;
        }
        throw new IOException("no self user");
    }

    /**
     * auth.importBotAuthorization with DC migration and FLOOD_WAIT back-off.
     * Telegram only needs this once per auth key — afterwards the session file
     * is reused, so a normal restart never touches this path.
     */
    private TlNode authorize() throws Exception {
        long deadline = System.currentTimeMillis() + 3_600_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!mt.isRunning()) {
                System.out.println("[mtproto] (re)connecting for authorisation…");
                mt.reconnect();
            }
            try {
                return mt.invoke(new TlNode("auth.importBotAuthorization")
                        .put("flags", 0)
                        .put("api_id", apiId)
                        .put("api_hash", apiHash)
                        .put("bot_auth_token", token), 30);
            } catch (java.io.IOException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                int migrateDc = migrateTarget(msg);
                if (migrateDc > 0) {
                    System.out.println("[mtproto] account lives on dc" + migrateDc + ", migrating…");
                    mt.migrateTo(migrateDc);
                    continue;
                }
                long wait = floodWaitSeconds(msg);
                if (wait > 0) {
                    System.out.println("[mtproto] flood wait: retrying authorisation in " + wait + "s");
                    Thread.sleep(Math.min(wait + 2, 3_600) * 1000L);
                    continue;
                }
                if (msg.contains("not connected") || msg.contains("connection") || msg.contains("timeout")) {
                    System.out.println("[mtproto] authorisation transport error (" + msg + "), retrying");
                    Thread.sleep(3000);
                    continue;
                }
                throw e;
            }
        }
        return null;
    }

    private static long floodWaitSeconds(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("FLOOD_WAIT_(\\d+)").matcher(message);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static int migrateTarget(String message) {
        if (message == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:PHONE|USER|NETWORK|FILE)_MIGRATE_(\\d+)").matcher(message);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private void startPinger() {
        pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mtproto-ping");
            t.setDaemon(true);
            return t;
        });
        pinger.scheduleAtFixedRate(() -> {
            try {
                if (mt != null && ready) {
                    mt.ping();
                } else if (mt != null && !ready) {
                    // The link dropped: resume from the stored auth key.
                    System.out.println("[mtproto] reconnecting…");
                    mt.close();
                    mt.connect();
                    mt.startReader();
                    TlNode me = selfUser();
                    if (me != null) {
                        ready = true;
                        System.out.println("[mtproto] reconnected as @" + myUsername);
                    }
                }
            } catch (Exception e) {
                System.err.println("[mtproto] reconnect failed: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    // ── update polling safety net ─────────────────────────────────────────────
    // Push updates normally arrive on the socket, but when they don't (or the
    // link was quiet for a while) updates.getDifference guarantees delivery.

    private long diffPts, diffQts, diffSeq, diffDate;
    private ScheduledExecutorService updater;

    private void startUpdater() {
        updater = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mtproto-updater");
            t.setDaemon(true);
            return t;
        });
        updater.scheduleAtFixedRate(() -> {
            try {
                if (mt == null || !ready) return;
                pollDifference();
            } catch (Exception e) {
                System.err.println("[mtproto] poll failed: " + e.getMessage());
            }
        }, 3, 15, TimeUnit.SECONDS);
    }

    private synchronized void pollDifference() throws java.io.IOException {
        if (diffDate == 0) {
            TlNode st = mt.invoke(new TlNode("updates.getState"), 20);
            diffPts = st.l("pts");
            diffQts = st.l("qts");
            diffSeq = st.l("seq");
            diffDate = st.l("date");
            return;
        }
        TlNode diff = mt.invoke(new TlNode("updates.getDifference")
                .put("flags", 0)
                .put("pts", diffPts)
                .put("date", diffDate)
                .put("qts", diffQts), 20);
        switch (diff.name) {
            case "updates_differenceEmpty" -> {
                diffDate = diff.l("date");
                diffSeq = diff.l("seq");
            }
            case "updates_difference", "updates_differenceSlice" -> {
                cacheUsers(diff.list("users"));
                cacheChats(diff.list("chats"));
                for (Object o : diff.list("new_messages")) {
                    if (o instanceof TlNode msg) emitMessage(msg);
                }
                for (Object o : diff.list("other_updates")) {
                    if (o instanceof TlNode u) handleUpdate(u);
                }
                TlNode st = diff.node("state");
                if (st != null) {
                    diffPts = st.l("pts");
                    diffQts = st.l("qts");
                    diffSeq = st.l("seq");
                    diffDate = st.l("date");
                }
            }
            default -> {
            }
        }
    }

    @Override
    public int setCommands(List<String> names) {
        List<Object> cmds = new ArrayList<>();
        for (String raw : names) {
            if (cmds.size() >= 100) break;   // Telegram caps the menu at 100
            String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_]{1,32}")) continue;
            cmds.add(new TlNode("botCommand").put("command", name)
                    .put("description", CommandDesc.of(name)));
        }
        if (cmds.isEmpty()) return 0;
        try {
            mt.invoke(new TlNode("bots.setBotCommands")
                    .put("scope", new TlNode("botCommandScopeDefault"))
                    .put("lang_code", "")
                    .put("commands", cmds), 25);
            return cmds.size();
        } catch (Exception e) {
            System.err.println("[mtproto] setBotCommands failed: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public void close() {
        if (pinger != null) pinger.shutdownNow();
        if (mt != null) mt.close();
    }

    // ── peer cache ────────────────────────────────────────────────────────────

    private static long encodeChannel(long channelId) {
        return -1_000_000_000_000L - channelId;
    }

    private static boolean isChannelId(long chatId) {
        return chatId <= -1_000_000_000_000L;
    }

    private static long channelOf(long chatId) {
        return -chatId - 1_000_000_000_000L;
    }

    private TlNode inputPeer(long chatId) {
        if (chatId > 0) {
            return new TlNode("inputPeerUser")
                    .put("user_id", chatId)
                    .put("access_hash", accessHashes.getOrDefault(chatId, 0L));
        }
        if (isChannelId(chatId)) {
            long cid = channelOf(chatId);
            Long hash = accessHashes.get(chatId);
            if (hash == null) refreshPeersSoon();
            return new TlNode("inputPeerChannel")
                    .put("channel_id", cid)
                    .put("access_hash", hash == null ? 0L : hash);
        }
        return new TlNode("inputPeerChat").put("chat_id", -chatId);
    }

    private TlNode inputChannel(long chatId) {
        long cid = channelOf(chatId);
        return new TlNode("inputChannel")
                .put("channel_id", cid)
                .put("access_hash", accessHashes.getOrDefault(chatId, 0L));
    }

    private TlNode inputUser(long userId) {
        return new TlNode("inputUser")
                .put("user_id", userId)
                .put("access_hash", accessHashes.getOrDefault(userId, 0L));
    }

    private void refreshPeersSoon() {
        if (System.currentTimeMillis() - lastPeerRefresh < 20_000) return;
        lastPeerRefresh = System.currentTimeMillis();
        Thread t = new Thread(this::refreshPeers, "peer-refresh");
        t.setDaemon(true);
        t.start();
    }

    private void refreshPeers() {
        // Bots cannot call messages.getDialogs (BOT_METHOD_INVALID) — every
        // update envelope already carries the users/chats we need, and they
        // are cached in onUpdates().
    }

    private void cacheUsers(List<Object> users) {
        for (Object o : users) {
            if (o instanceof TlNode u && "user".equals(u.name)) cacheUser(u);
        }
    }

    private void cacheUser(TlNode u) {
        long id = u.l("id");
        if (u.has("access_hash")) accessHashes.put(id, u.l("access_hash"));
        if (u.has("username")) usernames.put(id, u.str("username"));
        userIsBot.put(id, u.bool("bot"));
        String name = (u.str("first_name") + " " + u.str("last_name")).trim();
        if (!name.isEmpty()) userNames.put(id, name);
    }

    void cacheChats(List<Object> chats) {
        for (Object o : chats) {
            if (o instanceof TlNode c) cacheChat(c);
        }
    }

    private void cacheChat(TlNode c) {
        switch (c.name) {
            case "channel", "channelForbidden" -> {
                long id = c.l("id");
                long chatId = encodeChannel(id);
                if (c.has("access_hash")) accessHashes.put(chatId, c.l("access_hash"));
                if (c.has("username")) usernames.put(chatId, c.str("username"));
                if (c.has("title")) chatTitles.put(chatId, c.str("title"));
                broadcastChat.put(chatId, c.bool("broadcast"));
            }
            case "chat", "chatForbidden" -> {
                long chatId = -c.l("id");
                if (c.has("title")) chatTitles.put(chatId, c.str("title"));
                broadcastChat.put(chatId, false);
            }
            default -> {
            }
        }
    }

    // ── updates ───────────────────────────────────────────────────────────────

    private void onUpdates(TlNode n) {
        switch (n.name) {
            case "updates", "updatesCombined" -> {
                cacheUsers(n.list("users"));
                cacheChats(n.list("chats"));
                if (System.getenv("NANDANI_TL_DEBUG") != null) {
                    StringBuilder sb = new StringBuilder("[upd] envelope " + n.name + ":");
                    for (Object o : n.list("updates")) {
                        sb.append(' ').append(o instanceof TlNode t ? t.name : String.valueOf(o));
                    }
                    System.out.println(sb);
                }
                for (Object o : n.list("updates")) {
                    if (o instanceof TlNode u) handleUpdate(u);
                }
            }
            case "updateShort" -> {
                TlNode u = n.node("update");
                if (u != null) handleUpdate(u);
            }
            default -> handleUpdate(n);
        }
    }

    private void handleUpdate(TlNode u) {
        switch (u.name) {
            case "updateNewMessage", "updateNewChannelMessage", "updateNewScheduledMessage" ->
                    emitMessage(u.node("message"));
            case "updateShortMessage" -> emitShortMessage(u, false);
            case "updateShortChatMessage" -> emitShortMessage(u, true);
            case "updateBotCallbackQuery" -> emitCallback(u);
            case "updateChannel" -> {
                TlNode c = u.node("channel");
                if (c != null) cacheChat(c);
            }
            case "updateUserName" -> {
                long id = u.l("user_id");
                String name = (u.str("first_name") + " " + u.str("last_name")).trim();
                if (!name.isEmpty()) userNames.put(id, name);
                if (u.has("username")) usernames.put(id, u.str("username"));
            }
            default -> {
                if (System.getenv("NANDANI_TL_DEBUG") != null) {
                    System.out.println("[upd] ignored " + u.name);
                }
            }
        }
    }

    private void emitMessage(TlNode msg) {
        if (msg == null) {
            if (System.getenv("NANDANI_TL_DEBUG") != null) {
                System.out.println("[upd] updateNewMessage carried no message node");
            }
            return;
        }
        try {
            TlNode peer = msg.node("peer_id");
            if (peer == null) {
                if (System.getenv("NANDANI_TL_DEBUG") != null) {
                    System.out.println("[upd] message fields: " + msg.fields());
                }
                return;
            }
            long chatId = encodePeer(peer);

            // Service messages carry an action instead of text/media — the
            // group-call one is our VC start/end signal (messageActionGroupCall:
            // no duration = VC started, duration = VC ended).
            if ("messageService".equals(msg.name)) {
                emitVoiceChatEvent(chatId, msg.node("action"), msg.i("id"));
                return;
            }

            TgMessage m = new TgMessage();
            m.chatId = chatId;
            m.id = msg.i("id");
            m.text = msg.str("message");
            m.isPrivate = "peerUser".equals(peer.name);
            m.isChannel = "peerChannel".equals(peer.name) && Boolean.TRUE.equals(broadcastChat.get(chatId));

            TlNode from = msg.node("from_id");
            if (from != null && "peerUser".equals(from.name)) {
                m.senderId = from.l("user_id");
            } else {
                m.senderId = chatId;
            }
            m.senderIsBot = Boolean.TRUE.equals(userIsBot.get(m.senderId));
            m.chatTitle = chatTitles.getOrDefault(chatId, "");
            String name = userNames.get(m.senderId);
            if (name != null) {
                int sp = name.indexOf(' ');
                m.firstName = sp > 0 ? name.substring(0, sp) : name;
                m.lastName = sp > 0 ? name.substring(sp + 1) : "";
            }

            TlNode reply = msg.node("reply_to");
            if (reply != null) {
                m.replyTo = reply.i("reply_to_msg_id");
                if (reply.has("forum_topic")) m.messageThreadId = reply.i("reply_to_msg_id");
            }

            TlNode media = msg.node("media");
            if (media != null) {
                if ("messageMediaDocument".equals(media.name)) {
                    TlNode doc = media.node("document");
                    if (doc != null) {
                        m.docId = doc.l("id");
                        m.docAccessHash = doc.l("access_hash");
                        m.docFileRef = doc.bytes("file_reference");
                        boolean sticker = false;
                        boolean video = false;
                        for (Object o : doc.list("attributes")) {
                            if (o instanceof TlNode a) {
                                if ("documentAttributeSticker".equals(a.name)) sticker = true;
                                if ("documentAttributeVideo".equals(a.name)) video = true;
                            }
                        }
                        m.mediaKind = sticker ? TgMessage.MediaKind.STICKER
                                : video ? TgMessage.MediaKind.VIDEO
                                : TgMessage.MediaKind.DOCUMENT;
                    }
                } else if ("messageMediaPhoto".equals(media.name)) {
                    m.mediaKind = TgMessage.MediaKind.PHOTO;
                }
            }

            dispatch(m);
        } catch (Throwable e) {
            System.err.println("[mtproto] bad message: " + e);
        }
    }

    /**
     * updateShortMessage / updateShortChatMessage — the compact forms Telegram
     * uses for private chats and small groups instead of a full message object.
     */
    private void emitShortMessage(TlNode u, boolean chatForm) {
        try {
            TgMessage m = new TgMessage();
            m.id = u.i("id");
            m.text = u.str("message");
            if (chatForm) {
                m.chatId = -u.l("chat_id");
                m.senderId = u.l("from_id");
                m.isPrivate = false;
                m.chatTitle = chatTitles.getOrDefault(m.chatId, "");
            } else {
                m.chatId = u.l("user_id");
                m.senderId = m.chatId;
                m.isPrivate = true;
            }
            TlNode reply = u.node("reply_to");
            if (reply != null) m.replyTo = reply.i("reply_to_msg_id");
            String name = userNames.get(m.senderId);
            if (name != null) {
                int sp = name.indexOf(' ');
                m.firstName = sp > 0 ? name.substring(0, sp) : name;
                m.lastName = sp > 0 ? name.substring(sp + 1) : "";
            }
            if (u.list("entities") != null && !u.list("entities").isEmpty()) {
                // entities are parsed from the text by callers that need them
            }
            dispatch(m);
        } catch (Throwable e) {
            System.err.println("[mtproto] bad short message: " + e);
        }
    }

    /** Group-call service message: VC start/end event (messageActionGroupCall). */
    public record VoiceChatEvent(long chatId, boolean ended, int durationSec, long messageId) {}

    /** Subscribe to voice-chat start/end service events. */
    public void onVoiceChat(java.util.function.BiConsumer<Long, VoiceChatEvent> handler) {
        vcHandlers.add(handler);
    }

    private void emitVoiceChatEvent(long chatId, TlNode action, long messageId) {
        if (action == null || !"messageActionGroupCall".equals(action.name)) return;
        boolean ended = action.has("duration");
        int dur = ended ? action.i("duration") : 0;
        VoiceChatEvent ev = new VoiceChatEvent(chatId, ended, dur, messageId);
        System.out.println("[upd] voice chat " + (ended ? "ended (" + dur + "s)" : "started")
                + " chat=" + chatId);
        for (java.util.function.BiConsumer<Long, VoiceChatEvent> h : vcHandlers) {
            try {
                h.accept(chatId, ev);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private void dispatch(TgMessage m) {
        System.out.println("[in] chat=" + m.chatId + " user=" + m.senderId
                + " thread=" + m.messageThreadId + " text=" + m.text);        for (Consumer<TgMessage> h : messageHandlers) {
            try {
                h.accept(m);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        if (m.text.startsWith("/")) {
            String cmd = m.text.substring(1).split("[ @]", 2)[0].toLowerCase(Locale.ROOT);
            List<Consumer<TgMessage>> hs = commandHandlers.get(cmd);
            if (hs != null) {
                for (Consumer<TgMessage> h : hs) {
                    try {
                        h.accept(m);
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void emitCallback(TlNode u) {
        try {
            TlNode peer = u.node("peer");
            TgCallback cb = new TgCallback();
            cb.queryId = u.l("query_id");
            cb.senderId = u.l("user_id");
            cb.messageId = u.i("msg_id");
            cb.chatId = peer == null ? cb.senderId : encodePeer(peer);
            cb.data = new String(u.bytes("data"), StandardCharsets.UTF_8);
            cb.isChannel = "peerChannel".equals(peer == null ? "" : peer.name);
            cb.senderName = userNames.getOrDefault(cb.senderId, "user");

            System.out.println("[cb] chat=" + cb.chatId + " user=" + cb.senderId + " data=" + cb.data);
            String prefix = cb.data.contains(":") ? cb.data.substring(0, cb.data.indexOf(':') + 1) : cb.data;
            for (Map.Entry<String, List<Consumer<TgCallback>>> e : callbackHandlers) {
                if (e.getKey().equals(prefix)) {
                    for (Consumer<TgCallback> h : e.getValue()) {
                        try {
                            h.accept(cb);
                        } catch (Throwable ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }
        } catch (Throwable e) {
            System.err.println("[mtproto] bad callback: " + e);
        }
    }

    private long encodePeer(TlNode peer) {
        return switch (peer.name) {
            case "peerUser" -> peer.l("user_id");
            case "peerChat" -> -peer.l("chat_id");
            case "peerChannel" -> encodeChannel(peer.l("channel_id"));
            default -> 0L;
        };
    }

    // ── markup / entities ─────────────────────────────────────────────────────

    TlNode buildMarkup(Markup markup) {
        TlNode mk = new TlNode("replyInlineMarkup");
        List<Object> rows = new ArrayList<>();
        for (List<Markup.Button> row : markup.rows) {
            List<Object> buttons = new ArrayList<>();
            for (Markup.Button b : row) {
                TlNode btn = new TlNode("keyboardInlineButton").put("text", b.text);
                if (b.url != null && !b.url.isEmpty()) {
                    btn.put("type", new TlNode("inlineButtonTypeUrl").put("url", b.url));
                } else {
                    btn.put("type", new TlNode("inlineButtonTypeCallback")
                            .put("data", (b.data == null ? "" : b.data).getBytes(StandardCharsets.UTF_8)));
                }
                if (b.style == 1) {
                    btn.put("style", new TlNode("keyboardButtonStyle").put("bg_primary", Boolean.TRUE));
                } else if (b.style == 2) {
                    btn.put("style", new TlNode("keyboardButtonStyle").put("bg_danger", Boolean.TRUE));
                } else if (b.style == 3) {
                    btn.put("style", new TlNode("keyboardButtonStyle").put("bg_success", Boolean.TRUE));
                }
                buttons.add(btn);
            }
            rows.add(new TlNode("keyboardInlineButtonRow").put("buttons", buttons));
        }
        mk.put("rows", rows);
        return mk;
    }

    private TlNode emptyMarkup() {
        TlNode mk = new TlNode("replyInlineMarkup");
        mk.put("rows", new ArrayList<>());
        return mk;
    }

    private long extractMessageId(TlNode updates, long randomId) {
        if (updates == null) return 0;
        long fallback = 0;
        for (Object o : updates.list("updates")) {
            if (!(o instanceof TlNode u)) continue;
            if ("updateMessageID".equals(u.name) && u.l("random_id") == randomId) return u.i("id");
            if ("updateNewMessage".equals(u.name) || "updateNewChannelMessage".equals(u.name)) {
                TlNode m = u.node("message");
                if (m != null && fallback == 0) fallback = m.i("id");
            }
        }
        return fallback;
    }

    // ── sending ───────────────────────────────────────────────────────────────

    @Override
    public long sendText(long chatId, String text, Markup markup) {
        return sendTextInternal(chatId, text, markup, 0);
    }

    @Override
    public long sendTextSync(long chatId, String text, Markup markup, long replyTo) {
        return sendTextInternal(chatId, text, markup, replyTo);
    }

    private long sendTextInternal(long chatId, String text, Markup markup, long replyTo) {
        if (!ready) return 0;
        try {
            HtmlText.Result r = HtmlText.parse(text);
            long randomId = ThreadLocalRandom.current().nextLong();
            TlNode q = new TlNode("messages.sendMessage")
                    .put("peer", inputPeer(chatId))
                    .put("message", r.text)
                    .put("random_id", randomId)
                    .put("no_webpage", Boolean.TRUE);
            if (replyTo > 0) {
                q.put("reply_to", new TlNode("inputReplyToMessage").put("reply_to_msg_id", (int) replyTo));
            }
            if (!r.entities.isEmpty()) q.put("entities", r.entities);
            if (markup != null && !markup.isEmpty()) q.put("reply_markup", buildMarkup(markup));
            return extractMessageId(mt.invoke(q), randomId);
        } catch (Exception e) {
            System.err.println("[mtproto] sendText failed: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public long replyText(TgMessage msg, String text, Markup markup) {
        return sendTextInternal(msg.chatId, text, markup, msg.id);
    }

    @Override
    public boolean editText(long chatId, long messageId, String text, Markup markup) {
        if (!ready) return false;
        try {
            HtmlText.Result r = HtmlText.parse(text);
            TlNode q = new TlNode("messages.editMessage")
                    .put("peer", inputPeer(chatId))
                    .put("id", (int) messageId)
                    .put("message", r.text);
            // An empty replyInlineMarkup (0 rows) is rejected with
            // REPLY_MARKUP_INVALID — omit the field to keep the old markup.
            if (markup != null && !markup.isEmpty()) q.put("reply_markup", buildMarkup(markup));
            if (!r.entities.isEmpty()) q.put("entities", r.entities);
            mt.invoke(q);
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] editText failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean editCaption(long chatId, long messageId, String caption, Markup markup) {
        return editText(chatId, messageId, caption, markup);
    }

    @Override
    public boolean editMarkup(long chatId, long messageId, Markup markup) {
        if (!ready) return false;
        try {
            TlNode q = new TlNode("messages.editMessage")
                    .put("peer", inputPeer(chatId))
                    .put("id", (int) messageId);
            if (markup != null && !markup.isEmpty()) q.put("reply_markup", buildMarkup(markup));
            mt.invoke(q);
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] editMarkup failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public long sendPhoto(long chatId, String photo, String caption, Markup markup) {
        return sendPhotoInternal(chatId, photo, caption, markup, 0);
    }

    @Override
    public long sendPhotoSync(long chatId, String photo, String caption, Markup markup, long replyTo) {
        return sendPhotoInternal(chatId, photo, caption, markup, replyTo);
    }

    private long sendPhotoInternal(long chatId, String photo, String caption, Markup markup, long replyTo) {
        if (!ready) return 0;
        try {
            byte[] data = readPhoto(photo);
            if (data == null || data.length == 0) return sendTextInternal(chatId, caption, markup, replyTo);
            long fileId = ThreadLocalRandom.current().nextLong() & 0x7fffffffffffffffL;
            int partSize = 512 * 1024;
            int parts = (data.length + partSize - 1) / partSize;
            for (int i = 0; i < parts; i++) {
                byte[] chunk = Arrays.copyOfRange(data, i * partSize, Math.min(data.length, (i + 1) * partSize));
                mt.invoke(new TlNode("upload.saveFilePart")
                        .put("file_id", fileId)
                        .put("file_part", i)
                        .put("bytes", chunk), 60);
            }
            TlNode inputFile = new TlNode("inputFile")
                    .put("id", fileId)
                    .put("parts", parts)
                    .put("name", "thumb.jpg")
                    .put("md5_checksum", "");
            HtmlText.Result r = HtmlText.parse(caption == null ? "" : caption);
            long randomId = ThreadLocalRandom.current().nextLong();
            TlNode q = new TlNode("messages.sendMedia")
                    .put("peer", inputPeer(chatId))
                    .put("media", new TlNode("inputMediaUploadedPhoto").put("file", inputFile))
                    .put("message", r.text)
                    .put("random_id", randomId);
            if (!r.entities.isEmpty()) q.put("entities", r.entities);
            if (markup != null && !markup.isEmpty()) q.put("reply_markup", buildMarkup(markup));
            if (replyTo > 0) {
                q.put("reply_to", new TlNode("inputReplyToMessage").put("reply_to_msg_id", (int) replyTo));
            }
            return extractMessageId(mt.invoke(q, 60), randomId);
        } catch (Exception e) {
            System.err.println("[mtproto] sendPhoto failed: " + e.getMessage());
            return sendTextInternal(chatId, caption, markup, replyTo);
        }
    }

    private byte[] readPhoto(String photo) {
        try {
            if (photo == null || photo.isEmpty()) return null;
            if (photo.startsWith("http")) {
                HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                HttpResponse<byte[]> res = c.send(HttpRequest.newBuilder(URI.create(photo))
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                return res.statusCode() == 200 ? res.body() : null;
            }
            Path p = Path.of(photo);
            return Files.isReadable(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean answerCallback(long queryId, String text, boolean alert) {
        if (!ready) return false;
        try {
            TlNode q = new TlNode("messages.setBotCallbackAnswer")
                    .put("query_id", queryId)
                    .put("cache_time", 0);
            if (text != null && !text.isEmpty()) q.put("message", text);
            if (alert) q.put("alert", Boolean.TRUE);
            mt.invoke(q);
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] answerCallback failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deleteMessage(long chatId, long messageId) {
        if (!ready) return false;
        try {
            List<Object> ids = new ArrayList<>();
            ids.add((int) messageId);
            mt.invoke(new TlNode("messages.deleteMessages").put("revoke", Boolean.TRUE).put("id", ids));
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] delete failed: " + e.getMessage());
            return false;
        }
    }

    // ── moderation ────────────────────────────────────────────────────────────

    @Override
    public boolean banMember(long chatId, long userId, long untilDate) {
        if (!ready) return false;
        try {
            if (isChannelId(chatId)) {
                TlNode rights = new TlNode("chatBannedRights")
                        .put("view_messages", Boolean.TRUE)
                        .put("until_date", untilDate > 0 ? (int) untilDate : 0);
                mt.invoke(new TlNode("channels.editBanned")
                        .put("channel", inputChannel(chatId))
                        .put("participant", inputUser(userId))
                        .put("banned_rights", rights));
                return true;
            }
            mt.invoke(new TlNode("messages.deleteChatUser")
                    .put("revoke_history", Boolean.TRUE)
                    .put("chat_id", -chatId)
                    .put("user_id", inputUser(userId)));
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] ban failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean unbanMember(long chatId, long userId) {
        if (!ready || !isChannelId(chatId)) return false;
        try {
            mt.invoke(new TlNode("channels.editBanned")
                    .put("channel", inputChannel(chatId))
                    .put("participant", inputUser(userId))
                    .put("banned_rights", new TlNode("chatBannedRights").put("until_date", 0)));
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] unban failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean muteMember(long chatId, long userId) {
        if (!ready || !isChannelId(chatId)) return false;
        try {
            TlNode rights = new TlNode("chatBannedRights")
                    .put("send_messages", Boolean.TRUE)
                    .put("send_media", Boolean.TRUE)
                    .put("send_stickers", Boolean.TRUE)
                    .put("send_gifs", Boolean.TRUE)
                    .put("send_games", Boolean.TRUE)
                    .put("send_inline", Boolean.TRUE)
                    .put("embed_links", Boolean.TRUE)
                    .put("send_polls", Boolean.TRUE)
                    .put("send_photos", Boolean.TRUE)
                    .put("send_videos", Boolean.TRUE)
                    .put("send_roundvideos", Boolean.TRUE)
                    .put("send_audios", Boolean.TRUE)
                    .put("send_voices", Boolean.TRUE)
                    .put("send_docs", Boolean.TRUE)
                    .put("send_plain", Boolean.TRUE)
                    .put("until_date", Integer.MAX_VALUE);
            mt.invoke(new TlNode("channels.editBanned")
                    .put("channel", inputChannel(chatId))
                    .put("participant", inputUser(userId))
                    .put("banned_rights", rights));
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] mute failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean promoteMember(long chatId, long userId, PromoteRights rights) {
        if (!ready || !isChannelId(chatId)) return false;
        try {
            TlNode r = new TlNode("chatAdminRights")
                    .put("change_info", rights.changeInfo())
                    .put("delete_messages", rights.deleteMessages())
                    .put("ban_users", rights.banUsers())
                    .put("invite_users", rights.inviteUsers())
                    .put("pin_messages", rights.pinMessages())
                    .put("manage_call", rights.manageVoiceChats())
                    .put("other", rights.manageVideoChats());
            mt.invoke(new TlNode("channels.editAdmin")
                    .put("channel", inputChannel(chatId))
                    .put("user_id", inputUser(userId))
                    .put("admin_rights", r));
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] promote failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean demoteMember(long chatId, long userId) {
        if (!ready || !isChannelId(chatId)) return false;
        try {
            mt.invoke(new TlNode("channels.editAdmin")
                    .put("channel", inputChannel(chatId))
                    .put("user_id", inputUser(userId))
                    .put("admin_rights", new TlNode("chatAdminRights")));
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] demote failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean pinMessage(long chatId, long messageId, boolean silent) {
        if (!ready) return false;
        try {
            TlNode q = new TlNode("messages.updatePinnedMessage")
                    .put("peer", inputPeer(chatId))
                    .put("id", (int) messageId);
            if (silent) q.put("silent", Boolean.TRUE);
            mt.invoke(q);
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] pin failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean unpinMessage(long chatId, long messageId) {
        if (!ready) return false;
        try {
            mt.invoke(new TlNode("messages.updatePinnedMessage")
                    .put("peer", inputPeer(chatId))
                    .put("id", (int) messageId)
                    .put("unpin", Boolean.TRUE));
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] unpin failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean unpinAllMessages(long chatId) {
        if (!ready) return false;
        try {
            mt.invoke(new TlNode("messages.unpinAllMessages").put("peer", inputPeer(chatId)));
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] unpinAll failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public MemberState getChatMemberState(long chatId, long userId) {
        if (!ready) return MemberState.UNKNOWN;
        try {
            if (chatId > 0) return MemberState.MEMBER;
            if (isChannelId(chatId)) {
                TlNode res = mt.invoke(new TlNode("channels.getParticipant")
                        .put("channel", inputChannel(chatId))
                        .put("participant", inputUser(userId)));
                TlNode p = res.node("participant");
                if (p == null) {
                    System.out.println("[memberState] chat=" + chatId + " user=" + userId
                            + " participant=null (resp=" + res.name + ")");
                    return MemberState.UNKNOWN;
                }
                if (System.getenv("NANDANI_TL_DEBUG") != null)
                    System.out.println("[memberState] chat=" + chatId + " user=" + userId
                            + " participant=" + p.name);
                return switch (p.name) {
                    case "channelParticipantCreator" -> MemberState.CREATOR;
                    case "channelParticipantAdmin" -> MemberState.ADMINISTRATOR;
                    case "channelParticipant" -> MemberState.MEMBER;
                    case "channelParticipantBanned" -> {
                        TlNode r = p.node("banned_rights");
                        boolean left = p.bool("left");
                        yield left ? MemberState.LEFT
                                : (r != null && r.bool("view_messages")) ? MemberState.BANNED : MemberState.MEMBER;
                    }
                    case "channelParticipantLeft" -> MemberState.LEFT;
                    default -> MemberState.UNKNOWN;
                };
            }
            TlNode res = mt.invoke(new TlNode("messages.getFullChat").put("chat_id", -chatId));
            TlNode full = res.node("full_chat");
            if (full == null) return MemberState.UNKNOWN;
            TlNode parts = full.node("participants");
            if (parts == null) return MemberState.MEMBER;
            for (Object o : parts.list("participants")) {
                if (!(o instanceof TlNode p)) continue;
                if (p.l("user_id") != userId) continue;
                return switch (p.name) {
                    case "chatParticipantCreator" -> MemberState.CREATOR;
                    case "chatParticipantAdmin" -> MemberState.ADMINISTRATOR;
                    default -> MemberState.MEMBER;
                };
            }
            return MemberState.LEFT;
        } catch (Exception e) {
            System.err.println("[mtproto] memberState failed: " + e.getMessage());
            return MemberState.UNKNOWN;
        }
    }

    @Override
    public String chatPublicUsername(long chatId) {
        return usernames.getOrDefault(chatId, "");
    }

    @Override
    public String createChatInviteLink(long chatId) {
        if (!ready) return "";
        try {
            TlNode res = mt.invoke(new TlNode("messages.exportChatInvite").put("peer", inputPeer(chatId)));
            return res.str("link");
        } catch (Exception e) {
            System.err.println("[mtproto] invite link failed: " + e.getMessage());
            return "";
        }
    }

    @Override
    public boolean approveJoinRequest(long chatId, long userId) {
        if (!ready) return false;
        try {
            mt.invoke(new TlNode("messages.hideChatJoinRequest")
                    .put("approved", Boolean.TRUE)
                    .put("peer", inputPeer(chatId))
                    .put("user_id", inputUser(userId)));
            return true;
        } catch (Exception e) {
            System.err.println("[mtproto] approve failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public long getRepliedSender(long chatId, long messageId) {
        return 0;
    }

    @Override
    public boolean sendTyping(long chatId) {
        if (!ready) return false;
        try {
            mt.invoke(new TlNode("messages.setTyping")
                    .put("peer", inputPeer(chatId))
                    .put("action", new TlNode("sendMessageTypingAction")));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean sendChatActionChoosingSticker(long chatId) {
        if (!ready) return false;
        try {
            mt.invoke(new TlNode("messages.setTyping")
                    .put("peer", inputPeer(chatId))
                    .put("action", new TlNode("sendMessageChooseStickerAction")));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean sendSticker(long chatId, String sticker, long replyTo) {
        return false;
    }

    // ── identity / plumbing ───────────────────────────────────────────────────

    @Override
    public long meId() {
        return myId;
    }

    @Override
    public String meUsername() {
        return myUsername;
    }

    @Override
    public String meFirstName() {
        return myFirstName;
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public String executeJson(String request) {
        return "{}";
    }

    @Override
    public void onCommand(String name, Consumer<TgMessage> handler) {
        commandHandlers.computeIfAbsent(name.toLowerCase(Locale.ROOT),
                k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void onCallback(String prefix, Consumer<TgCallback> handler) {
        for (Map.Entry<String, List<Consumer<TgCallback>>> e : callbackHandlers) {
            if (e.getKey().equals(prefix)) {
                e.getValue().add(handler);
                return;
            }
        }
        List<Consumer<TgCallback>> list = new CopyOnWriteArrayList<>();
        list.add(handler);
        callbackHandlers.add(Map.entry(prefix, list));
    }

    @Override
    public void onMessage(Consumer<TgMessage> handler) {
        messageHandlers.add(handler);
    }

    public MtProto raw() {
        return mt;
    }
}
