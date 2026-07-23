#!/usr/bin/env bash
# Hidden judge: all three bugs must be fixed, and correct behavior preserved.
set -e
cat > _Check.java <<'EOF'
import java.util.Map;

public class _Check {
    public static void main(String[] args) {
        // Bug 1: oversell must be rejected, level never negative.
        Inventory inv = new Inventory();
        inv.receive("apple", 5);
        try {
            inv.ship("apple", 6);
            System.err.println("ship() allowed oversell");
            System.exit(1);
        } catch (RuntimeException expected) {
            // rejected — level must be unchanged
        }
        if (inv.level("apple") != 5) {
            System.err.println("level changed after rejected oversell: " + inv.level("apple"));
            System.exit(1);
        }
        inv.ship("apple", 5);
        if (inv.level("apple") != 0) {
            System.err.println("exact shipment should reach 0, got " + inv.level("apple"));
            System.exit(1);
        }

        // Bug 2: discount math must not truncate early.
        expect(Pricing.applyDiscount(999, 20), 800, "20% off 999 (round discount down)");
        expect(Pricing.applyDiscount(1000, 20), 800, "20% off 1000");
        expect(Pricing.applyDiscount(999, 0), 999, "0% discount");
        expect(Pricing.applyDiscount(999, 100), 0, "100% discount");
        expect(Pricing.applyDiscount(101, 50), 51, "50% off 101 favors customer");

        // Bug 3: TOTAL must equal the sum of the printed levels.
        Inventory inv2 = new Inventory();
        inv2.receive("pear", 3);
        inv2.receive("plum", 2);
        inv2.receive("kiwi", 1);
        inv2.ship("kiwi", 1);
        String report = StockReport.render(inv2.snapshot());
        if (!report.contains("pear: 3") || !report.contains("plum: 2")) {
            System.err.println("report lines missing:\n" + report);
            System.exit(1);
        }
        if (report.contains("kiwi")) {
            System.err.println("zero-level SKU should not be listed:\n" + report);
            System.exit(1);
        }
        if (!report.trim().endsWith("TOTAL: 5")) {
            System.err.println("TOTAL mismatch:\n" + report);
            System.exit(1);
        }
        System.out.println("ok");
    }

    private static void expect(int actual, int expected, String what) {
        if (actual != expected) {
            System.err.println(what + " = " + actual + ", expected " + expected);
            System.exit(1);
        }
    }
}
EOF
javac Inventory.java Pricing.java StockReport.java _Check.java
java _Check
