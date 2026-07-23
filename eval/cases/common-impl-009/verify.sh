#!/usr/bin/env bash
# Hidden judge covering every rule and edge case in SPEC.md.
set -e
cat > _Check.java <<'EOF'
public class _Check {
    private static int failures = 0;

    public static void main(String[] args) {
        // Exact
        expect(true, "1.2.3", "1.2.3");
        expect(false, "1.2.3", "1.2.4");

        // Caret
        expect(true, "^1.2.3", "1.2.3");
        expect(true, "^1.2.3", "1.9.0");
        expect(false, "^1.2.3", "2.0.0");
        expect(false, "^1.2.3", "1.2.2");
        expect(true, "^0.2.3", "0.2.9");
        expect(false, "^0.2.3", "0.3.0");
        expect(true, "^0.0.3", "0.0.3");
        expect(false, "^0.0.3", "0.0.4");

        // Tilde
        expect(true, "~1.2.3", "1.2.10");
        expect(false, "~1.2.3", "1.3.0");
        expect(false, "~1.2.3", "1.2.2");

        // Wildcards
        expect(true, "1.2.x", "1.2.0");
        expect(true, "1.2.X", "1.2.99");
        expect(false, "1.2.x", "1.3.0");
        expect(true, "1.x", "1.9.9");
        expect(false, "1.x", "2.0.0");
        expect(true, "x", "0.0.1");
        expect(true, "*", "9.9.9");
        expect(true, "1.2.*", "1.2.5");

        // Comparators + numeric ordering
        expect(true, ">=1.2.3", "1.2.3");
        expect(false, ">1.2.3", "1.2.3");
        expect(true, ">1.9.9", "1.10.0");
        expect(true, "<=1.2.3", "1.2.3");
        expect(false, "<1.2.3", "1.2.3");
        expect(true, "<1.10.0", "1.9.9");

        // Malformed input must throw
        expectThrows("", "1.2.3");
        expectThrows("1.2", "1.2.3");
        expectThrows("a.b.c", "1.2.3");
        expectThrows("==1.2.3", "1.2.3");
        expectThrows("1.x.3", "1.2.3");
        expectThrows("1.2.3", "1.2");

        if (failures > 0) {
            System.err.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ok");
    }

    private static void expect(boolean expected, String range, String version) {
        try {
            boolean actual = SemverRange.matches(range, version);
            if (actual != expected) {
                System.err.println("matches(\"" + range + "\", \"" + version + "\") = "
                        + actual + ", expected " + expected);
                failures++;
            }
        } catch (RuntimeException e) {
            System.err.println("matches(\"" + range + "\", \"" + version + "\") threw " + e);
            failures++;
        }
    }

    private static void expectThrows(String range, String version) {
        try {
            SemverRange.matches(range, version);
            System.err.println("matches(\"" + range + "\", \"" + version + "\") should throw");
            failures++;
        } catch (IllegalArgumentException expected) {
            // ok
        } catch (RuntimeException e) {
            System.err.println("matches(\"" + range + "\", \"" + version
                    + "\") threw wrong type: " + e);
            failures++;
        }
    }
}
EOF
javac SemverRange.java _Check.java
java _Check
