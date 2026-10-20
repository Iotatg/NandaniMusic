package music.nandani.modules;

import music.nandani.tg.Bot;
import music.nandani.tg.TgCallback;
import music.nandani.tg.TgMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Command / callback registry — mirrors Registry in modules/mod.rs. */
public final class Registry {

    public interface CmdHandler extends BiConsumer<Bot, TgMessage> {}
    public interface CbHandler extends BiConsumer<Bot, TgCallback> {}

    private final Map<String, CmdHandler> cmds = new LinkedHashMap<>();
    private final List<Map.Entry<String, CbHandler>> cbs = new ArrayList<>();
    private final List<CmdHandler> messages = new ArrayList<>();

    /**
     * Commands that operate on a group's voice chat / settings and therefore
     * make no sense in the bot's private chat — blocked in DM.
     */
    private static final java.util.Set<String> GROUP_ONLY = java.util.Set.of(
            // voice-chat controls
            "queue", "cqueue", "skip", "cskip", "askip", "caskip", "pause", "cpause",
            "resume", "cresume", "stop", "cstop", "end", "cend", "seek", "cseek",
            "seekback", "cseekback", "loop", "cloop", "setloop", "csetloop",
            "shuffle", "cshuffle", "speed", "cspeed", "setspeed", "csetspeed",
            "speedup", "cspeedup", "mute", "cmute", "unmute", "cunmute",
            "replay", "creplay", "position", "cposition", "pos", "cpos",
            "jump", "cjump", "move", "cmove", "remove", "cremove", "clear", "cclear",
            // group-scoped admin
            "auth", "authorize", "addauth", "unauth", "delauth", "delauthuser",
            "authlist", "authusers", "ban", "unban", "kick", "promote", "fullpromote",
            "unpromote", "demote", "pin", "unpin", "unpinall", "purge",
            "warn", "warnings", "warns", "resetwarns", "clearwarn", "unwarn",
            "setwarnlimit", "warnlimit", "setwarnmode", "welcome",
            "blockuser", "blacklistuser", "bluser", "blackuser", "unblockuser",
            "unblacklistuser", "unbluser", "whitelistuser", "blockchat", "blacklistchat",
            "blchat", "blackchat", "unblockchat", "unblacklistchat", "unblchat",
            "whitechat", "blacklisted", "blocked", "playmode", "adminmode",
            "nothumb", "nothumbs", "autoleave", "cplay", "setcplay", "channelplay",
            "autoplay", "cleanmode", "cmddelete", "logger", "logs", "log",
            "vcstats", "vcstatus", "activevc", "activevoice", "ac", "active",
            "vclog", "vclogger", "reload", "creload",
            // chat data
            "save", "get", "notes", "clearnote", "filter", "filters",
            "stopfilter", "clearfilters");

    public void command(String name, CmdHandler h) {
        cmds.put(name.toLowerCase(java.util.Locale.ROOT), h);
    }

    public void callback(String prefix, CbHandler h) {
        cbs.add(Map.entry(prefix, h));
    }

    public void onMessage(CmdHandler h) {
        messages.add(h);
    }

    public List<String> commandNames() { return new ArrayList<>(cmds.keySet()); }

    public List<String> callbackPrefixes() {
        List<String> out = new ArrayList<>();
        for (var e : cbs) out.add(e.getKey());
        return out;
    }

    public CmdHandler commandHandler(String name) {
        return cmds.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public CbHandler callbackHandler(String prefix) {
        for (var e : cbs) if (e.getKey().equals(prefix)) return e.getValue();
        return null;
    }

    public List<CmdHandler> messageHandlers() { return messages; }

    public void dispatchCommand(Bot bot, TgMessage m) {
        String name = m.text.substring(1).split("[ @]", 2)[0];
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        CmdHandler h = commandHandler(name);
        if (h == null) return;
        if (m.isPrivate && GROUP_ONLY.contains(lower)) {
            bot.replyText(m, Core.f(m.chatId, "groups_only"), null);
            return;
        }
        music.nandani.i18n.Locales.setCurrentCommand("/" + lower);
        try {
            h.accept(bot, m);
        } finally {
            music.nandani.i18n.Locales.setCurrentCommand(null);
        }
    }

    public void dispatchCallback(Bot bot, TgCallback cb) {
        String prefix = cb.data.contains(":")
                ? cb.data.substring(0, cb.data.indexOf(':') + 1) : cb.data;
        CbHandler h = callbackHandler(prefix);
        if (h != null) h.accept(bot, cb);
    }

    public void dispatchMessage(Bot bot, TgMessage m) {
        for (CmdHandler h : messages) h.accept(bot, m);
    }
}
