# MadaCode

[简体中文](README.zh-CN.md)

A coding agent that lives in your terminal - written in Java.

![Java 21](https://img.shields.io/badge/Java-21-blue)
![Build](https://img.shields.io/badge/build-Maven-green)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

## ✨ Features

- Terminal-first coding agent runtime with an interactive JLine TUI.
- Claude-style sessions: start fresh, continue the latest session, resume by ID, or list saved sessions.
- Anthropic-compatible provider config with multiple base URLs, providers, and models.
- Built-in file, shell, search, web fetch, planning, memory, agent, skill, and MCP tools.
- Permission gate for filesystem and shell actions, with an explicit bypass flag for trusted automation.
- Persistent sessions, provider state, skills, agents, memories, MCP config, and audit data under `~/.mada`.
- Streaming Markdown rendering with tool cards and diff highlighting.

## 🚀 Getting Started

### Requirements

- Java 21 or newer
- Maven 3.9.x, or the included `./mvnw` wrapper

### Install The `mada` Command

```sh
./install.sh
```

The installer builds `target/MadaCode.jar`, copies it to `~/.mada/MadaCode.jar`, and writes the command wrapper to `~/.local/bin/mada`.

If `~/.local/bin` is not on your `PATH`, add this to `~/.zshrc`:

```sh
export PATH="$HOME/.local/bin:$PATH"
```

### Run From The Repository

```sh
./bin/mada
```

The repository launcher builds `target/MadaCode.jar` automatically when the jar is missing, or when `pom.xml` or `src/` is newer than the jar. If nothing changed, it starts immediately.

On first startup, MadaCode creates `~/.mada/providers.json` and asks you to fill in an auth token before running again.

## 💬 Usage

```sh
mada
mada --new
mada --continue
mada --resume <session-id>
mada --list
mada --provider <name>
mada --no-memory
mada --help
```

Options:

| Option | Description |
| --- | --- |
| `mada` | Open the interactive startup selector. |
| `--new` | Start a new session. |
| `--continue`, `-c` | Continue the most recent session. |
| `--resume <id>`, `-r <id>` | Resume a saved session by ID. |
| `--list`, `-l` | List saved sessions. |
| `--provider <name>` | Start with a provider from `providers.json`. |
| `--no-memory` | Disable project memory for this run. |
| `--dangerously-bypass-permissions` | Skip approval prompts in trusted automation contexts. |
| `--help`, `-h` | Show CLI help. |

Common slash commands:

| Command | Description |
| --- | --- |
| `/help [command]` | Show help for slash commands. |
| `/model [name]` | List or switch models for the active provider. |
| `/provider [name\|reset]` | List, switch, or reset providers. |
| `/sessions` | Show saved sessions. |
| `/resume <session-id>` | Resume a saved session. |
| `/new` | Start a new session. |
| `/compact [instructions]` | Compact conversation context. |
| `/cost` | Show token usage for the active model. |
| `/status` | Show provider, model, and mode status. |
| `/theme [name]` | View or change the terminal theme. |
| `/skills [list\|on <name>\|off <name>\|reload]` | Manage loaded skills. |
| `/exit` | Exit the REPL. |

## 🛠 Tools

MadaCode currently registers these built-in tools:

| Area | Tools |
| --- | --- |
| Shell | `bash` |
| Files | `file_read`, `write`, `edit`, `glob`, `grep` |
| Web | `web_fetch` |
| Agents and skills | `agent`, `skill` |
| Planning | `todo_write`, `enter_plan_mode`, `exit_plan_mode`, `plan_create`, `plan_get`, `plan_list`, `plan_update` |
| Memory | `memory_save` |
| MCP | `list_mcp_resources`, `read_mcp_resource` |
| Provider setup | `add_provider` |
| Interaction | `ask_user_question` |

Tool registration is organized through `FileToolModule`, `WebToolModule`, `McpToolModule`, `MemoryToolModule`, `PlanToolModule`, `AgentSkillToolModule`, and `InteractionToolModule`.

## ⚙️ Configuration

Provider configuration lives at `~/.mada/providers.json`. If the file does not exist, MadaCode creates this template:

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

Fill in `authToken`, `baseUrl`, `defaultModel`, and `models`, then run `mada` again.

Useful environment variables:

| Variable | Description |
| --- | --- |
| `MADA_DISABLE_MEMORY=true` | Disable memory loading. |
| `MADA_NO_PICKER=true` | Skip the interactive startup selector and start a new session. |
| `MADA_VERBOSE_TIMINGS=true` | Show verbose timing metadata in rendered events. |

Common data paths:

| Path | Purpose |
| --- | --- |
| `~/.mada/providers.json` | Provider definitions. |
| `~/.mada/state.json` | Active provider/model state. |
| `~/.mada/sessions/` | Saved conversations and turn logs. |
| `~/.mada/last-session` | Most recently active session pointer. |
| `~/.mada/blobs/` | Persisted blobs from tool output and MCP resources. |
| `~/.mada/agents/` | User-defined agents. |
| `~/.mada/skills/` | User-installed skills. |
| `~/.mada/skills.json` | Skill enable/disable state. |
| `~/.mada/mcp.json` | MCP server configuration. |
| `~/.mada/MADA.md` | User-global memory file. |
| `~/.mada/permissions/audit.jsonl` | Permission audit log. |

Project-local `.mada/agents` and `.mada/skills` directories are also loaded when present.

## 🔌 MCP

MCP configuration is loaded from `~/.mada/mcp.json`. Enabled servers are started by the MCP connection manager, and MCP resources can be inspected through the built-in resource tools:

```text
list_mcp_resources
read_mcp_resource
```

Text resources are returned inline. Binary resources are persisted under `~/.mada/blobs` and returned as local paths.

## 🏗 Architecture

MadaCode is a Java 21 CLI runtime with no DI framework. `MadaAgentCLI` parses CLI arguments, then `Bootstrapper` and the `*Assembly` classes wire the runtime.

| Package | Role |
| --- | --- |
| `core/engine`, `core/turn`, `core/session`, `core/model` | Turn execution, tool orchestration, sessions, and message models. |
| `tool`, `tool/blob`, `tool/validation` | Built-in tools, blob persistence, and input validation. |
| `bootstrap` | Composition root and module registration. |
| `provider` | Anthropic-compatible provider and model configuration. |
| `services/api` | API client, retry, serialization, and streaming. |
| `services/compact` | Micro/full context compaction and token estimation. |
| `permission` | Approval and filesystem authority. |
| `agent`, `skill`, `memory`, `mcp` | Sub-agents, skill loading, memory, and MCP integration. |
| `events`, `render`, `tui` | Event bus, terminal rendering, and JLine UI. |

## 🧪 Development

```sh
./mvnw test-compile
./mvnw test
./mvnw package
```

The Maven build enforces Java 21 and Maven 3.9.x. The shaded application jar is written to `target/MadaCode.jar`.

## 📄 License

MadaCode is released under the [MIT License](LICENSE).
