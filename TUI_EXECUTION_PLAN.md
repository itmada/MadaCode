# TUI 剩余改造执行工单（U1–U4）

> 执行方式：**每次只做一个任务**，按 U1 → U2 → U3 → U4 顺序。每个任务完成、验收通过、
> 提交后再开始下一个。所有任务只改终端渲染/交互层，**禁止改动 core、tool、services、
> permission、longrunning 包中的任何业务逻辑**（工单中明确列出的文件除外）。

## 全局规则（每个任务开工前重读一遍）

1. 本工单授权为新增的纯函数（主题选择、过滤逻辑）编写少量单元测试，覆盖 AGENTS.md
   "不默认新增测试"条款；AGENTS.md 其余条款照常遵守。
2. 每个任务一个独立 commit，message 以任务编号开头（如 `U1: ...`）。
3. 每个任务完成后运行 `./mvnw test`，必须全绿（当前基线 150 个测试）。
4. **动手前先用 Read 完整读一遍要改的文件**。工单里的代码片段基于编写时的源码，若与
   实际不符（方法签名变了、行号漂移），以实际源码为准、保持工单意图，并在完成报告中
   说明差异。
5. 渲染冒烟测试方法（多个任务的验收会用到）：
   ```sh
   # 1) 先编译:  ./mvnw compile -q
   # 2) 写一个 jshell 脚本，注意最后一行必须是 /exit，否则 jshell 会挂起等待输入:
   printf 'import madacode.render.StageWriter;\n<你的单行java语句>\n/exit\n' > /tmp/s.jsh
   # 3) 运行并把 ANSI 转义剥掉以便肉眼检查对齐:
   JLINE=$(find ~/.m2/repository/org/jline -name "jline-3*.jar" | head -1)
   jshell --class-path "target/classes:$JLINE" /tmp/s.jsh 2>&1 | sed -e 's/\x1b\[[0-9;]*m//g'
   ```
   jshell 按"行"解析语句，**每条语句必须写在同一行**，不要跨行链式调用。
6. 所有颜色必须通过 `Tk` / `Token` / `Themes` 体系输出，禁止在任何渲染代码里手写
   ANSI 转义串（这是 [Tk.java](src/main/java/madacode/tui/theme/Tk.java) 头部注释规定的项目铁律）。
7. 所有新增的可见字符（图标、徽章）必须是 wcwidth 单列宽。不确定就用
   `madacode.tui.TerminalText.displayWidth("字符")` 在 jshell 里验证等于 1。

---

## U1：浅色主题 + 终端能力探测 + NO_COLOR + 主题持久化

### 背景与现状

- [Themes.java](src/main/java/madacode/tui/theme/Themes.java) 目前只有一个 `dark()` 主题，
  `names()` 返回 `List.of("dark")`。调色板用 256 色索引（文件顶部有
  `GREEN_SOFT = 71` 等常量），在浅色背景终端上部分颜色偏浅难读。
- `/theme` 命令在 [ThemeCommand.java](src/main/java/madacode/cli/slash/ThemeCommand.java)，
  已支持列出/选择/切换，但重启后丢失（无持久化）。
- 终端在 [TerminalAssembly.java](src/main/java/madacode/bootstrap/TerminalAssembly.java) 第 16 行
  通过 `JLineRepl.createTerminal()` 创建——这是能力探测的接入点。
- 不支持 256 色的终端、设置了 `NO_COLOR` 环境变量（约定见 no-color.org）的用户，
  目前都会收到他们终端无法正确显示的转义码。

### 步骤 1：Themes 增加浅色与降级调色板

修改 [Themes.java](src/main/java/madacode/tui/theme/Themes.java)：

1. 把现有 8 个颜色常量改为"按主题取色"的私有 record：

```java
/** Palette: one indexed color per semantic slot, per theme variant. */
private record Palette(int success, int failure, int amber, int accent,
                       int path, int link, int code, int thinking) {}

// 256-color mid-tones for dark backgrounds (current values, do not change)
private static final Palette DARK = new Palette(71, 167, 179, 80, 110, 75, 180, 177);
// darker stops readable on light backgrounds
private static final Palette LIGHT = new Palette(28, 124, 130, 30, 25, 26, 94, 97);
```

2. 把 `buildDark()` 改名为 `build(Palette p, boolean lightBackground)`，体内所有
   `GREEN_SOFT` → `p.success()`、`RED_SOFT` → `p.failure()`、`AMBER` → `p.amber()`、
   `TEAL` → `p.accent()`、`STEEL_BLUE` → `p.path()`、`SKY_BLUE` → `p.link()`、
   `SAND` → `p.code()`、`ORCHID` → `p.thinking()`。
   **一处特殊**：`PROMPT_ACTIVE` 当前是
   `d.bold().foreground(AttributedStyle.WHITE + AttributedStyle.BRIGHT)`，
   亮白在浅色背景上不可见，改为：
   ```java
   m.put(Token.PROMPT_ACTIVE, lightBackground ? d.bold() : d.bold().foreground(AttributedStyle.WHITE + AttributedStyle.BRIGHT));
   ```

3. 新增三个工厂 + 能力降级开关：

```java
private static volatile boolean basicColorsOnly;
private static volatile boolean monochrome;

/** Called once at startup after terminal capability detection. */
public static void configureCapabilities(boolean basic, boolean mono) {
    basicColorsOnly = basic;
    monochrome = mono;
}

public static Theme dark()  { return themed(DARK, false); }
public static Theme light() { return themed(LIGHT, true); }

private static Theme themed(Palette p, boolean lightBackground) {
    if (monochrome) return new MapTheme(buildMono());
    if (basicColorsOnly) return new MapTheme(buildBasic(lightBackground));
    return new MapTheme(build(p, lightBackground));
}
```

4. `buildMono()`：所有 token 只用 `d.faint()` / `d.bold()` / `d`（无任何 foreground），
   按现有语义分配：MUTED/INFO/TAG_INFO/CODE_FENCE/QUOTE(=faint italic)/STATUS_KEY 等
   → faint；EMPHASIS/HEADING/TOOL_NAME/PROMPT_ACTIVE → bold；LINK → `d.underline()`；
   其余 → `d`。
5. `buildBasic(lightBackground)`：恢复 git 历史中 256 色改造前的 8 色映射（用
   `git show 851a401~1:src/main/java/madacode/tui/theme/Themes.java` 查看旧版
   `buildDark()` 原文照抄），浅色背景时 `PROMPT_ACTIVE` 同样去掉亮白。
6. `names()` 改为 `List.of("dark", "light")`；`setActive(String)` 增加
   `"light"` 分支。

### 步骤 2：启动时探测能力 + 读取持久化偏好

新建 `src/main/java/madacode/tui/theme/ThemeBootstrap.java`：

```java
package madacode.tui.theme;

import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One-shot startup wiring: capability detection + persisted preference. */
public final class ThemeBootstrap {

    private static final Path PREF_FILE =
            Path.of(System.getProperty("user.home"), ".mada", "theme");

    private ThemeBootstrap() {}

    public static void initialize(Terminal terminal) {
        boolean mono = System.getenv("NO_COLOR") != null
                && !System.getenv("NO_COLOR").isEmpty();
        Integer maxColors = terminal.getNumericCapability(InfoCmp.Capability.max_colors);
        // Unknown capabilities keep the current 256-color behavior; only explicit
        // low-color terminals downgrade, and NO_COLOR can force monochrome.
        boolean basic = maxColors != null && maxColors > 0 && maxColors < 256;
        Themes.configureCapabilities(basic, mono);
        Themes.setActive(readPreference());
    }

    private static String readPreference() {
        try {
            if (Files.isRegularFile(PREF_FILE)) {
                String name = Files.readString(PREF_FILE).strip();
                if (Themes.names().contains(name)) return name;
            }
        } catch (IOException ignored) {
        }
        return "dark";
    }

    /** Best-effort persistence — failures must never break theme switching. */
    public static void savePreference(String name) {
        try {
            Files.createDirectories(PREF_FILE.getParent());
            Files.writeString(PREF_FILE, name + System.lineSeparator());
        } catch (IOException ignored) {
        }
    }
}
```

在 [TerminalAssembly.java](src/main/java/madacode/bootstrap/TerminalAssembly.java) 创建
terminal 之后（第 16 行 `JLineRepl.createTerminal()` 调用的下一行）插入：
```java
madacode.tui.theme.ThemeBootstrap.initialize(terminal);
```
先读该文件确认 terminal 变量名与作用域。

### 步骤 3：/theme 切换后写入偏好

[ThemeCommand.java](src/main/java/madacode/cli/slash/ThemeCommand.java) 的 `execute` 中，
`Themes.setActive(theme)` 返回 true 之后、打印反馈之前，加一行：
```java
madacode.tui.theme.ThemeBootstrap.savePreference(theme);
```
同时把第 29 行 `SlashChoiceModels.choice(..., "dark")` 的默认值留意一下——它是选择面板
的初始高亮项，保持 "dark" 即可，不用改。

### 步骤 4：单元测试（要求新增）

新建 `src/test/java/madacode/tui/theme/ThemesTest.java`，至少覆盖：
- `names()` 含 "dark" 与 "light"，`setActive("light")` 返回 true，`setActive("nope")` 返回 false。
- `configureCapabilities(false, true)`（NO_COLOR）后 `dark()` 主题中
  `Token.SUCCESS` 的样式不含前景色（断言
  `Themes.dark().styleOf(Token.SUCCESS).equals(AttributedStyle.DEFAULT.faint())` 之类，
  按你的 buildMono 实际映射写）。
- **测试收尾必须恢复全局状态**：`@AfterEach` 里调
  `Themes.configureCapabilities(false, false); Themes.setActive("dark");`
  ——`Themes.ACTIVE` 是全局静态，不恢复会污染其他测试。

### 验收

1. `./mvnw test` 全绿。
2. jshell 冒烟（不剥 ANSI，直接肉眼比对转义码差异）：
   ```sh
   printf 'import madacode.tui.theme.*;\nSystem.out.println(Tk.success("ok-dark"));\nThemes.setActive("light");\nSystem.out.println(Tk.success("ok-light"));\nThemes.configureCapabilities(false, true);\nThemes.setActive("dark");\nSystem.out.println(Tk.success("ok-mono"));\n/exit\n' > /tmp/u1.jsh
   ```
   预期：前两行输出含不同的 `38;5;` 颜色码（71 vs 28），第三行不含任何颜色码。
3. 手动：`./bin/mada` → `/theme` → 选 light → 退出重启 → 主题仍是 light
   （检查 `~/.mada/theme` 文件内容为 `light`）。

### 禁止事项

- 不要改 `Tk.java` 的任何方法签名（全仓几十处调用）。
- 不要把 `configureCapabilities` 做成在每次渲染时读环境变量——只在启动时调用一次。
- `ThemeBootstrap` 的 IO 全部 best-effort 吞掉，主题切换路径上不允许抛异常。

---

## U2：运行中工具卡的 spinner 动画

### 背景与现状

- 回合进行中，工具卡由 [ToolCardRenderable.java](src/main/java/madacode/render/turn/ToolCardRenderable.java)
  在 live 区域渲染（`render(int maxWidth)`，RUNNING 分支在第 158–175 行），
  状态图标是静态的琥珀色 `●`（来自 `StageWriter.glyph(RUNNING)`）。
- **重绘节拍已经存在，不需要你创建任何定时器**：
  [TurnRenderer.java](src/main/java/madacode/render/turn/TurnRenderer.java) 第 61 行创建的
  `TurnStatusRenderable` 每 120ms 调一次 `turnView::markDirty`，TurnView 重绘时会重新调用
  每个 Renderable 的 `render()`。所以只要 RUNNING 分支每次 render 返回下一帧 spinner，
  动画就自然发生。
- 卡片最终落盘到 scrollback 时用的是 finalize 后的 render() 输出（状态已是
  SUCCESS/FAILED/DENIED），所以 spinner 帧**不会**泄漏进历史记录。

### 步骤 1：StageWriter 支持运行态图标覆盖

[StageWriter.java](src/main/java/madacode/render/StageWriter.java)：现有
`public static List<String> render(Stage stage)` 保持不变，新增重载：

```java
/**
 * Render with an optional glyph override for the RUNNING state — used by the
 * live tool card to animate a spinner frame in place of the static bullet.
 * Non-RUNNING stages ignore the override.
 */
public static List<String> render(Stage stage, String runningGlyphOverride) {
    Objects.requireNonNull(stage, "stage");
    String glyph = (stage.status() == Status.RUNNING && runningGlyphOverride != null
            && !runningGlyphOverride.isBlank())
            ? runningGlyphOverride
            : glyph(stage.status());
    List<String> lines = new ArrayList<>();
    lines.add(colored(stage.status(), glyph) + " " + styledTitle(stage.title()));
    ... // 以下与现有 render(Stage) 完全一致
}
```

然后把现有 `render(Stage stage)` 的方法体改成一行 `return render(stage, null);`，
**删除原方法体里的重复代码**（保证逻辑只有一份）。

### 步骤 2：ToolActivityCardRenderer 透传

[ToolActivityCardRenderer.java](src/main/java/madacode/render/tool/ToolActivityCardRenderer.java)
现有 `card(ToolDisplay display, int maxWidth)`（第 32 行）保持不变，新增重载：

```java
/** Live-region variant: animates the RUNNING bullet with a spinner frame. */
public static List<String> card(ToolDisplay display, int maxWidth, String runningGlyphOverride) {
    Objects.requireNonNull(display, "display");
    StageWriter.Stage stage = stage(display, false);
    return clampWidth(StageWriter.render(stage, runningGlyphOverride), maxWidth);
}
```
并把现有两参 `card` 改为调用三参版本传 `null`。

### 步骤 3：ToolCardRenderable 持有 spinner

[ToolCardRenderable.java](src/main/java/madacode/render/turn/ToolCardRenderable.java)：

1. 字段区（第 43 行附近）加：
   ```java
   private final madacode.render.Spinner spinner = madacode.render.Spinner.dots();
   ```
2. `render` 的 RUNNING 分支（第 158–162 行），把
   ```java
   lines.addAll(ToolActivityCardRenderer.card(display, maxWidth));
   ```
   改为：
   ```java
   lines.addAll(ToolActivityCardRenderer.card(
           display, maxWidth, started ? spinner.tick() : null));
   ```
   `started == false`（排队中）保持静态图标。`render` 已是 `synchronized`，
   `spinner.tick()` 在锁内调用，线程安全无需额外处理。
3. SUCCESS/FAILED/DENIED 分支**不要动**。

### 验收

1. `./mvnw test` 全绿。
2. jshell 冒烟（单行语句）：
   ```sh
   printf 'import madacode.render.StageWriter;\nimport java.util.List;\nvar st = new StageWriter.Stage(StageWriter.Status.RUNNING, "bash(sleep 5)", List.of("running"), List.of(), false);\nSystem.out.println(StageWriter.render(st, "\\u280b").get(0));\nSystem.out.println(StageWriter.render(st).get(0));\nSystem.out.println(StageWriter.render(new StageWriter.Stage(StageWriter.Status.SUCCESS, "t", List.of(), List.of(), false), "\\u280b").get(0));\n/exit\n' > /tmp/u2.jsh
   ```
   预期（剥 ANSI 后）：第一行以 `⠋` 开头，第二行以 `●` 开头，第三行以 `●` 开头
   （override 对非 RUNNING 无效）。
3. 手动：`./bin/mada` 让模型执行 `bash sleep 8`，运行中卡片图标应以盲文点阵动画旋转，
   完成后定格为 `●`（绿色）；scrollback 历史里不出现盲文字符。

### 禁止事项

- 不要新建定时器/线程，重绘节拍由 TurnStatusRenderable 提供。
- 不要改 `Renderable` 接口。
- 不要让 spinner 帧出现在 `ToolCardWriter`（scrollback 两段式落盘）路径里。

---

## U3：选择面板输入过滤（会话/模型选择器搜索）

### 背景与现状

- 所有选择交互（/model、/theme、/resume 会话选择等）走
  [InlineChoicePrompt.java](src/main/java/madacode/tui/inline/InlineChoicePrompt.java) 的
  `choose(ChoicePrompt.Model<T>)` 键盘循环（第 63–130 行）。
- 当前按字母键的行为是"跳到下一个以该字母开头的选项"（`resolvePrintable`，第 157 行），
  会话多时找目标很费劲。
- [TerminalKeys.java](src/main/java/madacode/tui/TerminalKeys.java) 的 `Key` 枚举**已有**
  `BACKSPACE`（第 177 行，码 127 和 `\b` 都映射，见第 158 行），无需新增按键解析。
- 选项数据是 [ChoicePrompt.java](src/main/java/madacode/tui/widget/ChoicePrompt.java) 的
  `Model`/`Option` record；渲染走 `ChoicePanel.render`。
- `Model.horizontal()` 为 true 的是水平小面板（mode/permission 等 2–4 个选项），
  这类**保持现有行为不变**，过滤只作用于垂直列表。

### 设计

- 维护一个 `StringBuilder filter`。可打印字符追加进 filter；BACKSPACE 删最后一个字符；
  filter 非空时选项列表按"primary 或 secondary 含该子串（忽略大小写）"过滤。
- filter 为空时保留现有的数字 1–9 跳转；字母键只精确匹配 hotkey，未命中即进入过滤。
  零行为变化只承诺水平面板（`horizontal() == true`）和数字 1–9 跳转；垂直列表的
  字母键行为是本任务有意变更的部分。filter 非空时数字也作为过滤字符处理。
- ESC 语义分两段：filter 非空 → 第一次 ESC 清空 filter；filter 已空 → 取消选择
  （维持现状）。
- ENTER 在过滤结果为空时不做任何事（不能选中不存在的项）。
- 渲染时把 filter 状态拼进 subtitle，结果集变化后光标重置为 0。

### 步骤（全部在 InlineChoicePrompt.java 内完成）

1. `choose` 方法开头、`int selected = ...` 之后加：
   ```java
   StringBuilder filter = new StringBuilder();
   List<Integer> visible = allIndexes(model);   // 指向 model.options() 的下标
   ```
   其中：
   ```java
   private static <T> List<Integer> allIndexes(ChoicePrompt.Model<T> model) {
       List<Integer> all = new ArrayList<>();
       for (int i = 0; i < model.options().size(); i++) all.add(i);
       return all;
   }

   private static <T> List<Integer> filteredIndexes(ChoicePrompt.Model<T> model, String needle) {
       if (needle.isBlank()) return allIndexes(model);
       String n = needle.toLowerCase(java.util.Locale.ROOT);
       List<Integer> out = new ArrayList<>();
       for (int i = 0; i < model.options().size(); i++) {
           ChoicePrompt.Option<T> o = model.options().get(i);
           if (o.primary().toLowerCase(java.util.Locale.ROOT).contains(n)
                   || o.secondary().toLowerCase(java.util.Locale.ROOT).contains(n)) {
               out.add(i);
           }
       }
       return out;
   }
   ```
2. 循环体内 `screen.setLiveModal(renderPicker(model, selected, width));` 改为传入过滤
   后的视图。`renderPicker`/`buildView` 增加参数 `List<Integer> visible, String filter`：
   - `buildView` 只遍历 `visible` 中的下标构建 options；
   - subtitle 改为：filter 为空用 `model.subtitle()`，否则
     `model.subtitle() + "   filter: " + filter + "▏"`（无 subtitle 时只显示 filter 段）；
     结果为空时再追加 `"  (no match)"`。
   - `selected` 此时表示 **visible 列表内** 的下标。
3. 按键分支改造（`model.horizontal()` 为 true 时整段跳过，直接用旧逻辑——最简单的做法
   是方法开头 `if (model.horizontal()) return chooseLegacy(model);`，把现有整个循环体
   原样搬进私有 `chooseLegacy`，新循环只服务垂直列表）：
   - `ENTER`：`if (visible.isEmpty()) continue;` 否则
     `return Optional.of(model.options().get(visible.get(selected)).value());`
   - `ESCAPE`：`if (filter.length() > 0) { filter.setLength(0); visible = allIndexes(model); selected = 0; continue; }`
     否则维持现有取消逻辑。
   - `BACKSPACE`（新增 case）：`if (filter.length() > 0) { filter.deleteCharAt(filter.length() - 1); visible = filteredIndexes(model, filter.toString()); selected = 0; }`
   - `UP/LEFT`、`DOWN/RIGHT`、`PAGE_UP/PAGE_DOWN`：把 `model.options().size()` 全部换成
     `visible.size()`（注意 `visible.size()==0` 时跳过移动，避免 `Math.floorMod(x, 0)`
     除零异常——这是本任务最容易犯的错）。
   - `default` 可打印分支：
     ```java
     if (key.isPrintable()) {
         if (filter.isEmpty()) {
             int newSel = resolvePrintable(key.ch(), model, visible, selected);
             if (newSel != selected) { selected = newSel; continue; }
             if (key.ch() >= '1' && key.ch() <= '9') continue; // 数字命中即止
         }
         filter.append((char) key.ch());
         visible = filteredIndexes(model, filter.toString());
         selected = 0;
     }
     ```
     注意：旧 `resolvePrintable` 的"字母跳转"与过滤有冲突——上面的写法让字母**先尝试
     hotkey 精确匹配**（仅 hotkey，去掉旧的 primary 前缀跳转），未命中则进入过滤。
     相应地把 `resolvePrintable` 改为只匹配 `option.hotkey()`（删掉
     `matchesHotkeyOrPrimary` 中 primary 前缀分支），并让它接收 `visible` 在过滤域内
     循环查找。数字 1–9 跳转保留且只在 filter 为空时生效。
4. footer：垂直列表渲染时若 `model.footer()` 为空，给一个默认
   `"type to filter · backspace delete · esc clear/cancel"`（dim 由 ChoicePanel 自己处理，
   你只传纯文本）。

### 步骤 5：单元测试（要求新增）

把 `filteredIndexes` 提为包级可见静态方法（或挪到 `ChoicePrompt` 作为静态工具），新建
`src/test/java/madacode/tui/inline/ChoiceFilterTest.java` 覆盖：
空过滤返回全部、大小写不敏感、secondary 命中、无命中返回空表。
**不要**尝试为键盘循环本身写测试（需要真终端，做不了）。

### 验收

1. `./mvnw test` 全绿。
2. 手动验证清单（`./bin/mada`）：
   - `/resume`（需要 ≥3 个历史会话）：输入若干字符 → 列表收窄且 subtitle 显示
     `filter: xxx▏`；BACKSPACE 逐字删；ESC 第一次清过滤、第二次取消；
     无匹配时 ENTER 无效果且显示 `(no match)`。
   - `/theme`：输入 `li` → 只剩 light → ENTER 生效。
   - `/mode`（水平面板）：行为与改造前完全一致（热键、左右移动）。
   - 过滤后用 ↑↓ 移动再 ENTER，选中的是**屏幕上高亮的那一项**（这是下标映射最容易
     错的地方，务必亲手验证）。

### 禁止事项

- 不要改 `ChoicePrompt.Model` / `Option` record 的字段（StartupSessionLauncher 等
  多处构造它们）。
- 不要改 `ChoicePanel.render` 的签名；过滤逻辑全部留在 InlineChoicePrompt。
- 水平模式（`horizontal() == true`）行为零变化。

---

## U4：空闲态状态栏（prompt 徽章 + 右侧状态）

### 背景与设计决策（先理解再动手）

空闲等输入时，live 区域被 phase 门控（[JLineScreen.java](src/main/java/madacode/tui/JLineScreen.java)
`enterIdlePhase` 后 `setLiveStatus` 直接丢弃），输入行归 JLine LineReader 所有。
所以"持久底部状态栏"在空闲态的正确实现**不是** live region，而是：
- **左侧**：prompt 前缀加模式徽章（plan / accept-edits 等非默认模式时显示）；
- **右侧**：用 JLine 的 right-prompt 显示 `model · ctx N%`（输入文字接近右侧时
  JLine 会自动隐藏 right-prompt，这是 zsh RPROMPT 的标准行为，可接受）。

JLine 对 prompt 字符串会做 `AttributedString.fromAnsi` 解析，现有
`buildPrompt()`（[JLineRepl.java:419](src/main/java/madacode/cli/JLineRepl.java#L419)）已经
内嵌 ANSI 并正常工作，right-prompt 同理，可以放心用 `Tk` 上色。

### 步骤 1：修正 SessionContext 的 token 语义

现状 bug 级问题：[MetaEventRenderer.java:66-71](src/main/java/madacode/render/MetaEventRenderer.java#L66)
把每次 API 调用的 usage **累加**进 `renderedTokenTotal` 再喂给
`sessionContext.setTokens(...)` ——这是"本会话累计消耗"，不是"当前上下文占用"，
拿它除以 contextWindow 得到的百分比是错的。改法：

```java
case MetaEvent.TokenReport u -> {
    if (sessionContext != null) {
        // Latest request's input side + its output ≈ context size of the NEXT request.
        var usage = u.usage();
        sessionContext.setTokens(usage.inputTokens()
                + usage.cacheReadTokens()
                + usage.cacheCreationTokens()
                + usage.outputTokens());
    }
}
```
然后删除 `renderedTokenTotal` 字段和 `reset()` 里对它的清零（`reset()` 方法本身保留，
其他调用方不受影响；若删字段后 `reset()` 变空方法，体内留注释说明保留原因）。
`TokenUsage` 的四个字段名见
[TokenUsage.java](src/main/java/madacode/core/model/TokenUsage.java)：`inputTokens`、
`outputTokens`、`cacheCreationTokens`、`cacheReadTokens`。

[SessionContext.java](src/main/java/madacode/tui/widget/SessionContext.java) 加两个方法：

```java
public synchronized int tokens() { return tokens; }

/** Percent of the model context window in use; -1 when the limit is unknown. */
public synchronized int contextPercent() {
    if (tokenLimit <= 0) return -1;
    return Math.min(100, (int) Math.round(tokens * 100.0 / tokenLimit));
}
```

注意：`/compact` 压缩与会话切换后 tokens 应归零——`replaceMessages`/新会话路径会触发
新的 TokenReport 前显示旧值，可接受，不要为此加额外清零逻辑（保持任务边界）。

### 步骤 2：prompt 徽章 + right-prompt

[JLineRepl.java](src/main/java/madacode/cli/JLineRepl.java)：

1. 第 219 行 `line = lineReader.readLine(buildPrompt());` 改为：
   ```java
   line = lineReader.readLine(buildPrompt(), buildRightPrompt(), (Character) null, null);
   ```
   （`LineReader.readLine(String prompt, String rightPrompt, Character mask, String buffer)`
   是 JLine 3 标准重载。）

2. `buildPrompt()` 改为：
   ```java
   private String buildPrompt() {
       String badge = promptBadge();
       return (badge.isEmpty() ? "" : badge + " ") + Tk.promptActive("❯") + " ";
   }

   private String promptBadge() {
       if (sessionContext == null) return "";
       if (sessionContext.planMode()) {
           return Tk.apply(madacode.tui.theme.Token.MODE_INDICATOR_PLAN, "[plan]");
       }
       madacode.permission.PermissionMode pm = sessionContext.permissionMode();
       if (pm != null && pm != madacode.permission.PermissionMode.DEFAULT) {
           return Tk.apply(madacode.tui.theme.Token.TAG_WARN, "[" + permissionLabel(pm) + "]");
       }
       return "";
   }
   ```
   `permissionLabel(pm)`：先读
   [PermissionMode.java](src/main/java/madacode/permission/PermissionMode.java)，枚举构造
   形如 `DEFAULT("strict", "...", 0)`，找到第一个字符串参数对应的访问器方法名
   （类似 `label()` 或 `wireName()`），直接用它；找不到访问器就用
   `pm.name().toLowerCase(Locale.ROOT).replace('_', ' ')`。

3. 新增：
   ```java
   private String buildRightPrompt() {
       if (sessionContext == null) return "";
       StringBuilder sb = new StringBuilder();
       String model = sessionContext.model();
       if (model != null && !model.isBlank()) {
           sb.append(Tk.dim(model));
       }
       int pct = sessionContext.contextPercent();
       if (pct >= 0) {
           if (!sb.isEmpty()) sb.append(Tk.dim(" · "));
           String ctx = "ctx " + pct + "%";
           if (pct >= 90)      sb.append(Tk.failure(ctx));
           else if (pct >= 70) sb.append(Tk.apply(madacode.tui.theme.Token.TAG_WARN, ctx));
           else                sb.append(Tk.dim(ctx));
       }
       return sb.toString();
   }
   ```
   阈值含义：≥70% 提醒该 `/compact` 了，≥90% 红色警示。

4. `Repl.java` 中 `sessionContext` 字段对子类可见（包级 final 字段），直接用即可，
   不要新加 getter。

### 验收

1. `./mvnw test` 全绿。
2. 手动验证清单（`./bin/mada`）：
   - 新会话空闲态：右侧显示 `model-name · ctx N%`（首轮对话前没有 TokenReport，
     `ctx` 可能显示 0% 或不准，对话一轮后应变为真实占用且**不随轮数无限上涨**——
     这是步骤 1 语义修正的核心验证点；改造前它每轮单调递增）。
   - `/mode` 切到 plan：prompt 变为 `[plan] ❯ `（青色徽章）；切回 default 徽章消失。
   - 切换 accept-edits 类权限模式：徽章 `[<label>]`（琥珀色）。
   - 输入一行长文字：右侧状态自动消失，不与输入重叠、不残留。
   - 窗口拉窄到 60 列再操作一遍，无换行错乱。
3. 若 `readLine` 四参重载导致历史/续行行为异常（极小概率），回退判据：仅保留
   `buildPrompt()` 徽章部分、放弃 right-prompt，并在完成报告说明。

### 禁止事项

- 不要碰 `TurnStatusRenderable` / TurnView（回合中的状态行已有自己的体系）。
- 不要尝试用 `org.jline.utils.Status` 做底部钉住栏（与 JLineDisplayRegion 的
  Display 双写同一终端区域会互相踩踏，这是本任务明确否决过的方案）。
- 不要在 `buildRightPrompt` 里做任何 IO 或加锁以外的耗时操作（每次 readLine 都会调用）。

---

## 完成报告要求

逐任务报告：做了什么、`./mvnw test` 的 `Tests run` 总数、手动验收清单逐条结果
（做不了交互验证的条目如实标注"未验证，需要人工确认"）、所有与工单的偏离及原因。
