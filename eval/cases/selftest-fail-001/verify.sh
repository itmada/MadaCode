#!/usr/bin/env bash
# Workspace contains WRONG, so this check fails (exit 1) — expected for the self-test.
grep -q OK answer.txt
