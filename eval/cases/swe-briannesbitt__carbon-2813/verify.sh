#!/usr/bin/env bash
# SWE-bench Multilingual verify: apply hidden test_patch, run harness cmds, grade F2P/P2P.
set -euo pipefail
CASE_DIR="$(cd "$(dirname "$0")" && pwd)"
# Prefer language toolchains close to SWE-bench Harbor images.
export PATH="/opt/homebrew/bin:/usr/local/go/bin:$HOME/.cargo/bin:$PATH"
export NO_PROXY="*"
export no_proxy="*"
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy || true
for candidate in \
  "/opt/homebrew/opt/php@8.3/bin" \
  "/usr/local/opt/php@8.3/bin"; do
  if [ -x "$candidate/php" ]; then
    export PATH="$candidate:$PATH"
    break
  fi
done
echo "+ php $(php -v | head -1)"

# Resolve grader: sibling of cases/ when running on host; optional copy under CASE_DIR.
GRADE_PY=""
for candidate in \
  "$CASE_DIR/../../scripts/swe_grade_log.py" \
  "$CASE_DIR/swe_grade_log.py" \
  "/judge/swe_grade_log.py"; do
  if [ -f "$candidate" ]; then
    GRADE_PY="$candidate"
    break
  fi
done
if [ -z "$GRADE_PY" ]; then
  echo "MADA_EVAL_SETUP_ERROR: swe_grade_log.py not found" >&2
  exit 2
fi

run_setup() {
  echo "+ $1"
  if ! bash -c "$1"; then
    echo "MADA_EVAL_SETUP_ERROR: setup command failed: $1" >&2
    exit 2
  fi
}

LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT
: >"$LOG"
run_test() {
  echo "+ $1"
  set +e
  bash -c "$1" >>"$LOG" 2>&1
  set -e
}

if command -v git >/dev/null 2>&1; then
  if ! git apply --check --whitespace=nowarn "$CASE_DIR/test.patch"; then
    echo "MADA_EVAL_TEST_PATCH_CONFLICT: hidden test patch does not apply" >&2
    exit 3
  fi
  git apply --whitespace=nowarn "$CASE_DIR/test.patch"
else
  if ! patch --dry-run -p1 < "$CASE_DIR/test.patch"; then
    echo "MADA_EVAL_TEST_PATCH_CONFLICT: hidden test patch does not apply" >&2
    exit 3
  fi
  patch -p1 < "$CASE_DIR/test.patch"
fi

run_setup "composer update"
run_setup "composer install"
run_setup "composer update"
run_setup "composer install"
run_test "vendor/bin/phpunit --testdox --colors=never tests/Factory/FactoryTest.php"

cat "$LOG"
python3 "$GRADE_PY" "$CASE_DIR/harness.json" "$LOG"
