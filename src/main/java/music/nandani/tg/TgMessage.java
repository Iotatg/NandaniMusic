package music.nandani.tg;

/** Incoming message — mirrors TgMessage in mtproto/src/messaging.rs. */
public final class TgMessage {
    public long chatId;
    public long id;
    public long senderId;
    public String text = "";
    public String firstName = "";
    public String lastName = "";
    public boolean isPrivate;
    public boolean isChannel;
    public long replyTo;
    public MediaKind mediaKind = MediaKind.NONE;
    public long docId;
    public long docAccessHash;
    public byte[] docFileRef = new byte[0];
    public String stickerEmoji = "";
    /** Forum topic id (0 = none) — needed to answer in the right topic. */
    public long messageThreadId;
    /** Chat title, when known (group/channel name). */
    public String chatTitle = "";
    /** True when the sender is a bot account. */
    public boolean senderIsBot;

    public enum MediaKind { NONE, STICKER, VIDEO, DOCUMENT, PHOTO }

    public String senderName() {
        String n = (firstName == null ? "" : firstName).trim();
        String l = (lastName == null ? "" : lastName).trim();
        String full = (n + " " + l).trim();
        return full.isEmpty() ? "user" : full;
    }

    public String mention() {
        return music.nandani.util.Util.htmlMention(senderName(), senderId);
    }
}
