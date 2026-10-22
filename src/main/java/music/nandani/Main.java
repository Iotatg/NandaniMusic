package music.nandani;

import music.nandani.config.Config;
import music.nandani.db.Database;
import music.nandani.health.HealthServer;
import music.nandani.i18n.Locales;
import music.nandani.modules.AdminModule;
import music.nandani.modules.BasicModule;
import music.nandani.modules.Buttons;
import music.nandani.modules.CallbackModule;
import music.nandani.modules.ChatbotModule;
import music.nandani.modules.ControlModule;
import music.nandani.modules.Core;
import music.nandani.modules.EventsModule;
import music.nandani.modules.ListModule;
import music.nandani.modules.Monitor;
import music.nandani.modules.PlayModule;
import music.nandani.modules.Registry;
import music.nandani.platform.PlatformRegistry;
import music.nandani.tg.AssistantSession;
import music.nandani.tg.Bot;
import music.nandani.tg.Markup;
import music.nandani.tg.MtAssistantSession;
import music.nandani.tg.MtBot;
import music.nandani.tg.TdAssistantSession;
import music.nandani.tg.TgCallback;
import music.nandani.tg.TgMessage;
import music.nandani.voice.AssistantManager;
import music.nandani.voice.NtgCalls;

import java.util.Scanner;

/** Entry point: boots config, db, mtproto, assistants and modules. */
public final class Main {

    /** Serializes stream-end events per submission; 2 threads is plenty. */
    private static final java.util.concurrent.ScheduledExecutorService monitorStreamEndExecutor =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "nandani-stream-end");
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] args) {
        System.out.println("""
                ╔══════════════════════════════════════╗
                ║        NandaniMusic  v1.0.0          ║
                ║  Telegram voice-chat music engine    ║
                ╚══════════════════════════════════════╝""");

        Config cfg;
        try {
            cfg = Config.load();
        } catch (Exception e) {
            System.err.println("[fatal] " + e.getMessage());
            System.err.println("Create a .env with API_ID, API_HASH and TOKEN.");
            System.exit(2);
            return;
        }
        Core.cfg = cfg;
        Database.init(cfg.ownerId);

        Locales loc;
        try {
            loc = Locales.load(System.getenv().getOrDefault("LOCALES_DIR", "locales"));
            loc.setDefaultLang(cfg.defaultLang);
        } catch (Exception e) {
            System.err.println("[fatal] locales: " + e.getMessage());
            System.exit(2);
            return;
        }
        Core.loc = loc;
        loc.setBotName(cfg.botBrand);
        Markup.setColourEnabled(!cfg.disableColour);
        System.out.println("[locales] " + String.join(", ", loc.available()));

        Core.platforms = new PlatformRegistry(cfg);

        int port = 0;
        try {
            port = Integer.parseInt(cfg.port);
        } catch (NumberFormatException ignored) { }
        if (port > 0) {
            try {
                new HealthServer(port);
                System.out.println("[health] listening on :" + port);
            } catch (Exception e) {
                System.err.println("[health] disabled: " + e.getMessage());
            }
        }

        Registry reg = new Registry();
        BasicModule.register(reg);
        PlayModule.register(reg);
        ControlModule.register(reg);
        CallbackModule.register(reg);
        AdminModule.register(reg);
        ListModule.register(reg);
        EventsModule.register(reg);
        ChatbotModule.register(reg, cfg);

        // ---- native voice engine (optional: state-only mode without it) ----
        // Only load libntgcalls when at least one assistant account is
        // configured: the library spawns native worker threads that outlive
        // an orderly JVM shutdown, and a plain bot-only deployment doesn't
        // need any of it.
        NtgCalls ntgInit = null;
        if (!cfg.assistantPhones.isEmpty() || !cfg.stringSessions.isEmpty()) {
            try {
                ntgInit = new NtgCalls(cfg.ntgcallsLib);
            } catch (Throwable e) {
                System.err.println("[voice] libntgcalls unavailable: " + e.getMessage());
                System.err.println("[voice] running in state-only mode (no audio)");
            }
        }
        final NtgCalls ntg = ntgInit;

        AssistantManager assistants = new AssistantManager();
        if (ntg != null) {
            // MTProto assistants first: STRING_SESSIONS = comma-separated
            // Pyrogram-271 session strings (or file:<path> entries). No OTP needed.
            int i = 0;
            for (String entry : cfg.stringSessions) {
                String data = entry;
                if (data.startsWith("file:")) {
                    try {
                        data = java.nio.file.Files.readString(
                                java.nio.file.Path.of(data.substring(5))).trim();
                    } catch (Exception e) {
                        System.err.println("[assistant] cannot read " + entry + ": " + e.getMessage());
                        continue;
                    }
                }
                i++;
                AssistantSession as = new MtAssistantSession(
                        "assistant" + i, cfg.apiId, cfg.apiHash, data);
                if (as.start(60_000)) {
                    assistants.add(as, new NtgCalls(cfg.ntgcallsLib));
                    System.out.println("[assistant] #" + i + " @" + as.myUsername() + " ready (mtproto)");
                } else {
                    System.err.println("[assistant] #" + i + " mtproto login failed");
                }
            }
            // TDLib phone-number assistants as fallback.
            for (String phone : cfg.assistantPhones) {
                i++;
                TdAssistantSession as = new TdAssistantSession("assistant" + i, phone, cfg.tdjsonLib);
                as.configure(Integer.toString(cfg.apiId), cfg.apiHash,
                        cfg.tdlibAssistantDir + "/" + i, cfg.tdlibAssistantDir + "/" + i + "-files");
                as.setCodeProvider(q -> prompt("OTP for " + phone + " (" + q + "): "));
                as.setPasswordProvider(q -> prompt("2FA password (" + q + "): "));
                if (as.start(90_000)) {
                    assistants.add(as, new NtgCalls(cfg.ntgcallsLib));
                    System.out.println("[assistant] #" + i + " @" + as.myUsername() + " ready");
                } else {
                    System.err.println("[assistant] #" + i + " login timed out");
                }
            }
        }
        Core.assistants = assistants;

        Core.monitor = new Monitor();
        Core.monitor.start();
        // Feed stream-end events into advanceRoom on
        // their own threads (never block the native ntg callback thread).
        // Debounce 3s: ntgcalls fires one event per stream (audio AND video),
        // a second event right after an advance would skip the fresh track.
        assistants.forEach(a -> a.engine.onStreamEnd(chatId ->
                monitorStreamEndExecutor.submit(() -> Core.monitor.advanceRoom(chatId))));
        // Connection terminally lost (VC closed / assistant kicked) -> destroy
        // the room so /play can join fresh instead of streaming into a dead
        // call. Only settled failure states — connecting/connected are fine.
        assistants.forEach(a -> a.engine.onConnectionChange((chatId, state) -> {
            if ("closed".equals(state) || "failed".equals(state) || "timeout".equals(state)) {
                monitorStreamEndExecutor.submit(() -> {
                    if (Core.rooms.roomFor(chatId) != null) {
                        System.out.println("[monitor] connection " + state + " chat=" + chatId + " → dropping room");
                        Core.rooms.dropRoom(chatId);
                    }
                });
            }
        }));
        System.out.println("[monitor] stream-end handlers wired for " + assistants.count() + " assistant(s)");

        boolean dryRun = music.nandani.util.Util.parseBool(
                System.getenv().getOrDefault("NANDANI_DRY_RUN", ""));

        if (dryRun) {
            Bot bot = new ConsoleBot();
            Core.bot = bot;
            Core.botReady = true;
            boolean asGroup = music.nandani.util.Util.parseBool(
                    System.getenv().getOrDefault("NANDANI_DRY_GROUP", ""));
            boolean runAll = music.nandani.util.Util.parseBool(
                    System.getenv().getOrDefault("NANDANI_DRY_ALL", ""));

            if (runAll) {
                // Offline regression sweep: every registered command, twice
                // (private + group), flagging exceptions and unrendered text.
                int ran = 0;
                int failed = 0;
                for (String name : reg.commandNames()) {
                    for (boolean group : new boolean[]{false, true}) {
                        TgMessage m = new TgMessage();
                        m.chatId = group ? -1001234567890L : (cfg.ownerId == 0 ? 1 : cfg.ownerId);
                        m.senderId = cfg.ownerId == 0 ? 1 : cfg.ownerId;
                        m.id = 1000L + ran;
                        m.text = "/" + name;
                        m.isPrivate = !group;
                        m.firstName = "Tester";
                        try {
                            reg.dispatchMessage(bot, m);
                            reg.dispatchCommand(bot, m);
                        } catch (Throwable e) {
                            failed++;
                            System.out.println("[dry-run] FAIL /" + name + (group ? " (group)" : "")
                                    + ": " + e);
                        }
                        ran++;
                    }
                }
                System.out.println("[dry-run] ran " + ran + " command invocations, " + failed + " failed");
                return;
            }

            System.out.println("[dry-run] console mode — type a command, Ctrl-D to exit");
            Scanner sc = new Scanner(System.in);
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                if (!line.startsWith("/")) line = "/" + line;
                TgMessage m = new TgMessage();
                m.chatId = cfg.ownerId == 0 ? 1 : cfg.ownerId;
                m.senderId = m.chatId;
                m.id = System.nanoTime() % 1_000_000;
                m.text = line;
                m.isPrivate = !asGroup;
                try {
                    reg.dispatchMessage(bot, m);
                    reg.dispatchCommand(bot, m);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            return;
        }

        // Raw MTProto transport.
        MtBot bot = new MtBot(cfg.apiId, cfg.apiHash, cfg.token, cfg.dc);
        Core.bot = bot;

        bot.onMessage(m -> {
            try {
                reg.dispatchMessage(bot, m);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
        // Voice-chat start/end service messages -> styled announcement +
        // room cleanup on end (EventsModule.onVoiceChat).
        bot.onVoiceChat((chatId, ev) -> {
            try {
                EventsModule.onVoiceChat(bot, chatId, ev);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
        // Command + callback dispatch is registered per-name so the transport
        // only delivers what the registry knows about.
        for (String name : reg.commandNames()) {
            bot.onCommand(name, m -> {
                try {
                    reg.dispatchCommand(bot, m);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            });
        }
        for (String prefix : reg.callbackPrefixes()) {
            bot.onCallback(prefix, cb -> {
                try {
                    reg.dispatchCallback(bot, cb);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            });
        }

        if (!bot.start(120_000)) {
            System.err.println("[fatal] Telegram authorization failed (check TOKEN / network)");
            System.exit(2);
        }
        Core.botReady = true;
        Buttons.setBotUsername(bot.meUsername());
        ChatbotModule.setBotUsername(bot.meUsername());
        Buttons.setDisableColour(cfg.disableColour);
        System.out.println("[bot] ready as @" + bot.meUsername() + " (" + bot.meId() + ")");

        if (cfg.setCmds) {
            int n = bot.setCommands(reg.commandNames());
            System.out.println("[bot] commands registered: " + n);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[shutdown] closing");
            if (Core.monitor != null) Core.monitor.shutdown();
            assistants.close();
            if (ntg != null) ntg.close();
            bot.close();
            // Skip the rest of the JVM teardown: native libraries with live
            // worker threads can fault while their callbacks are unloaded.
            System.out.flush();
            Runtime.getRuntime().halt(0);
        }));

        while (true) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static String prompt(String label) {
        System.out.print(label);
        System.out.flush();
        Scanner sc = new Scanner(System.in);
        return sc.hasNextLine() ? sc.nextLine().trim() : "";
    }

    /** Minimal console bot used by NANDANI_DRY_RUN for offline command checks. */
    static final class ConsoleBot implements Bot {
        @Override public long sendText(long chatId, String text, music.nandani.tg.Markup m) {
            System.out.println("---\n" + text);
            String t = text == null ? "" : text.trim();
            if (t.matches("\\[[a-z0-9_]+\\]")) {
                System.out.println("[dry-run] WARN missing locale key " + t);
            }
            java.util.regex.Matcher ph = java.util.regex.Pattern.compile("\\{([a-zA-Z0-9_]+)}").matcher(t);
            while (ph.find()) {
                System.out.println("[dry-run] WARN unrendered placeholder " + ph.group());
            }
            return 1;
        }
        @Override public long sendTextSync(long chatId, String text, music.nandani.tg.Markup m, long r) {
            return sendText(chatId, text, m);
        }
        @Override public long replyText(TgMessage msg, String text, music.nandani.tg.Markup m) {
            return sendText(msg.chatId, text, m);
        }
        @Override public boolean editText(long c, long id, String t, music.nandani.tg.Markup m) { return true; }
        @Override public long sendPhoto(long c, String p, String cap, music.nandani.tg.Markup m) { return sendText(c, cap, m); }
        @Override public long sendPhotoSync(long c, String p, String cap, music.nandani.tg.Markup m, long r) { return sendText(c, cap, m); }
        @Override public boolean editCaption(long c, long id, String t, music.nandani.tg.Markup m) { return true; }
        @Override public boolean editMarkup(long c, long id, music.nandani.tg.Markup m) { return true; }
        @Override public boolean answerCallback(long q, String t, boolean a) { return true; }
        @Override public boolean deleteMessage(long c, long id) { return true; }
        @Override public boolean banMember(long c, long u, long until) { return true; }
        @Override public boolean unbanMember(long c, long u) { return true; }
        @Override public boolean muteMember(long c, long u) { return true; }
        @Override public boolean promoteMember(long c, long u, PromoteRights r) { return true; }
        @Override public boolean demoteMember(long c, long u) { return true; }
        @Override public boolean pinMessage(long c, long id, boolean s) { return true; }
        @Override public boolean unpinMessage(long c, long id) { return true; }
        @Override public boolean unpinAllMessages(long c) { return true; }
        @Override public MemberState getChatMemberState(long c, long u) { return MemberState.CREATOR; }
        @Override public String chatPublicUsername(long c) { return ""; }
        @Override public String createChatInviteLink(long c) { return ""; }
        @Override public boolean approveJoinRequest(long c, long u) { return true; }
        @Override public long getRepliedSender(long c, long id) { return 0; }
        @Override public boolean sendChatActionChoosingSticker(long c) { return true; }
        @Override public boolean sendSticker(long c, String s, long r) { return true; }
        @Override public long meId() { return 1; }
        @Override public String meUsername() { return "nandani_bot"; }
        @Override public String meFirstName() { return "Nandani"; }
        @Override public boolean ready() { return true; }
        @Override public String executeJson(String r) { return "{}"; }
        @Override public boolean start(long timeoutMs) { return true; }
        @Override public void close() { }
    }
}
