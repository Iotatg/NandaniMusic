package music.nandani.modules;

import music.nandani.db.Database;
import music.nandani.tg.Bot;
import music.nandani.tg.TgMessage;

/** Plain-message side effects: filters, blacklist, maintenance. */
public final class EventsModule {

    private EventsModule() {}

    public static void register(Registry r) {
        r.onMessage(EventsModule::onMessage);
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
