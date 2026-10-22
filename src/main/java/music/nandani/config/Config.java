package music.nandani.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static music.nandani.util.Util.parseBool;

/**
 * Runtime configuration (environment variables).
 * Reads standard environment variables so an
 * existing .env keeps working unchanged.
 */
public final class Config {

    public final int apiId;
    public final String apiHash;
    public final String token;
    public final String mongoUri;
    public final List<String> stringSessions;
    public final String dbName;
    public final String sessionType;
    public final long loggerId;
    public final long ownerId;
    public final String ownerUsername;
    public final String devUrl;
    public final boolean disableColour;
    public final String meowApiUrl;
    public final String meowApiKey;
    public final String yukiApiUrl;
    public final String yukiApiKey;
    public final String iotaApiUrl;
    public final String iotaApiKey;
    public final String defaultLang;
    public final long durationLimit;
    public final boolean leaveOnDemoted;
    public final long queueLimit;
    public final String supportChat;
    public final String supportChannel;
    public final String cookiesLink;
    public final boolean setCmds;
    public final long maxAuthUsers;
    public final List<String> startImages;
    public final String pingImage;
    public final String repoImage;
    public final String port;
    /** MTProto data-centre id (1..5). */
    public final int dc;
    /** Displayed bot name (fancy glyphs) used for {bot}. */
    public final String botBrand;

    /** TDLib database root for the bot account (bot API id/hash login). */
    public final String tdlibBotDir;
    /** TDLib database root for assistant accounts (one subdir per assistant). */
    public final String tdlibAssistantDir;
    /** Phone numbers for assistant accounts, comma separated (first login only). */
    public final List<String> assistantPhones;
    /** libntgcalls shared object path/name. */
    public final String ntgcallsLib;
    /** libtdjson shared object path/name. */
    public final String tdjsonLib;

    public final long startTime = System.currentTimeMillis();

    private Config(Path dotenv) {
        loadDotenv(dotenv);

        this.apiId = (int) getenvI64("API_ID", 0);
        this.apiHash = getenvStr("API_HASH", "");
        this.dc = (int) getenvI64("DC", 2);
        this.botBrand = getenvStr("BOT_BRAND",
                "\uD835\uDC0D\u200C\u1D00\u0274\u1D05\u1D00\u0274\u026A\u0020\uD835\uDE05\u0020\u03FB\u200C\u03C5\u0073\u026A\u1D04");
        String t = getenvStr("TOKEN", "");
        this.token = t.isEmpty() ? getenvStr("BOT_TOKEN", "") : t;
        this.mongoUri = getenvStr("MONGO_DB_URI", "");

        String rawSessions = getenvStr("STRING_SESSIONS", "");
        if (rawSessions.isEmpty()) rawSessions = getenvStr("STRING_SESSION", "");
        this.stringSessions = splitCsv(rawSessions);

        this.dbName = getenvStr("DB_NAME", "NandaniMusic");
        this.sessionType = getenvStr("SESSION_TYPE", "271");

        long l = getenvI64("LOGGER_ID", 0);
        this.loggerId = l != 0 ? l : getenvI64("LOG_GROUP_ID", 0);
        this.ownerId = getenvI64("OWNER_ID", 0);
        this.ownerUsername = getenvStr("OWNER_USERNAME", "");

        String d = getenvStr("DEV_URL", "");
        this.devUrl = d.isEmpty() ? getenvStr("DEVELOPER_URL", "") : d;
        this.disableColour = parseBool(getenvStr("DISABLE_COLOUR", ""));

        this.meowApiUrl = getenvStr("MEOW_API_URL", "");
        this.meowApiKey = getenvStr("MEOW_API_KEY", "");
        String y = getenvStr("YUKI_API_URL", "");
        this.yukiApiUrl = y.isEmpty() ? meowApiUrl : y;
        String yk = getenvStr("YUKI_API_KEY", "");
        this.yukiApiKey = yk.isEmpty() ? meowApiKey : yk;
        this.iotaApiUrl = getenvStr("IOTA_API_URL", "http://127.0.0.1:8000");
        this.iotaApiKey = getenvStr("IOTA_API_KEY", "");

        this.defaultLang = getenvStr("DEFAULT_LANG", "en");
        this.durationLimit = getenvI64("DURATION_LIMIT", 9000);
        this.leaveOnDemoted = parseBool(getenvStr("LEAVE_ON_DEMOTED", ""));
        this.queueLimit = getenvI64("QUEUE_LIMIT", 500);
        this.supportChat = getenvStr("SUPPORT_CHAT", "https://t.me/+Q493A_VBAUpkMmRl");
        this.supportChannel = getenvStr("SUPPORT_CHANNEL", "https://t.me/im_iota");
        this.cookiesLink = getenvStr("COOKIES_LINK", "");
        this.setCmds = parseBool(getenvStr("SET_CMDS", ""));
        this.maxAuthUsers = getenvI64("MAX_AUTH_USERS", 10);

        String rawImgs = getenvStr("START_IMAGES", "");
        if (rawImgs.isEmpty()) rawImgs = getenvStr("START_IMG_URL", "");
        this.startImages = splitCsv(rawImgs);
        this.pingImage = getenvStr("PING_IMG_URL", "");
        this.repoImage = getenvStr("REPO_IMG_URL", "");
        this.port = getenvStr("PORT", "8000");

        this.tdlibBotDir = getenvStr("TDLIB_BOT_DIR", "data/tdlib-bot");
        this.tdlibAssistantDir = getenvStr("TDLIB_ASSISTANT_DIR", "data/tdlib-assistant");
        this.assistantPhones = splitCsv(getenvStr("ASSISTANT_PHONES", ""));
        this.ntgcallsLib = getenvStr("NTGCALLS_LIB", "ntgcalls");
        this.tdjsonLib = getenvStr("TDJSON_LIB", "tdjson");
    }

    public static Config load() {
        return loadFrom(Path.of(".env"));
    }

    public static Config loadFrom(Path dotenv) {
        Config c = new Config(dotenv);
        boolean dryRun = music.nandani.util.Util.parseBool(
                System.getenv().getOrDefault("NANDANI_DRY_RUN", ""));
        if (dryRun) {
            // Offline console mode needs no Telegram credentials at all.
            return c;
        }
        List<String> missing = new ArrayList<>();
        if (c.apiId == 0) missing.add("API_ID");
        if (c.apiHash.isEmpty()) missing.add("API_HASH");
        if (c.token.isEmpty()) missing.add("TOKEN");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("config: missing required env " + String.join(", ", missing));
        }
        return c;
    }

    public String startImage() {
        return startImages.isEmpty() ? "" : startImages.get(0);
    }

    public long uptimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000L;
    }

    private static List<String> splitCsv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** Effective environment: process env first, then .env values for absent keys. */
    private static final java.util.Map<String, String> ENV = new java.util.HashMap<>();

    /** Raw lookup in the merged view — process env first, then .env. */
    public static String env(String key) {
        String v = ENV.get(key);
        if (v != null) return v;
        return System.getenv().getOrDefault(key, "");
    }

    private static String getenvStr(String key, String def) {
        String v = ENV.get(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static long getenvI64(String key, long def) {
        String v = ENV.get(key);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Minimal .env loader: KEY=VALUE, quotes stripped, existing env wins. */
    private static void loadDotenv(Path path) {
        ENV.putAll(System.getenv());
        if (path == null || !Files.isReadable(path)) return;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq < 0) continue;
                String k = s.substring(0, eq).trim();
                String v = s.substring(eq + 1).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                ENV.putIfAbsent(k, v);
            }
        } catch (IOException ignored) {
            // .env is optional
        }
    }
}
