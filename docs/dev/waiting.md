# 等待：先等多久，再看什么

`maicraft:wait_for_condition` 的意思是“等到某个可以观察的条件成立”。它不会为了达到条件替角色吃饭、治疗，也不会修改世界时间。

## 玩家提出的要求

下面是 MCP `plan` 的完整参数；计划本身不开始计时。拿到实际 `plan_id` 后调用 `execute`，接着沿返回的 `next_attention` 等待结果。

```json
{
  "goal": {
    "ability": "maicraft:wait_for_condition",
    "outcome": "至少等两秒，之后等到天亮",
    "parameters": { "after_s": 2, "condition": "day" }
  }
}
```

这一子步骤真正开始时，执行器记录当前游戏时间，加上 40 个游戏刻。到了这个时刻才检查是否天亮；如果仍是夜间，就继续等。这里一秒换算为 20 个游戏刻；暂停世界或服务器卡顿时，不保证等于现实世界的一秒。

`after_s` 是**最短等待时间**，不是最长等待时间。比如“至少等两秒后等到天亮”，不会因为两秒到了就报告天已经亮了。

| 参数 | 不填写时 | 接受什么 |
| --- | --- | --- |
| `goal.parameters.after_s` | 1 游戏秒 | 0 到 3600 的整数；0 表示立即允许检查。拒绝小数、字符串、布尔值、显式 `null` 和越界数字 |
| `goal.parameters.condition` | `elapsed` | 下表中的一种字符串，大小写必须准确；显式 `null`、空字符串和其他名称拒绝 |

`goal.target` 可以省略、为 `null` 或 `{"kind":"current_place"}`；它不安排移动。`preferences` 和 `constraints` 不承载本能力参数。没有最大等待时长参数，条件永不成立时会一直等待，调用方可取消。不要把 `after_s` 当成超时，也不要用 `false` 表示关闭条件。

| 条件 | 实际判断 |
| --- | --- |
| `elapsed` | 最短等待时间已经过去 |
| `day` | 当前世界时间不在夜间时段；凌晨和傍晚也算可满足 |
| `night` | 当前世界的一天中处于第 13000 到 22999 刻 |
| `health_full` | 当前生命值不低于最大生命值 |
| `not_hungry` | 当前饱食度至少为 18 |

昼夜按世界日时钟判断，不按角色眼前是否漆黑判断。因此洞穴、天气或某个维度的天空外观，不会把这个条件自动改成别的含义。

需要“现在就检查是否满血，然后一直等”的完整 `plan` 参数如下。`after_s: 0` 只免掉前置延时，不会免掉满血条件：

```json
{
  "goal": {
    "ability": "maicraft:wait_for_condition",
    "outcome": "等到生命值恢复至最大值",
    "parameters": { "condition": "health_full", "after_s": 0 }
  }
}
```

进食和回血依赖另外的游戏行为；这项能力不会承诺把饥饿值补到 20，`not_hungry` 的阈值只是 18。模组改变最大生命值时会读取当下的最大值；改变天空效果不会改变日时钟的判断方式。

## 代码怎样走

```text
plan / execute
  → SemanticGoalContract 检查公开目标
  → WaitAbilityAdapter 校验条件和时长
  → 执行开始时记下最早检查的游戏刻
  → IntentTask.tickWait 逐刻检查
  → 条件成立，记录本步结果
  → 还有后续目标就继续，否则结束总任务
```

主要实现：

- [WaitAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/WaitAbilityAdapter.java)：参数和五种条件集中在这里，计划与执行共用。
- [IntentTask](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)：保存正在等待的条件，记录结果并推进总目标。
- [WorldTimeSemantics](../../common/src/main/java/org/maiwithu/maicraft/core/data/WorldTimeSemantics.java)：一天各时段的边界。

## 暂停、取消和恢复

普通暂停时，总任务不参与执行，但世界可能仍在继续计时。继续任务后会承认已经过去的游戏时间，不会把已经等过的两秒再等一遍。

取消时没有挖掘、物品使用或菜单操作需要撤销，仍走总任务的统一取消和结果路径。

死亡由公共运行时先处理复活授权或待答决定，等待能力自己不复活。换世界清理旧身体上的执行器，按世界保存和恢复目标；不能把旧世界的天亮或血量当成新世界已经满足条件。

重启恢复属于另一种情况：检查点保留的是目标和已完成步骤，不保存这次内存里的等待计时对象。未完成任务先以暂停状态恢复；明确继续后，当前等待目标重新建立最短等待时间。

旧版本曾宽松接收小数等等待参数。它们可以作为历史恢复、查询和取消，不会因此封锁整个世界的任务记录；新的计划、执行和参数修订仍使用严格校验。不能为了恢复方便而悄悄改写当初提交的目标。

## 怎样读结果与查验证入口

本步成功消息是 `wait condition satisfied: <condition>`。这只证明检查的那一刻条件成立；如果马上又受伤，不会改写此前的成功结果。接单成功、Attention 等待超时和任务真正完成是三件不同的事。步骤失败默认由父任务结算，只有出现实际 `decision_id` 才通过 `task(answer)` 回答。

以下是现有测试的职责，本轮未运行回归或游戏。

[WaitGoalTest](../../common/src/test/java/org/maiwithu/maicraft/intent/WaitGoalTest.java) 检查参数边界、最短时长之前不会提前完成、暂停后继续、昼夜和身体条件变化，以及两步等待各自记录结果。

[GoalCheckpointCompatibilityTest](../../common/src/test/java/org/maiwithu/maicraft/intent/GoalCheckpointCompatibilityTest.java) 走真实检查点读写和恢复入口，检查旧计划、旧请求编号和失败历史仍在，新请求不会绕过校验，旧任务可直接取消。

这两组测试接入 `:common:attentionRegression`，并随 `:common:check` 运行。
