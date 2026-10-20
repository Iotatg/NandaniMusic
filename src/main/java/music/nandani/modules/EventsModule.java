package music.nandani.modules;

import music.nandani.db.Database;
import music.nandani.tg.Bot;
import music.nandani.tg.MtBot;
import music.nandani.tg.TgMessage;
import music.nandani.util.Util;

/** Plain-message side effects: filters, blacklist, maintenance + VC events. */
public final class EventsModule {

    private EventsModule() {}

    public static void register(Registry r) {
        r.onMessage(EventsModule::onMessage);
    }

    /** Voice-chat start/end service events (wired from Main to the MtBot). */
    public static void onVoiceChat(Bot bot, long chatId, MtBot.VoiceChatEvent ev) {
        if (Database.isBlacklistedChat(chatId)) return;
        if (ev.ended()) {
            bot.sendTextSync(chatId, Core.f(chatId, "voicechat_ended",
                    "duration", Util.formatTime(ev.durationSec())), null, ev.messageId());
            // The call is gone — destroy the room so /play can join fresh.
            Core.rooms.dropRoom(chatId);
        } else {
            bot.sendTextSync(chatId, Core.f(chatId, "voicechat_started"), null, ev.messageId());
        }
    }

    private static void onMessage(Bot bot, TgMessage m) {
        if (m.isChannel) return;
        Database.addServedChat(m.chatId);
        if (m.senderId != 0) Database.addServedUser(m.senderId);

        if (Database.isBlacklistedChat(m.chatId)) return;
        if (Database.isBlacklistedUser(m.senderId)) {
            bot.deleteMessage(m.chatId, m.id);
            return;
        }
        if (Database.maintenance() && !Database.isSudo(m.senderId)) return;
        if (m.text == null || m.text.isEmpty() || m.text.startsWith("/")) return;

        var hit = Database.matchFilter(m.chatId, m.text);
        if (hit != null) {
            bot.sendTextSync(m.chatId, hit.text(), null, m.id);
        }
    }
}
