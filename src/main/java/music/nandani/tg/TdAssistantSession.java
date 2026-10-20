package music.nandani.tg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * TDLib-backed assistant (user account) login via phone number + OTP.
 * The MTProto-backed variant lives in {@link MtAssistantSession}.
 */
public final class TdAssistantSession extends TdSession implements AssistantSession {

    private final String phone;
    private volatile Function<String, String> codeProvider = q -> "";
    private volatile Function<String, String> passwordProvider = q -> "";

    private final Map<Long, Long> groupCallIds = new ConcurrentHashMap<>();

    public TdAssistantSession(String name, String phone, String libName) {
        super(name, libName);
        this.phone = phone;
        on("updateChatVideoChat", n -> {
            long chatId = n.path("chat_id").asLong();
            long gid = n.path("video_chat").path("group_call_id").asLong(0);
            if (gid != 0) groupCallIds.put(chatId, gid);
        });
    }

    public void setCodeProvider(Function<String, String> p) { this.codeProvider = p; }
    public void setPasswordProvider(Function<String, String> p) { this.passwordProvider = p; }

    @Override
    protected void onAuthState(JsonNode state) {
        String t = state.path("@type").asText();
        switch (t) {
            case "authorizationStateWaitTdlibParameters" -> sendTdlibParameters();
            case "authorizationStateWaitPhoneNumber" -> {
                ObjectNode r = obj();
                r.put("@type", "setAuthenticationPhoneNumber");
                r.put("phone_number", phone);
                ObjectNode settings = r.putObject("settings");
                settings.put("@type", "phoneNumberAuthenticationSettings");
                settings.put("allow_flash_call", false);
                settings.put("allow_missed_call", false);
                settings.put("is_current_phone_number", false);
                settings.put("has_unknown_phone_number", false);
                settings.put("allow_sms_retriever_api", false);
                send(r.toString());
            }
            case "authorizationStateWaitCode" -> {
                String code = codeProvider.apply(state.path("code_info").path("phone_number").asText(phone));
                ObjectNode r = obj();
                r.put("@type", "checkAuthenticationCode");
                r.put("code", code);
                send(r.toString());
            }
            case "authorizationStateWaitPassword" -> {
                String pw = passwordProvider.apply(state.path("password_hint").asText(""));
                ObjectNode r = obj();
                r.put("@type", "checkAuthenticationPassword");
                r.put("password", pw);
                send(r.toString());
            }
            case "authorizationStateReady" -> {
                ready.set(true);
                onReady();
                System.out.println("[assistant " + name + "] authorized as @" + myUsername + " (" + myUserId + ")");
            }
            case "authorizationStateClosed" -> running.set(false);
            default -> { }
        }
    }

    /** Group call id for a chat (0 when no active voice chat). */
    public long groupCallId(long chatId) {
        Long cached = groupCallIds.get(chatId);
        if (cached != null && cached != 0) return cached;
        ObjectNode r = obj();
        r.put("@type", "getChat");
        r.put("chat_id", chatId);
        JsonNode res = request(r, 15_000);
        long gid = res == null ? 0 : res.path("video_chat").path("group_call_id").asLong(0);
        if (gid != 0) groupCallIds.put(chatId, gid);
        return gid;
    }

    public boolean hasVoiceChat(long chatId) {
        return groupCallId(chatId) != 0;
    }

    /** Joins with an ntgcalls SDP offer; returns the Telegram SDP answer. */
    @Override
    public String joinGroupCall(long chatId, String offer, boolean video) {
        long gid = groupCallId(chatId);
        if (gid == 0) return "";
        ObjectNode r = obj();
        r.put("@type", "joinGroupCall");
        r.put("group_call_id", gid);
        r.put("participant_id", 0);
        r.put("audio_source_id", 0);
        r.put("payload", offer);
        r.put("is_muted", false);
        r.put("is_video_stopped", !video);
        JsonNode res = request(r, 40_000);
        if (res == null) return "";
        if ("error".equals(res.path("@type").asText())) {
            System.err.println("[assistant " + name + "] joinGroupCall: " + res.path("message").asText());
            return "";
        }
        return res.path("payload").asText("");
    }

    @Override
    public boolean leaveGroupCall(long chatId) {
        long gid = groupCallId(chatId);
        if (gid == 0) return true;
        ObjectNode r = obj();
        r.put("@type", "leaveGroupCall");
        r.put("group_call_id", gid);
        JsonNode res = request(r, 15_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }

    @Override
    public boolean isChatMember(long chatId) {
        ObjectNode r = obj();
        r.put("@type", "getChatMember");
        r.put("chat_id", chatId);
        r.set("member_id", TdClient.senderUser(myUserId));
        JsonNode res = request(r, 15_000);
        if (res == null) return false;
        String st = res.path("status").path("@type").asText();
        return !"chatMemberStatusLeft".equals(st) && !"chatMemberStatusBanned".equals(st);
    }

    @Override
    public boolean joinChat(long chatId) {
        String link = "";
        ObjectNode g = obj();
        g.put("@type", "getChat");
        g.put("chat_id", chatId);
        JsonNode chat = request(g, 15_000);
        if (chat != null) link = chat.path("invite_link").asText("");
        ObjectNode r = obj();
        r.put("@type", "joinChat");
        r.put("chat_id", chatId);
        JsonNode res = request(r, 20_000);
        return res != null && !"error".equals(res.path("@type").asText());
    }
}
