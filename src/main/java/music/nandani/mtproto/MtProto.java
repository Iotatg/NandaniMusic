package music.nandani.mtproto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Hand-rolled MTProto 2.0 client (TCP intermediate transport, DH handshake,
 * encrypted RPC, update stream).
 */
public final class MtProto implements AutoCloseable {

    public static final int LAYER = 230;

    /** Production data-centre endpoints. */
    public static final String[][] DC = {
            {"149.154.175.50", "443"},   // dc1
            {"149.154.167.51", "443"},   // dc2
            {"149.154.175.100", "443"},  // dc3
            {"149.154.167.91", "443"},   // dc4
            {"91.108.56.130", "443"},    // dc5
    };

    private static final SecureRandom RND = new SecureRandom();

    private final int apiId;
    private final String apiHash;
    private String host;
    private int port;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();

    private byte[] authKey;
    private long authKeyId;
    private long salt;
    private long sessionId;
    private long lastMsgId;
    private int seqNo;
    private long timeOffsetMs;
    private volatile boolean inited;
    private volatile boolean bodyLogged;

    private final Map<Long, CompletableFuture<TlNode>> pending = new ConcurrentHashMap<>();
    private final Map<Long, byte[]> pendingBodies = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong pingId = new AtomicLong();

    private volatile Consumer<TlNode> updateConsumer = n -> { };
    private volatile Runnable onDisconnect = () -> { };
    private Thread reader;

    public MtProto(int apiId, String apiHash, int dc) {
        this.apiId = apiId;
        this.apiHash = apiHash;
        String[] ep = DC[Math.max(0, Math.min(DC.length - 1, dc - 1))];
        this.host = ep[0];
        this.port = Integer.parseInt(ep[1]);
    }

    public void onUpdate(Consumer<TlNode> consumer) {
        this.updateConsumer = consumer;
    }

    public void onDisconnect(Runnable r) {
        this.onDisconnect = r;
    }

    public boolean connected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public long salt() {
        return salt;
    }

    // ── connection ────────────────────────────────────────────────────────────

    public synchronized void connect() throws IOException {
        closeSocket();
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(0);
        socket.connect(new InetSocketAddress(host, port), 15000);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
        // intermediate transport marker
        out.write(new byte[]{(byte) 0xee, (byte) 0xee, (byte) 0xee, (byte) 0xee});
        out.flush();
        running.set(true);
        inited = false;
    }

    /** Performs the DH handshake and starts the reader thread. */
    public void handshake() throws Exception {
        TlNode res = sendUnencrypted(TlCodec.write(new TlNode("req_pq_multi").put("nonce", random(16))));
        if (!"resPQ".equals(res.name)) throw new IOException("handshake: expected resPQ, got " + res.name);

        byte[] nonce = res.bytes("nonce");
        byte[] serverNonce = res.bytes("server_nonce");
        byte[] pqBytes = res.bytes("pq");

        BigInteger pq = new BigInteger(1, pqBytes);
        BigInteger[] factors = factor(pq);
        BigInteger p = factors[0];
        BigInteger q = factors[1];
        if (p.compareTo(q) > 0) {
            BigInteger t = p;
            p = q;
            q = t;
        }

        byte[] newNonce = random(32);
        TlNode inner = new TlNode("p_q_inner_data")
                .put("pq", toBytes(pq))
                .put("p", toBytes(p))
                .put("q", toBytes(q))
                .put("nonce", nonce)
                .put("server_nonce", serverNonce)
                .put("new_nonce", newNonce);

        byte[] innerBytes = TlCodec.write(inner);
        if (System.getenv("NANDANI_TL_DEBUG") != null) {
            System.out.println("[tl] inner=" + java.util.HexFormat.of().formatHex(innerBytes));
        }
        byte[] encrypted = RsaKey.pad(innerBytes);

        TlNode dh = sendUnencrypted(TlCodec.write(new TlNode("req_DH_params")
                .put("nonce", nonce)
                .put("server_nonce", serverNonce)
                .put("p", toBytes(p))
                .put("q", toBytes(q))
                .put("public_key_fingerprint", RsaKey.FINGERPRINT)
                .put("encrypted_data", encrypted)));
        if (!"server_DH_params_ok".equals(dh.name)) {
            throw new IOException("handshake: DH params rejected (" + dh.name + ")");
        }

        byte[] encAnswer = dh.bytes("encrypted_answer");
        if (System.getenv("NANDANI_TL_DEBUG") != null) {
            System.out.println("[tl] encAnswer len=" + encAnswer.length
                    + " sn=" + java.util.HexFormat.of().formatHex(serverNonce)
                    + " nn=" + java.util.HexFormat.of().formatHex(newNonce)
                    + " enc=" + java.util.HexFormat.of().formatHex(encAnswer));
        }
        if (System.getenv("NANDANI_TL_DEBUG") != null) {
            System.out.println("[tl] key=" + java.util.HexFormat.of().formatHex(dhKey(newNonce, serverNonce))
                    + " iv=" + java.util.HexFormat.of().formatHex(dhIv(newNonce, serverNonce)));
        }
        byte[] answer = aesDecryptAnswer(newNonce, serverNonce, encAnswer);
        if (System.getenv("NANDANI_TL_DEBUG") != null) {
            System.out.println("[tl] dec=" + java.util.HexFormat.of().formatHex(answer));
        }
        // answer = sha1(inner) + inner + random padding; the hash only covers
        // the object itself, so parse the object instead of hashing the tail.
        TlReader ar = new TlReader(answer);
        ar.raw(20);
        TlNode dhInner = TlCodec.read(ar);
        BigInteger g = BigInteger.valueOf(dhInner.i("g"));
        BigInteger dhPrime = new BigInteger(1, dhInner.bytes("dh_prime"));
        BigInteger gA = new BigInteger(1, dhInner.bytes("g_a"));
        long serverTime = dhInner.l("server_time") * 1000L;
        timeOffsetMs = serverTime - System.currentTimeMillis();

        BigInteger b = new BigInteger(1, random(256));
        BigInteger gB = g.modPow(b, dhPrime);
        BigInteger key = gA.modPow(b, dhPrime);
        authKey = toFixed(key, 256);
        authKeyId = readLong(sha1(authKey), 12);
        if (System.getenv("NANDANI_TL_DEBUG") != null) {
            System.out.println("[tl] authKey=" + java.util.HexFormat.of().formatHex(authKey));
            System.out.println("[tl] authKeyId=" + java.util.HexFormat.of().formatHex(
                    java.util.Arrays.copyOfRange(sha1(authKey), 12, 20)));
        }

        TlNode clientInner = new TlNode("client_DH_inner_data")
                .put("nonce", nonce)
                .put("server_nonce", serverNonce)
                .put("retry_id", 0L)
                .put("g_b", toBytes(gB));
        byte[] cInner = TlCodec.write(clientInner);
        if (System.getenv("NANDANI_TL_DEBUG") != null) {
            System.out.println("[tl] cInner " + cInner.length + " " + java.util.HexFormat.of().formatHex(cInner));
        }
        byte[] payload = concat(sha1(cInner), cInner);
        int dhPad = (16 - (payload.length % 16)) % 16;
        if (dhPad > 0) {
            byte[] padded = new byte[payload.length + dhPad];
            System.arraycopy(payload, 0, padded, 0, payload.length);
            byte[] rnd = random(dhPad);
            System.arraycopy(rnd, 0, padded, payload.length, dhPad);
            payload = padded;
        }
        byte[] enc = new AesIge(dhKey(newNonce, serverNonce)).encrypt(dhIv(newNonce, serverNonce), payload);

        TlNode gen = sendUnencrypted(TlCodec.write(new TlNode("set_client_DH_params")
                .put("nonce", nonce)
                .put("server_nonce", serverNonce)
                .put("encrypted_data", enc)));
        if (!"dh_gen_ok".equals(gen.name)) {
            throw new IOException("handshake: DH generation failed (" + gen.name + ")");
        }
        // aux_hash = first 8 bytes of SHA1(auth_key), not the key itself.
        byte[] checkHash = sha1(concat(newNonce, concat(new byte[]{1}, slice(sha1(authKey), 0, 8))));
        byte[] expect = slice(checkHash, 4, 20);
        byte[] got = gen.bytes("new_nonce_hash1");
        if (System.getenv("NANDANI_TL_DEBUG") != null) {
            System.out.println("[tl] g=" + g + " primeBits=" + dhPrime.bitLength()
                    + " gA=" + gA.bitLength() + " gB=" + gB.bitLength() + " key=" + key.bitLength());
            System.out.println("[tl] p=" + dhPrime.toString(16));
            System.out.println("[tl] nn=" + java.util.HexFormat.of().formatHex(newNonce));
            System.out.println("[tl] b=" + b.toString(16) + " gB=" + gB.toString(16));
            System.out.println("[tl] gA=" + gA.toString(16) + " key=" + key.toString(16));
            System.out.println("[tl] expect=" + java.util.HexFormat.of().formatHex(expect)
                    + " got=" + java.util.HexFormat.of().formatHex(got));
        }
        if (!MessageDigest.isEqual(expect, got)) {
            throw new IOException("handshake: auth key mismatch (new_nonce_hash1)");
        }

        sessionId = readLong(random(8), 0);
        seqNo = 0;
        lastMsgId = 0;
        reader = new Thread(this::readLoop, "mtproto-reader");
        reader.setDaemon(true);
        reader.start();
        System.out.println("[mtproto] handshake ok — dc=" + host + " layer=" + LAYER);
    }

    private static byte[] dhKey(byte[] newNonce, byte[] serverNonce) {
        byte[] h1 = sha1(concat(newNonce, serverNonce));
        byte[] h2 = sha1(concat(serverNonce, newNonce));
        byte[] out = new byte[32];
        System.arraycopy(h1, 0, out, 0, 20);
        System.arraycopy(h2, 0, out, 20, 12);
        return out;
    }

    private static byte[] dhIv(byte[] newNonce, byte[] serverNonce) {
        byte[] h2 = sha1(concat(serverNonce, newNonce));
        byte[] h3 = sha1(concat(newNonce, newNonce));
        byte[] out = new byte[32];
        System.arraycopy(h2, 12, out, 0, 8);
        System.arraycopy(h3, 0, out, 8, 20);
        System.arraycopy(newNonce, 0, out, 28, 4);
        return out;
    }

    private byte[] aesDecryptAnswer(byte[] newNonce, byte[] serverNonce, byte[] encrypted) {
        return new AesIge(dhKey(newNonce, serverNonce)).decrypt(dhIv(newNonce, serverNonce), encrypted);
    }

    // ── RPC ───────────────────────────────────────────────────────────────────

    public TlNode invoke(TlNode query) throws IOException {
        return invoke(query, 30);
    }

    public TlNode invoke(TlNode query, int timeoutSeconds) throws IOException {
        if (!connected()) throw new IOException("mtproto: not connected");
        byte[] body = TlCodec.write(query);
        if (!inited) {
            TlNode init = new TlNode("initConnection")
                    .put("api_id", apiId)
                    .put("device_model", "NandaniMusic")
                    .put("system_version", "Linux")
                    .put("app_version", "1.0")
                    .put("system_lang_code", "en")
                    .put("lang_pack", "")
                    .put("lang_code", "en")
                    .put("query", query);
            body = TlCodec.write(new TlNode("invokeWithLayer").put("layer", LAYER).put("query", init));
            inited = true;
        }
        if (System.getenv("NANDANI_TL_DEBUG") != null && !bodyLogged) {
            bodyLogged = true;
            System.out.println("[tl] first body " + body.length + " " + java.util.HexFormat.of().formatHex(body));
        }
        CompletableFuture<TlNode> f = new CompletableFuture<>();
        long msgId = nextMsgId();
        pending.put(msgId, f);
        pendingBodies.put(msgId, body);
        sendEncrypted(body, true, msgId);
        if (System.getenv("NANDANI_TL_DEBUG") != null) System.out.println("[tl] sent msg_id=" + msgId + " len=" + body.length);
        try {
            return f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException(cause == null ? "rpc failed" : cause.getMessage(), cause);
        } catch (TimeoutException e) {
            pending.remove(msgId);
            throw new IOException("mtproto: rpc timeout (" + query.name + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } finally {
            pendingBodies.remove(msgId);
        }
    }

    private long sendEncrypted(byte[] body, boolean content) throws IOException {
        long msgId = nextMsgId();
        sendEncrypted(body, content, msgId);
        return msgId;
    }

    private void sendEncrypted(byte[] body, boolean content, long msgId) throws IOException {
        int seq;
        synchronized (this) {
            if (content) {
                seqNo++;
                seq = seqNo * 2 - 1;
            } else {
                seq = seqNo * 2;
            }
        }
        int headerLen = 8 + 8 + 8 + 4 + 4;
        int total = headerLen + body.length;
        int pad = 16 - (total % 16);
        if (pad < 12) pad += 16;

        TlWriter w = new TlWriter();
        w.i64(salt);
        w.i64(sessionId);
        w.i64(msgId);
        w.i32(seq);
        w.i32(body.length);
        w.raw(body);
        w.raw(random(pad));
        byte[] plain = w.toByteArray();

        byte[] msgKey = slice(sha256(concat(slice(authKey, 88, 120), plain)), 8, 24);
        byte[][] ki = calcKey(authKey, msgKey, true);
        byte[] key = ki[0];
        byte[] iv = ki[1];
        byte[] cipher = new AesIge(key).encrypt(iv, plain);

        TlWriter pkt = new TlWriter();
        pkt.i64(authKeyId);
        pkt.raw(msgKey);
        pkt.raw(cipher);
        if (System.getenv("NANDANI_TL_DEBUG") != null) {
            System.out.println("[tl] send msg_id=" + msgId + " seq=" + seq + " keyId=" + Long.toHexString(authKeyId)
                    + " salt=" + salt + " plain=" + plain.length);
            System.out.println("[tl] PLAIN=" + java.util.HexFormat.of().formatHex(plain));
            System.out.println("[tl] MSGKEY=" + java.util.HexFormat.of().formatHex(msgKey));
            System.out.println("[tl] KEY=" + java.util.HexFormat.of().formatHex(key));
            System.out.println("[tl] IV=" + java.util.HexFormat.of().formatHex(iv));
            System.out.println("[tl] CIPHER=" + java.util.HexFormat.of().formatHex(cipher));
        }
        writePacket(pkt.toByteArray());
    }

    private TlNode sendUnencrypted(byte[] body) throws IOException {
        long msgId = nextMsgId();
        TlWriter w = new TlWriter();
        w.i64(0);
        w.i64(msgId);
        w.i32(body.length);
        w.raw(body);
        writePacket(w.toByteArray());

        while (true) {
            byte[] pkt = readPacket();
            if (System.getenv("NANDANI_TL_DEBUG") != null) {
                System.out.println("[tl] pkt " + pkt.length + " " + java.util.HexFormat.of().formatHex(pkt));
            }
            if (pkt.length < 20) {
                throw new IOException("handshake: transport error " + (int) readLong4(pkt, 0));
            }
            long keyId = readLong(pkt, 0);
            if (keyId != 0) throw new IOException("handshake: unexpected encrypted reply");
            int len = (int) readLong4(pkt, 16);
            byte[] payload = new byte[len];
            System.arraycopy(pkt, 20, payload, 0, len);
            TlNode n = TlCodec.read(payload);
            if ("msg_container".equals(n.name)) {
                List<Object> msgs = n.list("messages");
                for (Object o : msgs) {
                    TlNode m = (TlNode) o;
                    TlNode inner = TlCodec.read(m.bytes("body"));
                    if (!"pong".equals(inner.name)) return inner;
                }
                continue;
            }
            if ("pong".equals(n.name)) continue;
            return n;
        }
    }

    private void writePacket(byte[] payload) throws IOException {
        synchronized (writeLock) {
            byte[] hdr = new byte[4];
            int n = payload.length;
            hdr[0] = (byte) n;
            hdr[1] = (byte) (n >>> 8);
            hdr[2] = (byte) (n >>> 16);
            hdr[3] = (byte) (n >>> 24);
            out.write(hdr);
            out.write(payload);
            out.flush();
        }
    }

    private byte[] readPacket() throws IOException {
        byte[] hdr = new byte[4];
        in.readFully(hdr);
        int n = (hdr[0] & 0xff) | ((hdr[1] & 0xff) << 8) | ((hdr[2] & 0xff) << 16) | ((hdr[3] & 0xff) << 24);
        if (n <= 0 || n > (1 << 24)) throw new IOException("mtproto: bad packet length " + n);
        byte[] pkt = new byte[n];
        in.readFully(pkt);
        return pkt;
    }

    // ── reader ────────────────────────────────────────────────────────────────

    private void readLoop() {
        List<Long> acks = new ArrayList<>();
        while (running.get()) {
            try {
                byte[] pkt = readPacket();
                if (System.getenv("NANDANI_TL_DEBUG") != null) {
                    System.out.println("[tl] recv pkt " + pkt.length);
                }
                if (pkt.length == 4) {
                    int err = (int) readLong4(pkt, 0);
                    System.err.println("[mtproto] transport error " + err + " (" + transportErrorName(err) + ")");
                    if (err == -404 || err == -444) {
                        running.set(false);
                        onDisconnect.run();
                        return;
                    }
                    continue;
                }
                if (pkt.length < 24) continue;
                long keyId = readLong(pkt, 0);
                if (keyId == 0) continue;
                if (keyId != authKeyId) {
                    System.err.println("[mtproto] packet for foreign auth key");
                    continue;
                }
                byte[] msgKey = new byte[16];
                System.arraycopy(pkt, 8, msgKey, 0, 16);
                byte[] cipher = new byte[pkt.length - 24];
                System.arraycopy(pkt, 24, cipher, 0, cipher.length);
                byte[] key = calcKey(authKey, msgKey, false)[0];
                byte[] iv = calcKey(authKey, msgKey, false)[1];
                byte[] plain = new AesIge(key).decrypt(iv, cipher);
                byte[] check = slice(sha256(concat(slice(authKey, 96, 128), plain)), 8, 24);
                if (!MessageDigest.isEqual(check, msgKey)) {
                    System.err.println("[mtproto] bad msg_key, dropping packet");
                    continue;
                }
                long msgId = readLong(plain, 16);
                int seq = (int) readLong4(plain, 24);
                int len = (int) readLong4(plain, 28);
                if (len < 0 || 32 + len > plain.length) continue;
                byte[] body = new byte[len];
                System.arraycopy(plain, 32, body, 0, len);
                if ((seq & 1) == 1) acks.add(msgId);
                handle(body);
                if (!acks.isEmpty()) {
                    List<Object> ids = new ArrayList<>(acks);
                    acks.clear();
                    try {
                        byte[] ack = TlCodec.write(new TlNode("msgs_ack").put("msg_ids", ids));
                        sendEncrypted(ack, false);
                    } catch (IOException ignored) {
                    }
                }
            } catch (EOFException | java.net.SocketException e) {
                if (running.get()) {
                    System.err.println("[mtproto] connection closed: " + e.getMessage());
                    running.set(false);
                    onDisconnect.run();
                }
                return;
            } catch (Exception e) {
                if (running.get()) System.err.println("[mtproto] read error: " + e);
            }
        }
    }

    private void handle(byte[] body) {
        TlNode n;
        try {
            n = TlCodec.read(body);
        } catch (Exception e) {
            System.err.println("[mtproto] cannot parse update: " + e.getMessage());
            if (System.getenv("NANDANI_TL_DEBUG") != null) {
                System.err.println("[mtproto] body " + body.length + " " + java.util.HexFormat.of().formatHex(body));
            }
            return;
        }
        switch (n.name) {
            case "msg_container" -> {
                for (Object o : n.list("messages")) {
                    TlNode m = (TlNode) o;
                    handle(m.bytes("body"));
                }
            }
            case "gzip_packed" -> {
                byte[] inner = gunzipBytes(n.bytes("packed_data"));
                if (inner != null) handle(inner);
            }
            case "rpc_result" -> {
                long reqId = n.l("req_msg_id");
                CompletableFuture<TlNode> f = pending.remove(reqId);
                TlNode res = n.node("result");
                if (res != null && "gzip_packed".equals(res.name)) res = gunzip(res);
                if (System.getenv("NANDANI_TL_DEBUG") != null) {
                    System.out.println("[tl] rpc_result req=" + reqId
                            + (f == null ? " NO-PENDING" : " matched")
                            + " result=" + (res == null ? "null" : res.name)
                            + " pendingLeft=" + pending.size());
                }
                if (f == null) return;
                if (res != null && "rpc_error".equals(res.name)) {
                    f.completeExceptionally(new IOException("rpc " + res.i("error_code") + ": " + res.str("error_message")));
                } else {
                    f.complete(res);
                }
            }
            case "bad_server_salt" -> {
                long badId = n.l("bad_msg_id");
                salt = n.l("new_server_salt");
                System.out.println("[mtproto] bad_server_salt for " + badId + ", retrying");
                byte[] retry = pendingBodies.remove(badId);
                if (retry != null) {
                    try {
                        CompletableFuture<TlNode> f = pending.remove(badId);
                        long newId = nextMsgId();
                        if (f != null) {
                            pending.put(newId, f);
                            pendingBodies.put(newId, retry);
                        }
                        sendEncrypted(retry, true, newId);
                    } catch (IOException e) {
                        System.err.println("[mtproto] salt retry failed: " + e.getMessage());
                    }
                }
            }
            case "new_session_created" -> salt = n.l("server_salt");
            case "bad_msg_notification" -> System.err.println("[mtproto] bad_msg " + n.i("error_code")
                    + " for " + n.l("bad_msg_id"));
            case "msgs_ack", "pong", "msgs_state_info", "msgs_all_info" -> {
            }
            default -> {
                if (System.getenv("NANDANI_TL_DEBUG") != null) {
                    System.out.println("[upd] envelope " + n.name);
                }
                // Never run handlers on the reader thread: a handler that
                // invokes an RPC would block the very thread that must read
                // the rpc_result completing its own future (deadlock → 30s
                // timeouts on every message).
                dispatchPool.execute(() -> {
                    try {
                        updateConsumer.accept(n);
                    } catch (Throwable e) {
                        System.err.println("[mtproto] update handler failed: " + e);
                    }
                });
            }
        }
    }

    private final java.util.concurrent.ExecutorService dispatchPool =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "mtproto-dispatch");
                t.setDaemon(true);
                return t;
            });

    private TlNode gunzip(TlNode n) {
        byte[] inner = gunzipBytes(n.bytes("packed_data"));
        if (inner == null) return null;
        try {
            return TlCodec.read(inner);
        } catch (Exception e) {
            System.err.println("[mtproto] gzip parse: " + e.getMessage());
            return null;
        }
    }

    /**
     * Decompresses a gzip_packed payload and returns the RAW bytes. Callers
     * must parse these bytes exactly once — re-serialising a parsed node and
     * parsing it again corrupts objects whose constructor name is ambiguous
     * across the telegram/mtproto schemas (e.g. "message").
     */
    private byte[] gunzipBytes(byte[] packed) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            return gz.readAllBytes();
        } catch (IOException e) {
            System.err.println("[mtproto] gzip: " + e.getMessage());
            return null;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String transportErrorName(int code) {
        return switch (code) {
            case -1 -> "bad checksum / unknown";
            case -2 -> "msg_id too high or too low (clock skew)";
            case -3 -> "bad message length";
            case -4 -> "bad seq_no";
            case -404 -> "auth key not found";
            case -444 -> "invalid dc / auth key";
            default -> "unknown";
        };
    }

    public long nextMsgId() {
        long nowSec = (System.currentTimeMillis() + timeOffsetMs) / 1000L;
        long id = (nowSec << 32) | (System.nanoTime() & 0xfffffffcL);
        synchronized (this) {
            if (id <= lastMsgId) id = lastMsgId + 4;
            lastMsgId = id;
        }
        return id;
    }

    public void ping() {
        try {
            long id = pingId.incrementAndGet();
            sendEncrypted(TlCodec.write(new TlNode("ping_delay_disconnect")
                    .put("ping_id", id).put("disconnect_delay", 75)), false);
        } catch (Exception ignored) {
        }
    }

    private static BigInteger[] factor(BigInteger n) {
        if (n.mod(BigInteger.TWO).signum() == 0) return new BigInteger[]{BigInteger.TWO, n.divide(BigInteger.TWO)};
        BigInteger x = BigInteger.valueOf(2 + RND.nextInt(1000));
        BigInteger y = x;
        BigInteger c = BigInteger.valueOf(1 + RND.nextInt(1000));
        BigInteger d = BigInteger.ONE;
        while (d.equals(BigInteger.ONE)) {
            x = x.multiply(x).add(c).mod(n);
            y = y.multiply(y).add(c).mod(n);
            y = y.multiply(y).add(c).mod(n);
            d = x.subtract(y).abs().gcd(n);
        }
        if (d.equals(n)) return factor(n);
        return new BigInteger[]{d, n.divide(d)};
    }

    private static byte[] toBytes(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] out = new byte[b.length - 1];
            System.arraycopy(b, 1, out, 0, b.length - 1);
            return out;
        }
        return b;
    }

    private static byte[] toFixed(BigInteger v, int size) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[size];
        if (b.length >= size) {
            System.arraycopy(b, b.length - size, out, 0, size);
        } else {
            System.arraycopy(b, 0, out, size - b.length, b.length);
        }
        return out;
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    /**
     * MTProto 2.0 AES key/IV derivation (core.telegram.org/mtproto/description#defining-aes-key-and-initialization-vector).
     * x = 0 for messages we send, x = 8 for messages we receive.
     */
    static byte[][] calcKey(byte[] authKey, byte[] msgKey, boolean client) {
        int x = client ? 0 : 8;
        byte[] sha256a = sha256(concat(msgKey, slice(authKey, x, x + 36)));
        byte[] sha256b = sha256(concat(slice(authKey, x + 40, x + 76), msgKey));
        byte[] key = new byte[32];
        System.arraycopy(sha256a, 0, key, 0, 8);
        System.arraycopy(sha256b, 8, key, 8, 16);
        System.arraycopy(sha256a, 24, key, 24, 8);
        byte[] iv = new byte[32];
        System.arraycopy(sha256b, 0, iv, 0, 8);
        System.arraycopy(sha256a, 8, iv, 8, 16);
        System.arraycopy(sha256b, 24, iv, 24, 8);
        return new byte[][]{key, iv};
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] slice(byte[] a, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(a, from, out, 0, to - from);
        return out;
    }

    static byte[] sha1(byte[] d) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[] d) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static long readLong(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xffL);
        return v;
    }

    static long readLong4(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8) | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }

    @Override
    public void close() {
        running.set(false);
        closeSocket();
    }

    /** Fresh TCP connection + DH handshake (drops the previous auth key). */
    public synchronized void reconnect() throws Exception {
        close();
        connect();
        handshake();
    }

    public boolean isRunning() {
        return running.get() && connected();
    }

    /** Starts the reader thread on an already-connected socket (no handshake). */
    public void startReader() {
        sessionId = readLong(random(8), 0);
        seqNo = 0;
        lastMsgId = 0;
        inited = false;
        reader = new Thread(this::readLoop, "mtproto-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Serialised auth key + DC for session persistence. */
    public String exportSession() {
        if (authKey == null) return null;
        return "v1\n" + currentDc() + "\n" + java.util.HexFormat.of().formatHex(authKey) + "\n";
    }

    public boolean importSession(String s) {
        if (s == null || s.isBlank()) return false;
        String[] parts = s.trim().split("\\R");
        if (parts.length < 3 || !"v1".equals(parts[0].trim())) return false;
        int dc;
        byte[] key;
        try {
            dc = Integer.parseInt(parts[1].trim());
            key = java.util.HexFormat.of().parseHex(parts[2].trim());
        } catch (RuntimeException e) {
            return false;
        }
        if (key.length != 256 || dc < 1 || dc > DC.length) return false;
        String[] ep = DC[dc - 1];
        this.host = ep[0];
        this.port = Integer.parseInt(ep[1]);
        this.authKey = key;
        this.authKeyId = readLong(sha1(key), 12);
        this.salt = 0;
        this.seqNo = 0;
        this.lastMsgId = 0;
        this.inited = false;
        return true;
    }

    /**
     * Reconnects to another data centre after PHONE/USER/NETWORK_MIGRATE_X:
     * a fresh DH handshake is performed there (auth keys are per-DC).
     */
    public synchronized void migrateTo(int dc) throws Exception {
        running.set(false);
        closeSocket();
        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
        for (CompletableFuture<TlNode> f : pending.values()) {
            f.completeExceptionally(new IOException("mtproto: migrated to dc" + dc));
        }
        pending.clear();
        pendingBodies.clear();
        String[] ep = DC[Math.max(0, Math.min(DC.length - 1, dc - 1))];
        this.host = ep[0];
        this.port = Integer.parseInt(ep[1]);
        this.authKey = null;
        this.authKeyId = 0;
        this.salt = 0;
        this.seqNo = 0;
        this.lastMsgId = 0;
        this.inited = false;
        this.bodyLogged = false;
        connect();
        handshake();
        System.out.println("[mtproto] migrated to dc" + dc + " (" + host + ")");
    }

    public int currentDc() {
        for (int i = 0; i < DC.length; i++) {
            if (DC[i][0].equals(host)) return i + 1;
        }
        return 2;
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }
}
