import java.util.HashMap;
import java.util.Map;

/** Stock levels per SKU. */
public final class Inventory {

    private final Map<String, Integer> stock = new HashMap<>();

    public void receive(String sku, int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        stock.merge(sku, qty, Integer::sum);
    }

    /** Ships {@code qty} units of a SKU out of the warehouse. */
    public void ship(String sku, int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        stock.merge(sku, -qty, Integer::sum);
    }

    public int level(String sku) {
        return stock.getOrDefault(sku, 0);
    }

    public Map<String, Integer> snapshot() {
        return new HashMap<>(stock);
    }
}
