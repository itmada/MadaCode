public final class Order {

    /** Returns amount plus tax, where taxRate is a fraction (0.07 means 7%). */
    public static double calc(double amount, double taxRate) {
        return amount * (1.0 + taxRate);
    }
}
