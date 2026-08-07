#!/usr/bin/env python3
"""Grade a SWE-bench Multilingual verify log against FAIL_TO_PASS / PASS_TO_PASS."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def parse_go(log: str) -> dict[str, str]:
    out = {}
    for line in log.splitlines():
        m = re.match(r"^--- (PASS|FAIL|SKIP): (.+) \(", line.strip())
        if m:
            out[m.group(2)] = {"PASS": "PASSED", "FAIL": "FAILED", "SKIP": "SKIPPED"}[m.group(1)]
    return out


def parse_mocha_tap(log: str) -> dict[str, str]:
    out = {}
    for line in log.splitlines():
        m = re.match(r"^(ok|not ok) \d+ (.+)$", line.strip())
        if not m:
            continue
        name = m.group(2).strip()
        # Drop trailing timing annotations if present.
        name = re.sub(r"\s+#.*$", "", name)
        out[name] = "PASSED" if m.group(1) == "ok" else "FAILED"
    return out


def parse_jest_verbose(log: str) -> dict[str, str]:
    """Best-effort parser for jest --verbose / nested suites."""
    out = {}
    suite: list[tuple[str, int]] = []
    for line in log.splitlines():
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        stripped = line.strip()
        if stripped.startswith("✓") or stripped.startswith("✕"):
            passed = stripped.startswith("✓")
            title = re.sub(r"^[✓✕]\s*", "", stripped)
            title = re.sub(r"\s*\(\d+\s*ms\)$", "", title).strip()
            while suite and suite[-1][1] >= indent:
                suite.pop()
            name = " > ".join([s[0] for s in suite] + [title])
            out[name] = "PASSED" if passed else "FAILED"
        elif stripped.startswith("●"):
            continue
        elif indent > 0 and not stripped.startswith(("PASS", "FAIL", "Test Suites", "Tests:")):
            while suite and suite[-1][1] >= indent:
                suite.pop()
            suite.append((stripped, indent))
    return out


def parse_phpunit_testdox(log: str) -> dict[str, str]:
    out = {}
    suite = ""
    for line in log.splitlines():
        if re.match(r"^[A-Za-z].*", line) and not line.startswith(("✔", "✘", "✓", "✕", " ", "\t")):
            # Suite header like "Round" or "Round (Tests\\Carbon\\Round)"
            if not line.startswith(("PHPUnit", "Time:", "OK", "FAILURES", "There", "WARN", "Warning", "Suggestion")):
                suite = re.sub(r"\s*\(.*\)\s*$", "", line.strip())
            continue
        m = re.match(r"^\s*[✔✓]\s+(.+)$", line)
        if m:
            case = m.group(1).strip()
            out[f"{suite} > {case}" if suite else case] = "PASSED"
            continue
        m = re.match(r"^\s*[✘✕]\s+(.+)$", line)
        if m:
            case = m.group(1).strip()
            out[f"{suite} > {case}" if suite else case] = "FAILED"
    return out


def parse_cargo(log: str) -> dict[str, str]:
    out = {}
    for line in log.splitlines():
        m = re.match(r"^test\s+(.+)\s+\.\.\.\s+(ok|FAILED|ignored)", line.strip())
        if m:
            status = {"ok": "PASSED", "FAILED": "FAILED", "ignored": "SKIPPED"}[m.group(2)]
            out[m.group(1).strip()] = status
    return out


PARSERS = {
    "go": parse_go,
    "javascript": parse_mocha_tap,  # axios mocha; immutable overrides below
    "php": parse_phpunit_testdox,
    "rust": parse_cargo,
}


def status_of(parsed: dict[str, str], name: str) -> str | None:
    if name in parsed:
        return parsed[name]
    # Soft match: allow suffix/prefix differences across reporters.
    for key, status in parsed.items():
        if key.endswith(name) or name.endswith(key) or name in key or key in name:
            return status
    return None


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: swe_grade_log.py <harness.json> <log.txt>", file=sys.stderr)
        return 2
    harness = json.loads(Path(sys.argv[1]).read_text())
    log = Path(sys.argv[2]).read_text(errors="replace")
    lang = harness["language"]
    repo = harness["repo"]
    if repo == "immutable-js/immutable-js":
        parsed = parse_jest_verbose(log)
        # Also accept bracketed Harbor jq format: [PASSED] name
        for line in log.splitlines():
            m = re.match(r"^\[(PASSED|FAILED|PENDING)\]\s+(.+)$", line.strip())
            if m:
                parsed[m.group(2)] = m.group(1)
    else:
        parsed = PARSERS[lang](log)

    missing = []
    failed = []
    for name in list(harness.get("FAIL_TO_PASS") or []) + list(harness.get("PASS_TO_PASS") or []):
        status = status_of(parsed, name)
        if status is None:
            missing.append(name)
        elif status != "PASSED":
            failed.append(f"{name}={status}")

    if missing or failed:
        print("GRADE FAIL")
        if missing:
            print("missing:", *missing, sep="\n  ")
        if failed:
            print("failed:", *failed, sep="\n  ")
        print(f"parsed {len(parsed)} test results")
        return 1
    print(f"GRADE PASS ({len(parsed)} results checked against F2P/P2P)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
