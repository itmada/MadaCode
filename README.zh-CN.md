# MadaCode

[English](README.md)

一个生活在终端里的编码 Agent - 使用 Java 编写。

![Java 21](https://img.shields.io/badge/Java-21-blue)
![Build](https://img.shields.io/badge/build-Maven-green)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

## ✨ 功能

- 终端优先的编码 Agent 运行时，带交互式 JLine TUI。
- Claude 风格会话：新建、继续最近会话、按 ID 恢复、列出已保存会话。
- Anthropic 兼容的 provider 配置，支持多个 base URL、provider 和 model。
- 内置文件、Shell、搜索、网页抓取、计划、记忆、子 Agent、技能和 MCP 工具。
- 文件系统和 Shell 操作走权限门；可信自动化场景可显式开启 bypass。
- 会话、provider 状态、技能、Agent、记忆、MCP 配置和审计数据持久化在 `~/.mada`。
- 流式 Markdown 渲染，带工具卡片和 diff 高亮。

## 🚀 快速开始

### 环境要求

- Java 21 或更新版本
- Maven 3.9.x，或项目内置的 `./mvnw`

### 安装 `mada` 命令

```sh
./install.sh
```

安装脚本会构建 `target/MadaCode.jar`，复制到 `~/.mada/MadaCode.jar`，并把命令启动器写入 `~/.local/bin/mada`。

如果 `~/.local/bin` 不在 `PATH` 中，把下面一行加入 `~/.zshrc`：

```sh
export PATH="$HOME/.local/bin:$PATH"
```

### 从仓库内运行

```sh
./bin/mada
```

仓库内启动器会在 `target/MadaCode.jar` 不存在，或 `pom.xml` / `src/` 比 jar 更新时自动构建。如果没有代码变化，会直接启动。

首次启动时，MadaCode 会创建 `~/.mada/providers.json`，并提示你填写 auth token 后再次运行。

## 💬 使用

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

参数：

| 参数 | 说明 |
| --- | --- |
| `mada` | 打开交互式启动选择器。 |
| `--new` | 开始一个新会话。 |
| `--continue`, `-c` | 继续最近一次会话。 |
| `--resume <id>`, `-r <id>` | 按 ID 恢复已保存会话。 |
| `--list`, `-l` | 列出已保存会话。 |
| `--provider <name>` | 使用 `providers.json` 中指定的 provider 启动。 |
| `--no-memory` | 本次运行禁用项目记忆。 |
| `--dangerously-bypass-permissions` | 在可信自动化场景跳过审批提示。 |
| `--help`, `-h` | 显示 CLI 帮助。 |

常用 slash 命令：

| 命令 | 说明 |
| --- | --- |
| `/help [command]` | 查看 slash 命令帮助。 |
| `/model [name]` | 列出或切换当前 provider 的模型。 |
| `/provider [name\|reset]` | 列出、切换或重置 provider。 |
| `/sessions` | 显示已保存会话。 |
| `/resume <session-id>` | 恢复已保存会话。 |
| `/new` | 开始新会话。 |
| `/compact [instructions]` | 压缩对话上下文。 |
| `/cost` | 显示当前模型的 token 用量。 |
| `/status` | 显示 provider、model 和模式状态。 |
| `/theme [name]` | 查看或切换终端主题。 |
| `/skills [list\|on <name>\|off <name>\|reload]` | 管理已加载技能。 |
| `/exit` | 退出 REPL。 |

## 🛠 工具

MadaCode 当前注册的内置工具：

| 分类 | 工具 |
| --- | --- |
| Shell | `bash` |
| 文件 | `file_read`, `write`, `edit`, `glob`, `grep` |
| Web | `web_fetch` |
| Agent 和技能 | `agent`, `skill` |
| 计划 | `todo_write`, `enter_plan_mode`, `exit_plan_mode`, `plan_create`, `plan_get`, `plan_list`, `plan_update` |
| 记忆 | `memory_save` |
| MCP | `list_mcp_resources`, `read_mcp_resource` |
| Provider 设置 | `add_provider` |
| 交互 | `ask_user_question` |

工具注册由 `FileToolModule`、`WebToolModule`、`McpToolModule`、`MemoryToolModule`、`PlanToolModule`、`AgentSkillToolModule` 和 `InteractionToolModule` 分组组织。

## ⚙️ 配置

Provider 配置文件位于 `~/.mada/providers.json`。如果文件不存在，MadaCode 会创建下面的模板：

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

填写 `authToken`、`baseUrl`、`defaultModel` 和 `models` 后，再次运行 `mada`。

常用环境变量：

| 变量 | 说明 |
| --- | --- |
| `MADA_DISABLE_MEMORY=true` | 禁用记忆加载。 |
| `MADA_NO_PICKER=true` | 跳过交互式启动选择器并开启新会话。 |
| `MADA_VERBOSE_TIMINGS=true` | 在渲染事件中显示更详细的耗时元数据。 |

常见数据路径：

| 路径 | 用途 |
| --- | --- |
| `~/.mada/providers.json` | Provider 定义。 |
| `~/.mada/state.json` | 当前 provider/model 状态。 |
| `~/.mada/sessions/` | 已保存对话和 turn log。 |
| `~/.mada/last-session` | 最近活跃会话指针。 |
| `~/.mada/blobs/` | 工具输出和 MCP 资源保存的 blob。 |
| `~/.mada/agents/` | 用户自定义 Agent。 |
| `~/.mada/skills/` | 用户安装的技能。 |
| `~/.mada/skills.json` | 技能启用/禁用状态。 |
| `~/.mada/mcp.json` | MCP server 配置。 |
| `~/.mada/MADA.md` | 用户全局记忆文件。 |
| `~/.mada/permissions/audit.jsonl` | 权限审计日志。 |

如果项目目录中存在 `.mada/agents` 和 `.mada/skills`，也会被加载。

## 🔌 MCP

MCP 配置从 `~/.mada/mcp.json` 加载。启用的 server 会由 MCP 连接管理器启动，MCP 资源可通过内置资源工具查看：

```text
list_mcp_resources
read_mcp_resource
```

文本资源会直接返回。二进制资源会保存到 `~/.mada/blobs`，并以本地路径返回。

## 🏗 架构

MadaCode 是 Java 21 CLI 运行时，不使用 DI 框架。`MadaAgentCLI` 解析 CLI 参数，然后由 `Bootstrapper` 和各个 `*Assembly` 类装配运行时。

| 包 | 职责 |
| --- | --- |
| `core/engine`, `core/turn`, `core/session`, `core/model` | turn 执行、工具编排、会话和消息模型。 |
| `tool`, `tool/blob`, `tool/validation` | 内置工具、blob 持久化和输入校验。 |
| `bootstrap` | 组合根和模块注册。 |
| `provider` | Anthropic 兼容的 provider 和模型配置。 |
| `services/api` | API 客户端、重试、序列化和流式响应。 |
| `services/compact` | micro/full 上下文压缩和 token 估算。 |
| `permission` | 审批和文件系统权限权威。 |
| `agent`, `skill`, `memory`, `mcp` | 子 Agent、技能加载、记忆和 MCP 集成。 |
| `events`, `render`, `tui` | 事件总线、终端渲染和 JLine UI。 |

## 🧪 开发

```sh
./mvnw test-compile
./mvnw test
./mvnw package
```

Maven 构建会强制 Java 21 和 Maven 3.9.x。shade 后的应用 jar 会输出到 `target/MadaCode.jar`。

## 📄 License

MadaCode 使用 [MIT License](LICENSE) 发布。
