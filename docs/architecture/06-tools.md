# 四个公开工具

MCP 的 `tools/list` 里**只有四个工具**。它们是**通道**，不是功能。

> "建房子""找物品""聊天"这些是 `goal.ability` 的取值，**不是工具**。
> 四个通道负责"观察 / 规划 / 启动 / 控制"，具体做什么写在载荷里。

## 总览

| 工具 | 一句话 | 只读 | 会改状态 | 幂等 |
| --- | --- | :---: | :---: | :---: |
| `perceive` | 看现状，或等消息 | ✅ | ❌ | ✅ |
| `plan` | 编译目标成步骤清单，**不执行** | ❌ | ❌ | ❌ |
| `execute` | 异步启动目标或计划 | ❌ | ✅ | ❌ |
| `task` | 查询 / 暂停 / 恢复 / 取消 / 回答 | ❌ | ✅ | ❌ |

（"会改状态"指对**语义任务或世界**产生影响；`plan` 只登记计划，不算。
"幂等" = 同一个编号重复发来不会开出第二份，见[关键机制](04-mechanisms.md)第 7 节。）

---

## `perceive` —— 看，或者等

**用途**：读当前事实，或等待任务事件。

**主要参数**：

| 参数 | 作用 |
| --- | --- |
| `view` | 看哪一类：`situation` / `surroundings` / `abilities` / `tasks` / `attention` / `landmarks` / `machines` / `knowledge` … |
| `focus` | 在选定的 view 里精确读一个对象（例如某个能力的契约） |
| `query` | 模糊搜索候选（与 `focus`、`resource_uri` 互斥） |
| `resource_uri` | 直接读知识或冻结回执；**给了就默认 `view=knowledge`** |
| `task_id` | 只看某一个任务（仅 `tasks` / `attention`） |
| `stream_id` / `after_cursor` | 事件游标（仅 `attention`） |
| `wait_ms` | 等多久（仅 `attention`；见[Attention 与等待](07-attention.md)） |

**边界**：不同 view 只接受自己的字段。例如 `sections` 只给 `situation` / `surroundings`，
`wait_ms` 只给 `attention`；填错就地拒绝，**不静默忽略**。

**到语义部分**：`attention` / `tasks` / `landmarks` 读 `IntentRuntime` 的记录；
其余 view 是现场观察。

**三条旁路**（不走游戏线程的常规路径）：

| 请求 | 去哪 |
| --- | --- |
| `view=knowledge` | 离线知识库，不要求进世界 |
| `view=knowledge` + 回执 URI | 直接读冻结回执，连知识库都不查 |
| `view=attention` + `wait_ms>0` | 挂起等待，而不是立即求值 |

---

## `plan` —— 先算清楚，但不动手

**用途**：把组合目标展开成有编号的步骤清单，并做一次检查；**不接管身体、不走路**。

**参数**：`goal` **或** `plan_id`（二选一），外加 `path` / `offset` / `limit`（读取已存计划）。

**它做什么**：

```text
bindForRequest            检查这请求属于当前世界吗
  ├─ 给 plan_id → 读回已存计划（只读冻结输入，不重新编译）
  └─ 给 goal    → 机器类预检 → 编译成步骤清单 → 存进 plans
返回：计划摘要 + 能否执行
```

**它不做什么**：

> **不算路线、不算材料、不证明一定能做成。**
> 这些要等真正执行时，结合世界里的情况判断。

**验证范围**：能力是否认识、参数名是否声明、目标类型是否允许；
**不检查**"现在能不能做到"。

---

## `execute` —— 开始做

**用途**：异步启动一个目标或计划。返回任务编号，之后靠 Attention 观察。

**参数**：`goal` **或** `plan_id`，外加 `request_key`。

**它做什么**：

```text
bindForRequest + requireRecoveredState    世界可用？旧检查点恢复了吗？
  ├─ 有 plan_id → 取出计划里的目标
  ├─ request_key 去重                      重复请求直接返回原任务
  ├─ 非只读设计 → 申请接管玩家身体
  └─ IntentRuntime.execute
        ├─ 校验目标、建总任务单、登记、发 started 事件
        └─ CompanionTickDispatcher.submitCurrent  ★ 丢给身体
返回：task_id / accepted / control_status / next_attention
```

**返回值**：

| 字段 | 意思 |
| --- | --- |
| `task_id` | 任务编号；之后查询、暂停、取消都用它 |
| `accepted` | **只是"已登记"**，不是做完 |
| `status` | 终态优先；否则 `waiting_for_decision` / `paused` / `running` |
| `control_status` | 这次有没有去抢玩家身体 |
| `next_attention` | 拼好的下次等待参数，原样拿去调 `perceive` |

`control_status` 三个值：

| 值 | 意思 | 什么时候出现 |
| --- | --- | --- |
| `takeover_requested` | **已申请接管**身体 | 正常的新请求 |
| `not_requested` | **没有再申请** | 这是重复请求（之前已申请过） |
| `not_required` | **不需要**接管 | 只读设计（不碰身体） |

> 申请 ≠ 立刻生效：它只是登记，真正抢在**下一个游戏刻**发生，
> 不会在处理请求的中途把玩家的键盘抢走。

**边界**：

- `accepted: true` 只表示**已登记**，不表示做完。
- 它**不做** `plan` 的那次预检。
- **`plan` 不是它的前置**：可以直接 `execute(goal)`。

---

## `task` —— 查、管、答

**用途**：查看任务、暂停 / 恢复 / 取消，或**回答任务提出的问题**。

**参数**：`action` ∈ `get` / `list` / `pause` / `resume` / `cancel` / `answer`；
`task_id`（`list` 以外必填）；`path`（仅 `get`，逐项读完整证据）；`answer`（仅 `answer`）。

**`answer` 的两种含义必须分开**：

| `choice` | 必须带什么 | 意思 |
| --- | --- | --- |
| `recover` | `details.goal`（且不能带 `parameters`） | 在当前步骤**之前插入**前置目标 |
| `replace_goal` | `details.goal` | **换掉**当前这一步 |
| `retry` | `details.parameters`（不能带 `goal`） | 用新参数重试当前步骤 |
| `skip` | 什么都不带 | 明确略过这一步 |
| `cancel` | 什么都不带 | 停止整个任务 |

**边界**：`retry` 不是万能的——**已经提交、结果不确定的操作不允许普通重试**
（否则会重复消费）。这时要用 `recover` / `replace_goal` / `skip` / `cancel`。

---

## 四个工具怎么到达语义部分

```text
tools/call
  ▼
EmbeddedMcpService.callTool
  ├─ 名字是不是四个之一？
  ├─ PublicToolCatalog.validateAndNormalize   形状校验
  ├─ execute 专属：没给 request_key 就自动生成
  └─ switch(name)
       perceive ─► runtime.perceive
                    ├─ knowledge   → 离线知识库
                    ├─ surroundings→ 分帧采样
                    ├─ attention   → 挂起等待或立即读
                    └─ 其余        → onClient → IntentRuntime 的记录
       plan     ─► runtime.plan     → onClient → IntentRuntime.compile
       execute  ─► runtime.execute  → onClient → IntentRuntime.execute ★
       task     ─► runtime.task     → onClient → IntentRuntime.task / pause / cancel / answer
```

**共同点**：都在 `onClient` 里切到游戏线程
（见[结构地图](01-structure.md)的"`onClient`：把请求搬到游戏线程"一节），
除非走了上面那三条旁路。

---

## 相关代码

- 定义与校验：`PublicToolCatalog`
- 分发：`EmbeddedMcpService`（`callTool`）
- 落地：`MaiCraftRuntimeFacade`、`IntentRuntime`
