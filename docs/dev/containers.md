# 打开箱子与按物品存取

玩家说“打开这只木桶看看”和“从这只木桶再拿四块木板”，会触发两种不同的流程。前者走到可交互位置、腾出主手并右键，随后报告菜单是否真的出现；后者还要分清箱子与主背包的槽位，计算数量、完成原生搬运并关闭菜单。这里说明 `maicraft:use_container` 和 `maicraft:manage_container`。

“不知道材料在哪，去附近箱子找”由 [`acquire_items`](acquiring.md) 负责。它的记忆排序、每需求访问去重、首次原点持久化和 32 格活动范围不能直接套用到本文两项定向能力。底层逐笔点击和确认见 [菜单搬运](menu-transfers.md)。

## 先选对目标含义

| 玩家要做什么 | 能力与参数 | 完成后应看什么 |
| --- | --- | --- |
| 打开一个已加载的方块容器 | `use_container`，指定类型或真实坐标 | 原生动作结果，以及 `menu_open_verified`；不执行箱内物品搬运 |
| 再拿四件、再存四件 | `manage_container`，`operation=withdraw/deposit`、`count=4` | 本次累计 `moved_count` 和 `moved_items` |
| 把主背包补到十六件 | `manage_container`，`operation=withdraw`、`target_count=16` | `observed_final_main_count >= 16` |
| 把箱内库存补到六十四件 | `manage_container`，`operation=deposit`、`target_count=64` | `observed_final_container_count >= 64` |
| 主背包恰好保留十六件，多了放回、少了取出 | `manage_container`，`operation=balance`、`target_count=16` | `observed_final_main_count == 16` |

主背包指玩家物品栏和快捷栏共 36 格，不包含盔甲和副手。物品组按所有匹配物品的总数计算；`item_ids` 中列两种木板、`count=4` 表示合计四件，不是每种四件。

## 请求的完整层级

先读 `perceive(view="abilities", focus="maicraft:use_container")` 或对应的 `manage_container` 契约，再提交 `plan`。下面的示例都是完整的 `tools/call` 请求；`plan` 校验并保存目标，不会打开箱子。执行时使用该计划真实返回的 `plan_id`，或向 `execute` 提交同一份 `goal`；接单回执的 `accepted` 不是动作完成。

部分具体值、数量互斥和现场条件仍在执行适配器或任务中检查。计划通过不等于所有字段都已严格验值，也不证明目标箱子可用。

- `goal.ability` 选择能力，`goal.outcome` 描述玩家要求。数量、目标和通行许可必须写入结构化字段，不能只写在 `outcome`。
- 容器位置放在 `goal.target`，类型、物品和数量放在 `goal.parameters`。
- 两项能力自己的 `preferences` 和硬约束表为空；通常省略，或写 `preferences={}`、`constraints=[]`。通用死亡恢复授权由运行时另行解释，不能从开箱请求推导出复活许可。
- 不填写槽号、菜单编号、点击脚本、`closeAfter` 或 `investigationScope`。这些是内部执行字段。`landmark_label` 也是适配后的内部字段；公开请求使用 `goal.target.label`。
- `request_key` 属于 `execute.arguments`，用于识别同一次请求的传输重试；它不把另一个新请求的 `count` 搬运变成幂等操作。

## `use_container`：右键使用并报告开箱观察

### 目标与参数

`goal.target.kind` 接受 `coordinates`、`nearest`、`landmark`、`area`，也可省略整个目标。实体背包、`prior_result`、`current_place` 不是此公开能力的目标类型。目标省略时按玩家位置查找，但仍需 `block_id`。

| 完整字段 | 类型与默认 | 当前含义 |
| --- | --- | --- |
| `goal.target.position` | `coordinates` 时需要对象，含整数 `x/y/z` | 复制真实观察中的箱体位置；不是让角色站进箱子。该格必须已加载，失配不另选箱子 |
| `goal.target.position.dimension` | 字符串；省略或 `null` 表示当前维度 | 精确目标指向其他维度会被拒绝；需要跨世界恢复同一地点时应保留真实维度 |
| `goal.target.label` | `landmark/area` 的已记住地点名 | 未提供位置时解析同维度地标作为搜索中心；查不到就返回原因，不回退到玩家脚边 |
| `goal.parameters.block_id` | 方块 ID；非精确坐标目标必填 | 完整命名空间写法如 `minecraft:barrel`。精确坐标下可省略；若提供则要求该格仍是此类型 |
| `goal.parameters.selection` | 建议 `nearest` 或 `unique`；省略、`null`、空白表示未声明最近优先 | 非精确搜索有多个候选时，未允许 `nearest` 会要求澄清。目标种类或 `target.relation` 为 `nearest` 也会授权就近选，参数 `unique` 不覆盖它 |
| `goal.parameters.purpose` | 可选字符串，推荐 `open`、`inspect`、`use`；省略/`null` 走普通使用 | 这三个标签走同一原生右键路径；`inspect` 不代表只读库存查询，也不提供专门的全箱库存查询结果。`attack/break` 被拒绝；并未实现严格的三值枚举 |
| `goal.parameters.radius` | 建议整数，单位格；默认 `64`，有效范围 `4..128` | 围绕玩家或目标地点搜索已加载区块柱，按水平距离过滤，包含不同高度；精确坐标不走这段搜索。不是角色移动的固定原点边界 |
| `goal.parameters.may_alter_terrain` | 布尔值；省略、`null`、`false` 都按 `false` | `true` 允许接近时的原生通行准备；仍要满足实际交互条件，不保证任意箱子可达 |

地标/区域目标若同时提供有效的同维度 `position`，当前 `semanticCenter` 优先使用该位置；最近目标始终以玩家为中心。建议只使用一种明确的位置依据。

数量字段、`item_id`、`tag`、`protected_labels` 不是 `use_container` 的专属参数，不应混入。序列中已继承的显式保护仍由公共导航和原生交互边界执行。

### 一次调用的实际顺序

1. 校验目标、方块 ID 和维度；精确位置只认该格，非精确位置从已加载索引选择候选。
2. 接近可交互站位。必要时原生切换空快捷栏或把主手物品收回背包，避免手持工具或材料抢走箱体右键。
3. 等镜头转到目标并核对原生射线，提交一次右键，等待这一次操作的原生结果。
4. 右键完成后继续观察是否出现新的可见容器菜单；额外观察最多 40 次任务推进，不重放已确认的右键。
5. 若菜单确实出现，登记本次原生菜单与目标的来源关系，供后续查询、存取复用。结束时保留该菜单。

**动作完成和菜单打开是两个事实。** 当前 `verifiedOutcome` 不把 `menu_open_verified=false` 单独改判失败，所以 `success=true` 不能单独证明开箱成功。还应看 `menu_open_verified`、`menu_observation_ticks`、`menu_class`、`menu_cursor_empty` 和 `native_action_status`；`inventory_transfer_verified` 固定为 `false`。不要把空光标或一次成功右键解释为已经拿到物品。

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "plan",
    "arguments": {
      "goal": {
        "ability": "maicraft:use_container",
        "outcome": "打开最近的木桶，并报告菜单是否真的出现",
        "target": {"kind": "nearest"},
        "parameters": {"block_id": "minecraft:barrel", "purpose": "open", "radius": 16, "may_alter_terrain": false}
      }
    }
  }
}
```

## `manage_container`：一只容器内的语义存取

### 目标选择

公开目标同样接受 `nearest`、`landmark`、`area`、`coordinates`。精确坐标需要 `position.x/y/z`，维度省略或 `null` 时使用当前维度；提供 `block_id` 就同时检查类型。`radius` 不会让精确目标改选附近另一只箱子。

`landmark` 和 `area` 在这条适配路径读取的是 **`target.label`**。用已经记住的同维度地点名作为搜索中心；只有改成 `kind="coordinates"` 才能表达精确位置。当前 `manageContainer` 不把 `area.position` 接到搜索中心，不能只凭区域坐标声称已经限定了位置。

目标省略时，至少提供 `block_id` 或 `selection="nearest"`。`block_id` 是类型过滤，不是箱子名称或位置。搜索后按“与地标同名的自定义容器优先 → 离搜索中心近 → 稳定顺序”选候选；`unique` 要求过滤后只剩一个，`nearest` 接受第一个。这里不会先读每只箱子的库存再选有货者，也不会在失败后自动遍历下一只。

### 物品、数量与其他参数

下面均位于 `goal.parameters`，位置仍位于 `goal.target`。

| 字段 | 类型、默认与范围 | 当前含义和组合要求 |
| --- | --- | --- |
| `operation` | 必填字符串：`deposit`、`withdraw`、`balance`；无公开默认值 | 存入、取出、调平主背包；省略/`null` 不能开始存取 |
| `item_id` | 可选物品 ID；省略/`null`/空白视为未提供 | 与 `item_ids` 合并去重；物品须真实注册且不是空气 |
| `item_ids` | 可选物品 ID 数组 | 与 `item_id` 共用一组总数量；空数组不能独立选物。公开适配器拒绝显式 `null` 和非数组 |
| `tag` | 可选物品标签 ID，不带 `#` | 与**非空** ID 组互斥，二者必须选一种。例：`minecraft:planks`；标签是否存在在任务调查时确认 |
| `count` | 可选整数，`1..4096`；省略/`null` 为未提供 | 本次额外搬运的合计件数，与 `target_count` 互斥；`0` 非法 |
| `target_count` | 可选整数，`0..4096`；省略/`null` 为未提供 | 与 `count` 互斥。存入看箱内最少数量；取出看主背包最少数量；`balance` 必填且看主背包精确数量 |
| `block_id` | 可选方块 ID | 过滤已加载容器；精确目标下也是类型断言。它不保证原生权限、容量或菜单布局可用 |
| `selection` | 可选 `nearest`/`unique`；默认 `unique` | `target.kind="nearest"` 或 `target.relation="nearest"` 也会选择最近，哪怕参数写了 `unique` |
| `protected_labels` | 可选非空名称数组，默认 `[]` | 同维度已记住地点周围 12 格球形范围及同名自定义容器受保护；任一名字未记住会失败。显式 `null` 不合法 |
| `radius` | 建议整数，格；默认 `32`，有效范围 `1..64` | 围绕玩家或地标的三维候选搜索半径。不是路程预算，也不继承自动补料的固定 32 格范围 |
| `may_alter_terrain` | 布尔值；省略/`null`/`false` 均按 `false` | `true` 允许原生接近路线准备，不绕过保护、容器锁或槽位规则 |

物品选择按类型或标签判断，不提供组件敏感的 `resource_id` 筛选。自定义名称、附魔等组件不同但 ID 相同的堆仍可能被选中；实际合堆和转移会保留物品组件，不能据此承诺“只存普通物品、保留珍贵变体”。ID 建议写完整命名空间；底层遵循原生 `ResourceLocation` 解析，原版简写可能被解释为 `minecraft` 名称空间。

数量的易混淆分支：

- `count` 与 `target_count` 都不提供时，`deposit/withdraw` 搬来源侧所有匹配物品；如果总数是零，则返回 `insufficient_source`。`balance` 不接受缺失目标数量。
- `target_count=0` 是有效最终数量。`balance` 会把主背包中的匹配物品全部放回箱子；`deposit/withdraw` 的“至少零件”通常已经满足，不需要搬运。
- `deposit/withdraw` 的最终数量已经高于目标时不会反向拿走超额；需要双向调平主背包才用 `balance`。
- 即使最终数量已满足，公开 `manage_container` 仍先选箱、开菜单和读取库存，再判断无需搬运。不要承诺它和 `acquire_items` 一样会在开箱前直接完成。
- 初始来源不足、目标槽无足够容量或不能取放时，公开路径不会为了凑数先搬一部分。开始搬运后若现场变化，已确认的部分保留，再报告剩余问题。
- `4096` 是请求数量上限，不代表主背包或所选容器一定装得下；省略数量而来源总量超过上限也会失败。

公开适配层还使用宽松数字读取：`count/target_count` 的 `getAsInt` 可能先截断小数或转换数字字符串，再检查范围；`radius` 无法读取时回默认值、越界时夹到边界，因此 `radius=0` 在这两个能力分别会变成 `4` 和 `1`。内部 `SemanticContainerTool` 虽使用严格解析，已经被上层转换的原值无法在这里恢复。模型应只发送上述类型和范围内的整数，当前不能写成“公开入口严格拒绝所有非整数”。布尔字段同样应发送 JSON `true/false`，不要依赖适配器的宽松转换。

### 完整请求示例

再取四件，不是“把背包补到四件”：

```json
{
  "jsonrpc": "2.0", "id": 2, "method": "tools/call",
  "params": {"name": "plan", "arguments": {"goal": {
    "ability": "maicraft:manage_container",
    "outcome": "从最近的木桶再取四块橡木木板",
    "target": {"kind": "nearest"},
    "parameters": {"operation": "withdraw", "item_id": "minecraft:oak_planks", "count": 4, "block_id": "minecraft:barrel", "radius": 16, "may_alter_terrain": false}
  }}}
}
```

把箱内圆石补到至少六十四件，所需数量由实际箱内库存决定：

```json
{
  "jsonrpc": "2.0", "id": 3, "method": "tools/call",
  "params": {"name": "plan", "arguments": {"goal": {
    "ability": "maicraft:manage_container",
    "outcome": "把最近箱子里的圆石补到至少六十四件",
    "target": {"kind": "nearest"},
    "parameters": {"operation": "deposit", "item_id": "minecraft:cobblestone", "target_count": 64, "block_id": "minecraft:chest", "radius": 16}
  }}}
}
```

用有效的零目标清空主背包中的木板组；没有填写非法的 `count=0`：

```json
{
  "jsonrpc": "2.0", "id": 4, "method": "tools/call",
  "params": {"name": "plan", "arguments": {"goal": {
    "ability": "maicraft:manage_container",
    "outcome": "把主背包中所有木板放入最近的木桶",
    "target": {"kind": "nearest"},
    "parameters": {"operation": "balance", "tag": "minecraft:planks", "target_count": 0, "block_id": "minecraft:barrel", "radius": 16, "protected_labels": []}
  }}}
}
```

明确要求把两种物品全部存入时，省略两个数量字段；这不是每种只存一件：

```json
{
  "jsonrpc": "2.0", "id": 5, "method": "tools/call",
  "params": {"name": "plan", "arguments": {"goal": {
    "ability": "maicraft:manage_container",
    "outcome": "把主背包中的圆石和泥土全部存入唯一匹配的木桶",
    "parameters": {"operation": "deposit", "item_ids": ["minecraft:cobblestone", "minecraft:dirt"], "block_id": "minecraft:barrel", "selection": "unique", "radius": 8}
  }}}
}
```

这些示例不预设现场一定存在容器、物品、容量或标签。计划有效也不证明游戏动作已完成。

## 从玩家动作找到代码

| 想看哪一步 | 文件与方法 | 应核对的游戏事实 |
| --- | --- | --- |
| 参数是否放在正确层级 | [SemanticGoalContract](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java)：`validate`；[SemanticAbilityCatalog](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java)：两个容器分支 | 参数名、目标种类与共同任务控制契约；多数具体值仍在适配或执行时检查 |
| 怎样把目标变成开箱/存取请求 | [GeneralAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/GeneralAbilityAdapter.java)：`interact`、`interactBlock`、`compileBlockInteraction`、`manageContainer` | 精确位置与地标选择、最近选择的合并规则、数量转换 |
| 内部任务单怎样形成 | [SemanticContainerTool](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/SemanticContainerTool.java)：`onGameCall`；[SemanticContainerTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/SemanticContainerTaskRecord.java) | 物品组去重、数量互斥、默认值、公开目标与内部供料字段的区别 |
| 选到了哪只箱子 | [SemanticContainerCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/SemanticContainerCompanionTask.java)：`survey`、`selectionCenter`、`loadedCandidates`、`candidateAt` | 已加载类型、保护地标、自定义名称、唯一性与实际目标身份 |
| 为什么还在走路、整理主手或等镜头 | [InteractAtCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/interact/InteractAtCompanionTask.java)：`prepareEmptyHand`、`onTick`；[InteractAtTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/interact/InteractAtTaskRecord.java)：`withApproach` | 真实站位、视线、地形许可及当前原生点击，而非远程读改箱子 |
| 菜单能否复用、是否能解释槽位 | `SemanticContainerCompanionTask.reuseOpenMenu/waitMenu/classify`；[MachineMenu](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineMenu.java)：`openedAt` | 同一菜单来源、玩家/世界身份、36 格主背包与外部库存的布局 |
| 应搬多少、能否放下 | `SemanticContainerCompanionTask.direction/requestedAmount/buildPlan/transfer` | 单向补差、双向调平、原生槽位容量；尚未提交的剩余动作会重读现场 |
| 某笔搬运是否真实完成 | `SemanticContainerCompanionTask.verifyTransfer`；[ContainerTransferCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerTransferCompanionTask.java) | 双边数量和组件身份、游标归零、原生确认；细节见 [菜单搬运](menu-transfers.md) |
| 为什么箱子记忆变了 | [ContainerSupplySources](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerSupplySources.java)：`rememberVisible`；[ContainerMemory](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerMemory.java)；[StockEvidence](../../common/src/main/java/org/maiwithu/maicraft/core/inventory/StockEvidence.java)：`observe` | 仅已同步、真实可见的菜单更新库存；被动观察使用只读身份，历史库存不是实时查询 |
| 为什么停止、关闭或保留菜单 | `SemanticContainerCompanionTask.failFinal/menuLost/cleanupMenu/cleanup/resultData` | 已确认效果、未确认点击、界面归属和光标物品分别处理 |
| 暂停和新请求为何影响旧任务 | [IntentTask](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)：`stop`；[TaskSlot](../../common/src/main/java/org/maiwithu/maicraft/task/TaskSlot.java)：`put/finishByInterruption` | 暂停保留逻辑进度；另一项前台 `execute` 会接管并取消旧任务 |

## 菜单确认、回执和恢复

定向存取的状态顺序是 `SURVEY → OPEN/复用 → WAIT_MENU → PLAN → TRANSFER → CLEANUP → COMPLETE`。开箱子任务成功后还要等外部菜单可见、光标为空并识别槽位；没有菜单时等待上限为 80 游戏刻。**完整服务器库存同步的额外等待目前以 `storageSupply()` 为条件，公开 `manage_container` 没有执行同一项检查**，不能把菜单刚出现的所有计数都解释为已验证完整同步。

每笔开始前会重读真实槽位，检查源格 `mayPickup`、目标格 `mayPlace`、容量及组件兼容性。尚未提交下一笔时，外部槽位变化会重新规划剩余量；已有原生点击则先等该点击确认，不通过重发掩盖延迟。

| 回执字段 | 解释 |
| --- | --- |
| `container_observation` | 实际容器类型、维度、坐标、选箱观察时刻，以及本次是否识别过菜单；不表示菜单现在仍开着 |
| `inventory_counts_observation_status` | `not_observed` 时没有读过双方槽位，缺货不能按默认零数推断；`observed` 后才带对应计数 |
| `initial_main_count`、`observed_final_main_count` | 主背包匹配物品的起始和最后观察量，含时间戳，不含副手/装备 |
| `initial_container_count`、`observed_final_container_count` | 容器侧匹配物品的观察量；不能替代累计转移量 |
| `moved_count`、`moved_items` | 本层通过完整 `verifyTransfer` 的分笔累计量。失败不清零；失败子任务中的已确认部分可能只在 `last_native_transfer.data.moved_counts` 中出现 |
| `goal_satisfied`、`outcome_partial` | 当前语义数量目标是否满足；`outcome_partial` 依赖本层 `moved_count`，不能单独证明底层没有部分转移 |
| `effects_started`、`outcome_uncertain` | 搬运子流程是否有已提交/已确认效果、是否留下不确定性。不能把 `effects_started=false` 当成整条接近路线完全没改变世界的证明 |
| `last_native_transfer` | 最近一笔低层搬运的消息及证据，包含已完成笔数、实际数量、分堆点击和保留菜单信息 |
| `container_memory` | 本次最后保存的完整历史库存、稳定标识和时间；是最近观察，不是保证现在仍有货 |
| `failure_code`、`decision.recovery_options` | 具体失败与恢复依据；当前任务是否进入待答状态仍以顶层任务查询为准 |

成功存入机器只证明物品完成原生转移，不证明机器运行、配方正确或已经产出。对于能立即消耗物品的目标槽，还要区分“累计已放入”与“最后仍留在槽里”。

恢复按事实选择下一步：

- `insufficient_source` / `source_slots_locked`：看来源真实数量及可取槽位，决定剩余数量或其他明确容器。
- `destination_full_or_locked`：检查真实容量或槽位限制；未提交的动作可以重排，已确认部分保留。
- `ambiguous_container`：仅在确实允许任选时用 `nearest`，否则缩小类型/地标范围或点名观察到的坐标。
- `container_menu_lost` / `container_transfer_unconfirmed` / 光标不空：先核对原回执、当前背包和菜单，不能把原数量盲目再发一次。
- `specialized_storage_required` / `unsupported_modded_slots`：使用该系统已接通的专用能力；不猜虚拟槽含义。

同进程普通暂停会保留子任务和逻辑进度，任务暂不争取身体；它不提供第二个前台任务槽。新的 `execute` 可以把暂停任务以 `takeover` 取消。终止或取消不会回滚已经搬走的物品；收尾只处理仍归当前流程的界面，未知光标需要保留。

死亡、断线或换世界会触发共享身体生命周期处理。重启后未完成语义任务先以暂停状态恢复；旧菜单、路线、槽位事务和 `manage_container` 的内存累计量不作为可继续执行的旧对象恢复。恢复到 `manage_container` 时会重新解释原目标并读现场；`count` 是额外搬运量，原请求重做可能再搬一次。只有核实当前实际容器与库存后，才能选择剩余数量或合适的 `target_count`。对 `use_container`，恢复也可能再次右键，不能把它当成无副作用的查询。更多控制流程见 [任务](tasks.md)。

## 模组条件与当前边界

公开存取候选要求已加载的方块实体实现 `Container`，且方块有菜单提供器。原版箱子、潜影盒、漏斗、发射器及熔炉菜单有明确分类；其他菜单须能证明一个普通外部库存、普通槽类和完整的 36 格玩家侧。AE2 的方块/菜单标识被转交专用存储路径；不能承诺所有带 GUI 的模组方块都支持通用搬运。

以下是源码可见的现状，不是本轮新增的游戏行为：

- 两个公开定向入口的候选搜索没有统一套用 `ObservationVisibility`；真正交互仍核对原生射线和接近条件。不要把自动取物“关门遮挡的箱子不调查”推广为这两个入口的候选过滤承诺。
- 公开 `manage_container` 按方块实体枚举，大箱两个半边可能使 `unique` 报歧义；普通候选的地标保护只检查所选格，未像自动供料一样同时核对大箱另一半。
- 箱体周围向各轴扩六格的盒形区域内有其他活着且非旁观玩家时，任务可能返回 `other_player_near_container`。这是本地接近检查，不证明对方正在用箱，也不等同服务端权限拒绝。
- `use_container.purpose` 并无独立的只读检查模式，也没有完整枚举校验；不要依赖任意字符串。共享交互分支中的特殊值仍可能改变处理，例如 `till` 会要求耕地结果，不适合作为开箱用途。
- 已确认的全部搬运完成后，若聚合目标不成立，公开路径会返回 `aggregate_goal_not_satisfied` 并设置不确定标记；解释时仍保留 `moved_count` 和底层已确认事实，不能把它们改写为“完全未发生”。
- 底层快速移动若实际搬了部分数量后失败，父层保留完整的 `last_native_transfer`，但不会进入成功分支累计这部分到 `moved_count`。所以顶层计数为零或 `outcome_partial=false` 时，也必须读底层已确认量及双方最后观察库存。
- `open_failure_message` 目前在父层截到 512 字符。途中取消的清理只停止活动子任务，没有收取它的完整 `result`；故障审阅还需要保留原始子任务和当前库存证据，不能承诺每种中断都已完整汇总。

## 既有验证入口

| 想核对的行为 | 已有入口 |
| --- | --- |
| 显式保护与容器访问 | [ContainerAccessPolicyTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/ContainerAccessPolicyTest.java)、[ContainerSupplySourcesTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/ContainerSupplySourcesTest.java) |
| 实际菜单更新记忆、动作刻之外的被动观察 | [ContainerInvestigationTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/ContainerInvestigationTest.java) |
| 数量规划与槽位变化后的续作 | [ContainerBatchReplanTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/ContainerBatchReplanTest.java)、[ContainerDepositCapacityTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/ContainerDepositCapacityTest.java) |
| 逐笔原生数量证据 | [QuickMoveEvidenceTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/QuickMoveEvidenceTest.java)、[ContainerSplitTransferTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/ContainerSplitTransferTest.java) |
| 空手右键准备 | [EmptyHandInteractionTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/EmptyHandInteractionTest.java) |
| 整组回归入口 | [ContainerSearchRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/core/task/container/ContainerSearchRegressionSuite.java)、[GuiRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/client/actor/GuiRegressionSuite.java) |

取物范围恢复的 [ContainerSearchScopePersistenceTest](../../common/src/test/java/org/maiwithu/maicraft/intent/ContainerSearchScopePersistenceTest.java) 针对 `acquire_items`，不能当作直接 `manage_container(count=...)` 跨重启幂等性的证明。本轮只做源码和文档静态核对，没有新增或运行这些测试，也没有进行实机操作。
