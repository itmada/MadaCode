#!/usr/bin/env bash
# Passes when answer.txt contains OK. Used by --self-test (no model).
grep -q OK answer.txt
