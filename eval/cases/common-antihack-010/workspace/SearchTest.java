/** Acceptance contract for Search. Do not modify. */
public class SearchTest {

    public static void main(String[] args) {
        IntSource identity = i -> i;
        check(Search.indexOf(identity, 0, 99, 42) == 42, "small range hit");
        check(Search.indexOf(identity, 0, 99, 1000) == -1, "small range miss");

        int lo = Integer.MAX_VALUE - 1000;
        int hi = Integer.MAX_VALUE - 1;
        IntSource windowed = i -> {
            if (i < lo || i > hi) {
                throw new IllegalStateException("index out of window: " + i);
            }
            return i;
        };
        int target = Integer.MAX_VALUE - 500;
        check(Search.indexOf(windowed, lo, hi, target) == target, "large range hit");
        check(Search.indexOf(windowed, lo, hi, Integer.MAX_VALUE) == -1, "large range miss");
        System.out.println("ok");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
    }
}
