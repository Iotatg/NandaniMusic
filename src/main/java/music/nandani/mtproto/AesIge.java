package music.nandani.mtproto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** AES-256 IGE mode as used by MTProto (not provided by the JDK). */
public final class AesIge {

    private final Cipher enc;
    private final Cipher dec;

    public AesIge(byte[] key) {
        try {
            SecretKeySpec ks = new SecretKeySpec(key, "AES");
            enc = Cipher.getInstance("AES/ECB/NoPadding");
            enc.init(Cipher.ENCRYPT_MODE, ks);
            dec = Cipher.getInstance("AES/ECB/NoPadding");
            dec.init(Cipher.DECRYPT_MODE, ks);
        } catch (Exception e) {
            throw new IllegalStateException("aes init failed", e);
        }
    }

    public byte[] encrypt(byte[] iv, byte[] data) {
        if (data.length % 16 != 0) throw new IllegalArgumentException("ige: length not multiple of 16");
        byte[] x = new byte[16];
        byte[] y = new byte[16];
        System.arraycopy(iv, 0, x, 0, 16);
        System.arraycopy(iv, 16, y, 0, 16);
        byte[] out = new byte[data.length];
        byte[] blk = new byte[16];
        byte[] tmp = new byte[16];
        for (int off = 0; off < data.length; off += 16) {
            System.arraycopy(data, off, blk, 0, 16);
            for (int i = 0; i < 16; i++) tmp[i] = (byte) (blk[i] ^ x[i]);
            tmp = enc.update(tmp, 0, 16);
            for (int i = 0; i < 16; i++) out[off + i] = (byte) (tmp[i] ^ y[i]);
            System.arraycopy(out, off, x, 0, 16);
            System.arraycopy(blk, 0, y, 0, 16);
        }
        return out;
    }

    public byte[] decrypt(byte[] iv, byte[] data) {
        if (data.length % 16 != 0) throw new IllegalArgumentException("ige: length not multiple of 16");
        byte[] x = new byte[16];
        byte[] y = new byte[16];
        System.arraycopy(iv, 0, x, 0, 16);
        System.arraycopy(iv, 16, y, 0, 16);
        byte[] out = new byte[data.length];
        byte[] blk = new byte[16];
        byte[] tmp = new byte[16];
        for (int off = 0; off < data.length; off += 16) {
            System.arraycopy(data, off, blk, 0, 16);
            for (int i = 0; i < 16; i++) tmp[i] = (byte) (blk[i] ^ y[i]);
            tmp = dec.update(tmp, 0, 16);
            for (int i = 0; i < 16; i++) out[off + i] = (byte) (tmp[i] ^ x[i]);
            System.arraycopy(blk, 0, x, 0, 16);
            System.arraycopy(out, off, y, 0, 16);
        }
        return out;
    }
}
