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

实现：[Goal.executableSteps](../../common/src/main/java/org/maiwithu/maicraft/intent/Goal.java)、[SemanticGoalContract](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java)。

## 保护范围怎样跟着步骤走

假设外层要求保护农田，内层某一组还要求保护树林：

- 内层步骤同时继承农田与树林的要求。
- 内层结束后，其兄弟步骤仍只继承外层的农田要求。
- 为某一步插入取料等恢复工作时，新前置步骤继承这一步所在分组的保护范围。
- 原请求不被改写；这些执行范围另存于检查点，重启恢复后仍有效。

仅有名字还不够。导航和施工使用的是此前实际测得、维度匹配的保护格子；观察过某区域，也不会自动把它变成所有后续任务都必须避让的区域。

## 做不下去时怎样继续

当前步骤失败后，总任务保留原目标、失败尝试和已发生的效果，再提出问题。常见回答有：

| 回答 | 清单怎样变化 |
| --- | --- |
| `retry` | 用允许的新参数重新判断当前步骤；不确定或不允许重复的操作不会开放普通重试 |
| `recover` | 在当前步骤之前插入前置目标；整个前置序列完成后再回到原步骤 |
| `replace_goal` | 替换当前这一步，已处理的前缀和后面的步骤保留 |
| `skip` | 明确略过这一步，继续后面的清单；不把它记成实际成功 |
| `cancel` | 停止整个总任务，保留已发生效果与必要的未决说明 |

执行中的步骤表只提供只读视图。改动通过 [IntentTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTaskRecord.java) 的恢复、替换和参数更新方法完成，避免直接改列表时漏掉保护范围或保存通知。

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

## 已有验证

- [SequenceSkipTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SequenceSkipTest.java)：真实询问和跳过、继续后续目标、查询和通知、旧检查点恢复、残留位置不被复用。
- [SequenceProtectionTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SequenceProtectionTest.java)：分组保护范围经过修改、恢复和替换仍有效，且不会流到兄弟步骤。
- [TaskStepPersistenceTest](../../common/src/test/java/org/maiwithu/maicraft/intent/TaskStepPersistenceTest.java)：实际步骤清单和处理位置在持久化往返中保留。
- [WaitGoalTest](../../common/src/test/java/org/maiwithu/maicraft/intent/WaitGoalTest.java)：即使连续两步立即满足，也各自留下结果，全部处理后才结束流程。

这里完成的是组合与恢复规则的审阅。每个子能力自己的游戏动作、确认方式和后端分支，继续在[能力表](capabilities.md)逐项记录。
