# MadaCode

[English](README.en.md)

**MadaCode** 是一个运行在终端（CLI）的智能 AI 编码 Agent 运行时系统，旨在通过自然语言对话无缝协助开发者完成各种复杂的代码任务。

作为一个强大的终端结对编程助手，MadaCode 能够自主读取和修改项目文件、执行终端命令、搜索代码逻辑、规划复杂任务以及调度外部工具。它默认接入 Anthropic 兼容的大语言模型生态，为开发者提供开箱即用的智能化开发体验。

### 核心特性

- 🧠 **智能会话管理**：支持会话持久化、中断恢复（Resume）以及自动上下文压缩。
- 🔌 **灵活的模型生态**：支持在终端热切换不同的 Model 与 Provider。
- 🧩 **模块化扩展**：原生支持 MCP (Model Context Protocol) 接入，允许挂载 Sub Agent 和自定义 Skill 体系。
- ⚡ **原生终端体验**：基于 Java 21 构建的纯 CLI 应用，内置交互式 TUI 面板。既可从源码快速启动，也支持安装为全局 `mada` 命令。

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
mada --provider <name>   # 使用 providers.json 中的 provider 启动
mada --no-memory         # 本次运行禁用记忆
mada --help              # 显示 CLI 帮助
```

进入会话后，用 `/help` 查看 slash 命令。常用命令包括 `/model`、`/provider`、
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

## 贡献

欢迎在 [github.com/itmada/MadaCode](https://github.com/itmada/MadaCode) 提交
issue 和 pull request。提 PR 前请先跑 `./mvnw test`。
