#!/bin/sh
# Non-production spike for the T8 container attempt file protocol.
# Usage: sh eval/docker/spike-container-attempt.sh [work-dir]

set -eu

DOCKER=${DOCKER:-docker}
IMAGE=${EVAL_SPIKE_IMAGE:-alpine:latest}
WORK_DIR=${1:-$(mktemp -d "${TMPDIR:-/tmp}/mada-eval-container-spike.XXXXXX")}

if ! command -v "$DOCKER" >/dev/null 2>&1; then
    echo "eval container spike: Docker command '$DOCKER' was not found" >&2
    exit 2
fi

if ! "$DOCKER" info >/dev/null 2>&1; then
    echo "eval container spike: Docker is not available; start Docker or set DOCKER to a compatible CLI" >&2
    exit 2
fi

mkdir -p "$WORK_DIR/input" "$WORK_DIR/output" "$WORK_DIR/workspace"
cat > "$WORK_DIR/input/attempt.json" <<'JSON'
{
  "caseId": "spike-noop",
  "mode": "common",
  "instruction": "write an outcome DTO without using a model"
}
JSON

"$DOCKER" run --rm \
    --network none \
    -v "$WORK_DIR/input:/mada/input:ro" \
    -v "$WORK_DIR/output:/mada/output:rw" \
    -v "$WORK_DIR/workspace:/mada/workspace:rw" \
    "$IMAGE" sh -eu -c 'test -r /mada/input/attempt.json
cat > /mada/output/outcome.json <<'"'"'JSON'"'"'
{
  "schemaVersion": "spike-1",
  "caseId": "spike-noop",
  "mode": "common",
  "executionStatus": "COMPLETED",
  "terminalSummary": "COMPLETED",
  "detail": "container spike wrote a stable AttemptExecutionResultJson DTO",
  "finalText": "ok",
  "metrics": {
    "controlIterations": 0,
    "workerIterations": 0,
    "totalIterations": 0,
    "workerCycles": 0,
    "toolCalls": 0,
    "tokenUsage": {
      "inputTokens": 0,
      "outputTokens": 0,
      "cacheCreationTokens": 0,
      "cacheReadTokens": 0,
      "totalTokens": 0
    }
  },
  "apiFailure": null,
  "quiescent": true,
  "trace": {
    "invocations": [],
    "fileEffects": [],
    "userTurns": [],
    "assistantTurns": [],
    "finalText": "ok",
    "metrics": {
      "controlIterations": 0,
      "workerIterations": 0,
      "totalIterations": 0,
      "workerCycles": 0,
      "toolCalls": 0,
      "tokenUsage": {
        "inputTokens": 0,
        "outputTokens": 0,
        "cacheCreationTokens": 0,
        "cacheReadTokens": 0,
        "totalTokens": 0
      }
    }
  },
  "diagnostics": ["docker file-protocol spike"]
}
JSON'

if [ ! -s "$WORK_DIR/output/outcome.json" ]; then
    echo "eval container spike: container exited without writing output/outcome.json" >&2
    exit 1
fi

echo "eval container spike: outcome written to $WORK_DIR/output/outcome.json"
