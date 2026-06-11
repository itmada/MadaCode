# MadaCode 重构 — Thread Team 执行编排（Leader 文档）

> 本文档给 **leader 线程**使用，配合 thread-team skill 的六阶段工作流执行 [REFACTOR_PLAN.md](REFACTOR_PLAN.md)。
> 任务的具体内容、做法、验收标准**一律以 REFACTOR_PLAN.md 中对应编号为准**，本文档只负责编排：谁并行、谁串行、怎么合并。

## 前置动作（开任何 worker 之前）

1. 把 `REFACTOR_PLAN.md` 和本文档 **commit 到集成分支**。worker 线程在独立分支上工作，看不到 leader 未提交的文件。
2. 从当前 `main` 切出集成分支（建议 `refactor/integration`），全程由 leader 独占写入。worker 分支一律从**集成分支的最新提交**切出，禁止从 `main` 或其他 worker 分支切。
3. leader 先自己跑一次 `./mvnw test`，确认基线全绿并记录耗时（作为后续回归参照）。

## 波次编排

原计划的阶段依赖不变，但同一波内的任务文件域互不相交，可以并行。**一波全部合并并回归通过之前，不开下一波的 worker。**

| 波次 | Worker 数 | 任务分配 | 并行依据 / 风险 |
|---|---|---|---|
| W1 | 3 | A: P0-1 / B: P0-2 / C: P0-3 | 三个全新测试文件，禁止改 `src/main`，零冲突 |
| W2 | 2 | A: P1-1 → P1-2 → P1-3（**同一分支按序三个 commit**） / B: P1-4 | P1-1/2/3 都改 `ToolExecutor`，必须同 worker 串行；P1-4 只动 `QueryEngine` |
| W3 | 2 | A: P2-1 / B: P2-2 | 两者都会碰 `bootstrap` 装配类，预期小冲突，由 leader 在合并时解决（见合并规则 3） |
| W4 | 1 | A: P3-1 → P3-2 → P3-3（同一分支按序三个 commit） | 严格依赖链且都动 `ConversationSession`/`Repl`，无并行空间，单 worker 串行 |
| W5 | 2 | A: P3-4 / B: P3-5 | P3-4 动 coordinator/screen，P3-5 动 longrunning store + `AtomicFiles`（含 `SessionStorage` 一处改造），文件域基本不相交 |
| W6 | 1 | A: P4-1 → P4-2（同一分支，按计划要求拆 commit） | 全计划风险最高的一波；不并行，leader 在第 6 阶段对这一波做加倍审查（transcript 兼容、resume 验证） |
| W7a | 3 | A: P5-1 / B: P5-2 / C: P5-3 | services/api、prompt、render 三个包，互不相交 |
| W7b | 1–2 | A: P5-4 / B: P5-5 | P5-4 依赖 P5-2 的 system prompt 指纹缓存已合入；P5-4 动 serializer/Provider，P5-5 动 Repl.Config/QueryEngine/AGENTS.md，可并行但若资源紧张就单 worker 串行 |

## Worker 初始化与派发要求

按 skill 的第 2、3 阶段执行，额外加上本项目的硬性内容：

1. **初始化消息**必须包含：协作模型全文（按 skill 要求）、worker 自己的分支名、leader 线程 ID、全员名册。
2. **派发消息**必须包含：
   - REFACTOR_PLAN.md 的「全局规则」一节**全文**（尤其是对 AGENTS.md 测试条款的覆盖声明——worker 读到仓库里的 AGENTS.md 会拒绝写测试，必须用这条压过去）；
   - 被分配任务的编号与指令："先读仓库根目录 REFACTOR_PLAN.md 中任务 Px-y 的完整描述再动手"；
   - 任务边界声明：**只做这一个（组）任务**，发现相邻问题记录在报告里报给 leader，不许顺手修；
   - 完成定义：实现 → 自审 diff → 修复自审发现 → `./mvnw test` 全绿 → 在 worker 分支 commit → 向 leader 发结构化报告（含验收标准逐条核对结果、测试输出摘要、改动文件清单）。
3. W2-A、W4-A、W6-A 这类**单 worker 多任务**的派发：明确要求每个任务编号一个独立 commit、按编号顺序执行、前一个任务测试不绿不开下一个。

## 合并规则（skill 第 5 阶段的本项目细则）

1. 同一波内的合并顺序：**改动面大的先合**（W3 先合 P2-1 再合 P2-2；W7a 先合 P5-2，再 P5-1，最后 P5-3——P5-2 改动跨包最广）。后合的分支冲突由 leader 解决，倾向以先合入的为准。
2. 每合并**一个** worker 分支就跑一次 `./mvnw test`，失败先修复再合下一个，不允许带病叠加。
3. bootstrap 装配类（`EventsAssembly`、`EngineAssembly` 等）是已知冲突热点：冲突时以"注入点更靠近构造、静态调用更少"的一侧为准。
4. 特征测试（P0 产物）在后续所有波次中**只允许任务描述明确说可改的地方改**（P4-1 对 separator/barrier 断言的更新是唯一预期内的大改）。worker 报告里若出现"为了让测试通过修改了 P0 测试"，leader 必须在第 6 阶段逐条核对该修改是否被任务授权。

## Leader 状态记录

按 skill 要求维护 leader state，本项目至少跟踪：当前波次、各 worker（线程 ID / 分支 / 任务编号 / 状态 / 报告是否收到）、本波合并顺序与进度、累计已合入的任务编号、回归测试最近一次结果、待裁决事项。上下文被压缩后，依据本文档 + `git log` + `list_threads` 恢复状态。

## 与原串行计划的差异声明

- 原计划"每次投喂一个任务"的节奏被波次取代；任务内容与验收标准不变。
- 原计划允许 P5-* 在阶段 2 后任意插入；thread-team 模式下为控制合并复杂度，统一放到 W7，**不要**提前穿插。
- W4 与 W6 实质是"单线程执行"，thread-team 在这两波只提供隔离分支和强制报告纪律，不提供加速——这是依赖结构决定的，不要为了并行而强拆。
