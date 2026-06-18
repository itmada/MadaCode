#!/usr/bin/env bash
# Compiles the agent's Calculator.java against a hidden check and asserts add() sums.
set -e
cat > _Check.java <<'EOF'
public class _Check {
    public static void main(String[] args) {
        if (Calculator.add(2, 3) != 5) System.exit(1);
        if (Calculator.add(10, 1) != 11) System.exit(1);
        if (Calculator.add(-4, 4) != 0) System.exit(1);
        System.out.println("ok");
    }
}
EOF
javac Calculator.java _Check.java
java _Check
