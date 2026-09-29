# 结构地图

项目分成几个**部分**。它们不是一条直线——有的互相嵌套，有的被多方调用。

先看两条线，再看每个部分，最后看**谁驱动谁**（这一节最重要）。

---

## 两条线

系统有两个入口，它们在调度器的**任务槽**汇合。
（调度器有两个槽：`sync` 和 `current`；总任务单进 `current`，见 ④。）

```text
【请求线】网络触发，一次
  LLM ──HTTP──► 入口 ──► 语义（登记）
                            │
                            ▼ 放进「current 槽」
                    ┌───────────────┐
                    │  current 槽   │  ← 两条线在这里汇合
                    └───────────────┘
                            ▲
                            │ 每刻挑一个
【执行线】游戏刻触发，每刻
  心跳 ──► 调度 ──► 任务 ──► 身体操作 ──► 真实游戏
```

---

## 六个部分

| # | 部分 | 负责什么 | 主要类 |
| --- | --- | --- | --- |
| ① | 入口 | 收请求、查格式、搬到游戏线程 | `EmbeddedMcpService`、`PublicToolCatalog`、`MaiCraftRuntimeFacade` |
| ② | 语义 | 把请求变成「总任务单」；绑定世界；保存；发事件 | `IntentRuntime`、`IntentTaskRecord` |
| ③ | 翻译 | 看现场，把当前目标翻成"下一步动作" | `AbilityAdapter`、`GeneralAbilityAdapter`、`MachineAbilityAdapter` |
| ④ | 调度 | 每刻挑一个任务来跑 | `CompanionTickDispatcher`、`CompanionBrain`、`TaskSlot`、`TaskSelector` |
| ⑤ | 任务 | 逐刻做事 | `IntentTask`、`CompanionTask` |
| ⑥ | 身体操作 | 按键、镜头、点击、菜单 | `LocalPlayerContext`、`BodyControlPort`、`NativeActionPort`、`MenuPort` |

另有一个**心跳**：`ClientRuntime.tick`。它不是"部分"，是**节拍器**——
每刻把执行线推一遍。

---

## 谁驱动谁（**最重要的一节**）

上面六个部分**不是**一条直线。真实的调用关系是：

```text
心跳 ClientRuntime.tick
  └─► 调度（每刻挑一个任务）
        └─► 任务（被挑中的那个）
              ├─ 语义任务 IntentTask
              │    ├─ 用「翻译」决定下一步做什么
              │    └─ 造一个「动作任务」并自己驱动它（不进任务槽）
              └─ 动作任务 CompanionTask
                   └─► 身体操作（按键 / 点击 / 菜单）
```

### 三处最容易搞错

| 容易以为 | 实际 |
| --- | --- |
| 语义在上、调度在下 | **调度驱动语义任务**（调度挑中它，调它的 `tick`） |
| 语义任务和动作任务是上下层 | 它们是**嵌套**：都是任务，动作任务是语义任务的**子任务** |
| 翻译是"语义下面的层" | 翻译是**被语义任务调用的一步**，给完答案就返回 |

> 为什么这样设计？见[关键机制](04-mechanisms.md)第 1、2 节。

---

## ① 入口

**职责**：把外部的 HTTP 请求，变成游戏线程上一个合法、可追踪的请求。

| 类 | 管什么 |
| --- | --- |
| `EmbeddedMcpService` | HTTP/JSON-RPC 传输、`tools/call` 分发、订阅、超时 |
| `PublicToolCatalog` | 四个公开工具的定义与**格式校验** |
| `MaiCraftRuntimeFacade` | 把请求切到客户端线程、返回可撤回的结果 |

**三个要点：**

1. **对外只有四个工具**：`perceive` / `plan` / `execute` / `task`。
   具体能力（`maicraft:sleep` 等）写在 `goal.ability` 里，**不是工具**。
   （见[四个公开工具](06-tools.md)。）
2. **`onClient` 是线程边界**：见下面单独一节。
3. **校验分两处**：这里只查**格式**（字段名、类型、长度、枚举）；
   能力是否认识、参数是否合法，交给**语义部分**。

### `onClient`：把请求搬到游戏线程

网络线程**不能碰游戏对象**（它们不是线程安全的）。所以每个请求都要先搬到客户端线程：

```text
网络线程                     客户端线程
  │
  ├─ 造一个 future（结果通道）
  ├─ 造一个闭包（把 future 和"要做什么"包进去）
  ├─ minecraft.execute(闭包) ──────► 闭包执行
  │                                    ├─ 调用真正的业务方法
  │                                    └─ 结果写进 future
  └─ 返回 future（CompletionStage）◄────────┘
```

三个要点：

| 要点 | 说明 |
| --- | --- |
| **快路径** | 如果本来就在客户端线程上，**直接执行**，不排队 |
| **撤回语义** | future 同时是"取消状态机"，能区分"还没开始"和"已经开始" |
| **异常两条出口** | 业务方法抛异常 → future 以失败完成；调度本身失败 → 直接拒绝 |

> **为什么要区分"还没开始 / 已经开始"？**
> 网络传输可能超时。超时后要能回答："这次请求到底动过游戏没有？"
> 没动过可以安全重试；动过了只能说"结果未知"。

---

## ② 语义

**职责**：把"一件事"登记下来、绑定到当前世界、保存、发事件。

> 真正逐刻推进这件事的是 `IntentTask`——它是一个**语义任务**，按"任务"归 ⑤。

| 类 | 管什么 |
| --- | --- |
| `IntentRuntime` | 状态仓储 + 生命周期协调：任务表、地标、请求键、检查点 |
| `IntentTaskRecord` | **总任务单**：目标、步骤、进度、问题、暂停、终态 |

**三个要点：**

1. **绑定世界身份**：每个任务带创建时的存档/服务器身份。
   换世界或换连接后，旧任务不能被误用。
2. **恢复 ≠ 重新批准**：从磁盘恢复的未完成任务先以**暂停**状态回来，
   要明确要求继续才动。
3. **对外只给投影**：内部坐标、路线、点击位置不会原样给外部；
   要完整证据时用 `task(get, path=...)` 按需读。

---

## ③ 翻译

**职责**：**看现场**，把当前语义目标翻译成"下一步该做什么动作"。

入口是 `AbilityAdapter.adapt(goal, player, runtime)`，按能力分三组：

```text
MachineAbilityAdapter.supports(...)   → 机器类
GeneralAbilityAdapter.supports(...)   → 战斗 / 交互 / 使用 / 采集 / 跟随 / 容器 ...
其余                                   → 一个 switch（travel / sleep / build / craft / trade ...）
```

**三个要点：**

1. **不改世界**：它只**读**世界、背包、地标，不做动作、不写世界。
2. **会被反复调用**：只要没有子任务在跑，每个游戏刻都重新翻译一次；
   所以它必须便宜、可重复调用。
3. **信息不全就返回 `Pending`**：例如方块还在扫描，下一刻再判断。

**它返回什么**：一个 `IntentAction`（见[核心类型](03-core-types.md)）。

---

## ④ 调度

**职责**：**玩家只有一双手**，每刻只能有一个使用者。

```text
TaskSelector 按顺序问"你能跑吗"，第一个说能的拿走身体：

  1. 反射（自救：落地 / 换气 / 自卫 / 休息）   多个，按注册顺序
  2. 同步槽 sync                                 0 或 1 个
  3. 当前槽 current ★                            0 或 1 个
  4. 空闲姿态                                    通常不用
```

| 类 | 管什么 |
| --- | --- |
| `CompanionTickDispatcher` | 客户端线程入口 + 身体生命周期 + 持有 `CompanionBrain` |
| `CompanionBrain` | 每刻选一个赢家、安全交接、驱动它 |
| `TaskSlot` | 任务单 ↔ 执行器：换、推、超时、结算 |
| `TaskSelector` | 纯选择逻辑（三档顺序） |

**三个要点：**

1. **被抢占不等于丢失**：`PREEMPTED` 保留进度，回来接着干。
2. **没轮到的任务会延后截止时间**：等别人干活不花自己的执行时间。
3. **子任务不进任务槽**：总任务把子任务放在自己的私有字段里驱动
   （见[关键机制](04-mechanisms.md)第 1 节）。

---

## ⑤ 任务

**职责**：逐刻做事。有两种，是**嵌套**关系。

| 任务 | 由谁创建 | 干什么 |
| --- | --- | --- |
| **语义任务** `IntentTask` | 语义部分（进 `current` 槽） | 决定下一步、翻译、造并驱动子任务 |
| **动作任务** `CompanionTask` | 语义任务私有创建 | 走路、挖、放、开菜单——真的动手 |

两者都实现同一个 `Task` 接口：`start` / `tick` / `result`。

**三个要点：**

1. **动作任务不进任务槽**：它是语义任务的私有子任务，由语义任务每刻亲自驱动。
2. **`start` 是一次性准备**：不是"以后每次拿到身体都重来"。
3. **`result` 会收尾**：关菜单、停导航——不只是查一下结果。

---

## ⑥ 身体操作

**职责**：**真实操作玩家身体**的那双手。再往下就是游戏本身。

入口是 `LocalPlayerContext`——**本刻**的玩家、世界、动作入口。每刻重新取得，
下一刻可能就换了（重生、换世界）。

它下面挂三个端口：

| 端口 | 管什么 |
| --- | --- |
| `body()` | 按键、镜头；**每刻要续发**，不续发就松开了 |
| `actions()` | 原生操作：提交 + 对账（见[关键机制](04-mechanisms.md)第 3 节） |
| `menus()` | 容器菜单：打开、搬运、确认 |

**三个要点：**

1. **必须每刻重新取**：不能把这一刻的入口留到下一刻用。
2. **每刻只能提交一次操作**：由 `mutationAvailable()` 管；
   这是"所有任务都每刻做一点"的根本原因。
3. **被两个部分使用**：任务日常操作用它；调度在换任务或结束时用它**松手**。

---

## 相关代码

- 入口：`EmbeddedMcpService`、`PublicToolCatalog`、`MaiCraftRuntimeFacade`
- 语义：`IntentRuntime`、`IntentTaskRecord`
- 翻译：`AbilityAdapter`、`GeneralAbilityAdapter`、`MachineAbilityAdapter`
- 调度：`CompanionTickDispatcher`、`CompanionBrain`、`TaskSlot`、`TaskSelector`
- 任务：`IntentTask`、`CompanionTask`、`TaskRecord`、`TaskFactory`、`AbstractCompanionTask`
- 身体操作：`LocalPlayerContext`、`BodyControlPort`、`NativeActionPort`、`MenuPort`
- 心跳：`ClientRuntime`
