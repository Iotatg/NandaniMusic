package music.nandani.tg;

import music.nandani.mtproto.TlNode;

/**
 * Diagnostic: pull updates via getDifference to see whether messages sent to
 * the bot are queued server-side (proves delivery works even without push).
 *
 * <p>Run: {@code java -cp nandani-music.jar music.nandani.tg.Diag}
 */
public final class Diag {

    public static void main(String[] args) throws Exception {
        int apiId = Integer.parseInt(System.getenv().getOrDefault("API_ID", "24168862"));
        String apiHash = System.getenv().getOrDefault("API_HASH", "");
        String token = System.getenv().getOrDefault("BOT_TOKEN", "");

        MtBot bot = new MtBot(apiId, apiHash, token, 2);
        if (!bot.start(60_000)) {
            System.err.println("[diag] login failed");
            System.exit(1);
        }

        TlNode st = bot.mtproto().invoke(new TlNode("updates.getState"), 20);
        System.out.println("[diag] state " + st.name + " pts=" + st.l("pts")
                + " qts=" + st.l("qts") + " seq=" + st.l("seq") + " date=" + st.l("date"));

        TlNode diff = bot.mtproto().invoke(new TlNode("updates.getDifference")
                .put("flags", 0)
                .put("pts", st.l("pts"))
                .put("date", st.l("date"))
                .put("qts", st.l("qts")), 20);
        System.out.println("[diag] diff " + diff.name);
        if ("updates_difference".equals(diff.name) || "updates.difference".equals(diff.name)) {
            for (Object o : diff.list("new_messages")) {
                if (o instanceof TlNode m) {
                    System.out.println("[diag] queued msg id=" + m.i("id")
                            + " from=" + m.node("from_id") + " text=" + m.str("message"));
                }
            }
            for (Object o : diff.list("other_updates")) {
                if (o instanceof TlNode u) System.out.println("[diag] other " + u.name);
            }
        }
        bot.close();
        System.exit(0);
    }
}
