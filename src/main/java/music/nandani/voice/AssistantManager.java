package music.nandani.voice;

import music.nandani.db.Database;
import music.nandani.tg.AssistantSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Assistant pool — one voice engine per logged-in user account.
 * Chat -&gt; assistant mapping keeps the same modulo semantics as db::get_assistant.
 */
public final class AssistantManager {

    public static final class Assistant {
        public final int index;          // 1-based
        public final AssistantSession session;
        public final VoiceEngine engine;

        Assistant(int index, AssistantSession session, VoiceEngine engine) {
            this.index = index;
            this.session = session;
            this.engine = engine;
        }
    }

    private final List<Assistant> list = new ArrayList<>();

    public AssistantManager() {
        Database.initAssistantIndexes(1);
    }

    public void add(AssistantSession session, NtgCalls ntg) {
        int idx = list.size() + 1;
        list.add(new Assistant(idx, session, new VoiceEngine(ntg, session)));
        Database.initAssistantIndexes(list.size());
    }

    public int count() { return list.size(); }

    public Assistant get(int idx) {
        return idx >= 1 && idx <= list.size() ? list.get(idx - 1) : null;
    }

    public Assistant first() { return list.isEmpty() ? null : list.get(0); }

    public Assistant forChat(long chatId) {
        if (list.isEmpty()) return null;
        long idx = Database.getAssistant(chatId);
        return get((int) idx);
    }

    public List<Assistant> all() { return new ArrayList<>(list); }

    public void forEach(java.util.function.Consumer<Assistant> f) {
        for (Assistant a : list) f.accept(a);
    }

    public void close() {
        for (Assistant a : list) {
            a.engine.close();
            a.session.close();
        }
    }
}
