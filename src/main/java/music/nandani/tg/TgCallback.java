package music.nandani.tg;

/** Incoming callback query — mirrors TgCallback. */
public final class TgCallback {
    public long queryId;
    public long chatId;
    public long messageId;
    public long senderId;
    public String data = "";
    public String senderName = "";
    public boolean isChannel;

    public String mention() {
        return music.nandani.util.Util.htmlMention(senderName, senderId);
    }
}
