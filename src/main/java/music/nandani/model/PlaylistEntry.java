package music.nandani.model;

/** A saved playlist entry. */
public final class PlaylistEntry {
    public String title;
    public String url;
    public long duration;
    public String requester;

    public PlaylistEntry() {}

    public PlaylistEntry(String title, String url, long duration, String requester) {
        this.title = title;
        this.url = url;
        this.duration = duration;
        this.requester = requester;
    }

    public PlaylistEntry copy() {
        return new PlaylistEntry(title, url, duration, requester);
    }
}
