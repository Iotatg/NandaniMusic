package music.nandani.modules;

import music.nandani.config.Config;
import music.nandani.i18n.Locales;
import music.nandani.room.RoomState;
import music.nandani.tg.Markup;
import music.nandani.tg.Markup.Button;
import music.nandani.util.Util;

import java.util.List;

/** Inline keyboard builders — 1:1 with bot/src/buttons.rs. */
public final class Buttons {

    private static volatile String botUsername = "";
    private static volatile boolean disableColour = false;

    private Buttons() {}

    public static void setBotUsername(String u) { botUsername = u == null ? "" : u; }
    public static String botUsername() { return botUsername; }
    public static void setDisableColour(boolean v) { disableColour = v; }
    public static boolean disableColour() { return disableColour; }

    private static Markup rows(List<List<Button>> all) {
        Markup m = new Markup();
        for (List<Button> r : all) if (!r.isEmpty()) m.rows.add(r);
        return m;
    }

    /** Progress row only when !queued. */
    public static Markup playMarkup(Locales loc, String lang, RoomState.RoomView r, boolean queued) {
        String prefix = "room:" + r.id() + ":";
        java.util.function.BiFunction<String, String, Button> cb =
                (action, text) -> Button.data(text, prefix + action);
        String progress = Util.formatTime(r.position()) + " "
                + Util.getProgressBar(r.position(), r.duration()) + " "
                + Util.formatTime(r.duration());

        java.util.List<java.util.List<Button>> all = new java.util.ArrayList<>();
        if (!queued) all.add(List.of(Button.data(progress, "progress")));
        all.add(List.of(
                cb.apply("resume", "\u25B7"),
                cb.apply("pause", "II"),
                cb.apply("replay", "\u27F3"),
                cb.apply("skip", "\u2023\u2023I"),
                cb.apply("stop", "\u25A2")
        ));
        all.add(List.of(
                cb.apply("seekback_15", "-\uD835\uDFE3\uD835\uDFE7\u02E2"),
                cb.apply("seek_15", "\uD835\uDFE3\uD835\uDFE7\u02E2+")
        ));
        String state = r.autoplay() ? "enabled" : "disabled";
        String apText = loc.f(lang, "autoplay_btn", "state", loc.get(lang, state));
        all.add(List.of(cb.apply("autoplay_toggle", apText)));
        all.add(List.of(Button.data(loc.get(lang, "CLOSE_BTN"), "close")));
        return rows(all);
    }

    public static Markup stopConfirmMarkup(Locales loc, String lang, RoomState.RoomView r) {
        String prefix = "room:" + r.id() + ":";
        String label = r.paused() ? "CONFIRM_RESUME_BTN" : "CONFIRM_UNMUTE_BTN";
        String action = r.paused() ? "resume" : "unmute";
        return rows(List.of(List.of(
                Button.data(loc.get(lang, label), prefix + action),
                Button.data(loc.get(lang, "CONFIRM_STOP_BTN"), prefix + "stop")
        )));
    }

    public static Markup addMeMarkup(Locales loc, String lang) {
        return rows(List.of(List.of(Button.url(
                loc.get(lang, "ADD_ME_BTN"),
                "https://t.me/" + botUsername + "?startgroup&admin=invite_users"))));
    }

    public static Markup cancelKeyboard(Locales loc, String lang) {
        return rows(List.of(List.of(Button.data(loc.get(lang, "DOWNLOAD_CANCEL_BTN"), "cancel"))));
    }

    public static Markup broadcastCancelKeyboard(Locales loc, String lang) {
        return rows(List.of(List.of(Button.data(loc.get(lang, "BROADCAST_CANCEL_BTN"), "bcast_cancel"))));
    }

    public static Markup suppMarkup(Config cfg, Locales loc, String lang) {
        return rows(List.of(List.of(Button.url(loc.get(lang, "SUPPORT_BTN"), cfg.supportChat))));
    }

    public static Markup groupHelpKeyboard(Locales loc, String lang) {
        return rows(List.of(List.of(Button.url(
                loc.get(lang, "GC_HELP_BTN"),
                "https://t.me/" + botUsername + "?start=pm_help"))));
    }

    public static Markup helpKeyboard(Locales loc, String lang) {
        return rows(List.of(
                List.of(
                        Button.data(loc.get(lang, "HELP_ADMIN_BTN"), "help:admin"),
                        Button.data(loc.get(lang, "HELP_AUTH_BTN"), "help:auth"),
                        Button.data(loc.get(lang, "HELP_BCAST_BTN"), "help:bcast")),
                List.of(
                        Button.data(loc.get(lang, "HELP_PLAY_BTN"), "help:play"),
                        Button.data(loc.get(lang, "HELP_SUDO_BTN"), "help:sudo"),
                        Button.data(loc.get(lang, "HELP_RESTRICT_BTN"), "help:restrict")),
                List.of(
                        Button.data(loc.get(lang, "HELP_THUMB_BTN"), "help:thumb"),
                        Button.data(loc.get(lang, "HELP_START_BTN"), "help:start"),
                        Button.data(loc.get(lang, "HELP_AUTOPLAY_BTN"), "help:autoplay")),
                List.of(
                        Button.data(loc.get(lang, "HELP_PLAYLIST_BTN"), "help:playlist"),
                        Button.data(loc.get(lang, "HELP_VCLOGS_BTN"), "help:vclogs"),
                        Button.data(loc.get(lang, "HELP_INLINE_BTN"), "help:inline")),
                List.of(Button.data(loc.get(lang, "BACK_BTN"), "start"))
        ));
    }

    public static Markup backKeyboard(Locales loc, String lang) {
        return rows(List.of(List.of(Button.data(loc.get(lang, "BACK_BTN"), "help:main"))));
    }

    public static Markup startMarkup(Config cfg, Locales loc, String lang) {
        java.util.List<java.util.List<Button>> all = new java.util.ArrayList<>();
        all.add(List.of(Button.url(loc.get(lang, "ADD_ME_BTN"),
                "https://t.me/" + botUsername + "?startgroup&admin=invite_users")));
        all.add(List.of(Button.data(loc.get(lang, "START_HELP_BTN"), "help_cb")));
        all.add(List.of(
                Button.url(loc.get(lang, "UPDATES_BTN"), cfg.supportChannel),
                Button.url(loc.get(lang, "SUPPORT_BTN"), cfg.supportChat)));
        // Source button only when a REPO_URL is configured.
        if (!repoUrl().isEmpty()) {
            all.add(List.of(Button.url(loc.get(lang, "SOURCE_BTN"), repoUrl())));
        }
        return rows(all);
    }

    /** Repo URL from the environment — empty by default (no third-party credit link). */
    static String repoUrl() {
        String r = System.getenv("REPO_URL");
        return r == null ? "" : r.trim();
    }

    public static Markup repoMarkup(Config cfg, Locales loc, String lang, String devUrl) {
        String d = devUrl == null ? "" : devUrl;
        if (d.isEmpty()) {
            if (!cfg.devUrl.isEmpty()) d = cfg.devUrl;
            else if (!cfg.ownerUsername.isEmpty()) d = "https://t.me/" + cfg.ownerUsername.replaceFirst("^@", "");
            else if (cfg.ownerId != 0) d = "tg://openmessage?user_id=" + cfg.ownerId;
        }
        return rows(List.of(List.of(
                Button.url(loc.get(lang, "REPO_DEV_BTN"), d),
                Button.url(loc.get(lang, "REPO_BTN"), repoUrl())
        )));
    }
}
