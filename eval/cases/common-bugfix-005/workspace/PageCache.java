import java.util.HashMap;
import java.util.Map;

/** Tiny per-page render cache. */
public final class PageCache {

    private final Map<Integer, String> rendered = new HashMap<>();

    public String get(int page) {
        return rendered.get(page);
    }

    public void put(int page, String html) {
        rendered.put(page, html);
    }

    public void invalidate() {
        rendered.clear();
    }
}
