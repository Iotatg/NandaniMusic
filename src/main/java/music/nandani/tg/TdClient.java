package music.nandani.tg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * TDLib-backed bot client. Implements the shared bot facade
 * (send/edit/delete/member ops), but over TDLib's JSON API instead of the
 * hand-rolled MTProto stack.
 */
public final class TdClient extends TdSession implements Bot {

    private final String token;

    private final List<Consumer<TgMessage>> messageHandlers = new CopyOnWriteArrayList<>();
    private final List<Consumer<TgCallback>> callbackHandlers = new CopyOnWriteArrayList<>();
    private final Map<String, List<Consumer<TgMessage>>> commandHandlers = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<TgCallback>>> callbackPrefixHandlers = new ConcurrentHashMap<>();

    private record ChatInfo(boolean isPrivate, boolean isChannel, String username) {}
    private final Map<Long, ChatInfo> chats = new ConcurrentHashMap<>();
    private final Map<Long, String> userNames = new ConcurrentHashMap<>();

    public TdClient(String token, String libName) {
        super("bot", libName);
        this.token = token;
        on("updateNewChat", this::onNewChat);
        on("updateUser", this::onUser);
        on("updateNewMessage", n -> dispatchMessage(n.path("message")));
        on("updateNewCallbackQuery", this::onCallbackQuery);
    }

    @Override
    protected void onAuthState(JsonNode state) {
        String t = state.path("@type").asText();
        switch (t) {
            case "authorizationStateWaitTdlibParameters" -> sendTdlibParameters();
            case "authorizationStateWaitPhoneNumber", "authorizationStateWaitPremiumPurchase" -> {
                ObjectNode r = obj();
                r.put("@type", "checkAuthenticationBotToken");
                r.put("token", token);
                send(r.toString());
            }
            case "authorizationStateWaitCode" -> System.err.println("[bot] unexpected code prompt");
            case "authorizationStateReady" -> {
                ready.set(true);
                onReady();
                System.out.println("[bot] authorized as @" + myUsername + " (" + myUserId + ")");
            }
            case "authorizationStateClosed" -> running.set(false);
            default -> { }
        }
    }

    private void onNewChat(JsonNode n) {
        JsonNode c = n.path("chat");
        long id = c.path("id").asLong();
        String type = c.path("type").path("@type").asText();
        boolean priv = "chatTypePrivate".equals(type) || "chatTypeSecret".equals(type);
        boolean chan = "chatTypeSupergroup".equals(type)
                && c.path("type").path("is_channel").asBoolean(false);
        chats.put(id, new ChatInfo(priv, chan, c.path("username").asText("")));
    }

    private void onUser(JsonNode n) {
        JsonNode u = n.path("user");
        String first = u.path("first_name").asText("");
        String last = u.path("last_name").asText("");
        String full = (first + " " + last).trim();
        if (!full.isEmpty()) userNames.put(u.path("id").asLong(), full);
    }

    private void dispatchMessage(JsonNode m) {
        TgMessage msg = parseMessage(m);
        if (msg == null) return;
        for (Consumer<TgMessage> h : messageHandlers) h.accept(msg);
        if (!msg.text.startsWith("/")) return;
        String cmd = msg.text.substring(1).split("[ @]", 2)[0].toLowerCase(java.util.Locale.ROOT);
        List<Consumer<TgMessage>> hs = commandHandlers.get(cmd);
        if (hs != null) for (Consumer<TgMessage> h : hs) h.accept(msg);
    }

    private void onCallbackQuery(JsonNode n) {
        TgCallback cb = new TgCallback();
        cb.queryId = n.path("id").asLong();
        cb.chatId = n.path("chat_id").asLong();
        cb.messageId = n.path("message_id").asLong();
        cb.senderId = n.path("sender_user_id").asLong();
        cb.senderName = userNames.getOrDefault(cb.senderId, "user");
        String data = n.path("data").asText("");
        try {
            cb.data = new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            cb.data = data;
        }
        ChatInfo ci = chats.get(cb.chatId);
        cb.isChannel = ci != null && ci.isChannel();
        for (Consumer<TgCallback> h : callbackHandlers) h.accept(cb);
        String prefix = cb.data.contains(":") ? cb.data.substring(0, cb.data.indexOf(':') + 1) : cb.data;
        List<Consumer<TgCallback>> hs = callbackPrefixHandlers.get(prefix);
        if (hs != null) for (Consumer<TgCallback> h : hs) h.accept(cb);
    }

    private TgMessage parseMessage(JsonNode m) {
        if (m == null || m.isMissingNode()) return null;
        TgMessage msg = new TgMessage();
        msg.chatId = m.path("chat_id").asLong();
        msg.id = m.path("id").asLong();
        JsonNode sender = m.path("sender_id");
        if ("messageSenderUser".equals(sender.path("@type").asText())) {
            msg.senderId = sender.path("user_id").asLong();
        } else if ("messageSenderChat".equals(sender.path("@type").asText())) {
            msg.senderId = sender.path("chat_id").asLong();
        }
        ChatInfo ci = chats.get(msg.chatId);
        msg.isPrivate = ci != null && ci.isPrivate();
        msg.isChannel = ci != null && ci.isChannel();
        msg.firstName = userNames.getOrDefault(msg.senderId, "");
        msg.lastName = "";

        JsonNode content = m.path("content");
        String ct = content.path("@type").asText();
        switch (ct) {
            case "messageText" -> msg.text = content.path("text").path("text").asText("");
            case "messagePhoto" -> {
                msg.mediaKind = TgMessage.MediaKind.PHOTO;
                msg.text = content.path("caption").path("text").asText("");
            }
            case "messageVideo" -> {
                msg.mediaKind = TgMessage.MediaKind.VIDEO;
                msg.text = content.path("caption").path("text").asText("");
            }
            case "messageDocument" -> {
                msg.mediaKind = TgMessage.MediaKind.DOCUMENT;
                msg.text = content.path("caption").path("text").asText("");
            }
            case "messageSticker" -> {
                msg.mediaKind = TgMessage.MediaKind.STICKER;
                msg.stickerEmoji = content.path("sticker").path("emoji").asText("");
            }
            default -> msg.text = "";
        }
        JsonNode reply = m.path("reply_to");
        if ("messageReplyToMessage".equals(reply.path("@type").asText())) {
            msg.replyTo = reply.path("message_id").asLong();
        }
        return msg;
    }

    // ------------------------------------------------------------------
    // Bot facade
    // ------------------------------------------------------------------

    @Override
    public long sendText(long chatId, String text, Markup markup) {
        return sendTextSync(chatId, text, markup, 0);
    }

    @Override
    public long sendTextSync(long chatId, String text, Markup markup, long replyTo) {
        ObjectNode r = obj();
        r.put("@type", "sendMessage");
        r.put("chat_id", chatId);
        if (replyTo != 0) {
            ObjectNode rt = r.putObject("reply_to");
            rt.put("@type", "inputMessageReplyToMessage");
            rt.put("message_id", replyTo);
        }
        ObjectNode content = r.putObject("input_message_content");
        content.put("@type", "inputMessageText");
        ObjectNode txt = content.putObject("text");
        txt.put("text", text);
        txt.put("parse_mode", "textParseModeHTML");
        content.put("disable_web_page_preview", true);
        if (markup != null && !markup.isEmpty()) r.set("reply_markup", markupJson(markup));
        JsonNode res = request(r, 30_000);
        if (res == null || "error".equals(res.path("@type").asText())) return 0;
        return res.path("id").asLong();
    }

    @Override
    public long replyText(TgMessage msg, String text, Markup markup) {
        return sendTextSync(msg.chatId, text, markup, msg.id);
    }

    @Override
    public boolean editText(long chatId, long messageId, String text, Markup markup) {
        if (messageId == 0) return false;
        ObjectNode r = obj();
        r.put("@type", "editMessageText");
        r.put("chat_id", chatId);
        r.put("message_id", messageId);
        ObjectNode content = r.putObject("input_message_content");
        content.put("@type", "inputMessageText");
        ObjectNode txt = content.putObject("text");
        txt.put("text", text);
        txt.put("parse_mode", "textParseModeHTML");
        content.put("disable_web_page_preview", true);
        r.set("reply_markup", markup == null ? obj().put("@type", "replyMarkupInlineKeyboard")
                : markupJson(markup));
        JsonNode res = request(r, 30_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public long sendPhoto(long chatId, String photo, String caption, Markup markup) {
        return sendPhotoSync(chatId, photo, caption, markup, 0);
    }

    @Override
    public long sendPhotoSync(long chatId, String photo, String caption, Markup markup, long replyTo) {
        ObjectNode r = obj();
        r.put("@type", "sendMessage");
        r.put("chat_id", chatId);
        if (replyTo != 0) {
            ObjectNode rt = r.putObject("reply_to");
            rt.put("@type", "inputMessageReplyToMessage");
            rt.put("message_id", replyTo);
        }
        ObjectNode content = r.putObject("input_message_content");
        content.put("@type", "inputMessagePhoto");
        content.set("photo", inputFile(photo));
        content.put("width", 0);
        content.put("height", 0);
        if (caption != null && !caption.isEmpty()) {
            ObjectNode cap = content.putObject("caption");
            cap.put("text", caption);
            cap.put("parse_mode", "textParseModeHTML");
        }
        if (markup != null && !markup.isEmpty()) r.set("reply_markup", markupJson(markup));
        JsonNode res = request(r, 60_000);
        if (res == null || "error".equals(res.path("@type").asText())) return 0;
        return res.path("id").asLong();
    }

    @Override
    public boolean editCaption(long chatId, long messageId, String caption, Markup markup) {
        ObjectNode r = obj();
        r.put("@type", "editMessageCaption");
        r.put("chat_id", chatId);
        r.put("message_id", messageId);
        ObjectNode cap = r.putObject("caption");
        cap.put("text", caption == null ? "" : caption);
        cap.put("parse_mode", "textParseModeHTML");
        if (markup != null) r.set("reply_markup", markupJson(markup));
        JsonNode res = request(r, 30_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public boolean editMarkup(long chatId, long messageId, Markup markup) {
        ObjectNode r = obj();
        r.put("@type", "editMessageReplyMarkup");
        r.put("chat_id", chatId);
        r.put("message_id", messageId);
        r.set("reply_markup", markup == null ? obj().put("@type", "replyMarkupInlineKeyboard")
                : markupJson(markup));
        JsonNode res = request(r, 30_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public boolean answerCallback(long queryId, String text, boolean alert) {
        ObjectNode r = obj();
        r.put("@type", "getCallbackQueryAnswer");
        r.put("callback_query_id", queryId);
        ObjectNode p = r.putObject("payload");
        p.put("@type", "callbackQueryPayloadData");
        p.put("data", Base64.getEncoder().encodeToString(
                (text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        // TDLib shows a toast for answerCallbackQuery; alert text goes through
        // the answer payload on Bot API. The call is kept for compatibility.
        request(r, 15_000);
        if (alert && text != null && !text.isEmpty()) {
            // No direct alert in TDLib JSON; the toast is the equivalent.
        }
        return true;
    }

    @Override
    public boolean deleteMessage(long chatId, long messageId) {
        ObjectNode r = obj();
        r.put("@type", "deleteMessages");
        r.put("chat_id", chatId);
        ArrayNode ids = r.putArray("message_ids");
        ids.add(messageId);
        r.put("revoke", true);
        JsonNode res = request(r, 15_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public boolean banMember(long chatId, long userId, long untilDate) {
        return setStatus(chatId, userId, "chatMemberStatusBanned", untilDate);
    }

    @Override
    public boolean unbanMember(long chatId, long userId) {
        return setStatus(chatId, userId, "chatMemberStatusLeft", 0);
    }

    private boolean setStatus(long chatId, long userId, String statusType, long until) {
        ObjectNode r = obj();
        r.put("@type", "setChatMemberStatus");
        r.put("chat_id", chatId);
        r.set("member_id", senderUser(userId));
        ObjectNode st = r.putObject("status");
        st.put("@type", statusType);
        if ("chatMemberStatusBanned".equals(statusType)) st.put("banned_until_date", until);
        JsonNode res = request(r, 20_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public boolean muteMember(long chatId, long userId) {
        ObjectNode r = obj();
        r.put("@type", "restrictChatMember");
        r.put("chat_id", chatId);
        r.set("member_id", senderUser(userId));
        ObjectNode perms = r.putObject("permissions");
        perms.put("can_send_messages", false);
        perms.put("can_send_audios", false);
        perms.put("can_send_documents", false);
        perms.put("can_send_photos", false);
        perms.put("can_send_videos", false);
        perms.put("can_send_video_notes", false);
        perms.put("can_send_voice_notes", false);
        perms.put("can_send_polls", false);
        perms.put("can_send_other_messages", false);
        perms.put("can_add_web_page_previews", false);
        perms.put("can_change_info", false);
        perms.put("can_invite_users", false);
        perms.put("can_pin_messages", false);
        perms.put("can_manage_topics", false);
        r.put("use_independent_chat_permissions", true);
        JsonNode res = request(r, 20_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public boolean promoteMember(long chatId, long userId, PromoteRights rights) {
        ObjectNode r = obj();
        r.put("@type", "setChatMemberStatus");
        r.put("chat_id", chatId);
        r.set("member_id", senderUser(userId));
        ObjectNode st = r.putObject("status");
        st.put("@type", "chatMemberStatusAdministrator");
        st.put("can_manage_chat", true);
        st.put("can_change_info", rights.changeInfo());
        st.put("can_delete_messages", rights.deleteMessages());
        st.put("can_invite_users", rights.inviteUsers());
        st.put("can_restrict_members", rights.banUsers());
        st.put("can_pin_messages", rights.pinMessages());
        st.put("can_manage_video_chats", rights.manageVideoChats());
        st.put("can_promote_members", false);
        st.put("can_manage_topics", false);
        st.put("is_anonymous", false);
        JsonNode res = request(r, 20_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public boolean demoteMember(long chatId, long userId) {
        return setStatus(chatId, userId, "chatMemberStatusMember", 0);
    }

    @Override
    public boolean pinMessage(long chatId, long messageId, boolean silent) {
        ObjectNode r = obj();
        r.put("@type", "pinChatMessage");
        r.put("chat_id", chatId);
        r.put("message_id", messageId);
        r.put("disable_notification", silent);
        r.put("only_for_self", false);
        JsonNode res = request(r, 20_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public boolean unpinMessage(long chatId, long messageId) {
        ObjectNode r = obj();
        r.put("@type", "unpinChatMessage");
        r.put("chat_id", chatId);
        r.put("message_id", messageId);
        JsonNode res = request(r, 20_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public boolean unpinAllMessages(long chatId) {
        ObjectNode r = obj();
        r.put("@type", "unpinAllChatMessages");
        r.put("chat_id", chatId);
        JsonNode res = request(r, 20_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public MemberState getChatMemberState(long chatId, long userId) {
        ObjectNode r = obj();
        r.put("@type", "getChatMember");
        r.put("chat_id", chatId);
        r.set("member_id", senderUser(userId));
        JsonNode res = request(r, 15_000);
        if (res == null) return MemberState.UNKNOWN;
        String st = res.path("status").path("@type").asText();
        return switch (st) {
            case "chatMemberStatusCreator" -> MemberState.CREATOR;
            case "chatMemberStatusAdministrator" -> MemberState.ADMINISTRATOR;
            case "chatMemberStatusMember", "chatMemberStatusRestricted" -> MemberState.MEMBER;
            case "chatMemberStatusBanned" -> MemberState.BANNED;
            case "chatMemberStatusLeft" -> MemberState.LEFT;
            default -> MemberState.UNKNOWN;
        };
    }

    @Override
    public String chatPublicUsername(long chatId) {
        ChatInfo ci = chats.get(chatId);
        return ci == null ? "" : ci.username();
    }

    @Override
    public String createChatInviteLink(long chatId) {
        ObjectNode r = obj();
        r.put("@type", "createChatInviteLink");
        r.put("chat_id", chatId);
        r.put("name", "NandaniMusic");
        r.put("expiration_date", 0);
        r.put("member_limit", 0);
        r.put("creates_join_request", false);
        JsonNode res = request(r, 20_000);
        return res == null ? "" : res.path("invite_link").asText("");
    }

    @Override
    public boolean approveJoinRequest(long chatId, long userId) {
        ObjectNode r = obj();
        r.put("@type", "processChatJoinRequest");
        r.put("chat_id", chatId);
        r.put("user_id", userId);
        r.put("approve", true);
        JsonNode res = request(r, 15_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public long getRepliedSender(long chatId, long messageId) {
        ObjectNode r = obj();
        r.put("@type", "getMessage");
        r.put("chat_id", chatId);
        r.put("message_id", messageId);
        JsonNode res = request(r, 15_000);
        if (res == null) return 0;
        JsonNode s = res.path("sender_id");
        return "messageSenderUser".equals(s.path("@type").asText()) ? s.path("user_id").asLong() : 0;
    }

    @Override
    public boolean sendChatActionChoosingSticker(long chatId) {
        ObjectNode r = obj();
        r.put("@type", "sendChatAction");
        r.put("chat_id", chatId);
        r.putObject("action").put("@type", "chatActionChoosingSticker");
        send(r.toString());
        return true;
    }

    @Override
    public boolean sendSticker(long chatId, String sticker, long replyTo) {
        ObjectNode r = obj();
        r.put("@type", "sendMessage");
        r.put("chat_id", chatId);
        if (replyTo != 0) {
            ObjectNode rt = r.putObject("reply_to");
            rt.put("@type", "inputMessageReplyToMessage");
            rt.put("message_id", replyTo);
        }
        ObjectNode content = r.putObject("input_message_content");
        content.put("@type", "inputMessageSticker");
        content.set("sticker", inputFile(sticker));
        content.put("width", 512);
        content.put("height", 512);
        JsonNode res = request(r, 30_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public long meId() { return myUserId; }

    @Override
    public String meUsername() { return myUsername; }

    @Override
    public String meFirstName() { return myFirstName; }

    @Override
    public boolean ready() { return ready.get(); }

    @Override
    public String executeJson(String request) {
        try {
            JsonNode req = JSON.readTree(request);
            if (req instanceof ObjectNode on) {
                JsonNode r = execute(on);
                return r == null ? "{}" : r.toString();
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
        return "{}";
    }

    @Override
    public void onCommand(String name, Consumer<TgMessage> handler) {
        commandHandlers.computeIfAbsent(name.toLowerCase(java.util.Locale.ROOT),
                k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void onCallback(String prefix, Consumer<TgCallback> handler) {
        callbackPrefixHandlers.computeIfAbsent(prefix, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void onMessage(Consumer<TgMessage> handler) {
        messageHandlers.add(handler);
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    public static ObjectNode senderUser(long userId) {
        ObjectNode n = obj();
        n.put("@type", "messageSenderUser");
        n.put("user_id", userId);
        return n;
    }

    public static ObjectNode inputFile(String ref) {
        ObjectNode f = obj();
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            f.put("@type", "inputFileRemote");
            f.put("remote", obj().put("id", ref));
        } else {
            f.put("@type", "inputFileLocal");
            f.put("path", ref);
        }
        return f;
    }

    public static ObjectNode markupJson(Markup markup) {
        ObjectNode root = obj();
        root.put("@type", "replyMarkupInlineKeyboard");
        ArrayNode rows = root.putArray("rows");
        for (List<Markup.Button> row : markup.rows) {
            ArrayNode r = rows.addArray();
            for (Markup.Button b : row) {
                ObjectNode btn = r.addObject();
                btn.put("text", b.text);
                ObjectNode type = btn.putObject("type");
                if (b.url != null) {
                    type.put("@type", "inlineKeyboardButtonTypeUrl");
                    type.put("url", b.url);
                } else {
                    type.put("@type", "inlineKeyboardButtonTypeCallback");
                    type.put("data", Base64.getEncoder().encodeToString(
                            (b.data == null ? "" : b.data).getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
        return root;
    }

    public List<String> commandNames() {
        return new ArrayList<>(commandHandlers.keySet());
    }
}
