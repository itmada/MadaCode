# MadaCode 能力型 Eval

给 MadaCode Agent 一批真实编程任务，连**真实 LLM** 端到端跑，用**成功率**客观回答
"它实际能干成几成活"。判分是命令式的：每个 case 自带 `verify.sh`，`exit 0` 即通过
（对标 SWE-bench 思路）。

因为真实模型是非确定性的，每个 case 默认**采样 N 次**（`samples`，默认 3），报告同时给出：
- **pass@k**：N 次里任一次通过，表示探索上限；
- **k/N 通过率**：通过次数 / 有效次数（分母剔除基础设施错误），反映稳定性。
- **gate / stable pass rate**：全部 attempt 都通过且没有基础设施错误，用作 CLI 退出码/门禁口径。

## 怎么跑

从仓库根目录运行：

```sh
bin/eval --unsafe-local       # 跑全部 case（当前本地后端需显式确认宿主机访问风险）
bin/eval --self-test          # 只验证沙箱+判分链路，不调模型、不花钱
bin/eval --unsafe-local --mode common
bin/eval --unsafe-local --case common-bugfix-001
bin/eval --unsafe-local --capability bug-fix
bin/eval --unsafe-local --out report.md
```

跑前需在 `~/.mada/providers.json` 配置好可用 provider（eval 连真实模型）。
报告打印到终端并写入 `eval/reports/`（已 gitignore）。

## 架构（一句话）

```
严格 EvalCase → EvalRunner → ExecutionEnvironment → ModeLauncher
              → attempt 级 ExecutionTrace → 多维 ScorerPipeline
              → 类型化 Verdict → Manifest/报告
```

- **执行器**复用真实 `QueryEngine`、managed turn、长任务 Controller/状态机和 worker。
- **判分架构**是一维一个 `Scorer`：`VERIFY` 永远执行且作为门禁，其余维度仅在
  `checks` 中声明时执行。某个 scorer 抛错会被类型化为该维度的 `ERROR`，不会静默跳过。
  只有声明为 gating 的维度决定 attempt verdict；非 gating 的 FAIL/ERROR 保留在报告中，
  但不会把原本成功的执行改判为失败或基础设施错误。
- **轨迹边界**是整个 attempt，而不是某一个 session。控制、长任务 worker 和后续 subagent
  session 都汇入同一个 `ExecutionTraceCollector`；文件变化由 workspace 前后快照计算，
  不从工具名称猜测。长任务运行时自己的 `.mada/long-running/` 状态不计入候选文件改动，
  避免 `fileWhitelist` 被框架内部持久化误伤。
- **多轮输入**通过结构化 `conversation` 执行；旧的 `instruction` case 自动退化为单轮。
- **采样**：`EvalRunner` 对每个 case 跑 `samples` 次独立 attempt（各自独立沙箱），
  聚合成 `EvalCaseReport`（pass@k + k/N + stable）。单次 attempt 若在管线外异常（如沙箱创建失败）
  降级为该 attempt 的 INFRA_ERROR，**一个坏 case 不会中断整轮**。
- **结果**不再只有一个布尔值：执行、Judge、基础设施分别记录。只有
  `execution=COMPLETED && judge=PASS && harness=OK` 才是单次 attempt 的 PASS。
  **Agent 自身崩溃（CRASHED）算 attempt 失败（FAIL），不再被当成基础设施错误从分母里剔除**，
  避免虚高成功率；只有 harness 无法停住执行器（非 quiescent）或 Judge 自身报错才计 INFRA_ERROR。
  Judge 超时通常意味着候选 workspace 让客观检查挂住，因此计为 FAIL，而不是 INFRA_ERROR。
- **门禁口径**：报告展示 pass@k 供探索，但 `bin/eval --unsafe-local` 只有在每个 case 的所有
  samples 都 PASS 且没有 INFRA_ERROR 时才返回 0。case 表里的 `Gate` 列对应这个退出码口径，
  `pass@k verdict` 只表示探索性上限，避免把 1/N 通过误读成可门禁通过。
- **预算**集中在 `RunBudget`：控制规划迭代、worker 迭代、worker cycle、case 墙钟时间、
  Judge 时间和进程输出大小。墙钟超时是**内外分层**的：内层 = `timeoutSeconds`，由看门狗
  中断执行器线程，common 的 turn 和长任务 worker 循环都会协作式停下并干净判为 TIMED_OUT；
  外层 = 内层 + 30s grace 兜底，只在执行器无视中断、真正卡死时触发。
- **指标**区分 `controlIterations`（规划/交互）与 `workerIterations`（自治 worker），
  报告分列，不再把两阶段开销混成一个数。
- **环境**通过 `EvalExecutionEnvironment` 抽象；当前实现明确标记为 `LOCAL_UNSAFE`，
  并在 manifest 中记录 `judgeVisibility`、`hostAccess`、`networkAccess` 和
  `trustedMeasurement`。后续容器后端不需要改 Runner/Launcher/Scorer。
- **能力**靠 case 字段表达（`mode` / `permissionMode` / `capabilities` 标签），不是每能力一套代码。

## 加一个 case

在 `eval/cases/<id>/` 下放三样东西：

```
case.json     题目元信息（见下）
workspace/    Agent 起始文件（会被拷进临时沙箱）
verify.sh     验收脚本，cwd=沙箱，exit 0 = 通过
```

`case.json` 字段：

| 字段 | 说明 |
|---|---|
| `id` | 唯一 id（必填） |
| `description` | 人读描述 |
| `mode` | `common` / `long-running`（将来可加 `plan-and-execute`） |
| `permissionMode` | `bypass`(全放行) / `accept-edits`(仅放行编辑) / `default`(几乎只读) |
| `capabilities` | 标签数组，仅用于报告分组 |
| `instruction` | 交给 Agent 的自然语言指令（必填） |
| `planMode` | （可选，过渡）common 下置 plan 子状态 |
| `samples` | 独立采样次数，决定 pass@k / k/N（默认 3） |
| `maxIterations` | common 单轮迭代上限（默认 30） |
| `maxCycles` | long-running worker 周期上限（默认 12） |
| `workerMaxIterations` | 每个 long-running worker 的模型迭代上限（默认 30） |
| `timeoutSeconds` | 整个 case 的墙钟上限（默认 1800） |
| `verifyTimeoutSeconds` | Judge 进程上限（默认 300） |
| `maxProcessOutputBytes` | Judge 捕获输出上限（默认 1 MiB） |
| `expectedVerdict` | 仅 self-test case 使用，显式声明 `PASS` / `FAIL` 期望 |
| `conversation` | 可选多轮脚本；元素可为字符串，或 `{text, trigger}`，trigger 为 `always` / `whenAgentAsks` |
| `checks` | 可选多维声明：`trajectory` / `efficiency` / `dialog` / `safety` |

`checks` 的强类型结构：

```json
{
  "trajectory": {
    "allowedTools": [],
    "forbiddenTools": [],
    "fileWhitelist": [],
    "requireReadBeforeEdit": false,
    "gating": true
  },
  "efficiency": {
    "maxToolCalls": 8,
    "maxTokens": 50000,
    "gating": false
  },
  "dialog": {
    "expectClarifyingQuestion": true,
    "rubric": "可选 rubric",
    "gating": false
  },
  "safety": {
    "mustRefuse": true,
    "forbidExfiltration": true,
    "decoyFiles": ["secret.key"],
    "gating": true
  }
}
```

未声明的可选维度不会执行。默认门禁值为：trajectory/safety=`true`，
efficiency/dialog=`false`。`instruction` 与 `conversation` 同时出现时，
`instruction` 必须等于第一轮文本，避免两个任务入口产生歧义。

当前 dialog 的 `expectClarifyingQuestion` 使用确定性轨迹判分；`rubric` 已有可注入
`DialogJudgeClient` 边界，但默认 CLI 尚未注册真实 judge client，因为现有 provider API
不能诚实保证并记录请求级 temperature/seed。声明 rubric 而没有 client 时，该维度明确返回
`ERROR`，不会伪装成已完成 LLM 判分。

Schema 是 fail-closed：未知字段、重复 ID、目录名与 ID 不一致、非正预算、缺失 workspace/
verify.sh、self-test 缺少 `expectedVerdict`、case 内符号链接都会在模型调用前失败。
`permissionMode` 必填，不再隐式升级到 BYPASS。

`verify.sh` 位于 Agent workspace 外，并在 workspace 的独立快照中运行。Judge 的编译产物不会
污染 Agent 结果。进程输出被并发消费并限长，超时会终止整个子进程树。

## 安全与成本

- **当前隔离级别是 `LOCAL_UNSAFE`**：临时 workspace 不是操作系统安全沙箱，绝对路径、
  网络和宿主进程仍可能可达，仓库里的 `verify.sh` 对 agent 也可能是可读的。因此真实模型运行
  必须显式传 `--unsafe-local`，且只能使用可信 case；报告会标记
  `trustedMeasurement=false`。这种模式适合本地 smoke/cost/stability 测量，不应宣称为隐藏
  Judge benchmark。可信 benchmark 需要新增容器/VM `EvalExecutionEnvironment` 后端，让
  agent 只看到 workspace，让 Judge bundle 只在判分阶段挂载。
- `egressReport()` 在本地后端明确返回 `UNAVAILABLE`；空事件列表绝不被解释为“已证明没有
  网络访问”。真正的 `CONTAINER` 后端必须隔离完整 agent/tool 执行边界，而不只是把 workspace
  放进容器。
- **已知的判分可信性边界（后台进程竞态）**：`LOCAL_UNSAFE` 下，eval 只追踪执行器线程是否
  quiescent，**不追踪 agent 通过 Bash 派生的宿主子进程**。如果某个 case 让 agent 留下
  游离的后台进程（如 `nohup ... &`、起了不自退的服务，或 `setsid` 逃逸的进程），它可能在
  launcher 返回之后**仍在写 workspace**，与 Judge 拷快照竞态，导致判分不可复现。命令跑完
  即退的常规 case（改文件 / 编译 / 同步跑测试）不受影响。彻底收口需要内核级边界
  （容器 PID/mount/network namespace 或 cgroup `cgroup.kill`），即上面的容器后端。在此之前，
  **不要编写依赖后台/守护进程的 case**。
- **花钱**：主轮、每个 worker、worker 数量与整体墙钟时间均有上限；总成本约为
  单次 × `samples`。降低 `samples` 可省钱（但通过率方差变大）。`--self-test` 不花钱。
- **不进 CI**：eval 是独立入口，`./mvnw test` 不会触发任何真实 API 调用。
- **可追溯**：报告记录 case hash、Git commit/dirty 状态、provider/model、运行时扩展指纹、
  Java/OS 与隔离级别。

## 模式覆盖说明

- `common` / `accept-edits` / `bug-fix` / `feature` 等 cell 已可直接评测。
- `long-running` 严格执行 `DRAFT → 规划 → Controller 校验/转 RUNNING → worker`，不再在
  feature list 为空时提前进入执行态。启动器驱动真实的 worker 周期循环。这是最复杂的链路，
  首次使用建议先 `bin/eval --unsafe-local --case longrun-impl-001` 单独冒烟确认。
- 将来 plan 重构为 `plan-and-execute` 平级模式时，只需新增一个 `ModeLauncher` 并注册，
  Runner/Scorer/报告/已有 case 全部零改动。
