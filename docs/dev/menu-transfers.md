# 菜单里点过一次，不等于已经搬完

炉子、箱子、交易和附魔共用菜单操作端口。角色先看到实际菜单，一次提交一笔操作，再等对应结果；等待不能顺手把同一点击发第二遍。

例如，玩家要从箱子取 49 件物品，执行器可能先拿起一堆、分出数量、放入背包再返还余量。光标拿到了物品、背包客户端画面先变了、菜单编号仍相同，都不能单独证明这笔已经完成。贡献者需要沿“计划 → 原生提交 → 同步确认 → 实际数量 → 收尾”检查证据。

公开 `maicraft:use_container` 和 `maicraft:manage_container` 的完整参数及 `plan` 示例见 [容器能力](containers.md)。本文的槽号、`Move`、`closeAfter` 是内部协议，不能直接写进公开 `goal.parameters`。

## 想查哪一步，先打开哪里

| 玩家现场问题 | 实现入口 | 看什么 |
| --- | --- | --- |
| 为什么这次数量不是原来那一整堆 | [SemanticContainerCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/SemanticContainerCompanionTask.java)：`requestedAmount/buildPlan` | 本次额外量与最终目标量，以及实际可取、可放的容量 |
| 怎么把数量交给低层 | [ContainerTransferTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerTransferTaskRecord.java)：`Move`、构造器 | 固定菜单编号、逐笔来源/目的地、数量和是否保留菜单 |
| 为什么还在等同一次点击 | [ContainerTransferCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerTransferCompanionTask.java)：`onTick/afterConfirmedClick` | 同一菜单对象、未结束的 `MenuReceipt`、实际完成量 |
| 快速移动到底进了哪边 | [QuickMoveEvidence](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/QuickMoveEvidence.java) | 源格减少、玩家增加、外部增加及普通格/结果格的确认区别 |
| 拆半、放一件或返还余量卡在哪 | [ContainerSplitPlanner](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerSplitPlanner.java)、[ContainerSplitTransfer](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerSplitTransfer.java)：`tick/confirm/permitted` | 数量计划与每次真实源格、光标、目标格的前后状态 |
| 模组槽位是否能按普通堆叠处理 | [ContainerSlotCapacity](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerSlotCapacity.java) | 已同步槽位角色、原生容量、一次拿起的上限；不可证明时停止普通搬运 |
| 为什么画面变了还不算成功 | [DefaultMenuPort](../../common/src/main/java/org/maiwithu/maicraft/client/actor/DefaultMenuPort.java)、[MenuSynchronization](../../common/src/main/java/org/maiwithu/maicraft/client/actor/MenuSynchronization.java) | 原生后置条件、服务器同步或稳定观察窗口，而非只看客户端预测 |
| 中断后哪些物品已经移动 | `ContainerTransferCompanionTask.resultData/cleanup`；`SemanticContainerCompanionTask.tickChild/verifyTransfer` | 低层已确认量、父层累计量和未知效果是否分别保留 |

## 内部任务单的数量约定

`Move.from`、`Move.to` 是当前原生菜单的槽号；`to=-1` 表示快速移动，`count=0` 表示该源格整堆。指定尾数必须有实际目的槽，快速移动不是任意精确数量的替代操作。公开 `manage_container.count=0` 非法；公开省略数量表示语义来源侧所有匹配物品，这也不等于单个槽的一整堆。

`DestinationMode.EXACT` 要求目标堆显示应有的数量；`MAY_MUTATE_AFTER_DEPOSIT` 给已经接通、会即时消耗或转换输入的机器使用。后者保留源侧与玩家侧的原生证据，不把“物品没有继续留在机器槽中”自动理解为投料失败；它也不能证明下游工序或产量已完成。

选择物品与合并堆叠是两件事。语义父层可按 ID/标签选中多个组件变体，低层则用物品与组件身份确认每笔真实堆叠，不把另一个同名堆的变化算成本笔结果。

## 一次搬运怎样确认

[ContainerTransferCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerTransferCompanionTask.java) 负责普通快速移动、拿起再放入、分堆与交换。任务单保存要动哪些槽，执行器逐笔重读真实内容。

快速移动的数量由 [QuickMoveEvidence](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/QuickMoveEvidence.java) 记录：

- 从普通箱子取物，源格减少量要与玩家增加量相符。只收到其中一侧的同步包时继续等待。
- 从玩家存入容器，检查玩家减少和外部增加；重复显示同一容器槽时不重复计数。
- 炉子的槽位可能继续消耗原料或产生结果，交易结果也可能连续产出。先记录真正进入玩家物品栏的同组件物品数，再由上层工序账核对它们属于哪一炉或哪次交易。
- 原版可能把不适合当前炉子的物品只在主背包与快捷栏之间换区。这个变化也按实际槽位转移记录，不虚构成已存入炉子。

背包只装下 7 件时，不能报告请求的 64 件全到了。确认的部分数量保留在 `moved_counts`；仍有余量没有搬走时，本次完整搬运要求失败，父任务据此处理容量和剩余物品。

`completed_moves` 是已记账的搬运条目数，不保证这些条目都达到了最初请求量：部分快速移动会先记下实际数量，再以剩余来源未搬走结束失败。`submitted_clicks` 和 `effects_started` 只说明原生动作已提交/开始，不能代替 `moved_counts`；`confirmed_split_clicks` 才统计已确认的分堆点击。

在公开存取父层，`moved_count` 只在 `verifyTransfer` 完整通过后递增。失败子任务的部分量保存在 `last_native_transfer.data.moved_counts`，可能没有进入父层累计数；核查部分效果时必须一起看双方最后观察库存，不能根据顶层零值重发原数量。

## 父任务为什么需要保留菜单

`closeAfter=false` 表示菜单仍归组合任务处理。搬运失败也要把界面交回去，让烹饪等父任务核实部分收货，不能提前关掉它。

鼠标上的未知物品和分堆中断状态要保留。父任务的最后清理不能覆盖下层“保留菜单”的决定。`outcome_uncertain` 表示提交过的结果仍无法确认；`preserve_menu` 说明菜单需要留给上层或玩家检查。

`preserve_menu=true` 也可能只是正常的 `closeAfter=false` 组合流程，因此不等于一定发生未知错误。低层成功只交还菜单时，父层仍负责验收和关闭；关闭本身也有原生回执，物品转移完成不等于界面已经关闭。

当父目标已经满足，`requestSatisfiedSettlement` 会阻止尚未提交的下一笔搬运。已拿在手里的小堆结清后停止；例如原计划从 64 件中取 49 件，第一小堆 32 件完成后收到收尾要求，就不再取后续的 16 件和 1 件。回执只记录实际的 32 件。

## 菜单编号和预测画面都不是充分证据

[MenuReceipt](../../common/src/main/java/org/maiwithu/maicraft/client/actor/MenuReceipt.java) 的生产创建入口保存实际菜单对象。编号可能复用，另一份同编号菜单不能用自己的物品或版本变化来确认旧点击。

[DefaultMenuPort](../../common/src/main/java/org/maiwithu/maicraft/client/actor/DefaultMenuPort.java) 检查具体后置条件和同步。附魔按钮等消费必须看到新同步；普通槽位在没有版本回显时，可以在后置状态稳定超过已观测网络往返窗口后确认。[MenuSynchronization](../../common/src/main/java/org/maiwithu/maicraft/client/actor/MenuSynchronization.java) 共用这段等待估计，不再把高延迟强行压成最多十刻。

这仍是“无回显但稳定”的确认依据，不是另造了服务器确认包。测试会分别模拟本地预测、后来到达的拒绝、同编号菜单替换和分开到达的槽位变化。

原生拒绝、后置状态偏离和确认超时应分开解释。`last_native_click` 与 `split_transfer` 保存该笔证据；已经提交的点击等待期间不能重发。需要恢复时先核对原任务、原菜单是否仍存在及实际物品增减，再决定剩余工作。

## 暂停、终止和重启的边界

暂停不撤销已经发出的原生点击。恢复推进时仍检查原菜单对象、编号、槽位和游标；另一份同编号菜单不能承接旧事务。取消或死亡也不会自动回滚已经确认的搬运，光标物品和不确定状态应随回执保留。

当前 `SemanticContainerCompanionTask.cleanup` 在活动子任务中断时只调用 `stop`，没有完整收取其 `result`。因此不能承诺任何取消时刻的底层确认量都已汇总到语义父层。普通结束走 `tickChild` 收结果的路径，与这条终止路径需要分别审阅。

菜单对象、事务和分堆中间状态不作为可跨重启继续点击的状态恢复。语义任务会重新解释目标；相同的额外 `count` 不提供跨重启恰好一次保证。重启后先核对真实库存和历史结果，再决定原目标是否已满足；需要继续时按实际剩余量或最终库存目标处理，而不是恢复旧槽号脚本。

箱子记忆另由 [ContainerSupplySources.rememberVisible](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerSupplySources.java) 记录已同步可见菜单的完整库存。只读身份戳允许被动观察发生在动作刻之外；这是历史观察更新，不是对菜单发出新的操作许可。

## 既有验证入口

- [QuickMoveEvidenceTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/QuickMoveEvidenceTest.java)：双边增减、部分容量和快速移动实际数量。
- [ContainerSplitPlannerTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/ContainerSplitPlannerTest.java)、[ContainerSplitTransferTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/ContainerSplitTransferTest.java)：数量拆分、逐次点击确认和游标收尾。
- [MenuButtonTransactionTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/MenuButtonTransactionTest.java)、[MenuConfirmationLatencyTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/MenuConfirmationLatencyTest.java)：消费按钮与高延迟同步边界。
- [VisibleMenuSessionTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/VisibleMenuSessionTest.java)：页面可见性与原生菜单会话。
- [GuiRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/client/actor/GuiRegressionSuite.java)、[ContainerSearchRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/ContainerSearchRegressionSuite.java)：对应的现有组合入口；炉子、交易、附魔还应看各自工序的收货与消耗回归。

这些入口使用真实槽位规则和显式回执边界，不能替代整合包里的网络与渲染验收。本轮仅做源码、JSON、链接和 diff 静态核对，没有新增或运行测试。
