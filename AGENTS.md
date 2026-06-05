# MadaCode Agent Instructions

## Project Overview

- MadaCode is a Java 21 CLI agent runtime.
- Main entry point: `madacode.MadaAgentCLI`.
- Packaged application artifact: `target/MadaCode.jar`.

## Build Commands

- Use the project Maven wrapper (`./mvnw`) for all Maven commands. Do not use the system `mvn` command.
- Common commands:
  - `./mvnw test-compile`
  - `./mvnw test`
  - `./mvnw package`

## Testing Policy

- Do not add broad new test coverage by default when implementing features or fixes.
- Only write new tests when the user explicitly asks for tests for a feature, bug, or behavior.
- After code changes, prefer running the existing Maven test suite or a focused existing test when practical.
- If an existing test fails after a change, investigate and fix the production code or the existing test as appropriate.
- Keep any test edits narrowly scoped to preserving the current intended behavior; avoid expanding the test suite unless requested.

## Code Change Style

- Do not perform unrelated refactors.
- Keep changes scoped to the current task.
- Prefer the existing module boundaries and code style.
- Add new abstractions only when they clearly reduce complexity.

## Project Structure

- `src/main/java/madacode/cli`: CLI args, REPL, and slash commands.
- `src/main/java/madacode/core/engine`: turn execution and tool orchestration.
- `src/main/java/madacode/core/session`: sessions, persistence, and resume.
- `src/main/java/madacode/tool`: built-in tools.
- `src/main/java/madacode/permission`: permission policy.
- `src/main/java/madacode/services/api`: API client, message serialization, and retries.
- `src/main/java/madacode/services/compact`: context compaction.
- `src/main/java/madacode/agent`, `src/main/java/madacode/skill`, `src/main/java/madacode/mcp`: extension systems.
- `src/main/java/madacode/longrunning`: long-running task runtime.
- `src/main/java/madacode/render`, `src/main/java/madacode/tui`: terminal output and UI.

## Implementation Preferences

- Use Java 21.
- Prefer Jackson for JSON parsing and serialization.
- Prefer the existing event/render pipeline for user-visible output and errors.
- Use the existing schema and validator flow for tool input validation.

## Change Review

- After every code change, review the changes you just made before finishing.
- Check for design or architecture problems, logic bugs, regressions, and behavior that does not match the user's request.
- Keep this review focused on the actual diff; do not use it as a reason to expand scope or add tests unless the user requested tests.
