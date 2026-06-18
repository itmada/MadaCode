#!/usr/bin/env bash
set -e
cat > _Check.java <<'EOF'
public class _Check {
    public static void main(String[] args) {
        if (Math2.square(5) != 25) System.exit(1);
        if (Math2.square(0) != 0) System.exit(1);
        if (Math2.cube(3) != 27) System.exit(1);
        if (Math2.cube(2) != 8) System.exit(1);
        System.out.println("ok");
    }
}
EOF
javac Math2.java _Check.java
java _Check
