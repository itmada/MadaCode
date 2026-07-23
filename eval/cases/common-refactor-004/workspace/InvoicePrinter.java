public final class InvoicePrinter {

    /** Renders one invoice line with the 20% luxury tax applied. */
    public static String line(double amount) {
        return String.format(java.util.Locale.ROOT, "TOTAL=%.2f", Order.calc(amount, 0.20));
    }
}
