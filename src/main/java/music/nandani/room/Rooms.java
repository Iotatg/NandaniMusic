package music.nandani.room;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Global chat_id -&gt; RoomState map. */
public final class Rooms {

    private final Map<Long, RoomState> map = new ConcurrentHashMap<>();

    /** existing rooms are returned as-is. */
    public RoomState createRoom(long chatId, boolean autoplay) {
        return map.computeIfAbsent(chatId, id -> new RoomState(id, autoplay));
    }

    public RoomState roomFor(long chatId) {
        return map.get(chatId);
    }

    public boolean dropRoom(long chatId) {
        RoomState r = map.remove(chatId);
        if (r == null) return false;
        synchronized (r) {
            r.stop();
            r.destroy();
        }
        return true;
    }

    public List<Map.Entry<Long, RoomState>> allRooms() {
        return new ArrayList<>(map.entrySet());
    }

    public int count() {
        return map.size();
    }
}
