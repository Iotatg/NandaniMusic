package music.nandani.mtproto;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the canonical Telegram TL schema (shipped in resources/tl) so the client
 * can serialize/deserialize every constructor at the layer the server expects.
 */
public final class TlSchema {

    public static final class Field {
        public final String name;
        public final String type;
        public final int flagWord;   // 0 = flags, 1 = flags2
        public final int flagBit;    // -1 when unconditional
        public final boolean isFlags;

        Field(String name, String type, int flagWord, int flagBit, boolean isFlags) {
            this.name = name;
            this.type = type;
            this.flagWord = flagWord;
            this.flagBit = flagBit;
            this.isFlags = isFlags;
        }
    }

    public static final class Ctor {
        public final String name;
        public final int id;
        public final List<Field> fields = new ArrayList<>();

        Ctor(String name, int id) {
            this.name = name;
            this.id = id;
        }
    }

    private static final Map<Integer, Ctor> BY_ID = new HashMap<>();
    private static final Map<String, Ctor> BY_NAME = new HashMap<>();
    private static volatile boolean loaded;

    public static synchronized void load() {
        if (loaded) return;
        parse("tl/mtproto_api.tl");
        parse("tl/telegram_api.tl");
        parse("tl/custom.tl");
        loaded = true;
    }

    public static Ctor byId(int id) {
        load();
        return BY_ID.get(id);
    }

    public static Ctor byName(String name) {
        load();
        return BY_NAME.get(name);
    }

    public static int size() {
        load();
        return BY_ID.size();
    }

    private static void parse(String resource) {
        try (InputStream in = TlSchema.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("[tl] missing schema " + resource);
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("//")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String left = line.substring(0, eq).trim();
                String result = line.substring(eq + 1).trim();
                if (result.startsWith("Vector") || result.startsWith("vector")) continue;
                if (left.startsWith("vector")) continue;
                int hash = left.indexOf('#');
                if (hash < 0) continue; // bare type alias such as "int ? = Int;"
                String name = left.substring(0, hash).trim();
                int sp = left.indexOf(' ', hash);
                String idHex = (sp < 0 ? left.substring(hash + 1) : left.substring(hash + 1, sp)).trim();
                int id;
                try {
                    id = (int) Long.parseLong(idHex, 16);
                } catch (NumberFormatException e) {
                    continue;
                }
                Ctor c = new Ctor(name, id);
                if (sp > 0) {
                    String rest = left.substring(sp + 1).trim();
                    // drop generic parameter blocks: {X:Type}
                    while (rest.contains("{")) {
                        int a = rest.indexOf('{');
                        int z = rest.indexOf('}', a);
                        if (z < 0) break;
                        rest = (rest.substring(0, a) + " " + rest.substring(z + 1)).trim();
                    }
                    for (String tok : rest.split("\\s+")) {
                        if (tok.isEmpty() || tok.equals("#") || tok.equals("[") || tok.equals("]")) continue;
                        int colon = tok.indexOf(':');
                        if (colon < 0) continue;
                        String fname = tok.substring(0, colon);
                        String ftype = tok.substring(colon + 1);
                        if (ftype.equals("#")) {
                            int word = fname.equals("flags2") ? 1 : 0;
                            c.fields.add(new Field(fname, "#", word, -1, true));
                            continue;
                        }
                        int fw = 0;
                        int bit = -1;
                        String q = null;
                        if (ftype.startsWith("flags.")) {
                            fw = 0;
                            ftype = ftype.substring(6);
                            q = ftype;
                        } else if (ftype.startsWith("flags2.")) {
                            fw = 1;
                            ftype = ftype.substring(7);
                            q = ftype;
                        }
                        if (q != null) {
                            int mark = ftype.indexOf('?');
                            if (mark > 0) {
                                try {
                                    bit = Integer.parseInt(ftype.substring(0, mark));
                                } catch (NumberFormatException ignored) {
                                    bit = -1;
                                }
                                ftype = ftype.substring(mark + 1);
                            }
                        }
                        c.fields.add(new Field(fname, ftype, fw, bit, false));
                    }
                }
                BY_ID.put(id, c);
                BY_NAME.put(name, c);
            }
        } catch (Exception e) {
            System.err.println("[tl] schema parse failed for " + resource + ": " + e);
        }
    }

    private TlSchema() {
    }
}
