/** Step 2 of the pipeline: numeric aggregation over parsed rows. */
public final class StatsCollector {

    /**
     * Sums the integer values of column {@code col} over all rows except the
     * first (header) row. Throws NumberFormatException on non-numeric cells.
     */
    public static int sumColumn(String[][] rows, int col) {
        throw new UnsupportedOperationException("not implemented");
    }
}
