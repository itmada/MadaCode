# MadaCode

[简体中文](README.md)

**MadaCode** is an intelligent AI coding agent runtime designed to seamlessly assist developers with complex code tasks through natural language dialogue directly from the terminal (CLI).

As a powerful pair-programming assistant, MadaCode can autonomously read and edit project files, execute terminal commands, search codebases, plan complex tasks, and orchestrate external tools. It integrates natively with Anthropic-compatible large language models, providing an out-of-the-box intelligent development experience.

### Core Features

- 🧠 **Intelligent Session Management**: Supports session persistence, resumable states, and automatic context compaction.
- 🔌 **Flexible Model Ecosystem**: Dynamically hot-switch between different models and providers directly from the terminal.
- 🧩 **Modular Extensibility**: Native support for MCP (Model Context Protocol) integration, Sub-Agents, and customizable Skill systems.
- ⚡ **Native Terminal Experience**: A pure CLI application built on Java 21 with a built-in interactive TUI. Can be quickly launched from source or installed as a global `mada` command.

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
mada --provider <name>   # start with a provider from providers.json
mada --no-memory         # disable memory for this run
mada --help              # show CLI help
```

Inside a session, use `/help` to list slash commands. Common commands include
`/model`, `/provider`, `/sessions`, `/resume`, `/compact`, `/skills`,
`/status`, and `/exit`.

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
