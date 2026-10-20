package music.nandani.voice;

import music.nandani.tg.AssistantSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Voice engine per assistant: joins the group call with an ntgcalls SDP offer,
 * then streams a local file (join / set_stream_sources /
 * re-join on audio&lt;-&gt;video switch).
 */
public final class VoiceEngine {

    private static final class Mount {
        boolean joined;
        boolean sendingVideo;
    }

    private final NtgCalls ntg;
    private final AssistantSession assistant;
    private final Map<Long, Mount> mounts = new ConcurrentHashMap<>();

    public VoiceEngine(NtgCalls ntg, AssistantSession assistant) {
        this.ntg = ntg;
        this.assistant = assistant;
    }

    public NtgCalls ntg() { return ntg; }

    public void onStreamEnd(Consumer<Long> handler) { ntg.onStreamEnd(handler); }

    public void onConnectionChange(java.util.function.BiConsumer<Long, String> handler) {
        ntg.onConnectionChange(handler);
    }

    /** Joins the call if needed and starts streaming. Returns "" on success. */
    public synchronized String playFile(long chatId, String path, long pos, double speed, boolean video) {
        System.err.println("[voice] playFile chat=" + chatId + " video=" + video
                + " src=" + (path != null && path.length() > 160 ? path.substring(0, 160) + "…" : path));
        NtgCalls.MediaDesc desc = NtgCalls.mediaFromFile(path, pos, speed, video);
        return play(chatId, desc, video);
    }

    private String play(long chatId, NtgCalls.MediaDesc desc, boolean video) {
        if (!assistant.isReady()) return "assistant not ready";

        Mount mount = mounts.computeIfAbsent(chatId, k -> new Mount());
        if (!mount.joined) {
            return joinLocked(chatId, desc, video, mount);
        }
        if (mount.sendingVideo != video) {
            // SDP was fixed at join time: re-join to switch media mode.
            ntg.stop(chatId);
            mount.joined = false;
            return joinLocked(chatId, desc, video, mount);
        }
        return applySources(chatId, desc);
    }

    /** Sets stream sources and starts the external camera pipeline if any. */
    private String applySources(long chatId, NtgCalls.MediaDesc desc) {
        ntg.stopExternalCamera(chatId);
        if (!ntg.setStreamSources(chatId, desc)) return "set_stream_sources failed";
        // ntgcalls 3.x registers new video sources in the PAUSED state —
        // resume once so the camera track actually starts streaming.
        ntg.resume(chatId);
        NtgCalls.VideoDesc cam = desc.camera();
        if (cam != null && cam.mediaSource() == NtgCalls.SOURCE_EXTERNAL) {
            if (!ntg.startExternalCamera(chatId, cam.input(), cam.width(), cam.height(), cam.fps())) {
                return "camera pipeline failed";
            }
            System.out.println("[voice] external camera pusher started chat=" + chatId);
        }
        return "";
    }

    private String joinLocked(long chatId, NtgCalls.MediaDesc desc, boolean video, Mount mount) {
        // Sources MUST be configured before ntg_create: the SDP offer carries
        // the video m-line only for streams already configured, and Telegram
        // discards every video frame from a call joined with an audio-only
        // offer — Telegram discards video frames from audio-only offers.
        boolean preconfigured = ntg.setStreamSources(chatId, desc);
        String offer = ntg.create(chatId);
        if (offer == null) {
            ntg.stop(chatId);
            return "ntgcalls create failed";
        }
        if (!preconfigured && !ntg.setStreamSources(chatId, desc)) {
            ntg.stop(chatId);
            return "set_stream_sources failed";
        }
        String answer = assistant.joinGroupCall(chatId, offer, video);
        if (answer.isEmpty()) {
            ntg.stop(chatId);
            return "joinGroupCall failed";
        }
        if (!ntg.connect(chatId, answer)) {
            ntg.stop(chatId);
            return "webrtc connect failed";
        }
        if (!ntg.waitConnected(chatId, 20_000)) {
            String state = ntg.connectionState(chatId);
            ntg.stop(chatId);
            return "webrtc connection " + state;
        }
        mount.joined = true;
        mount.sendingVideo = video;
        // ntgcalls 3.x registers video sources paused — resume so the camera
        // track actually starts streaming.
        ntg.resume(chatId);
        return startCamera(chatId, desc);
    }

    /** Starts the external camera pipeline if the descriptor has one. */
    private String startCamera(long chatId, NtgCalls.MediaDesc desc) {
        NtgCalls.VideoDesc cam = desc.camera();
        if (cam != null && cam.mediaSource() == NtgCalls.SOURCE_EXTERNAL) {
            ntg.stopExternalCamera(chatId);
            if (!ntg.startExternalCamera(chatId, cam.input(), cam.width(), cam.height(), cam.fps())) {
                return "camera pipeline failed";
            }
            System.out.println("[voice] external camera pusher started chat=" + chatId);
        }
        return "";
    }

    public boolean pause(long chatId) { return ntg.pause(chatId); }
    public boolean resume(long chatId) { return ntg.resume(chatId); }
    public boolean mute(long chatId) { return ntg.mute(chatId); }
    public boolean unmute(long chatId) { return ntg.unmute(chatId); }

    public boolean stop(long chatId) {
        Mount m = mounts.remove(chatId);
        boolean ok = ntg.stop(chatId);
        if (m != null && m.joined) assistant.leaveGroupCall(chatId);
        return ok;
    }

    public long position(long chatId) { return ntg.time(chatId); }

    public boolean joined(long chatId) {
        Mount m = mounts.get(chatId);
        return m != null && m.joined;
    }

    public void close() {
        for (Long chatId : mounts.keySet()) stop(chatId);
    }
}
