package music.nandani.tg;

import java.util.ArrayList;
import java.util.List;

/** Inline keyboard model. */
public final class Markup {

    /** DISABLE_COLOUR: when false every button gets a random bg colour. */
    private static volatile boolean colourEnabled = true;

    public static void setColourEnabled(boolean enabled) {
        colourEnabled = enabled;
    }

    /**
     * Random button colour:
     * each button independently gets blue/red/green (1/2/3).
     */
    static int randomStyle() {
        if (!colourEnabled) return 0;
        long x = System.nanoTime() ^ 0x9E3779B97F4A7C15L;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return (int) Math.floorMod(x, 3L) + 1;
    }

    public static final class Button {
        public final String text;
        public String url;
        public String data;
        /** 0 = default, 1 = primary, 2 = danger, 3 = success. */
        public int style;

        private Button(String text) { this.text = text; }

        public static Button url(String text, String url) {
            Button b = new Button(text);
            b.url = url;
            b.style = randomStyle();
            return b;
        }

        public static Button data(String text, String cb) {
            Button b = new Button(text);
            b.data = cb;
            b.style = randomStyle();
            return b;
        }

        public Button style(int s) { this.style = s; return this; }
    }

    public final List<List<Button>> rows = new ArrayList<>();

    public Markup row(Button... buttons) {
        List<Button> r = new ArrayList<>();
        for (Button b : buttons) if (b != null) r.add(b);
        if (!r.isEmpty()) rows.add(r);
        return this;
    }

    public boolean isEmpty() { return rows.isEmpty(); }

    public static Markup of(List<List<Button>> rows) {
        Markup m = new Markup();
        m.rows.addAll(rows);
        return m;
    }
}
