package music.nandani.room;

import music.nandani.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-chat playback state machine — 1:1 port of bot/src/room.rs.
 * All public methods are synchronized, matching the Rust Mutex&lt;RoomState&gt; usage.
 */
public final class RoomState {

    public static final double MIN_SPEED = 0.50;
    public static final double MAX_SPEED = 4.0;
    public static final long SEEK_END_THRESHOLD = 10;
    public static final long SEEK_SAFETY_MARGIN = 5;
    public static final int HISTORY_CAP = 100;

    public final long id;
    public final long chatId;
    public long statusMsg = 0;
    public boolean statusIsPhoto = false;

    public Track track;
    public final List<Track> queue = new ArrayList<>();
    public String filePath = "";
    public boolean playing = false;
    public boolean paused = false;
    public boolean muted = false;
    public double speed = 1.0;
    public long position = 0;
    public long loopN = 0;
    public boolean shuffle = false;
    public boolean autoplay;
    public final List<String> playedHistory = new ArrayList<>();
    public String engineToken = "";
    public long updatedAt = 0;

    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicBoolean advancing = new AtomicBoolean(false);

    public RoomState(long chatId, boolean autoplay) {
        this.id = chatId;
        this.chatId = chatId;
        this.autoplay = autoplay;
    }

    private static long nowSecs() {
        return System.currentTimeMillis() / 1000L;
    }

    public synchronized boolean active() {
        return playing && track != null;
    }

    public boolean destroyed() {
        return destroyed.get();
    }

    public void destroy() {
        destroyed.set(true);
    }

    public boolean tryLockAdvance() {
        return advancing.compareAndSet(false, true);
    }

    public void unlockAdvance() {
        advancing.set(false);
    }

    private void addPlayedLocked(Track t) {
        playedHistory.add("id:" + t.id);
        String n = Track.normalizeTrackTitle(t.title);
        if (!n.isEmpty()) playedHistory.add("title:" + n);
        if (playedHistory.size() > HISTORY_CAP) {
            playedHistory.subList(0, playedHistory.size() - HISTORY_CAP).clear();
        }
    }

    public synchronized void addPlayed() {
        if (track != null) addPlayedLocked(track);
    }

    public synchronized void rollBackFailedStart() {
        track = null;
        playing = false;
        filePath = "";
    }

    /** An empty path still becomes the current track but is re-queued. */
    public synchronized PlayAction play(Track t, String path, boolean force) {
        if (destroyed()) return PlayAction.QUEUED;
        if (!force && playing && track != null) {
            queue.add(t);
            return PlayAction.QUEUED;
        }
        if (track == null || !track.id.equals(t.id)) loopN = 0;
        track = t;
        addPlayedLocked(t);
        playing = true;
        filePath = path == null ? "" : path;
        position = 0;
        paused = false;
        muted = false;
        updatedAt = nowSecs();
        if (filePath.isEmpty()) queue.add(t);
        return PlayAction.STARTED;
    }

    /** loop first, then queue head (random under shuffle). */
    public synchronized Track nextTrack() {
        if (destroyed()) return null;
        if (track != null && loopN > 0) {
            position = 0;
            playing = false;
            paused = false;
            muted = false;
            loopN--;
            updatedAt = nowSecs();
            return track;
        }
        if (queue.isEmpty()) return null;
        int index = 0;
        if (shuffle && queue.size() > 1) index = ThreadLocalRandom.current().nextInt(queue.size());
        Track next = queue.remove(index);
        track = next;
        addPlayedLocked(next);
        position = 0;
        playing = false;
        paused = false;
        muted = false;
        updatedAt = nowSecs();
        return next;
    }

    public synchronized List<Track> queue() {
        return new ArrayList<>(queue);
    }

    public synchronized void clearQueue() {
        queue.clear();
    }

    public synchronized boolean removeAt(int index) {
        if (index < 0 || index >= queue.size()) return false;
        queue.remove(index);
        return true;
    }

    public synchronized boolean moveTo(int from, int to) {
        if (from < 0 || to < 0 || from >= queue.size() || to >= queue.size()) return false;
        Track t = queue.remove(from);
        queue.add(to, t);
        return true;
    }

    public synchronized void shuffleQueue() {
        Collections.shuffle(queue);
        shuffle = true;
    }

    public synchronized void setShuffle(boolean v) { shuffle = v; }

    public synchronized void setLoop(long v) { loopN = v; }

    public synchronized void setAutoplay(boolean v) { autoplay = v; }

    public synchronized String filePath() { return filePath; }

    public synchronized List<String> playedHistory() { return new ArrayList<>(playedHistory); }

    private void updatePosition() {
        if (!playing || paused || updatedAt == 0) return;
        long now = nowSecs();
        long delta = (long) ((now - updatedAt) * speed);
        if (delta > 0) position += delta;
        updatedAt = now;
    }

    public synchronized void tickPosition() {
        updatePosition();
    }

    /** relative seek with end refusal and safety margin. */
    public synchronized Long seek(long seconds) {
        if (destroyed() || track == null || filePath.isEmpty()) return null;
        updatePosition();
        long duration = track.duration;
        if (seconds > 0 && duration - position <= SEEK_END_THRESHOLD) return null;
        long newPos = position + seconds;
        if (newPos >= duration) newPos = duration - SEEK_SAFETY_MARGIN;
        if (newPos < 0) newPos = 0;
        position = newPos;
        paused = false;
        muted = false;
        return newPos;
    }

    public synchronized Double setSpeed(double s) {
        if (destroyed() || s < MIN_SPEED || s > MAX_SPEED) return null;
        if (track == null || filePath.isEmpty()) return null;
        if (speed == s) return s;
        updatePosition();
        speed = s;
        playing = true;
        paused = false;
        muted = false;
        return s;
    }

    public synchronized double speed() { return speed; }

    public synchronized boolean pause() {
        if (destroyed()) return false;
        if (paused) return true;
        updatePosition();
        paused = true;
        muted = false;
        return true;
    }

    public synchronized boolean resume() {
        if (destroyed()) return false;
        if (!active()) return false;
        if (!paused) return true;
        paused = false;
        muted = false;
        playing = true;
        updatedAt = nowSecs();
        return true;
    }

    public synchronized boolean replay() {
        if (destroyed() || track == null || filePath.isEmpty()) return false;
        position = 0;
        paused = false;
        muted = false;
        playing = true;
        return true;
    }

    public synchronized boolean mute() {
        if (destroyed()) return false;
        if (muted) return true;
        if (paused) paused = false;
        muted = true;
        return true;
    }

    public synchronized boolean unmute() {
        if (destroyed()) return false;
        updatePosition();
        muted = false;
        paused = false;
        return true;
    }

    public synchronized boolean stop() {
        if (destroyed()) return false;
        track = null;
        position = 0;
        playing = false;
        paused = false;
        muted = false;
        updatedAt = 0;
        autoplay = false;
        return true;
    }

    /** Snapshot used by button markup. */
    public synchronized RoomView view() {
        return new RoomView(id, paused, autoplay, position, track == null ? 0 : track.duration);
    }

    public record RoomView(long id, boolean paused, boolean autoplay, long position, long duration) {}
}
