package music.nandani.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import music.nandani.model.Track;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * yt-dlp driven resolver/downloader.
 * Replaces the Rust YouTube platform + provider APIs with the same observable
 * behaviour: search -&gt; pick -&gt; download to a local file -&gt; hand the path to
 * ntgcalls.
 */
public final class YtDlp {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String binary;
    private final Path downloadDir;
    private final String cookiesFile;
    /** Optional HTTP(S) proxy for extraction — datacentre IPs often hit YouTube's bot check. */
    private final String proxy;

    public YtDlp(String binary, String downloadDir, String cookiesLink) {
        this(binary, downloadDir, cookiesLink, "");
    }

    public YtDlp(String binary, String downloadDir, String cookiesLink, String proxy) {
        this.binary = binary;
        this.downloadDir = Path.of(downloadDir);
        this.cookiesFile = cookiesLink == null ? "" : cookiesLink;
        this.proxy = proxy == null ? "" : proxy.trim();
        try {
            Files.createDirectories(this.downloadDir);
        } catch (Exception e) {
            System.err.println("[yt-dlp] cannot create " + downloadDir + ": " + e.getMessage());
        }
    }

    public boolean available() {
        try {
            Process p = new ProcessBuilder(binary, "--version")
                    .redirectErrorStream(true).start();
            boolean ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroy();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** Shared flags: JS runtime, optional proxy and cookies. */
    private void addCommon(List<String> cmd) {
        cmd.add("--js-runtimes");
        cmd.add("node");
        if (!proxy.isEmpty()) {
            cmd.add("--proxy");
            cmd.add(proxy);
        }
        if (!cookiesFile.isEmpty()) {
            cmd.add("--cookies");
            cmd.add(cookiesFile);
        }
    }

    /** ytsearchN; returns up to {@code limit} tracks (empty on failure). */
    public List<Track> search(String query, int limit, boolean video) {
        List<Track> out = new ArrayList<>();
        if (query == null || query.isBlank()) return out;
        String target = query.startsWith("http://") || query.startsWith("https://")
                ? query : "ytsearch" + Math.max(1, limit) + ":" + query;
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        addCommon(cmd);
        // A URL with a list= param (autoplay RD mixes) must extract the
        // playlist — --no-playlist would return nothing for it.
        boolean playlistUrl = target.startsWith("http") && target.contains("list=");
        if (!playlistUrl) cmd.add("--no-playlist");
        cmd.add("--flat-playlist");
        cmd.add("-J");
        cmd.add(target);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return out;
            }
            JsonNode root = JSON.readTree(sb.toString());
            JsonNode entries = root.path("entries");
            if (entries.isArray()) {
                for (JsonNode e : entries) {
                    Track t = fromJson(e, video);
                    if (t != null) out.add(t);
                    if (out.size() >= limit) break;
                }
            } else {
                Track t = fromJson(root, video);
                if (t != null) out.add(t);
            }
        } catch (Exception e) {
            System.err.println("[yt-dlp] search failed: " + e.getMessage());
        }
        return out;
    }

    private Track fromJson(JsonNode e, boolean video) {
        String id = e.path("id").asText("");
        String title = e.path("title").asText("");
        if (id.isEmpty() || title.isEmpty()) return null;
        long dur = e.path("duration").asLong(0);
        String url = e.path("webpage_url").asText(
                e.path("url").asText("https://www.youtube.com/watch?v=" + id));
        Track t = new Track(id, title, dur, url, "YouTube");
        t.video = video;
        t.artwork = e.path("thumbnail").asText("");
        return t;
    }

    /** Downloads best audio (or muxed video) and returns the local path. */
    public String download(Track track, AtomicBoolean cancel, String[] err) {
        if (track == null) return "";
        String url = track.url == null || track.url.isEmpty()
                ? "https://www.youtube.com/watch?v=" + track.id : track.url;
        String ext = track.video ? "mp4" : "mp3";
        Path out = downloadDir.resolve(safeName(track.id) + "." + ext);
        if (Files.exists(out) && sizeOf(out) > 0) return out.toString();

        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        addCommon(cmd);
        cmd.add("--no-playlist");
        cmd.add("-o");
        cmd.add(downloadDir.resolve(safeName(track.id) + ".%(ext)s").toString());
        if (track.video) {
            cmd.add("-f");
            cmd.add("bv*[height<=720]+ba/b[height<=720]/b");
            cmd.add("--merge-output-format");
            cmd.add("mp4");
        } else {
            cmd.add("-f");
            cmd.add("bestaudio/best");
            cmd.add("-x");
            cmd.add("--audio-format");
            cmd.add("mp3");
            cmd.add("--audio-quality");
            cmd.add("0");
        }
        cmd.add(url);

        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder log = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        log.append(line).append('\n');
                        if (cancel != null && cancel.get()) {
                            p.destroyForcibly();
                            break;
                        }
                    }
                } catch (Exception ignored) { }
            });
            reader.setDaemon(true);
            reader.start();

            while (!p.waitFor(2, TimeUnit.SECONDS)) {
                if (cancel != null && cancel.get()) {
                    p.destroyForcibly();
                    if (err != null) err[0] = "canceled";
                    return "";
                }
            }
            reader.join(2000);
            if (p.exitValue() != 0) {
                if (err != null) err[0] = lastLines(log.toString(), 3);
                return "";
            }
            if (Files.exists(out) && sizeOf(out) > 0) return out.toString();
            // yt-dlp may pick a different container; find by id prefix.
            File[] found = downloadDir.toFile().listFiles((d, n) -> n.startsWith(safeName(track.id) + "."));
            if (found != null && found.length > 0) return found[0].getAbsolutePath();
            if (err != null) err[0] = "output file missing";
            return "";
        } catch (Exception e) {
            if (err != null) err[0] = e.getMessage();
            return "";
        }
    }

    /** Direct stream URL (used by /vplay previews and live links). */
    public String streamUrl(String url) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(binary);
            addCommon(cmd);
            cmd.add("-f");
            cmd.add("bestaudio/best");
            cmd.add("-g");
            cmd.add(url);
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.readLine();
            }
            p.waitFor(30, TimeUnit.SECONDS);
            return out == null ? "" : out.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static long sizeOf(Path p) {
        try { return Files.size(p); } catch (Exception e) { return 0; }
    }

    private static String safeName(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String lastLines(String s, int n) {
        String[] lines = s.trim().split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) {
            b.append(lines[i]).append(' ');
        }
        return b.toString().trim();
    }
}
