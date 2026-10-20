package music.nandani.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import music.nandani.config.Config;
import music.nandani.media.YtDlp;
import music.nandani.model.Track;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Platform resolution + download dispatch.
 * Ordering: Iota API (95) -&gt; Yuki API (85) -&gt; yt-dlp / direct HTTP fallback.
 */
public final class PlatformRegistry {

    public static final String PLATFORM_YOUTUBE = "YouTube";
    public static final String PLATFORM_IOTA = "IotaApi";
    public static final String PLATFORM_YUKI = "YukiApi";
    public static final String PLATFORM_TELEGRAM = "Telegram";
    public static final String PLATFORM_HTTP = "Http";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final Config cfg;
    private final YtDlp yt;

    public PlatformRegistry(Config cfg) {
        this.cfg = cfg;
        // Extraction proxy: datacentre IPs frequently hit YouTube's bot check;
        // a residential/working proxy fixes video (yt-dlp) extraction.
        String proxy = System.getenv("YTDLP_PROXY");
        if (proxy == null || proxy.isBlank()) proxy = System.getenv("PROXY");
        // Cookies: COOKIES_LINK env, or a local data/cookies.txt fallback
        // (export with a browser extension like "Get cookies.txt LOCALLY").
        String cookies = cfg.cookiesLink;
        if (cookies == null || cookies.isBlank()) {
            Path local = Path.of("data/cookies.txt");
            if (Files.isReadable(local)) cookies = local.toString();
        }
        this.yt = new YtDlp(
                System.getenv().getOrDefault("YTDLP_BIN", "yt-dlp"),
                System.getenv().getOrDefault("DOWNLOAD_DIR", "data/downloads"),
                cookies,
                proxy == null ? "" : proxy);
    }

    public YtDlp ytdlp() { return yt; }

    public List<Track> resolveQuery(String query, boolean video) {
        List<Track> out = new ArrayList<>();
        if (query == null || query.isBlank()) return out;

        // Direct URL: no search needed.
        if (query.startsWith("http://") || query.startsWith("https://")) {
            if (query.contains("youtube.com") || query.contains("youtu.be")) {
                List<Track> y = yt.search(query, 1, video);
                if (!y.isEmpty()) return y;
            }
            Track t = new Track(query, query, 0, query, PLATFORM_HTTP);
            t.video = video;
            out.add(t);
            return out;
        }

        List<Track> iota = iotaSearch(query, 5, video);
        if (!iota.isEmpty()) return iota;
        List<Track> yuki = yukiSearch(query, 5, video);
        if (!yuki.isEmpty()) return yuki;

        return yt.search(query, 5, video);
    }

    public String downloadTrack(Track t, String[] err, AtomicBoolean cancel) {
        if (t == null) return "";
        fillArtwork(t);
        if (PLATFORM_HTTP.equals(t.source) && t.url.startsWith("http")) {
            // Stream URLs are passed straight to ffmpeg by ntgcalls.
            return t.url;
        }
        // Iota (self-hosted API): range-capable audio stream + merged 720p video.
        // Its stream proxy fans out across parallel googlevideo connections, so
        // prefer it whenever the endpoint actually answers.
        if (PLATFORM_IOTA.equals(t.source) && t.id != null && !t.id.isEmpty()) {
            String u = iotaMediaUrl(t, t.video);
            if (!u.isEmpty() && httpReachable(u)) return u;
            // fall through to the generic tiers below
        }
        // Video requests: prefer the Yuki video gateway when it exists, then
        // the yt-dlp direct URL, then a full yt-dlp download.
        if (t.video) {
            String yv = yukiVideoStreamUrl(t);
            if (!yv.isEmpty() && httpReachable(yv)) return yv;
            String v = ytVideoStreamUrl(t);
            if (!v.isEmpty() && httpReachable(v)) return v;
            return yt.download(t, cancel, err);
        }
        // Yuki stream gateway: direct HTTP audio by video id — no download.
        String yuki = yukiStreamUrl(t);
        if (!yuki.isEmpty() && httpReachable(yuki)) return yuki;
        return yt.download(t, cancel, err);
    }

    /** Thumbnails make the now-playing card a photo — derive from the video id. */
    private void fillArtwork(Track t) {
        if (t.artwork != null && !t.artwork.isBlank()) return;
        String id = t.id == null ? "" : t.id;
        // Iota media ids carry a provider prefix (yt:VIDEOID).
        if (id.startsWith("yt:")) id = id.substring(3);
        if (id.isEmpty() && t.url != null && (t.url.contains("youtube.com") || t.url.contains("youtu.be"))) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:[?&]v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{6,15})")
                    .matcher(t.url);
            if (m.find()) id = m.group(1);
        }
        if (!id.isEmpty()) {
            t.artwork = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        }
    }

    /** Direct progressive video URL via yt-dlp (no download, streamed by ffmpeg). */
    private String ytVideoStreamUrl(Track t) {
        try {
            String vid = t.id == null ? "" : t.id;
            if (vid.startsWith("yt:")) vid = vid.substring(3);
            String url = t.url != null && t.url.startsWith("http") ? t.url
                    : "https://www.youtube.com/watch?v=" + vid;
            List<String> cmd = new ArrayList<>();
            cmd.add("yt-dlp");
            cmd.add("--js-runtimes");
            cmd.add("node");
            String proxy = System.getenv("YTDLP_PROXY");
            if (proxy == null || proxy.isBlank()) proxy = System.getenv("PROXY");
            if (proxy != null && !proxy.isBlank()) {
                cmd.add("--proxy");
                cmd.add(proxy.trim());
            }
            cmd.add("-f");
            cmd.add("best[height<=720][ext=mp4]/best[height<=720]/best");
            cmd.add("--no-playlist");
            cmd.add("-g");
            cmd.add(url);
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true).start();
            String out;
            try (var r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                out = r.readLine();
            }
            p.waitFor(40, java.util.concurrent.TimeUnit.SECONDS);
            return out == null ? "" : out.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Iota media URL — {base}/stream/{media_id}?key=… for audio,
     * {base}/download/{media_id}?kind=video&amp;max_height=720&amp;key=… for video.
     * Video URLs are only returned when the endpoint confirms video/mp4.
     */
    private String iotaMediaUrl(Track t, boolean video) {
        if (cfg.iotaApiUrl == null || cfg.iotaApiUrl.isBlank()) return "";
        String base = cfg.iotaApiUrl.replaceAll("/+$", "");
        String id = t.id == null ? "" : t.id;
        if (id.isEmpty()) return "";
        String key = cfg.iotaApiKey == null ? "" : cfg.iotaApiKey;
        if (!video) return base + "/stream/" + id + "?key=" + key;
        String url = base + "/download/" + id + "?kind=video&max_height=720&key=" + key;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Range", "bytes=0-1")
                    .GET().build();
            HttpResponse<Void> res = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            String ct = res.headers().firstValue("content-type").orElse("");
            int sc = res.statusCode();
            if (sc / 100 == 2 && ct.startsWith("video/")) return url;
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * Yuki gateway URL for a track — https://music.yukiapi.site/stream/{VIDEO_ID}?key=…
     * YUKI_API_URL may be either the site base or a full template containing {VIDEO_ID}.
     */
    private String yukiStreamUrl(Track t) {
        if (cfg.yukiApiKey == null || cfg.yukiApiKey.isBlank()) return "";
        String base = cfg.yukiApiUrl == null ? "" : cfg.yukiApiUrl.trim();
        if (base.isEmpty()) return "";
        String id = t.id == null ? "" : t.id;
        if (id.startsWith("yt:")) id = id.substring(3);
        if (id.isEmpty() && t.url != null && (t.url.contains("youtube.com") || t.url.contains("youtu.be"))) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:[?&]v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{6,15})")
                    .matcher(t.url);
            if (m.find()) id = m.group(1);
        }
        if (id.isEmpty()) return "";
        if (base.contains("{VIDEO_ID}")) {
            return base.replace("{VIDEO_ID}", id)
                    + (base.contains("?") ? "&" : "?") + "key=" + cfg.yukiApiKey;
        }
        return base.replaceAll("/+$", "") + "/stream/" + id + "?key=" + cfg.yukiApiKey;
    }

    /**
     * Yuki video gateway URL — https://music.yukiapi.site/video/{VIDEO_ID}?key=…
     * Only accepted when the endpoint actually serves video/* content (the
     * current Yuki gateways stream mp3 audio only, so this is future-proofing).
     */
    private String yukiVideoStreamUrl(Track t) {
        if (cfg.yukiApiKey == null || cfg.yukiApiKey.isBlank()) return "";
        String base = cfg.yukiApiUrl == null ? "" : cfg.yukiApiUrl.trim();
        if (base.isEmpty()) return "";
        String id = t.id == null ? "" : t.id;
        if (id.isEmpty()) return "";
        String url = base.replaceAll("/+$", "") + "/video/" + id + "?key=" + cfg.yukiApiKey;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Range", "bytes=0-1")
                    .GET().build();
            HttpResponse<Void> res = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            String ct = res.headers().firstValue("content-type").orElse("");
            int sc = res.statusCode();
            if (sc / 100 == 2 && ct.startsWith("video/")) return url;
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Cheap Range GET — the gateway must serve bytes before we skip yt-dlp. */
    private boolean httpReachable(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Range", "bytes=0-1")
                    .GET().build();
            HttpResponse<Void> res = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            int sc = res.statusCode();
            return sc / 100 == 2 || sc == 416; // 416: alive but odd range handling
        } catch (Exception e) {
            return false;
        }
    }

    public List<Track> autoplayTracks(Track last, int limit, List<String> history) {
        List<Track> filtered = new ArrayList<>();
        java.util.function.Predicate<Track> accept = t -> {
            if ((t.id == null || t.id.isEmpty()) && (t.url == null || t.url.isEmpty())) return false;
            if (t.id != null && !t.id.isEmpty() && t.id.equals(last.id)) return false;
            if (t.id != null && !t.id.isEmpty() && history.contains("id:" + t.id)) return false;
            String n = Track.normalizeTrackTitle(t.title);
            return n.isEmpty() || !history.contains("title:" + n);
        };
        // Tier 0: iota /similar (fast API)
        for (Track t : iotaSimilar(last, 8)) {
            if (!accept.test(t)) continue;
            t.video = last.video;
            filtered.add(t);
            if (limit > 0 && filtered.size() >= limit) break;
        }
        // Tier 1: YouTube radio mix, Tier 2: "<title> similar songs"
        if (filtered.isEmpty()) {
            for (Track t : related(last, limit + 4)) {
                if (!accept.test(t)) continue;
                t.video = last.video;
                filtered.add(t);
                if (limit > 0 && filtered.size() >= limit) break;
            }
        }
        return filtered;
    }

    /** iota /similar/{media_id} — Tier 0 autoplay source. */
    private List<Track> iotaSimilar(Track last, int limit) {
        if (cfg.iotaApiUrl == null || cfg.iotaApiUrl.isBlank() || last == null) return List.of();
        String media = "";
        if (PLATFORM_YOUTUBE.equals(last.source) && last.id != null && !last.id.isEmpty()) {
            media = "yt:" + last.id;
        } else if (PLATFORM_IOTA.equals(last.source) && last.id != null) {
            media = last.id;
        }
        if (media.isEmpty()) return List.of();
        String key = cfg.iotaApiKey == null ? "" : cfg.iotaApiKey;
        String url = cfg.iotaApiUrl.replaceAll("/+$", "")
                + "/similar/" + java.net.URLEncoder.encode(media, java.nio.charset.StandardCharsets.UTF_8)
                + "?limit=" + limit + (key.isBlank() ? "" : "&key=" + key);
        List<Track> out = new ArrayList<>();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12)).GET();
            if (!key.isBlank()) b.header("X-API-Key", key);
            HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return out;
            JsonNode arr = JSON.readTree(res.body()).path("results");
            if (!arr.isArray()) return out;
            for (JsonNode n : arr) {
                String id = n.path("media_id").asText("");
                String title = n.path("title").asText("");
                if (id.isEmpty() || title.isEmpty()) continue;
                String artist = n.path("artist").asText("");
                if (!artist.isEmpty() && !title.isEmpty()) title = title + " - " + artist;
                String link = id.startsWith("yt:")
                        ? "https://youtu.be/" + id.substring(3)
                        : n.path("stream_url").asText("");
                Track t = new Track(id, title, n.path("duration_seconds").asLong(0), link, PLATFORM_IOTA);
                t.artwork = n.path("thumbnail_url").asText("");
                out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<Track> related(Track last, int limit) {
        List<Track> out = new ArrayList<>();
        if (last.source.equals(PLATFORM_YOUTUBE) && !last.id.isEmpty()) {
            out.addAll(yt.search("https://www.youtube.com/watch?v=" + last.id + "&list=RD" + last.id, limit, last.video));
        }
        if (out.isEmpty()) {
            String seed = Track.normalizeTrackTitle(last.title);
            if (seed.isEmpty()) seed = last.title;
            out.addAll(yt.search(seed + " similar songs", limit, last.video));
        }
        return out;
    }

    /** Random pick from the top 4 candidates. */
    public static Track goldenZonePick(List<Track> candidates) {
        int pool = Math.min(candidates.size(), 4);
        if (pool == 0) return null;
        int i = pool == 1 ? 0 : ThreadLocalRandom.current().nextInt(pool);
        return candidates.get(i);
    }

    // ------------------------------------------------------------------
    // Optional provider APIs
    // ------------------------------------------------------------------

    private List<Track> iotaSearch(String query, int limit, boolean video) {
        if (cfg.iotaApiUrl == null || cfg.iotaApiUrl.isBlank()) return List.of();
        StringBuilder url = new StringBuilder(cfg.iotaApiUrl.replaceAll("/+$", ""))
                .append("/search?q=")
                .append(java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8))
                .append("&limit=").append(limit);
        // Iota authenticates via the X-API-Key header (or ?key= — we probe with
        // the header and append the key so httpReachable() calls also pass).
        String key = cfg.iotaApiKey == null ? "" : cfg.iotaApiKey;
        if (!key.isBlank()) url.append("&key=").append(key);
        List<Track> out = new ArrayList<>();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(25)).GET();
            if (!key.isBlank()) b.header("X-API-Key", key);
            HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return out;
            JsonNode arr = JSON.readTree(res.body()).path("results");
            if (!arr.isArray()) return out;
            for (JsonNode n : arr) {
                String id = n.path("media_id").asText("");
                String title = n.path("title").asText("");
                if (id.isEmpty() || title.isEmpty()) continue;
                // yt:VIDEOID → give the card a clickable youtu.be link.
                String link = id.startsWith("yt:")
                        ? "https://youtu.be/" + id.substring(3)
                        : n.path("stream_url").asText("");
                Track t = new Track(id, title,
                        n.path("duration_seconds").asLong(0), link, PLATFORM_IOTA);
                t.video = video;
                t.artwork = n.path("thumbnail_url").asText("");
                out.add(t);
                if (out.size() >= 5) break;
            }
        } catch (Exception e) {
            // provider down: fall through to the next tier
        }
        return out;
    }

    private List<Track> yukiSearch(String query, int limit, boolean video) {
        if (cfg.yukiApiUrl == null || cfg.yukiApiUrl.isBlank()) return List.of();
        StringBuilder url = new StringBuilder(cfg.yukiApiUrl.replaceAll("/+$", ""))
                .append("/search?q=")
                .append(java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8))
                .append("&limit=").append(limit);
        // The Yuki API authenticates via the ?key= query parameter.
        if (cfg.yukiApiKey != null && !cfg.yukiApiKey.isBlank()) {
            url.append("&key=").append(cfg.yukiApiKey);
        }
        return httpSearch(url.toString(), "", PLATFORM_YUKI, video);
    }

    private List<Track> httpSearch(String url, String apiKey, String source, boolean video) {
        List<Track> out = new ArrayList<>();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12)).GET();
            if (apiKey != null && !apiKey.isBlank()) b.header("Authorization", apiKey);
            HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return out;
            JsonNode root = JSON.readTree(res.body());
            JsonNode arr = root.isArray() ? root : root.path("results");
            if (!arr.isArray()) return out;
            for (JsonNode n : arr) {
                String id = n.path("id").asText(n.path("videoId").asText(""));
                String title = n.path("title").asText("");
                if (title.isEmpty()) continue;
                String link = n.path("url").asText(n.path("link").asText(""));
                Track t = new Track(id, title, n.path("duration").asLong(0), link, source);
                t.video = video;
                t.artwork = n.path("thumbnail").asText(n.path("artwork").asText(""));
                out.add(t);
                if (out.size() >= 5) break;
            }
        } catch (Exception e) {
            // provider down: fall through to the next tier
        }
        return out;
    }

    public static String artworkLocalPath(String artwork) {
        if (artwork == null || artwork.isBlank()) return "";
        return artwork;
    }
}
