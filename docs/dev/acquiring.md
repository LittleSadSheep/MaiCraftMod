# 想要物品时，角色究竟会做什么

`acquire_items` 的目标是“背包里最后有多少”，不是“再拿多少”。要 16 根木棍，已经带着 4 根，就还缺 12 根；如果允许橡木板和桦木板替代，数量按两种木板合计。这里只数主背包和快捷栏，不把装备栏、工作台格子或仓库里的物品当成已经到手。

这页说明取物入口、需求拆分、配方选择和父子任务衔接。采矿、狩猎、容器、烹饪等执行器仍在继续逐项审阅；这里没有把整条取物能力标成已完成验收。

## 先读这条主线

```text
检查请求 → 盘点背包 → 选择允许的来源
                    ↓
              缺中间材料或工具？
              先补这一项小需求
                    ↓
           创建一个实际操作子任务
                    ↓
       等子任务收尾，再看真实背包数量
                    ↓
         回到上层需求，或说明做不了的原因
```

例如做木棍时缺木板，木板又缺原木，执行器会记住“木棍还在等”，先处理原木，再做木板，最后回到木棍。它不会同时让挖树、开箱和合成三个任务争抢鼠标。

入口是 [AcquireAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/AcquireAbilityAdapter.java)，随后经过内部工具和 [SemanticAcquireApi](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/SemanticAcquireApi.java)，生成 [SemanticAcquireTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/SemanticAcquireTaskRecord.java)。实际逐刻推进由 [SemanticAcquireCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/SemanticAcquireCompanionTask.java) 负责。

## 请求不能被偷偷改成另一件事

`count` 默认 1，接受 1～2304 的整数；`radius` 默认 16，接受 1～48 的整数。2304 来自 36 格、每格 64 件的数量上限，不保证任意物品都能装这么多。小数、超限数字、数字字符串和文本形式的布尔值会被拒绝。

`item_id`、`item_ids`、`item_tag`、`item_tags` 至少填一种。标签在执行时按游戏当前注册表展开；多种选择方式合并、去重后成为同一组可替代物品。

取物从角色当前位置开始。可以不填 `target`，或只填 `{"kind":"nearest"}`。要去营地取材，就用 `sequence` 明确安排“先 `travel` 到营地，再 `acquire_items`”。以前声明的地点目标没有接入执行，不能继续接受后悄悄忽略。

`source_hint` 只补充方块、标签、生物种类、预期掉落和村民职业等线索。不认识的字段会报错；坐标、槽号或点击脚本不能藏在这里。线索也不等于世界里已经存在可取用的目标。

## 允许来源，与下一步动作是两件事

[AcquisitionSources](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionSources.java) 集中处理来源继承和排序。

| 来源 | 角色做什么 | 不能误解为什么 |
| --- | --- | --- |
| `inventory` | 看自己已经带着多少 | 默认总会检查，不是生成物品 |
| `nearby` | 让拾取子任务靠近符合条件的掉落物 | 当前只接受归属能明确解析为本玩家的掉落，归属未知不能当无主 |
| `storage` | 先尝试普通容器，再尝试支持的 AE2 网络 | 需要明确允许；见过库存不等于允许取用 |
| `craft` | 使用普通合成配方 | 缺料时继续拆需求，不凭配方计划认定成品已经存在 |
| `cook` | 委托烹饪任务准备原料、燃料和设备 | 前置来源继承当前需求，并去掉 `cook`，避免递归开炉 |
| `mine` | 准备合适工具，再找方块并收取掉落 | 工具也受原来源限制，不能顺手开放合成或仓库 |
| `trade` | 委托交易任务确认并执行可接受的交易 | 需要明确允许，不把职业线索当成现成交易 |
| `hunt` | 找到合适生物，检查关系与保护后攻击并结算掉落 | 必须允许伤害；击杀本身不等于已获得目标物品 |

没有填写来源，或给空来源列表时，默认考虑背包、附近掉落、合成、烹饪、采矿和狩猎；仓库、交易需要明确加入。默认伤害许可是关闭的，所以列出了狩猎也不能直接动手。

来源填写顺序不会成为执行脚本。先看背包、附近现货和允许的仓库，再结合当前配方是否能做、是否有采矿或狩猎线索排序。已经耗尽的来源按名字记录；重新排序不会让同一种失败来源凭换下标重新出现。

必需工具继承当前来源许可。富余铁或钻石带来的工具升级，只在已允许的背包、仓库、合成范围内尝试；不能为一次可选升级另起野外采集链。未授权仓库的库存也不参与升级预算。

## 配方规划只负责回答“先缺哪一项”

[AcquisitionNeed](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionNeed.java) 是一项尚未满足的需求，保存目标数量、允许来源、已经经过的物品和配方、失败过的路线，以及是否已经发生实际效果。执行器把小需求压到栈顶：最上面的一项先做完，然后回到下面等着的原任务。

[CraftOps](../../common/src/main/java/org/maiwithu/maicraft/core/tools/CraftOps.java) 先分配真实背包材料。例如一条配方分别需要“任意木板”和“橡木板”，背包只有一块橡木和一块桦木，分配不能先用掉唯一橡木，再误报第二格缺料。

做不了的候选直接通过 [CraftRecoveryCandidate](../../common/src/main/java/org/maiwithu/maicraft/core/task/craft/CraftRecoveryCandidate.java) 交给 [AcquisitionRecipePlanner](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionRecipePlanner.java)。内部保留完整材料替代项；对外报告最多展示八个候选、每格六十四种材料，这个展示上限不会截断内部规划。

规划按以下规则继续：

1. 现在已有材料能完成的配方优先，不再为尚未凑齐的旧方案找额外材料。
2. 材料齐了但缺工作台，就先补工作台；同一配方不会因为摆台失败无限追加工作台。
3. 任一必需材料组只剩祖先物品时，拒绝整条循环路线。不能为了“先有床才能染床”的配方，把所有染料先做一遍。
4. 同样的材料组先合并数量。优先暴露伤害许可等限制，以及转换层数更深、数量更多的材料需求。
5. 两条配方各只差一件时，可以把那两种材料当作替代项一起找；各差多件时不能混算半套材料。
6. 已经为选定路线产生了实际效果，却又发现关键前提做不了时，留下证据并停止。不能无声切换到另一条仍需花材料的路线。

转换层数最多向前看六层，只用于排序。仓库观察也只帮助排序；两者都不能替代实际取材与合成回执。库存估算共用临时材料账，已经分给一组配方的原木不能再次算给另一组。

## 什么才算完成，什么情况要停

主背包目标数量满足是完成条件，但仍有在途原生事务的子任务需要先收尾。父任务读取子任务结果、库存增量和已发生效果，再决定继续哪一项需求。

仓库结果如果表明操作可能已经发生、事务却没结清，父任务会停下；即使此时背包增加，也不能用增加数量掩盖未知事务。狩猎的击杀掉落由攻击子任务自己结算，父任务不会随后再启动一次不分归属的泛用拾取。

取消不会撤销已经挖掉的方块或取走的物品。收尾会停止子任务，并保留尝试、库存变化与不确定性。主要结果字段包括 `goal_satisfied`、`attempts`、`recipe_trace`、`issues`、`effects_observed` 和 `outcome_uncertain`；配方记录里的 `allowed_sources` 表示许可，不能当作已经执行的顺序。

普通路线确实耗尽后，允许制造的需求可以通过 [MaterialProcessPlanning](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/MaterialProcessPlanning.java) 返回知识链接和仍缺的材料。它不会自行造机器，也不会把 EMI 配方展示当作物品已经生产出来。

需求栈属于当前执行器。世界恢复保留总目标、步骤和历史，先以暂停状态恢复，不把旧菜单和路线直接搬到新世界。旧版曾接受的小数数量或被忽略的地点仍可查询、取消和修订；重新执行必须满足现在的规则。

## 修改后怎样核对

- [AcquireGoalTest](../../common/src/test/java/org/maiwithu/maicraft/intent/AcquireGoalTest.java)：检查新目标数量、来源提示、地点边界及发现上限。
- [AcquisitionSourceInheritanceTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionSourceInheritanceTest.java)：经过实际工具准备分支，检查来源不扩大；只盘点背包不会扫描配方。
- [AcquisitionRecipePlanningTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionRecipePlanningTest.java)：原生配方与真实背包分配、完整候选、循环、材料组与替代路线。
- [StorageSupplyRadiusTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/StorageSupplyRadiusTest.java)：建筑补料的仓库半径与配方库存提示一致，不扩大野外采集范围。
- [GoalCheckpointCompatibilityTest](../../common/src/test/java/org/maiwithu/maicraft/intent/GoalCheckpointCompatibilityTest.java)：实际保存、恢复和取消旧目标。

这些是离线场景回归，不等于在整合包中走完八类来源。继续修改具体来源时，要跟进对应执行器的导航、菜单确认、暂停、取消和恢复路径；能力表会单独记录这部分进度。
