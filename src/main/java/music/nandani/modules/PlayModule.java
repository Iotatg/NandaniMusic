package music.nandani.modules;

import music.nandani.db.Database;
import music.nandani.model.Track;
import music.nandani.room.PlayAction;
import music.nandani.room.RoomState;
import music.nandani.tg.Bot;
import music.nandani.tg.Markup;
import music.nandani.tg.TgMessage;
import music.nandani.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** /play family — mirrors modules/play.rs. */
public final class PlayModule {

    public record Opts(boolean force, boolean cplay, boolean video) {
        public static final Opts PLAIN = new Opts(false, false, false);
    }

    private PlayModule() {}

    public static void register(Registry r) {
        r.command("play", (b, m) -> handlePlay(b, m, Opts.PLAIN));
        r.command("fplay", (b, m) -> handlePlay(b, m, new Opts(true, false, false)));
        r.command("playforce", (b, m) -> handlePlay(b, m, new Opts(true, false, false)));
        r.command("cplay", (b, m) -> handlePlay(b, m, new Opts(false, true, false)));
        r.command("cfplay", (b, m) -> handlePlay(b, m, new Opts(true, true, false)));
        r.command("fcplay", (b, m) -> handlePlay(b, m, new Opts(true, true, false)));
        r.command("cforceplay", (b, m) -> handlePlay(b, m, new Opts(true, true, false)));
        r.command("cplayforce", (b, m) -> handlePlay(b, m, new Opts(true, true, false)));
        r.command("vplay", (b, m) -> handlePlay(b, m, new Opts(false, false, true)));
        r.command("fvplay", (b, m) -> handlePlay(b, m, new Opts(true, false, true)));
        r.command("vfplay", (b, m) -> handlePlay(b, m, new Opts(true, false, true)));
        r.command("vplayforce", (b, m) -> handlePlay(b, m, new Opts(true, false, true)));
        r.command("vcplay", (b, m) -> handlePlay(b, m, new Opts(false, true, true)));
        r.command("cvplay", (b, m) -> handlePlay(b, m, new Opts(false, true, true)));
        r.command("fvcplay", (b, m) -> handlePlay(b, m, new Opts(true, true, true)));
        r.command("vcplayforce", (b, m) -> handlePlay(b, m, new Opts(true, true, true)));
    }

    public static void handlePlay(Bot bot, TgMessage m, Opts opts) {
        if (m.isChannel) return;
        long chatId = m.chatId;
        String lang = Core.lang(chatId);

        if (Database.playModeAdminsOnly(chatId)
                && !Database.isAuthorized(chatId, m.senderId)
                && !Database.isSudo(m.senderId)) {
            bot.replyText(m, Core.f(chatId, "playmode_restricted"), null);
            return;
        }

        long roomId = chatId;
        if (opts.cplay) {
            long linked = Database.linkedChannel(chatId);
            if (linked == 0) {
                bot.replyText(m, Core.f(chatId, "cplay_id_not_set"), null);
                return;
            }
            roomId = linked;
        }
        RoomState room = Core.rooms.createRoom(roomId, Database.autoplay(roomId));
        var cfg = Core.cfg;

        if (cfg.queueLimit > 0 && room.queue().size() >= cfg.queueLimit) {
            bot.replyText(m, Core.f(chatId, "queue_limit_reached",
                    "limit", Long.toString(cfg.queueLimit)), null);
            return;
        }

        String query = Util.extractQuery(m.text);
        if (query.isEmpty() && m.replyTo == 0) {
            String key = opts.video ? "vplay_usage" : "no_song_query";
            bot.replyText(m, Core.f(chatId, key,
                    "cmd", Util.commandOf(m.text)), null);
            return;
        }

        Common.LiveMsg lm = new Common.LiveMsg();
        lm.reply(bot, chatId, query.isEmpty()
                ? Core.f(chatId, "searching")
                : Core.f(chatId, "searching_query", "query", Util.escapeHtml(query)), null, 0);

        List<Track> tracks = Core.platforms.resolveQuery(query, opts.video);
        if (tracks.isEmpty()) {
            lm.eor(bot, Core.f(chatId, "no_song_found"), null);
            return;
        }

        // Assistant membership guard.
        var am = Core.assistants;
        if (am == null || am.forChat(chatId) == null) {
            lm.eor(bot, Core.f(chatId, "err_assistant_get_failed"), null);
            return;
        }
        var assist = am.forChat(chatId);
        if (!assist.session.isChatMember(chatId)) {
            assist.session.joinChat(chatId);
            if (!assist.session.isChatMember(chatId)) {
                lm.eor(bot, Core.f(chatId, "assistant_restricted_warning",
                        "assistant", Util.htmlMention("assistant", assist.session.myUserId()),
                        "id", Long.toString(assist.session.myUserId())), null);
                return;
            }
        }

        if (tracks.size() == 1 && !opts.force && inQueue(room, tracks.get(0))) {
            lm.eor(bot, Core.f(chatId, "play_already_in_queue",
                    "title", Util.escapeHtml(Util.shortTitle(tracks.get(0).title, 35))), null);
            return;
        }

        boolean active = room.active();
        if (cfg.queueLimit == 0 && active && !opts.force) {
            lm.eor(bot, Core.f(chatId, "play_already_playing"), null);
            return;
        }

        List<Track> kept = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Track t : tracks) {
            if (cfg.durationLimit > 0 && t.duration > cfg.durationLimit) {
                skipped.add(Util.escapeHtml(Util.shortTitle(t.title, 35)));
                continue;
            }
            kept.add(t);
        }
        if (!skipped.isEmpty()) {
            if (tracks.size() == 1) {
                lm.eor(bot, Core.f(chatId, "play_single_track_too_long",
                        "limit_mins", Util.formatDuration(cfg.durationLimit),
                        "title", skipped.get(0)), null);
                return;
            }
            lm.eor(bot, skippedText(chatId, skipped), null);
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
        }
        if (kept.isEmpty()) {
            lm.eor(bot, Core.f(chatId, "play_all_tracks_skipped"), null);
            return;
        }
        // A text query plays exactly one track — never a bulk queue of all
        // search results (force or not).
        if (kept.size() > 1) {
            kept = new ArrayList<>(kept.subList(0, 1));
        }

        long availableSlots = kept.size();
        if (cfg.queueLimit >= 0) {
            availableSlots = cfg.queueLimit - room.queue().size();
            if (availableSlots < 0) availableSlots = 0;
        }
        if (availableSlots == 0 && !active) availableSlots = 1;
        if (availableSlots < kept.size()) kept = new ArrayList<>(kept.subList(0, (int) availableSlots));

        String mention = Common.mentionOf(m);
        Track first = kept.get(0).copy();
        first.requester = mention;
        first.video = opts.video;

        String path = "";
        boolean downloaded = false;
        if (!active || opts.force) {
            String title = Util.escapeHtml(Util.shortTitle(first.title, 25));
            Markup dlMarkup = first.duration > 600
                    ? Buttons.cancelKeyboard(Core.loc, lang) : null;
            lm.eor(bot, Core.f(chatId, "play_downloading_song", "title", title), dlMarkup);

            AtomicBoolean cancel = new AtomicBoolean(false);
            Core.registerCancel(chatId, cancel);
            String[] err = new String[]{""};
            path = Core.platforms.downloadTrack(first, err, cancel);
            Core.unregisterCancel(chatId, cancel);

            if ("canceled".equals(err[0])) {
                lm.eor(bot, Core.f(chatId, "play_download_canceled", "user", mention), null);
                return;
            }
            if (path.isEmpty()) {
                lm.eor(bot, Core.f(chatId, "play_download_failed"), null);
                return;
            }
            downloaded = true;
        }

        boolean played = false;
        String playErr = "";
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (room.destroyed()) return;
            playErr = "";
            PlayAction action = room.play(first, downloaded ? path : "", opts.force);
            played = action == PlayAction.STARTED || action == PlayAction.QUEUED;
            if (played && downloaded) {
                String err = Core.monitor == null
                        ? "monitor not started"
                        : Core.monitor.playNative(roomId, room, path, 0, 1.0, first.video);
                if (!err.isEmpty()) {
                    playErr = err;
                    room.rollBackFailedStart();
                    played = false;
                }
            }
            if (played) break;
            if (!retryable(playErr, attempt, chatId, lm, bot)) return;
        }
        if (!played) {
            String why = playErr.isEmpty() ? "playback failed" : playErr;
            lm.eor(bot, Core.f(chatId, "play_failed", "error", Util.escapeHtml(why)), null);
            return;
        }

        if (!active || opts.force) {
            String text = Common.nowPlayingText(chatId, first);
            Common.showTrack(lm, bot, chatId, room, first, text, false);
            if (kept.size() > 1) {
                for (Track t : kept.subList(1, kept.size())) {
                    Track c = t.copy();
                    c.requester = mention;
                    c.video = opts.video;
                    room.play(c, "", false);
                }
                String multi = multiAddedText(chatId, kept.size() - 1, mention, availableSlots, kept.size());
                bot.sendTextSync(chatId, multi, null, lm.id);
            }
            return;
        }

        if (kept.size() == 1) {
            String text = Core.f(chatId, "play_added_to_queue_single",
                    "index", Integer.toString(room.queue().size()),
                    "url", first.url,
                    "title", Util.escapeHtml(Util.shortTitle(first.title, 25)),
                    "duration", Util.formatTime(first.duration),
                    "by", mention);
            Common.showTrackQueued(lm, bot, chatId, room, text);
            return;
        }
        for (Track t : kept.subList(1, kept.size())) {
            Track c = t.copy();
            c.requester = mention;
            c.video = opts.video;
            room.play(c, "", false);
        }
        lm.eor(bot, multiAddedText(chatId, kept.size(), mention, availableSlots, kept.size()), null);
    }

    private static boolean inQueue(RoomState r, Track t) {
        for (Track q : r.queue()) {
            if (q.url.equals(t.url) || q.id.equals(t.id)) return true;
        }
        return false;
    }

    private static String skippedText(long chatId, List<String> skipped) {
        long limitMins = Core.cfg.durationLimit / 60;
        StringBuilder out = new StringBuilder(Core.f(chatId, "play_multiple_tracks_too_long_header",
                "count", Integer.toString(skipped.size()),
                "limit_mins", Long.toString(limitMins))).append('\n');
        for (int i = 0; i < skipped.size(); i++) {
            if (i < 5) {
                out.append(Core.f(chatId, "play_multiple_tracks_too_long_item",
                        "title", skipped.get(i))).append('\n');
                continue;
            }
            out.append(Core.f(chatId, "play_multiple_tracks_too_long_more",
                    "remaining", Integer.toString(skipped.size() - i))).append('\n');
            break;
        }
        return out.toString();
    }

    private static String multiAddedText(long chatId, long count, String mention,
                                         long availableSlots, long trackCount) {
        long queueLimit = Core.cfg.queueLimit;
        StringBuilder out = new StringBuilder(Core.f(chatId, "play_added_multiple_header",
                "count", Long.toString(count), "user", mention));
        out.append("\n\n");
        if (queueLimit > 0 && availableSlots <= trackCount) {
            out.append(Core.f(chatId, "play_queue_limit_hint")).append('\n');
        }
        out.append(Core.f(chatId, "play_queue_view_hint"));
        return out.toString();
    }

    /** FloodWait / connection-timeout handling, same retry contract as play.rs. */
    private static boolean retryable(String err, int attempt, long chatId,
                                     Common.LiveMsg lm, Bot bot) {
        long wait = floodWaitSeconds(err);
        if (wait > 0) {
            lm.eor(bot, wait >= 60
                    ? Core.f(chatId, "flood_minutes", "duration", Long.toString(wait / 60))
                    : Core.f(chatId, "flood_seconds", "duration", Long.toString(wait)), null);
            try { Thread.sleep(wait * 1000L); } catch (InterruptedException ignored) { }
            return true;
        }
        if (err.contains("connection timeout") || err.contains("no response from ntgcalls")
                || err.contains("timed out")) {
            lm.eor(bot, Core.f(chatId, "err_connection_timeout"), null);
            Core.rooms.dropRoom(chatId);
            return false;
        }
        if (err.contains("Streaming is not supported when using RTMP")) {
            Core.rooms.dropRoom(chatId);
            lm.eor(bot, Core.f(chatId, "rtmp_play_unsupported"), null);
            return false;
        }
        if (err.contains("group call") && err.contains("is closed")) {
            lm.eor(bot, Core.f(chatId, "err_no_active_voicechat"), null);
            return false;
        }
        if (err.contains("GROUPCALL_INVALID")) {
            Core.rooms.dropRoom(chatId);
            lm.eor(bot, Core.f(chatId, "play_unable"), null);
            return false;
        }
        if (err.contains("INTERDC_X_CALL_ERROR")) {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            return true;
        }
        return true;
    }

    private static long floodWaitSeconds(String err) {
        int p = err.indexOf("FLOOD_WAIT_");
        if (p < 0) return 0;
        int i = p + 11;
        long v = 0;
        while (i < err.length() && Character.isDigit(err.charAt(i))) {
            v = v * 10 + (err.charAt(i) - '0');
            i++;
        }
        return v;
    }
}
