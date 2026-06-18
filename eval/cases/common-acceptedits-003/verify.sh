#!/usr/bin/env bash
set -e
cat > _Check.java <<'EOF'
public class _Check {
    public static void main(String[] args) {
        if (Config.MAX != 100) System.exit(1);
        System.out.println("ok");
    }
}
EOF
javac Config.java _Check.java
java _Check
