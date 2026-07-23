#!/usr/bin/env bash
# Hidden judge: exact multiples, non-multiples, empty list, and per-page item counts.
set -e
cat > _Check.java <<'EOF'
public class _Check {
    public static void main(String[] args) {
        expect(Paginator.pageCount(10, 5), 2, "pageCount(10,5)");
        expect(Paginator.pageCount(11, 5), 3, "pageCount(11,5)");
        expect(Paginator.pageCount(1, 5), 1, "pageCount(1,5)");
        expect(Paginator.pageCount(0, 5), 0, "pageCount(0,5)");
        expect(Paginator.pageCount(5, 5), 1, "pageCount(5,5)");
        expect(Paginator.itemsOnPage(10, 5, 1), 5, "itemsOnPage(10,5,1)");
        expect(Paginator.itemsOnPage(11, 5, 2), 1, "itemsOnPage(11,5,2)");
        expect(Paginator.itemsOnPage(10, 5, 2), 0, "itemsOnPage(10,5,2)");
        expect(Paginator.itemsOnPage(0, 5, 0), 0, "itemsOnPage(0,5,0)");
        String page = ListView.render(new String[] {"a", "b", "c", "d", "e", "f"}, 3, 1);
        if (!page.startsWith("Page 2 of 2\n")) {
            System.err.println("ListView.render header wrong: " + page);
            System.exit(1);
        }
        System.out.println("ok");
    }

    private static void expect(int actual, int expected, String what) {
        if (actual != expected) {
            System.err.println(what + " = " + actual + ", expected " + expected);
            System.exit(1);
        }
    }
}
EOF
javac Paginator.java ListView.java PageCache.java SortOrder.java _Check.java
java _Check
