public final class ReportService {

    /** Sums all amounts with the reduced 5% report tax. */
    public static double sumWithTax(double[] amounts) {
        double sum = 0.0;
        for (double amount : amounts) {
            sum += Order.calc(amount, 0.05);
        }
        return sum;
    }
}
