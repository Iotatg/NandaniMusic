package music.nandani.model;

/** WarnEntry — 1:1 with db.rs. */
public final class WarnEntry {
    public String reason;
    public long by;
    public long at;

    public WarnEntry() {}

    public WarnEntry(String reason, long by, long at) {
        this.reason = reason;
        this.by = by;
        this.at = at;
    }
}
