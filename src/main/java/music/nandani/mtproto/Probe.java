package music.nandani.mtproto;

/**
 * Tiny diagnostic entry point: handshake + one plain RPC.
 * Usage: java -cp nandani-music.jar music.nandani.mtproto.Probe [method]
 */
public final class Probe {

    public static void main(String[] args) throws Exception {
        int apiId = Integer.parseInt(System.getenv().getOrDefault("API_ID", "24168862"));
        String apiHash = System.getenv().getOrDefault("API_HASH", "");
        String method = args.length > 0 ? args[0] : "help.getConfig";

        MtProto mt = new MtProto(apiId, apiHash, 2);
        mt.onUpdate(n -> System.out.println("[update] " + n.name));
        mt.connect();
        mt.handshake();
        System.out.println("[probe] calling " + method);
        TlNode res = mt.invoke(new TlNode(method), 20);
        System.out.println("[probe] result " + (res == null ? "null" : res.name));
        if (res != null) System.out.println("[probe] fields " + res.fields().keySet());
        mt.close();
        System.exit(0);
    }
}
