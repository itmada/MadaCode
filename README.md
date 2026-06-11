# MadaCode

[English](README.en.md) | [架构设计](docs/architecture.md)

![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/badge/license-MIT-blue)
![LOC](https://img.shields.io/badge/main%20code-~42k%20lines-informational)
![Framework](https://img.shields.io/badge/runtime%20deps-no%20Spring-success)

**MadaCode** 是一个运行在终端（CLI）的 LLM Coding Agent，参照 Claude Code 的核心运行机制独立实现。它能自主读写项目文件、执行终端命令、搜索代码、规划复杂任务、派生子 Agent，并通过 MCP 接入外部工具生态。

约 4.2 万行 Java 21，零运行时框架依赖（无 Spring），单 jar 分发。

<!-- TODO: 在此插入终端 demo GIF（推荐用 vhs 或 asciinema 录制）
![demo](docs/assets/demo.gif)
-->

## 核心特性

- 🤝 **终端结对编程** — 自然语言对话即可读写文件、执行命令、搜索代码、规划并完成复杂任务
- 🚀 **Long-Running 模式** — 面向大规模重构与批量修复：Worker Agent 以多轮独立上下文循环推进，内置任务状态机、断点恢复与实时进度监控
- 🧠 **会话与记忆** — 会话持久化、中断恢复、自动上下文压缩，跨会话长期记忆
- 🧩 **可扩展生态** — MCP 外部工具接入、Sub-Agent 派生、自定义 Skill 体系
- 🔌 **灵活的模型接入** — 终端内热切换 Provider 与 Model，兼容 Anthropic 生态
- ⚡ **原生终端体验** — Java 21 单 jar，零框架依赖，安装即得全局 `mada` 命令

## 架构

对内部实现感兴趣？主循环如何将取消 / 压缩 / 工具调用收敛为统一消息流、同轮工具如何切段并发、ESC 在任意时刻按下会发生什么、如何对 Agent 行为做确定性回归测试——见 **[docs/architecture.md](docs/architecture.md)**。

## 快速开始

环境要求：

- Java 21 或更新版本
- Maven 3.9.x，或项目自带的 `./mvnw` wrapper

安装 `mada` 命令：

```sh
git clone https://github.com/itmada/MadaCode.git
cd MadaCode
./install.sh
```

安装脚本会构建 `target/MadaCode.jar`，复制到 `~/.mada/MadaCode.jar`，
并把启动器写入 `~/.local/bin/mada`。

如果 `~/.local/bin` 不在 `PATH` 中，把下面这行加入 shell 配置：

```sh
export PATH="$HOME/.local/bin:$PATH"
```

也可以直接从仓库运行：

```sh
./bin/mada
```

仓库启动器会在 jar 缺失或过期时自动重新构建，然后启动 MadaCode。

## 首次运行

首次启动时，如果还没有 provider 配置，MadaCode 会在 TUI 中打开配置面板，引导你填写
provider 名称、base URL、auth token、默认模型和可用模型，并保存到
`~/.mada/providers.json`。

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

## 使用

```sh
mada                     # 交互式启动选择器
mada --new               # 开始新会话
mada --continue          # 继续最近一次会话
mada --resume <id>       # 按 ID 恢复已保存会话
mada --list              # 列出已保存会话
mada --long-running      # 启动 Long-Running 模式
mada --provider <name>   # 使用 providers.json 中的 provider 启动
mada --no-memory         # 本次运行禁用记忆
mada --help              # 显示 CLI 帮助
```

进入会话后，用 `/help` 查看 slash 命令。常用命令包括 `/model`、`/provider`、`/mode`、`/permission`、
`/sessions`、`/resume`、`/compact`、`/skills`、`/status` 和 `/exit`。

## 配置

大部分状态保存在 `~/.mada`，包括 provider、当前模型状态、会话、blob、agent、
技能、MCP 配置、记忆和权限审计日志。如果项目中存在 `.mada/agents` 和
`.mada/skills`，也会被加载。

MCP server 配置位于 `~/.mada/mcp.json`。文本资源会直接返回；二进制资源会保存到
`~/.mada/blobs`，并以本地路径返回。

## 开发

```sh
./mvnw test-compile
./mvnw test
./mvnw package
```

构建会强制 Java 21 和 Maven 3.9.x。shade 后的应用 jar 会输出到
`target/MadaCode.jar`。

## License

[MIT](LICENSE) © itmada

欢迎在 [github.com/itmada/MadaCode](https://github.com/itmada/MadaCode) 提交
issue 和 pull request。提 PR 前请先跑 `./mvnw test`。
