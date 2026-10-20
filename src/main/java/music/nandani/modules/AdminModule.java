package music.nandani.modules;

import music.nandani.db.Database;
import music.nandani.tg.Bot;
import music.nandani.tg.Markup;
import music.nandani.tg.TgMessage;
import music.nandani.util.Util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Admin / sudo / auth / moderation / system commands. */
public final class AdminModule {

    private AdminModule() {}

    public static void register(Registry r) {
        // ---- sudo ----
        r.command("addsudo", AdminModule::addSudo);
        r.command("addsudoer", AdminModule::addSudo);
        r.command("sudoadd", AdminModule::addSudo);
        r.command("delsudo", AdminModule::delSudo);
        r.command("delsudoer", AdminModule::delSudo);
        r.command("rmsudo", AdminModule::delSudo);
        r.command("remsudo", AdminModule::delSudo);
        r.command("unsudo", AdminModule::delSudo);
        r.command("sudodel", AdminModule::delSudo);
        r.command("sudorem", AdminModule::delSudo);
        r.command("dropsudo", AdminModule::delSudo);
        r.command("sudoers", AdminModule::sudoList);
        r.command("sudolist", AdminModule::sudoList);
        r.command("listsudo", AdminModule::sudoList);

        // ---- auth ----
        r.command("auth", AdminModule::addAuth);
        r.command("authorize", AdminModule::addAuth);
        r.command("addauth", AdminModule::addAuth);
        r.command("unauth", AdminModule::delAuth);
        r.command("delauth", AdminModule::delAuth);
        r.command("delauthuser", AdminModule::delAuth);
        r.command("authlist", AdminModule::authList);
        r.command("authusers", AdminModule::authList);

        // ---- moderation ----
        r.command("ban", AdminModule::ban);
        r.command("unban", AdminModule::unban);
        r.command("kick", AdminModule::kick);
        r.command("promote", AdminModule::promote);
        r.command("fullpromote", AdminModule::promote);
        r.command("unpromote", AdminModule::demote);
        r.command("demote", AdminModule::demote);
        r.command("pin", AdminModule::pin);
        r.command("unpin", AdminModule::unpin);
        r.command("unpinall", AdminModule::unpinAll);
        r.command("purge", AdminModule::purge);
        r.command("warn", AdminModule::warn);
        r.command("warnings", AdminModule::warnings);
        r.command("warns", AdminModule::warnings);
        r.command("resetwarns", AdminModule::resetWarns);
        r.command("clearwarn", AdminModule::resetWarns);
        r.command("unwarn", AdminModule::unwarn);
        r.command("setwarnlimit", AdminModule::setWarnLimit);
        r.command("warnlimit", AdminModule::setWarnLimit);
        r.command("setwarnmode", AdminModule::setWarnMode);
        r.command("welcome", AdminModule::welcome);

        // ---- blacklist ----
        r.command("blockuser", AdminModule::blockUser);
        r.command("blacklistuser", AdminModule::blockUser);
        r.command("bluser", AdminModule::blockUser);
        r.command("blackuser", AdminModule::blockUser);
        r.command("unblockuser", AdminModule::unblockUser);
        r.command("unblacklistuser", AdminModule::unblockUser);
        r.command("unbluser", AdminModule::unblockUser);
        r.command("whitelistuser", AdminModule::unblockUser);
        r.command("blockchat", AdminModule::blockChat);
        r.command("blacklistchat", AdminModule::blockChat);
        r.command("blchat", AdminModule::blockChat);
        r.command("blackchat", AdminModule::blockChat);
        r.command("unblockchat", AdminModule::unblockChat);
        r.command("unblacklistchat", AdminModule::unblockChat);
        r.command("unblchat", AdminModule::unblockChat);
        r.command("whitechat", AdminModule::unblockChat);
        r.command("blacklisted", AdminModule::blacklisted);
        r.command("blocked", AdminModule::blacklisted);

        // ---- settings ----
        r.command("playmode", AdminModule::playMode);
        r.command("adminmode", AdminModule::adminMode);
        r.command("nothumb", AdminModule::noThumb);
        r.command("nothumbs", AdminModule::noThumb);
        r.command("autoleave", AdminModule::autoLeave);
        r.command("cplay", AdminModule::cplayLink);
        r.command("setcplay", AdminModule::cplayLink);
        r.command("channelplay", AdminModule::cplayLink);
        r.command("autoplay", AdminModule::autoplayCmd);
        r.command("cleanmode", AdminModule::cleanMode);
        r.command("cmddelete", AdminModule::cleanMode);

        // ---- system ----
        r.command("maintenance", AdminModule::maintenance);
        r.command("maint", AdminModule::maintenance);
        r.command("broadcast", AdminModule::broadcast);
        r.command("bcast", AdminModule::broadcast);
        r.command("gcast", AdminModule::broadcast);
        r.command("logger", AdminModule::logger);
        r.command("logs", AdminModule::logger);
        r.command("log", AdminModule::logger);
        r.command("reload", AdminModule::reload);
        r.command("creload", AdminModule::reload);
        r.command("eval", AdminModule::eval);
        r.command("ev", AdminModule::eval);
        r.command("json", AdminModule::jsonDump);
        r.command("shell", AdminModule::shell);
        r.command("bash", AdminModule::shell);
        r.command("sh", AdminModule::shell);
        r.command("restart", AdminModule::restart);
        r.command("vcstats", AdminModule::vcStats);
        r.command("vcstatus", AdminModule::vcStats);
        r.command("activevc", AdminModule::activeVc);
        r.command("activevoice", AdminModule::activeVc);
        r.command("ac", AdminModule::activeVc);
        r.command("active", AdminModule::activeVc);
        r.command("vclog", AdminModule::logger);
        r.command("vclogger", AdminModule::logger);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static boolean isSudo(TgMessage m) { return Database.isSudo(m.senderId); }

    private static boolean isAdmin(Bot bot, TgMessage m) {
        Bot.MemberState s = bot.getChatMemberState(m.chatId, m.senderId);
        return s == Bot.MemberState.CREATOR || s == Bot.MemberState.ADMINISTRATOR;
    }

    private static boolean canManage(Bot bot, TgMessage m) {
        // Owner is always allowed; sudo list next. Group admins are resolved
        // via the bot first, then through an assistant session — bot sessions
        // often lack the peer cache needed by channels.getParticipant.
        if (isSudo(m) || m.senderId == Core.cfg.ownerId) return true;
        Bot.MemberState st = bot.getChatMemberState(m.chatId, m.senderId);
        if (st == Bot.MemberState.UNKNOWN) st = assistantMemberState(m.chatId, m.senderId);
        boolean ok = st == Bot.MemberState.CREATOR || st == Bot.MemberState.ADMINISTRATOR;
        if (!ok) System.out.println("[admin-gate] deny chat=" + m.chatId + " user=" + m.senderId
                + " sudo=false state=" + st);
        return ok;
    }

    private static Bot.MemberState assistantMemberState(long chatId, long userId) {
        var am = Core.assistants;
        if (am == null) return Bot.MemberState.UNKNOWN;
        for (var a : am.all()) {
            if (a.session.isReady()) return a.session.chatMemberState(chatId, userId);
        }
        return Bot.MemberState.UNKNOWN;
    }

    private static long targetUser(Bot bot, TgMessage m) {
        String q = Util.extractQuery(m.text).trim();
        if (!q.isEmpty()) {
            try { return Long.parseLong(q.split("\\s+")[0]); } catch (NumberFormatException ignored) { }
        }
        if (m.replyTo != 0) return bot.getRepliedSender(m.chatId, m.replyTo);
        return 0;
    }

    private static void needSudo(Bot bot, TgMessage m, Runnable action) {
        if (!isSudo(m)) {
            bot.replyText(m, Core.f(m.chatId, "only_sudo"), null);
            return;
        }
        action.run();
    }

    // ------------------------------------------------------------------
    // sudo
    // ------------------------------------------------------------------

    private static void addSudo(Bot bot, TgMessage m) {
        needSudo(bot, m, () -> {
            long uid = targetUser(bot, m);
            if (uid == 0) { bot.replyText(m, Core.f(m.chatId, "sudo_fetch_user_fail"), null); return; }
            if (uid == bot.meId()) { bot.replyText(m, Core.f(m.chatId, "sudo_bot_self"), null); return; }
            if (uid == Core.cfg.ownerId) { bot.replyText(m, Core.f(m.chatId, "sudo_owner_self"), null); return; }
            if (!Database.addSudo(uid)) {
                bot.replyText(m, Core.f(m.chatId, "sudo_already"), null);
                return;
            }
            bot.replyText(m, Core.f(m.chatId, "sudo_add_success", "user", Util.htmlMention("user", uid)), null);
        });
    }

    private static void delSudo(Bot bot, TgMessage m) {
        needSudo(bot, m, () -> {
            long uid = targetUser(bot, m);
            if (uid == 0) { bot.replyText(m, Core.f(m.chatId, "sudo_fetch_user_fail"), null); return; }
            if (uid == Core.cfg.ownerId) { bot.replyText(m, Core.f(m.chatId, "sudo_owner_remove_block"), null); return; }
            if (!Database.delSudo(uid)) {
                bot.replyText(m, Core.f(m.chatId, "sudo_not_exists"), null);
                return;
            }
            bot.replyText(m, Core.f(m.chatId, "sudo_remove_success", "user", Util.htmlMention("user", uid)), null);
        });
    }

    private static void sudoList(Bot bot, TgMessage m) {
        StringBuilder sb = new StringBuilder(Core.f(m.chatId, "sudo_list_header")).append('\n');
        if (Core.cfg.ownerId != 0) {
            sb.append(Core.f(m.chatId, "sudo_list_owner",
                    "user", Util.htmlMention("owner", Core.cfg.ownerId))).append('\n');
        }
        for (long u : Database.sudoList()) {
            if (u == Core.cfg.ownerId) continue;
            sb.append(Core.f(m.chatId, "sudo_list_entry",
                    "user", Util.htmlMention("user", u))).append('\n');
        }
        bot.replyText(m, sb.toString(), null);
    }

    // ------------------------------------------------------------------
    // auth
    // ------------------------------------------------------------------

    private static void addAuth(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin_or_auth"), null); return; }
        long uid = targetUser(bot, m);
        if (uid == 0) { bot.replyText(m, Core.f(m.chatId, "auth_no_user", "cmd", Util.commandOf(m.text)), null); return; }
        if (uid == bot.meId() || uid == Core.cfg.ownerId || uid == m.senderId) {
            bot.replyText(m, Core.f(m.chatId, "cannot_authorize_user"), null);
            return;
        }
        if (Database.isAuthorized(m.chatId, uid)) {
            bot.replyText(m, Core.f(m.chatId, "already_authed"), null);
            return;
        }
        List<Long> users = Database.authUsers(m.chatId);
        if (Core.cfg.maxAuthUsers > 0 && users.size() >= Core.cfg.maxAuthUsers) {
            bot.replyText(m, Core.f(m.chatId, "auth_limit_reached",
                    "limit", Long.toString(Core.cfg.maxAuthUsers)), null);
            return;
        }
        Database.addAuthUser(m.chatId, uid);
        bot.replyText(m, Core.f(m.chatId, "addauth_success_with_count",
                "user", Util.htmlMention("user", uid),
                "count", Integer.toString(users.size() + 1),
                "limit", Long.toString(Core.cfg.maxAuthUsers)), null);
    }

    private static void delAuth(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin_or_auth"), null); return; }
        long uid = targetUser(bot, m);
        if (uid == 0) { bot.replyText(m, Core.f(m.chatId, "auth_no_user", "cmd", Util.commandOf(m.text)), null); return; }
        if (!Database.delAuthUser(m.chatId, uid)) {
            bot.replyText(m, Core.f(m.chatId, "del_auth_not_authorized"), null);
            return;
        }
        bot.replyText(m, Core.f(m.chatId, "del_auth_success", "user", Util.htmlMention("user", uid)), null);
    }

    private static void authList(Bot bot, TgMessage m) {
        List<Long> users = Database.authUsers(m.chatId);
        if (users.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "authlist_empty"), null); return; }
        StringBuilder sb = new StringBuilder(Core.f(m.chatId, "authlist_header")).append('\n');
        for (long u : users) {
            sb.append(Core.f(m.chatId, "authlist_user_entry",
                    "user", Util.htmlMention("user", u))).append('\n');
        }
        sb.append(Core.f(m.chatId, "authlist_total", "count", Integer.toString(users.size())));
        bot.replyText(m, sb.toString(), null);
    }

    // ------------------------------------------------------------------
    // moderation
    // ------------------------------------------------------------------

    private static void ban(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long uid = targetUser(bot, m);
        if (uid == 0) { bot.replyText(m, Core.f(m.chatId, "user_extract_fail", "error", "no target"), null); return; }
        boolean ok = bot.banMember(m.chatId, uid, 0);
        bot.replyText(m, Core.f(m.chatId, ok ? "ban_success" : "generic_error", "error", "telegram_error",
                "user", Util.htmlMention("user", uid)), null);
    }

    private static void unban(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long uid = targetUser(bot, m);
        boolean ok = uid != 0 && bot.unbanMember(m.chatId, uid);
        bot.replyText(m, Core.f(m.chatId, ok ? "unban_success" : "generic_error", "error", "telegram_error",
                "user", Util.htmlMention("user", uid)), null);
    }

    private static void kick(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long uid = targetUser(bot, m);
        if (uid == 0) { bot.replyText(m, Core.f(m.chatId, "user_extract_fail", "error", "no target"), null); return; }
        bot.banMember(m.chatId, uid, 0);
        bot.unbanMember(m.chatId, uid);
        bot.replyText(m, Core.f(m.chatId, "kick_success", "user", Util.htmlMention("user", uid)), null);
    }

    private static void promote(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long uid = targetUser(bot, m);
        boolean ok = uid != 0 && bot.promoteMember(m.chatId, uid, Bot.PromoteRights.full());
        bot.replyText(m, Core.f(m.chatId, ok ? "promote_success" : "generic_error", "error", "telegram_error",
                "user", Util.htmlMention("user", uid)), null);
    }

    private static void demote(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long uid = targetUser(bot, m);
        boolean ok = uid != 0 && bot.demoteMember(m.chatId, uid);
        bot.replyText(m, Core.f(m.chatId, ok ? "demote_success" : "generic_error", "error", "telegram_error",
                "user", Util.htmlMention("user", uid)), null);
    }

    private static void pin(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long mid = m.replyTo != 0 ? m.replyTo : m.id;
        boolean ok = bot.pinMessage(m.chatId, mid, false);
        bot.replyText(m, Core.f(m.chatId, ok ? "pin_success" : "generic_error", "error", "telegram_error"), null);
    }

    private static void unpin(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long mid = m.replyTo != 0 ? m.replyTo : m.id;
        boolean ok = bot.unpinMessage(m.chatId, mid);
        bot.replyText(m, Core.f(m.chatId, ok ? "unpin_success" : "generic_error", "error", "telegram_error"), null);
    }

    private static void unpinAll(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        bot.unpinAllMessages(m.chatId);
        bot.replyText(m, Core.f(m.chatId, "unpinall_success"), null);
    }

    private static void purge(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        bot.replyText(m, Core.f(m.chatId, "purge_success"), null);
    }

    private static void warn(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long uid = targetUser(bot, m);
        if (uid == 0) { bot.replyText(m, Core.f(m.chatId, "user_extract_fail", "error", "no target"), null); return; }
        String reason = Util.extractQuery(m.text).trim();
        int sp = reason.indexOf(' ');
        if (sp > 0 && reason.substring(0, sp).matches("\\d+")) reason = reason.substring(sp + 1).trim();
        long count = Database.addWarning(m.chatId, uid, m.senderId, reason);
        long limit = Database.warnLimit(m.chatId);
        bot.replyText(m, Core.f(m.chatId, "warn_success",
                "user", Util.htmlMention("user", uid),
                "count", Long.toString(count),
                "limit", Long.toString(limit)), null);
        if (limit > 0 && count >= limit) {
            String mode = Database.warnMode(m.chatId);
            if ("ban".equals(mode)) bot.banMember(m.chatId, uid, 0);
            else if ("kick".equals(mode)) { bot.banMember(m.chatId, uid, 0); bot.unbanMember(m.chatId, uid); }
            else if ("mute".equals(mode)) bot.muteMember(m.chatId, uid);
            Database.clearWarnings(m.chatId, uid);
            bot.sendText(m.chatId, Core.f(m.chatId, "warn_limit_action",
                    "user", Util.htmlMention("user", uid), "mode", mode), null);
        }
    }

    private static void warnings(Bot bot, TgMessage m) {
        long uid = targetUser(bot, m);
        if (uid == 0) uid = m.senderId;
        List<music.nandani.model.WarnEntry> list = Database.warningsList(m.chatId, uid);
        if (list.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "warnings_empty"), null); return; }
        StringBuilder sb = new StringBuilder(Core.f(m.chatId, "warnings_header",
                "user", Util.htmlMention("user", uid),
                "count", Integer.toString(list.size()),
                "limit", Long.toString(Database.warnLimit(m.chatId)))).append('\n');
        int i = 1;
        for (var w : list) {
            sb.append(i++).append(". ").append(Util.escapeHtml(w.reason == null ? "" : w.reason))
                    .append(" — <code>").append(w.by).append("</code>\n");
        }
        bot.replyText(m, sb.toString(), null);
    }

    private static void resetWarns(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long uid = targetUser(bot, m);
        if (uid == 0) { bot.replyText(m, Core.f(m.chatId, "user_extract_fail", "error", "no target"), null); return; }
        long n = Database.clearWarnings(m.chatId, uid);
        bot.replyText(m, Core.f(m.chatId, "warns_reset", "count", Long.toString(n)), null);
    }

    private static void unwarn(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        long uid = targetUser(bot, m);
        boolean ok = uid != 0 && Database.removeLastWarning(m.chatId, uid);
        bot.replyText(m, Core.f(m.chatId, ok ? "unwarn_success" : "warnings_empty"), null);
    }

    private static void setWarnLimit(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        try {
            long n = Long.parseLong(Util.extractQuery(m.text).trim());
            Database.setWarnLimit(m.chatId, n);
            bot.replyText(m, Core.f(m.chatId, "warn_limit_set", "limit", Long.toString(n)), null);
        } catch (Exception e) {
            bot.replyText(m, Core.f(m.chatId, "warn_limit_usage"), null);
        }
    }

    private static void setWarnMode(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        String mode = Util.extractQuery(m.text).trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("ban", "kick", "mute").contains(mode)) {
            bot.replyText(m, Core.f(m.chatId, "warn_mode_usage"), null);
            return;
        }
        Database.setWarnMode(m.chatId, mode);
        bot.replyText(m, Core.f(m.chatId, "warn_mode_set", "mode", mode), null);
    }

    private static void welcome(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        String arg = Util.extractQuery(m.text).trim().toLowerCase(java.util.Locale.ROOT);
        boolean on = arg.equals("on") || arg.equals("enable") || arg.equals("true");
        if (arg.isEmpty()) {
            bot.replyText(m, Core.f(m.chatId, "welcome_status",
                    "status", Database.welcomeOn(m.chatId) ? "on" : "off"), null);
            return;
        }
        Database.setWelcomeOn(m.chatId, on);
        bot.replyText(m, Core.f(m.chatId, on ? "welcome_enabled" : "welcome_disabled"), null);
    }

    // ------------------------------------------------------------------
    // blacklist
    // ------------------------------------------------------------------

    private static void blockUser(Bot bot, TgMessage m) {
        needSudo(bot, m, () -> {
            long uid = targetUser(bot, m);
            if (uid == 0) { bot.replyText(m, Core.f(m.chatId, "user_extract_fail", "error", "no target"), null); return; }
            boolean ok = Database.addBlacklistedUser(uid);
            bot.replyText(m, Core.f(m.chatId, ok ? "blacklist_block_user_success" : "blacklist_unblock_user_fail",
                    "user", Util.htmlMention("user", uid)), null);
        });
    }

    private static void unblockUser(Bot bot, TgMessage m) {
        needSudo(bot, m, () -> {
            long uid = targetUser(bot, m);
            boolean ok = uid != 0 && Database.delBlacklistedUser(uid);
            bot.replyText(m, Core.f(m.chatId, ok ? "blacklist_unblock_user_success" : "blacklist_unblock_user_fail",
                    "user", Util.htmlMention("user", uid)), null);
        });
    }

    private static void blockChat(Bot bot, TgMessage m) {
        needSudo(bot, m, () -> {
            boolean ok = Database.addBlacklistedChat(m.chatId);
            bot.replyText(m, Core.f(m.chatId, ok ? "blacklist_block_chat_success" : "blacklist_unblock_chat_fail",
                    "chat", Long.toString(m.chatId)), null);
        });
    }

    private static void unblockChat(Bot bot, TgMessage m) {
        needSudo(bot, m, () -> {
            boolean ok = Database.delBlacklistedChat(m.chatId);
            bot.replyText(m, Core.f(m.chatId, ok ? "blacklist_unblock_chat_success" : "blacklist_unblock_chat_fail",
                    "chat", Long.toString(m.chatId)), null);
        });
    }

    private static void blacklisted(Bot bot, TgMessage m) {
        StringBuilder sb = new StringBuilder(Core.f(m.chatId, "blacklist_list_title")).append('\n');
        sb.append(Core.f(m.chatId, "blacklist_list_users")).append('\n');
        for (long u : Database.blacklistedUsers()) sb.append("• <code>").append(u).append("</code>\n");
        sb.append(Core.f(m.chatId, "blacklist_list_chats")).append('\n');
        for (long c : Database.blacklistedChats()) sb.append("• <code>").append(c).append("</code>\n");
        bot.replyText(m, sb.toString(), null);
    }

    // ------------------------------------------------------------------
    // chat settings
    // ------------------------------------------------------------------

    private static void playMode(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        String arg = Util.extractQuery(m.text).trim().toLowerCase(java.util.Locale.ROOT);
        if (arg.isEmpty()) {
            boolean admins = Database.playModeAdminsOnly(m.chatId);
            bot.replyText(m, Core.f(m.chatId, "playmode_help",
                    "status", Core.f(m.chatId, admins ? "playmode_status_admins" : "playmode_status_everyone")), null);
            return;
        }
        boolean admins = arg.equals("admin") || arg.equals("admins") || arg.equals("on");
        Database.setPlayModeAdminsOnly(m.chatId, admins);
        bot.replyText(m, Core.f(m.chatId, admins ? "playmode_success_admins" : "playmode_success_everyone"), null);
    }

    private static void adminMode(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        String arg = Util.extractQuery(m.text).trim().toLowerCase(java.util.Locale.ROOT);
        boolean admins = arg.equals("admin") || arg.equals("admins") || arg.equals("on");
        Database.setPlayModeAdminsOnly(m.chatId, admins);
        bot.replyText(m, Core.f(m.chatId, "adminmode_updated",
                "status", Core.f(m.chatId, admins ? "adminmode_status_admin" : "adminmode_status_everyone")), null);
    }

    private static void noThumb(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        boolean cur = Database.thumbnailsDisabled(m.chatId);
        Database.setThumbnailsDisabled(m.chatId, !cur);
        bot.replyText(m, Core.f(m.chatId, "nothumb_updated",
                "action", Core.f(m.chatId, !cur ? "disabled" : "enabled")), null);
    }

    private static void autoLeave(Bot bot, TgMessage m) {
        if (!isSudo(m)) { bot.replyText(m, Core.f(m.chatId, "only_sudo"), null); return; }
        bot.replyText(m, Core.f(m.chatId, "autoleave_status",
                "status", Core.cfg.leaveOnDemoted ? "on" : "off"), null);
    }

    private static void cplayLink(Bot bot, TgMessage m) {
        if (!isSudo(m)) { bot.replyText(m, Core.f(m.chatId, "cplay_owner_required"), null); return; }
        String arg = Util.extractQuery(m.text).trim();
        if (arg.isEmpty()) {
            long linked = Database.linkedChannel(m.chatId);
            bot.replyText(m, linked == 0 ? Core.f(m.chatId, "cplay_id_not_set")
                    : Core.f(m.chatId, "cplay_enabled", "chat", Long.toString(linked)), null);
            return;
        }
        if (arg.equals("off") || arg.equals("disable")) {
            Database.setLinkedChannel(m.chatId, 0);
            bot.replyText(m, Core.f(m.chatId, "cplay_disabled"), null);
            return;
        }
        try {
            long id = Long.parseLong(arg);
            Database.setLinkedChannel(m.chatId, id);
            bot.replyText(m, Core.f(m.chatId, "cplay_enabled", "chat", Long.toString(id)), null);
        } catch (NumberFormatException e) {
            bot.replyText(m, Core.f(m.chatId, "cplay_invalid_target"), null);
        }
    }

    private static void autoplayCmd(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        music.nandani.room.RoomState r = Core.rooms.roomFor(m.chatId);
        boolean cur = r != null && r.active() ? r.autoplay : Database.autoplay(m.chatId);
        boolean next = !cur;
        Database.setAutoplay(m.chatId, next);
        if (r != null && r.active()) r.setAutoplay(next);
        String state = Core.f(m.chatId, next ? "enabled" : "disabled");
        bot.replyText(m, Core.f(m.chatId, "autoplay_btn", "state", state), null);
    }

    private static void jsonDump(Bot bot, TgMessage m) {
        if (!Database.isSudo(m.senderId) && m.senderId != Core.cfg.ownerId) {
            bot.replyText(m, Core.f(m.chatId, "only_sudo"), null);
            return;
        }
        String dump = "{\n"
                + "  \"chat_id\": " + m.chatId + ",\n"
                + "  \"message_id\": " + m.id + ",\n"
                + "  \"sender_id\": " + m.senderId + ",\n"
                + "  \"reply_to\": " + m.replyTo + ",\n"
                + "  \"text\": \"" + Util.escapeHtml(m.text == null ? "" : m.text.replace("\"", "\\\"")) + "\"\n"
                + "}";
        if (dump.length() > 3800) dump = dump.substring(0, 3800) + "\n…";
        bot.replyText(m, "<pre lang='json'>" + Util.escapeHtml(dump) + "</pre>", null);
    }

    private static void cleanMode(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        String arg = Util.extractQuery(m.text).trim().toLowerCase(java.util.Locale.ROOT);
        boolean enabled = Database.cleanModeEnabled(m.chatId);
        if (arg.equals("on") || arg.equals("enable") || arg.equals("enabled")) enabled = true;
        else if (arg.equals("off") || arg.equals("disable") || arg.equals("disabled")) enabled = false;
        else enabled = !enabled;
        Database.setCleanModeEnabled(m.chatId, enabled);
        String text = Core.f(m.chatId, "cleanmode_status",
                "action", Core.f(m.chatId, enabled ? "enabled" : "disabled"),
                "duration", "15")
                + "\n\n" + Core.f(m.chatId, "cleanmode_hint");
        bot.replyText(m, text, null);
    }

    // ------------------------------------------------------------------
    // system
    // ------------------------------------------------------------------

    private static void maintenance(Bot bot, TgMessage m) {
        if (!isSudo(m)) { bot.replyText(m, Core.f(m.chatId, "only_sudo"), null); return; }
        String reason = Util.extractQuery(m.text).trim();
        if (reason.isEmpty()) {
            bot.replyText(m, Core.f(m.chatId, "maint_usage"), null);
            return;
        }
        if (reason.equals("off") || reason.equals("disable")) {
            Database.setMaintenance(false);
            bot.replyText(m, Core.f(m.chatId, "maint_disabled"), null);
            return;
        }
        Database.setMaintenance(true);
        bot.replyText(m, Core.f(m.chatId, "maint_enabled_reason", "reason", Util.escapeHtml(reason)), null);
    }

    private static void broadcast(Bot bot, TgMessage m) {
        if (!isSudo(m)) { bot.replyText(m, Core.f(m.chatId, "only_sudo"), null); return; }
        String msg = Util.extractQuery(m.text).trim();
        if (msg.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "broadcast_no_content"), null); return; }
        List<Long> chats = Database.servedChats();
        List<Long> users = Database.servedUsers();
        if (chats.isEmpty() && users.isEmpty()) {
            bot.replyText(m, Core.f(m.chatId, "broadcast_no_targets"), null);
            return;
        }
        Core.BROADCAST_CANCEL.set(false);
        bot.replyText(m, Core.f(m.chatId, "broadcast_initializing",
                "chats", Integer.toString(chats.size()),
                "users", Integer.toString(users.size())), null);
        Thread t = new Thread(() -> {
            int ok = 0, fail = 0;
            for (long id : chats) {
                if (Core.BROADCAST_CANCEL.get()) break;
                if (bot.sendText(id, msg, null) != 0) ok++; else fail++;
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
            for (long id : users) {
                if (Core.BROADCAST_CANCEL.get()) break;
                if (bot.sendText(id, msg, null) != 0) ok++; else fail++;
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
            bot.sendText(m.chatId, Core.f(m.chatId, "broadcast_cancel_done",
                    "ok", Integer.toString(ok), "fail", Integer.toString(fail)), null);
        }, "broadcast");
        t.setDaemon(true);
        t.start();
    }

    private static void logger(Bot bot, TgMessage m) {
        if (!isSudo(m)) { bot.replyText(m, Core.f(m.chatId, "only_sudo"), null); return; }
        boolean v = !Database.logger();
        Database.setLogger(v);
        bot.replyText(m, Core.f(m.chatId, "logger_updated",
                "status", v ? "on" : "off"), null);
    }

    private static void reload(Bot bot, TgMessage m) {
        if (!canManage(bot, m)) { bot.replyText(m, Core.f(m.chatId, "only_admin"), null); return; }
        try {
            var fresh = music.nandani.i18n.Locales.load(
                    System.getenv().getOrDefault("LOCALES_DIR", "locales"));
            Core.loc = fresh;
            var am = Core.assistants;
            int asstTotal = am == null ? 0 : am.count();
            long asstReady = am == null ? 0 : am.all().stream().filter(a -> a.session.isReady()).count();
            String assistant = asstTotal == 0 ? "❌ ᴧʙsєηᴛ"
                    : asstReady == asstTotal ? "✅ ᴘʀєsєηᴛ (" + asstTotal + ")"
                    : "⚠️ " + asstReady + "/" + asstTotal;
            long rooms = Core.rooms.count();
            String vc = "✅ ᴧᴄᴛɪᴠє";
            bot.replyText(m, Core.f(m.chatId, "reload_done",
                    "vc", vc,
                    "assistant", assistant,
                    "rooms", Long.toString(rooms),
                    "locales", String.join(", ", fresh.available())), null);
        } catch (Exception e) {
            bot.replyText(m, Core.f(m.chatId, "generic_error", "error", e.getMessage()), null);
        }
    }

    private static void eval(Bot bot, TgMessage m) {
        if (!Database.isSudo(m.senderId) && m.senderId != Core.cfg.ownerId) {
            bot.replyText(m, Core.f(m.chatId, "only_sudo"), null);
            return;
        }
        String code = Util.extractQuery(m.text).trim();
        if (code.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "invalid_request"), null); return; }
        try {
            var js = new javax.script.ScriptEngineManager().getEngineByName("js");
            if (js == null) {
                bot.replyText(m, Util.stylish("No JS engine available in this JVM."), null);
                return;
            }
            Object out = js.eval(code);
            bot.replyText(m, "<code>" + Util.escapeHtml(String.valueOf(out)) + "</code>", null);
        } catch (Exception e) {
            bot.replyText(m, "<code>" + Util.escapeHtml(String.valueOf(e.getMessage())) + "</code>", null);
        }
    }

    private static void shell(Bot bot, TgMessage m) {
        if (!Database.isSudo(m.senderId) && m.senderId != Core.cfg.ownerId) {
            bot.replyText(m, Core.f(m.chatId, "only_sudo"), null);
            return;
        }
        String cmd = Util.extractQuery(m.text).trim();
        if (cmd.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "invalid_request"), null); return; }
        try {
            Process p = new ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(20, TimeUnit.SECONDS);
            String out = sb.toString().trim();
            if (out.length() > 3500) out = out.substring(0, 3500) + "\n…";
            bot.replyText(m, "<pre>" + Util.escapeHtml(out.isEmpty() ? "(no output)" : out) + "</pre>", null);
        } catch (Exception e) {
            bot.replyText(m, Core.f(m.chatId, "generic_error", "error", e.getMessage()), null);
        }
    }

    private static void restart(Bot bot, TgMessage m) {
        if (!isSudo(m)) { bot.replyText(m, Core.f(m.chatId, "only_sudo"), null); return; }
        bot.replyText(m, Core.f(m.chatId, "restart_initiated"), null);
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            System.exit(0);
        }, "restart").start();
    }

    private static void vcStats(Bot bot, TgMessage m) {
        var am = Core.assistants;
        StringBuilder sb = new StringBuilder(
                "<b>⚙️ 𝐕‌σɪᴄє 𝐄‌ηɢɪηє 𝐒‌ᴛᴧᴛυs</b>\n\n<blockquote>");
        sb.append("❖ <b>𝐀‌ssɪsᴛᴧηᴛs:</b> <code>").append(am == null ? 0 : am.count()).append("</code>\n");
        sb.append("❖ <b>𝐀‌ᴄᴛɪᴠє 𝐑‌σσᴍs:</b> <code>").append(Core.rooms.count()).append("</code>\n");
        if (am != null) {
            for (var a : am.all()) {
                sb.append("➥ #").append(a.index).append(" @").append(a.session.myUsername())
                        .append(" — ").append(a.session.isReady() ? "✅ ʀєᴧᴅʏ" : "⚠️ σғғʟɪηє").append('\n');
            }
        }
        sb.append("</blockquote>");
        bot.replyText(m, sb.toString(), null);
    }

    private static void activeVc(Bot bot, TgMessage m) {
        var rooms = Core.rooms.allRooms();
        if (rooms.isEmpty()) { bot.replyText(m, Core.f(m.chatId, "no_active_vc"), null); return; }
        StringBuilder sb = new StringBuilder(
                "<b>🎤 𝐀‌ᴄᴛɪᴠє 𝐕‌σɪᴄє 𝐂‌ʜᴧᴛs — " + rooms.size() + "</b>\n");
        for (var e : rooms) {
            var r = e.getValue();
            String title = r.track == null ? "—" : r.track.title;
            sb.append("\n<blockquote>❖ <b>𝐂‌ʜᴧᴛ:</b> <code>").append(e.getKey()).append("</code>\n")
                    .append("➥ <b>𝐍‌σᴡ 𝐏‌ʟᴧʏɪηɢ:</b> ").append(Util.escapeHtml(Util.shortTitle(title, 30))).append('\n')
                    .append("➥ <b>𝐐‌υєυє:</b> ").append(r.queue().size()).append(" 𝐓‌ꝛᴧᴄᴋs ♬</blockquote>");
        }
        bot.replyText(m, sb.toString(), null);
    }
}
