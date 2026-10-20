package music.nandani.tg;

import music.nandani.mtproto.MtProto;
import music.nandani.mtproto.TlNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MTProto-backed assistant (user account) logged in from a Pyrogram-style
 * session string — the same STRING_SESSION flow the Rust bot uses, no TDLib
 * and no OTP required.
 *
 * Decoded string layout (validated against a live key): byte 0 = data centre,
 * bytes 6..262 = 256-byte auth key, the trailing 9 bytes carry the user id.
 */
public final class MtAssistantSession implements AssistantSession {

    private final String name;
    private final int apiId;
    private final String apiHash;

    private final int dc;
    private final byte[] authKey;

    private MtProto mt;
    private volatile boolean ready;
    private volatile long myUserId;
    private volatile String myUsername = "";
    private volatile String myFirstName = "";

    private final Map<Long, Long> accessHashes = new ConcurrentHashMap<>();
    private final Map<Long, TlNode> groupCalls = new ConcurrentHashMap<>();
    private ScheduledExecutorService pinger;

    public MtAssistantSession(String name, int apiId, String apiHash, String sessionData) {
        this.name = name;
        this.apiId = apiId;
        this.apiHash = apiHash;
        int[] dcBox = new int[1];
        this.authKey = decodeSession(sessionData, dcBox);
        this.dc = dcBox[0];
    }

    // ── session-string decoding ───────────────────────────────────────────────

    /** Accepts a Pyrogram session string or our own "v1\ndc\n<hex>" format. */
    static byte[] decodeSession(String data, int[] dcOut) {
        String s = data == null ? "" : data.trim();
        if (s.startsWith("v1")) {
            String[] parts = s.split("\\R");
            dcOut[0] = Integer.parseInt(parts[1].trim());
            return java.util.HexFormat.of().parseHex(parts[2].trim());
        }
        byte[] raw = Base64.getUrlDecoder().decode(s + "=".repeat((4 - s.length() % 4) % 4));
        if (raw.length >= 258 && raw[0] >= 1 && raw[0] <= 5) {
            dcOut[0] = raw[0] & 0xff;
            return java.util.Arrays.copyOfRange(raw, 6, 262);
        }
        throw new IllegalArgumentException("unrecognised assistant session format");
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public boolean start(long readyTimeoutMs) {
        try {
            mt = new MtProto(apiId, apiHash, dc);
            mt.onUpdate(this::onUpdates);
            mt.importSession("v1\n" + dc + "\n"
                    + java.util.HexFormat.of().formatHex(authKey) + "\n");
            mt.connect();
            mt.startReader();

            TlNode res = mt.invoke(new TlNode("users.getFullUser")
                    .put("id", new TlNode("inputUserSelf")), 20);
            for (Object o : res.list("users")) {
                if (o instanceof TlNode u && "user".equals(u.name)) {
                    myUserId = u.l("id");
                    myUsername = u.str("username");
                    myFirstName = u.str("first_name");
                    if (u.has("access_hash")) accessHashes.put(myUserId, u.l("access_hash"));
                }
            }
            if (myUserId == 0) throw new java.io.IOException("users.getUsers returned no self user");
            refreshPeers();

            pinger = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "assistant-" + name + "-ping");
                t.setDaemon(true);
                return t;
            });
            pinger.scheduleAtFixedRate(() -> {
                try {
                    if (!mt.connected()) return;
                    mt.invoke(new TlNode("ping").put("ping_id", System.nanoTime()), 20);
                } catch (Throwable ignored) {
                }
            }, 60, 60, TimeUnit.SECONDS);

            ready = true;
            System.out.println("[assistant " + name + "] mtproto session resumed (dc" + dc
                    + ") as " + myFirstName + " @" + myUsername + " (" + myUserId + ")");
            return true;
        } catch (Throwable e) {
            System.err.println("[assistant " + name + "] login failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        ready = false;
        if (pinger != null) pinger.shutdownNow();
        if (mt != null) mt.close();
    }

    // ── identity ──────────────────────────────────────────────────────────────

    @Override
    public boolean isReady() { return ready; }

    @Override
    public long myUserId() { return myUserId; }

    @Override
    public String myUsername() { return myUsername; }

    // ── peers ─────────────────────────────────────────────────────────────────

    private void onUpdates(TlNode upd) {
        for (Object o : upd.list("chats")) {
            if (o instanceof TlNode c && c.has("access_hash")) {
                long raw = c.l("id");
                long hash = c.l("access_hash");
                // cache under BOTH the raw channel id and the marked -100… id
                accessHashes.put(raw, hash);
                accessHashes.put(-(raw + 1000000000000L), hash);
            }
        }
        for (Object o : upd.list("users")) {
            if (o instanceof TlNode p && p.has("access_hash")) {
                accessHashes.put(p.l("id"), p.l("access_hash"));
            }
        }
    }

    /** Users (unlike bots) can list dialogs — the cleanest peer-cache source. */
    private void refreshPeers() {
        try {
            TlNode res = mt.invoke(new TlNode("messages.getDialogs")
                    .put("offset_date", 0)
                    .put("offset_id", 0)
                    .put("offset_peer", new TlNode("inputPeerEmpty"))
                    .put("limit", 500)
                    .put("hash", 0), 40);
            onUpdates(res);
            System.out.println("[assistant " + name + "] peer cache: " + accessHashes.size() + " entries");
        } catch (Throwable e) {
            System.err.println("[assistant " + name + "] refreshPeers: " + e.getMessage());
        }
    }

    private static boolean isChannelId(long chatId) {
        return chatId <= -1000000000000L;
    }

    private static long channelOf(long chatId) {
        return -(chatId + 1000000000000L);
    }

    private TlNode inputChannel(long chatId) {
        long cid = isChannelId(chatId) ? channelOf(chatId) : chatId;
        Long hash = accessHashes.get(chatId);
        if (hash == null) hash = accessHashes.get(cid);
        if (hash == null) {
            // Not in the dialog cache — refresh once and re-lookup.
            refreshPeers();
            hash = accessHashes.get(chatId);
            if (hash == null) hash = accessHashes.get(cid);
        }
        return new TlNode("inputChannel")
                .put("channel_id", cid)
                .put("access_hash", hash == null ? 0L : hash);
    }

    private TlNode inputPeerSelf() {
        return new TlNode("inputPeerSelf");
    }

    // ── membership ────────────────────────────────────────────────────────────

    @Override
    public boolean isChatMember(long chatId) {
        Boolean r = isChatMemberOnce(chatId);
        if (r == null) {
            // CHANNEL_INVALID = stale peer cache; refresh dialogs and retry once.
            refreshPeers();
            r = isChatMemberOnce(chatId);
        }
        return Boolean.TRUE.equals(r);
    }

    /** null = transient peer-resolution failure (cache refreshed and retried). */
    private Boolean isChatMemberOnce(long chatId) {
        try {
            if (isChannelId(chatId)) {
                mt.invoke(new TlNode("channels.getParticipant")
                        .put("channel", inputChannel(chatId))
                        .put("participant", inputPeerSelf()), 20);
                return true;
            }
            TlNode full = mt.invoke(new TlNode("messages.getFullChat")
                    .put("chat_id", -chatId), 20);
            TlNode parts = full.node("full_chat").node("participants");
            for (Object o : parts.list("participants")) {
                TlNode p = (TlNode) o;
                TlNode peer = p.node("peer");
                if ("peerUser".equals(peer.name) && peer.l("user_id") == myUserId) return true;
            }
            return false;
        } catch (Throwable e) {
            String msg = String.valueOf(e.getMessage());
            // USER_NOT_PARTICIPANT / CHAT_MEMBER_NOT_FOUND = simply not a member
            if (msg.contains("CHANNEL_INVALID")) return null;
            if (!msg.contains("USER_NOT_PARTICIPANT")
                    && !msg.contains("CHAT_MEMBER_NOT_FOUND")
                    && !msg.contains("CHANNEL_PRIVATE")) {
                System.err.println("[assistant " + name + "] isChatMember " + chatId + ": " + msg);
            }
            return false;
        }
    }

    @Override
    public boolean joinChat(long chatId) {
        try {
            if (isChannelId(chatId)) {
                mt.invoke(new TlNode("channels.joinChannel")
                        .put("channel", inputChannel(chatId)), 25);
                return true;
            }
            // basic group: add ourselves is impossible; the bot must add us.
            mt.invoke(new TlNode("messages.addChatUser")
                    .put("chat_id", -chatId)
                    .put("user_id", new TlNode("inputUserSelf")), 20);
            return true;
        } catch (Throwable e) {
            System.err.println("[assistant " + name + "] joinChat " + chatId + ": " + e.getMessage());
            return false;
        }
    }

    // ── group calls ───────────────────────────────────────────────────────────

    /** Resolves (and caches) the active group call of a chat. */
    private TlNode groupCall(long chatId) {
        TlNode cached = groupCalls.get(chatId);
        if (cached != null) return cached;
        try {
            TlNode call;
            if (isChannelId(chatId)) {
                TlNode res = mt.invoke(new TlNode("channels.getFullChannel")
                        .put("channel", inputChannel(chatId)), 25);
                call = res.node("full_chat").node("call");
            } else {
                TlNode res = mt.invoke(new TlNode("messages.getFullChat")
                        .put("chat_id", -chatId), 25);
                call = res.node("full_chat").node("call");
            }
            // channelFull.call is typed InputGroupCall — the constructor that
            // comes back is "inputGroupCall", which is exactly what
            // phone.joinGroupCall expects. (Full GroupCall objects only
            // appear in updates.)
            if (call == null || !"inputGroupCall".equals(call.name)) {
                System.err.println("[assistant " + name + "] groupCall " + chatId
                        + ": no active call (full_chat.call="
                        + (call == null ? "null" : call.name) + ")");
                return null;
            }
            groupCalls.put(chatId, call);
            return call;
        } catch (Throwable e) {
            System.err.println("[assistant " + name + "] groupCall " + chatId + ": " + e.getMessage());
            return null;
        }
    }

    private TlNode inputGroupCall(TlNode call) {
        return new TlNode("inputGroupCall")
                .put("id", call.l("id"))
                .put("access_hash", call.l("access_hash"));
    }

    @Override
    public String joinGroupCall(long chatId, String offer, boolean video) {
        try {
            TlNode call = groupCall(chatId);
            if (call == null) return "";
            System.err.println("[assistant " + name + "] joining call " + chatId
                    + " (id=" + call.l("id") + ", video=" + video + ", offer=" + offer.length() + "B)");
            TlNode updates = mt.invoke(new TlNode("phone.joinGroupCall")
                    .put("call", inputGroupCall(call))
                    .put("join_as", inputPeerSelf())
                    // TL flags are presence-encoded: putting the key AT ALL
                    // sets the flag bit, regardless of value. video_stopped
                    // must only appear when video is actually OFF — putting
                    // it with "false" made every join register as
                    // video_stopped=true server-side (no video tile).
                    .put("video_stopped", video ? null : Boolean.TRUE)
                    .put("params", new TlNode("dataJSON").put("data", offer)), 45);
            groupCalls.remove(chatId); // re-resolve next time (call may end)
            return findAnswer(updates);
        } catch (Throwable e) {
            System.err.println("[assistant " + name + "] joinGroupCall: " + e.getMessage());
            groupCalls.remove(chatId);
            return "";
        }
    }

    /** SDP answer rides in updateGroupCallConnection.params.data. */
    private static String findAnswer(TlNode updates) {
        if (updates == null) return "";
        List<TlNode> stack = new ArrayList<>();
        stack.add(updates);
        while (!stack.isEmpty()) {
            TlNode n = stack.remove(stack.size() - 1);
            if ("updateGroupCallConnection".equals(n.name)) {
                return n.node("params").str("data");
            }
            for (String key : new String[]{"updates", "chats", "users"}) {
                for (Object o : n.list(key)) if (o instanceof TlNode t) stack.add(t);
            }
        }
        return "";
    }

    @Override
    public boolean leaveGroupCall(long chatId) {
        try {
            TlNode call = groupCalls.remove(chatId);
            if (call == null) return true;
            mt.invoke(new TlNode("phone.leaveGroupCall")
                    .put("call", inputGroupCall(call))
                    .put("source", 0), 20);
            return true;
        } catch (Throwable e) {
            System.err.println("[assistant " + name + "] leaveGroupCall: " + e.getMessage());
            return false;
        }
    }

    @Override
    public Bot.MemberState chatMemberState(long chatId, long userId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (isChannelId(chatId)) {
                    TlNode res = mt.invoke(new TlNode("channels.getParticipant")
                            .put("channel", inputChannel(chatId))
                            .put("participant", inputPeerUser(userId)), 20);
                    TlNode p = res.node("participant");
                    if (p == null) return Bot.MemberState.UNKNOWN;
                    return switch (p.name) {
                        case "channelParticipantCreator" -> Bot.MemberState.CREATOR;
                        case "channelParticipantAdmin" -> Bot.MemberState.ADMINISTRATOR;
                        case "channelParticipantLeft" -> Bot.MemberState.LEFT;
                        case "channelParticipantBanned" -> Bot.MemberState.BANNED;
                        default -> Bot.MemberState.MEMBER;
                    };
                }
                TlNode full = mt.invoke(new TlNode("messages.getFullChat")
                        .put("chat_id", -chatId), 20);
                TlNode parts = full.node("full_chat").node("participants");
                if (parts != null) {
                    for (Object o : parts.list("participants")) {
                        TlNode p = (TlNode) o;
                        TlNode peer = p.node("peer");
                        if ("peerUser".equals(peer.name) && peer.l("user_id") == userId) {
                            return switch (p.name) {
                                case "chatParticipantCreator" -> Bot.MemberState.CREATOR;
                                case "chatParticipantAdmin" -> Bot.MemberState.ADMINISTRATOR;
                                default -> Bot.MemberState.MEMBER;
                            };
                        }
                    }
                }
                return Bot.MemberState.LEFT;
            } catch (Throwable e) {
                String msg = String.valueOf(e.getMessage());
                if (attempt == 0 && (msg.contains("CHANNEL_INVALID") || msg.contains("PEER_ID_INVALID"))) {
                    refreshPeers();
                    continue;
                }
                if (!msg.contains("USER_NOT_PARTICIPANT")
                        && !msg.contains("CHAT_MEMBER_NOT_FOUND")
                        && !msg.contains("CHANNEL_PRIVATE")) {
                    System.err.println("[assistant " + name + "] memberState " + chatId + "/" + userId + ": " + msg);
                }
                return Bot.MemberState.UNKNOWN;
            }
        }
        return Bot.MemberState.UNKNOWN;
    }

    private TlNode inputPeerUser(long userId) {
        return new TlNode("inputPeerUser")
                .put("user_id", userId)
                .put("access_hash", accessHashes.getOrDefault(userId, 0L));
    }

}
