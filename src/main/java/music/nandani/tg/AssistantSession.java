package music.nandani.tg;

/**
 * Assistant (user account) session abstraction. Bots cannot join voice chats,
 * so playback is carried by one or more user accounts — either TDLib-backed
 * (phone-number login) or MTProto-backed (imported session string).
 */
public interface AssistantSession extends AutoCloseable {

    /** Blocking login; returns true once the account is usable. */
    boolean start(long readyTimeoutMs);

    @Override
    void close();

    boolean isReady();

    long myUserId();

    String myUsername();

    /** True when the assistant account is a member of the chat. */
    boolean isChatMember(long chatId);

    /**
     * Member status of a user in a chat, resolved through this (user) session.
     * User sessions resolve peers reliably (dialogs cache), unlike bot sessions.
     */
    default Bot.MemberState chatMemberState(long chatId, long userId) {
        return Bot.MemberState.UNKNOWN;
    }

    /** Joins the chat/channel (no-op when already a member). */
    boolean joinChat(long chatId);

    /**
     * Joins the chat's active group call with an ntgcalls SDP offer;
     * returns the Telegram SDP answer ("" on failure).
     */
    String joinGroupCall(long chatId, String offer, boolean video);

    boolean leaveGroupCall(long chatId);
}
