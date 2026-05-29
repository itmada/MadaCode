---
name: simplify
description: Review changed code for reuse, quality, and efficiency, then fix issues found
when_to_use: User asks to refactor, simplify, or clean up code
tags: [code, refactor, quality]
mode: inline
allowed_tools: [file_read, edit, grep, glob, bash]
disallowed_tools: [agent]
---

# Simplify: Code Review and Cleanup

Review all changed files for reuse, quality, and efficiency. Fix any issues found.

## Phase 1: Identify Changes

Run `git diff` (or `git diff HEAD` if there are staged changes) to see what changed.

## Phase 2: Review

### Code Reuse
- Search for existing utilities that could replace new code.
- Flag duplicate functionality — suggest the existing function.
- Flag inline logic that could use an existing utility.

### Code Quality
- Redundant state or cached values that could be derived.
- Copy-paste with slight variation — unify with a shared abstraction.
- Unnecessary comments explaining WHAT the code does.
- Raw strings where constants or enums already exist.

### Efficiency
- Redundant computations, repeated file reads.
- Independent operations run sequentially when they could be parallel.
- Unnecessary existence checks before operating (TOCTOU).

## Phase 3: Fix

Fix each issue directly. If a finding is a false positive, note it and move on.
When done, briefly summarize what was fixed (or confirm the code was already clean).

Preserve behavior. Prefer small mechanical refactors. Avoid unrelated rewrites.
