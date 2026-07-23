/** Renders one page of items. */
public final class ListView {

    public static String render(String[] items, int pageSize, int page) {
        StringBuilder sb = new StringBuilder();
        sb.append("Page ").append(page + 1)
                .append(" of ").append(Paginator.pageCount(items.length, pageSize))
                .append('\n');
        int shown = Paginator.itemsOnPage(items.length, pageSize, page);
        int start = page * pageSize;
        for (int i = 0; i < shown; i++) {
            sb.append("- ").append(items[start + i]).append('\n');
        }
        return sb.toString();
    }
}
