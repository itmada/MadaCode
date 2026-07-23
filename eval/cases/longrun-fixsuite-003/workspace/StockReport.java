import java.util.Map;
import java.util.TreeMap;

/** Renders inventory as text. */
public final class StockReport {

    /**
     * Lists SKUs with positive stock alphabetically, one per line, followed by
     * a TOTAL line summing the listed stock.
     */
    public static String render(Map<String, Integer> snapshot) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, Integer> e : new TreeMap<>(snapshot).entrySet()) {
            if (e.getValue() > 0) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            total += Math.abs(e.getValue());
        }
        sb.append("TOTAL: ").append(total).append('\n');
        return sb.toString();
    }
}
