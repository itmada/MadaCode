#!/usr/bin/env bash
# Hidden judge: both the original behavior AND the new one must pass.
set -e
cat > _Check.java <<'EOF'
public class _Check {
    public static void main(String[] args) {
        // pre-existing behavior that must keep working
        expect(MathUtils.clamp(5, 1, 10), 5, "clamp mid");
        expect(MathUtils.clamp(0, 1, 10), 1, "clamp below");
        expect(MathUtils.clamp(11, 1, 10), 10, "clamp above");
        expect(MathUtils.factorial(0), 1, "factorial 0");
        expect(MathUtils.factorial(5), 120, "factorial 5");
        // newly required
        expect(MathUtils.gcd(12, 8), 4, "gcd(12,8)");
        expect(MathUtils.gcd(7, 13), 1, "gcd(7,13)");
        expect(MathUtils.gcd(0, 5), 5, "gcd(0,5)");
        expect(MathUtils.gcd(18, 0), 18, "gcd(18,0)");
        expect(MathUtils.lcm(4, 6), 12, "lcm(4,6)");
        expect(MathUtils.lcm(1, 7), 7, "lcm(1,7)");
        expect(MathUtils.isPrime(2), true, "isPrime(2)");
        expect(MathUtils.isPrime(1), false, "isPrime(1)");
        expect(MathUtils.isPrime(17), true, "isPrime(17)");
        expect(MathUtils.isPrime(18), false, "isPrime(18)");
        System.out.println("ok");
    }

    private static void expect(int actual, int expected, String what) {
        if (actual != expected) {
            System.err.println(what + " = " + actual + ", expected " + expected);
            System.exit(1);
        }
    }

    private static void expect(boolean actual, boolean expected, String what) {
        if (actual != expected) {
            System.err.println(what + " = " + actual + ", expected " + expected);
            System.exit(1);
        }
    }
}
EOF
javac MathUtils.java _Check.java
java _Check
