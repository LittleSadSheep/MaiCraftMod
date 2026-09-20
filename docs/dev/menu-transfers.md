# 菜单里点过一次，不等于已经搬完

炉子、箱子、交易和附魔共用菜单操作端口。角色先看到实际菜单，一次提交一笔操作，再等对应结果；等待不能顺手把同一点击发第二遍。

## 一次搬运怎样确认

[ContainerTransferCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerTransferCompanionTask.java) 负责普通快速移动、拿起再放入、分堆与交换。任务单保存要动哪些槽，执行器逐笔重读真实内容。

快速移动的数量由 [QuickMoveEvidence](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/QuickMoveEvidence.java) 记录：

- 从普通箱子取物，源格减少量要与玩家增加量相符。只收到其中一侧的同步包时继续等待。
- 从玩家存入容器，检查玩家减少和外部增加；重复显示同一容器槽时不重复计数。
- 炉子的槽位可能继续消耗原料或产生结果，交易结果也可能连续产出。先记录真正进入玩家物品栏的同组件物品数，再由上层工序账核对它们属于哪一炉或哪次交易。
- 原版可能把不适合当前炉子的物品只在主背包与快捷栏之间换区。这个变化也按实际槽位转移记录，不虚构成已存入炉子。

背包只装下 7 件时，不能报告请求的 64 件全到了。确认的部分数量保留在 `moved_counts`；仍有余量没有搬走时，本次完整搬运要求失败，父任务据此处理容量和剩余物品。

## 父任务为什么需要保留菜单

`closeAfter=false` 表示菜单仍归组合任务处理。搬运失败也要把界面交回去，让烹饪等父任务核实部分收货，不能提前关掉它。

鼠标上的未知物品和分堆中断状态要保留。父任务的最后清理不能覆盖下层“保留菜单”的决定。`outcome_uncertain` 表示提交过的结果仍无法确认；`preserve_menu` 说明菜单需要留给上层或玩家检查。

当父目标已经满足，`requestSatisfiedSettlement` 会阻止尚未提交的下一笔搬运。已拿在手里的小堆结清后停止；例如原计划从 64 件中取 49 件，第一小堆 32 件完成后收到收尾要求，就不再取后续的 16 件和 1 件。回执只记录实际的 32 件。

## 菜单编号和预测画面都不是充分证据

[MenuReceipt](../../common/src/main/java/org/maiwithu/maicraft/client/actor/MenuReceipt.java) 的生产创建入口保存实际菜单对象。编号可能复用，另一份同编号菜单不能用自己的物品或版本变化来确认旧点击。

[DefaultMenuPort](../../common/src/main/java/org/maiwithu/maicraft/client/actor/DefaultMenuPort.java) 检查具体后置条件和同步。附魔按钮等消费必须看到新同步；普通槽位在没有版本回显时，可以在后置状态稳定超过已观测网络往返窗口后确认。[MenuSynchronization](../../common/src/main/java/org/maiwithu/maicraft/client/actor/MenuSynchronization.java) 共用这段等待估计，不再把高延迟强行压成最多十刻。

这仍是“无回显但稳定”的确认依据，不是另造了服务器确认包。测试会分别模拟本地预测、后来到达的拒绝、同编号菜单替换和分开到达的槽位变化。

相关回归是 `QuickMoveEvidenceTest`、`ContainerSplitTransferTest`、`MenuButtonTransactionTest`、`MenuConfirmationLatencyTest`，以及烹饪的收货、退料和同步测试。它们使用真实槽位规则和显式回执边界，不能替代整合包里的网络与渲染验收。
