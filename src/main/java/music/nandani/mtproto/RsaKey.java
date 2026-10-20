package music.nandani.mtproto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Telegram's RSA public key (2048 bit) plus the MTProto rsa_pad routine. */
public final class RsaKey {

    private static final String MODULUS_HEX =
            "aeec36c8ffc109cb099624685b97815415657bd76d8c9c3e398103d7ad16c9bba6f525ed0412d7ae2c2de2b44e77d72c"
          + "bf4b7438709a4e646a05c43427c7f184debf72947519680e651500890c6832796dd11f772c25ff8f576755afe055b0a3"
          + "752c696eb7d8da0d8be1faf38c9bdd97ce0a77d3916230c4032167100edd0f9e7a3a9b602d04367b689536af0d64b6"
          + "13ccba7962939d3b57682beb6dae5b608130b2e52aca78ba023cf6ce806b1dc49c72cf928a7199d22e3d7ac84e47bc9"
          + "427d0236945d10dbd15177bab413fbf0edfda09f014c7a7da088dde9759702ca760af2b8e4e97cc055c617bd74c3d9"
          + "7008635b98dc4d621b4891da9fb0473047927";

    public static final BigInteger MODULUS = new BigInteger(MODULUS_HEX, 16);
    public static final BigInteger EXPONENT = BigInteger.valueOf(65537);
    /** 0x0bc35f3509f7b7a5 — one of the fingerprints the production DCs advertise. */
    public static final long FINGERPRINT = 0x0bc35f3509f7b7a5L;

    private static final SecureRandom RND = new SecureRandom();

    /** @return 256-byte RSA block ready to be embedded in req_DH_params. */
    public static byte[] pad(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(data);
            // sha1(data) + data + random padding up to 255 bytes, then RSA.
            byte[] toEncrypt = new byte[255];
            System.arraycopy(hash, 0, toEncrypt, 0, 20);
            System.arraycopy(data, 0, toEncrypt, 20, data.length);
            int padLen = 235 - data.length;
            if (padLen < 0) throw new IllegalArgumentException("rsa_pad: payload too large");
            byte[] rnd = new byte[padLen];
            RND.nextBytes(rnd);
            System.arraycopy(rnd, 0, toEncrypt, 20 + data.length, padLen);

            BigInteger m = new BigInteger(1, toEncrypt);
            byte[] c = m.modPow(EXPONENT, MODULUS).toByteArray();
            byte[] out = new byte[256];
            if (c.length >= 256) {
                System.arraycopy(c, c.length - 256, out, 0, 256);
            } else {
                System.arraycopy(c, 0, out, 256 - c.length, c.length);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("rsa_pad failed", e);
        }
    }

    private RsaKey() {
    }
}
