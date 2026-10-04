# 找到方块，再采收指定的一格

玩家说“看看附近有没有铁矿”和“把刚看到的那一块铁矿采回来”，需要两种不同的完成证据。`maicraft:find_block` 交付当前可见的方块观察；`maicraft:harvest_block` 要确认指定源格被原生挖掉，并看到期望物品进入主背包。找到坐标、走到附近、方块消失，都不能单独证明材料已经到手。

本文说明这两个公开能力。需要凑齐一批物品、准备前置工具或主动开路找矿时，阅读 [取物编排](acquiring.md) 中的 `acquire_items`；独立补拾取使用 `collect_items`。定点采收不会自行换成仓库取货、合成或整脉连锁。

## 从请求进入哪里

两者都通过 `plan` 的 `goal` 提交：能力名在 `goal.ability`，目的在 `goal.outcome`，选择器及开关在 `goal.parameters`，地点在 `goal.target`。不要把 `target` 塞进 `parameters`，也不要用 `outcome` 中的自然语言替代数量、坐标或授权字段。`goal.preferences` 保持空对象；这里的选择器与采收授权不从该处读取。

`plan` 只规划，不开始游戏动作。计划就绪后，把实际返回的 `plan_id` 交给 `execute`；`accepted` 只表示任务已登记。跟随回执的 `next_attention` 等待完成、决定或暂停。控制和保存的共用规则见 [任务运行与回执](tasks.md)。

| 想看哪一步 | 打开哪个文件或方法 |
| --- | --- |
| 公开请求外壳、参数层级与目标种类校验 | [PublicToolCatalog](../../common/src/main/java/org/maiwithu/maicraft/mcp/PublicToolCatalog.java) 的 `validateGoal`，以及 [PublicTargetContract](../../common/src/main/java/org/maiwithu/maicraft/mcp/PublicTargetContract.java) |
| 模型看到的字段、用途和恢复提示 | [SemanticAbilityCatalog](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java) 的 `FIND_BLOCK`、`HARVEST_BLOCK` 分支 |
| 把语义请求交给实际执行器 | [GeneralAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/GeneralAbilityAdapter.java) 的 `findBlock`、`harvestBlock` |
| 查块记录、数量与半径边界 | [SemanticBlockSearchApi](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/SemanticBlockSearchApi.java) 的 `newRecord`，以及 [SemanticBlockSearchTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/locate/SemanticBlockSearchTaskRecord.java) |
| 为什么扫描在推进，却暂时还没找到 | [LoadedBlockScan](../../common/src/main/java/org/maiwithu/maicraft/core/scan/LoadedBlockScan.java) 的 `advance`，以及 [SemanticBlockSearchCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/locate/SemanticBlockSearchCompanionTask.java) 的 `onTick`、`progress` |
| 哪些方块算当前能看见 | [ObservationVisibility](../../common/src/main/java/org/maiwithu/maicraft/core/scan/ObservationVisibility.java) 的 `block`、`ray` |
| 岩浆为什么还要统计整池 | [PortalLavaPoolSurvey](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/PortalLavaPoolSurvey.java) 的 `observeVisible`、`advance`、`facts` |
| 如何把采收限制在一个已观察源格 | [MineBlockTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/mine/MineBlockTaskRecord.java) 的 `onlyAt`、`approachTerrainAlter` |
| 工具、站位、破坏、掉落和完成条件 | [MineCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/mine/MineCompanionTask.java) 的 `preconditions`、`reachableTarget`、`mineProgress`、`collectDrops`、`resultData` |
| 原生破坏提交与确认 | [UltimineBreak](../../common/src/main/java/org/maiwithu/maicraft/core/integration/ultimine/UltimineBreak.java) 的 `tick`、`settle`，以及 [BlockDigger](../../common/src/main/java/org/maiwithu/maicraft/core/act/BlockDigger.java) 的 `advance`、`settleGone` |
| 收尾为何还要等待接触与背包同步 | [CollectItemsApproach](../../common/src/main/java/org/maiwithu/maicraft/core/task/collect/CollectItemsApproach.java) 和 [NativePickupReceipt](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/NativePickupReceipt.java) |

“任务单”只保存执行要求与进度；实际移动、挖掘和确认由任务执行器逐游戏刻推进。改说明时先核对适配器传了什么，再看执行器如何消费，不能仅凭能力名判断行为。

## `find_block`：在站立处查可见证据

角色不会为了查块走向新区块，也不会挖开覆盖层。扫描中心在任务开始时固定为角色所在方块格；每个候选仍按检查时的眼位做射线观察。这里的可见性不是摄像机画面边框：实现检查到目标中心或外露面的原生射线，没有要求准星正对它。

### 字段及缺省值

| 完整位置 | 有效写法、单位与实际缺省语义 |
| --- | --- |
| `goal.target` | 可省略或为 `null`；提供时使用 `{"kind":"current_place"}` 或 `{"kind":"nearest"}`。两者都从当前站立处查找，不指定另一个区域；不能带 `position`。 |
| `goal.parameters.block_id` | 可选的单个方块 ID 字符串，须可解析且在客户端注册表中存在。建议写完整命名空间。`null`、省略或空白字符串按未提供处理。 |
| `goal.parameters.block_ids` | 可选的方块 ID 数组，与 `block_id` 合并去重，合计最多 32 种。空数组或 `null` 不补充选择器；不是 `#标签` 查询接口。 |
| `goal.parameters.purpose` | 字符串 `blocks` 或 `portal_casting`；省略、`null`、空白字符串采用 `blocks`，实现会去首尾空格并转小写。`false`、`0` 等并不是合法用途。 |
| `goal.parameters.count` | 建议使用 JSON 整数，范围 1–32，默认 1。`blocks` 数方块位置；`portal_casting` 数匹配的池子。当前适配器把 0 和负数裁到 1，超过上限裁到 32；省略、`null` 或无法读成整数的值（如 `false`）使用默认值。 |
| `goal.parameters.max_distance` | 建议使用 JSON 整数，单位为方块，范围 4–128，默认 64。它是水平半径，覆盖相应区块柱的各高度段。0 和负数裁到 4，过大值裁到 128；省略、`null`、无法解析的值（如 `false`）使用 64。 |

`purpose=blocks` 必须通过两个选择器中的至少一个给出有效方块种类。`portal_casting` 只接受 `minecraft:lava`；未提供有效选择器时自动补上岩浆，不能与石头等其他种类混用。单个未知 ID 不会被静默丢掉后继续扫描，而是交回需要修正选择的决定。

这些数值边界是当前兼容转换行为，并非严格拒绝越界的校验：数字字符串可能被接受，小数可能被截成整数，超大数还可能先发生 32 位整数转换。另一个现有边界是，非数组的 `block_ids` 会被忽略；若另有合法 `block_id`，请求仍可能执行。新调用应使用上表的有效类型，不能依赖宽松转换。允许的 `target.kind` 上即使附带 `label/relation`，适配器也不据此更换扫描区域。

### 完整 `plan` 示例

从当前位置找两处可见铁矿位置，普通矿和深层变体都可接受：

```json
{
  "goal": {
    "ability": "maicraft:find_block",
    "outcome": "找到两处当前可见的铁矿位置",
    "target": {"kind": "current_place"},
    "parameters": {
      "block_ids": ["minecraft:iron_ore", "minecraft:deepslate_iron_ore"],
      "purpose": "blocks",
      "count": 2,
      "max_distance": 64
    }
  }
}
```

找一处具有已观察浇筑布局和源岩浆余量的池子；选择器省略后由该用途补为岩浆：

```json
{
  "goal": {
    "ability": "maicraft:find_block",
    "outcome": "寻找具备浇筑候选布局和源岩浆余量的可见池子",
    "parameters": {
      "purpose": "portal_casting",
      "count": 1,
      "max_distance": 128
    }
  }
}
```

### 扫描如何推进和收场

1. `LoadedBlockScan` 持有同一个段内游标，先处理角色高度附近的区块段，再扩展到其他高度。每刻按工作量和约两毫秒的时间片让步，不从头反复查询已经检查过的石层。
2. 未加载段直接记为未知部分；调色板中没有目标的段可以跳过。候选还要通过当刻的类型、范围及眼位射线复核，不能把客户端已加载当成透视授权。
3. 普通查块达到 `count` 即可成功，此时 `scan_complete` 可能仍为 `false`。`nearest_match_position` 是已验证观察中的最近位置，不保证是整个半径内的全局最近目标。
4. 选择器含岩浆时，即使普通用途且 `count=1`，也先完成范围扫描，再分刻分析可见池面。`portal_casting` 以存在浇筑起始排、且预计填补平台后至少剩余 15 格已观察源岩浆的池子计数。
5. 可见数量不足返回失败并保留部分观察；不能把找到一处但请求两处写成成功。岩浆池的几何和余量也不证明角色已到池岸、桶操作可执行或原生流体反应一定符合设计。

`done/total` 使用 `section_cells_processed_including_unloaded_or_palette_skips` 口径，包含跳过的区块段体积。逐格读取、提交候选、完成段、未加载段和调色板空段分别见 `examined_block_states`、`visibility_candidates_checked`、`finished_sections`、`unloaded_sections`、`palette_empty_sections`；这些数不是已看到的矿物数。

### 怎样读结果并恢复

- 数量看 `requested_count`、`count_unit`、`observed_acceptable_count`、`verified`；原始可见方块总量另看 `observed_block_count` 及按 ID 分类的统计。
- 范围看 `max_distance`、`search_geometry=horizontal_radius_across_loaded_sections`、`scope=visible_loaded_client_blocks`。`scan_complete=true` 也不会把跳过的未加载段变成已知空地。
- 距离是从开始时的方块格计算的三维距离，另附水平距离和高度差；不是原生交互距离或寻路距离。
- `nearest_match_position` 仅含 `x/y/z`。它是源格，不是脚位；需要维度时结合本次世界上下文。`portal_casting` 只从匹配池返回该坐标，没有匹配池就不返回孤立源格来充数。
- 岩浆附 `lava_pool_survey`，其中候选的 `reserve_observed`、平台填补量与 `remaining_sources_lower_bound` 是观察和布局分析，不是已经施工的效果。
- 无可见目标用 `no_block_evidence_within_bound`；数量不足用 `insufficient_block_count`；用途池不足用 `no_suitable_lava_pool_within_bound` 或 `insufficient_casting_pool_count`。换视点或加载确有必要的新区域后再查，不对同一现场无变化地反复扫描。
- 超时、取消分别标为 `find_block_timeout`、`find_block_cancelled`，`scan_outcome` 保留终态。取消和部分扫描不能证明余下范围不存在目标；换客户端世界时返回 `find_block_world_changed`。

当前公开 `find_block` 不写入 `ObservedSourceMemory`。它交出的坐标可以供模型构造下一次精确采收请求，但不能保证另一个 `acquire_items` 采矿任务已经记住该位置。内部取桶流程新增的 `sourceFluidsOnly` 和排除位置集不是公开参数，不要提交到 `goal.parameters`。

## `harvest_block`：一格源与真实入包分别确认

例如铁矿露在洞壁里，角色先核对这一格仍是指定矿，再选择当前背包里能采出材料的工具，走到可交互位置，按原版速度挖掘，最后靠近掉落物收取。没有合适工具时不会自动造镐；它交回工具不足，由模型另选已授权的准备流程。

### 字段与单格授权

| 完整位置 | 有效写法、单位与实际缺省语义 |
| --- | --- |
| `goal.target.kind` | 必须为 `coordinates`。本能力不接受 `nearest`、地标或 `prior_result`；需要把实际观察坐标写入 `position`。 |
| `goal.target.position.x/y/z` | 三个必需的整数方块坐标。0 是正常坐标，不表示省略或当前位置。不能用浮点物品实体位置代替源方块格。 |
| `goal.target.position.dimension` | 可选维度 ID；省略或 `null` 使用当前维度。显式指定其他维度不会触发跨维度旅行。 |
| `goal.parameters.block_id` | 必填的已注册方块 ID，执行适配时校验该格的类型。省略、`null`、空白或未注册名称不能形成有效来源。 |
| `goal.parameters.expected_output_item_id` | 必填的已注册非空气物品 ID。它声明哪种背包增量算目标，不验证配方、不改变掉落，也不把粗铁自动变成铁锭。省略、`null`、空白均不能有效采收。 |
| `goal.parameters.may_alter_terrain` | 必须明确为 JSON 布尔 `true`，仅授权该源格的破坏及原生邻格更新。省略、`null`、`false`、0 都不取得授权；当前布尔辅助函数也接受字符串 `"true"`，新调用不要依赖这个兼容行为。 |

没有公开的 `count`、`radius`、连锁形状或探矿参数。内部固定为一次源格破坏、至少一件期望物品新增；一块矿掉出多件时，如实报告实际增量，不截成一件。定点任务的 `onlyAt` 冻结执行适配时读取的完整方块状态，并把来源范围限制为这一格。

这个冻结不是“玩家很早以前看过的完整属性”校验：调用方只提交 `block_id`，完整状态来自实际执行时的读取。接近及拾取保持地形，`may_alter_terrain=true` 不会额外授权拆旁边的机架或扩宽通道。若补拾取需要开路，模型可针对实际 `drop_ref` 使用已获授权的 `collect_items` 开路能力，见 [掉落物规则](../../common/src/main/resources/assets/maicraft/knowledge/game_mechanics/item-drops.md)。

### 完整 `plan` 示例

下面沿用先前实际报告过的铁矿源格 `(-107,22,-55)` 演示请求结构。它不证明旧坐标现在仍有矿；实际提交必须换成这次观察得到且仍有效的坐标、方块类型和预期产物。示例省略维度，含义是在当前维度采收。

```json
{
  "goal": {
    "ability": "maicraft:harvest_block",
    "outcome": "采收这一个已观察铁矿源格并取得粗铁",
    "target": {
      "kind": "coordinates",
      "position": {"x": -107, "y": 22, "z": -55}
    },
    "parameters": {
      "block_id": "minecraft:iron_ore",
      "expected_output_item_id": "minecraft:raw_iron",
      "may_alter_terrain": true
    }
  }
}
```

### 从源格到掉落物的主流程

1. 适配器要求目标已加载、类型匹配、不是空气、流体状态为空且没有方块实体。因此含水方块也被排除；这组检查并不等同于“碰撞箱一定是完整实心方块”。工具、不可破坏性、保护及落沙等条件还会在执行时复核。
2. 任务先保存主背包期望物品数及地上已有物品，再只查询被冻结的那一格。原生动作尚未提交时，来源换状态或卸载会停止，不改挖新放入的方块。
3. 原地可够到就先挖；否则沿现有路线接近。脚下源格不是一律禁止：下方已加载、没有流体且满足当前可站立判定时，可以原地挖除并下降一格。
4. `UltimineBreak` 使用 `SINGLE` 模式，`BlockDigger` 完成工具准备、必要的菜单收尾、瞄准、持续挖掘及回执核对。有无 FTB Ultimine 都不会把这个任务扩大为整脉；确认挖掉源格后也不会追着同坐标的再生块继续挖。
5. 方块消失后先结算本任务持有的原生破坏回执；不能只凭空气把别人挖掉的方块算成自己的效果。随后收取匹配掉落并等待背包同步，路上偶然拿到同类物品不能在源格尚未确认破坏时完成采收。
6. 在有掉落的工作模式中，一次源格破坏后仍没取得期望物品，先等掉落窗口收尾，再报告 `expected_mining_output_not_observed`。已经观察到却走不到的掉落物则按拾取路径问题报告，不能改说配方一定不对。

### 回执与实际副作用

| 需要核对的事实 | 主要字段 |
| --- | --- |
| 本次源格是否确认挖掉 | `exact_source`、`confirmed_source_breaks`、`confirmed_harvests` |
| 指定物品是否到手 | `requested`、`gathered`、`expected_output_items`；`gathered` 是开始后的目标主背包增量 |
| 原位置现在是什么 | `source_now`，可以是再生后的方块或 `unloaded`；它与此前确认破坏是两件事 |
| 原生事务及未知项 | `ultimine_actions`、存在时的 `outcome_uncertain`，以及终态的 `failure_type` |
| 哪些东西没捡完 | `unreachable_drop_count`、`uncollected_drop`、存在时的 `remaining_live_drops` |
| 没取得期望物品时实际增加了什么 | `observed_inventory_increases` 与 `inventory_change_scope`；当前明细存在 16 种上限，见下文 |

常见失败包括工具不足 `wrong_tool`、来源变化 `target_lost`、没有路线 `no_path`、背包容量不足 `no_space`、未取得期望物品 `no_material`、原生效果未结清 `unknown`。先核对已经发生的破坏、实际库存及剩余掉落，再决定准备工具、补拾取或重新观察。确认破坏后回执会给出 `mechanical_retry_allowed=false`，不能为补捡材料机械重发采收同一格。

`gathered` 不等于来源归因证明：实现记录破坏点附近的掉落与混堆事实，但最终数量仍来自主背包增量。同种物品的外来增量、旧堆和混堆要结合 `pickup_scope`、`ambiguous_merged_drop_count` 判断；这里没有 `collect_items` 精确掉落引用的同 UUID 拾取包条件。

## 暂停、死亡、换世界与恢复

- 同进程暂停时，查块的游标和部分观察仍在任务对象内；采收则先释放原生控制并结算能确认的效果。取消不撤销已经破坏的方块，也不退还消耗的工具耐久。
- 采收暂停时若还有未结清的破坏，`closeChain` 会保留未知；恢复不能靠再次挥镐覆盖这个状态。已经确认的源格效果仍保留，后续只应核对或收尾相应产物。
- 死亡和玩家接管由共同的身体及语义任务层处理；这两个能力不自行复活、跨世界追踪或重造物品。参见 [IntentRuntime](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) 的死亡决定和世界绑定，以及 [TaskSlot](../../common/src/main/java/org/maiwithu/maicraft/task/TaskSlot.java)。
- `find_block` 发现 `ClientLevel` 已变化时明确失败。采收使用原生身体/世界绑定和当刻源格检查；旧坐标与旧回执不能证明新世界存在同一来源。
- 重启恢复保存的是语义目标和历史，不是可以继续发送的原生挖掘交易或段内扫描游标。未结束记录由 [IntentTaskRecord.restored](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTaskRecord.java) 恢复为 `paused_restored`，再按 [任务控制](tasks.md) 恢复；不要把重新适配同一坐标理解成可靠的恰好一次续采。

## 已知边界与维护时要核对的差异

- 脚下采收由 `safeSupportDescent` 判断下一层实际落脚条件；旧说明中的一律禁止与代码不符，不能据此删除现有分支。
- `find_block` 数值读取有裁剪及宽松转换，且非数组 `block_ids` 可能被忽略；契约不能声称所有错误类型都会严格拒绝。
- `find_block` 没有把结果写入采矿观察记忆，现有 `mine-source-scope.md` 的相关说法与调用链不一致。自动采矿需要自己的可见性/记忆判断。
- 扫描器会快速跳过全空气段，即使选择器里写了空气类型；不要把该能力当成完整的空气体积或建筑空地查询。
- 期望物品缺失时，`resultData` 对 `observed_inventory_increases` 使用 `limit(16)`，且未附截断标记。当前回执不能证明其余物品没有增加。
- 底层 `BlockDigger` 把多种非成功终态归为 `NO_SHOT`；`UltimineBreak` 的该分支并不总把“已尝试但源格未确认”标成未知。原生 `UNCERTAIN` 的保真需要单独修复，不能用未出现 `outcome_uncertain` 推导绝无未决效果。

这些边界是源码复盘结果。本轮只整理契约、正式文档与注释，没有修改动作条件、执行算法、返回语义或原生操作顺序。

## 已有验证入口

| 想核对什么 | 已有入口 |
| --- | --- |
| 可见性、三维距离、计数、逐刻游标及取消语义 | [SemanticBlockSearchTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SemanticBlockSearchTest.java)，由 `:common:semanticBlockSearchRegression` 调用 |
| 单格状态冻结、再生边界、产物名称和脚下安全下降 | [ExactHarvestTest](../../common/src/test/java/org/maiwithu/maicraft/intent/ExactHarvestTest.java)，由 `:common:guiRegression` 调用 |
| 实际工具掉落门槛与效率工具耗尽后的行为 | [HarvestToolTierTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/mine/HarvestToolTierTest.java)，由 `:common:navigationRegression` 调用 |
| 无期望产物时停止继续破坏 | [MiningOutputBudgetTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/mine/MiningOutputBudgetTest.java)，由 `:common:guiRegression` 调用 |
| 破坏点拾取、坑边站位、旧堆和背包同步 | [DroppedItemPickupTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/DroppedItemPickupTest.java)，由 `:common:pickupRegression` 调用 |
| 原生批次确认、取消和结果投影 | [UltimineMiningRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/core/task/mine/UltimineMiningRegressionSuite.java)，由 `:common:ultimineMiningRegression` 调用 |

任务注册与各入口接线见 [common/build.gradle](../../common/build.gradle)。本轮仅做源码对照、示例 JSON、本地链接和 diff 静态检查；未新增或运行测试，未启动游戏或 Luna，不据此声称实机验收通过。
