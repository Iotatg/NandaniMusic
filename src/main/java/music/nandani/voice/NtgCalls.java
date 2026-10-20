package music.nandani.voice;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * JNA binding over libntgcalls 3.0.4 — 1:1 with include/ntgcalls.h
 * (synchronous C ABI: every call returns ntg_result, no futures).
 */
public final class NtgCalls implements AutoCloseable {

    // ntg_connection_state
    public static final int STATE_CONNECTING = 0;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_FAILED = 2;
    public static final int STATE_TIMEOUT = 3;
    public static final int STATE_CLOSED = 4;

    // ntg_stream_mode
    public static final int STREAM_CAPTURE = 0;

    // ntg_media_source
    public static final int SOURCE_FILE = 1;
    public static final int SOURCE_SHELL = 2;
    public static final int SOURCE_FFMPEG = 3;
    public static final int SOURCE_EXTERNAL = 6;

    // ntg_stream_device
    public static final int DEVICE_MICROPHONE = 0;
    public static final int DEVICE_SPEAKER = 1;
    public static final int DEVICE_CAMERA = 2;
    public static final int DEVICE_SCREEN = 3;

    // ntg_stream_type
    public static final int TYPE_AUDIO = 0;
    public static final int TYPE_VIDEO = 1;

    /** Resolved once at startup; overridable via FFMPEG_BIN env. */
    public static final String FFMPEG_BIN = resolveFfmpeg();

    private static String resolveFfmpeg() {
        // NOTE: must stay RELATIVE ("ffmpeg"). ntgcalls spawns via boost's
        // shell() → exe() → find_executable(), which resolves through PATH;
        // an absolute path makes find_executable return an empty path in the
        // JVM context (execve("") → ENOENT "default_launcher").
        String env = System.getenv("FFMPEG_BIN");
        if (env != null && !env.isBlank()) return env;
        return "ffmpeg";
    }

    // ------------------------------------------------------------------
    // Native structures (ntgcalls.h 3.0.4)
    // ------------------------------------------------------------------

    public interface StreamEndCb extends Callback {
        // void (*)(ntg_instance*, int64_t chat_id, ntg_stream_type, ntg_stream_device, void*)
        void invoke(Pointer handle, long chatId, int streamType, int device, Pointer userData);
    }

    public interface ConnectionCb extends Callback {
        // void (*)(ntg_instance*, int64_t chat_id, ntg_connection_info, void*) — info BY VALUE.
        void invoke(Pointer handle, long chatId, ConnectionInfo.ByValue info, Pointer userData);
    }

    public interface LogCb extends Callback {
        // void (*)(ntg_log_message, void*) — message BY VALUE.
        void invoke(LogMessage.ByValue msg, Pointer userData);
    }

    @Structure.FieldOrder({"mediaSource", "sampleRate", "channelCount", "input", "keepOpen"})
    public static class AudioDescription extends Structure {
        public int mediaSource;
        public int sampleRate;
        public byte channelCount;
        public Pointer input;
        public byte keepOpen;

        public AudioDescription() { super(); }

        public static class ByValue extends AudioDescription implements Structure.ByValue {}
    }

    @Structure.FieldOrder({"mediaSource", "width", "height", "fps", "input", "keepOpen"})
    public static class VideoDescription extends Structure {
        public int mediaSource;
        public short width;
        public short height;
        public byte fps;
        public Pointer input;
        public byte keepOpen;

        public VideoDescription() { super(); }

        public static class ByValue extends VideoDescription implements Structure.ByValue {}
    }

    @Structure.FieldOrder({"microphone", "speaker", "camera", "screen"})
    public static class MediaDescription extends Structure {
        public Pointer microphone;
        public Pointer speaker;
        public Pointer camera;
        public Pointer screen;

        public MediaDescription() { super(); }

        public static class ByValue extends MediaDescription implements Structure.ByValue {}
    }

    @Structure.FieldOrder({"state", "kind"})
    public static class ConnectionInfo extends Structure {
        public int state;
        public int kind;

        public ConnectionInfo() { super(); }

        public static class ByValue extends ConnectionInfo implements Structure.ByValue {}
    }

    @Structure.FieldOrder({"level", "source", "file", "line", "message"})
    public static class LogMessage extends Structure {
        public int level;
        public int source;
        public Pointer file;
        public int line;
        public Pointer message;

        public LogMessage() { super(); }

        public static class ByValue extends LogMessage implements Structure.ByValue {}
    }

    // ntg_frame_data {int64_t absolute_capture_timestamp_ms; ntg_video_rotation; uint16_t w; uint16_t h;}
    @Structure.FieldOrder({"absoluteCaptureTimestampMs", "rotation", "width", "height"})
    public static class FrameData extends Structure {
        public long absoluteCaptureTimestampMs;
        public int rotation;
        public short width;
        public short height;

        public FrameData() { super(); }

        public static class ByValue extends FrameData implements Structure.ByValue {}
    }

    // ntg_media_state {bool muted; bool video_paused; bool video_stopped;
    //                  bool presentation_paused; bool presentation_stopped;}
    // NOTE: C++ bool is 1 byte; JNA maps Java boolean to 4 bytes. Use byte
    // fields — with boolean fields every flag after the first reads garbage
    // (e.g. phantom videoPaused=true from presentationStopped's byte).
    @Structure.FieldOrder({"muted", "videoPaused", "videoStopped", "presentationPaused", "presentationStopped"})
    public static class MediaState extends Structure {
        public byte muted;
        public byte videoPaused;
        public byte videoStopped;
        public byte presentationPaused;
        public byte presentationStopped;

        public MediaState() { super(); }

        public boolean mutedB() { return muted != 0; }
        public boolean videoPausedB() { return videoPaused != 0; }
        public boolean videoStoppedB() { return videoStopped != 0; }
        public boolean presentationPausedB() { return presentationPaused != 0; }
        public boolean presentationStoppedB() { return presentationStopped != 0; }

        @Override
        public String toString() {
            return "muted=" + mutedB() + " videoPaused=" + videoPausedB()
                    + " videoStopped=" + videoStoppedB()
                    + " presentationPaused=" + presentationPausedB()
                    + " presentationStopped=" + presentationStoppedB();
        }
    }

    interface Lib extends Library {
        void ntg_set_log_callback(LogCb cb, Pointer userData);
        Pointer ntg_instance_create();
        void ntg_instance_destroy(Pointer handle);
        int ntg_create_call(Pointer handle, long chatId, PointerByReference out);
        int ntg_connect(Pointer handle, long chatId, Pointer params, boolean isPresentation);
        int ntg_set_stream_sources(Pointer handle, long chatId, int mode, MediaDescription.ByValue media);
        int ntg_pause(Pointer handle, long chatId, Memory out);
        int ntg_resume(Pointer handle, long chatId, Memory out);
        int ntg_mute(Pointer handle, long chatId, Memory out);
        int ntg_unmute(Pointer handle, long chatId, Memory out);
        int ntg_stop(Pointer handle, long chatId);
        int ntg_time(Pointer handle, long chatId, int mode, Memory out);
        Pointer ntg_get_version();
        Pointer ntg_last_error();
        void ntg_string_free(Pointer value);
        int ntg_on_stream_end_callback(Pointer handle, StreamEndCb cb, Pointer userData);
        int ntg_on_connection_change_callback(Pointer handle, ConnectionCb cb, Pointer userData);
        int ntg_send_external_frame(Pointer handle, long chatId, int device, byte[] data, long dataLen, FrameData.ByValue frameData);
        int ntg_get_state(Pointer handle, long chatId, MediaState out);
    }

    // ------------------------------------------------------------------
    // Error mapping (ntg_result)
    // ------------------------------------------------------------------

    private static String errName(int code) {
        return switch (code) {
            case 0 -> "OK";
            case -1 -> "UNKNOWN";
            case -2 -> "NULL_POINTER";
            case -100 -> "RTC";
            case -101 -> "SDP_PARSE";
            case -102 -> "TRANSPORT_PARSE";
            case -103 -> "RTMP_UNSUPPORTED";
            case -200 -> "CONNECTION";
            case -201 -> "CONNECTION_NOT_FOUND";
            case -202 -> "CONNECTION_ERROR";
            case -203 -> "CRYPTO_ERROR";
            case -204 -> "TELEGRAM_SERVER_ERROR";
            case -205 -> "RTC_CONNECTION_NEEDED";
            case -206 -> "INVALID_PARAMS";
            case -300 -> "SIGNALING";
            case -301 -> "SIGNALING_ERROR";
            case -302 -> "SIGNALING_UNSUPPORTED";
            case -400 -> "MEDIA";
            case -401 -> "FILE_ERROR";
            case -402 -> "FFMPEG_ERROR";
            case -403 -> "SHELL_ERROR";
            case -404 -> "MEDIA_DEVICE_ERROR";
            default -> "ERR_" + code;
        };
    }

    // ------------------------------------------------------------------
    // Descriptors
    // ------------------------------------------------------------------

    public record AudioDesc(int mediaSource, String input, int sampleRate, int channelCount) {}

    public record VideoDesc(int mediaSource, String input, short width, short height, byte fps) {}

    public record MediaDesc(AudioDesc microphone, VideoDesc camera) {}

    public record ConnInfo(boolean settled, boolean connected, String state) {}

    // ------------------------------------------------------------------
    // Client
    // ------------------------------------------------------------------

    private final Lib lib;
    private final Pointer handle;
    private final Map<Long, ConnInfo> conns = new ConcurrentHashMap<>();
    private final List<Consumer<Long>> streamEndHandlers = new ArrayList<>();
    private final List<BiConsumer<Long, Boolean>> connHandlers = new ArrayList<>();
    private final Object connLock = new Object();
    private volatile boolean closed;

    private final StreamEndCb streamEndCb = (h, chat, t, d, ud) -> {
        List<Consumer<Long>> copy;
        synchronized (streamEndHandlers) { copy = new ArrayList<>(streamEndHandlers); }
        for (Consumer<Long> handler : copy) handler.accept(chat);
    };

    private final ConnectionCb connectionCb = (h, chat, info, ud) -> {
        String state;
        boolean settled, connected;
        switch (info.state) {
            case STATE_CONNECTING -> { state = "connecting"; settled = false; connected = false; }
            case STATE_CONNECTED -> { state = "connected"; settled = true; connected = true; }
            case STATE_TIMEOUT -> { state = "timeout"; settled = true; connected = false; }
            case STATE_FAILED -> { state = "failed"; settled = true; connected = false; }
            case STATE_CLOSED -> { state = "closed"; settled = true; connected = false; }
            default -> { state = "unknown"; settled = true; connected = false; }
        }
        synchronized (connLock) {
            conns.put(chat, new ConnInfo(settled, connected, state));
            connLock.notifyAll();
        }
        List<BiConsumer<Long, Boolean>> copy;
        synchronized (connHandlers) { copy = new ArrayList<>(connHandlers); }
        for (BiConsumer<Long, Boolean> handler : copy) handler.accept(chat, connected);
    };

    public NtgCalls(String libName) {
        this.lib = Native.load(libName, Lib.class);
        lib.ntg_set_log_callback((msg, ud) -> {
            String lvl = switch (msg.level) {
                case 1 -> "TRACE";
                case 2 -> "DEBUG";
                case 4 -> "WARN";
                case 8 -> "ERROR";
                default -> "?";
            };
            String file = msg.file == null ? "" : msg.file.getString(0);
            String m = msg.message == null ? "" : msg.message.getString(0);
            // Full native log (INFO+) to a side file — webrtc INFO lines show
            // whether the video encoder/sender is actually running.
            logNative(lvl, file, msg.line, m);
            if (msg.level >= 4) System.err.println("[ntgcalls] " + lvl + " [" + file + ":" + msg.line + "] " + m);
        }, Pointer.NULL);
        this.handle = lib.ntg_instance_create();
        lib.ntg_on_connection_change_callback(handle, connectionCb, Pointer.NULL);
        lib.ntg_on_stream_end_callback(handle, streamEndCb, Pointer.NULL);
        System.out.println("[ntgcalls] init handle=" + handle + " version=" + version());
    }

    private static final Object LOG_LOCK = new Object();
    private static java.io.PrintWriter logWriter;

    private static void logNative(String lvl, String file, int line, String m) {
        synchronized (LOG_LOCK) {
            try {
                if (logWriter == null) {
                    logWriter = new java.io.PrintWriter(new java.io.FileWriter("/tmp/ntg-info.log", true), false);
                }
                logWriter.println(System.currentTimeMillis() + " " + lvl + " [" + file + ":" + line + "] " + m);
                logWriter.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    public long ptr() {
        return handle == null ? 0 : Pointer.nativeValue(handle);
    }

    public void onStreamEnd(Consumer<Long> handler) {
        synchronized (streamEndHandlers) { streamEndHandlers.add(handler); }
    }

    public void onConnectionChange(BiConsumer<Long, Boolean> handler) {
        synchronized (connHandlers) { connHandlers.add(handler); }
    }

    private String lastError() {
        try {
            Pointer p = lib.ntg_last_error();
            if (p == null) return "";
            String s = p.getString(0, StandardCharsets.UTF_8.name());
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Creates the call and returns the SDP offer, or null on failure. */
    public String create(long chatId) {
        PointerByReference buf = new PointerByReference();
        int rc = lib.ntg_create_call(handle, chatId, buf);
        if (rc != 0) {
            System.err.println("[ntgcalls] create failed rc=" + rc + " (" + errName(rc) + ") " + lastError());
            return null;
        }
        Pointer p = buf.getValue();
        if (p == null) return "";
        String sdp = p.getString(0, StandardCharsets.UTF_8.name());
        lib.ntg_string_free(p);
        // Diagnostics: the join payload must advertise the video ssrc-groups,
        // otherwise Telegram never subscribes to our camera track.
        int groups = countOccurrences(sdp, "semantics");
        System.out.println("[ntgcalls] create payload len=" + sdp.length()
                + " ssrc-groups=" + groups + " ssrc=" + sdp.contains("\"ssrc\""));
        System.out.println("[ntgcalls] payload=" + sdp);
        return sdp;
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    public boolean connect(long chatId, String params) {
        Memory m = new Memory(params.getBytes(StandardCharsets.UTF_8).length + 1L);
        m.setString(0, params);
        System.out.println("[ntgcalls] connect params len=" + params.length()
                + " hasMedia=" + params.contains("\"media\"")
                + " hasCandidates=" + params.contains("candidates"));
        int rc = lib.ntg_connect(handle, chatId, m, false);
        if (rc != 0) {
            System.err.println("[ntgcalls] connect failed rc=" + rc + " (" + errName(rc) + ") " + lastError());
            return false;
        }
        return true;
    }

    public boolean setStreamSources(long chatId, MediaDesc desc) {
        List<Memory> keepAlive = new ArrayList<>();

        AudioDescription.ByValue mic = new AudioDescription.ByValue();
        mic.mediaSource = 0;
        mic.sampleRate = 96000;
        mic.channelCount = 2;
        mic.input = null;
        mic.keepOpen = (byte) 0;

        VideoDescription.ByValue cam = new VideoDescription.ByValue();
        cam.mediaSource = 0;
        cam.width = 1280;
        cam.height = 720;
        cam.fps = 30;
        cam.input = null;
        cam.keepOpen = (byte) 0;

        if (desc.microphone() != null) {
            AudioDesc a = desc.microphone();
            mic.mediaSource = a.mediaSource();
            mic.sampleRate = a.sampleRate();
            mic.channelCount = (byte) a.channelCount();
            mic.input = cString(a.input(), keepAlive);
        }
        if (desc.camera() != null) {
            VideoDesc v = desc.camera();
            cam.mediaSource = v.mediaSource();
            cam.width = v.width();
            cam.height = v.height();
            cam.fps = v.fps();
            // EXTERNAL frames are pushed by us (sendExternalFrame) — ntgcalls
            // must not try to spawn anything for the camera source.
            cam.input = v.mediaSource() == SOURCE_EXTERNAL ? null : cString(v.input(), keepAlive);
        }
        mic.write();
        cam.write();

        MediaDescription.ByValue d = new MediaDescription.ByValue();
        d.microphone = desc.microphone() != null ? mic.getPointer() : null;
        d.speaker = null;
        d.camera = desc.camera() != null ? cam.getPointer() : null;
        d.screen = null;
        d.write();

        int rc = lib.ntg_set_stream_sources(handle, chatId, STREAM_CAPTURE, d);
        boolean ok = rc == 0;
        if (!ok) {
            System.err.println("[ntgcalls] set_stream_sources FAILED chat=" + chatId
                    + " rc=" + rc + " (" + errName(rc) + ") msg=" + lastError()
                    + " mic=" + (desc.microphone() != null) + " cam=" + (desc.camera() != null));
        }
        return ok;
    }

    private static Memory cString(String s, List<Memory> keepAlive) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        Memory m = new Memory(b.length + 1L);
        m.write(0, b, 0, b.length);
        m.setByte(b.length, (byte) 0);
        keepAlive.add(m);
        return m;
    }

    public boolean pause(long chatId) { return simple(chatId, lib::ntg_pause); }
    public boolean resume(long chatId) { return simple(chatId, lib::ntg_resume); }
    public boolean mute(long chatId) { return simple(chatId, lib::ntg_mute); }
    public boolean unmute(long chatId) { return simple(chatId, lib::ntg_unmute); }

    public boolean stop(long chatId) {
        synchronized (connLock) { conns.remove(chatId); }
        stopExternalCamera(chatId);
        int rc = lib.ntg_stop(handle, chatId);
        return rc == 0;
    }

    private interface SimpleCall {
        int invoke(Pointer handle, long chatId, Memory out);
    }

    private boolean simple(long chatId, SimpleCall call) {
        Memory out = new Memory(1);
        out.setByte(0, (byte) 0);
        int rc = call.invoke(handle, chatId, out);
        if (rc != 0) {
            System.err.println("[ntgcalls] call failed chat=" + chatId
                    + " rc=" + rc + " (" + errName(rc) + ") " + lastError());
            return false;
        }
        return true;
    }

    public long time(long chatId) {
        Memory t = new Memory(8);
        t.setLong(0, 0);
        lib.ntg_time(handle, chatId, STREAM_CAPTURE, t);
        return t.getLong(0);
    }

    /** Waits for a terminal connection state; no event at all counts as OK. */
    public boolean waitConnected(long chatId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (connLock) {
            while (true) {
                ConnInfo ci = conns.get(chatId);
                if (ci != null && ci.settled()) return ci.connected();
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    connLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            ConnInfo ci = conns.get(chatId);
            return ci == null || ci.connected();
        }
    }

    public String connectionState(long chatId) {
        ConnInfo ci = conns.get(chatId);
        return ci == null ? "no event" : ci.state();
    }

    public String version() {
        try {
            Pointer p = lib.ntg_get_version();
            if (p == null) return "unknown";
            String s = p.getString(0, StandardCharsets.UTF_8.name());
            return s == null ? "unknown" : s;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------
    // External camera: we spawn our own ffmpeg, read I420 frames and push
    // them via ntg_send_external_frame with strictly increasing timestamps.
    // This bypasses ntgcalls' internal shell reader, which shares one
    // capture timestamp across frames and makes libwebrtc drop 2 of every
    // 3 frames ("Same/old NTP timestamp") — a ~10 fps slideshow.
    // ------------------------------------------------------------------

    private final Map<Long, CameraPusher> camPushers = new ConcurrentHashMap<>();

    /** Starts the camera pipeline for a chat; replaces any running one. */
    public boolean startExternalCamera(long chatId, String command, int width, int height, int fps) {
        stopExternalCamera(chatId);
        if (command == null || command.isBlank()) return false;
        CameraPusher p = new CameraPusher(chatId, command, width, height, Math.max(1, fps));
        camPushers.put(chatId, p);
        p.start();
        return true;
    }

    public void stopExternalCamera(long chatId) {
        CameraPusher p = camPushers.remove(chatId);
        if (p != null) p.shutdown();
    }

    public boolean externalCameraRunning(long chatId) {
        CameraPusher p = camPushers.get(chatId);
        return p != null && p.running;
    }

    private final class CameraPusher implements Runnable {
        final long chatId;
        final String command;
        final int width;
        final int height;
        final int fps;
        volatile boolean running = true;
        Process proc;
        Thread thread;

        CameraPusher(long chatId, String command, int width, int height, int fps) {
            this.chatId = chatId;
            this.command = command;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }

        void start() {
            thread = new Thread(this, "cam-push-" + chatId);
            thread.setDaemon(true);
            thread.start();
        }

        void shutdown() {
            running = false;
            Process p = proc;
            if (p != null) {
                p.destroy();
            }
            Thread t = thread;
            if (t != null) t.interrupt();
        }

        @Override
        public void run() {
            final int frameSize = width * height * 3 / 2; // I420 (yuv420p)
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                proc = pb.start();
                java.io.InputStream in = new java.io.BufferedInputStream(proc.getInputStream(), 1 << 20);
                byte[] frame = new byte[frameSize];
                final long frameDurMs = 1000L / fps;
                long baseMs = System.currentTimeMillis();
                long lastTs = 0;
                long index = 0;
                while (running) {
                    readFully(in, frame);
                    long ts = baseMs + index * frameDurMs;
                    long now = System.currentTimeMillis();
                    if (ts < now) ts = now;
                    if (ts <= lastTs) ts = lastTs + 1;
                    lastTs = ts;
                    index++;

                    FrameData.ByValue fd = new FrameData.ByValue();
                    fd.absoluteCaptureTimestampMs = ts;
                    fd.rotation = 0;
                    fd.width = (short) width;
                    fd.height = (short) height;
                    int rc = lib.ntg_send_external_frame(handle, chatId, DEVICE_CAMERA, frame, (long) frameSize, fd);
                    if (rc != 0 && (index < 3 || index % 300 == 0)) {
                        System.err.println("[ntgcalls] send_external_frame rc=" + rc
                                + " chat=" + chatId + " frame#" + index);
                    } else if (index % 300 == 0) {
                        MediaState st = new MediaState();
                        int src = lib.ntg_get_state(handle, chatId, st);
                        if (st.videoPausedB()) {
                            // 3.0.4 can leave the camera stream paused after a
                            // re-set of sources — nudge it again.
                            Memory out = new Memory(1);
                            int rrc = lib.ntg_resume(handle, chatId, out);
                            System.out.println("[ntgcalls] camera pusher chat=" + chatId
                                    + " videoPaused=true → resume rc=" + rrc);
                        }
                        System.out.println("[ntgcalls] camera pusher chat=" + chatId
                                + " sent " + index + " frames (rc=0) state_rc=" + src + " " + st);
                    }

                    long target = baseMs + index * frameDurMs;
                    long sleep = target - System.currentTimeMillis();
                    if (sleep > 0) Thread.sleep(sleep);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                if (running) {
                    System.err.println("[ntgcalls] camera pusher ended chat=" + chatId + ": " + t);
                }
            } finally {
                Process p = proc;
                if (p != null) p.destroy();
                camPushers.remove(chatId, this);
            }
        }

        private static void readFully(java.io.InputStream in, byte[] buf) throws java.io.IOException {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) throw new java.io.EOFException("camera stream ended");
                off += n;
            }
        }
    }

    // ------------------------------------------------------------------
    // media_from_file (same ffmpeg pipelines as the Rust binding)
    // ------------------------------------------------------------------

    public static MediaDesc mediaFromFile(String path, long pos, double speed, boolean video) {
        double sp = Math.max(0.5, Math.min(4.0, speed));
        // Absolute path: ntgcalls spawns via boost::process execve (wordexp
        // parsing), which can fail to resolve "ffmpeg" through PATH in the
        // JVM context — a full path avoids the ENOENT entirely.
        String ffmpeg = FFMPEG_BIN;
        StringBuilder base = new StringBuilder(ffmpeg).append(' ');
        if (path.startsWith("http://") || path.startsWith("https://")) {
            base.append("-reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 5 ");
        }
        if (pos > 0) base.append("-ss ").append(pos).append(' ');
        base.append("-v warning -i \"").append(path).append("\" ");

        AudioDesc mic = new AudioDesc(SOURCE_SHELL,
                base + String.format(java.util.Locale.ROOT, "-filter:a \"atempo=%.2f\" ", sp)
                        + "-f s16le -ac 2 -ar 96000 pipe:1",
                96000, 2);
        if (!video) return new MediaDesc(mic, null);

        // Camera frames are pushed by us via ntg_send_external_frame
        // (CameraPusher paces reads at the target fps), so no -re pacing is
        // needed here — ffmpeg blocks on the pipe and follows our read rate.
        String camCmd = base.toString()
                + String.format(java.util.Locale.ROOT,
                        "-filter:v \"scale=1280:720:force_original_aspect_ratio=decrease,"
                                + "pad=1280:720:(ow-iw)/2:(oh-ih)/2,fps=30,setpts=PTS/%.2f\" ", sp)
                + "-f rawvideo -r 30 -pix_fmt yuv420p pipe:1";
        VideoDesc cam = new VideoDesc(SOURCE_EXTERNAL, camCmd, (short) 1280, (short) 720, (byte) 30);
        return new MediaDesc(mic, cam);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Deliberately do NOT call ntg_instance_destroy() here. Callbacks from
        // the native worker threads can still be in flight while the JVM is
        // shutting down; freeing the instance under them segfaults the
        // process (SIGSEGV in JNA). The OS reclaims the memory on exit.
    }
}
