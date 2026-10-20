package music.nandani.modules;

import music.nandani.model.Track;
import music.nandani.room.RoomState;
import music.nandani.tg.Bot;
import music.nandani.tg.Markup;
import music.nandani.tg.TgMessage;
import music.nandani.util.Util;

/** Shared card/message helpers — 1:1 with modules/common.rs. */
public final class Common {

    private Common() {}

    /** Live message: create once, edit in place afterwards. */
    public static final class LiveMsg {
        public long id;
        public long chatId;

        public void reply(Bot bot, long chatId, String text, Markup markup, long replyTo) {
            this.chatId = chatId;
            this.id = bot.sendTextSync(chatId, text, markup, replyTo);
        }

        public void send(Bot bot, String text, Markup markup) {
            if (id == 0) {
                reply(bot, chatId, text, markup, 0);
            } else {
                bot.editText(chatId, id, text, markup);
            }
        }

        public void eor(Bot bot, String text, Markup markup) {
            if (id == 0) {
                reply(bot, chatId, text, markup, 0);
                return;
            }
            if (!bot.editText(chatId, id, text, markup)) {
                id = bot.sendTextSync(chatId, text, markup, 0);
            }
        }

        public void remove(Bot bot) {
            if (id != 0) bot.deleteMessage(chatId, id);
            id = 0;
        }
    }

    public static String nowPlayingText(long chatId, Track t) {
        return Core.f(chatId, "stream_now_playing",
                "url", t.url,
                "title", Util.escapeHtml(Util.shortTitle(t.title, 25)),
                "duration", Util.formatTime(t.duration),
                "by", t.requester);
    }

    public static boolean cardEdit(Bot bot, long chatId, long messageId, String text,
                                   Markup markup, boolean expectPhoto) {
        if (expectPhoto && bot.editCaption(chatId, messageId, text, markup)) return true;
        return bot.editText(chatId, messageId, text, markup);
    }

    /** Publish a card as photo when artwork is available, else text. */
    public static long[] publishCard(Bot bot, long chatId, Track t, String text, Markup mk) {
        if (t.artwork != null && !t.artwork.isEmpty()
                && !music.nandani.db.Database.thumbnailsDisabled(chatId)) {
            String local = music.nandani.platform.PlatformRegistry.artworkLocalPath(t.artwork);
            if (!local.isEmpty()) {
                long id = bot.sendPhotoSync(chatId, local, text, mk, 0);
                if (id != 0) return new long[]{id, 1};
            }
        }
        long id = bot.sendTextSync(chatId, text, mk, 0);
        return new long[]{id, 0};
    }

    public static void sendNowPlaying(Bot bot, long chatId, RoomState r, Track t) {
        long old = r.statusMsg;
        String text = nowPlayingText(chatId, t);
        Markup mk = Buttons.playMarkup(Core.loc, Core.lang(chatId), r.view(), false);
        long[] res = publishCard(bot, chatId, t, text, mk);
        r.statusMsg = res[0];
        r.statusIsPhoto = res[1] == 1;
        if (old != 0 && old != res[0]) bot.deleteMessage(chatId, old);
    }

    public static void setStatus(Bot bot, RoomState r, String text) {
        long mid = r.statusMsg;
        if (mid != 0) {
            if (r.statusIsPhoto && bot.editCaption(r.chatId, mid, text, null)) return;
            if (!r.statusIsPhoto && bot.editText(r.chatId, mid, text, null)) return;
            bot.deleteMessage(r.chatId, mid);
        }
        long id = bot.sendTextSync(r.chatId, text, null, 0);
        if (id != 0) {
            r.statusMsg = id;
            r.statusIsPhoto = false;
        }
    }

    public static void showTrack(LiveMsg lm, Bot bot, long chatId, RoomState r,
                                 Track t, String text, boolean queued) {
        long old = r.statusMsg;
        Markup mk = Buttons.playMarkup(Core.loc, Core.lang(chatId), r.view(), queued);
        long[] res = publishCard(bot, chatId, t, text, mk);
        if (res[0] == 0) {
            lm.eor(bot, text, mk);
            r.statusMsg = lm.id;
            r.statusIsPhoto = false;
            return;
        }
        lm.remove(bot);
        r.statusMsg = res[0];
        r.statusIsPhoto = res[1] == 1;
        if (old != 0 && old != res[0]) bot.deleteMessage(chatId, old);
    }

    public static void showTrackQueued(LiveMsg lm, Bot bot, long chatId, RoomState r, String text) {
        Markup mk = Buttons.playMarkup(Core.loc, Core.lang(chatId), r.view(), true);
        lm.eor(bot, text, mk);
    }

    public static String mentionOf(TgMessage m) {
        return Util.htmlMention(m.senderName(), m.senderId);
    }
}
