package madacode.eval;

/** Wilson score confidence interval for a binomial k/N pass rate. */
public record WilsonInterval(
        long successes,
        long total,
        double lower,
        double upper) {

    private static final double Z_95 = 1.959963984540054;

    public WilsonInterval {
        if (successes < 0 || total < 0 || successes > total) {
            throw new IllegalArgumentException("successes must be in [0,total]");
        }
        lower = clamp(lower);
        upper = clamp(upper);
    }

    public static WilsonInterval of(long successes, long total) {
        if (total == 0) {
            return new WilsonInterval(0, 0, 0.0, 0.0);
        }
        if (successes < 0 || successes > total) {
            throw new IllegalArgumentException("successes must be in [0,total]");
        }

        double n = total;
        double phat = successes / n;
        double z2 = Z_95 * Z_95;
        double denominator = 1.0 + z2 / n;
        double center = phat + z2 / (2.0 * n);
        double margin = Z_95 * Math.sqrt((phat * (1.0 - phat) + z2 / (4.0 * n)) / n);
        return new WilsonInterval(
                successes,
                total,
                (center - margin) / denominator,
                (center + margin) / denominator);
    }

    public int lowerPercentRounded() {
        return (int) Math.round(lower * 100.0);
    }

    public int upperPercentRounded() {
        return (int) Math.round(upper * 100.0);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
