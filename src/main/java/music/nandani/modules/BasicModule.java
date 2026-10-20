package music.nandani.modules;

import music.nandani.db.Database;
import music.nandani.tg.Bot;
import music.nandani.tg.Markup;
import music.nandani.tg.Markup.Button;
import music.nandani.tg.TgMessage;
import music.nandani.util.Util;

import java.util.List;

/** /start, /help, /ping, /lang, /settings and friends. */
public final class BasicModule {

    private BasicModule() {}

    private static long lastGcCount;
    private static long lastGcAt;

    public static void register(Registry r) {
        r.command("start", BasicModule::start);
        r.command("help", BasicModule::help);
        r.command("ping", BasicModule::ping);
        r.command("alive", BasicModule::ping);
        r.command("repo", BasicModule::repo);
        r.command("source", BasicModule::repo);
        r.command("lang", BasicModule::lang);
        r.command("language", BasicModule::lang);
        r.command("settings", BasicModule::settings);
        r.command("privacy", BasicModule::privacy);
        r.command("id", BasicModule::id);
        r.command("stats", BasicModule::stats);
        r.command("bug", BasicModule::bug);
        r.command("sudo", BasicModule::sudoInfo);
    }

    private static void start(Bot bot, TgMessage m) {
        String key = m.isPrivate ? "start_private" : "start_group";
        String text = Core.f(m.chatId, key, "user", Util.htmlMention(m.firstName, m.senderId));
        if (text.startsWith("[")) {
            text = "<b>NandaniMusic</b>\n\nTelegram voice-chat music engine.\n"
                    + "Use /help to see every command.";
        }
        Markup mk = m.isPrivate
                ? Buttons.startMarkup(Core.cfg, Core.loc, Core.lang(m.chatId))
                : Buttons.groupHelpKeyboard(Core.loc, Core.lang(m.chatId));
        bot.replyText(m, text, mk);
    }

    private static void help(Bot bot, TgMessage m) {
        String text = Core.f(m.chatId, "help_main");
        if (text.startsWith("[")) text = "<b>NandaniMusic — help</b>\nPick a category below.";
        bot.replyText(m, text, Buttons.helpKeyboard(Core.loc, Core.lang(m.chatId)));
    }

    private static void ping(Bot bot, TgMessage m) {
        long t0 = System.nanoTime();
        long sent = bot.replyText(m, Core.f(m.chatId, "ping_start"), null);
        String ms = String.format(java.util.Locale.ROOT, "%.3f",
                (System.nanoTime() - t0) / 1_000_000.0);
        String text = Core.f(m.chatId, "ping_result",
                "latency", ms + "ms",
                "uptime", Util.stylish(Util.formatUptime(Core.cfg.uptimeSeconds())),
                "ram", Util.ramPercent(),
                "cpu", Util.cpuInfo(),
                "disk", Util.diskPercent(),
                "ptg", ms + "ms");
        Markup mk = Buttons.suppMarkup(Core.cfg, Core.loc, Core.lang(m.chatId));
        if (sent != 0) {
            if (!bot.editText(m.chatId, sent, text, mk)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                bot.editText(m.chatId, sent, text, mk);
            }
        } else {
            bot.replyText(m, text, mk);
        }
    }

    private static void repo(Bot bot, TgMessage m) {
        String dev = Core.cfg.devUrl;
        bot.replyText(m, Core.f(m.chatId, "repo_text", "bot", Core.cfg.botBrand),
                Buttons.repoMarkup(Core.cfg, Core.loc, Core.lang(m.chatId), dev));
    }

    private static void lang(Bot bot, TgMessage m) {
        List<String> available = Core.loc.available();
        String arg = Util.extractQuery(m.text).trim().toLowerCase(java.util.Locale.ROOT);
        if (arg.isEmpty()) {
            Markup mk = new Markup();
            for (String code : available) {
                String name = Core.loc.get(code, "name");
                mk.row(Button.data(name, "lang:" + code));
            }
            bot.replyText(m, Core.f(m.chatId, "lang_select"), mk);
            return;
        }
        if (!available.contains(arg)) {
            bot.replyText(m, Core.f(m.chatId, "lang_fail"), null);
            return;
        }
        if (arg.equals(Core.lang(m.chatId))) {
            bot.replyText(m, Core.f(m.chatId, "lang_same"), null);
            return;
        }
        Database.setLanguage(m.chatId, arg);
        bot.replyText(m, Core.f(m.chatId, "lang_success", "lang", arg), null);
    }

    private static void settings(Bot bot, TgMessage m) {
        String lang = Core.lang(m.chatId);
        Markup mk = new Markup()
                .row(Button.data(Core.loc.get(lang, "settings_btn_lang"), "lang:menu"))
                .row(Button.data(Core.loc.get(lang, "settings_btn_playmode"), "set:playmode"))
                .row(Button.data(Core.loc.get(lang, "settings_btn_adminmode"), "set:adminmode"))
                .row(Button.data(Core.loc.get(lang, "settings_btn_nothumb"), "set:nothumb"))
                .row(Button.data(Core.loc.get(lang, "settings_btn_cmddelete"), "set:cmddelete"))
                .row(Button.data(Core.loc.get(lang, "settings_btn_cleanduration"), "set:cleanduration"))
                .row(Button.data(Core.loc.get(lang, "CLOSE_BTN"), "close"));
        bot.replyText(m, Core.f(m.chatId, "settings_main",
                "title", Util.escapeHtml(m.chatTitle == null || m.chatTitle.isBlank() ? "ᴛʜɪs ᴄʜᴧᴛ" : m.chatTitle),
                "id", Long.toString(m.chatId)), mk);
    }

    private static void privacy(Bot bot, TgMessage m) {
        bot.replyText(m, Util.stylish("<b>Privacy</b>\n"
                + "NandaniMusic stores chat settings, playlists, warnings and notes in memory. "
                + "No message content is persisted. Streams are resolved on demand and cached "
                + "temporarily on disk for playback."), null);
    }

    private static void id(Bot bot, TgMessage m) {
        bot.replyText(m, Util.stylish("<b>Chat</b>: <code>" + m.chatId + "</code>\n"
                + "<b>User</b>: <code>" + m.senderId + "</code>\n"
                + "<b>Message</b>: <code>" + m.id + "</code>"), null);
    }

    private static void stats(Bot bot, TgMessage m) {
        Runtime rt = Runtime.getRuntime();
        double usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        double totalMb = rt.totalMemory() / (1024.0 * 1024.0);
        long gcCount = 0;
        for (java.lang.management.GarbageCollectorMXBean gc
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) gcCount += gc.getCollectionCount();
        }
        // GC rate: delta since the previous /stats call, per minute.
        long now = System.currentTimeMillis();
        double gcRate = 0.0;
        if (lastGcAt > 0 && gcCount >= lastGcCount) {
            double mins = (now - lastGcAt) / 60000.0;
            if (mins > 0) gcRate = (gcCount - lastGcCount) / mins;
        }
        lastGcCount = gcCount;
        lastGcAt = now;
        String cpu = Util.cpuInfo();
        double cpuPct;
        try {
            cpuPct = Double.parseDouble(cpu.replace("%", ""));
        } catch (NumberFormatException e) {
            cpuPct = 0;
        }
        String cpuEmoji = cpuPct >= 75 ? "🔴" : cpuPct >= 35 ? "🟡" : "🟢";
        String ramPct = Util.ramPercent();
        double ramVal;
        try {
            ramVal = Double.parseDouble(ramPct.replace("%", ""));
        } catch (NumberFormatException e) {
            ramVal = 0;
        }
        String ramEmoji = ramVal >= 80 ? "🔴" : ramVal >= 50 ? "🟡" : "🟢";
        String arch = "amd64".equals(System.getProperty("os.arch")) ? "x86_64" : System.getProperty("os.arch");
        String text = Core.f(m.chatId, "stats_overview",
                "os", "Linux",
                "arch", arch,
                "cores", rt.availableProcessors() + " Cores",
                "threads", Thread.activeCount() + " Threads",
                "alloc", String.format(java.util.Locale.ROOT, "%.2f MB", usedMb),
                "sys", String.format(java.util.Locale.ROOT, "%.2f MB", totalMb),
                "gc_count", Long.toString(gcCount),
                "gc_emoji", "🟢",
                "gc_rate", String.format(java.util.Locale.ROOT, "%.1f/min", gcRate),
                "cpu_emoji", cpuEmoji,
                "cpu", cpu,
                "ram_emoji", ramEmoji,
                "ram", Util.ramGiB(),
                "disk", Util.diskGiB(),
                "db_data", "0.00 MB",
                "db_collections", "0",
                "db_objects", "1",
                "served_chats", Integer.toString(Database.servedChats().size()),
                "served_users", Integer.toString(Database.servedUsers().size()),
                "core_ver", "1.0.0",
                "api_ver", "Layer 227",
                "vc_ver", "ntgcalls-rs");
        bot.replyText(m, text, null);
    }

    private static void bug(Bot bot, TgMessage m) {
        bot.replyText(m, Util.stylish("<b>Report a bug</b>\nSend the command, what you expected and the "
                + "exact error text to the support chat."), Buttons.suppMarkup(Core.cfg, Core.loc, Core.lang(m.chatId)));
    }

    private static void sudoInfo(Bot bot, TgMessage m) {
        StringBuilder sb = new StringBuilder(Core.f(m.chatId, "sudo_list_header"));
        int index = 1;
        if (Core.cfg.ownerId != 0) {
            sb.append('\n').append(Core.f(m.chatId, "sudo_list_owner",
                    "index", Integer.toString(index++),
                    "user", Util.htmlMention("Owner", Core.cfg.ownerId)));
        }
        for (long id : Database.sudoList()) {
            if (id == Core.cfg.ownerId) continue;
            sb.append('\n').append(Core.f(m.chatId, "sudo_list_entry",
                    "index", Integer.toString(index++),
                    "user", Util.htmlMention("user", id)));
        }
        if (index == 1) sb.append('\n').append(Core.f(m.chatId, "sudo_list_no_extra"));
        bot.replyText(m, sb.toString(), null);
    }
}
