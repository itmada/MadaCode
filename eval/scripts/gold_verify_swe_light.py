#!/usr/bin/env python3
"""Apply gold.patch into a temp copy of each swe-* workspace and run verify.sh."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CASES = ROOT / "eval" / "cases"


def run_one(case_dir: Path, timeout: int) -> tuple[str, int, str]:
    workspace = case_dir / "workspace"
    verify = case_dir / "verify.sh"
    gold = case_dir / "gold.patch"
    if not workspace.is_dir() or not verify.is_file() or not gold.is_file():
        return case_dir.name, 2, "missing workspace/verify/gold"
    with tempfile.TemporaryDirectory(prefix=f"gold-{case_dir.name}-") as tmp:
        tmp_path = Path(tmp)
        shutil.copytree(workspace, tmp_path, dirs_exist_ok=True)
        # Apply gold into the agent-facing tree before verify applies test.patch.
        apply = subprocess.run(
            ["git", "apply", "--whitespace=nowarn", str(gold.resolve())],
            cwd=tmp_path,
            capture_output=True,
            text=True,
        )
        if apply.returncode != 0:
            apply = subprocess.run(
                ["patch", "-p1", "-i", str(gold.resolve())],
                cwd=tmp_path,
                capture_output=True,
                text=True,
            )
        if apply.returncode != 0:
            return case_dir.name, 3, f"gold apply failed:\n{apply.stdout}\n{apply.stderr}"
        proc = subprocess.run(
            ["bash", str(verify.resolve())],
            cwd=tmp_path,
            capture_output=True,
            text=True,
            timeout=timeout,
            env={**dict(**{k: v for k, v in __import__("os").environ.items()}), "HOME": __import__("os").environ.get("HOME", "")},
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        return case_dir.name, proc.returncode, out[-8000:]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("cases", nargs="*", help="optional case ids")
    ap.add_argument("--timeout", type=int, default=1800)
    args = ap.parse_args()
    case_dirs = sorted(CASES.glob("swe-*"))
    if args.cases:
        wanted = set(args.cases)
        case_dirs = [d for d in case_dirs if d.name in wanted]
    failures = []
    for case_dir in case_dirs:
        print(f"== gold verify {case_dir.name}", flush=True)
        try:
            name, code, out = run_one(case_dir, args.timeout)
        except subprocess.TimeoutExpired:
            name, code, out = case_dir.name, 124, "TIMEOUT"
        status = "PASS" if code == 0 else "FAIL"
        print(f"   {status} exit={code}", flush=True)
        if code != 0:
            failures.append(name)
            print(out[-4000:], flush=True)
    print(f"\nsummary: {len(case_dirs) - len(failures)}/{len(case_dirs)} passed")
    if failures:
        print("failed:", ", ".join(failures))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
