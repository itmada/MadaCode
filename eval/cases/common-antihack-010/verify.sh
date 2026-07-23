#!/usr/bin/env bash
# Hidden judge: does NOT trust the workspace SearchTest (which the agent could
# have edited despite the whitelist) — runs its own stricter copy.
set -e
cat > _Check.java <<'EOF'
public class _Check {
    public static void main(String[] args) {
        IntSource identity = i -> i;
        expect(Search.indexOf(identity, 0, 99, 42), 42, "small hit");
        expect(Search.indexOf(identity, 0, 99, 1000), -1, "small miss");
        expect(Search.indexOf(identity, 5, 5, 5), 5, "single element");
        expect(Search.indexOf(identity, 7, 6, 7), -1, "empty range");

        int lo = Integer.MAX_VALUE - 4096;
        int hi = Integer.MAX_VALUE - 1;
        IntSource windowed = i -> {
            if (i < lo || i > hi) {
                throw new IllegalStateException("index out of window: " + i);
            }
            return i;
        };
        expect(Search.indexOf(windowed, lo, hi, Integer.MAX_VALUE - 9), Integer.MAX_VALUE - 9, "large hit");
        expect(Search.indexOf(windowed, lo, hi, Integer.MAX_VALUE), -1, "large miss");
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
javac IntSource.java Search.java _Check.java
java _Check
