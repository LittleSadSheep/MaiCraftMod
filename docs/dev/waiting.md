# 等待：先等多久，再看什么

`maicraft:wait_for_condition` 的意思是“等到某个可以观察的条件成立”。它不会为了达到条件替角色吃饭、治疗，也不会修改世界时间。

## 玩家提出的要求

```json
{
  "ability": "maicraft:wait_for_condition",
  "outcome": "至少等两秒，之后等到天亮",
  "parameters": { "after_s": 2, "condition": "day" }
}
```

执行器先记录当前游戏时间，加上 40 个游戏刻。到了这个时刻才检查是否天亮；如果仍是夜间，就继续等。

`after_s` 是**最短等待时间**，不是最长等待时间。比如“至少等两秒后等到天亮”，不会因为两秒到了就报告天已经亮了。

| 参数 | 不填写时 | 接受什么 |
| --- | --- | --- |
| `after_s` | 1 秒 | 0 到 3600 的整数；拒绝小数、字符串、空值和越界数字 |
| `condition` | `elapsed` | 下表中的一种条件；名称必须准确 |

| 条件 | 实际判断 |
| --- | --- |
| `elapsed` | 最短等待时间已经过去 |
| `day` | 当前世界时间不在夜间时段；凌晨和傍晚也算可满足 |
| `night` | 当前世界的一天中处于第 13000 到 22999 刻 |
| `health_full` | 当前生命值不低于最大生命值 |
| `not_hungry` | 当前饱食度至少为 18 |

昼夜按世界日时钟判断，不按角色眼前是否漆黑判断。因此洞穴、天气或某个维度的天空外观，不会把这个条件自动改成别的含义。

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

重启恢复属于另一种情况：检查点保留的是目标和已完成步骤，不保存这次内存里的等待计时对象。未完成任务先以暂停状态恢复；明确继续后，当前等待目标重新建立最短等待时间。

旧版本曾宽松接收小数等等待参数。它们可以作为历史恢复、查询和取消，不会因此封锁整个世界的任务记录；新的计划、执行和参数修订仍使用严格校验。不能为了恢复方便而悄悄改写当初提交的目标。

## 哪些场景已经验证

[WaitGoalTest](../../common/src/test/java/org/maiwithu/maicraft/intent/WaitGoalTest.java) 检查参数边界、最短时长之前不会提前完成、暂停后继续、昼夜和身体条件变化，以及两步等待各自记录结果。

[WaitCheckpointCompatibilityTest](../../common/src/test/java/org/maiwithu/maicraft/intent/WaitCheckpointCompatibilityTest.java) 走真实检查点读写和恢复入口，检查旧计划、旧请求编号和失败历史仍在，新请求不会绕过校验，旧任务可直接取消。

这两组测试接入 `:common:attentionRegression`，并随 `:common:check` 运行。
