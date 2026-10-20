package music.nandani.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * YAML localization engine.
 * Flat-YAML parser: `key:` at column 0; quoted or plain scalars;
 * block scalars `|` (keep newlines) and `>` (fold to spaces).
 */
public final class Locales {

    private final Map<String, Map<String, String>> langs = new HashMap<>();
    private volatile String defaultLang = "en";
    private volatile String botName = "Nandani Music";
    /** Command currently being dispatched, used for {cmd} placeholders. */
    private static final ThreadLocal<String> currentCommand = new ThreadLocal<>();

    public static void setCurrentCommand(String command) {
        if (command == null) currentCommand.remove();
        else currentCommand.set(command);
    }

    public static Locales load(String dir) {
        Locales l = new Locales();
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("locales: " + dir + ": not a directory");
        }
        try (Stream<Path> files = Files.list(root)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".yml")) continue;
                String stem = name.substring(0, name.length() - 4);
                String text = Files.readString(p, StandardCharsets.UTF_8);
                l.langs.put(stem, parseYamlFlat(text));
            }
        } catch (IOException e) {
            throw new IllegalStateException("locales: " + dir + ": " + e.getMessage(), e);
        }
        if (l.langs.isEmpty()) throw new IllegalStateException("locales: no yml in " + dir);
        return l;
    }

    public void setDefaultLang(String lang) {
        this.defaultLang = lang;
    }

    public String defaultLang() {
        return defaultLang;
    }

    public List<String> available() {
        List<String> codes = new ArrayList<>(langs.keySet());
        Collections.sort(codes);
        return codes;
    }

    public int keys(String lang) {
        Map<String, String> m = langs.get(lang);
        return m == null ? 0 : m.size();
    }

    /** Fallback chain: lang -&gt; defaultLang -&gt; en; missing -&gt; "[key]". */
    public String get(String lang, String key, Map<String, String> args) {
        for (String l : new String[]{lang, defaultLang, "en"}) {
            if (l == null) continue;
            Map<String, String> m = langs.get(l);
            if (m != null && m.containsKey(key)) {
                // Style the template first (placeholders protected), then fill
                // in arguments so user names keep their original casing.
                String text = music.nandani.util.Util.stylishTemplate(expandEmojis(m.get(key)));
                text = substitute(text, args);
                text = text.replace("{bot}", botName);
                String cmd = currentCommand.get();
                if (cmd != null) text = text.replace("{cmd}", cmd);
                return text;
            }
        }
        System.err.println("[locales] missing key " + key);
        return "[" + key + "]";
    }

    /** Displayed bot name used for {@code {bot}} placeholders. */
    public void setBotName(String name) {
        if (name != null && !name.isBlank()) this.botName = name;
    }

    public String get(String lang, String key) {
        return get(lang, key, Collections.emptyMap());
    }

    public String f(String lang, String key, String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return get(lang, key, m);
    }

    private static String substitute(String t, Map<String, String> args) {
        String out = t;
        if (args == null) return out;
        for (Map.Entry<String, String> e : args.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /** {emoji:X} renders as the bare emoji. */
    private static String expandEmojis(String t) {
        StringBuilder out = new StringBuilder(t.length());
        int i = 0;
        while (i < t.length()) {
            if (t.startsWith("{emoji:", i)) {
                int end = t.indexOf('}', i);
                if (end > 0) {
                    out.append(t, i + 7, end);
                    i = end + 1;
                    continue;
                }
            }
            out.append(t.charAt(i));
            i++;
        }
        return out.toString();
    }

    // ---- flat YAML parser ----

    private static Map<String, String> parseYamlFlat(String text) {
        Map<String, String> map = new HashMap<>();
        String key = "";
        List<String> buf = new ArrayList<>();
        char mode = ' ';

        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.trim().isEmpty()) {
                // Keep blank lines inside "|" block templates (card layouts).
                if (key.isEmpty() || mode != '|') continue;
                buf.add("");
                continue;
            }
            if (line.stripLeading().startsWith("#")) continue;
            int ind = line.length() - line.stripLeading().length();
            if (ind == 0) {
                flush(key, buf, mode, map);
                key = "";
                buf.clear();
                mode = ' ';
                int c = line.indexOf(':');
                if (c >= 0) {
                    key = line.substring(0, c).trim();
                    String v = line.substring(c + 1).trim();
                    if (v.equals("|") || v.equals(">")) {
                        mode = v.charAt(0);
                    } else if (!v.isEmpty()) {
                        map.put(key, unquote(v));
                        key = "";
                    }
                }
            } else if (!key.isEmpty()) {
                buf.add(line.trim());
            }
        }
        flush(key, buf, mode, map);
        return map;
    }

    private static void flush(String key, List<String> buf, char mode, Map<String, String> map) {
        if (key.isEmpty() || buf.isEmpty()) return;
        String val = mode == '|' ? String.join("\n", buf) : String.join(" ", buf);
        map.put(key, unquote(val.stripTrailing()));
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            String inner = s.substring(1, s.length() - 1);
            StringBuilder out = new StringBuilder(inner.length());
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c != '\\') { out.append(c); continue; }
                if (++i >= inner.length()) { out.append('\\'); break; }
                char n = inner.charAt(i);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'u' -> {
                        if (i + 4 < inner.length()) {
                            try {
                                out.append((char) Integer.parseInt(inner.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                out.append("\\u");
                            }
                        } else out.append("\\u");
                    }
                    default -> { out.append('\\'); out.append(n); }
                }
            }
            return out.toString();
        }
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        return s;
    }
}
