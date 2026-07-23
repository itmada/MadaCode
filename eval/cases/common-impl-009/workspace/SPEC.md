# Semver range matching rules

`matches(range, version)` decides whether `version` satisfies `range`.

A version is always `MAJOR.MINOR.PATCH` with non-negative integers (no
prerelease/build suffixes in this exercise). A range is one of:

1. **Exact**: `1.2.3` — matches only that version.
2. **Caret**: `^1.2.3` — matches versions `>= 1.2.3` and `< 2.0.0`.
   - Special case major 0: `^0.2.3` matches `>= 0.2.3` and `< 0.3.0`.
   - Special case major and minor 0: `^0.0.3` matches only `0.0.3`.
3. **Tilde**: `~1.2.3` — matches `>= 1.2.3` and `< 1.3.0`.
4. **Wildcard**: `1.2.x` matches any patch of `1.2`; `1.x` matches any
   minor/patch of major `1`; a bare `x` matches every version.
   `x` may be uppercase `X` or `*`.
5. **Comparators**: `>=1.2.3`, `>1.2.3`, `<=1.2.3`, `<1.2.3` with numeric
   version-order semantics (`1.10.0` is greater than `1.9.9`).

Malformed inputs (empty string, wrong arity like `1.2`, non-numeric parts,
unknown operators) must throw `IllegalArgumentException` — never return a
boolean for garbage input. Note that wildcards are only valid as a whole
trailing component (`1.x.3` is malformed).
