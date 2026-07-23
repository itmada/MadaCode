#!/usr/bin/env bash
# Read-only investigation: VERIFY asserts the workspace is byte-identical to the
# starting state. The comprehension answer itself lives in finalText (not gated
# deterministically); the gate here is trajectory + no mutation.
set -e
sha() { find . -type f ! -name '_expected.sha' -print0 | sort -z | xargs -0 shasum -a 256 | shasum -a 256 | cut -d' ' -f1; }
expected="235c2e6e98aad7437f4faf73056aa46b77e359a6010e684139e03bf9e7a06714"
actual="$(sha)"
if [ "$actual" != "$expected" ]; then
  echo "workspace was modified (hash $actual, expected $expected)" >&2
  exit 1
fi
echo ok
