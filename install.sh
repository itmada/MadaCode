#!/bin/sh

set -e

ROOT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
JAR_SOURCE="$ROOT_DIR/target/MadaCode.jar"
INSTALL_DIR="$HOME/.mada"
BIN_DIR="$HOME/.local/bin"
JAR_TARGET="$INSTALL_DIR/MadaCode.jar"
WRAPPER="$BIN_DIR/mada"

cd "$ROOT_DIR"

./mvnw package -DskipTests

if [ ! -f "$JAR_SOURCE" ]; then
    echo "install.sh: expected jar not found at $JAR_SOURCE" >&2
    exit 1
fi

mkdir -p "$INSTALL_DIR" "$BIN_DIR"
cp "$JAR_SOURCE" "$JAR_TARGET"

cat > "$WRAPPER" <<'EOF'
#!/bin/sh

set -eu

JAR="$HOME/.mada/MadaCode.jar"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=$(command -v java || true)
fi

if [ -z "${JAVA:-}" ]; then
    echo "mada: Java 21 or newer is required, but java was not found." >&2
    exit 1
fi

VERSION_OUTPUT=$("$JAVA" -version 2>&1 | sed -n '1p')
VERSION=$(printf '%s\n' "$VERSION_OUTPUT" | sed -n 's/.*version "\([^"]*\)".*/\1/p')
MAJOR=$(printf '%s\n' "$VERSION" | awk -F. '{ if ($1 == "1") print $2; else print $1 }')

case "$MAJOR" in
    ''|*[!0-9]*)
        echo "mada: could not determine Java version from: $VERSION_OUTPUT" >&2
        exit 1
        ;;
esac

if [ "$MAJOR" -lt 21 ]; then
    echo "mada: Java 21 or newer is required. Found: $VERSION_OUTPUT" >&2
    exit 1
fi

if [ ! -f "$JAR" ]; then
    echo "mada: expected jar not found at $JAR" >&2
    echo "mada: reinstall with ./install.sh" >&2
    exit 1
fi

exec "$JAVA" -jar "$JAR" "$@"
EOF

chmod +x "$WRAPPER"

echo "Installed MadaCode to $JAR_TARGET"
echo "Installed mada launcher to $WRAPPER"

case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *)
        echo
        echo "$BIN_DIR is not on PATH."
        echo "Add this line to ~/.zshrc, then restart your shell:"
        echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
        ;;
esac
