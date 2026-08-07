#!/usr/bin/env python3
"""Materialize SWE-bench Multilingual light-suite cases for MadaCode eval.

Repos covered: gin, axios, immutable-js, carbon, bat, ripgrep (36 instances).

Writes under eval/cases/swe-<instance_id>/:
  case.json, verify.sh, test.patch, gold.patch, harness.json, workspace/

Bulky trees (workspace/) are gitignored; re-run this script after clone.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / "eval" / "_cache"
CASES = ROOT / "eval" / "cases"
INSTANCES = CACHE / "swe_multilingual_light.json"
SPECS = CACHE / "harness" / "specs_light.json"

REPO_URLS = {
    "gin-gonic/gin": "https://github.com/gin-gonic/gin.git",
    "axios/axios": "https://github.com/axios/axios.git",
    "immutable-js/immutable-js": "https://github.com/immutable-js/immutable-js.git",
    "briannesbitt/carbon": "https://github.com/briannesbitt/Carbon.git",
    "sharkdp/bat": "https://github.com/sharkdp/bat.git",
    "burntsushi/ripgrep": "https://github.com/BurntSushi/ripgrep.git",
}

LANG = {
    "gin-gonic/gin": "go",
    "axios/axios": "javascript",
    "immutable-js/immutable-js": "javascript",
    "briannesbitt/carbon": "php",
    "sharkdp/bat": "rust",
    "burntsushi/ripgrep": "rust",
}


def run(cmd: list[str], **kwargs) -> None:
    print("+", " ".join(cmd), flush=True)
    subprocess.run(cmd, check=True, **kwargs)


def ensure_repo(repo: str) -> Path:
    dest = CACHE / "repos" / repo.replace("/", "__")
    if (dest / ".git").is_dir():
        run(["git", "-C", str(dest), "fetch", "--tags", "--force", "origin"])
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    run(["git", "clone", REPO_URLS[repo], str(dest)])
    return dest


def export_tree(repo_dir: Path, commit: str, dest: Path) -> None:
    """Export a commit as a plain tree.

    Prefer a detached worktree over ``git archive`` so ``export-ignore`` rules
    (e.g. Carbon's ``/tests``) cannot strip files needed by verify.
    """
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    probe = subprocess.run(
        ["git", "-C", str(repo_dir), "cat-file", "-e", f"{commit}^{{commit}}"],
        capture_output=True,
    )
    if probe.returncode != 0:
        run(["git", "-C", str(repo_dir), "fetch", "--depth", "1", "origin", commit])

    wt = CACHE / "worktrees" / f"{repo_dir.name}-{commit[:12]}"
    if wt.exists():
        shutil.rmtree(wt)
    wt.parent.mkdir(parents=True, exist_ok=True)
    try:
        run(["git", "-C", str(repo_dir), "worktree", "add", "--detach", str(wt), commit])
        # Copy contents without the worktree gitlink.
        # Follow symlinks so EvalCaseLoader (no symlinks in case trees) accepts the workspace.
        for item in wt.iterdir():
            if item.name == ".git":
                continue
            target = dest / item.name
            if item.is_dir() and not item.is_symlink():
                shutil.copytree(item, target, symlinks=False)
            elif item.is_symlink() and item.resolve().is_dir():
                shutil.copytree(item.resolve(), target, symlinks=False)
            else:
                shutil.copy2(item, target, follow_symlinks=True)
    finally:
        subprocess.run(
            ["git", "-C", str(repo_dir), "worktree", "remove", "--force", str(wt)],
            check=False,
            capture_output=True,
        )
        subprocess.run(
            ["git", "-C", str(repo_dir), "worktree", "prune"],
            check=False,
            capture_output=True,
        )


def pr_number(instance_id: str) -> str:
    return instance_id.rsplit("-", 1)[-1]


def normalize_cmds(cmds) -> list[str]:
    if cmds is None:
        return []
    if isinstance(cmds, str):
        return [cmds]
    return list(cmds)


def adjust_cmds(instance_id: str, repo: str, cmds: list[str]) -> list[str]:
    out = []
    for cmd in cmds:
        # Exit-code friendly jest for immutable-js-2005 (official pipes to jq for Harbor logs).
        if instance_id == "immutable-js__immutable-js-2005" and "jest" in cmd and "|" in cmd:
            cmd = (
                "npx jest __tests__/OrderedMap.ts __tests__/OrderedSet.ts --verbose"
            )
        # Archive workspaces have no .git; axios prepare/husky otherwise fails.
        if repo == "axios/axios" and cmd.strip() in {"npm install", "npm ci"}:
            cmd = "npm install --ignore-scripts"
        # axios-4738 intentionally exercises request timeouts; Harbor wraps mocha in
        # `timeout 10s`. macOS often lacks GNU timeout, and a long mocha timeout
        # makes the suite hang — use a portable hard cap instead.
        if instance_id == "axios__axios-4738" and "mocha" in cmd:
            cmd = (
                "python3 -c \"import subprocess,sys; "
                "sys.exit(subprocess.run("
                "['npx','mocha','-R','tap','test/unit/adapters/http.js',"
                "'-g','timeout','--timeout','3000'], timeout=45"
                ").returncode)\""
            )
        else:
            # macOS often lacks GNU timeout; drop it for local verify.
            cmd = re.sub(r"^timeout\s+\S+\s+", "", cmd)
            # Local hosts are slower than Harbor images; axios mocha defaults to 2s.
            if repo == "axios/axios" and "mocha" in cmd and "--timeout" not in cmd:
                cmd = cmd + " --timeout 20000"
        out.append(cmd)
    return out


def toolchain_prologue(repo: str, docker_specs: dict) -> str:
    """Prefer Harbor-pinned toolchains when present on the host."""
    lines = [
        '# Prefer language toolchains close to SWE-bench Harbor images.',
        'export PATH="/opt/homebrew/bin:/usr/local/go/bin:$HOME/.cargo/bin:$PATH"',
        # Host HTTP proxies (e.g. 127.0.0.1:7890) break axios/gin local test servers.
        'export NO_PROXY="*"',
        'export no_proxy="*"',
        'unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy || true',
    ]
    if repo in {"axios/axios", "immutable-js/immutable-js"}:
        node_ver = str((docker_specs or {}).get("node_version") or "20")
        lines += [
            f'for candidate in \\',
            f'  "/opt/homebrew/opt/node@{node_ver}/bin" \\',
            f'  "/usr/local/opt/node@{node_ver}/bin"; do',
            '  if [ -x "$candidate/node" ]; then',
            '    export PATH="$candidate:$PATH"',
            '    break',
            '  fi',
            'done',
            'echo "+ node $(node -v) npm $(npm -v)"',
        ]
    if repo == "briannesbitt/carbon":
        php_ver = str((docker_specs or {}).get("php_version") or "8.3")
        # Harbor pins patch versions like 8.3.16; prefer the major.minor Homebrew keg.
        major_minor = ".".join(php_ver.split(".")[:2])
        lines += [
            f'for candidate in \\',
            f'  "/opt/homebrew/opt/php@{major_minor}/bin" \\',
            f'  "/usr/local/opt/php@{major_minor}/bin"; do',
            '  if [ -x "$candidate/php" ]; then',
            '    export PATH="$candidate:$PATH"',
            '    break',
            '  fi',
            'done',
            'echo "+ php $(php -v | head -1)"',
        ]
    return "\n".join(lines)


def write_verify(
    case_dir: Path,
    repo: str,
    docker_specs: dict,
    install: list[str],
    build: list[str],
    test_cmd: list[str],
) -> None:
    prep_steps = install + build
    prep_lines = "\n".join(f"run_setup {json.dumps(s)}" for s in prep_steps)
    test_lines = "\n".join(f"run_test {json.dumps(s)}" for s in test_cmd)
    prologue = toolchain_prologue(repo, docker_specs)
    # Grade script lives beside cases under eval/scripts; also copy a pointer path for docker.
    content = f"""#!/usr/bin/env bash
# SWE-bench Multilingual verify: apply hidden test_patch, run harness cmds, grade F2P/P2P.
set -euo pipefail
CASE_DIR="$(cd "$(dirname "$0")" && pwd)"
{prologue}

# Resolve grader: sibling of cases/ when running on host; optional copy under CASE_DIR.
GRADE_PY=""
for candidate in \\
  "$CASE_DIR/../../scripts/swe_grade_log.py" \\
  "$CASE_DIR/swe_grade_log.py" \\
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

run_setup() {{
  echo "+ $1"
  if ! bash -c "$1"; then
    echo "MADA_EVAL_SETUP_ERROR: setup command failed: $1" >&2
    exit 2
  fi
}}

LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT
: >"$LOG"
run_test() {{
  echo "+ $1"
  set +e
  bash -c "$1" >>"$LOG" 2>&1
  set -e
}}

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

{prep_lines}
{test_lines}

cat "$LOG"
python3 "$GRADE_PY" "$CASE_DIR/harness.json" "$LOG"
"""
    verify = case_dir / "verify.sh"
    verify.write_text(content)
    verify.chmod(0o755)
    # Keep a copy inside the case so Docker /judge mount can find it.
    shutil.copy2(ROOT / "eval" / "scripts" / "swe_grade_log.py", case_dir / "swe_grade_log.py")


def write_case(row: dict, spec: dict) -> Path:
    instance_id = row["instance_id"]
    case_id = f"swe-{instance_id}"
    case_dir = CASES / case_id
    case_dir.mkdir(parents=True, exist_ok=True)

    install = adjust_cmds(instance_id, row["repo"], normalize_cmds(spec.get("install")))
    build = adjust_cmds(instance_id, row["repo"], normalize_cmds(spec.get("build")))
    test_cmd = adjust_cmds(instance_id, row["repo"], normalize_cmds(spec.get("test_cmd")))
    instruction = (row.get("problem_statement") or "").strip()
    if not instruction:
        instruction = f"Fix the issue described in {instance_id}."

    case_json = {
        "id": case_id,
        "description": f"SWE-bench Multilingual: {instance_id}",
        "mode": "common",
        "permissionMode": "bypass",
        "capabilities": [
            "swe-bench",
            "multilingual",
            LANG[row["repo"]],
            row["repo"].replace("/", "__"),
        ],
        "instruction": instruction,
        "samples": 3,
        "maxIterations": 60,
        "timeoutSeconds": 3600,
        "verifyTimeoutSeconds": 1800,
        "maxProcessOutputBytes": 8 * 1024 * 1024,
        "repository": row["repo"],
        "baseCommit": row["base_commit"],
    }
    (case_dir / "case.json").write_text(json.dumps(case_json, indent=2, ensure_ascii=False) + "\n")
    (case_dir / "test.patch").write_text(row.get("test_patch") or "")
    (case_dir / "gold.patch").write_text(row.get("patch") or "")
    harness = {
        "instance_id": instance_id,
        "repo": row["repo"],
        "base_commit": row["base_commit"],
        "language": LANG[row["repo"]],
        "FAIL_TO_PASS": row.get("FAIL_TO_PASS") or [],
        "PASS_TO_PASS": row.get("PASS_TO_PASS") or [],
        "install": install,
        "build": build,
        "test_cmd": test_cmd,
        "docker_specs": spec.get("docker_specs") or {},
    }
    (case_dir / "harness.json").write_text(json.dumps(harness, indent=2, ensure_ascii=False) + "\n")
    write_verify(case_dir, row["repo"], harness["docker_specs"], install, build, test_cmd)
    return case_dir


def main() -> int:
    if not INSTANCES.is_file() or not SPECS.is_file():
        print("missing instance/spec cache; fetch metadata first", file=sys.stderr)
        return 1
    rows = json.loads(INSTANCES.read_text())
    specs = json.loads(SPECS.read_text())
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    meta_only = "--meta-only" in flags
    only = set(args) if args else None

    for row in rows:
        instance_id = row["instance_id"]
        if only and instance_id not in only and f"swe-{instance_id}" not in only:
            continue
        repo = row["repo"]
        pr = pr_number(instance_id)
        if pr not in specs[repo]:
            raise SystemExit(f"no harness spec for {instance_id}")
        print(f"== materialize {instance_id}", flush=True)
        existing_workspace = CASES / f"swe-{instance_id}" / "workspace"
        if meta_only and not existing_workspace.is_dir():
            print("   skipped (workspace is not materialized)", flush=True)
            continue
        case_dir = write_case(row, specs[repo][pr])
        workspace = case_dir / "workspace"
        if meta_only:
            print(f"   refreshed metadata {case_dir}", flush=True)
            continue
        repo_dir = ensure_repo(repo)
        export_tree(repo_dir, row["base_commit"], workspace)
        print(f"   wrote {case_dir}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
