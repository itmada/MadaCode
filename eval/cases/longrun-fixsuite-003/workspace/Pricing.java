/** Price math in integer cents. */
public final class Pricing {

    /**
     * Applies a percentage discount to a price in cents. The discount amount
     * is {@code floor(cents * percent / 100)}.
     */
    public static int applyDiscount(int cents, int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent out of range");
        }
        int discount = cents / 100 * percent;
        return cents - discount;
    }
}
