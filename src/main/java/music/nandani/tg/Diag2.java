package music.nandani.tg;

import music.nandani.mtproto.TlNode;

/**
 * Sends one test message to the most recent private dialog so the running bot
 * instance receives its own outgoing message back as an updateNewMessage
 * (out=true) — a complete self-test of the update receive path.
 */
public final class Diag2 {

    public static void main(String[] args) throws Exception {
        int apiId = Integer.parseInt(System.getenv().getOrDefault("API_ID", "24168862"));
        String apiHash = System.getenv().getOrDefault("API_HASH", "");
        String token = System.getenv().getOrDefault("BOT_TOKEN", "");

        MtBot bot = new MtBot(apiId, apiHash, token, 2);
        if (!bot.start(60_000)) {
            System.err.println("[diag2] login failed");
            System.exit(1);
        }

        TlNode res = bot.mtproto().invoke(new TlNode("messages.getDialogs")
                .put("offset_date", 0)
                .put("offset_id", 0)
                .put("offset_peer", new TlNode("inputPeerEmpty"))
                .put("limit", 10)
                .put("hash", 0L), 20);
        for (Object o : res.list("dialogs")) {
            TlNode d = (TlNode) o;
            TlNode peer = d.node("peer");
            if (peer == null || !"peerUser".equals(peer.name)) continue;
            long userId = peer.l("user_id");
            System.out.println("[diag2] first private dialog user=" + userId);
            bot.sendText(userId, "🛠 ᴛєsᴛ ᴘɪηɢ — ɪɢησʀє ᴛʜɪs ᴍєssᴧɢє", null);
            System.out.println("[diag2] sent");
            break;
        }
        bot.close();
        System.exit(0);
    }
}
