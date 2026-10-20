package music.nandani.modules;

import music.nandani.model.Track;
import music.nandani.platform.PlatformRegistry;
import music.nandani.room.PlayAction;
import music.nandani.room.RoomState;
import music.nandani.tg.Bot;
import music.nandani.tg.Markup;
import music.nandani.voice.AssistantManager;
import music.nandani.voice.VoiceEngine;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Playback driver: native start/stop, queue advance and the 4s room watchdog.
 */
public final class Monitor {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "nandani-monitor");
        t.setDaemon(true);
        return t;
    });

    public void start() {
        scheduler.scheduleWithFixedDelay(this::watchdog, 4, 4, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    private void tick() {
        for (var e : Core.rooms.allRooms()) {
            RoomState r = e.getValue();
            if (r.active() && !r.paused) r.tickPosition();
        }
    }

    /** chatId -> consecutive end ticks past track duration. */
    private final java.util.Map<Long, Integer> endStreak = new java.util.HashMap<>();

    private void watchdog() {
        for (var e : Core.rooms.allRooms()) {
            long chatId = e.getKey();
            RoomState r = e.getValue();
            if (r.destroyed() || !r.active() || r.paused) continue;
            AssistantManager am = Core.assistants;
            if (am == null || am.forChat(chatId) == null) continue;
            Track t = r.track;
            if (t == null) continue;
            long enginePos = am.forChat(chatId).engine.position(chatId);
            long statePos = r.position;
            // Engine reports 0 while buffering; only trust it once it advanced.
            if (enginePos > 0 && Math.abs(enginePos - statePos) > 12) {
                r.position = enginePos;
            }
            // 3 consecutive ticks past the duration
            // means the stream-end event was missed — rescue the queue.
            if (t.duration > 0 && r.position >= t.duration) {
                int s = endStreak.merge(chatId, 1, Integer::sum);
                if (s >= 3) {
                    endStreak.remove(chatId);
                    System.out.println("[monitor] end watchdog: advancing chat " + chatId);
                    advanceRoom(chatId);
                    continue;
                }
            } else {
                endStreak.remove(chatId);
            }
            // Keep the now-playing card's progress row fresh.
            long mid = r.statusMsg;
            Bot bot = Core.bot;
            if (mid != 0 && bot != null && Core.botReady
                    && !(t.duration > 0 && r.position >= t.duration)) {
                String text = Common.nowPlayingText(chatId, t);
                Markup mk = Buttons.playMarkup(Core.loc, Core.lang(chatId), r.view(), false);
                Common.cardEdit(bot, chatId, mid, text, mk, r.statusIsPhoto);
            }
        }
    }

    /** Hands an already-play()ed track to the voice engine. */
    public boolean startTrack(long chatId, RoomState r, String path, boolean video) {
        AssistantManager am = Core.assistants;
        AssistantManager.Assistant a = am == null ? null : am.forChat(chatId);
        if (a == null) {
            r.playing = true;
            return true;
        }
        String err = a.engine.playFile(chatId, path, 0, r.speed(), video);
        if (err.isEmpty()) {
            r.playing = true;
            return true;
        }
        System.err.println("[monitor] start failed chat=" + chatId + ": " + err);
        r.rollBackFailedStart();
        return false;
    }

    public String playNative(long chatId, RoomState r, String path, long pos, double speed, boolean video) {
        AssistantManager am = Core.assistants;
        AssistantManager.Assistant a = am == null ? null : am.forChat(chatId);
        if (a == null) return "no assistant configured for this chat";
        return a.engine.playFile(chatId, path, pos, speed, video);
    }

    /** Stream-end / watchdog entry point. */
    public boolean advanceRoom(long chatId) {
        RoomState r = Core.rooms.roomFor(chatId);
        if (r == null) return false;
        if (r.destroyed() || !r.tryLockAdvance()) return false;
        try {
            return advanceLocked(chatId, r);
        } finally {
            r.unlockAdvance();
        }
    }

    private boolean advanceLocked(long chatId, RoomState r) {
        Bot bot = Core.bot;
        // Loop mode repeats the current track.
        if (r.loopN > 0) {
            r.setLoop(r.loopN - 1);
            if (r.replay()) {
                String path = r.filePath();
                boolean video = r.track != null && r.track.video;
                if (!playNative(chatId, r, path, 0, r.speed(), video).isEmpty()) {
                    r.position = 0;
                    return true;
                }
                String text = Core.f(chatId, "cb_replaying");
                if (r.statusMsg == 0) {
                    r.statusMsg = bot.sendTextSync(chatId, text, null, 0);
                    r.statusIsPhoto = false;
                } else {
                    Common.cardEdit(bot, chatId, r.statusMsg, text, null, r.statusIsPhoto);
                }
            }
            return true;
        }

        Track next = r.nextTrack();
        if (next == null && r.autoplay && r.track != null) {
            Track last = r.track;
            List<Track> pool = Core.platforms.autoplayTracks(last, 5, r.playedHistory());
            Track pick = PlatformRegistry.goldenZonePick(pool);
            if (pick != null) {
                pick.markAutoplay(Core.f(chatId, "autoplay_requester"), r.engineToken);
                next = pick;
            }
        }

        if (next == null) {
            Common.setStatus(bot, r, Core.f(chatId, "stream_queue_finished"));
            Core.rooms.dropRoom(chatId);
            return false;
        }

        Common.setStatus(bot, r, Core.f(chatId, "stream_downloading_next"));
        String[] err = new String[]{""};
        String path = Core.platforms.downloadTrack(next, err, null);
        if (path.isEmpty() && next.isAutoplay && r.track != null) {
            for (Track c : Core.platforms.autoplayTracks(r.track, 8, r.playedHistory())) {
                String[] e2 = new String[]{""};
                String p = Core.platforms.downloadTrack(c, e2, null);
                if (!p.isEmpty()) {
                    next = c;
                    path = p;
                    break;
                }
            }
        }
        if (path.isEmpty()) {
            Common.setStatus(bot, r, Core.f(chatId, "stream_downloading_next"));
            return false;
        }

        PlayAction action = r.play(next, path, true);
        if (action == PlayAction.FAILED) return false;
        String perr = playNative(chatId, r, path, 0, r.speed(), next.video);
        if (!perr.isEmpty()) {
            r.rollBackFailedStart();
            return false;
        }
        r.playing = true;
        r.position = 0;
        r.updatedAt = System.currentTimeMillis() / 1000L;
        Common.sendNowPlaying(bot, chatId, r, next);
        return true;
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
