/** Pure pagination math shared by all list views. */
public final class Paginator {

    /** Total number of pages needed to show {@code totalItems} at {@code pageSize} per page. */
    public static int pageCount(int totalItems, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        return totalItems / pageSize + 1;
    }

    /** Number of items rendered on the given zero-based page. */
    public static int itemsOnPage(int totalItems, int pageSize, int page) {
        if (page < 0 || page >= pageCount(totalItems, pageSize)) {
            return 0;
        }
        int start = page * pageSize;
        return Math.min(pageSize, totalItems - start);
    }
}
