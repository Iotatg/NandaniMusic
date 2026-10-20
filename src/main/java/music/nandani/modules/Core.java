package music.nandani.modules;

import music.nandani.config.Config;
import music.nandani.i18n.Locales;
import music.nandani.platform.PlatformRegistry;
import music.nandani.room.Rooms;
import music.nandani.tg.Bot;
import music.nandani.voice.AssistantManager;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide singletons. The Rust bot reaches these through module-level
 * OnceLock/Mutex statics; a single holder keeps the port readable.
 */
public final class Core {

    private Core() {}

    public static volatile Config cfg;
    public static volatile Locales loc;
    public static volatile Rooms rooms = new Rooms();
    public static volatile AssistantManager assistants;
    public static volatile PlatformRegistry platforms;
    public static volatile Bot bot;
    public static volatile boolean botReady = false;
    public static volatile Monitor monitor;

    /** Chat id -&gt; in-flight download cancel flag. */
    public static final java.util.Map<Long, AtomicBoolean> DOWNLOAD_CANCELS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Broadcast cancel switch. */
    public static final AtomicBoolean BROADCAST_CANCEL = new AtomicBoolean(false);

    public static String lang(long chatId) {
        String l = music.nandani.db.Database.language(chatId);
        if (l == null || l.isEmpty()) l = cfg == null ? "en" : cfg.defaultLang;
        return l == null || l.isEmpty() ? "en" : l;
    }

    public static String f(long chatId, String key) {
        return loc.get(lang(chatId), key);
    }

    public static String f(long chatId, String key, String... kv) {
        return loc.f(lang(chatId), key, kv);
    }

    public static void registerCancel(long chatId, AtomicBoolean flag) {
        DOWNLOAD_CANCELS.put(chatId, flag);
    }

    public static void unregisterCancel(long chatId, AtomicBoolean flag) {
        DOWNLOAD_CANCELS.remove(chatId, flag);
    }

    public static boolean cancelDownload(long chatId) {
        AtomicBoolean f = DOWNLOAD_CANCELS.get(chatId);
        if (f == null) return false;
        f.set(true);
        return true;
    }

    private static final AtomicReference<String> ENGINE_TOKEN = new AtomicReference<>("");

    public static String engineToken() {
        return ENGINE_TOKEN.updateAndGet(old ->
                old.isEmpty() ? Long.toHexString(System.nanoTime()) : old);
    }
}
