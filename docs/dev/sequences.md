# 顺序目标：一件做完，再做下一件

`maicraft:sequence` 把多个语义目标放在同一份有顺序的清单里。例如先找材料，再施工，最后补光。组合目标自己不提供另一套走路、点击或放置逻辑，每一步仍使用对应能力。

## 从请求到执行清单

```text
sequence 的 children
  → 检查每个子目标的能力、参数和限制
  → 按原顺序展开嵌套分组
  → 保留分组声明的保护范围
  → IntentTask 一次推进当前一步
  → 确认完成或处理明确决定后，才前进到下一步
```

`sequence` 必须有子目标，不能另设一个总目的地。每项具体目标保留自己的地点与参数。组合层可以声明 `protected_labels`，其余具体偏好和限制放在相关子目标中。

## 怎样提交

下面是传给 MCP `plan` 的完整参数。`plan` 只建计划；拿到真实的 `plan_id` 后再交给 `execute`。网络重试沿用同一个 `request_key`，接单后跟随返回的 `next_attention`。

```json
{
  "goal": {
    "ability": "maicraft:sequence",
    "outcome": "先等两秒，再等到白天",
    "children": [
      {
        "ability": "maicraft:wait_for_condition",
        "outcome": "等待两秒",
        "parameters": { "condition": "elapsed", "after_s": 2 }
      },
      {
        "ability": "maicraft:wait_for_condition",
        "outcome": "确认已经进入白天",
        "parameters": { "condition": "day", "after_s": 0 }
      }
    ]
  }
}
```

| 字段位置 | 格式和含义 |
| --- | --- |
| `goal.ability`、`goal.outcome` | 必填字符串；能力为 `maicraft:sequence`，目标文字为 1～500 字符。文字不代替子目标参数 |
| `goal.children` | 必填非空目标数组，每层至多 32 项；按数组顺序执行。子项也必须有 `ability` 和 `outcome` |
| `goal.parameters.protected_labels` | 可选字符串数组；省略或 `[]` 不新增保护，`null`、非字符串、空白名字拒绝。名字必须来自已记住的地点，不能把坐标放进名字 |
| `goal.children[i].on_failure` | 与 `ability` 同层，不能放进 `parameters`。正式取值为 `stop`、`continue`，省略为 `stop`；`continue` 只允许声明在某个 sequence 的直接叶子子项上。Schema 声明字符串，但当前运行入口将 `null` 按缺省处理；布尔值和其他名称拒绝 |
| `goal.target` | 省略或 `null`；组合层没有目的地 |
| `goal.preferences`、`goal.constraints` | 省略或分别用 `{}`、`[]`；组合层不能声明自己的非空偏好或约束 |

嵌套 sequence 可以分组，但不会并行。根目标深度为 0，超过 32 的嵌套深度拒绝；当前不另计展开后的总步骤上限。每层都受公开目标格式校验；空数组不能表示“什么都不做”。

实现：[Goal.executableSteps](../../common/src/main/java/org/maiwithu/maicraft/intent/Goal.java)、[SemanticGoalContract](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java)。

## 保护范围怎样跟着步骤走

假设外层要求保护农田，内层某一组还要求保护树林：

- 内层步骤同时继承农田与树林的要求。
- 内层结束后，其兄弟步骤仍只继承外层的农田要求。
- 为某一步插入取料等恢复工作时，新前置步骤继承这一步所在分组的保护范围。
- 原请求不被改写；这些执行范围另存于检查点，重启恢复后仍有效。

仅有名字还不够。导航和施工使用的是此前实际测得、维度匹配的保护格子；观察过某区域，也不会自动把它变成所有后续任务都必须避让的区域。

## 做不下去时怎样继续

先区分**待决定**与**执行失败**。适配器遇到需要选择的情况，可以交回 `Decision`，父任务此时等待答复；已经执行的子任务交回失败时，`IntentTask.failStep` 记录尝试和现场，默认直接结束总任务，不再自动转成问题。

只有回执确实带有当前 `decision_id` 时，才通过 `task(action="answer")` 回答，并且只选本次列出的选项。常见选项的含义如下：

| 回答 | 清单怎样变化 |
| --- | --- |
| `retry` | 用允许的新参数重新判断当前步骤；不确定或不允许重复的操作不会开放普通重试 |
| `recover` | 在当前步骤之前插入前置目标；整个前置序列完成后再回到原步骤 |
| `replace_goal` | 替换当前这一步，已处理的前缀和后面的步骤保留 |
| `skip` | 明确略过这一步，继续后面的清单；不把它记成实际成功 |
| `cancel` | 停止整个总任务，保留已发生效果与必要的未决说明 |

执行中的步骤表只提供只读视图。改动通过 [IntentTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTaskRecord.java) 的恢复、替换和参数更新方法完成，避免直接改列表时漏掉保护范围或保存通知。

## 声明可容忍的失败（on_failure）

默认执行失败会终止总任务。如果编排者知道后续步骤不依赖当前结果，可以在当前叶子目标上声明 `on_failure: "continue"`。例如钓鱼失败也要继续做下一项等待：

```json
{
  "goal": {
    "ability": "maicraft:sequence",
    "outcome": "尝试钓一竿，失败也继续最后的等待",
    "children": [
      {
        "ability": "maicraft:fish",
        "outcome": "收回一竿钓获物",
        "on_failure": "continue",
        "parameters": { "count": 1 }
      },
      {
        "ability": "maicraft:wait_for_condition",
        "outcome": "等待一秒",
        "parameters": { "after_s": 1 }
      }
    ]
  }
}
```

这个声明只处理失败终态。缺鱼竿时适配器可能先提出补料决定，清单仍会等待答复，不能把 `continue` 当作自动回答所有问题。

边界与语义：

- `on_failure=continue` 只允许出现在 sequence 的**直接叶子子项**；不能给顶层目标或 sequence 分组本身声明继续策略。嵌套分组里的叶子仍可逐项声明；分组被展开后，没有“跳过整个失败分组”的语义。显式 `stop` 与省略相同。
- 当前 `toleratesStepFailure` 只检查终态是 `FAILED`、本项声明继续且仍有后项，**没有另查 `outcome_uncertain`**。因此未知效果也可能随失败留在账本、后项继续；不能把继续执行解释为前项已经结清。`TIMEOUT` 和 `CANCELLED` 不走此分支，最后一项失败也直接结束。
- **部分失败算完全失败**：只要存在事实失败（含被容忍的），整体终态仍是 FAILED；只有显式跳过而没有任何事实失败时才是 SUCCESS。跳过是"决定不做"，容忍失败是"做了没成"，两者终态不同是刻意的区分。
- 被容忍的失败记为 `success=false`、`skipped=false` 的真实失败，不混入跳过。终态回执带 `tolerated_failure_count` 与完整失败账本（`completed_effects` 逐步列出失败与成功、`remaining_effects` 为空）。
- 失败步骤的位置不再作为后续 `prior_result` 的依据；引用它的兄弟步骤会得到明确的解析失败，而不是拿到 stale 坐标。

## 跳过之后，到底算完成了什么

例如第一步“记住营地”没有指定地点，用户决定跳过，然后第二步正常结束：

- 营地不会凭空写入地点记忆。
- 这一步记为 `skipped=true`、`success=false`。
- 余下清单仍可正常走完，整个流程可以结束。
- 最终说明会明确包含跳过数量，不直接照抄原目标文案宣称全部达成。

任务详情和列表提供 `skipped_step_count` 与 `all_steps_succeeded`。最终结果还列出 `skipped_steps` 的步骤索引。`state=success` 表示清单按最后确认的选择正常结束，是否原清单每一步都成功要看 `all_steps_succeeded`。

Attention 使用独立的 `step_skipped` 事件。后续失败的效果账本也把跳过索引单独列出，不把它们混入已经成功完成的效果。

跳过不等于撤销。比如某次施工已放下几块方块、后来停止，决定跳过并不会把这些方块撤回。已有的部分效果和未决状态仍在尝试历史里；任务结束或需要决定时，Attention 附带完整任务快照供核对。

## 保存和引用前序结果

检查点同时保存原请求、实际执行清单、处理位置、每步结果和失败尝试。恢复时使用实际清单，不能用原始 `children` 覆盖后来插入的恢复步骤。

跳过标记也保存。旧版没有专门字段的记录，只按原执行器的固定跳过说明识别，不把普通失败推断为跳过。即使旧记录还残留位置，被跳过或失败的步骤也不能成为后续 `prior_result` 的坐标依据。

## 被打断时保留到哪里

普通暂停保留当前执行器与清单，先释放身体输出；继续时由同一个子能力核对现场。取消结束整份清单，后面的步骤不会再开始，之前吃掉的食物、换到的物品或放下的炉子不会撤销。

死亡走运行时的死亡恢复流程：是否可以自动复活取决于已有授权，没有授权就交付死亡决定。复活不代表原来的材料、地点和菜单还有效。普通断线或换世界先保存原世界进度，再清理旧身体；仅已授权的传送门交接可以保留父任务。磁盘恢复保留实际清单和已完成结果，未完成记录先暂停；恢复的是当前语义步骤，具体能力的内存动作不保证恢复。例如等待重新计时，聊天和切石还要检查各自的持久提交记录。

| 要看哪一步 | 代码入口 |
| --- | --- |
| 目标字段、顺序和保护继承 | [Goal.fromJson / executableSteps](../../common/src/main/java/org/maiwithu/maicraft/intent/Goal.java) |
| 拒绝错放的参数与继续策略 | [SemanticGoalContract.validate / validateOnFailure](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java) |
| 推进当前一步、等待、失败、终局结算 | [IntentTask.tickSemanticParent / failStep / completionResult](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java) |
| 修改实际清单、保存每次尝试 | [IntentTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTaskRecord.java) |
| 解析前序地点 | [PriorResultResolver](../../common/src/main/java/org/maiwithu/maicraft/intent/PriorResultResolver.java) |
| 世界归属、死亡记录和恢复 | [IntentRuntime.tickPersistence / restoreBound](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) |

## 已有验证

以下为现有验证入口，本轮只核对源码和文档，未运行这些回归。

- [SequenceSkipTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SequenceSkipTest.java)：真实询问和跳过、继续后续目标、查询和通知、旧检查点恢复、残留位置不被复用。
- [SequenceToleratedFailureTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SequenceToleratedFailureTest.java)：on_failure 的挂载位置与词表校验、容忍失败后兄弟步骤接续、整体仍报 FAILED 与账本披露、缺省 stop 对照。
- [SequenceProtectionTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SequenceProtectionTest.java)：分组保护范围经过修改、恢复和替换仍有效，且不会流到兄弟步骤。
- [TaskStepPersistenceTest](../../common/src/test/java/org/maiwithu/maicraft/intent/TaskStepPersistenceTest.java)：实际步骤清单和处理位置在持久化往返中保留。
- [WaitGoalTest](../../common/src/test/java/org/maiwithu/maicraft/intent/WaitGoalTest.java)：即使连续两步立即满足，也各自留下结果，全部处理后才结束流程。

这里完成的是组合与恢复规则的审阅。每个子能力自己的游戏动作、确认方式和后端分支，继续在[能力表](capabilities.md)逐项记录。
