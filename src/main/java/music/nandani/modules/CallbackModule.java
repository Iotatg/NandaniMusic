package music.nandani.modules;

import music.nandani.db.Database;
import music.nandani.room.RoomState;
import music.nandani.tg.Bot;
import music.nandani.tg.Markup;
import music.nandani.tg.TgCallback;
import music.nandani.util.Util;

/** Callback routing — mirrors roomcb.rs / settings_cb.rs / events.rs. */
public final class CallbackModule {

    private CallbackModule() {}

    public static void register(Registry r) {
        r.callback("room:", CallbackModule::room);
        r.callback("help:", CallbackModule::help);
        r.callback("help_cb", (b, c) -> help(b, c));
        r.callback("start", CallbackModule::start);
        r.callback("close", CallbackModule::close);
        r.callback("cancel", CallbackModule::cancel);
        r.callback("bcast_cancel", CallbackModule::cancelBroadcast);
        r.callback("lang:", CallbackModule::lang);
        r.callback("set:", CallbackModule::setting);
        r.callback("progress", CallbackModule::noop);
        r.callback("autoplay:", CallbackModule::autoplayToggle);
        r.callback("info:", CallbackModule::noop);
        r.callback("restart:", CallbackModule::noop);
    }

    private static void noop(Bot bot, TgCallback cb) {
        bot.answerCallback(cb.queryId, "", false);
    }

    private static void room(Bot bot, TgCallback cb) {
        // room:<chatId>:<action>
        String[] parts = cb.data.split(":");
        if (parts.length < 3) { bot.answerCallback(cb.queryId, "", false); return; }
        long roomId;
        try { roomId = Long.parseLong(parts[1]); } catch (NumberFormatException e) {
            bot.answerCallback(cb.queryId, "", false);
            return;
        }
        String action = parts[2];
        RoomState r = Core.rooms.roomFor(roomId);
        if (r == null || (!r.active() && !action.equals("stop"))) {
            bot.answerCallback(cb.queryId, Core.f(cb.chatId, "room_not_active_cb"), true);
            return;
        }
        if (!Database.isSudo(cb.senderId) && !Database.isAuthorized(roomId, cb.senderId)
                && cb.senderId != Core.cfg.ownerId) {
            Bot.MemberState st = bot.getChatMemberState(roomId, cb.senderId);
            if (st == Bot.MemberState.UNKNOWN) {
                var am = Core.assistants;
                if (am != null) {
                    for (var a : am.all()) {
                        if (a.session.isReady()) {
                            st = a.session.chatMemberState(roomId, cb.senderId);
                            break;
                        }
                    }
                }
            }
            if (st != Bot.MemberState.CREATOR && st != Bot.MemberState.ADMINISTRATOR) {
                bot.answerCallback(cb.queryId, Core.f(cb.chatId, "only_admin_cb"), true);
                return;
            }
        }

        switch (action) {
            case "pause" -> {
                r.pause();
                engine(roomId).ifPresent(e -> e.pause(roomId));
                bot.answerCallback(cb.queryId, Core.f(cb.chatId, "cb_pause_success"), false);
                editCard(bot, cb, r);
            }
            case "resume" -> {
                r.resume();
                engine(roomId).ifPresent(e -> e.resume(roomId));
                bot.answerCallback(cb.queryId, Core.f(cb.chatId, "cb_resume_success"), false);
                editCard(bot, cb, r);
            }
            case "replay" -> {
                r.replay();
                var a = Core.assistants == null ? null : Core.assistants.forChat(roomId);
                if (a != null && r.track != null) {
                    a.engine.playFile(roomId, r.filePath(), 0, r.speed(), r.track.video);
                }
                bot.answerCallback(cb.queryId, Core.f(cb.chatId, "cb_replay_success"), false);
                editCard(bot, cb, r);
            }
            case "skip" -> {
                bot.answerCallback(cb.queryId, Core.f(cb.chatId, "cb_skip_success"), false);
                Core.monitor.advanceRoom(roomId);
            }
            case "stop" -> {
                Markup mk = Buttons.stopConfirmMarkup(Core.loc, Core.lang(cb.chatId), r.view());
                // Parvati stop.rs parity: paused -> resume prompt, playing -> unmute prompt
                String text = Core.f(cb.chatId, r.paused ? "stop_confirm_paused" : "stop_confirm_muted");
                bot.editText(cb.chatId, cb.messageId, text, mk);
                bot.answerCallback(cb.queryId, "", false);
            }
            case "seek_15" -> {
                Long pos = r.seek(15);
                if (pos == null) {
                    bot.answerCallback(cb.queryId, Core.f(cb.chatId, "cb_seek_near_end"), true);
                    return;
                }
                seekEngine(roomId, r, pos);
                bot.answerCallback(cb.queryId, Core.f(cb.chatId, "cb_seek_success"), false);
                editCard(bot, cb, r);
            }
            case "seekback_15" -> {
                Long pos = r.seek(-15);
                if (pos == null) {
                    bot.answerCallback(cb.queryId, Core.f(cb.chatId, "cb_seek_failed"), true);
                    return;
                }
                seekEngine(roomId, r, pos);
                bot.answerCallback(cb.queryId, Core.f(cb.chatId, "cb_seekback_success"), false);
                editCard(bot, cb, r);
            }
            case "autoplay_toggle" -> {
                r.setAutoplay(!r.autoplay);
                Database.setAutoplay(roomId, r.autoplay);
                bot.answerCallback(cb.queryId,
                        Core.f(cb.chatId, r.autoplay ? "autoplay_enabled" : "autoplay_disabled"), false);
                editCard(bot, cb, r);
            }
            default -> bot.answerCallback(cb.queryId, "", false);
        }
    }

    private static void editCard(Bot bot, TgCallback cb, RoomState r) {
        String text = r.track == null ? Core.f(cb.chatId, "nothing_playing")
                : Common.nowPlayingText(cb.chatId, r.track);
        Markup mk = Buttons.playMarkup(Core.loc, Core.lang(cb.chatId), r.view(), false);
        Common.cardEdit(bot, cb.chatId, cb.messageId, text, mk, r.statusIsPhoto);
    }

    private static void seekEngine(long roomId, RoomState r, long pos) {
        var a = Core.assistants == null ? null : Core.assistants.forChat(roomId);
        if (a != null && r.track != null) {
            a.engine.playFile(roomId, r.filePath(), pos, r.speed(), r.track.video);
        }
    }

    private static java.util.Optional<music.nandani.voice.VoiceEngine> engine(long chatId) {
        var am = Core.assistants;
        if (am == null) return java.util.Optional.empty();
        var a = am.forChat(chatId);
        return a == null ? java.util.Optional.empty() : java.util.Optional.of(a.engine);
    }

    private static void help(Bot bot, TgCallback cb) {
        String topic = cb.data.startsWith("help:") ? cb.data.substring(5) : "main";
        String lang = Core.lang(cb.chatId);
        String text = Core.loc.get(lang, "help_" + topic);
        if (text.startsWith("[")) text = Core.loc.get(lang, "help_main");
        if (text.startsWith("[")) text = "<b>NandaniMusic — " + topic + "</b>";
        Markup mk = topic.equals("main") ? Buttons.helpKeyboard(Core.loc, lang)
                : Buttons.backKeyboard(Core.loc, lang);
        bot.editText(cb.chatId, cb.messageId, text, mk);
        bot.answerCallback(cb.queryId, "", false);
    }

    private static void start(Bot bot, TgCallback cb) {
        String text = Core.f(cb.chatId, "start_private");
        if (text.startsWith("[")) text = "<b>NandaniMusic</b>";
        bot.editText(cb.chatId, cb.messageId, text,
                Buttons.startMarkup(Core.cfg, Core.loc, Core.lang(cb.chatId)));
        bot.answerCallback(cb.queryId, "", false);
    }

    private static void close(Bot bot, TgCallback cb) {
        bot.deleteMessage(cb.chatId, cb.messageId);
        bot.answerCallback(cb.queryId, "", false);
    }

    private static void cancel(Bot bot, TgCallback cb) {
        boolean ok = Core.cancelDownload(cb.chatId);
        bot.answerCallback(cb.queryId,
                Core.f(cb.chatId, ok ? "download_cancelled" : "no_download_to_cancel"), !ok);
    }

    private static void cancelBroadcast(Bot bot, TgCallback cb) {
        Core.BROADCAST_CANCEL.set(true);
        bot.answerCallback(cb.queryId, Core.f(cb.chatId, "broadcast_cancel_success"), false);
    }

    private static void lang(Bot bot, TgCallback cb) {
        String code = cb.data.substring(5);
        if (code.equals("menu")) {
            Markup mk = new Markup();
            for (String c : Core.loc.available()) {
                mk.row(Markup.Button.data(Core.loc.get(c, "name"), "lang:" + c));
            }
            bot.editText(cb.chatId, cb.messageId, Core.f(cb.chatId, "lang_select"), mk);
            bot.answerCallback(cb.queryId, "", false);
            return;
        }
        if (!Core.loc.available().contains(code)) {
            bot.answerCallback(cb.queryId, Core.f(cb.chatId, "lang_fail"), true);
            return;
        }
        Database.setLanguage(cb.chatId, code);
        bot.answerCallback(cb.queryId, Core.f(cb.chatId, "lang_success", "lang", code), false);
        bot.editText(cb.chatId, cb.messageId, Core.f(cb.chatId, "settings_main"), null);
    }

    private static void setting(Bot bot, TgCallback cb) {
        String what = cb.data.substring(4);
        long chatId = cb.chatId;
        switch (what) {
            case "playmode" -> {
                boolean v = !Database.playModeAdminsOnly(chatId);
                Database.setPlayModeAdminsOnly(chatId, v);
                bot.answerCallback(cb.queryId,
                        Core.f(chatId, v ? "playmode_success_admins" : "playmode_success_everyone"), false);
            }
            case "nothumb" -> {
                boolean v = !Database.thumbnailsDisabled(chatId);
                Database.setThumbnailsDisabled(chatId, v);
                bot.answerCallback(cb.queryId, Core.f(chatId, "nothumb_updated",
                        "status", Core.f(chatId, v ? "disabled" : "enabled")), false);
            }
            case "adminmode" -> {
                boolean v = !Database.playModeAdminsOnly(chatId);
                Database.setPlayModeAdminsOnly(chatId, v);
                bot.answerCallback(cb.queryId, Core.f(chatId, "adminmode_updated",
                        "status", Core.f(chatId, v ? "adminmode_status_admin" : "adminmode_status_everyone")), false);
            }
            default -> bot.answerCallback(cb.queryId, Core.f(chatId, "settings_updated"), false);
        }
    }

    private static void autoplayToggle(Bot bot, TgCallback cb) {
        String[] parts = cb.data.split(":");
        if (parts.length < 2) return;
        long roomId;
        try { roomId = Long.parseLong(parts[1]); } catch (Exception e) { return; }
        RoomState r = Core.rooms.roomFor(roomId);
        if (r == null) { bot.answerCallback(cb.queryId, Core.f(cb.chatId, "autoplay_no_active"), true); return; }
        r.setAutoplay(!r.autoplay);
        Database.setAutoplay(roomId, r.autoplay);
        bot.answerCallback(cb.queryId,
                Core.f(cb.chatId, r.autoplay ? "autoplay_enabled" : "autoplay_disabled"), false);
    }
}
