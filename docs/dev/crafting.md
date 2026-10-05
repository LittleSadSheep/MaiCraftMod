# 从背包材料到真正入包的合成产物

玩家要求“做 8 支火把”时，`maicraft:craft` 的 `count` 是“再做 8 支”：本步开始时已带 4 支，目标就是主背包至少 12 支，已带的只作起始数，不算新做的；没有火把而有煤、木棍时，两批普通配方可以在背包 2×2 格内完成。做木锄、铁镐等需要较大网格的物品时，角色会寻找已放置的工作台，或摆出随身工作台，合成结束后尝试收回。

这不是远程凭空生成物品，也没有“工作台留在物品栏就直接拥有 3×3 网格”的操作。放置、开菜单、摆料、取结果、入包和回收都经过原生操作。允许外出补料应使用 [acquire_items](acquiring.md)；格子合成不能代替熔炉、切石或 Create 等机器加工，EMI 能展示一种配方也不代表这里能执行它。

## 公开参数与完整示例

所有合成参数放在 `goal.parameters`。以下分别是完整的 `plan` 工具参数；计划不会提前扣材料或保证能放下工作台。随后用实际返回的 `plan_id` 调用 `execute`，或提交同一 `goal` 与稳定 `request_key`，再跟随 `next_attention`，不要把 `accepted=true` 当作合成成功。

```json
{
  "goal": {
    "ability": "maicraft:craft",
    "outcome": "再做8支火把",
    "parameters": {
      "item_id": "minecraft:torch",
      "count": 8
    }
  }
}
```

```json
{
  "goal": {
    "ability": "maicraft:craft",
    "outcome": "做一把木锄，现有材料不足时优先用丛林木的中间配方",
    "parameters": {
      "item_id": "minecraft:wooden_hoe",
      "count": 1,
      "preferred_materials": ["minecraft:jungle_log", "minecraft:jungle_planks"]
    }
  }
}
```

| 字段 | 类型、默认值与边界 |
| --- | --- |
| `goal.parameters.item_id` | 请求产物的物品 ID 字符串，推荐明确写 `namespace:id`；最终须为已注册非空气物品。正常调用必须提供，不支持本能力的 `item_ids` 或 `item_tag`。 |
| `goal.parameters.count` | 正常调用使用整数 1～256，默认 1；单位是要再做的件数，不是最终主背包物品数或点击数。本步第一次启动时冻结主背包已有数作为起始数，目标为起始数 + `count`；同一步 `retry`、`recover`、暂停和重启都沿用该起始数，只有 `replace_goal` 才重新起算。配方按整批产出，最后数量可能高于目标。只数快捷栏和主背包，不计副手、护甲、鼠标或工作台格子。 |
| `goal.parameters.preferred_materials` | 物品 ID 字符串数组，必须带命名空间并存在于当前注册表；去重后最多 256 项。省略、`null`、`[]` 无偏好；数组内空白或 `null` 无效。它是贯穿中间配方的软偏好，不是指定配方 ID，也不是禁止其他材料。 |
| `goal.target` | 推荐省略。目录当前声明 `nearest`、`prior_result`，但本能力适配器没有按目标位置寻找指定工作台；不要靠它指定异地工作面，需先单独移动。兼容路径会在 `item_id` 缺失时把 `target.label` 当作物品 ID。 |
| `goal.preferences` / `goal.constraints` | 本能力没有自己的偏好对象字段或硬约束；材料偏好放在 `parameters.preferred_materials`。全局生命周期控制另见 [任务说明](tasks.md)，不要把坐标、槽位、点击脚本或配方编号放进自然语言以期生效。 |

`craft` 的数量校验目前与 `acquire_items` 不同：[AbilityAdapter.craft](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java) 先调用宽松 `integer`，再构造内部取物请求。省略、`null`、数组或对象会落到默认 1；原始数值先经 `getAsInt()` 再夹到 1～256，0 不表示“不做”，小数可能截断，数字字符串也可能被接收。无法转换的值可能到执行时才失败。这是待修的校验差异，不是建议调用格式；不能把“计划接受”解释为已严格确认数量。

## 合成入口为什么会进入取物任务

[AbilityAdapter.craft](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java) 把请求转成内部 `acquire_items`，固定 `allowed_sources=["inventory","craft"]` 并转交材料偏好。因此它与 `acquire_items` 共用数量判断、递归配方和工作台准备，不需要调用者先换能力才能摆随身台。

这里的 `inventory` 不只意味着静态数物品：支持的 Sophisticated Backpacks 随身背包可以被原生打开、取出现货。普通容器取货、AE 现货领取、采矿、烹饪、采收、狩猎和交易不会因缺料被追加到合成来源。容量故障后的整理是另一分支，可能把不需物品存入随身背包或可用随身 AE；当前该存入未按获取来源过滤，具体效果会进入 `inventory_maintenance`。

| 想看哪一步 | 打开文件与方法 |
| --- | --- |
| 公开参数怎样转成内部需求 | [AbilityAdapter.craft](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java)、[SemanticAcquireApi.newRecord](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/SemanticAcquireApi.java) |
| 已经够数为什么立即结束 | [SemanticAcquireCompanionTask.tickAcquisition](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/SemanticAcquireCompanionTask.java)、[PlayerInv.buildableCount](../../common/src/main/java/org/maiwithu/maicraft/core/PlayerInv.java) |
| 候选配方、真实材料分配和批数 | [CraftOps.plan / allocate / candidateFor](../../common/src/main/java/org/maiwithu/maicraft/core/tools/CraftOps.java)、[CraftPlanCost](../../common/src/main/java/org/maiwithu/maicraft/core/task/craft/CraftPlanCost.java) |
| 递归追哪种原料、何时换路线 | [AcquisitionRecipePlanner.materialPlan / chooseFrontier](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionRecipePlanner.java)、[RecipeMaterialPlan.estimate](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/RecipeMaterialPlan.java) |
| 已有工作台却报缺工作面 | [CraftingWorkstationCoordinator.inspect / next / validSite](../../common/src/main/java/org/maiwithu/maicraft/core/task/craft/CraftingWorkstationCoordinator.java)、父任务 `pushCraftingSurfacePrerequisite` |
| 放台、走到台旁和打开菜单 | [CraftCompanionTask.prepareSurface / prepareExistingStation / openStation](../../common/src/main/java/org/maiwithu/maicraft/core/task/craft/CraftCompanionTask.java) |
| 配方书与逐格摆料怎样分流 | [CraftingPlacementPlan.recipeBookUsable / create](../../common/src/main/java/org/maiwithu/maicraft/core/task/craft/CraftingPlacementPlan.java)、[CraftingGridPlacement](../../common/src/main/java/org/maiwithu/maicraft/core/task/craft/CraftingGridPlacement.java)、合成任务 `placeRecipe` |
| 为什么结果已显示却还不能结束 | 合成任务的 `takeResult / stowResult / returnCraftingGrid / closeMenu`；事务边界见 [DefaultMenuPort](../../common/src/main/java/org/maiwithu/maicraft/client/actor/DefaultMenuPort.java) |
| 台子是否应该拆、有没有真的收回 | 合成任务 `beginTemporaryStationRecovery / reclaimTemporaryStation / collectTemporaryStation / finishStationRecovery`；归属由工作台协调器记账 |

## 准备工作面，再逐批合成

1. **先看目标是否已经满足。** 当前主背包已够数量就结束；否则 `CraftOps` 扫描客户端已知、可读取的普通 `CraftingRecipe`，排除特殊配方和超出普通 3×3 的候选。所需批数为缺口除以每批产量向上取整。
2. **分配真实材料。** 一个槽位的材料不能同时满足多个配方格；宽标签与精确材料的重叠由容量分配处理。整机补料的 [CraftIngredientReservations](../../common/src/main/java/org/maiwithu/maicraft/core/task/craft/CraftIngredientReservations.java) 还会保留其他部件已备数量。材料够且工作面可准备的候选先执行；缺料才进入完整材料树。
3. **准备真正的网格。** 当前已打开且尺寸合适的合成网格可直接使用；否则 2×2 用背包，较大配方交给工作台协调器。当前不兼容的菜单先请求关闭并等待确认。
4. **核对光标和网格。** 不能抢走鼠标持物，也不能清掉不是本任务放入的材料。实际网格和菜单身份变化会停止或进入收尾，不能沿用旧槽号盲点。
5. **提交一批摆料。** 配方已解锁且原生配方书能用时，调用原生配方摆放；未解锁或合法带组件材料无法走配方书时，使用原生 `Ingredient` 匹配后逐格点击。不会修改背包数据强行凑配方。
6. **等待准确结果并入包。** 只有产物物品、组件和每批数量吻合才领取；菜单版本更新本身不能证明结果已同步。每次取出后确认光标、目标槽位及数量变化，归还本批剩料，再做下一批。一个空槽可容纳同类火把的两批输出，但其他物品或返还物仍要按实际容量计算。
7. **结束合成并处理临时台。** 归还自有网格内容、确认关闭菜单，再尝试拆回登记的临时工作台。父取物目标即使已够数量，也要等待已提交事务收尾。

### 工作台状态不是一个“有或没有”的布尔值

| 规划状态 | 玩家下一步 |
| --- | --- |
| `ready` | 发现可接近的已放置工作台，或当前网格可用；执行器仍核对真实交互射线与菜单。 |
| `preparable` | 可以摆出主背包中的工作台，或走近已发现的工作台；优先附近可用台，再考虑可摆的随身台，随后才走向远台。 |
| `searching` | 已加载工作台索引仍在分刻扫描，继续等待，不把当前预算用完当作不存在。 |
| `prerequisite` | 没有已放置或随身台，但找到可摆位置；允许按原来源先取得一张台，再回到同一配方。 |
| `unavailable` / `unsupported` | 前者是当前找不到可准备的工作面，后者是尺寸等不受支持；不是简单宣布原料短缺。 |

协调器识别普通 `CraftingTableBlock` 及兼容子类，但排除原版制箭台、锻造台。自定义模组工作面不因外观像桌子就自动被识别；实际打开的原生菜单还须验证。随身台只从可操作的主背包 36 格读取，不能把副手或容器里的台冒充已可取用的台。

摆台会查附近已加载、可替换、无液体、不占角色身体的格子；要求支撑面、上方净空和已有可站的放置方案，不为这一张台额外规划搭桥。这是当前选址边界：手里有台也可能因为找不到摆放点失败，此时回执需同时保留库存和具体原因。已持有一张台不会再要求“补到两张”；工作台或中间件后来到包时，父任务会在原生子任务结清后收起无用的更深材料需求。

临时台账以当前 `ClientLevel` 和实际方块位置记录，由本任务确认放置后才能登记；复用他人的已有台不会取得拆除许可。回收要求原方块仍匹配、主包有容量、原生破坏及掉落/入包证据。没能回收不改写已经完成的成品数量，分别返回 `crafting_table_recovered` 和原因。该台账是进程内弱引用记录，不是跨重启持久化资产归属。

## 回执怎样解释

公开 `craft` 返回的是取物父任务结果。先看最终 `state`、`goal_satisfied`、`requested_additional_count`、`baseline_count`、`net_gained_count`、`required_final_count`（起始数 + 件数）、`observed_final_count` 和 `missing`，再看 `attempts` 中来源为 `craft` 的 `child_data`；接单、配方选定或鼠标点击都不是库存完成证明。

| 子回执字段 | 说明 |
| --- | --- |
| `recipe`、`planned_batches`、`completed_batches`、`output_per_batch`、`crafted` | 配方身份与实际完成的批次、产量；`crafted` 是这一个合成子任务的新增产量；公开 `count` 是整步要再做的件数，整步净增看父回执的 `net_gained_count`。 |
| `crafting_placement_method`、`recipe_book_unlocked` | 实际采用 `recipe_book` 或 `native_grid_clicks`，不能只凭“已解锁”推断走了哪条路径。 |
| `crafting_grid_cleanup_verified`、`foreign_grid_preserved` | 自有材料是否归还、外来网格是否保留；强制结束可能另有 `crafting_grid_cleanup_unconfirmed_on_terminal`。 |
| `crafting_table_placed`、`crafting_table_recovery_attempted`、`crafting_table_recovered` | 放台、尝试回收和真实回收是三项独立事实；必要时还有位置及 `crafting_table_recovery_detail`。 |
| `failure_code`、`failure_detail`、`crafting_placement_evidence` | 缺料、工作面变化、界面占用、同步未知的具体证据，不应只读父层泛化错误。 |

`crafting_surface_missing` 在父规划层产生，早于配方书点击；`issues[].facts.crafting_surface` 包含工作面状态、细节及主背包工作台数量。`crafting_recipe_book_unconfirmed` 则表示实际摆料后没收到准确结果，应先查看网格和回执，不能重复点击来猜是否成功。

当前 `crafting_cursor_not_empty`、`crafting_grid_not_empty` 也使用 `NO_SPACE`，部分结果转移未确认同样归到这个类型；父任务可能包装为 `inventory_capacity_blocked`。所以“材料放不下”不一定等于缺第二个空槽。先检查子回执和当前鼠标、网格，再决定整理容量、修正参数或停止。

恢复使用总任务当前 `decision_id` 与实际列出的选择。`retry` 可修改 `details.parameters`；要开放采集来源，需用 `recover` / `replace_goal` 提供获准的 `acquire_items` 目标，不能给 `craft` 增加未声明的 `allowed_sources`。恢复建议本身不执行操作，也不撤销之前已合成的物品。

## 暂停、取消和更换世界

同进程暂停保留父需求与合成阶段，释放身体后再由总任务 `resume` 推进；有待答决策时不能只用 `resume` 跳过。原生服务器事务可能在暂停期间继续变化，恢复时必须核对现场，不能承诺停在某个点击之前。

取消、超时或身体失效会经过父子任务清理，停止导航、挖掘和索引，安排菜单边界关闭；自有网格尚未确认清空时保留未知标志。已经消耗的原料、入包的成品和已放置的台不会回滚。当前合成 `cleanup` 对工作面子任务只调用 `stop`，未调用其 `result` 完整收取清理结果，不能宣称所有嵌套放置事务都已结清。

死亡、玩家替换、断线或换世界由 [IntentRuntime](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) 保存语义检查点，恢复未完成任务时先暂停；[IntentTask.stop](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java) 保留中断子回执。重启保留目标、步骤和历史，不恢复旧 Java 需求栈、菜单对象或临时台所有权。重新观察后继续，不能把重生或重新连接说成已找回材料。

## 已知边界与已有验证入口

当前配方尺寸判断使用 `ShapedRecipe` 宽高或非空材料格数，没有通用调用所有自定义配方的尺寸语义。读取失败的模组配方会被跳过；特殊配方、机器加工和自定义桌面不因客户端看得到而承诺支持。材料树有预算且不保证全局最优；材料偏好不能替代现场库存、天然来源及真实菜单确认。

`CraftOps` 的诊断目前最多展示 8 条候选、每格 64 个可接受材料，并附总数/截断标志；内部恢复候选不受这个展示上限影响。需要审查证据完整性时应关注此处，不把较短报告误认为注册表只有这些配方。

- [CraftAbilityWorkstationTest](../../common/src/test/java/org/maiwithu/maicraft/intent/CraftAbilityWorkstationTest.java)、[CraftingWorkstationPlanningTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/craft/CraftingWorkstationPlanningTest.java)：两个公开入口、随身台、制箭台/锻造台排除、无支撑摆台。
- [CraftingTaskTagTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/CraftingTaskTagTest.java)：真实网格操作、标签材料、命名材料、两批火把、仅一个空槽、批间缺料及配方书超时。
- [CraftingRecipeBookBoundaryTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/CraftingRecipeBookBoundaryTest.java)、[CraftingResultSynchronizationTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/CraftingResultSynchronizationTest.java)：配方书资格和分包同步时的准确结果确认。
- [CraftSurfaceFailureTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/craft/CraftSurfaceFailureTest.java)、[AcquisitionCapacityFailureTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionCapacityFailureTest.java)：放台子任务失败、容量回退及原始证据。

这些已有场景主要汇集于 `:common:craftingRegression`；逐格事务还可从 `:common:guiRegression` 和 [验证任务注册](../../common/build.gradle) 查入口。源码、JSON 和链接静态检查不代表回归已经执行，离线用例也不能代替当前整合包的原生交互验收。
