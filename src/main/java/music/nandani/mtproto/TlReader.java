package music.nandani.mtproto;

import java.nio.charset.StandardCharsets;

/** Little-endian TL reader. "string" is kept as raw bytes; use TlNode.str() for text. */
public final class TlReader {

    private final byte[] b;
    private int p;

    public TlReader(byte[] b) {
        this.b = b;
    }

    public TlReader(byte[] b, int off) {
        this.b = b;
        this.p = off;
    }

    public int pos() {
        return p;
    }

    public int remaining() {
        return b.length - p;
    }

    public byte[] raw(int n) {
        if (p + n > b.length) throw new IllegalStateException("tl: short read " + n + " at " + p + "/" + b.length);
        byte[] out = new byte[n];
        System.arraycopy(b, p, out, 0, n);
        p += n;
        return out;
    }

    public int i32() {
        if (p + 4 > b.length) throw new IllegalStateException("tl: short int at " + p + "/" + b.length);
        int v = (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8) | ((b[p + 2] & 0xff) << 16) | ((b[p + 3] & 0xff) << 24);
        p += 4;
        return v;
    }

    public long i64() {
        long lo = i32() & 0xffffffffL;
        long hi = i32() & 0xffffffffL;
        return lo | (hi << 32);
    }

    public byte[] bytes() {
        int first = raw(1)[0] & 0xff;
        int n;
        int pad;
        if (first == 254) {
            n = (raw(1)[0] & 0xff) | ((raw(1)[0] & 0xff) << 8) | ((raw(1)[0] & 0xff) << 16);
            pad = (4 - (n & 3)) & 3;
        } else {
            n = first;
            pad = (4 - ((n + 1) & 3)) & 3;
        }
        if (n < 0 || p + n > b.length) throw new IllegalStateException("tl: bad length " + n + " at " + p);
        byte[] out = raw(n);
        if (pad > 0) raw(pad);
        return out;
    }

    public String utf8() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }
}
