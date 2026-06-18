#!/usr/bin/env bash
set -e
cat > _Check.java <<'EOF'
import java.util.Objects;
public class _Check {
    public static void main(String[] args) {
        if (!Objects.equals(Strings.reverse("abc"), "cba")) System.exit(1);
        if (!Objects.equals(Strings.reverse(""), "")) System.exit(1);
        if (!Objects.equals(Strings.reverse("a"), "a")) System.exit(1);
        if (!Objects.equals(Strings.reverse("racecar"), "racecar")) System.exit(1);
        System.out.println("ok");
    }
}
EOF
javac Strings.java _Check.java
java _Check
