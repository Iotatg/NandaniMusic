package music.nandani.mtproto;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A decoded TL object: constructor name plus its fields. */
public final class TlNode {

    public final String name;
    private final Map<String, Object> fields = new LinkedHashMap<>();

    public TlNode(String name) {
        this.name = name;
    }

    public TlNode put(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    public boolean has(String key) {
        return fields.containsKey(key) && fields.get(key) != null;
    }

    public Object get(String key) {
        return fields.get(key);
    }

    public Map<String, Object> fields() {
        return fields;
    }

    public long l(String key) {
        Object v = fields.get(key);
        if (v instanceof Long x) return x;
        if (v instanceof Integer x) return x.longValue();
        return 0L;
    }

    public int i(String key) {
        Object v = fields.get(key);
        if (v instanceof Integer x) return x;
        if (v instanceof Long x) return x.intValue();
        return 0;
    }

    public boolean bool(String key) {
        Object v = fields.get(key);
        if (v instanceof TlNode n) return n.name.equals("boolTrue") || n.name.equals("true");
        return Boolean.TRUE.equals(v);
    }

    public byte[] bytes(String key) {
        Object v = fields.get(key);
        if (v instanceof byte[] b) return b;
        if (v instanceof String s) return s.getBytes(StandardCharsets.UTF_8);
        return new byte[0];
    }

    public String str(String key) {
        Object v = fields.get(key);
        if (v instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        if (v instanceof String s) return s;
        return "";
    }

    public TlNode node(String key) {
        Object v = fields.get(key);
        return v instanceof TlNode n ? n : null;
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String key) {
        Object v = fields.get(key);
        if (v instanceof List<?> l) return (List<Object>) l;
        return new ArrayList<>();
    }

    @Override
    public String toString() {
        return name + fields;
    }
}
