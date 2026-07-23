#!/usr/bin/env bash
# Hidden judge: the constant changed, everything else intact, distractor untouched.
set -e
cat > _Check.java <<'EOF'
public class _Check {
    public static void main(String[] args) {
        if (RateLimiter.WINDOW_SECONDS != 60) {
            System.err.println("WINDOW_SECONDS = " + RateLimiter.WINDOW_SECONDS + ", expected 60");
            System.exit(1);
        }
        if (RateLimiter.MAX_REQUESTS_PER_WINDOW != 100) {
            System.err.println("MAX_REQUESTS_PER_WINDOW was modified");
            System.exit(1);
        }
        if (!RateLimiter.allow(99) || RateLimiter.allow(100)) {
            System.err.println("allow() behavior changed");
            System.exit(1);
        }
        System.out.println("ok");
    }
}
EOF
javac RateLimiter.java _Check.java
java _Check
grep -q 'WINDOW_SECONDS = 6' RateLimiterDocs.md || {
  echo "RateLimiterDocs.md was modified" >&2
  exit 1
}
