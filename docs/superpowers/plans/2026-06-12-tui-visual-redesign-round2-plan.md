# TUI 视觉重建 · 第二轮优化计划（V1–V5）

> **执行者须知（先读完再动手）**
> - 本计划是 `2026-06-12-tui-visual-redesign-plan.md`（T1–T8，已执行完毕）的修正轮。
>   第一轮的产出整体合格，本轮只修复验证中发现的具体落差，**不要重做第一轮的内容**。
> - 构建一律用 `./mvnw test-compile` / `./mvnw test`，不要用 gradle 或系统 mvn。
> - 遵守 `AGENTS.md`：不新增测试（现有测试因改动失败才可修正断言）、不做无关重构。
> - 所有着色必须经过 `madacode.tui.theme.Tk` / `Token`，禁止手写 ANSI 码。
> - 每个任务完成后：`./mvnw test-compile && ./mvnw test` 通过 → 单独 commit。
> - 按 V1 → V5 顺序执行，V1 影响全局观感，优先级最高。

## 背景：第一轮验证结论

通过渲染探针核对过：陶土橙 `38;5;173`、反白胶囊 `7;38;5;173`、块字字标、
半框审批、`● file_edit path · meta` 卡片语法均已正确输出。剩余落差：

1. 全部"弱化文本"依赖 SGR faint（`\e[2m`），不同终端表现差异巨大，灰阶层级
   经常塌掉 —— 这是"整体不像设计稿"的主因。
2. 文件路径在工具卡片标题里被染成沙褐（256 色号 180），设计要求钢青（110），
   冷暖对比没有出来。
3. 活动输入符被改成 `›`，与历史回显的橙色 `❯` 不一致。

---

## V1 · 灰阶固定色：faint 只留给降级主题

**文件**：`src/main/java/madacode/tui/theme/Themes.java`

**原理**：设计稿里的"灰"是确定的中灰色。256 色主题必须用固定灰色号，
faint 只允许出现在 `buildBasic`（8/16 色）和 `buildMono` 里。

1. `Palette` record 增加一个字段 `gray`（追加到末尾）：
   - `DARK` 增加 `gray = 243`（最终为 `(71, 167, 179, 173, 110, 75, 180, 139, 243)`）。
   - `LIGHT` 增加 `gray = 245`（最终为 `(28, 124, 136, 130, 25, 26, 94, 96, 245)`）。
2. `build(Palette p, boolean lightBackground)` 中，把**所有** `d.faint()` 替换为
   `d.foreground(p.gray())`，逐一核对以下 Token（一个都不能漏）：
   - `MUTED`、`INFO`、`TAG_INFO`、`CODE_FENCE`、`STATUS_KEY`、
     `STATUS_MODE_AUTO`、`TIP_AUTO`、`MODE_INDICATOR_AUTO`、`PROMPT_HISTORY`
   - `QUOTE` → `d.foreground(p.gray()).italic()`（保留 italic）。
3. `buildBasic` 和 `buildMono` **完全不动**（faint 是它们的合法降级手段）。
4. 改完后 `grep -n "faint" src/main/java/madacode/tui/theme/Themes.java`：
   faint 只应出现在 `buildBasic` 与 `buildMono` 两个方法里。

**验证**：`./mvnw test`；`ThemesTest` 若断言了 MUTED 等为 faint，按新映射修正断言。

**Commit**：`U6-1: replace faint with fixed gray ramp in 256-color themes`

---

## V2 · 路径类参数改钢青色

**文件**：`src/main/java/madacode/render/StageWriter.java`

现状：`styledTitle` 把所有 title detail 染成 `Tk.toolArg`（沙褐 180）。
设计要求：**文件路径用 `Tk.filePath`（钢青 110）**，命令/模式串保持沙褐。

1. 在 `styledTitle` 中按规范化后的 label 分流：
   ```java
   private static final java.util.Set<String> PATH_DETAIL_LABELS =
           java.util.Set.of("file_read", "file_write", "file_edit");

   private static String styledDetail(String label, String detail) {
       return PATH_DETAIL_LABELS.contains(label)
               ? Tk.filePath(detail)
               : Tk.toolArg(detail);
   }
   ```
   `styledTitle` 改为调用 `styledDetail(label, parts.detail())`。
2. `bash`/`grep`/`glob`/`web_fetch` 等保持沙褐不变（glob/grep 的参数是模式
   而非路径，web_fetch 是 URL，归在参数色）。

**验证**：`./mvnw test`；快速探针（可用 jshell 或临时 main，验证后删除）：
`StageWriter.render(Stage(SUCCESS, "Read(src/Foo.java)", ...))` 输出中路径段
应为 `38;5;110`，而 `Stage(SUCCESS, "Bash(ls)")` 的参数段仍为 `38;5;180`。

**Commit**：`U6-2: render file paths in steel blue within tool card titles`

---

## V3 · 活动输入符回归 ❯ 并接入主色

**文件**：`src/main/java/madacode/cli/JLineRepl.java`、`src/main/java/madacode/tui/theme/Themes.java`

1. `JLineRepl.buildPrompt()`：把 `Tk.promptActive("›")` 改回 `Tk.promptActive("❯")`。
2. `Themes.build(...)` 中 `PROMPT_ACTIVE` 改为主色加粗：
   ```java
   m.put(Token.PROMPT_ACTIVE, d.bold().foreground(p.accent()));
   ```
   （dark/light 同一表达式；`buildBasic`/`buildMono` 的 PROMPT_ACTIVE 不动。）
3. 效果：活动输入行与历史回显（`UserInputRenderer` 的橙色 `❯`）共用同一品牌
   锚点，输入前后视觉连续。

**验证**：`./mvnw test`。

**Commit**：`U6-3: restore accent ❯ as the active prompt marker`

---

## V4 · 端到端视觉核验与微调（真机走查）

`./mvnw package` 后运行 `target/MadaCode.jar`，逐项核对；发现偏差按下述
指引就地修复（均为小改动）：

1. **灰阶层级**：任意会话画面里，元信息/导轨/框线应呈清晰的中灰，与正文
   有明显但不刺眼的层级差。若 dark 主题下 243 偏亮或偏暗，可在 242–244 间
   微调（只动 `Palette.gray`）。
2. **diff 预览端到端**：让 agent 真实执行一次文件编辑，确认卡片下方出现
   绿/红着色的 diff 预览行（最多 6 行）+ `ctrl+o` 展开提示。若没有出现：
   检查 `FileEditTool` 的输出是否包含 `Diff:` 块及 `Line changes: +N -M`
   行（`ToolDisplaySupport.diffBlock` / `lineChangeSummary` 依赖这两个标记），
   缺哪个补哪个（改 `FileEditTool` 的输出拼装，不改解析端）。
3. **审批面板**：真实触发一次权限审批，核对：半框完整、第一行
   `工具名 + 参数`（橙 + 沙褐）、反白胶囊随 ←/→ 移动、deny 选中时胶囊变
   红底、esc 后卡片显示 `⊘ … denied by user`。
4. **留白节奏**：welcome 块与后续输出之间、用户输入回显上方，应各有且仅有
   一个空行。多了或少了在调用方（`JLineRepl` / `BlockSpacing`）调整，
   不动渲染器本身。
5. **light 主题**：`/theme light` 后重复抽查 1–4，重点看灰 245 在浅底上的
   可读性（不够深可调到 244/102）。

仅当本任务实际改了代码才提交。

**Commit**：`U6-4: visual QA fixes from end-to-end walkthrough`

---

## V5 · 收尾

1. `./mvnw test` 全绿；`./mvnw package` 成功。
2. `git log --oneline` 确认 U6 系列提交完整。
3. 在 `docs/superpowers/plans/2026-06-12-tui-visual-redesign-round2-plan.md`
   末尾追加一节"执行结果"，逐条记录：每个任务的实际改动点、V4 走查中发现并
   修复的问题、以及任何未能完成的项和原因。随文档一起提交。

**Commit**：`U6-5: round-2 execution report`

---

## 明确不做的事（防止范围蔓延）

- 不引入 truecolor、Nerd Font、背景色块。
- 不改 `ToolDisplay` / `ToolDisplayAdapter` / `Renderable` 等接口签名。
- 不重排第一轮已定型的卡片语法、面板结构、字标字形。
- 不新增配置项或主题数量。
- 终端自身的行高、字体、底色差异不属于本轮要解决的问题。

---

## 执行结果

- V1 已完成并提交 `5595165 U6-1: replace faint with fixed gray ramp in 256-color themes`：
  `Palette` 增加 `gray`，dark/light 分别使用 243/245；256 色 `build(...)` 中
  MUTED、INFO、TAG_INFO、CODE_FENCE、QUOTE、STATUS_KEY、STATUS_MODE_AUTO、
  TIP_AUTO、MODE_INDICATOR_AUTO、PROMPT_HISTORY 全部改为固定灰阶；`buildBasic`
  与 `buildMono` 未改。`rg -n "faint"` 确认 faint 只保留在降级主题方法中。
- V2 已完成并提交 `6242bdd U6-2: render file paths in steel blue within tool card titles`：
  `StageWriter` 增加路径类 label 分流，`file_read`、`file_write`、`file_edit`
  的 title detail 使用 `Tk.filePath`，其余工具参数仍使用 `Tk.toolArg`。
  临时探针确认 `Read(src/Foo.java)` 路径段为 `38;5;110`，
  `Bash(ls)` 参数段仍为 `38;5;180`。
- V3 已完成并提交 `429f4ef U6-3: restore accent ❯ as the active prompt marker`：
  `JLineRepl.buildPrompt()` 恢复 `❯`，256 色 `PROMPT_ACTIVE` 改为
  `d.bold().foreground(p.accent())`；`buildBasic` 与 `buildMono` 未改。
- V4 已完成端到端视觉核验，未发现需要代码微调的问题，因此按计划跳过
  `U6-4` 提交。核验包括：`./mvnw package` 成功并生成 `target/MadaCode.jar`；
  jar 入口 `--help`、`--list` 可运行；临时 jar 探针确认 dark gray=243、
  light gray=245、prompt accent、file_edit 路径 110、diff 预览最多 6 行并带
  `ctrl+o` 展开提示、审批 allow/deny 胶囊分别为橙/红反白、denied 卡片为
  `⊘ ... denied by user`。
- V5 最终验证已完成：`./mvnw test` 全绿（156 tests），`./mvnw package` 成功；
  `git log --oneline` 已确认 U6-1、U6-2、U6-3 存在。没有未完成项。
