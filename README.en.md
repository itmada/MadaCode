# MadaCode

[简体中文](README.md) | [Architecture](docs/architecture.md)

![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/license-MIT-blue)
![LOC](https://img.shields.io/badge/main%20code-~42k%20lines-informational)
![Framework](https://img.shields.io/badge/runtime%20deps-no%20Spring-success)

**MadaCode** is a terminal-based (CLI) LLM Coding Agent, independently built after the core runtime mechanics of Claude Code. It autonomously reads and edits project files, executes terminal commands, searches codebases, plans complex tasks, spawns sub-agents, and connects to external tool ecosystems via MCP.

~42k lines of Java 21, zero runtime framework dependencies (no Spring), shipped as a single jar.

### Core Features

- 🤝 **Terminal Pair Programming** — read/edit files, run commands, search code, and complete complex tasks through natural-language conversation
- 🚀 **Long-Running Mode** — built for large-scale refactoring and batch fixes: Worker Agents iterate in independent contexts with a task state machine, checkpoint recovery, and live progress monitoring
- 🧠 **Sessions & Memory** — session persistence, resume, automatic context compaction, and cross-session long-term memory
- 🧩 **Extensible Ecosystem** — MCP tool integration, Sub-Agent spawning, and a customizable Skill system
- 🔌 **Flexible Model Access** — hot-switch providers and models from the terminal, Anthropic-compatible
- ⚡ **Native Terminal Experience** — Java 21 single jar, zero framework dependencies, installs as a global `mada` command

### Architecture

Curious how it works inside? How the main loop converges cancellation / compaction / tool calls into a single message stream, how same-turn tools are segmented for parallel execution, what happens when ESC is pressed at any moment, and how agent behavior is regression-tested deterministically — see **[docs/architecture.md](docs/architecture.md)**.

## Quick Start

Requirements:

- Java 21 or newer
- Maven 3.9.x, or the bundled `./mvnw` wrapper

Install the `mada` command:

```sh
git clone https://github.com/itmada/MadaCode.git
cd MadaCode
./install.sh
```

The installer builds `target/MadaCode.jar`, copies it to
`~/.mada/MadaCode.jar`, and writes the launcher to `~/.local/bin/mada`.

If `~/.local/bin` is not on your `PATH`, add this to your shell profile:

```sh
export PATH="$HOME/.local/bin:$PATH"
```

You can also run directly from the repository:

```sh
./bin/mada
```

The repo launcher rebuilds the jar when it is missing or stale, then starts
MadaCode.

## First Run

On first startup, if no provider is configured yet, MadaCode opens a TUI setup
panel for the provider name, base URL, auth token, default model, and available
models, then saves the result to `~/.mada/providers.json`.

```json
{
  "providers": [
    {
      "name": "your-provider",
      "authToken": "YOUR-AUTH-TOKEN",
      "baseUrl": "https://your-provider.example.com",
      "defaultModel": "your-model",
      "models": [
        { "name": "your-model" }
      ]
    }
  ]
}
```

## Usage

```sh
mada                     # interactive startup selector
mada --new               # start a new session
mada --continue          # continue the most recent session
mada --resume <id>       # resume a saved session by ID
mada --list              # list saved sessions
mada --long-running      # start in Long-Running mode
mada --provider <name>   # start with a provider from providers.json
mada --no-memory         # disable memory for this run
mada --help              # show CLI help
```

Inside a session, use `/help` to list slash commands. Common commands include
`/model`, `/provider`, `/mode`, `/permission`, `/sessions`, `/resume`, `/compact`,
`/skills`, `/status`, and `/exit`.

## Configuration

Most state lives under `~/.mada`, including providers, active model state,
sessions, blobs, agents, skills, MCP config, memory, and permission audit logs.
Project-local `.mada/agents` and `.mada/skills` directories are also loaded when
present.

MCP servers are configured in `~/.mada/mcp.json`. Text resources are returned
inline; binary resources are persisted under `~/.mada/blobs` and returned as
local paths.

## Development

```sh
./mvnw test-compile
./mvnw test
./mvnw package
```

The build enforces Java 21 and Maven 3.9.x. The shaded application jar is
written to `target/MadaCode.jar`.

## Contributing

Issues and pull requests are welcome at
[github.com/itmada/MadaCode](https://github.com/itmada/MadaCode). Please run
`./mvnw test` before opening a PR.
