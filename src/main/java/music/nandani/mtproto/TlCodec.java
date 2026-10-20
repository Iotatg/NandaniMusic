package music.nandani.mtproto;

import java.util.ArrayList;
import java.util.List;

/** Schema-driven TL serializer / deserializer. */
public final class TlCodec {

    public static final int VECTOR_ID = 0x1cb5c415;

    public static TlNode read(byte[] data) {
        return read(new TlReader(data));
    }

    public static TlNode read(TlReader r) {
        int id = r.i32();
        TlSchema.Ctor c = TlSchema.byId(id);
        if (c == null) throw new IllegalStateException("tl: unknown constructor 0x" + Integer.toHexString(id));
        TlNode n = new TlNode(c.name);
        // msg_container is the one place Telegram omits the 0x1cb5c415 vector
        // prefix, and its elements are *bare* messages (no 0x5bb8e511 id).
        if ("msg_container".equals(c.name)) {
            int count = r.i32();
            if (System.getenv("NANDANI_TL_DEBUG") != null) {
                System.err.println("[tl] container count=" + count + " at " + r.pos() + " rem " + r.remaining());
            }
            List<Object> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                TlNode m = new TlNode("message");
                m.put("msg_id", r.i64());
                m.put("seqno", r.i32());
                int bytesField = r.i32();
                // Inside a container the body is "bare": the bytes:int field is
                // its length, with no TL length prefix and no padding.
                byte[] bodyBytes = r.raw(bytesField);
                if (System.getenv("NANDANI_TL_DEBUG") != null) {
                    System.err.println("[tl] msg[" + i + "] bytesField=" + bytesField
                            + " bodyLen=" + bodyBytes.length + " pos=" + r.pos()
                            + " body=" + java.util.HexFormat.of().formatHex(bodyBytes));
                }
                m.put("bytes", bytesField);
                m.put("body", bodyBytes);
                items.add(m);
            }
            n.put("messages", items);
            return n;
        }
        int flags = 0;
        int flags2 = 0;
        for (TlSchema.Field f : c.fields) {
            if (f.isFlags) {
                int v = r.i32();
                if (f.flagWord == 0) flags = v;
                else flags2 = v;
                n.put(f.name, v);
                continue;
            }
            if (f.flagBit >= 0) {
                int word = f.flagWord == 0 ? flags : flags2;
                if ((word & (1 << f.flagBit)) == 0) continue;
            }
            n.put(f.name, readValue(r, f.type));
        }
        return n;
    }

    private static Object readValue(TlReader r, String type) {
        switch (type) {
            case "int":
                return r.i32();
            case "long":
                return r.i64();
            case "int128":
                return r.raw(16);
            case "int256":
                return r.raw(32);
            case "double":
                return Double.longBitsToDouble(r.i64());
            case "string":
            case "bytes":
                return r.bytes();
            case "true":
                return Boolean.TRUE;
            case "Bool":
                return read(r);
            default:
                break;
        }
        if (type.startsWith("Vector<") && type.endsWith(">")) {
            String inner = type.substring(7, type.length() - 1).trim();
            int cid = r.i32();
            if (cid != VECTOR_ID) throw new IllegalStateException("tl: expected vector, got 0x" + Integer.toHexString(cid));
            int count = r.i32();
            List<Object> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) out.add(readValue(r, inner));
            return out;
        }
        if (type.startsWith("!")) return read(r);
        return read(r);
    }

    public static byte[] write(TlNode n) {
        TlWriter w = new TlWriter();
        write(w, n);
        return w.toByteArray();
    }

    public static void write(TlWriter w, TlNode n) {
        TlSchema.Ctor c = TlSchema.byName(n.name);
        if (c == null) throw new IllegalStateException("tl: unknown constructor name " + n.name);
        w.i32(c.id);
        int flags = 0;
        int flags2 = 0;
        for (TlSchema.Field f : c.fields) {
            if (f.flagBit >= 0 && n.has(f.name)) {
                if (f.flagWord == 0) flags |= 1 << f.flagBit;
                else flags2 |= 1 << f.flagBit;
            }
        }
        for (TlSchema.Field f : c.fields) {
            if (f.isFlags) {
                w.i32(f.flagWord == 0 ? flags : flags2);
                continue;
            }
            if (f.flagBit >= 0 && !n.has(f.name)) continue;
            writeValue(w, f.type, n.get(f.name));
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(TlWriter w, String type, Object v) {
        switch (type) {
            case "int":
                w.i32(v instanceof Number num ? num.intValue() : 0);
                return;
            case "long":
                w.i64(v instanceof Number num ? num.longValue() : 0L);
                return;
            case "int128":
            case "int256":
                w.raw((byte[]) v);
                return;
            case "double":
                w.i64(Double.doubleToLongBits(v instanceof Number num ? num.doubleValue() : 0d));
                return;
            case "string":
            case "bytes":
                if (v instanceof byte[] b) w.bytes(b);
                else w.string(v == null ? "" : v.toString());
                return;
            case "true":
                return;
            case "Bool":
                if (v instanceof TlNode n) write(w, n);
                else w.i32(Boolean.TRUE.equals(v) ? 0x997275b5 : 0xbc799737);
                return;
            default:
                break;
        }
        if (type.startsWith("Vector<") && type.endsWith(">")) {
            String inner = type.substring(7, type.length() - 1).trim();
            List<Object> items = v instanceof List<?> l ? (List<Object>) l : List.of();
            w.i32(VECTOR_ID);
            w.i32(items.size());
            for (Object it : items) writeValue(w, inner, it);
            return;
        }
        if (v instanceof TlNode n) {
            write(w, n);
            return;
        }
        throw new IllegalStateException("tl: cannot write " + type + " <- " + v);
    }

    private TlCodec() {
    }
}
