#!/usr/bin/env bash
# Hidden judge: the renamed API must exist with percent semantics, the old method
# must be gone, and every caller must produce the same numbers as before the refactor.
set -e
cat > _Check.java <<'EOF'
import java.lang.reflect.Method;

public class _Check {
    public static void main(String[] args) throws Exception {
        Method renamed = Order.class.getMethod("totalWithTax", double.class, double.class);
        for (Method m : Order.class.getDeclaredMethods()) {
            if (m.getName().equals("calc")) {
                System.err.println("old Order.calc still exists");
                System.exit(1);
            }
        }
        double direct = (double) renamed.invoke(null, 100.0, 7.0);
        assertClose(direct, 107.0, "Order.totalWithTax(100, 7)");
        assertClose(Checkout.total(100.0), 107.0, "Checkout.total(100)");
        if (!InvoicePrinter.line(100.0).equals("TOTAL=120.00")) {
            System.err.println("InvoicePrinter.line(100) = " + InvoicePrinter.line(100.0));
            System.exit(1);
        }
        assertClose(ReportService.sumWithTax(new double[] {100.0, 200.0}), 315.0,
                "ReportService.sumWithTax({100,200})");
        System.out.println("ok");
    }

    private static void assertClose(double actual, double expected, String what) {
        if (Math.abs(actual - expected) > 1e-9) {
            System.err.println(what + " = " + actual + ", expected " + expected);
            System.exit(1);
        }
    }
}
EOF
javac Order.java Checkout.java InvoicePrinter.java ReportService.java _Check.java
java _Check
