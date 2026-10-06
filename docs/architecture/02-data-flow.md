# 一条指令的旅程

以一次 `execute` 为例，看它从进门到按键，再到结果回传，中间经过谁。

整个过程分三条流：

| 流 | 什么时候 | 线程 |
| --- | --- | --- |
| **提交流** | 请求到达时，一次 | 网络线程 → 客户端线程 |
| **执行流** | 之后的每个游戏刻 | 客户端线程 |
| **观察流** | 有进展或结束时 | 客户端线程 → 订阅者 |

---

## 一、提交流：请求 → 登记

```text
LLM
  │ HTTP POST /mcp
  ▼
EmbeddedMcpService
  │ tools/call
  ▼
PublicToolCatalog
  │ ① 是不是四个工具之一？
  │ ② 形状对不对（字段名、类型、范围）？
  ▼
MaiCraftRuntimeFacade.execute
  │ onClient：把工作排到客户端线程，返回一个 CompletionStage
  ▼
（客户端线程上）
  ├─ requireWorld()            有没有可用的玩家与世界？
  ├─ bindForRequest()          这份请求属于当前世界吗？
  ├─ requireRecoveredState()   旧检查点恢复了吗？
  ├─ Goal.fromJson(...)        或从 plan_id 取回目标
  ├─ request_key 去重          重复请求直接返回原任务
  ├─ dispatchExecution()       非只读任务先申请接管玩家身体
  ▼
IntentRuntime.execute
  ├─ validateGoal()            能力契约 / 可持久化 / 禁微观指令
  ├─ 再查一次 request_key      重复就返回原任务单
  ├─ new IntentTaskRecord(...) ★ 建「总任务单」
  ├─ 登记 tasks / requestKeys
  ├─ publish("started")        发一条 Attention 事件
  ├─ 只读设计？ → 直接跑完返回（不占任务槽）
  ▼
CompanionTickDispatcher.submitCurrent(player, record)
  ├─ bind(player)              没有 CompanionBrain 就 new 一个
  ▼
CompanionBrain.submitCurrent
  └─ current 槽 .put(record)
       ├─ 槽里有旧任务？ → 先按 REPLACED 收尾
       ├─ TaskFactory.create(player, record)  ★ 任务单 → 执行器
       └─ executor.start(player)              一次性准备
```

**回到网络线程**，返回（`onClient` 的 future 机制见[结构地图](01-structure.md)的 `onClient` 一节）：

```json
{
  "task_id": "...",
  "status": "running",
  "accepted": true,
  "control_status": "takeover_requested",
  "next_attention": { "view": "attention", "task_id": "...", "wait_ms": 30000 }
}
```

> `accepted: true` 只表示**已登记**，不表示事情做完了。

#### 三个名字容易看不懂

| 名字 | 干什么 |
| --- | --- |
| `bindForRequest` | 核对**世界身份**——这份请求属于当前存档/服务器吗（见[关键机制](04-mechanisms.md)第 4 节） |
| `requireRecoveredState` | 旧检查点还没恢复成功就**拒绝接单**，避免用空状态覆盖 |
| `dispatchExecution` | **申请接管玩家身体**。注意：它只是**登记**，真正接管在**下一个游戏刻**（`control_status` 的三个值见[四个公开工具](06-tools.md)） |

#### `request_key` 为什么查了两次

| 在哪 | 决定什么 |
| --- | --- |
| Facade | 要不要**再申请接管**（重复请求不该再抢一次控制权） |
| IntentRuntime | 要不要**开工**（重复请求直接返回**原来那张任务单**） |

两次都命中 → 不重复开工，也不重复申请接管。命中计数会写上原任务单，`task` 查询以 `deduplicated_request_hits` 呈现——走 attention 等终态的调用链看不到 execute 即时响应的 `deduplicated` 标注，靠这个计数分辨拿到的是旧任务还是一次新执行。

### 对照：`plan` 和 `execute` 有什么不同

两者参数几乎一样（都能给 `goal` 或 `plan_id`），但做的事完全不同。

| | `plan` | `execute` |
| --- | --- | --- |
| 机器预检 | ✅ | ❌ |
| 编译成步骤清单 | ✅ 存进 `plans` | ❌ |
| 申请接管身体 | ❌ | ✅（只读设计除外） |
| 建总任务单 | ❌ | ✅ |
| **丢给身体** | ❌ | ✅ ★ |
| 返回 | 计划摘要 + 能否执行 | `task_id` + `next_attention` |

**`plan` 不是 `execute` 的前置**——可以直接 `execute(goal)`。

两者的差别只在"走到哪一步停"：

```text
plan：    检查世界 → 机器预检 → 编译步骤 → 存起来 → 返回 "能不能做"
execute： 检查世界 → 去重 → 申请接管 → 建任务单 → 丢给身体 → 返回 task_id
```

⚠️ **`plan` 做了检查，但不做可行性检查**：它不走路、不算材料、
不证明"一定做得到"。这些要等真正执行时结合世界判断。

（四个工具的完整契约见[四个公开工具](06-tools.md)。）

---

## 二、执行流：每个游戏刻

入口是 Minecraft 的"客户端刻结束"事件，固定调用 `ClientRuntime.tick`。

```text
END_CLIENT_TICK
  ▼
ClientRuntime.tick
  ├─ 观察：战斗威胁、机器目录、库存证据、预览 …
  ├─ 打开本刻的身体上下文（拿不到 = 死亡 / 换世界）
  ├─ 判断本刻能不能调度
  │     预览在等？需要玩家决定？控制权不在手？本刻操作额度用完了？
  ├─ advanceTasks
  │     ├─ 先收尾旧交通动作
  │     └─ CompanionTickDispatcher.tick(player)
  │           ├─ bind(player)            确保 CompanionBrain 还在
  │           └─ CompanionBrain.tick
  │                 ├─ TaskSelector 选一个赢家
  │                 ├─ 能安全交接吗（在坠落就先别换人）
  │                 ├─ 换了就停旧的
  │                 ├─ 驱动赢家一步
  │                 └─ 终态就结算
  ├─ 保存语义检查点
  └─ 归还身体上下文
```

> **"本刻能不能调度"有四种停住的理由**：预览在等、需要玩家决定、控制权不在手、
> 本刻操作额度用完了。它们对应 `lastTickStage` 的几个取值，完整对照见
> [一件任务怎样开始和结束](../dev/tasks.md)的"角色怎么站着不动"一节。

### 赢家是 `IntentTask` 时

```text
IntentTask.tick
  └─ tickSemanticParent
       ├─ 1. 所有步骤都完成？         → 成功
       ├─ 2. 调用者刚给了答复？       → 取消 / 跳过 / 补条件 / 改目标 / 重试
       ├─ 3. 有子任务在跑？           → tickChild（继续推同一个，不重新规划）
       ├─ 4. 在等条件？               → tickWait
       ├─ 5. 链上还有动作？           → 做下一个
       └─ 6. 否则：AbilityAdapter.adapt(当前目标, 现场)
                  └─► 得到一个 IntentAction
```

`IntentAction` 有八种，处理方式见[核心类型](03-core-types.md)：

| 变体 | 处理 |
| --- | --- |
| `Pending` | 下一刻继续判断同一目标 |
| `Report` | 直接完成这一步（只读设计走这条） |
| `Native` | 建子任务，父任务亲自驱动 |
| `Chain` | 依次做一组内部动作 |
| `Tool` | 调用内部工具；接住它的任务单，或收下它的即时结果 |
| `Decision` | 暂停，等调用者回答 |
| `Remember` | 记一个地标 |
| `Wait` | 等条件成立 |

### 子任务怎么被驱动

`Native` 和 `Tool` 最终都进同一个方法：

```text
beginNative(record)
  ├─ childRecord = record
  ├─ child = TaskFactory.create(player, record)   ★ 记录 → 执行器
  └─ child.start(player)                          一次性准备

之后每刻：
tickChild()
  ├─ 查子任务自己的截止时间
  ├─ child.tick(player)                           ★ 做一点
  └─ 终态？ → finishChild
                ├─ child.result(state)            收尾（关菜单、停导航）
                ├─ 释放身体按键
                ├─ 清掉 child
                ├─ 只是"看一眼情况"（reobserve）？ → 回去重判这一步
                ├─ 失败？ → 记失败，结束总任务
                └─ 成功 → 完成这一步，进入下一步
```

### 执行器怎么真的动手

执行器（`CompanionTask`）每刻通过 `LocalPlayerContext` 的三个端口操作身体：

```text
LocalPlayerContext
  ├─ body()    → 按键、镜头（每刻续发）
  ├─ actions() → 原生操作（提交 + 对账）
  └─ menus()   → 开容器、搬运
```

原生操作是**两阶段**的（详见[关键机制](04-mechanisms.md)）：

```text
提交 useBlock(...)  → 拿到回执（PENDING）
之后每刻 poll(...)  → 用判定条件读客户端事实
                     连续稳定刻满足 → 确认成功
```

---

## 三、观察流：结果 → 外部

```text
子任务结果
  ▼
IntentTask.finishChild / completeStep
  └─ 写进 IntentTaskRecord 的步骤结果
       ▼
总任务到终态
  ├─ record.terminal(...)
  └─ IntentRuntime.terminal(...)
       └─ 发一条 Attention 事件（completed / failed / cancelled）
            ▼
订阅者
  ├─ 资源订阅 maicraft://attention
  ├─ perceive(view="attention", ...) 按游标增量读取
  └─ task(get, path=...) 按需展开完整证据
```

**过程中也会发事件**：`decision`（要回答）、`paused` / `resumed`、
`step_completed` / `step_skipped`、`plan_changed`、`state_restored` 等。

外部看到的是**投影**：内部坐标、路线、点击槽位会被剔除；
大内容走冻结回执 URI 按需展开。

> 外部**怎么等**、`next_attention` 怎么用、超时和取消的区别，
> 见[Attention 与等待](07-attention.md)。

---

## 四、出岔子时怎么走

正常旅程之外，还有三条支线。

### 需要你回答（决策）

```text
适配器说"做不了，得问你"
  └─► IntentTask.requestDecision
        ├─ 把问题记进任务单（decision 快照）
        ├─ 暂停
        └─ 发 decision 事件（带候选项）
             ▼
        LLM 用 task(action="answer", ...) 回答
             ▼
        IntentRuntime.validateDecisionAnswer 先校验
             ▼
        IntentTask 按 choice 处理：
          retry        → 改参数，重新翻译当前步骤
          recover      → 在当前步骤【前】插入前置目标
          replace_goal → 换掉当前步骤
          skip         → 略过，继续下一步
          cancel       → 结束整个任务
```

**校验不是走过场**：`recover` / `replace_goal` 必须恰好带一个 `details.goal`；
`retry` 不允许带 `goal`；**结果不确定的操作不允许普通 `retry`**（防止重复消费）。

### 被抢占

```text
自救（落地 / 换气 / 自卫）或临时动作插进来
  └─► CompanionBrain 停旧任务，原因 PREEMPTED
        ├─ 进度保留
        └─ 不发终态事件
             ▼
        抢占结束后，旧任务重新拿到身体，接着干
```

**这是"让一下"，不是"失败"。**

### 失败

```text
子任务失败（拿回的 result.success() == false）
  └─► IntentTask.failStep
        ├─ 记一次尝试（attempts）
        ├─ 附上"已完成 / 剩余 / 跳过"效果账本
        └─ 终态 = FAILED
             ▼
        发 failed 事件（带证据）
```

> 暂停 / 取消 / 换世界 / 从磁盘恢复分别保留什么、清理什么，
> 见[一件任务怎样开始和结束](../dev/tasks.md)的对照表。

---

## 五、一张完整时序（浓缩版）

```text
LLM ──HTTP──► 入口 ──校验──► Facade ──切线程──► IntentRuntime
                                                      │ 建总任务单
                                                      ▼
                                          CompanionTickDispatcher.submitCurrent
                                                      │
                                              current 槽 ┌───────────┐
                                                         │ IntentTask │
                                                         └─────┬─────┘
   每个游戏刻 ◄───────────────────────────────────────────────┘
   ┌──────────────────────────────────────────────────────┐
   │ ClientRuntime.tick → CompanionBrain.tick              │
   │   → TaskSelector 选 current → IntentTask.tick         │
   │        → AbilityAdapter.adapt → IntentAction.Native   │
   │        → beginNative → TaskFactory.create             │
   │        → 之后每刻 tickChild → child.tick              │
   │             → LocalPlayerContext.actions()            │
   │                  → 提交 → 回执 → 对账 → 确认          │
   └──────────────────────────────────────────────────────┘
                                                      │
                                        终态 ─────────┘
                                                      ▼
                                        Attention 事件 → LLM
```

---

## 相关代码

- 提交流：`MaiCraftRuntimeFacade`、`IntentRuntime`、`CompanionTickDispatcher`
- 执行流：`ClientRuntime`、`CompanionBrain`、`IntentTask`、`TaskFactory`
- 观察流：`IntentRuntime`（事件发布）、`AttentionSnapshot`、`TaskView`
