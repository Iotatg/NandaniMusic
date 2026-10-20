package music.nandani.mtproto;

import java.nio.charset.StandardCharsets;

/** Minimal little-endian TL serializer. */
public final class TlWriter {

    private byte[] buf = new byte[1024];
    private int len;

    private void ensure(int n) {
        if (len + n <= buf.length) return;
        int cap = buf.length;
        while (cap < len + n) cap <<= 1;
        byte[] nb = new byte[cap];
        System.arraycopy(buf, 0, nb, 0, len);
        buf = nb;
    }

    public TlWriter i32(int v) {
        ensure(4);
        buf[len++] = (byte) v;
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) (v >>> 16);
        buf[len++] = (byte) (v >>> 24);
        return this;
    }

    public TlWriter i64(long v) {
        ensure(8);
        for (int i = 0; i < 8; i++) buf[len++] = (byte) (v >>> (8 * i));
        return this;
    }

    public TlWriter raw(byte[] b) {
        if (b == null) return this;
        ensure(b.length);
        System.arraycopy(b, 0, buf, len, b.length);
        len += b.length;
        return this;
    }

    /**
     * TL "string" / "bytes" as Telegram encodes them: a one-byte length, or
     * 0xFE followed by a three-byte length for payloads >= 254 bytes, then the
     * payload padded to a four-byte boundary.
     */
    public TlWriter bytes(byte[] b) {
        if (b == null) b = new byte[0];
        int pad;
        if (b.length < 254) {
            ensure(1);
            buf[len++] = (byte) b.length;
            pad = (4 - ((b.length + 1) & 3)) & 3;
        } else {
            ensure(4);
            buf[len++] = (byte) 0xFE;
            buf[len++] = (byte) b.length;
            buf[len++] = (byte) (b.length >>> 8);
            buf[len++] = (byte) (b.length >>> 16);
            pad = (4 - (b.length & 3)) & 3;
        }
        raw(b);
        for (int i = 0; i < pad; i++) {
            ensure(1);
            buf[len++] = 0;
        }
        return this;
    }

    public TlWriter string(String s) {
        return bytes(s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
    }

    public int size() {
        return len;
    }

    public byte[] toByteArray() {
        byte[] out = new byte[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }
}
