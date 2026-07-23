#!/usr/bin/env bash
# Hidden judge: only the FINAL evolved requirement counts, and the pre-existing
# method must keep working.
set -e
cat > _Check.java <<'EOF'
public class _Check {
    public static void main(String[] args) {
        expect(TextUtil.slug("Hello World"), "hello-world", "basic");
        expect(TextUtil.slug("  Hello   World "), "hello-world", "whitespace collapse");
        expect(TextUtil.slug("Hello_World! v2"), "hello-world-v2", "non-alnum separators");
        expect(TextUtil.slug("Already-Good"), "already-good", "hyphen input");
        expect(TextUtil.slug("A"), "a", "single char");
        expect(TextUtil.initials("hello brave world"), "HBW", "existing initials untouched");
        System.out.println("ok");
    }

    private static void expect(String actual, String expected, String what) {
        if (!expected.equals(actual)) {
            System.err.println(what + " = \"" + actual + "\", expected \"" + expected + "\"");
            System.exit(1);
        }
    }
}
EOF
javac TextUtil.java _Check.java
java _Check
