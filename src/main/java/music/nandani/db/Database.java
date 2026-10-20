package music.nandani.db;

import music.nandani.model.PlaylistEntry;
import music.nandani.model.WarnEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-process store — 1:1 port of bot/src/db.rs.
 * The Mongo URI is accepted for configuration parity but nothing is persisted
 * externally, exactly like the Rust original.
 */
public final class Database {

    private Database() {}

    public record FilterRow(String keyword, String text) {}

    private static final Map<Long, String> LANGS = new HashMap<>();
    private static final Map<Long, Boolean> AUTOPLAYS = new HashMap<>();
    private static final Map<Long, Boolean> PLAY_ADMINS = new HashMap<>();
    private static final Map<Long, Long> LINKED = new HashMap<>();
    private static final List<Long> SUDOERS = new ArrayList<>();
    private static final Map<Long, Set<Long>> AUTH = new HashMap<>();
    private static final Map<String, List<PlaylistEntry>> PLAYLISTS = new HashMap<>();
    private static final Map<String, List<WarnEntry>> WARNS = new HashMap<>();
    private static final Map<Long, long[]> WARN_LIMIT = new HashMap<>();
    private static final Map<Long, String> WARN_MODE = new HashMap<>();
    private static final Map<Long, Boolean> WELCOMES = new HashMap<>();
    private static final Map<Long, Map<String, String>> NOTES = new HashMap<>();
    private static final Map<Long, List<FilterRow>> FILTERS = new HashMap<>();
    private static final Map<String, List<String>> STICKER_PACKS = new LinkedHashMap<>();
    private static final Map<Long, Long> ASSISTANTS = new HashMap<>();
    private static final Map<Long, Boolean> NO_THUMBS = new HashMap<>();
    private static final Map<Long, Boolean> CLEAN_MODE = new HashMap<>();
    private static final Set<Long> SERVED_USERS = new HashSet<>();
    private static final Set<Long> SERVED_CHATS = new HashSet<>();
    private static final List<Long> BL_USERS = new ArrayList<>();
    private static final List<Long> BL_CHATS = new ArrayList<>();
    private static boolean maintenance = false;
    private static boolean logger = false;
    private static long assistantCount = 1;

    private static synchronized void lock() { /* monitor for all state */ }

    public static synchronized void init(long ownerId) {
        if (ownerId != 0 && !SUDOERS.contains(ownerId)) SUDOERS.add(ownerId);
    }

    private static String plKey(long user, String name) { return user + "\u0000" + name; }
    private static String warnKey(long chat, long user) { return chat + "\u0000" + user; }

    // ---- language / chat flags ----

    public static synchronized String language(long chatId) { return LANGS.getOrDefault(chatId, ""); }
    public static synchronized void setLanguage(long chatId, String lang) { LANGS.put(chatId, lang); }

    public static synchronized boolean autoplay(long chatId) { return AUTOPLAYS.getOrDefault(chatId, false); }
    public static synchronized void setAutoplay(long chatId, boolean v) { AUTOPLAYS.put(chatId, v); }

    public static synchronized boolean playModeAdminsOnly(long chatId) { return PLAY_ADMINS.getOrDefault(chatId, false); }
    public static synchronized void setPlayModeAdminsOnly(long chatId, boolean v) { PLAY_ADMINS.put(chatId, v); }

    public static synchronized long linkedChannel(long chatId) { return LINKED.getOrDefault(chatId, 0L); }
    public static synchronized void setLinkedChannel(long chatId, long channelId) {
        if (channelId == 0) LINKED.remove(chatId); else LINKED.put(chatId, channelId);
    }

    // ---- sudo / auth ----

    public static synchronized boolean isSudo(long userId) { return SUDOERS.contains(userId); }

    public static synchronized boolean addSudo(long userId) {
        if (SUDOERS.contains(userId)) return false;
        SUDOERS.add(userId);
        return true;
    }

    public static synchronized boolean delSudo(long userId) {
        return SUDOERS.remove(Long.valueOf(userId));
    }

    public static synchronized List<Long> sudoList() { return new ArrayList<>(SUDOERS); }

    public static synchronized boolean isAuthorized(long chatId, long userId) {
        Set<Long> s = AUTH.get(chatId);
        return s != null && s.contains(userId);
    }

    public static synchronized boolean addAuthUser(long chatId, long userId) {
        return AUTH.computeIfAbsent(chatId, k -> new HashSet<>()).add(userId);
    }

    public static synchronized boolean delAuthUser(long chatId, long userId) {
        Set<Long> s = AUTH.get(chatId);
        return s != null && s.remove(userId);
    }

    public static synchronized List<Long> authUsers(long chatId) {
        Set<Long> s = AUTH.get(chatId);
        return s == null ? new ArrayList<>() : new ArrayList<>(s);
    }

    // ---- blacklist ----

    public static synchronized boolean isBlacklistedUser(long userId) { return BL_USERS.contains(userId); }
    public static synchronized boolean addBlacklistedUser(long userId) {
        if (BL_USERS.contains(userId)) return false;
        BL_USERS.add(userId); return true;
    }
    public static synchronized boolean delBlacklistedUser(long userId) { return BL_USERS.remove(Long.valueOf(userId)); }
    public static synchronized List<Long> blacklistedUsers() { return new ArrayList<>(BL_USERS); }

    public static synchronized boolean isBlacklistedChat(long chatId) { return BL_CHATS.contains(chatId); }
    public static synchronized boolean addBlacklistedChat(long chatId) {
        if (BL_CHATS.contains(chatId)) return false;
        BL_CHATS.add(chatId); return true;
    }
    public static synchronized boolean delBlacklistedChat(long chatId) { return BL_CHATS.remove(Long.valueOf(chatId)); }
    public static synchronized List<Long> blacklistedChats() { return new ArrayList<>(BL_CHATS); }

    // ---- global flags ----

    public static synchronized boolean maintenance() { return maintenance; }
    public static synchronized void setMaintenance(boolean v) { maintenance = v; }
    public static synchronized boolean logger() { return logger; }
    public static synchronized void setLogger(boolean v) { logger = v; }

    // ---- playlists ----

    public static synchronized boolean createPlaylist(long userId, String name) {
        String k = plKey(userId, name);
        if (PLAYLISTS.containsKey(k)) return false;
        PLAYLISTS.put(k, new ArrayList<>());
        return true;
    }

    public static synchronized boolean playlistExists(long userId, String name) {
        return PLAYLISTS.containsKey(plKey(userId, name));
    }

    public static synchronized boolean deletePlaylist(long userId, String name) {
        return PLAYLISTS.remove(plKey(userId, name)) != null;
    }

    public static synchronized List<String> playlistsOf(long userId) {
        List<String> out = new ArrayList<>();
        for (String k : PLAYLISTS.keySet()) {
            int i = k.indexOf('\u0000');
            if (Long.parseLong(k.substring(0, i)) == userId) out.add(k.substring(i + 1));
        }
        Collections.sort(out);
        return out;
    }

    public static synchronized boolean addToPlaylist(long userId, String name, PlaylistEntry e) {
        List<PlaylistEntry> list = PLAYLISTS.get(plKey(userId, name));
        if (list == null) return false;
        list.add(e);
        return true;
    }

    public static synchronized boolean removeFromPlaylist(long userId, String name, int index) {
        List<PlaylistEntry> list = PLAYLISTS.get(plKey(userId, name));
        if (list == null || index < 0 || index >= list.size()) return false;
        list.remove(index);
        return true;
    }

    public static synchronized List<PlaylistEntry> playlistEntries(long userId, String name) {
        List<PlaylistEntry> l = PLAYLISTS.get(plKey(userId, name));
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    // ---- warns ----

    private static long nowSecs() { return System.currentTimeMillis() / 1000L; }

    public static synchronized long addWarning(long chatId, long userId, long by, String reason) {
        List<WarnEntry> list = WARNS.computeIfAbsent(warnKey(chatId, userId), k -> new ArrayList<>());
        list.add(new WarnEntry(reason, by, nowSecs()));
        return list.size();
    }

    public static synchronized long countWarnings(long chatId, long userId) {
        List<WarnEntry> l = WARNS.get(warnKey(chatId, userId));
        return l == null ? 0 : l.size();
    }

    public static synchronized List<WarnEntry> warningsList(long chatId, long userId) {
        List<WarnEntry> l = WARNS.get(warnKey(chatId, userId));
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    public static synchronized boolean removeLastWarning(long chatId, long userId) {
        List<WarnEntry> l = WARNS.get(warnKey(chatId, userId));
        if (l == null || l.isEmpty()) return false;
        l.remove(l.size() - 1);
        return true;
    }

    public static synchronized long clearWarnings(long chatId, long userId) {
        List<WarnEntry> l = WARNS.get(warnKey(chatId, userId));
        if (l == null) return 0;
        long n = l.size();
        l.clear();
        return n;
    }

    public static synchronized long clearAllWarnings(long chatId) {
        long n = 0;
        List<String> remove = new ArrayList<>();
        for (Map.Entry<String, List<WarnEntry>> e : WARNS.entrySet()) {
            int i = e.getKey().indexOf('\u0000');
            if (Long.parseLong(e.getKey().substring(0, i)) == chatId) {
                n += e.getValue().size();
                remove.add(e.getKey());
            }
        }
        for (String k : remove) WARNS.remove(k);
        return n;
    }

    public static synchronized long warnLimit(long chatId) {
        long[] v = WARN_LIMIT.get(chatId);
        if (v == null) { WARN_LIMIT.put(chatId, new long[]{3}); return 3; }
        return v[0];
    }

    public static synchronized void setWarnLimit(long chatId, long limit) {
        WARN_LIMIT.put(chatId, new long[]{limit});
        WARN_MODE.putIfAbsent(chatId, "ban");
    }

    public static synchronized String warnMode(long chatId) {
        String m = WARN_MODE.get(chatId);
        if (m == null) { WARN_MODE.put(chatId, "ban"); return "ban"; }
        return m;
    }

    public static synchronized void setWarnMode(long chatId, String mode) {
        WARN_MODE.put(chatId, mode);
        long[] v = WARN_LIMIT.get(chatId);
        if (v == null || v[0] <= 0) WARN_LIMIT.put(chatId, new long[]{3});
    }

    // ---- welcomes / notes ----

    public static synchronized boolean welcomeOn(long chatId) { return WELCOMES.getOrDefault(chatId, false); }
    public static synchronized void setWelcomeOn(long chatId, boolean on) { WELCOMES.put(chatId, on); }

    public static synchronized void setNote(long chatId, String key, String content) {
        NOTES.computeIfAbsent(chatId, k -> new HashMap<>()).put(key, content);
    }

    public static synchronized String getNote(long chatId, String key) {
        Map<String, String> m = NOTES.get(chatId);
        return m == null ? "" : m.getOrDefault(key, "");
    }

    public static synchronized List<String> listNotes(long chatId) {
        Map<String, String> m = NOTES.get(chatId);
        List<String> out = m == null ? new ArrayList<>() : new ArrayList<>(m.keySet());
        Collections.sort(out);
        return out;
    }

    public static synchronized boolean deleteNote(long chatId, String key) {
        Map<String, String> m = NOTES.get(chatId);
        return m != null && m.remove(key) != null;
    }

    // ---- filters ----

    public static synchronized void saveFilter(long chatId, String keyword, String text) {
        String kw = keyword.toLowerCase(java.util.Locale.ROOT);
        List<FilterRow> rows = FILTERS.computeIfAbsent(chatId, k -> new ArrayList<>());
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).keyword().equals(kw)) {
                rows.set(i, new FilterRow(kw, text));
                return;
            }
        }
        rows.add(new FilterRow(kw, text));
    }

    public static synchronized List<FilterRow> listFilters(long chatId) {
        List<FilterRow> r = FILTERS.get(chatId);
        return r == null ? new ArrayList<>() : new ArrayList<>(r);
    }

    public static synchronized String getFilter(long chatId, String keyword) {
        String kw = keyword.toLowerCase(java.util.Locale.ROOT);
        List<FilterRow> rows = FILTERS.get(chatId);
        if (rows == null) return null;
        for (FilterRow r : rows) if (r.keyword().equals(kw)) return r.text();
        return null;
    }

    public static synchronized FilterRow matchFilter(long chatId, String text) {
        String low = text.toLowerCase(java.util.Locale.ROOT);
        List<FilterRow> rows = FILTERS.get(chatId);
        if (rows == null) return null;
        for (FilterRow r : rows) if (low.contains(r.keyword())) return r;
        return null;
    }

    public static synchronized boolean deleteFilter(long chatId, String keyword) {
        String kw = keyword.toLowerCase(java.util.Locale.ROOT);
        List<FilterRow> rows = FILTERS.get(chatId);
        if (rows == null) return false;
        return rows.removeIf(r -> r.keyword().equals(kw));
    }

    public static synchronized long clearFilters(long chatId) {
        List<FilterRow> rows = FILTERS.remove(chatId);
        return rows == null ? 0 : rows.size();
    }

    // ---- sticker packs ----

    public static synchronized List<String> stickerPackIds(String mood) {
        List<String> l = STICKER_PACKS.get(mood);
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    public static synchronized List<Map.Entry<String, List<String>>> stickerPacks() {
        List<Map.Entry<String, List<String>>> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : STICKER_PACKS.entrySet()) {
            out.add(Map.entry(e.getKey(), new ArrayList<>(e.getValue())));
        }
        return out;
    }

    public static synchronized void addStickerToPack(String mood, String remoteId) {
        if (mood == null || mood.isEmpty() || remoteId == null || remoteId.isEmpty()) return;
        List<String> ids = STICKER_PACKS.computeIfAbsent(mood, k -> new ArrayList<>());
        if (!ids.contains(remoteId)) ids.add(remoteId);
    }

    public static synchronized long clearStickerPack(String moodOrAll) {
        if ("all".equals(moodOrAll)) {
            long n = STICKER_PACKS.size();
            STICKER_PACKS.clear();
            return n;
        }
        return STICKER_PACKS.remove(moodOrAll) != null ? 1 : 0;
    }

    // ---- served registry ----

    public static synchronized void addServedUser(long id) { SERVED_USERS.add(id); }
    public static synchronized void addServedChat(long id) { SERVED_CHATS.add(id); }
    public static synchronized List<Long> servedUsers() { return new ArrayList<>(SERVED_USERS); }
    public static synchronized List<Long> servedChats() { return new ArrayList<>(SERVED_CHATS); }

    // ---- assistant indexes ----

    public static synchronized long getAssistant(long chatId) {
        Long i = ASSISTANTS.get(chatId);
        if (i != null) return i;
        long count = Math.max(assistantCount, 1);
        long idx = (Math.abs(chatId) % count) + 1;
        ASSISTANTS.put(chatId, idx);
        return idx;
    }

    public static synchronized void initAssistantIndexes(long count) {
        assistantCount = count > 0 ? count : 1;
    }

    // ---- thumbnail toggle ----

    public static synchronized boolean thumbnailsDisabled(long chatId) {
        return NO_THUMBS.getOrDefault(chatId, false);
    }

    public static synchronized void setThumbnailsDisabled(long chatId, boolean v) {
        NO_THUMBS.put(chatId, v);
    }

    public static synchronized boolean cleanModeEnabled(long chatId) {
        return CLEAN_MODE.getOrDefault(chatId, false);
    }

    public static synchronized void setCleanModeEnabled(long chatId, boolean v) {
        CLEAN_MODE.put(chatId, v);
    }
}
