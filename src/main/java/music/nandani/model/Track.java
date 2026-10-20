package music.nandani.model;

import java.util.ArrayList;
import java.util.List;

/** A playable track resolved from any platform. */
public final class Track {

    public String id = "";
    public String title = "";
    public long duration = 0;
    public String artwork = "";
    public String url = "";
    public String requester = "";
    public boolean video = false;
    public String source = "";
    public boolean isAutoplay = false;
    public String engineToken = "";

    public Track() {}

    public Track(String id, String title, long duration, String url, String source) {
        this.id = id;
        this.title = title;
        this.duration = duration;
        this.url = url;
        this.source = source;
    }

    public Track markAutoplay(String who, String token) {
        this.isAutoplay = true;
        this.requester = who;
        this.engineToken = token;
        return this;
    }

    public boolean isVerifiedAutoplay(String token) {
        if (!isAutoplay) return false;
        if (token != null && !token.isEmpty() && !engineToken.equals(token)) return false;
        return true;
    }

    public Track copy() {
        Track t = new Track();
        t.id = id; t.title = title; t.duration = duration; t.artwork = artwork;
        t.url = url; t.requester = requester; t.video = video; t.source = source;
        t.isAutoplay = isAutoplay; t.engineToken = engineToken;
        return t;
    }

    /** lowercase, strip bracketed groups, strip filler words. */
    public static String normalizeTrackTitle(String title) {
        if (title == null || title.isEmpty()) return "";
        String t = title.toLowerCase(java.util.Locale.ROOT);
        String out = stripBracketed(t);
        String[] reps = {
            "official music video", "official video", "music video", "lyric video",
            "lyrics", "full video", "video song", "audio song", "audio track",
            "official audio", "audio", "4k", "8k", "hd", "remix", "song", "full track"
        };
        for (String r : reps) {
            int p;
            while ((p = out.indexOf(r)) >= 0) {
                out = out.substring(0, p) + " " + out.substring(p + r.length());
            }
        }
        return String.join(" ", out.trim().split("\\s+"));
    }

    private static String stripBracketed(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if ((c == ')' || c == ']' || c == '}') && depth > 0) depth--;
            else if (depth == 0) out.append(c);
        }
        return out.toString();
    }

    public static List<Track> list() {
        return new ArrayList<>();
    }

    @Override
    public String toString() {
        return "Track{" + id + ", " + title + ", " + duration + "s}";
    }
}
