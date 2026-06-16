#!/bin/sh

set -eu

say() {
    printf '%s\n' "$*"
}

warn() {
    printf 'install.sh: warning: %s\n' "$*" >&2
}

fail() {
    printf 'install.sh: %s\n' "$*" >&2
    exit 1
}

find_on_path() {
    cmd=$1
    old_ifs=$IFS
    IFS=:
    for dir in ${PATH:-}; do
        IFS=$old_ifs
        [ -n "$dir" ] || dir=.
        if [ -x "$dir/$cmd" ]; then
            printf '%s\n' "$dir/$cmd"
            return 0
        fi
        IFS=:
    done
    IFS=$old_ifs
    return 1
}

cleanup() {
    rm -f "${JAR_TMP:-}" "${WRAPPER_TMP:-}"
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 129' HUP
trap 'cleanup; exit 143' TERM

ROOT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
JAR_SOURCE="$ROOT_DIR/target/MadaCode.jar"
INSTALL_DIR="$HOME/.mada"
BIN_DIR="$HOME/.local/bin"
JAR_TARGET="$INSTALL_DIR/MadaCode.jar"
JAR_TMP="$INSTALL_DIR/MadaCode.jar.tmp.$$"
WRAPPER="$BIN_DIR/mada"
WRAPPER_TMP="$BIN_DIR/mada.tmp.$$"

cd "$ROOT_DIR"

[ -x "$ROOT_DIR/mvnw" ] || fail "Maven wrapper is missing or not executable: $ROOT_DIR/mvnw"

say "Building MadaCode..."
./mvnw package -DskipTests

[ -f "$JAR_SOURCE" ] || fail "expected jar not found at $JAR_SOURCE"

mkdir -p "$INSTALL_DIR" "$BIN_DIR"
cp "$JAR_SOURCE" "$JAR_TMP"
mv "$JAR_TMP" "$JAR_TARGET"

cat > "$WRAPPER_TMP" <<'EOF'
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

chmod +x "$WRAPPER_TMP"
mv "$WRAPPER_TMP" "$WRAPPER"
chmod +x "$WRAPPER"

"$WRAPPER" --help >/dev/null || fail "installed launcher failed self-check: $WRAPPER --help"

say "Installed MadaCode to $JAR_TARGET"
say "Installed mada launcher to $WRAPPER"

case ":$PATH:" in
    *":$BIN_DIR:"*)
        RESOLVED=$(find_on_path mada || true)
        if [ "$RESOLVED" != "$WRAPPER" ]; then
            warn "PATH resolves 'mada' to ${RESOLVED:-nothing}, not $WRAPPER"
            warn "Move $BIN_DIR earlier in PATH or remove the conflicting launcher."
        fi
        ;;
    *)
        say ""
        warn "$BIN_DIR is not on PATH, so 'mada' may not be found."
        say "Add this line to your shell profile, then restart your shell:"
        say "  export PATH=\"\$HOME/.local/bin:\$PATH\""
        ;;
esac

say ""
say "Try:"
say "  mada --new       # start a new session"
say "  mada --continue  # continue the latest session"
say "  mada             # open the interactive startup selector"
say ""
say "If your shell still runs an older mada, run 'hash -r' or restart the shell."
