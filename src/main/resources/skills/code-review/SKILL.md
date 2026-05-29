---
name: code-review
description: Review code changes for bugs, regressions, missing tests, and risky behavior
when_to_use: User asks for code review, quality check, or to review staged/unstaged changes
tags: [code, review, quality]
mode: inline
allowed_tools: [file_read, grep, glob, bash]
disallowed_tools: [write, edit]
---

# Code Review

Review the changes described in the task. Follow this structure:

## Phase 1: Identify Changes

Run `git diff` (or `git diff HEAD` if there are staged changes) to see what changed.
If there are no git changes, review the files mentioned in the task.

## Phase 2: Review

For each change, check:

1. **Correctness**: Could this change introduce bugs? Are edge cases handled?
2. **Regressions**: Could this break existing behavior? Check callers and dependents.
3. **Test gaps**: Are there tests covering the changed code? Are new tests needed?
4. **Security**: Any risk of injection, path traversal, unsafe deserialization?
5. **Error handling**: Are exceptions caught appropriately? Are error messages clear?

## Phase 3: Report

Return findings ordered by severity:
- [P1] Critical — likely bug or security issue
- [P2] Important — missing test, error handling gap
- [P3] Nice-to-have — naming, style, minor improvements

Include:
- **Findings**: each with severity, file, line reference, and explanation
- **Open Questions**: things you couldn't determine
- **Summary**: overall assessment (safe / needs fixes / blocked)
- **Test Gaps**: what should be tested

Do NOT modify files unless the task explicitly asks you to fix issues.
