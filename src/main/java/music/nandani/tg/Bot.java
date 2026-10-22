package music.nandani.tg;

/**
 * Transport-agnostic bot facade.
 * Implemented by {@link MtBot} (raw MTProto).
 */
public interface Bot {

    enum MemberState { CREATOR, ADMINISTRATOR, MEMBER, LEFT, BANNED, UNKNOWN }

    record PromoteRights(boolean changeInfo, boolean deleteMessages, boolean banUsers,
                         boolean inviteUsers, boolean pinMessages, boolean manageVoiceChats,
                         boolean manageVideoChats) {
        public static PromoteRights full() {
            return new PromoteRights(true, true, true, true, true, true, true);
        }
    }

    /** @return message id, or 0 on failure. */
    long sendText(long chatId, String text, Markup markup);

    long sendTextSync(long chatId, String text, Markup markup, long replyTo);

    long replyText(TgMessage msg, String text, Markup markup);

    boolean editText(long chatId, long messageId, String text, Markup markup);

    long sendPhoto(long chatId, String photo, String caption, Markup markup);

    long sendPhotoSync(long chatId, String photo, String caption, Markup markup, long replyTo);

    boolean editCaption(long chatId, long messageId, String caption, Markup markup);

    boolean editMarkup(long chatId, long messageId, Markup markup);

    boolean answerCallback(long queryId, String text, boolean alert);

    boolean deleteMessage(long chatId, long messageId);

    boolean banMember(long chatId, long userId, long untilDate);

    boolean unbanMember(long chatId, long userId);

    boolean muteMember(long chatId, long userId);

    boolean promoteMember(long chatId, long userId, PromoteRights rights);

    boolean demoteMember(long chatId, long userId);

    boolean pinMessage(long chatId, long messageId, boolean silent);

    boolean unpinMessage(long chatId, long messageId);

    boolean unpinAllMessages(long chatId);

    MemberState getChatMemberState(long chatId, long userId);

    String chatPublicUsername(long chatId);

    String createChatInviteLink(long chatId);

    boolean approveJoinRequest(long chatId, long userId);

    long getRepliedSender(long chatId, long messageId);

    boolean sendChatActionChoosingSticker(long chatId);

    /** Show "typing…" in the chat. Transports that cannot do it return false. */
    default boolean sendTyping(long chatId) { return false; }

    boolean sendSticker(long chatId, String sticker, long replyTo);

    long meId();

    String meUsername();

    String meFirstName();

    boolean ready();

    /** Raw JSON execute (used by /eval, /json, /get). */
    String executeJson(String request);

    default void onCommand(String name, java.util.function.Consumer<TgMessage> handler) {}

    default void onCallback(String prefix, java.util.function.Consumer<TgCallback> handler) {}

    default void onMessage(java.util.function.Consumer<TgMessage> handler) {}

    /** Publishes the command menu. @return how many commands were accepted. */
    default int setCommands(java.util.List<String> names) {
        return names.size();
    }

    /** Connects / authorizes the transport. */
    boolean start(long timeoutMs);

    /** Stops the transport and releases resources. */
    void close();
}
