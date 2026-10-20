package music.nandani.modules;

import music.nandani.db.Database;
import music.nandani.model.PlaylistEntry;
import music.nandani.tg.Bot;
import music.nandani.tg.TgMessage;
import music.nandani.util.Util;

import java.util.List;

/** Notes, filters, playlists and sticker packs. */
public final class ListModule {

    private ListModule() {}

    public static void register(Registry r) {
        r.command("save", ListModule::saveNote);
        r.command("get", ListModule::getNote);
        r.command("clear", ListModule::deleteNote);
        r.command("notes", ListModule::listNotes);
        r.command("clearnote", ListModule::deleteNote);

        r.command("filter", ListModule::saveFilter);
        r.command("filters", ListModule::listFilters);
        r.command("stopfilter", ListModule::stopFilter);
        r.command("clearfilters", ListModule::clearFilters);

        r.command("createplaylist", ListModule::createPlaylist);
        r.command("deleteplaylist", ListModule::deletePlaylist);
        r.command("addtoplaylist", ListModule::addToPlaylist);
        r.command("removefromplaylist", ListModule::removeFromPlaylist);
        r.command("playlistinfo", ListModule::playlistInfo);
        r.command("plistinfo", ListModule::playlistInfo);
        r.command("myplaylists", ListModule::myPlaylists);

        r.command("addsticker", ListModule::addSticker);
        r.command("clearstickers", ListModule::clearStickers);
        r.command("stickerpacks", ListModule::stickerPacks);
        r.command("previewsticker", ListModule::previewSticker);
        r.command("stickerdiag", ListModule::stickerDiag);
    }

    // ---- notes ----

    private static void saveNote(Bot bot, TgMessage m) {
        String q = Util.extractQuery(m.text).trim();
        int sp = q.indexOf(' ');
        if (sp <= 0) {
            bot.replyText(m, Util.stylish("<b>Usage</b>: <code>/save name content</code>"), null);
            return;
        }
        String key = q.substring(0, sp).toLowerCase(java.util.Locale.ROOT);
        String content = q.substring(sp + 1).trim();
        Database.setNote(m.chatId, key, content);
        bot.replyText(m, Core.f(m.chatId, "note_saved", "name", key), null);
    }

    private static void getNote(Bot bot, TgMessage m) {
        String key = Util.extractQuery(m.text).trim().toLowerCase(java.util.Locale.ROOT);
        String note = Database.getNote(m.chatId, key);
        if (note.isEmpty()) {
            bot.replyText(m, Core.f(m.chatId, "note_not_found", "name", key), null);
            return;
        }
        bot.replyText(m, note, null);
    }

    private static void deleteNote(Bot bot, TgMessage m) {
        String key = Util.extractQuery(m.text).trim().toLowerCase(java.util.Locale.ROOT);
        boolean ok = Database.deleteNote(m.chatId, key);
        bot.replyText(m, Core.f(m.chatId, ok ? "note_deleted" : "note_not_found", "name", key), null);
    }

    private static void listNotes(Bot bot, TgMessage m) {
        List<String> notes = Database.listNotes(m.chatId);
        if (notes.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "notes_empty"), null); return; }
        StringBuilder sb = new StringBuilder("<b>📝 𝐍‌σᴛєs</b>\n");
        for (String n : notes) sb.append("• <code>").append(n).append("</code>\n");
        bot.replyText(m, sb.toString(), null);
    }

    // ---- filters ----

    private static void saveFilter(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        String q = Util.extractQuery(m.text).trim();
        int sp = q.indexOf(' ');
        if (sp <= 0) {
            bot.replyText(m, Util.stylish("<b>Usage</b>: <code>/filter keyword reply</code>"), null);
            return;
        }
        String kw = q.substring(0, sp);
        String text = q.substring(sp + 1).trim();
        Database.saveFilter(m.chatId, kw, text);
        bot.replyText(m, Core.f(m.chatId, "filter_saved", "keyword", Util.escapeHtml(kw)), null);
    }

    private static void listFilters(Bot bot, TgMessage m) {
        var rows = Database.listFilters(m.chatId);
        if (rows.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "filters_empty"), null); return; }
        StringBuilder sb = new StringBuilder("<b>🔖 𝐅‌ɪʟᴛєꝛs</b>\n");
        for (var r : rows) sb.append("• <code>").append(Util.escapeHtml(r.keyword())).append("</code>\n");
        bot.replyText(m, sb.toString(), null);
    }

    private static void stopFilter(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        String kw = Util.extractQuery(m.text).trim();
        boolean ok = Database.deleteFilter(m.chatId, kw);
        bot.replyText(m, Core.f(m.chatId, ok ? "filter_deleted" : "filter_not_found",
                "keyword", Util.escapeHtml(kw)), null);
    }

    private static void clearFilters(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long n = Database.clearFilters(m.chatId);
        bot.replyText(m, Core.f(m.chatId, "filters_cleared", "count", Long.toString(n)), null);
    }

    // ---- playlists ----

    private static void createPlaylist(Bot bot, TgMessage m) {
        String name = Util.extractQuery(m.text).trim();
        if (name.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "playlist_usage_create"), null); return; }
        if (Database.playlistsOf(m.senderId).size() >= 20) {
            bot.replyText(m, Core.f(m.chatId, "playlist_limit_reached"), null);
            return;
        }
        boolean ok = Database.createPlaylist(m.senderId, name);
        bot.replyText(m, Core.f(m.chatId, ok ? "playlist_create_success" : "playlist_create_fail",
                "name", Util.escapeHtml(name)), null);
    }

    private static void deletePlaylist(Bot bot, TgMessage m) {
        String name = Util.extractQuery(m.text).trim();
        if (name.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "playlist_usage_delete"), null); return; }
        boolean ok = Database.deletePlaylist(m.senderId, name);
        bot.replyText(m, Core.f(m.chatId, ok ? "playlist_delete_success" : "playlist_not_found",
                "name", Util.escapeHtml(name)), null);
    }

    private static void addToPlaylist(Bot bot, TgMessage m) {
        String q = Util.extractQuery(m.text).trim();
        int sp = q.indexOf(' ');
        if (sp <= 0) { bot.replyText(m, Core.f(m.chatId, "playlist_usage_add"), null); return; }
        String name = q.substring(0, sp);
        String query = q.substring(sp + 1).trim();
        if (!Database.playlistExists(m.senderId, name)) {
            bot.replyText(m, Core.f(m.chatId, "playlist_not_found", "name", Util.escapeHtml(name)), null);
            return;
        }
        List<music.nandani.model.Track> tracks = Core.platforms.resolveQuery(query, false);
        if (tracks.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "playlist_invalid_query"), null); return; }
        var t = tracks.get(0);
        Database.addToPlaylist(m.senderId, name,
                new PlaylistEntry(t.title, t.url, t.duration, m.senderName()));
        bot.replyText(m, Core.f(m.chatId, "playlist_add_success",
                "title", Util.escapeHtml(Util.shortTitle(t.title, 40)),
                "name", Util.escapeHtml(name)), null);
    }

    private static void removeFromPlaylist(Bot bot, TgMessage m) {
        String q = Util.extractQuery(m.text).trim();
        int sp = q.indexOf(' ');
        if (sp <= 0) { bot.replyText(m, Core.f(m.chatId, "playlist_usage_remove"), null); return; }
        String name = q.substring(0, sp);
        try {
            int idx = Integer.parseInt(q.substring(sp + 1).trim()) - 1;
            boolean ok = Database.removeFromPlaylist(m.senderId, name, idx);
            bot.replyText(m, Core.f(m.chatId, ok ? "playlist_remove_success" : "playlist_remove_fail",
                    "name", Util.escapeHtml(name)), null);
        } catch (NumberFormatException e) {
            bot.replyText(m, Core.f(m.chatId, "playlist_invalid_song_number"), null);
        }
    }

    private static void playlistInfo(Bot bot, TgMessage m) {
        String name = Util.extractQuery(m.text).trim();
        if (name.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "playlist_usage_info"), null); return; }
        var entries = Database.playlistEntries(m.senderId, name);
        if (entries.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "playlist_songs_empty"), null); return; }
        StringBuilder sb = new StringBuilder(Core.f(m.chatId, "playlist_info",
                "name", Util.escapeHtml(name), "count", Integer.toString(entries.size()))).append('\n');
        int i = 1;
        for (var e : entries) {
            sb.append(Core.f(m.chatId, "playlist_song_line",
                    "index", Integer.toString(i++),
                    "title", Util.escapeHtml(Util.shortTitle(e.title, 40)),
                    "duration", Util.formatDuration(e.duration))).append('\n');
        }
        bot.replyText(m, sb.toString(), null);
    }

    private static void myPlaylists(Bot bot, TgMessage m) {
        List<String> names = Database.playlistsOf(m.senderId);
        if (names.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "playlist_none"), null); return; }
        StringBuilder sb = new StringBuilder("<b>🎶 𝐘‌συꝛ 𝐏‌ʟᴧʏʟɪsᴛs</b>\n");
        for (String n : names) {
            sb.append(Core.f(m.chatId, "playlist_list_line",
                    "name", Util.escapeHtml(n),
                    "count", Integer.toString(Database.playlistEntries(m.senderId, n).size()))).append('\n');
        }
        bot.replyText(m, sb.toString(), null);
    }

    // ---- stickers ----

    private static void addSticker(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        String q = Util.extractQuery(m.text).trim();
        int sp = q.indexOf(' ');
        if (sp <= 0) {
            bot.replyText(m, Util.stylish("<b>Usage</b>: <code>/addsticker mood file_id</code>"), null);
            return;
        }
        String mood = q.substring(0, sp).toLowerCase(java.util.Locale.ROOT);
        String id = q.substring(sp + 1).trim();
        Database.addStickerToPack(mood, id);
        bot.replyText(m, Core.f(m.chatId, "sticker_added", "mood", mood), null);
    }

    private static void clearStickers(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        String mood = Util.extractQuery(m.text).trim();
        if (mood.isEmpty()) mood = "all";
        long n = Database.clearStickerPack(mood.toLowerCase(java.util.Locale.ROOT));
        bot.replyText(m, Core.f(m.chatId, "stickers_cleared", "count", Long.toString(n)), null);
    }

    private static void stickerPacks(Bot bot, TgMessage m) {
        var packs = Database.stickerPacks();
        if (packs.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "sticker_packs_empty"), null); return; }
        StringBuilder sb = new StringBuilder("<b>💭 𝐒‌ᴛɪᴄᴋєꝛ 𝐏‌ᴧᴄᴋs</b>\n");
        for (var p : packs) {
            sb.append("• <code>").append(p.getKey()).append("</code> — ")
                    .append(p.getValue().size()).append(" 𝐒‌ᴛɪᴄᴋєꝛs\n");
        }
        bot.replyText(m, sb.toString(), null);
    }

    private static void previewSticker(Bot bot, TgMessage m) {
        String mood = Util.extractQuery(m.text).trim().toLowerCase(java.util.Locale.ROOT);
        List<String> ids = Database.stickerPackIds(mood);
        if (ids.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "sticker_packs_empty"), null); return; }
        bot.sendSticker(m.chatId, ids.get(0), m.id);
    }

    private static void stickerDiag(Bot bot, TgMessage m) {
        bot.replyText(m, Util.stylish("<b>Sticker diag</b>\nPacks: <code>"
                + Database.stickerPacks().size() + "</code>"), null);
    }

    private static boolean canManage(Bot bot, TgMessage m) {
        Bot.MemberState s = bot.getChatMemberState(m.chatId, m.senderId);
        return Database.isSudo(m.senderId)
                || s == Bot.MemberState.CREATOR || s == Bot.MemberState.ADMINISTRATOR;
    }
}
