package music.nandani.tg;

import music.nandani.mtproto.TlNode;

/**
 * End-to-end smoke test for the MTProto send path. Bots cannot create channels,
 * so this checks that a fully encoded message (entities + coloured inline
 * keyboard) is accepted by the server: a semantic error such as
 * PEER_ID_INVALID proves the request itself serialised correctly.
 *
 * <p>Run: {@code java -cp nandani-music.jar music.nandani.tg.SelfTest}
 */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        int apiId = Integer.parseInt(System.getenv().getOrDefault("API_ID", "24168862"));
        String apiHash = System.getenv().getOrDefault("API_HASH", "");
        String token = System.getenv().getOrDefault("BOT_TOKEN", "");

        MtBot bot = new MtBot(apiId, apiHash, token, 2);
        if (!bot.start(60_000)) {
            System.err.println("[selftest] login failed");
            System.exit(1);
        }

        TlNode me = bot.mtproto().invoke(new TlNode("users.getFullUser")
                .put("id", new TlNode("inputUserSelf")), 20);
        System.out.println("[selftest] getFullUser -> " + me.name);

        String html = "<b>Nandani</b> <i>Music</i>\n<blockquote>ᴘɪηɢɪηɢ…</blockquote>\n"
                + "<a href=\"https://t.me/Nandani_musicbot\">ᴡʜσσᴘs!</a> <code>ok</code>";
        Markup m = new Markup();
        m.row(Markup.Button.data("▶️ ᴘʟᴀʏ", "play:1").style(3),
                Markup.Button.data("⏸ ᴘᴀᴜsє", "pause:1").style(1),
                Markup.Button.data("⏭ sᴋɪᴘ", "skip:1").style(2));

        TlNode peer = new TlNode("inputPeerUser").put("user_id", 1L).put("access_hash", 0L);
        try {
            bot.mtproto().invoke(new TlNode("messages.sendMessage")
                    .put("flags", 0)
                    .put("peer", peer)
                    .put("message", html)
                    .put("random_id", System.nanoTime())
                    .put("reply_markup", bot.buildMarkup(m)), 20);
            System.out.println("[selftest] sendMessage unexpectedly succeeded");
        } catch (Exception e) {
            System.out.println("[selftest] sendMessage -> " + e.getMessage());
        }

        bot.close();
        System.exit(0);
    }
}
