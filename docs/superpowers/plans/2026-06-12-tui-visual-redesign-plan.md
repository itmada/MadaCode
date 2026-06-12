# TUI 视觉体系重建 · 实施计划

> **执行者须知（先读完再动手）**
> - 设计依据：`docs/superpowers/specs/2026-06-12-tui-visual-redesign-design.md`（本计划自包含，可不回读）。
> - 构建命令一律用 Maven wrapper：`./mvnw test-compile`、`./mvnw test`。**不要用 gradle 或系统 mvn**。
> - 遵守 `AGENTS.md`：**不新增测试**（除非现有测试因改动失败需要修正）、不做无关重构、保持现有代码风格。
> - 所有用户可见文案保持**英文**（如 `running`、`denied by user`、`12 matches`），设计稿里的中文仅为示意。
> - 所有终端着色必须经过 `madacode.tui.theme.Tk` / `Token`，禁止手写 ANSI 码（项目铁律）。
> - 每个任务完成后：`./mvnw test-compile && ./mvnw test` 通过 → 单独 commit（建议消息已写在各任务末尾）。
> - 任务必须按顺序执行：T1 是所有后续任务的依赖。

## 背景速览（渲染层架构）

- **主题体系**：`Token`（语义枚举）→ `Themes`（dark/light/basic/mono 四套 `Token→AttributedStyle` 映射，调色用 256 色号）→ `Tk`（字符串着色静态助手）。渲染代码只引用 `Tk`/`Token`。
- **Tool card**：各 `render/tool/*DisplayAdapter` 产出 `ToolDisplay`（title/summary/detailLines/status）→ `ToolActivityCardRenderer.card()` → `StageWriter.render()` 拼出最终行。`StageWriter.splitTitle` 会把 `"Bash(cmd)"` 形式的 title 拆成 label+detail 渲染（**视觉上已无括号**，不要改 adapter 的 title 构造方式）。
- **实时刷新**：`render/turn/*Renderable` 实现 `render(int maxWidth) -> List<String>`，由 TurnView 驱动重绘；spinner 动画靠 `Spinner.dots()`。
- **交互面板**：`tui/widget/ApprovalPanel|ChoicePanel|CommandPalettePanel` 是纯渲染器（无键盘逻辑），输出 `AttributedString` 或 ANSI `String`。

---

## T1 · 主题层：新 Token 与四套调色板

**文件**：`src/main/java/madacode/tui/theme/Token.java`、`Themes.java`、`Tk.java`

1. `Token` 新增两个枚举值（加在 `// Generic text` 组）：
   - `ACCENT` — 品牌主色（陶土橙）。
   - `SELECTION` — 反白选中态。
2. `Themes` 调色板（`Palette` record 字段顺序：success, failure, amber, accent, path, link, code, thinking）：
   - `DARK` 改为 `(71, 167, 179, 173, 110, 75, 180, 139)`（变化：accent 80→173，thinking 177→139）。
   - `LIGHT` 改为 `(28, 124, 136, 130, 25, 26, 94, 96)`（变化：amber 130→136 避免与 accent 130 撞色，accent 30→130，thinking 97→96）。
3. `Themes.build(...)` 中：
   - `Token.RUNNING` 改映射 `d.foreground(p.accent())`（running 让出琥珀色，琥珀只留给 `TAG_WARN`）。
   - 新增 `m.put(Token.ACCENT, d.foreground(p.accent()))`。
   - 新增 `m.put(Token.SELECTION, d.inverse().foreground(p.accent()))`（反白后前景号即胶囊底色）。
4. `buildBasic(...)`：`ACCENT` → `d.foreground(AttributedStyle.YELLOW)`；`SELECTION` → `d.inverse()`；`RUNNING` 改 `d.foreground(AttributedStyle.CYAN)`（避免与 YELLOW 的 warn 混淆）。
5. `buildMono()`：`ACCENT` → `d.bold()`；`SELECTION` → `d.inverse()`（其余循环已兜底 DEFAULT）。
6. `Tk` 新增两个助手（放在 semantic helpers 组）：
   ```java
   public static String accent(String s)    { return apply(Token.ACCENT, s); }
   public static String selection(String s) { return apply(Token.SELECTION, s); }
   ```

**验证**：`./mvnw test`。`src/test/java/madacode/tui/theme/ThemesTest.java` 若断言了旧色号/旧映射，按新值修正断言（这是"现有测试因改动失败"的合法修正）。

**Commit**：`U5-1: add ACCENT/SELECTION tokens and terracotta palette`

---

## T2 · StageWriter：glyph 字库与摘要着色

**文件**：`src/main/java/madacode/render/StageWriter.java`

1. `glyph(Status)` 替换为：
   ```java
   case RUNNING -> "⠧";   // 静态兜底帧；live 卡片仍用 spinner 覆盖
   case SUCCESS -> "●";
   case FAILED  -> "✗";
   case DENIED  -> "⊘";
   case INFO    -> "›";
   case WARN    -> "▲";
   ```
2. `colorSummary(Status, String)`：`RUNNING` 分支从 `Tk.running(text)` 改为 `Tk.dim(text)`——运行态摘要文字一律 dim，橙色只出现在 spinner 字符上（密度控制，spec §3.1）。其余分支不动。
3. `colored(...)` 不动（glyph 本身仍按状态着色；RUNNING 经 T1 已变主色）。

**验证**：`./mvnw test`；肉眼检查无其他调用方依赖旧字符（`grep -rn '"×"\|"!"' src/main/java/madacode/render` 应无残留）。

**Commit**：`U5-2: unify status glyph set in StageWriter`

---

## T3 · 框架性 UI：状态行、thinking、输入回显

**文件**：`render/turn/TurnStatusRenderable.java`、`render/turn/ThinkingRenderable.java`、`render/UserInputRenderer.java`

1. `TurnStatusRenderable.render(...)` 的 glyph 表改为：
   - `THINKING` → `"✦"`（样式保持 `Token.THINKING_PULSE`）。
   - `REQUESTING` → `"◌"`，样式改 `Tk.dim`（原 `Tk.running`）。
   - `TOOL_USE` → spinner 不变（T1 后自动变主色）。
   - `IDLE` → `"›"` 不变。
2. `ThinkingRenderable`：把 braille `FRAMES` 替换为脉冲两帧 `{"✦", "✧"}`，帧间隔改 `500_000_000L`（500ms 慢脉冲）；渲染行改为 `Tk.thinking(frame) + " " + Tk.dim("Thinking…")`。
3. `UserInputRenderer.lines(...)`：
   - 首行：`Tk.accent("❯") + " " + line`（**正文不再 dim**，用默认前景色直接输出）。
   - 续行：`"  " + line`（同样去掉 dim）。
   - 检查调用方（`grep -rn UserInputRenderer src/main/java`）确认首行上方有空行节奏；若回显块上方无空行，由调用方补一个空行（保持"输入块上方空一行"的网格规则）。

**验证**：`./mvnw test`。

**Commit**：`U5-3: align status line, thinking pulse and user echo with design language`

---

## T4 · Tool card 元信息词典

**文件**：`render/tool/ToolDisplaySupport.java` 及各 `*DisplayAdapter.java`

统一规则：title 构造（`"Bash(cmd)"` 形式）**全部不动**；只改 summary 文案，格式为
`{产出} · {耗时}`，耗时格式沿用现有 `completedSummary` 的时长渲染逻辑（抽公共助手 `durationText(durationMs)` 进 `ToolDisplaySupport`）。

| Adapter | 成功 summary（英文） | 失败 summary |
|---|---|---|
| `FileReadDisplayAdapter` | `"{N} lines · {dur}"` | 现状保留 |
| `FileEditDisplayAdapter` | `"{+N -M 着色} · {dur}"`（见下） | 现状保留 |
| `FileWriteDisplayAdapter` | `"{size} · new file · {dur}"`（size 用现有字节格式助手，没有则 `{N} lines`） | 现状保留 |
| `BashDisplayAdapter` | `"ok · {dur}"` | `"exit {code} · {dur}"`，`exit {code}` 片段用 `Tk.failure` 着色（在 `bashSummary` 内部处理） |
| `GrepDisplayAdapter` | `"{N} matches in {M} files"`（输出可解析时；否则现状） | 现状保留 |
| `GlobDisplayAdapter` | `"{N} files"` | 现状保留 |
| `WebFetchDisplayAdapter` | `"{host} · {size} · {dur}"` | 现状保留 |
| `AgentDisplayAdapter`、`SkillDisplayAdapter` | 仅核对耗时片段格式统一，不大改 | — |

`FileEditDisplayAdapter` 专项：
1. summary 中 `+N -M`：用 `Tk.diffAdd("+" + n)` / `Tk.diffDel("-" + m)` 着色（现 `lineChangeSummary` 产出纯文本，改造或新增着色版本）。
2. **内联 diff 预览**：调查 Edit 工具的 `output` 内容（看 `madacode/tool` 下 edit 工具的返回格式）。若 output 中含可定位的变更行（diff 或 before/after 片段），在成功分支把最多 **6 行**着色 diff 行（`+` 行 `Tk.diffAdd`、`-` 行 `Tk.diffDel`，可复用 `render/DiffHighlighter`）放入 `detailLines`，完整版放入 verbose（`renderResultVerbose` 路径），让现有 `ctrl+o 展开 · N lines hidden` 机制自然接管。**若 output 不含任何变更内容，跳过本条，保持 summary-only，并在 commit 信息里注明**。

**验证**：`./mvnw test`；如 `ToolProtocolTest` 等断言了旧 summary 文案，按新文案修正。

**Commit**：`U5-4: per-tool result summaries and inline diff preview`

---

## T5 · WelcomeCard：块字字标 + 三级降级

**文件**：`tui/WelcomeCard.java`

`render(provider, model, cwd, terminalWidth)` 改为三档：

1. **`terminalWidth < 40`**：现有 4 行纯文本降级，原样保留。
2. **`40 ≤ width < 80`**：基础版（去全包围框）：
   ```text
   ▌ MadaCode v0.1.0          ← ▌+名 Tk.accent 粗体（accent+bold 直接用 Tk.apply(Token.ACCENT,…)+Tk.bold 组合或新增局部样式），版本 dim
     model     claude-fable-5  ← key 列 Tk.dim、宽 9 左对齐；value 默认色
     provider  anthropic
     cwd       ~/workspace/MadaCode   ← value 用 Tk.filePath

     /help commands · @file add context · shift+tab cycle mode   ← Tk.dim
   ```
3. **`width ≥ 80`**：加强版，块字字标两行（**精确字符串如下，勿改字形**）：
   ```text
   █▀▄▀█ ▄▀█ █▀▄ ▄▀█ █▀▀ █▀█ █▀▄ █▀▀
   █ ▀ █ █▀█ █▄▀ █▀█ █▄▄ █▄█ █▄▀ ██▄
   ```
   前 17 列（MADA 部分，即前 17 个字符）用 `Tk.accent`，空格后的 CODE 部分用 `Tk.toolArg`。下接空行、一行 dim 元信息 `v0.1.0 · {model} · {provider} · {cwd}`（model 默认色、cwd `Tk.filePath`，复用现有 `fitCwd` 截断）、空行、tips 行（dim）。
4. 删除 `row(...)` 与框线拼装逻辑（若 `ChoicePrompter`/会话头等处复用了该私有方法，仅限本类内删除；先 `grep -rn WelcomeCard src/main/java` 确认外部只调 `render`）。

**验证**：`./mvnw test`；临时小 main 或 jshell 打印 38/60/100 三档宽度输出肉眼核对对齐（不提交临时代码）。

**Commit**：`U5-5: block wordmark welcome with three-tier width fallback`

---

## T6 · ApprovalPanel：半框 + 反白胶囊

**文件**：`tui/widget/ApprovalPanel.java`（注意：`permission/JLineApprovalPrompt.java` 只管按键，**不改**其逻辑，仅当它拼接面板文案时同步新视觉）

inline 版（`renderInlineApproval`）与 modal 版（`render`）统一为同一套视觉语法：

```text
╭─ ▲ Permission required ─────────────────────────
│ bash ./gradlew test --rerun-tasks
│ in ~/workspace/MadaCode
│
│  ❯ allow once    allow session    deny
╰─ ←/→ select · enter confirm · esc deny ─────────
```

实现要点：
1. 框线与 `│` 全部 `Tk.dim`；`▲` 用 `Tk.apply(Token.TAG_WARN, "▲")`；标题 `Permission required` 粗体默认色。
2. 内容行 1：工具名 `Tk.toolName` + 空格 + 命令/参数 `Tk.toolArg`（subject/detail 参数现成）。内容行 2（detail 为路径/目录时）用 `Tk.filePath`，前缀 `in `。
3. 动作行：选中项渲染为反白胶囊 —— `Tk.selection(" ❯ " + label + " ")`；**deny 被选中时**改用 inverse+failure（modal 版用 `Themes.active().styleOf(Token.FAILURE)` 加 `.inverse()`；inline 版新增局部组合，或在 `Tk` 加 `selectionDanger` 助手——允许，因为这是渲染层助手非新抽象）。未选中项 `Tk.dim(label)`，项间 4 空格。
4. 底框线内嵌按键提示：`╰─ ←/→ select · enter confirm · esc deny ─…`（dim）。**删除** `a/d legacy keys` 文案（`JLineApprovalPrompt` 中 a/d 按键处理逻辑保留）。
5. 旧的 `▌`（warn 色）左轨 inline 风格、`MODAL_FOOTER` 常量等随之清理；`headerLine/bodyLine` 等私有构件改造复用即可。宽度安全规则（`fitLine`、`safeWidth`）全部保留。

**验证**：`./mvnw test`；narrow 宽度（10 列）调用不抛异常、每行不超宽。

**Commit**：`U5-6: approval panel with half-frame and inverse selection pill`

---

## T7 · ChoicePanel 过滤高亮 + CommandPalette 两列对齐

**文件**：`tui/widget/ChoicePanel.java`、`CommandPalettePanel.java`、（视实现）`tui/inline/InlineChoicePrompt.java`

1. `ChoicePanel.optionLine`：
   - 选中行前缀 `❯ `，前缀与正文都用 `Token.ACCENT`（替换现 `STATUS_MODE_PLAN`），正文加粗。
   - 未选中行正文用默认色（非 dim，描述性副文案才 dim）。
   - `ChoiceOption` 若已有"当前生效"语义字段则行尾追加 dim `✓ current`；**没有就不加字段、跳过**（避免改 view-model 波及调用方）。
2. **过滤命中高亮**：查看 `tui/inline/ChoiceFilter`（有 `ChoiceFilterTest`）。若过滤是子串/前缀匹配，对每个候选行用 `indexOf`（大小写不敏感）找到命中区间，把命中片段着 `Token.ACCENT` 色（选中行在 accent 基础上保持粗体）。过滤输入行的光标用 `Tk.selection(" ")` 反白块。若 `ChoiceFilter` 是模糊匹配且不暴露区间，则只高亮首个连续子串命中，注释说明。
3. `CommandPalettePanel` 候选行：先扫描全部候选名取最大显示宽（`Tk.displayWidth`），命令名列右补空格对齐后接两空格再接描述（描述 `Tk.dim`）。选中行规则与 ChoicePanel 一致（`❯` + accent）。
4. 同步 `ChoicePanel` 顶部/底部 `── … ──` 分隔线保持现状（已符合设计）。

**验证**：`./mvnw test`（`ChoiceFilterTest` 必须保持通过）。

**Commit**：`U5-7: choice filter highlight and palette column alignment`

---

## T8 · 全量验证与收尾

1. `./mvnw test` 全绿。
2. `./mvnw package`，运行 `target/MadaCode.jar` 走一遍手工脚本：
   - 100 列宽终端启动 → 看块字开屏；60 列重启 → 基础版；38 列 → 纯文本。
   - 发起一个会触发 read + edit + bash 的任务 → 核对卡片：`●`/`✗` glyph、橙色工具名、钢青路径、`+N -M` 着色、失败卡片红色 exit 片段、运行中 spinner 橙色且摘要 dim。
   - 触发一次权限审批 → 半框 + 反白胶囊，←/→ 移动时 deny 胶囊变红底，esc 拒绝后卡片显示 `⊘ … denied by user`。
   - `/model` 打开选择面板：输入过滤词 → 命中片段橙色高亮；`/` 打开命令面板 → 两列对齐。
   - `/theme light` 切换 → 重复抽查上述各项无刺眼/不可读组合。
   - 设法在 8 色或 mono 模式（如 `TERM=dumb` 或项目的能力检测开关）下启动 → 确认降级可读。
3. 发现的视觉问题就地修复后重跑 `./mvnw test`，单独 commit `U5-8: visual QA fixes`（若无问题则跳过此 commit）。

---

## 风险与回退预案

- **橙色密度过高**（长任务时 spinner + 工具名都是橙）：预案为把 `Themes.build` 中 `Token.RUNNING` 改回 `d.faint()`，一行回退，不动其他。
- **`⊘ ✦ ◌ ▲` 在个别老终端字体缺字**：全部是 BMP 常用区字符，wcwidth=1；若真遇缺字，逐字符替换回 `x * o !` 即可，改动点全部集中在 `StageWriter.glyph` 与三个 Renderable。
- **inverse 在某些主题下对比度差**：`SELECTION` 集中在一个 Token，整体可调。
