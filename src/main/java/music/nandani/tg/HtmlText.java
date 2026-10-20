package music.nandani.tg;

import music.nandani.mtproto.TlNode;

import java.util.ArrayList;
import java.util.List;

/** Parses the HTML subset used by locales into text + TL message entities. */
public final class HtmlText {

    public static final class Result {
        public final String text;
        public final List<Object> entities;

        Result(String text, List<Object> entities) {
            this.text = text;
            this.entities = entities;
        }
    }

    private static final class Open {
        final String tag;
        final String extra;
        final int start;

        Open(String tag, String extra, int start) {
            this.tag = tag;
            this.extra = extra;
            this.start = start;
        }
    }

    public static Result parse(String html) {
        if (html == null) return new Result("", new ArrayList<>());
        StringBuilder sb = new StringBuilder(html.length());
        List<Object> entities = new ArrayList<>();
        List<Open> stack = new ArrayList<>();

        int i = 0;
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c == '<') {
                int end = html.indexOf('>', i);
                if (end < 0) {
                    sb.append(html.substring(i));
                    break;
                }
                String tag = html.substring(i + 1, end).trim();
                i = end + 1;
                if (tag.startsWith("/")) {
                    String name = tag.substring(1).trim().toLowerCase(java.util.Locale.ROOT);
                    for (int k = stack.size() - 1; k >= 0; k--) {
                        if (stack.get(k).tag.equals(name)) {
                            Open o = stack.remove(k);
                            addEntity(entities, name, o.extra, o.start, sb.length());
                            break;
                        }
                    }
                } else if (tag.equalsIgnoreCase("br")) {
                    sb.append('\n');
                } else if (tag.equalsIgnoreCase("tg-spoiler") || tag.equalsIgnoreCase("spoiler")) {
                    stack.add(new Open("spoiler", "", sb.length()));
                } else {
                    String name = tag.split("[\\s/>]", 2)[0].toLowerCase(java.util.Locale.ROOT);
                    String extra = "";
                    if (name.equals("a")) {
                        int h = tag.toLowerCase(java.util.Locale.ROOT).indexOf("href=");
                        if (h >= 0) {
                            char q = h + 5 < tag.length() ? tag.charAt(h + 5) : '"';
                            int from = h + 6;
                            int to = tag.indexOf(q, from);
                            if (to > from) extra = tag.substring(from, to);
                        }
                    } else if (name.equals("blockquote")
                            && tag.toLowerCase(java.util.Locale.ROOT).contains("expandable")) {
                        extra = "expandable";
                    }
                    stack.add(new Open(name, extra, sb.length()));
                }
            } else if (c == '&') {
                int semi = html.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = html.substring(i + 1, semi);
                    String rep = switch (ent) {
                        case "amp" -> "&";
                        case "lt" -> "<";
                        case "gt" -> ">";
                        case "quot" -> "\"";
                        case "apos", "#39" -> "'";
                        case "nbsp" -> "\u00a0";
                        default -> null;
                    };
                    if (rep != null) {
                        sb.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
                sb.append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        // close anything left open at end of input
        for (int k = stack.size() - 1; k >= 0; k--) {
            Open o = stack.get(k);
            addEntity(entities, o.tag, o.extra, o.start, sb.length());
        }
        entities.sort((a, b) -> {
            TlNode x = (TlNode) a;
            TlNode y = (TlNode) b;
            int cmp = Integer.compare(x.i("offset"), y.i("offset"));
            return cmp != 0 ? cmp : Integer.compare(y.i("length"), x.i("length"));
        });
        return new Result(sb.toString(), entities);
    }

    private static void addEntity(List<Object> out, String tag, String extra, int start, int end) {
        if (end <= start) return;
        int length = end - start;
        TlNode e = switch (tag) {
            case "b", "strong" -> new TlNode("messageEntityBold");
            case "i", "em" -> new TlNode("messageEntityItalic");
            case "u" -> new TlNode("messageEntityUnderline");
            case "s", "strike", "del" -> new TlNode("messageEntityStrike");
            case "code" -> new TlNode("messageEntityCode");
            case "pre" -> new TlNode("messageEntityPre").put("language", extra == null ? "" : extra);
            case "spoiler" -> new TlNode("messageEntitySpoiler");
            case "blockquote" -> {
                TlNode bq = new TlNode("messageEntityBlockquote");
                if ("expandable".equals(extra)) bq.put("collapsed", true);
                yield bq;
            }
            case "a" -> new TlNode("messageEntityTextUrl").put("url", extra == null ? "" : extra);
            default -> null;
        };
        if (e == null) return;
        e.put("offset", start).put("length", length);
        out.add(e);
    }

    private HtmlText() {
    }
}
