public final class Checkout {

    /** Total for the standard 7% checkout tax. */
    public static double total(double amount) {
        return Order.calc(amount, 0.07);
    }
}
