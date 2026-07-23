/** Binary search over a sorted ascending IntSource. */
public final class Search {

    /**
     * Returns an index in [fromInclusive, toInclusive] whose value equals
     * {@code target}, or -1 when absent. The source must be sorted ascending
     * on that index range.
     */
    public static int indexOf(IntSource source, int fromInclusive, int toInclusive, int target) {
        int lo = fromInclusive;
        int hi = toInclusive;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            int value = source.valueAt(mid);
            if (value == target) {
                return mid;
            }
            if (value < target) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }
}
