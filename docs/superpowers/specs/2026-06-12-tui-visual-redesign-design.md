# MadaCode TUI 视觉体系重建 · 设计文档

日期：2026-06-12
状态：已与用户逐节确认

## 1. 背景与目标

当前 TUI 各组件（welcome card、tool card、审批/选择面板、状态行）视觉风格各自为政：
状态 glyph 简陋（`:` / `×`）、缩进与留白无统一网格、welcome 用全包围框线、
用户输入回显为 dim 灰几乎隐形。用户确认的四个痛点：整体观感太素、tool card
排版、交互组件粗糙、开屏与状态信息缺乏设计感。

目标：在**不改动渲染架构**（`Tk` / `Themes` / `Token` / `StageWriter` /
`ToolDisplay` / `ToolDisplayAdapter` 这套抽象保持不变）的前提下，完成一次系统化的
视觉语言重建。

## 2. 已确认的决策

| 决策点 | 结论 |
|---|---|
| 风格基调 | 克制精致型（参考 Claude Code / opencode） |
| 终端能力假设 | 标准 Unicode + 256 色；不引入 Nerd Font / truecolor |
| 品牌主色 | 陶土橙（dark 主题 256 色号 173，light 主题 130） |
| 改造深度 | 方案二：系统化设计语言，架构不动，仅渲染层 |
| 开屏 | 加强版块字字标（宽终端），<80 列降级纯文字版 |

## 3. 设计语言总则

### 3.1 色彩角色

dark 主题调色板（`Themes.DARK`）调整为：

| 角色 | Token | 256 色号 | 用途 |
|---|---|---|---|
| 主色 · 陶土橙 | `ACCENT`（新增）、`TOOL_NAME` | 173 | 工具名、prompt 标记 ❯、选中态、品牌条 ▌、运行中 spinner |
| 路径 · 钢青 | `FILE_PATH`、`DIFF_HUNK` | 110 | 文件路径（与主色形成冷暖对比） |
| 参数 · 沙褐 | `TOOL_ARG`、`INLINE_CODE` | 180 | 命令与参数文本 |
| 成功绿 | `SUCCESS`、`DIFF_ADD` | 71 | 仅状态点与 diff 增行 |
| 失败红 | `FAILURE`、`DIFF_DEL`、`TAG_ERROR` | 167 | 状态点、diff 删行、错误 |
| 警告琥珀 | `TAG_WARN` | 179 | 仅警告（running 不再占用琥珀） |
| 思考灰紫 | `THINKING_PULSE` | 139 | thinking 脉冲（从 177 调暗） |
| 弱化 | `MUTED`、`INFO` 等 | faint | 元信息、导轨、提示 |

light 主题对应：accent 130、path 25、arg 94、success 28、failure 124、
warn 136（避免与 accent 130 撞色）、thinking 96。

**RUNNING 语义变更**：`Token.RUNNING` 改为映射主色（spinner 用主色），running
状态的摘要文字一律 dim——橙色只出现在单个 spinner 字符上，控制密度。若实测橙色
仍过密，退路为 spinner 降为 dim（单点改动）。

新增 Token：
- `ACCENT`：品牌主色，welcome 品牌条、prompt 标记、选中行等非工具场景使用。
- `SELECTION`：反白选中态，`AttributedStyle` 的 `inverse()` + 主色前景，
  用于审批胶囊与未来的选中高亮；在 basic/mono 降级下退化为纯 inverse。

### 3.2 glyph 字库（`StageWriter.glyph` 等处统一）

| 语义 | glyph | 颜色 |
|---|---|---|
| 成功 | `●` | success |
| 失败 | `✗`（替换现 `×`） | failure |
| 拒绝 | `⊘`（替换现 `×`） | failure |
| 排队 | `◌` | muted |
| 运行 | braille spinner `⠋⠙⠹…` | accent |
| 信息 | `›` | muted |
| 警告 | `▲`（替换现 `!`） | warn |
| 思考 | `✦`（替换现 `*`） | thinking |
| 输入标记 | `❯` | accent |
| 品牌条 | `▌` | accent |
| 树形导轨 | `├ └ │` | muted |
| 单选 | `◉ ○` | accent / muted |

全部为单列宽（wcwidth=1）标准 Unicode，无字体依赖。无色降级时形状本身保持
可区分性（现有 accessibility 原则延续）。

### 3.3 两格缩进网格

- 第 0 列永远是状态 glyph；内容从第 2 列起。
- 一切详情行（diff 预览、进度输出、展开提示）缩进两格 + `├ └ │` 导轨。
- 相邻 tool card 之间不留空行（紧凑执行流）；工具组与 assistant 正文之间空一行；
  用户输入回显上方空一行。
- 整屏形成单一垂直对齐线，这是精致感的首要来源。

## 4. 框架性 UI

### 4.1 Welcome card（`WelcomeCard`）

去掉 `╭─╮` 全包围框。两级布局：

- **宽终端（≥80 列）**：两行高块字字标 `MADA CODE`（█▀▄ 半块字符拼成，
  MADA 用主色、CODE 用沙褐 180），下接一行 dim 元信息
  `v0.1.0 · model · provider · cwd`（cwd 用钢青），再下一行 dim tips。
- **窄终端（40–79 列）**：基础版——`▌ MadaCode v0.1.0`（品牌条主色）+
  对齐的 key-value 区（key dim、value 正常色、cwd 钢青）+ tips。
- **极窄（<40 列）**：保留现有 4 行纯文本降级。

### 4.2 用户输入回显（`UserInputRenderer`）

- `❯` 用 `ACCENT`（替换现 promptHistory faint）。
- 正文用默认前景色，**不再 dim**。
- 续行缩进两格不变；整块上方保证一个空行。

### 4.3 状态行（`TurnStatusRenderable` / `ThinkingRenderable`）

| 模式 | glyph | 颜色 |
|---|---|---|
| THINKING | `✦` | thinking 139，消息 dim，配 ThinkingVerbs 轮换 |
| REQUESTING | `◌` | muted |
| TOOL_USE | spinner | accent，消息 dim |
| IDLE | `›` | muted |

超过 5 秒追加 `(Ns · esc to interrupt)` 的现有逻辑保留。`ThinkingRenderable`
的独立 spinner 样式与上表 THINKING 对齐（✦ + dim 文字）。

## 5. Tool Card

### 5.1 标题语法

`{glyph} {工具名} {主参数} · {元信息}`

- 工具名：accent 粗体（现有 snake_case 规范化保留）。
- 主参数：路径类用钢青 `FILE_PATH`，命令/模式类用沙褐 `TOOL_ARG`；
  视觉上不再出现括号（`StageWriter.splitTitle` 解析逻辑保留，渲染端不输出括号）。
- `·` 之后为元信息，统一 dim；其中状态性片段允许着色（exit 1 红、+N -M 绿/红）。

### 5.2 各工具元信息词典（各 `*DisplayAdapter`）

| 工具 | 成功时元信息 |
|---|---|
| file_read | `212 行 · 0.1s` |
| file_edit | `+24 -8 · 0.4s`（增删着色） |
| file_write | `3.1KB · 新文件 · 0.2s` |
| bash | `ok · 1.2s` / 失败 `exit 1 · 2.1s`（exit 红） |
| grep | `12 处命中 · 5 个文件` |
| glob | `8 个文件` |
| web_fetch | `域名 · 14KB · 1.8s` |
| agent | 子任务摘要一句话 + 耗时 |
| skill | skill 名 + `已加载` |

### 5.3 内联 diff 预览（file_edit / file_write）

- 成功的编辑卡片在导轨下直接渲染最多 6 行着色 diff（复用 `DiffHighlighter`）。
- 超出部分进 `ctrl+o` 展开区，末行提示 `└ ctrl+o 展开 · 还有 N 行`。

### 5.4 状态视觉等级

- **运行中**：accent spinner + 标题，摘要 `running · Ns` dim；进度输出挂
  `│` 导轨 dim，上限 10 行，更早行折叠为 `… (N earlier lines hidden)`（现状保留）。
- **失败 `✗`**：标题行仅摘要段变红；错误首行（最多 4 行）红色挂导轨下。
- **拒绝 `⊘`**：红 glyph + dim `denied by user`（或拒绝理由）。
- **排队**：保持现状不渲染（pure queued 不占行）。

## 6. 交互组件

总原则：**静态回显不画框，交互时刻才画框**。框线全局只有一个语义：
程序停下来等待用户操作。

### 6.1 审批面板（`ApprovalPanel`）

- 半框结构：`╭─ ▲ 权限请求 ─…`（▲ 琥珀、标题粗体、框线 dim），内容行以
  `│` 开头：第一行工具名（accent）+ 完整命令（沙褐），第二行执行目录（钢青）。
- 动作行横向：选中项为**反白胶囊**（`SELECTION` token：inverse + accent 前景，
  即橙底深字），未选中 dim；deny 选中时胶囊用 failure 色。
- 底框线：`╰─ ←/→ 选择 · enter 确认 · esc 拒绝 ──`；移除可见提示中的
  `a/d legacy keys`（按键行为保留）。
- 替换现有的 `▌`（warn 色）左轨风格 inline 审批与 modal 审批两处，
  两处共用同一渲染。

### 6.2 选择面板（`ChoicePanel` / `ChoicePrompt`，含 U3 过滤）

- 标题保持 `── 标题 ──` dim 分隔线 + 粗体标题。
- 过滤行：`筛选 {输入}` + 主色反白块光标。
- 选中行：`❯`（accent）+ 文本 accent 粗体；当前生效项行尾 dim `✓ 当前`。
- **过滤命中的字符片段用 accent 着色**（所有行，含未选中行）。
- 底部 `── ↑/↓ 选择 · enter 确认 · esc 取消 ──`。

### 6.3 命令面板（`CommandPalettePanel`）

- 继承选择面板语言；命令名列（accent/普通灰）与描述列（dim）两列对齐，
  对齐列宽取可见命令名最长者。

## 7. 降级与兼容

- **basic（8/16 色）主题**：accent → YELLOW、path → CYAN、arg → YELLOW，
  其余沿用现 `buildBasic`；`SELECTION` 退化为纯 inverse。
- **mono**：全部沿用现 `buildMono` 思路，新 glyph 形状本身保证状态可区分。
- **窄终端**：welcome 三级降级（§4.1）；面板与卡片沿用现有 fitEnd/clamp 逻辑，
  网格缩进在任何宽度下不破坏。
- 块字字标为纯 `█ ▀ ▄` 半块字符，所有等宽字体均可渲染。

## 8. 影响面（实现时的改动清单）

仅渲染层，核心逻辑零改动：

- `tui/theme/Token.java`：新增 `ACCENT`、`SELECTION`。
- `tui/theme/Themes.java`：dark/light/basic/mono 四套调色板更新。
- `render/StageWriter.java`：glyph 字库、标题渲染、摘要着色。
- `render/tool/*DisplayAdapter.java`：元信息词典；`FileEditDisplayAdapter`
  增加 diff 预览详情行。
- `render/turn/ToolCardRenderable.java`、`TurnStatusRenderable.java`、
  `ThinkingRenderable.java`：状态 glyph 与颜色对齐新规范。
- `render/UserInputRenderer.java`：输入回显提亮。
- `tui/WelcomeCard.java`：字标 + 三级降级布局。
- `tui/widget/ApprovalPanel.java`、`ChoicePanel.java`、
  `CommandPalettePanel.java`：面板语言重做。

## 9. 测试策略

- 各渲染器均为纯函数（输入 → `List<String>`/`AttributedString`），按现有测试
  风格补齐快照式断言：每个组件覆盖正常宽度、窄宽度（<40）、mono 降级三档。
- glyph 与缩进网格写入共享测试常量，防止后续组件偏离两格网格。
- 手工验收：dark/light × 宽/窄终端 × 一次包含 read/edit/bash 失败/审批拒绝的
  完整会话脚本。
