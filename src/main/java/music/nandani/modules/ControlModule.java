package music.nandani.modules;

import music.nandani.model.Track;
import music.nandani.room.RoomState;
import music.nandani.tg.Bot;
import music.nandani.tg.TgMessage;
import music.nandani.util.Util;
import music.nandani.voice.AssistantManager;

import java.util.List;

/** Queue + transport controls. */
public final class ControlModule {

    private ControlModule() {}

    public static void register(Registry r) {
        r.command("queue", ControlModule::queue);
        r.command("cqueue", ControlModule::queue);
        r.command("skip", (b, m) -> skip(b, m, false));
        r.command("cskip", (b, m) -> skip(b, m, true));
        r.command("askip", (b, m) -> skip(b, m, false));
        r.command("caskip", (b, m) -> skip(b, m, true));
        r.command("pause", (b, m) -> pause(b, m, false));
        r.command("cpause", (b, m) -> pause(b, m, true));
        r.command("resume", (b, m) -> resume(b, m, false));
        r.command("cresume", (b, m) -> resume(b, m, true));
        r.command("stop", (b, m) -> stop(b, m, false));
        r.command("cstop", (b, m) -> stop(b, m, true));
        r.command("end", (b, m) -> stop(b, m, false));
        r.command("cend", (b, m) -> stop(b, m, true));
        r.command("seek", (b, m) -> seek(b, m, 0));
        r.command("cseek", (b, m) -> seek(b, m, 0));
        r.command("seekback", (b, m) -> seek(b, m, -15));
        r.command("cseekback", (b, m) -> seek(b, m, -15));
        r.command("loop", ControlModule::loop);
        r.command("cloop", ControlModule::loop);
        r.command("setloop", ControlModule::loop);
        r.command("csetloop", ControlModule::loop);
        r.command("shuffle", (b, m) -> shuffle(b, m, false));
        r.command("cshuffle", (b, m) -> shuffle(b, m, true));
        r.command("speed", ControlModule::speed);
        r.command("cspeed", ControlModule::speed);
        r.command("setspeed", ControlModule::speed);
        r.command("csetspeed", ControlModule::speed);
        r.command("speedup", (b, m) -> speedStep(b, m, 0.25));
        r.command("cspeedup", (b, m) -> speedStep(b, m, 0.25));
        r.command("mute", (b, m) -> mute(b, m, false));
        r.command("cmute", (b, m) -> mute(b, m, true));
        r.command("unmute", (b, m) -> unmute(b, m, false));
        r.command("cunmute", (b, m) -> unmute(b, m, true));
        r.command("replay", (b, m) -> replay(b, m, false));
        r.command("creplay", (b, m) -> replay(b, m, true));
        r.command("position", ControlModule::position);
        r.command("cposition", ControlModule::position);
        r.command("pos", ControlModule::position);
        r.command("cpos", ControlModule::position);
        r.command("jump", ControlModule::jump);
        r.command("cjump", ControlModule::jump);
        r.command("move", ControlModule::move);
        r.command("cmove", ControlModule::move);
        r.command("remove", ControlModule::remove);
        r.command("cremove", ControlModule::remove);
        r.command("clear", (b, m) -> clear(b, m, false));
        r.command("cclear", (b, m) -> clear(b, m, true));
    }

    private static RoomState roomFor(long chatId, boolean cplay) {
        long id = chatId;
        if (cplay) {
            long linked = music.nandani.db.Database.linkedChannel(chatId);
            if (linked != 0) id = linked;
        }
        return Core.rooms.roomFor(id);
    }

    private static boolean authorized(Bot bot, TgMessage m) {
        if (music.nandani.db.Database.isSudo(m.senderId)
                || music.nandani.db.Database.isAuthorized(m.chatId, m.senderId)
                || m.senderId == Core.cfg.ownerId) return true;
        // Group admins: resolve via the bot, then via an assistant session
        // (bot sessions often fail channels.getParticipant with PEER_ID_INVALID).
        Bot.MemberState st = bot.getChatMemberState(m.chatId, m.senderId);
        if (st == Bot.MemberState.UNKNOWN) {
            var am = Core.assistants;
            if (am != null) {
                for (var a : am.all()) {
                    if (a.session.isReady()) {
                        st = a.session.chatMemberState(m.chatId, m.senderId);
                        break;
                    }
                }
            }
        }
        return st == Bot.MemberState.CREATOR || st == Bot.MemberState.ADMINISTRATOR;
    }

    private static void queue(Bot bot, TgMessage m) {
        RoomState r = roomFor(m.chatId, m.text.startsWith("/c"));
        if (r == null || !r.active()) {
            bot.replyText(m, Core.f(m.chatId, "queue_empty"), null);
            return;
        }
        List<Track> q = r.queue();
        Track cur = r.track;
        StringBuilder sb = new StringBuilder(Core.f(m.chatId, "queue_header",
                "count", Integer.toString(q.size() + (cur == null ? 0 : 1)))).append('\n');
        if (cur != null) {
            sb.append(Core.f(m.chatId, "queue_now_playing_item",
                    "url", cur.url == null ? "" : cur.url,
                    "title", Util.escapeHtml(Util.shortTitle(cur.title, 30)),
                    "duration", Util.formatDuration(cur.duration),
                    "by", cur.requester)).append('\n');
        }
        int i = 1;
        for (Track t : q) {
            sb.append(Core.f(m.chatId, "queue_item",
                    "index", Integer.toString(i++),
                    "url", t.url == null ? "" : t.url,
                    "title", Util.escapeHtml(Util.shortTitle(t.title, 30)),
                    "duration", Util.formatDuration(t.duration),
                    "by", t.requester)).append('\n');
            if (i > 30) {
                sb.append(Core.f(m.chatId, "queue_more",
                        "remaining", Integer.toString(q.size() - 30)));
                break;
            }
        }
        bot.replyText(m, sb.toString(), Buttons.playMarkup(Core.loc, Core.lang(m.chatId), r.view(), true));
    }

    private static void skip(Bot bot, TgMessage m, boolean cplay) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, cplay);
        if (r == null || !r.active()) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        bot.replyText(m, Core.f(m.chatId, "skipped"), null);
        Core.monitor.advanceRoom(r.id);
    }

    private static void pause(Bot bot, TgMessage m, boolean cplay) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, cplay);
        if (r == null || !r.active()) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        r.pause();
        engine(r.id).ifPresent(e -> e.pause(r.id));
        bot.replyText(m, Core.f(m.chatId, "paused"), Buttons.playMarkup(Core.loc, Core.lang(m.chatId), r.view(), false));
    }

    private static void resume(Bot bot, TgMessage m, boolean cplay) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, cplay);
        if (r == null || !r.active()) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        r.resume();
        engine(r.id).ifPresent(e -> e.resume(r.id));
        bot.replyText(m, Core.f(m.chatId, "resumed"), Buttons.playMarkup(Core.loc, Core.lang(m.chatId), r.view(), false));
    }

    private static void stop(Bot bot, TgMessage m, boolean cplay) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, cplay);
        if (r == null) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        engine(r.id).ifPresent(e -> e.stop(r.id));
        r.stop();
        r.clearQueue();
        if (r.statusMsg != 0) {
            bot.deleteMessage(r.chatId, r.statusMsg);
            r.statusMsg = 0;
        }
        Core.rooms.dropRoom(r.id);
        bot.replyText(m, Core.f(m.chatId, "stopped"), null);
    }

    private static void seek(Bot bot, TgMessage m, long delta) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, false);
        if (r == null || !r.active()) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        long secs = delta;
        if (delta == 0) {
            String q = Util.extractQuery(m.text);
            try {
                secs = Long.parseLong(q.trim());
            } catch (Exception e) {
                bot.replyText(m, Core.f(m.chatId, "seek_invalid"), null);
                return;
            }
        }
        Long pos = r.seek(secs);
        if (pos == null) {
            bot.replyText(m, Core.f(m.chatId, "seek_failed"), null);
            return;
        }
        AssistantManager.Assistant a = Core.assistants == null ? null : Core.assistants.forChat(r.id);
        if (a != null && r.track != null) {
            a.engine.playFile(r.id, r.filePath(), pos, r.speed(), r.track.video);
        }
        bot.replyText(m, Core.f(m.chatId, "seeked", "position", Util.formatTime(pos)),
                Buttons.playMarkup(Core.loc, Core.lang(m.chatId), r.view(), false));
    }

    private static void loop(Bot bot, TgMessage m) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, false);
        if (r == null) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        String q = Util.extractQuery(m.text).trim().toLowerCase(java.util.Locale.ROOT);
        if (q.equals("enable") || q.equals("on")) {
            r.setLoop(1);
            bot.replyText(m, Core.f(m.chatId, "loop_enabled"), null);
            return;
        }
        if (q.equals("disable") || q.equals("off")) {
            r.setLoop(0);
            bot.replyText(m, Core.f(m.chatId, "loop_disabled"), null);
            return;
        }
        long n = 0;
        try { n = Long.parseLong(q); } catch (Exception ignored) { }
        r.setLoop(n);
        bot.replyText(m, Core.f(m.chatId, n > 0 ? "loop_set" : "loop_disabled",
                "count", Long.toString(n)), null);
    }

    private static void shuffle(Bot bot, TgMessage m, boolean cplay) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, cplay);
        if (r == null || r.queue().isEmpty()) {
            bot.replyText(m, Core.f(m.chatId, "queue_empty"), null);
            return;
        }
        r.shuffleQueue();
        bot.replyText(m, Core.f(m.chatId, "shuffled"), null);
    }

    private static void speed(Bot bot, TgMessage m) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, false);
        if (r == null || !r.active()) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        String q = Util.extractQuery(m.text).trim();
        double s;
        try { s = Double.parseDouble(q); } catch (Exception e) {
            bot.replyText(m, Core.f(m.chatId, "speed_invalid"), null);
            return;
        }
        Double applied = r.setSpeed(s);
        if (applied == null) {
            bot.replyText(m, Core.f(m.chatId, "speed_invalid"), null);
            return;
        }
        AssistantManager.Assistant a = Core.assistants == null ? null : Core.assistants.forChat(r.id);
        if (a != null && r.track != null) {
            a.engine.playFile(r.id, r.filePath(), r.position, applied, r.track.video);
        }
        bot.replyText(m, Core.f(m.chatId, "speed_set",
                "speed", String.format(java.util.Locale.ROOT, "%.2f", applied)), null);
    }

    private static void speedStep(Bot bot, TgMessage m, double step) {
        RoomState r = roomFor(m.chatId, false);
        if (r == null || !r.active()) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        double s = Math.min(RoomState.MAX_SPEED, r.speed() + step);
        r.setSpeed(s);
        AssistantManager.Assistant a = Core.assistants == null ? null : Core.assistants.forChat(r.id);
        if (a != null && r.track != null) {
            a.engine.playFile(r.id, r.filePath(), r.position, s, r.track.video);
        }
        bot.replyText(m, Core.f(m.chatId, "speed_set",
                "speed", String.format(java.util.Locale.ROOT, "%.2f", s)), null);
    }

    private static void mute(Bot bot, TgMessage m, boolean cplay) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, cplay);
        if (r == null || !r.active()) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        r.mute();
        engine(r.id).ifPresent(e -> e.mute(r.id));
        bot.replyText(m, Core.f(m.chatId, "muted"), null);
    }

    private static void unmute(Bot bot, TgMessage m, boolean cplay) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, cplay);
        if (r == null || !r.active()) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        r.unmute();
        engine(r.id).ifPresent(e -> e.unmute(r.id));
        bot.replyText(m, Core.f(m.chatId, "unmuted"), null);
    }

    private static void replay(Bot bot, TgMessage m, boolean cplay) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, cplay);
        if (r == null || !r.replay()) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        AssistantManager.Assistant a = Core.assistants == null ? null : Core.assistants.forChat(r.id);
        if (a != null && r.track != null) {
            a.engine.playFile(r.id, r.filePath(), 0, r.speed(), r.track.video);
        }
        bot.replyText(m, Core.f(m.chatId, "cb_replaying"), null);
    }

    private static void position(Bot bot, TgMessage m) {
        RoomState r = roomFor(m.chatId, false);
        if (r == null || !r.active()) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        r.tickPosition();
        long dur = r.track == null ? 0 : r.track.duration;
        bot.replyText(m, Core.f(m.chatId, "position_text",
                "position", Util.formatTime(r.position),
                "duration", Util.formatTime(dur),
                "bar", Util.getProgressBar(r.position, dur)), null);
    }

    private static void jump(Bot bot, TgMessage m) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, false);
        if (r == null) {
            bot.replyText(m, Core.f(m.chatId, "room_no_active"), null);
            return;
        }
        int idx;
        try { idx = Integer.parseInt(Util.extractQuery(m.text).trim()); } catch (Exception e) {
            bot.replyText(m, Core.f(m.chatId, "jump_invalid"), null);
            return;
        }
        List<Track> q = r.queue();
        if (idx < 1 || idx > q.size()) {
            bot.replyText(m, Core.f(m.chatId, "jump_invalid"), null);
            return;
        }
        Track t = q.get(idx - 1);
        for (int i = 0; i < idx - 1; i++) r.removeAt(0);
        r.removeAt(0);
        String[] err = new String[]{""};
        String path = Core.platforms.downloadTrack(t, err, null);
        if (path.isEmpty()) {
            bot.replyText(m, Core.f(m.chatId, "play_download_failed"), null);
            return;
        }
        AssistantManager.Assistant a = Core.assistants == null ? null : Core.assistants.forChat(r.id);
        if (a != null) a.engine.playFile(r.id, path, 0, r.speed(), t.video);
        r.play(t, path, true);
        Common.sendNowPlaying(bot, r.chatId, r, t);
        bot.replyText(m, Core.f(m.chatId, "jumped", "index", Integer.toString(idx)), null);
    }

    private static void move(Bot bot, TgMessage m) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, false);
        if (r == null) return;
        String[] parts = Util.extractQuery(m.text).trim().split("\\s+");
        if (parts.length < 2) {
            bot.replyText(m, Core.f(m.chatId, "move_usage"), null);
            return;
        }
        try {
            int from = Integer.parseInt(parts[0]) - 1;
            int to = Integer.parseInt(parts[1]) - 1;
            boolean ok = r.moveTo(from, to);
            bot.replyText(m, Core.f(m.chatId, ok ? "moved" : "move_invalid"), null);
        } catch (NumberFormatException e) {
            bot.replyText(m, Core.f(m.chatId, "move_usage"), null);
        }
    }

    private static void remove(Bot bot, TgMessage m) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, false);
        if (r == null) return;
        try {
            int idx = Integer.parseInt(Util.extractQuery(m.text).trim()) - 1;
            boolean ok = r.removeAt(idx);
            bot.replyText(m, Core.f(m.chatId, ok ? "removed" : "remove_invalid"), null);
        } catch (NumberFormatException e) {
            bot.replyText(m, Core.f(m.chatId, "remove_invalid"), null);
        }
    }

    private static void clear(Bot bot, TgMessage m, boolean cplay) {
        if (!authorized(bot, m)) {
            bot.replyText(m, Core.f(m.chatId, "only_admin"), null);
            return;
        }
        RoomState r = roomFor(m.chatId, cplay);
        if (r == null) return;
        r.clearQueue();
        bot.replyText(m, Core.f(m.chatId, "queue_cleared"), null);
    }

    private static java.util.Optional<music.nandani.voice.VoiceEngine> engine(long chatId) {
        AssistantManager am = Core.assistants;
        if (am == null) return java.util.Optional.empty();
        AssistantManager.Assistant a = am.forChat(chatId);
        return a == null ? java.util.Optional.empty() : java.util.Optional.of(a.engine);
    }
}
