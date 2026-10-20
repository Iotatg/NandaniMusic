package music.nandani.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Pure formatting/parsing helpers. */
public final class Util {

    private Util() {}

    public static final String[] SMALL_CAPS = {
        "ᴧ", "ʙ", "ᴄ", "ᴅ", "є", "ғ", "ɢ", "ʜ", "ɪ", "ᴊ", "ᴋ", "ʟ", "ϻ",
        "η", "σ", "ᴘ", "ǫ", "ꝛ", "s", "ᴛ", "υ", "ᴠ", "ᴡ", "x", "ʏ", "ᴢ"
    };

    public static String formatDuration(long sec) {
        sec = Math.max(0, sec);
        if (sec < 60) return sec + "s";
        if (sec < 3600) return (sec / 60) + "m " + (sec % 60) + "s";
        if (sec < 86400) return (sec / 3600) + "h " + ((sec % 3600) / 60) + "m";
        return (sec / 86400) + "d " + ((sec % 86400) / 3600) + "h";
    }

    private static String padZero(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }

    public static String formatTime(long sec) {
        sec = Math.max(0, sec);
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return h + ":" + padZero(m) + ":" + padZero(s);
        return padZero(m) + ":" + padZero(s);
    }

    /** "used / total GB" from /proc/meminfo. */
    public static String ramInfo() {
        double total = 0, avail = 0;
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/meminfo"))) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2) continue;
                long v;
                try {
                    v = Long.parseLong(p[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if ("MemTotal:".equals(p[0])) total = v / 1024.0 / 1024.0;
                else if ("MemAvailable:".equals(p[0])) avail = v / 1024.0 / 1024.0;
            }
        } catch (Exception ignored) {
        }
        if (total <= 0) return "N/A";
        return String.format(java.util.Locale.ROOT, "%.2f / %.2f GB", total - avail, total);
    }

    /** "used / total GB" for the root filesystem. */
    public static String diskInfo() {
        java.io.File root = new java.io.File("/");
        long total = root.getTotalSpace();
        if (total <= 0) return "N/A";
        double gb = 1024.0 * 1024.0 * 1024.0;
        return String.format(java.util.Locale.ROOT, "%.2f / %.2f GB",
                (total - root.getUsableSpace()) / gb, total / gb);
    }

    /** RAM usage percentage, e.g. "34.5%". */
    public static String ramPercent() {
        double total = 0, avail = 0;
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/meminfo"))) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2) continue;
                long v;
                try {
                    v = Long.parseLong(p[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if ("MemTotal:".equals(p[0])) total = v;
                else if ("MemAvailable:".equals(p[0])) avail = v;
            }
        } catch (Exception ignored) {
        }
        if (total <= 0) return "N/A";
        return String.format(java.util.Locale.ROOT, "%.1f%%", (total - avail) / total * 100.0);
    }

    /** Root filesystem usage percentage, e.g. "17.1%". */
    public static String diskPercent() {
        java.io.File root = new java.io.File("/");
        long total = root.getTotalSpace();
        if (total <= 0) return "N/A";
        return String.format(java.util.Locale.ROOT, "%.1f%%",
                (total - root.getUsableSpace()) / (double) total * 100.0);
    }

    /** "used / total GiB" for RAM, e.g. "7.54 / 7.78 GiB". */
    public static String ramGiB() {
        double total = 0, avail = 0;
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/meminfo"))) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2) continue;
                long v;
                try {
                    v = Long.parseLong(p[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if ("MemTotal:".equals(p[0])) total = v / 1024.0 / 1024.0;
                else if ("MemAvailable:".equals(p[0])) avail = v / 1024.0 / 1024.0;
            }
        } catch (Exception ignored) {
        }
        if (total <= 0) return "N/A";
        return String.format(java.util.Locale.ROOT, "%.2f / %.2f GiB", total - avail, total);
    }

    /** "used / total GiB" for the root filesystem. */
    public static String diskGiB() {
        java.io.File root = new java.io.File("/");
        long total = root.getTotalSpace();
        if (total <= 0) return "N/A";
        double gb = 1024.0 * 1024.0 * 1024.0;
        return String.format(java.util.Locale.ROOT, "%.2f / %.2f GiB",
                (total - root.getUsableSpace()) / gb, total / gb);
    }

    private static long lastCpuUsec;
    private static long lastCpuAt;

    /** CPU load % from the cgroup v2 counter, falling back to loadavg / ncpu. */
    public static synchronized String cpuInfo() {
        long usage = -1;
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/sys/fs/cgroup/cpu.stat"))) {
                if (line.startsWith("usage_usec ")) {
                    usage = Long.parseLong(line.substring(11).trim());
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        long now = System.nanoTime();
        double pct = 0;
        if (usage >= 0) {
            if (lastCpuAt > 0 && usage >= lastCpuUsec) {
                double dt = (now - lastCpuAt) / 1e9;
                if (dt > 0) pct = (usage - lastCpuUsec) / 1e6 / dt * 100.0;
            }
            lastCpuUsec = usage;
            lastCpuAt = now;
        } else {
            try {
                String first = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/proc/loadavg")))
                        .trim().split("\\s+")[0];
                int ncpu = Math.max(1, Runtime.getRuntime().availableProcessors());
                pct = Double.parseDouble(first) / ncpu * 100.0;
            } catch (Exception ignored) {
            }
        }
        return String.format(java.util.Locale.ROOT, "%.2f%%", pct);
    }

    /**
     * Same as {@link #stylish(String)} but also leaves <code>{placeholder}</code>
     * spans alone, so templates can be styled before their arguments are
     * substituted (keeps user names / titles in their original case).
     */
    public static String stylishTemplate(String input) {
        return styleCore(input, true);
    }

    public static String formatUptime(long seconds) {
        StringBuilder r = new StringBuilder();
        long s = Math.max(0, seconds);
        long days = s / 86400; s %= 86400;
        long hours = s / 3600; s %= 3600;
        long minutes = s / 60;
        long secs = s % 60;
        if (days > 0) r.append(days).append("d ");
        if (r.length() > 0 || hours > 0) r.append(hours).append("h:");
        r.append(minutes).append("m:").append(padZero(secs)).append('s');
        return r.toString();
    }

    private static final String[] BAR = {
        "◉———————", "—◉——————", "——◉—————", "———◉————", "————◉———",
        "—————◉——", "——————◉—", "———————◉", "————————◉"
    };

    public static String getProgressBar(long playedSec, long durationSec) {
        if (durationSec <= 0 || playedSec <= 0) return BAR[0];
        if (playedSec >= durationSec) return BAR[8];
        long index = (playedSec * 9) / durationSec;
        if (index > 8) index = 8;
        return BAR[(int) index];
    }

    /** Byte-based truncation with U+2026 suffix. */
    public static String shortTitle(String title, int max) {
        if (title == null) return "";
        byte[] bytes = title.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= max) return title;
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        String out;
        try {
            out = dec.decode(ByteBuffer.wrap(bytes, 0, max)).toString();
        } catch (Exception e) {
            out = new String(bytes, 0, max, StandardCharsets.UTF_8);
        }
        return out + "\u2026";
    }

    public static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder o = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> o.append("&amp;");
                case '<' -> o.append("&lt;");
                case '>' -> o.append("&gt;");
                case '"' -> o.append("&quot;");
                default -> o.append(c);
            }
        }
        return o.toString();
    }

    public static String htmlMention(String name, long id) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = "user";
        return "<a href=\"tg://user?id=" + id + "\">" + escapeHtml(n) + "</a>";
    }

    /** Styled-glyph → ASCII, so pre-styled templates restyle cleanly. */
    private static final String pairs = "ᴀ:a,ʙ:b,ᴄ:c,ᴅ:d,ᴇ:e,є:e,ғ:f,ɢ:g,ʜ:h,ɪ:i,ᴊ:j,ᴋ:k,ʟ:l,"
            + "ϻ:m,ᴍ:m,η:n,ɴ:n,σ:o,ᴏ:o,ᴘ:p,ǫ:q,ʀ:r,ꝛ:r,ᴛ:t,υ:u,ᴜ:u,"
            + "ᴠ:v,ᴡ:w,ʏ:y,ᴢ:z,ᴧ:a";

    private static final java.util.Map<Character, Character> GLYPH_TO_ASCII = new java.util.HashMap<>();
    static {
        for (String p : pairs.split(",")) GLYPH_TO_ASCII.put(p.charAt(0), p.charAt(2));
    }

    /** Astral (supplementary-plane) styled letters → ASCII, by code point. */
    private static int astralToAscii(int cp) {
        int idx = -1;
        if (cp >= 0x1D400 && cp <= 0x1D433) idx = cp - 0x1D400;          // Mathematical Bold
        else if (cp >= 0x1D468 && cp <= 0x1D49B) idx = cp - 0x1D468;     // Bold Italic
        else if (cp >= 0x1D538 && cp <= 0x1D56B) idx = cp - 0x1D538;     // Double-struck
        else if (cp >= 0x1D5D4 && cp <= 0x1D60B) idx = cp - 0x1D5D4;     // Sans-serif Bold
        else if (cp >= 0x1D670 && cp <= 0x1D6A3) idx = cp - 0x1D670;     // Monospace
        if (idx < 0) return 0;
        return idx < 26 ? 'A' + idx : 'a' + (idx - 26);
    }

    /** Pre-pass: rewrite astral styled letters to ASCII before the char loop. */
    private static String normalize(String in) {
        boolean hasAstral = false;
        for (int i = 0; i < in.length(); i++) {
            if (Character.isSurrogate(in.charAt(i))) { hasAstral = true; break; }
        }
        if (!hasAstral) return in;
        StringBuilder b = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); ) {
            int cp = in.codePointAt(i);
            int a = astralToAscii(cp);
            if (a > 0) b.append((char) a);
            else b.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    /**
     * Signature output style: every word opens with a Mathematical-Bold
     * capital + ZWNJ, the rest of the word is small caps
     * (e.g. "Music Stream" → "𝐌‌υsɪᴄ 𝐒‌ᴛꝛєᴧᴍ").
     *
     * <p>Skipped untouched: HTML tags, <code>…</code> spans (commands stay
     * typeable), HTML entities, and (for templates) {placeholder} args.
     */
    private static String styleCore(String input, boolean protectArgs) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        input = normalize(input);
        StringBuilder out = new StringBuilder(input.length() * 2);
        boolean inTag = false, inArg = false, inEntity = false, inCode = false;
        boolean wordStart = true, slashWord = false, inBracket = false;
        for (int i = 0, len = input.length(); i < len; i++) {
            char ch = input.charAt(i);
            if (ch == '<') {
                inTag = true;
                if (input.regionMatches(i, "<code>", 0, 6)) inCode = true;
                out.append(ch);
                continue;
            }
            if (inTag) {
                out.append(ch);
                if (ch == '>') {
                    inTag = false;
                    if (input.regionMatches(i - 6, "</code>", 0, 7)) inCode = false;
                }
                continue;
            }
            if (protectArgs && ch == '{') { inArg = true; out.append(ch); continue; }
            if (protectArgs && inArg) {
                out.append(ch);
                if (ch == '}') inArg = false;
                continue;
            }
            if (ch == '&') { inEntity = true; out.append(ch); continue; }
            if (inEntity) {
                out.append(ch);
                if (ch == ';') inEntity = false;
                else if (ch == ' ' || ch == '<') inEntity = false;   // bare "&", not an entity
                continue;
            }
            boolean asciiLetter = ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z';
            Character mapped = asciiLetter ? null : GLYPH_TO_ASCII.get(ch);
            if (mapped != null && !inCode) {
                // pre-styled glyph: treat exactly like its ASCII letter
                ch = mapped;
                asciiLetter = true;
            } else if (ch == '\u200C') {
                continue;   // stray ZWNJ in template text
            }
            if (asciiLetter) {
                if (inCode || slashWord) { out.append(ch); continue; }
                if (wordStart || Character.isUpperCase(ch)) {
                    // word-initial letters and UPPER-CASE letters inside a word
                    // get the Mathematical-Bold treatment ("OS" → 𝐎‌𝐒,
                    // "CPlay" → 𝐂‌𝐏‌ʟᴧʏ), lowercase gets small caps.
                    out.appendCodePoint(0x1D400 + (Character.toUpperCase(ch) - 'A'));
                    out.append('\u200C');
                    wordStart = false;
                } else {
                    out.append(SMALL_CAPS[Character.toLowerCase(ch) - 'a']);
                }
            } else {
                // digits consume the word-start flag ("780ms" → "780ϻs");
                // the apostrophe inside a word doesn't start a new one either.
                if (ch >= '0' && ch <= '9') {
                    wordStart = false;
                } else if (ch != '\'' && ch != '_' && ch != '\u200C') {
                    // '/cmd' and '@mention' stay unstyled so they remain
                    // executable / resolvable; '[...]' spans (e.g. usage
                    // "[song name]") stay plain, spaces included.
                    if (ch == '/' || ch == '@' || ch == '[') {
                        slashWord = true;
                        if (ch == '[') inBracket = true;
                    } else if (ch == ']') {
                        inBracket = false;
                        slashWord = false;
                        wordStart = true;
                    } else if (!inBracket && (ch == ' ' || ch == '\n' || ch == '\t'
                            || ch == ')' || ch == ',' || ch == '"' || ch == '…')) {
                        slashWord = false;
                        wordStart = true;
                    }
                }
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** Smallcaps for ASCII lowercase outside tags. */
    public static String stylish(String input) {
        return styleCore(input, false);
    }

    public static String humanSize(long bytes) {
        char[] units = {'K', 'M', 'G', 'T', 'P', 'E'};
        if (bytes < 1024) return bytes + " B";
        double n = bytes / 1024.0;
        int exp = 0;
        while (n >= 1024.0 && exp + 1 < units.length) { n /= 1024.0; exp++; }
        return String.format(java.util.Locale.ROOT, "%.1f %ciB", n, units[exp]);
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String base64Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
        int val = 0, bits = -6;
        for (byte b : data) {
            val = (val << 8) + (b & 0xFF);
            bits += 8;
            while (bits >= 0) {
                out.append(B64[(val >> bits) & 0x3F]);
                bits -= 6;
            }
        }
        if (bits > -6) out.append(B64[((val << 8) >> (bits + 8)) & 0x3F]);
        while (out.length() % 4 != 0) out.append('=');
        return out.toString();
    }

    public static byte[] base64Decode(String s) {
        int[] rev = new int[256];
        java.util.Arrays.fill(rev, -1);
        for (int i = 0; i < B64.length; i++) rev[B64[i]] = i;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() * 3 / 4);
        int val = 0, bits = -8;
        for (byte bb : s.getBytes(StandardCharsets.US_ASCII)) {
            int c = bb & 0xFF;
            if (c == '=') break;
            if (c >= 256 || rev[c] < 0) continue;
            val = (val << 6) + rev[c];
            bits += 6;
            if (bits >= 0) {
                out.write((val >> bits) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** First whitespace token of a command text. */
    public static String commandOf(String text) {
        if (text == null) return "";
        int sp = text.indexOf(' ');
        return sp >= 0 ? text.substring(0, sp) : text;
    }

    public static String extractQuery(String text) {
        if (text == null) return "";
        int sp = text.indexOf(' ');
        return sp >= 0 ? text.substring(sp + 1).trim() : "";
    }

    public static boolean parseBool(String v) {
        if (v == null) return false;
        String s = v.toLowerCase(java.util.Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on");
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }
}
