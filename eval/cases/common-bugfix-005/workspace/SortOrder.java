/** Sort options for list views. */
public enum SortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
    ALPHABETICAL;

    public SortOrder reversed() {
        return switch (this) {
            case NEWEST_FIRST -> OLDEST_FIRST;
            case OLDEST_FIRST -> NEWEST_FIRST;
            case ALPHABETICAL -> ALPHABETICAL;
        };
    }
}
