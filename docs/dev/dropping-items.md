# 丢弃物品：整份投掷、点火收场与通道避让

角色背包快满时，玩家可能要求“丢掉 40 个圆石”。`maicraft:drop_items` 会先处理旧界面、选择站位，再把需要的数量整份丢出；它还可能走到空地、挖侧袋、使用已有打火石，以及回收烧不掉的挡路物品后重新选点。因此它是清理不要的物品的能力，不是交给指定玩家或向机器接收格精确投料的接口。需要保留物品时应使用存储能力；机器投料使用其专属原生工序。

本文按当前源码说明实际行为。`SUCCESS` 不能直接解释为“全部物品已烧毁、火已灭、任意后续路线都畅通”。数量、实体观察、点火、回收和后台扑火各有自己的证据。

## 怎样提交目标

读取当前运行实例的契约时，向 `perceive` 提交：

```json
{"view":"abilities","focus":"maicraft:drop_items"}
```

掌握当前契约和库存后可以直接提交目标，不必每次重复观察。下面的能力参数位于 **`goal.parameters`**，不是请求顶层、`target` 或 `preferences`。计划就绪后，把真实返回的 `plan_id` 交给 `execute`；接单只表示登记成功，继续按 `next_attention` 等待终态、暂停或决策。同一次执行的网络重试复用 `request_key`，不要另开一件丢弃任务。

### 字段、默认值和边界

| 完整位置 | 类型与单位 | 当前含义及省略、空值的区别 |
| --- | --- | --- |
| `goal.ability` | 字符串 | 必填，`maicraft:drop_items`。 |
| `goal.outcome` | 非空字符串，1～500 字符 | 必填，用自然语言描述目的；不能代替数量，也不能增加“不要烧”“不许挖”等执行开关。 |
| `goal.parameters.item_id` | 已注册物品的命名空间 ID 字符串 | 必填，例如 `minecraft:cobblestone`。省略、`null`、空白或非基础 JSON 值不能提供物品身份；未注册 ID 进入决策。按物品类型选堆，不按名称、附魔、耐久或其他组件选某一件。不是标签、槽位或实体 UUID。 |
| `goal.parameters.count` | 整数，单位是件，声明范围 1～999 | 必须显式给出，无业务默认值；不是“组数”或最终库存目标。省略、`null`、`false`、0、负数、不可读的值都不能表达“全部”，会在适配时进入决策。当前数值解析有宽松转换，见下文。 |
| `goal.target` | 可选对象或 `null` | 省略或 `null` 均可。对象只接受 `kind:"current_place"`；不是固定坐标，执行器仍可能移动和开挖。`position` 必须省略或为 `null`。`label`、`relation` 不是丢弃位置或收件人约束。 |
| `goal.preferences` | 对象，省略为 `{}` | 无丢弃专属偏好；`null` 不是空对象。不要在这里填写点火、开挖、距离或姿态设置。 |
| `goal.constraints`、`goal.children` | 数组，省略为 `[]` | 本能力不声明硬约束或子目标；不能用额外约束承诺执行器未实现的行为。`null` 不是空数组。 |

省略整个 `parameters` 会得到空对象，随后缺少必填业务信息；明确写 `parameters:null` 则不满足公开请求的对象格式。`on_failure` 等公共目标控制、运行时死亡授权键由通用任务层解释，不是丢弃开关，参见 [任务调度与结果](tasks.md)。本能力没有 `allow_fire`、`may_alter_terrain`、`radius`、`full_stack`、`yaw`、`pitch`、`slot` 等公开参数，未知字段会被语义契约拒绝；它们的 `false` 值也不会关闭默认行为。

库存核对遍历玩家可读的整个 `Inventory`，包括主背包、快捷栏、盔甲和副手；不自动取出箱子、背包物品内部或模组存储网络里的内容。同 ID 的不同组件变体都可能被选中，调用者不能只凭名字推断会丢哪一件。

### 数量存在两层不同的处理

- **公开语义入口**：`GeneralAbilityAdapter.drop` 要求物品和数量；规范化后的数量超过当时可读库存时返回待决策，不直接转成“丢全部”。
- **当前解析边界**：它使用宽松的 `integer` 帮助方法，`1000` 会压为 `999`，部分数字字符串及小数可经 `getAsInt` 转换；公开外壳不对这个能力做严格的整数范围校验。请提交真正的 JSON 整数 1～999，不把这些兼容转换当作推荐用法。
- **内部任务入口**：`InventoryOps.dropItems` 再把数量夹在 1～999。`DropCompanionTask` 在旧 GUI 返料结束后，以 `min(请求量, 当前库存)` 固定本轮目标。因此库存若在接单后减少，内部任务仍可能按较小目标成功；最终要核对 `dropped` 和 `remaining_in_inventory`。

这两层不是两个可互换的模型接口。内部工具名 `drop_items` 没有命名空间，供语义父任务捕获任务单；LLM 应使用公开能力。`GeneralAbilityAdapter` 中虽留有 `target.kind="item"` 的取名分支，但当前公开目标校验不接受这种目标，不要据此构造请求。

### 有效的完整 `plan` 示例

已确认至少有 40 个不要的圆石；即使它们放在一堆 64 个中，也可先分堆，再一次投掷 40 个：

```json
{
  "goal": {
    "ability": "maicraft:drop_items",
    "outcome": "清理背包中的 40 个多余圆石",
    "parameters": {"item_id": "minecraft:cobblestone", "count": 40}
  }
}
```

已确认至少有 128 个多余圆石；128 是件数，执行器会按实际槽位分成多个投掷批次。显式的 `current_place` 仍允许选点移动、开挖和已有打火石的处理：

```json
{
  "goal": {
    "ability": "maicraft:drop_items",
    "outcome": "清理 128 个多余圆石",
    "target": {"kind": "current_place"},
    "parameters": {"item_id": "minecraft:cobblestone", "count": 128},
    "preferences": {},
    "constraints": [],
    "children": []
  }
}
```

只丢一件同样显式写 1；0 不是空操作，省略数量也不是默认丢一件：

```json
{
  "goal": {
    "ability": "maicraft:drop_items",
    "outcome": "丢弃 1 个多余圆石",
    "parameters": {"item_id": "minecraft:cobblestone", "count": 1}
  }
}
```

## 想看哪一步，应打开哪里

以下链接直接定位负责该行为的文件；方法名帮助区分入口、动作和证据，避免只沿一个类名猜测整条链。

| 想排查的问题 | 入口与作用 |
| --- | --- |
| 模型看见哪些字段、哪些目标合法 | [SemanticAbilityCatalog.describe](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java) 的 `DROP` 分支；[SemanticGoalContract.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java) 检查参数名、目标种类、偏好及约束。 |
| 请求为何还没丢就要求回答 | [GeneralAbilityAdapter.drop](../../common/src/main/java/org/maiwithu/maicraft/intent/GeneralAbilityAdapter.java)：物品身份、显式数量与接单时库存；[PublicToolCatalog.validateGoal](../../common/src/main/java/org/maiwithu/maicraft/mcp/PublicToolCatalog.java)：请求外壳。 |
| 语义目标怎样接上逐刻执行 | [DropItemsTool.onGameCall](../../common/src/main/java/org/maiwithu/maicraft/core/tools/inventory/DropItemsTool.java) → [InventoryOps.dropItems](../../common/src/main/java/org/maiwithu/maicraft/core/tools/InventoryOps.java) → [TaskDispatch.captureNext/runSync](../../common/src/main/java/org/maiwithu/maicraft/task/TaskDispatch.java) → [IntentTask.beginTool/beginNative](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)。 |
| 当前在准备、投掷、烧毁还是回收 | [DropCompanionTask.onTick](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/DropCompanionTask.java)；数量与副作用在 `acceptClick`、`accountRecovery`、`resultData` 结算。 |
| 为什么离开原地、为什么开挖 | [DiscardSitePlan.find/connected/preservesRoutes](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/DiscardSitePlan.java) 选位置；[DiscardSitePreparation.tick](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/DiscardSitePreparation.java) 导航、站定并逐格开挖。 |
| 物品为什么低头丢、转向多久才出手 | [DropAim.ready/direction](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/DropAim.java) 和 [ActualViewConvergenceGate.ready](../../common/src/main/java/org/maiwithu/maicraft/core/task/ActualViewConvergenceGate.java)；先确认真实视角，不直接改实体速度。 |
| 40 个如何一次丢、满包怎么办 | [DropBatchPlan.source/plan](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/DropBatchPlan.java) 和 [ContainerSplitPlanner.plan](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerSplitPlanner.java)：原生左右键分堆、整堆或鼠标携带物投掷。 |
| 点击是否真的扣了对应数量 | [DefaultMenuPort.click/poll](../../common/src/main/java/org/maiwithu/maicraft/client/actor/DefaultMenuPort.java) 与 `DropBatchPlan.Click.confirmation`；[VisibleMenuSession](../../common/src/main/java/org/maiwithu/maicraft/core/task/menu/VisibleMenuSession.java) 管背包显示和关闭。 |
| 点火、等待、扑火的具体动作 | [DiscardFire.tick/canBurn](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/DiscardFire.java) 安排阶段；[DiscardBlockAction.tick](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/DiscardBlockAction.java) 选手、瞄准、原生使用或破坏并等待回执。 |
| 烧不掉时为什么又捡回来 | [DiscardRecovery.tick/close](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/DiscardRecovery.java) 临时放行本批跟踪物；[CollectItemsCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/collect/CollectItemsCompanionTask.java) 核对原生拾取和入包。 |
| 下一项任务为什么绕开这里 | [DiscardedItems.watch/observe/addPickupCells](../../common/src/main/java/org/maiwithu/maicraft/client/actor/DiscardedItems.java) 跟踪实体与合堆；[NavigationSafetyContext.forbiddenBodyCells](../../common/src/main/java/org/maiwithu/maicraft/core/pathing/execute/NavigationSafetyContext.java) 合入导航。 |
| 取消后还在扑火、换世界后不再扑火 | [DiscardFireCleanup.tick](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/DiscardFireCleanup.java)；[ClientRuntime.advanceTasks](../../common/src/main/java/org/maiwithu/maicraft/client/runtime/ClientRuntime.java) 在普通任务之前推进收尾。 |
| 原始结果为何与任务查询不同 | [SemanticResultView.result/internalKey](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticResultView.java)、[ObservedGameEvidence.spatialField](../../common/src/main/java/org/maiwithu/maicraft/intent/ObservedGameEvidence.java) 和 [TaskView](../../common/src/main/java/org/maiwithu/maicraft/mcp/TaskView.java)；已知火格过滤见文末。 |

## 在游戏中实际按什么顺序做

1. **关旧 GUI，再固定数量。** 先等鼠标或旧界面返料；当前没有该物品则失败。有料时固定本轮目标，不随着后来库存增长自动扩大丢弃量。
2. **选点与准备。** 有打火石且允许本轮点火时，先看当前位置四个水平朝向。可用的走廊可能标记为 `requiresBurn`，意为后续要销毁或回收才能让路。否则在同层、已加载、X/Z 各距起点不超过 12 格的可走平面内找空地走廊；走廊都不成立而原地丢弃不会切断已勘察区域、也不堵住通往远处的唯一出口时，就地朝某个方向丢出（原地形态）；仍不行才尝试四格深、两格高的小侧袋。选点检查已有平面的连通、边缘和台阶出口，不是全世界寻路保证。
3. **到位、清理并站定。** 只走已有路线抵达候选，不通过这段导航任意改地形；需要侧袋时逐格调用原生破坏。候选跳过有流体、方块实体、不可破坏或明确受保护的格。三次原生接近失败后停止。确认四格投掷通道成立，才允许丢物；开挖已发生的世界变化不会回滚。
4. **朝选定方向略微抬头。** 当前实现俯仰为 −15°；通过真实视角收敛门槛并等水平移动接近静止。投掷前若离选定格心水平超过 0.3 格则停止，保留尚未丢出的物品。菜单投掷前先同步已到达的朝向。
5. **原生分堆、一次投出一份。** 优先选不超过余量的最大整堆，没有时分拆较小的一堆。整堆用菜单 `THROW` 的整堆按钮；只丢一件用单件按钮。部分数量先借空槽分堆；满包时用鼠标暂存、把多拿的退回来源格，再在界面外一次丢出鼠标上的全部目标量。分堆仍可能需要多次点击，“一次投掷”不等于一帧完成或任意总量只发一个包。
6. **确认数量，另看实体。** 每次点击都核对来源、暂存格及鼠标的精确物品快照。`DefaultMenuPort` 接受新菜单同步，或无新版本时经过往返延迟窗口仍稳定的后置状态；后者不是独立的地面实体交付证明。投掷前冻结附近实体基线，后续出生或同种同组件增量另由 `DiscardedItems` 观察。
7. **按实际观察安排火与回收。** 有打火石才尝试在已经落地的候选物品格下方使用它，副手打火石也可用。点火会检查原生可见面、距离和明确保护，并跳过混入其他物品或周围存在可燃物等场景。看到火后最多观察 100 游戏刻，再尝试左键扑火；有空槽先空手，满包不另丢工具腾手。火未出现、物品仍在、扑火失败都留证据，不直接改世界。
8. **原地形态先走出拾取范围。** 原地丢出的物品会撞到前方障碍、落在身边，原版玩家丢出物品只有 40 游戏刻拾取冷却。等投出的物品被客户端看到（最多 10 刻）后，若脚位仍在丢弃物拾取范围内，就走到 4 格内最近的范围外可站立格（总预算 30 刻）。走不开不改判丢弃失败，回执 `discard_step_away.pickup_risk=true`，成功话术提醒物品可能被捡回、需复核背包。
9. **只在预先标记的走廊位置回收换点。** `requiresBurn` 为真且处理后仍有被跟踪实体时，按它们的 UUID 和当前维度建立拾取子任务，临时取消本批避让。本人拾取包和入包证据确认数量后，再等六刻实体移除同步；随后重选不依赖点火的空地或侧袋。新一轮不会重复烧这份余物。原生拾取可以同时吸收合堆或周围物品，UUID 限定不能把一堆拆成“只拿回本次份额”。

内部初始期限是 200 游戏刻，按通常 20 TPS 约 10 秒；这不是整项任务的固定墙钟上限。准备、收尾和已确认点击会续期，导航、界面、瞄准、破坏与回收又有各自窗口。例如菜单点击等待 40 刻，点火/扑火回执 80 刻，侧袋单格破坏 600 刻，回收初始窗口 400 刻、半径 8 格。不要把一个尚在有界等待中的任务当作应该重发的命令。

## 避让是什么，不是什么

`DiscardedItems` 在每次客户端身体观察时更新，任务结束或玩家暂时接管后仍能跟踪同一身体、同一世界中的物品。观察候选的初始区域是投掷时玩家包围盒向各方向扩 8 格，出生/增量窗口为 100 刻。实体移动、临时 ID 更换或附近合堆时更新对应位置；区块卸载时暂存最后现场；加载区域中实体消失且未找到接收堆，超过五刻后解除旧标记。

导航排除的是**体积相交范围**，不是简单“以落点为圆心的一格球”。代码按掉落物包围盒横向扩 `1 + 玩家半宽`，纵向扩半格并向下计入玩家身高，再换成脚位格集合；因此斜角和头顶拾取也会受影响。异步寻路使用原有不可变策略快照，后续刷新接入新格集。它控制自动导航，不会禁止玩家手动走过去，也不能关闭磁铁等模组自身的吸物机制。

当前没有把这份实体跟踪表写进存档。死亡换身体、换世界、断线或游戏时间倒退会清空它，不能承诺重进世界仍记住所有旧垃圾。

## 怎样读结果与未知项

以下是丢弃子任务原始 `TaskResult.data` 的主要字段。公开语义任务可能把它放入步骤结果、失败效果或中断尝试中；从 `task(get)` 返回的实际 `detail_path` 读取，不编造任务编号或历史路径。**原始字段存在不代表公共投影一定保留，火格明细目前存在下述缺口。**

| 字段 | 应怎样解释 |
| --- | --- |
| `item` | 物品显示标签，当前取注册 ID 的路径部分，不保证带命名空间。 |
| `dropped` | 每次已确认投掷增加；每次回收后扣去回收量并以 0 为下限。不是烧毁数、稳定腾出的槽数或简单的背包净减少。 |
| `confirmed_thrown_units` | 包括回收后重投在内的累计已确认投出件数。 |
| `recovered_after_burn` | 回收子任务确认入包的累计件数；混堆或原生附带拾取需结合子回执，不假设这些全是最初那份垃圾。 |
| `drop_batches` / `drop_attempts` | 已确认投掷批次 / 已进入投掷提交路径的次数；分堆点击不算投掷批次。 |
| `remaining_in_inventory` | 回执生成时玩家可读库存中的同类型数量，包括盔甲、副手；不是仅主手余量。 |
| `outcome_uncertain` | 本任务主要标记未结投掷；公共 GUI 失败还可能补充不确定性。`false` 不保证落点、销毁或后台扑火已经全部验证。 |
| `discard_site` | `ready`、`requires_burn_to_clear_passage`、`site`、`excavated`。`site` 是选定脚位格，不是最终落点；`excavated` 是准备流程接收的完成格，不是产物数量。 |
| `discarded_item_avoidance[]` | 每个投掷观察的 `amount`、`entity_observed`、`observation_pending`、`entities[]`。`amount` 是拟投数量；`entity_observed` 只表示曾见候选。实体含 `uuid`、`position`、`avoided`；位置可仍在空中，也可能是最后观察位置。 |
| `discard_fire` | 到达收场阶段才有；包含观察到的点火数、已消失的火数、剩余跟踪实体数、观察标记、扑火队列标记和 `issues`。逐项检查，不只读父任务状态。 |
| `discard_fire.all_landings_observed` | 实际实现为每个 `Watch` 至少观察过候选，**不是所有数量已落地或已烧毁**。 |
| `discard_fire.remaining_fire_cells` / `uncertain_ignition_cells` | 原始回执中的待处理火格与未结点火格。当前语义过滤会删除这两个 `_cells` 字段，不能把公共结果中的缺失理解为数组为空。 |
| `discard_fire.cleanup_queued` | 已安排后台扑火，不代表已经扑灭；旧回执不会在后台动作结束后自动改写。 |
| `discard_step_away` | 仅原地形态有：`outcome`（`not_needed` / `left_pickup_range` / `pickup_range_not_left` / `no_standable_cell_outside_pickup_range` / `interrupted`）、`pickup_risk`，选过落脚格时附 `destination`。 |
| `disposal_attempts[]` | 已结束的回收尝试，保存当时的 `site`、`fire`、`recovery`。回收对象含 `collected` 和原生拾取子回执 `pickup`；最终当前轮现场另外看顶层字段。 |

`DiscardFire.done()` 即使有未烧掉的物品、点火问题或扑火问题，也会结束自己的阶段。只有父任务选点时要求通过烧毁让路、且还跟踪到余物，才进入上述回收分支。因此任务成功首先是执行流程与数量账的结算，不是全量销毁证明；`remaining_discarded_entities=0` 也可能只是实体离开了当前可跟踪现场。

## 失败、暂停、取消和恢复

| 情况 | 当前处理与调用者下一步 |
| --- | --- |
| 缺物品、数量未给、规范化数量超过接单库存 | 公开适配器请求决策。复制实际返回的 `decision_id` 和候选选项；`retry` 的修订放在 `details.parameters`，不要编造“丢全部”或缺料许可开关。 |
| 找不到局部空地/可挖侧袋，或三次接近失败 | 结束准备；尚未投掷的物品保留。先看 `discard_site` 和已发生的开挖，再决定移位或改变目标。 |
| 槽位、鼠标、物品身份或站位变化 | 停止当前批次；已确认的量保留，未确认的投掷不在同一对象里重发。根据实际库存和回执决定剩余量。 |
| 普通暂停或临时抢占 | 语义父任务保留同一个子任务对象，释放身体输入；恢复后先处理已有回执。暂停不等于回滚点击、立即灭火或冻结世界中的物品物理。 |
| 取消、超时或永久结束子任务 | `result` 先执行 `cleanup`：结算已冻结的点击和已确认回收、请求关闭自己使用的菜单、停止选点操作，必要时排入扑火。取消不能撤销已投出的物品、工具使用或开挖。 |
| 点火包在取消后迟到 | 后台对原格保留 100 刻的被动等待；没有火时让出身体，看到火后尝试扑灭。只在当前身体允许自动工作时执行。 |
| 后台扑火失败、旧身体或旧世界已离开 | 清理尝试会退下；没有跨世界自动扑火或持久队列，不能据此宣称旧现场已安全。 |
| 死亡、断线、重启或从磁盘恢复 | 按通用任务层保留可持久的目标、步骤和已有结果，未完成工作先暂停；不会恢复这些 Java 对象、实体避让表或火队列。丢弃记录没有接入 `NativeSubmissionTaskRecord` 的持久消费屏障，恢复前必须重新核实库存和部分效果，不能保证从未丢的余量精确续接。 |

### 确认未观察到时的回执

投掷点击走菜单确认，只有服务器明确拒绝（无消费）才报确定失败。确认超时、或菜单状态在点击前后对不上（分歧）时，点击可能已经执行——这类失败一律置 `outcome_uncertain=true`，失败话术为"点击可能已执行但确认未观察到，重试前先对账背包"。取消收尾同样不设丢弃数量门槛：任何未结点击（含分堆拿起）除明确拒绝外都按不确定上报。收到不确定回执先对账真实背包净变化再决定下一步，不要把失败回执当成"物品未丢"的证据去原数量重投。

同一 `request_key` 的网络重试用于找回同一次登记，不是跨重启的每一次物品投掷幂等凭据。更详细的父任务处理见 [任务调度与结果](tasks.md)；数据不足时做针对缺口的观察，不重新调查已经完整给出的同一事实，也不要因为未知就再丢一次原数量。

## 已知边界与需要后续处理的差异

1. **契约字段少于实际副作用。** 自动点火和侧袋开挖没有逐次请求开关；自然语言、`current_place` 或未声明的 `false` 参数不能约束它们。不可把本能力说明成原地赠物或精确投料。
2. **数量不是严格拒绝越界的解析。** 公开入口存在数值转换/截断，内部执行还可能因接单后的库存变化缩小目标；不能只根据输入值宣称完成量。
3. **公共回执丢失火格。** `SemanticResultView.internalKey` 过滤 `_cells`，而 `ObservedGameEvidence` 未为这两个字段豁免。成功、失败和中断结果可能在保存前已被过滤，单纯展开历史不保证补回原始火格。需另行修改结果行为，本说明没有改变过滤。
4. **销毁和清场的证据弱于字段字面意思。** 候选被观察、实体消失、阶段成功和真正烧毁不是同一事实；后台扑火结束也不回写既有回执。不要声称不存在剩余火或物品。
5. **通道保证是局部且在选点时作出的。** 可走候选要求同层、两格空气和下方上表面支撑；范围有界。`requiresBurn` 是选点结果，实际漂移后不会重新判断所有通道；复杂地形、液体、移动结构或模组吸物不能被概括成“永远不会再捡回或卡路”。
6. **重启与混堆需要单独核对。** 执行器没有专属持久投掷日志；回收完整目标 UUID 的混堆时，回收件数还可能包含原有份额，`dropped` 的下限归零使它不总等于累计投出减累计回收。原始三个计数及子回执必须一起看。

以上差异是当前实现边界，不是已经完成的修复。改变动作条件、顺序或返回语义时，应另行说明玩家行为变化并按仓库授权规则处理。

## 模组条件与已有验证入口

该执行链使用公共 Minecraft 玩家、菜单、实体及导航接口，不要求 AE2、Create 或可选服务端机器协议。它不会为点火自动获取打火石，也不会展开嵌套背包。模组改变堆叠上限、槽位布局、耐火性、物品漂移、拾取距离、吸物、原生交互权限或回执同步时，要按实际效果检查；本实现不能替这些模组保证结果。

已有场景都接入 [PickupRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/client/actor/PickupRegressionSuite.java)，由 [common/build.gradle](../../common/build.gradle) 的 `:common:pickupRegression` 启动：

| 入口 | 已有场景意图 |
| --- | --- |
| [DropBatchPlanTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/DropBatchPlanTest.java) | 1～64 个、满包分堆、盔甲和副手菜单映射。 |
| [DropCompanionTaskTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/DropCompanionTaskTest.java) | 真实视角门槛、朝向包顺序、40 个整份投掷及取消不重发。 |
| [DiscardedItemsTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/DiscardedItemsTest.java) | 拾取体积、漂移、合堆、ID 重绑定、消失和世界隔离。 |
| [DiscardSitePlanTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/DiscardSitePlanTest.java) | 单行通道、空地、侧袋、区块边缘以及有打火石的基岩走廊候选。 |
| [DiscardPocketTaskTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/DiscardPocketTaskTest.java) | 先观察侧袋开挖完成再投掷；无法准备位置时保留物品。 |
| [DiscardFireTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/DiscardFireTest.java) | 点火、余物、混堆、扑火、取消和迟到火焰。 |
| [DiscardRecoveryTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/DiscardRecoveryTest.java) | 同 UUID 本人拾取包与入包、临时避让豁免和取消恢复。 |

导航接线还可参考 `:common:navigationRegression`，菜单和身体收尾可参考 `:common:guiRegression`，完整构建方法见 [修改与验证方法](contributing.md)。入口存在不等于当前修改已经通过验证；静态源码、JSON 和链接检查也不能代替回归或实机验收。
