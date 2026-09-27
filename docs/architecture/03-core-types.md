# 核心类型与数量关系

这一节回答两个问题：**有哪些类型**，以及**各有多少个**。

---

## 一、五个核心类型

```text
Goal              ──  想做成什么（纯描述，不执行）
  ↓
IntentAction      ──  下一步动作（每刻翻译出来的，八种变体之一）
  ↓
TaskRecord        ──  一件具体工作的任务单（纯数据）
  ↓
Task              ──  逐刻做事的执行器（有行为）
```

另有一个贯穿全程的**总任务单** `IntentTaskRecord`，它本身也是 `TaskRecord` 的子类。

| 类型 | 是什么 | 会自己动吗 |
| --- | --- | --- |
| `Goal` | 语义目标：能力、结果描述、目标对象、参数 | ❌ |
| `Plan` | 带编号的步骤清单（`Goal` 列表的冻结副本） | ❌ |
| `IntentAction` | 翻译结果：下一步动作，八种变体 | ❌ |
| `TaskRecord` | 具体任务单（含总任务单） | ❌ |
| `Task` | 执行器接口（`start` / `tick` / `result`） | ✅ |

---

## 二、`Goal`

| 字段 | 必须 | 说明 |
| --- | --- | --- |
| `ability` | ✅ | 要哪个能力，例如 `maicraft:sleep` |
| `outcome` | ✅ | 一句话描述要什么结果 |
| `target` | ❌ | 对象是谁（当前地点 / 坐标 / 地标名 / 人物 / 前一步结果） |
| `parameters` | ❌ | **由各能力自己定义**的参数 |
| `preferences` | ❌ | 偏好；能力没声明就必须为空 |
| `constraints` | ❌ | 约束；同样要能力声明 |
| `children` | ❌ | 子目标，**只有 `maicraft:sequence` 能用** |

**参数内容取决于能力**：`maicraft:chat` 要 `text`，`maicraft:travel` 要目的地，
`maicraft:sleep` 不需要参数。

### 拆步骤：只有 `sequence` 才展开

一个 `Goal` 可能对应 1 个步骤，也可能对应 N 个步骤。规则很简单：

```text
goal.executableSteps()
  ├─ 是 maicraft:sequence → 把 children 递归拍平成 N 个
  └─ 其他能力             → List.of(this)，就 1 个
```

**这不是"智能规划"，只是把 LLM 已经写好的结构拍平。**

LLM 用 `children` 排好顺序，Mod 负责展开：

```json
{
  "ability": "maicraft:sequence",
  "children": [
    {"ability": "maicraft:acquire_items", "parameters": {}},
    {"ability": "maicraft:build", "parameters": {}}
  ]
}
```

→ 拍平成 **2 步**。如果直接传 `maicraft:sleep`（不是 `sequence`）→ **1 步**。

> ⚠️ **"怎么做到"不在步骤里。**
> "睡觉"要"找床 / 放床 / 走过去"，是**执行时**由翻译决定的，
> 属于**一个步骤内部的动作**，不是新的步骤。见[关键机制](04-mechanisms.md)第 6 节。

### `Plan`：带编号的步骤清单

`plan` 工具把 `Goal` 展开成步骤并**存起来**，就得到一份 `Plan`：

| 字段 | 内容 |
| --- | --- |
| `id` | 计划编号（就是 `plan_id`） |
| `goal` | 原始目标 |
| `steps` | 展开后的步骤 |
| `createdGameTime` | 编译时刻 |

它**只是登记**——不算路、不算材料、不证明做得到。
它和任务单的区别见下面"步骤清单 vs 任务单"。

---

## 三、`IntentAction`（八种变体）

翻译每刻产出**恰好一个** `IntentAction`。它是一个 sealed 接口，八种变体：

| 变体 | 携带什么 | 谁执行 |
| --- | --- | --- |
| `Pending` | 无 | 下一刻重新判断 |
| `Report` | 一个 `TaskResult`（+ 可选确认位置） | 语义部分当场处理 |
| `Native` | **一张 `TaskRecord`** | 建子任务，父任务驱动 |
| `Chain` | 一组内部动作 | 依次执行 |
| `Tool` | 工具名 + JSON 参数 | 调工具；接住它的任务单，或收下它的即时结果 |
| `Decision` | 一个问题快照 | 暂停，等调用者回答 |
| `Remember` | 地标名 + 位置 | 写进地标表 |
| `Wait` | 条件 + 最早检查时刻 | 逐刻检查条件 |

因为它是 `sealed` 接口，**漏写任何一个变体编译器都会报错**。

---

## 四、`TaskRecord`

具体任务单的基类。只记数据，不含行为：

| 字段 | 说明 |
| --- | --- |
| `id` | 内部短编号（`t123`） |
| `toolName` | 谁发起的（`goto` 等），用于查询和排错 |
| `toolCallId` | 结果该送回给谁 |
| `deadlineGameTime` | 最晚什么时候做完（**游戏刻**，不是秒表） |
| `state` | `PENDING`（还没开始）/ `RUNNING`（进行中）/ 终态（`SUCCESS` / `FAILED` / `TIMEOUT` / `CANCELLED`） |
| `result` | 最后的结果 |

**创建的任务单状态是 `PENDING`，不会执行任何东西。**

### 总任务单 `IntentTaskRecord`

它是 `TaskRecord` 的子类，另加：

| 组 | 记什么 |
| --- | --- |
| 身份 | 对外 UUID、引用的计划、原始目标、**世界身份** |
| 进度 | 步骤索引、每步结果、失败尝试、内部位置、保护范围 |
| 等待 | 暂停快照、待答问题、已收到未处理的答复 |
| 结束 | 终态快照 |

### 步骤清单 vs 任务单

两者容易混。一个是"预告"，一个是"施工记录"。

| | 步骤清单（`Plan`） | 任务单（`IntentTaskRecord`） |
| --- | --- | --- |
| 是什么 | 静态预告 | 动态记录 |
| 记什么 | 目标 + 一串步骤 | 目标 + 步骤 + **进度 / 结果 / 问题 / 暂停 / 终态** |
| 会变吗 | ❌ 写完就固定 | ✅ 每刻在变 |
| 用来干什么 | 给外部看"要分几步" | 真正执行的凭据 |
| 丢了会怎样 | 无所谓，可重新编译 | 任务断了（要从检查点恢复） |
| 持久化 | ✅ | ✅ |

**关键**：两者用**同一个方法**（`goal.executableSteps()`）展开步骤，
所以**步骤内容完全一样**。

但 `execute` 用 `plan_id` 时，**只取计划里的 `goal`**，任务单会**自己再展开一遍**：

```text
execute(plan_id)
  └─ Plan plan = intents.plan(planId)
     goal = plan.goal()                          ← 只取 goal
     new IntentTaskRecord(..., goal, ...)
        └─ this.steps = goal.executableSteps()   ← 自己再展开
```

所以 `plan` 里那份 steps 是**给人看的预览**，不是执行依据。

---

## 五、数量关系（**最容易搞混的一节**）

### 静态结构

```text
1 个 Goal
   └─ executableSteps()   ──► 1 个步骤（普通能力）
                              或 N 个步骤（maicraft:sequence 展开 children）
```

### 翻译一次

```text
1 个步骤 ── 翻译一次 ──► 1 个 IntentAction
                            ├─ Native   ──► 1 张 TaskRecord
                            ├─ Tool     ──► 0 或 1 张 TaskRecord
                            ├─ Chain    ──► M 个子动作
                            └─ Decision / Wait / Pending / Report ──► 0 张
```

### 实例化

```text
1 张 TaskRecord ──► 1 个 Task（TaskFactory 按类型创建）
```

### 汇总表

| 从 | 到 | 数量 |
| --- | --- | ---: |
| 一次 `execute` | `IntentTaskRecord` | 1 |
| 1 个 `Goal` | 步骤 | 1（普通）/ N（`sequence`） |
| 1 个步骤 · 翻译一次 | `IntentAction` | 1 |
| 1 个 `Native` | `TaskRecord` | 1 |
| 1 个 `Tool` 调用 | `TaskRecord` | 0 或 1 |
| 1 个 `Chain` | 子动作 | M（≥1） |
| 1 个 `Chain` 整体 | `TaskRecord` | 0..M（顺序产生，不并发） |
| 1 张 `TaskRecord` | `Task` | 1 |
| 1 个 `IntentTaskRecord` | `IntentTask` | 1 |
| `IntentTask` · 同一刻 | 子任务 | 0 或 1 |

### 两个维度必须分开看

| | 同一刻 | 跨时间 |
| --- | --- | --- |
| `current` 槽 | 0 或 1 个任务 | 换来换去，很多个 |
| `IntentTask` 的子任务 | 0 或 1 个 | 有几步就有几个 |
| 一个步骤产生的 `TaskRecord` | — | **可能很多个**（重试、重译） |
| 每刻实际被驱动的 `Task` | 0 或 1 | — |

**为什么跨时间会有很多个？**

因为 `IntentTask` 会**反复翻译**：只要没有子任务在跑，它每个游戏刻都重新
`adapt()` 一次。所以：

> **同一时刻是严格的 1:1；跨时间可能是 1:N。**

---

## 相关代码

- 目标与计划：`Goal`、`Plan`
- 动作：`AbilityAdapter`（内嵌 `IntentAction` 定义）
- 任务单：`TaskRecord`、`IntentTaskRecord`
- 执行器：`Task`、`TaskFactory`、`AbstractCompanionTask`
