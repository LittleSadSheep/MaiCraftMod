# 从“还缺铁锭”到“这一炉已经收好”

`cook` 要完成的是主背包最终数量。例如已经有 256 个铁锭，再要求 300 个，还需要烧 44 个。目标数量、一次装多少原料、一次消耗多少燃料，是三件不同的事。

公开能力是 `maicraft:cook`。当前执行普通熔炉、高炉和烟熏炉；配方来自客户端实际收到的配方表。能识别营火配方，但需要加工时指定营火会明确失败，不会偷偷改用熔炉。模组物品只有能通过这些配方、燃料和菜单路径时才适用，不能据此承诺支持任意模组机器。

## 怎样提交一份加工要求

下面是 MCP `plan` 的完整参数。例子把目标设为主背包至少有 8 个铁锭，原料、煤和设备只允许使用随身现货；附近已有的可用炉子仍可使用。计划不会烧东西，执行实际返回的 `plan_id` 后才开始。

```json
{
  "goal": {
    "ability": "maicraft:cook",
    "outcome": "用随身原料和煤补足八个铁锭",
    "target": { "kind": "nearest" },
    "parameters": {
      "item_id": "minecraft:iron_ingot",
      "count": 8,
      "recipe_preference": "smelting",
      "allowed_fuels": ["minecraft:coal"],
      "allowed_sources": ["inventory"],
      "allow_harm": false,
      "protected_labels": []
    }
  }
}
```

| 字段位置 | 类型、默认和含义 |
| --- | --- |
| `goal.parameters.item_id` | 必填非空物品 ID 字符串，表示成品；执行时须为已安装的非空气物品。省略、`null`、数字和空白拒绝 |
| `goal.parameters.count` | 主背包最终总数，整数 1～2304；省略或 `null` 为 1。0、负数、小数、数字字符串、布尔值和超限数拒绝 |
| `goal.parameters.recipe_preference` | 字符串，省略为 `auto`；`null`、空白和非字符串拒绝。正式取值为 `auto`、`fastest`、`preserve_rare`、`smelting`、`blasting`、`smoking`、`campfire`，大小写与首尾空白会整理 |
| `goal.parameters.allowed_fuels` | 物品 ID 字符串数组，去重后最多 64 种；省略、`null`、`[]` 都使用默认普通燃料集合，不表示禁止燃烧。非空清单只允许列出的燃料，执行时检查已安装且原生炉子认作燃料 |
| `goal.parameters.allowed_sources` | 来源字符串数组，省略、`null`、`[]` 使用下述默认集合；显式清单也总会加入 `inventory`。不是执行顺序 |
| `goal.parameters.allow_harm` | 布尔值，省略、`null`、`false` 都不授权伤害生物；`true` 允许获准来源中的伤害行为，不会自动增加 `hunt` 来源 |
| `goal.parameters.protected_labels` | 已记住的地点名数组，最多 64 个不同名字；省略或 `[]` 不新增保护。公开目标拒绝 `null`、空白名字和非字符串；执行时未知名字会失败，其他维度的地标不转换成当前世界的保护格 |
| `goal.target` | 省略、`null` 或只含 `kind: nearest` 的对象；不能夹带 `label`、`position`、`relation`。去另一处开炉先执行旅行 |

来源名称为 `inventory`、`nearby`、`wireless`、`storage`、`harvest`、`craft`、`cook`、`mine`、`trade`、`hunt`。默认包含除 `trade` 外的这些来源，但 `hunt` 仍须 `allow_harm=true` 才能伤害生物。只准使用随身物品时写 `["inventory"]`，不能写空数组。数组成员必须是非空字符串，成员为 `null` 无效。取材细节见[取物文档](acquiring.md)。

默认燃料为原生燃料表中的煤、木炭、木棍、竹子、干海带块，以及木板和原木标签中的物品。`preserve_rare` 只改变固定燃料优先表，没有读取附魔、名字或稀有度来替玩家判断价值。兼容别名包括 `furnace`、`blast_furnace`、`blast-furnace`、`smoker`、`preserve-rare`、`campfire_cooking`；新请求使用正式取值。

下面这份完整计划展示营火边界：参数合法不等于设备已经可执行。库存尚未满足时，将返回营火执行不支持或没有对应配方；不会转去其他设备。

```json
{
  "goal": {
    "ability": "maicraft:cook",
    "outcome": "尝试用营火加工一份熟牛肉",
    "parameters": {
      "item_id": "minecraft:cooked_beef",
      "recipe_preference": "campfire",
      "allowed_sources": ["inventory"]
    }
  }
}
```

## 代码现在怎样分工

| 类 | 负责回答的问题 |
| --- | --- |
| [CookAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/CookAbilityAdapter.java) | 这个公开目标是否说清楚了要求，应该建立哪张任务单？ |
| [SemanticCookApi](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/SemanticCookApi.java) | 目标物品、数量、燃料和来源参数是否合法？ |
| [CookingRecipe](../../common/src/main/java/org/maiwithu/maicraft/core/task/cook/CookingRecipe.java) | 本次选了哪个配方、设备和原料，每份产出多少？ |
| [CookingDevice](../../common/src/main/java/org/maiwithu/maicraft/core/task/cook/CookingDevice.java) | 哪种配方对应哪台炉子、哪种菜单和怎样的燃烧时长？ |
| [CookingRecipePlanner](../../common/src/main/java/org/maiwithu/maicraft/core/task/cook/CookingRecipePlanner.java) | 哪份准备方案比较合适，需要哪些现货或前置合成？ |
| [CookingBatch](../../common/src/main/java/org/maiwithu/maicraft/core/task/cook/CookingBatch.java) | 这一炉能装几份原料，要备几份燃料？ |
| [CookingBatchLedger](../../common/src/main/java/org/maiwithu/maicraft/core/task/cook/CookingBatchLedger.java) | 实际装入、退回和取走多少，本炉数量是否守恒？ |
| [SemanticCookCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/cook/SemanticCookCompanionTask.java) | 角色现在该备料、开炉、等待，还是核对并收尾？ |

公开能力直接建立类型明确的任务单。旧内部 `cook` 工具也经过同一个参数入口，不再各自转换数量。它和取物入口共用 [SemanticParameters](../../common/src/main/java/org/maiwithu/maicraft/core/tools/SemanticParameters.java) 的严格读取规则。

## 开始之前检查什么

必须给 `item_id`。数量上限与取物一致，实际能装多少还取决于背包容量和物品堆叠规则；主背包含快捷栏，不包含副手和穿戴栏。已有数量够了会直接收尾，不要求额外烧制一炉。

`allowed_sources` 同时约束原料、燃料和工作站的获取。允许 `cook` 时可以先烧中间材料再烧成品；[ProductionLineage](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/ProductionLineage.java) 携带祖先成品并限制最多八层，防止为了做原料又递归回同一成品。燃料清单、保护要求和伤害许可继续传给子任务。

从当前位置开工，不填 `target` 或只填 `{"kind":"nearest"}` 即可。要在另一处加工，用顺序目标先移动，再烹饪；设备选择用 `recipe_preference`。以前会被忽略的地点信息、被当成物品名的目标标签，现在会明确拒绝。

旧等待、取物和烹饪记录仍保留原参数，供查询、取消与修订。恢复历史不等于允许按旧规则继续截断数量；再次执行还要经过当前校验。

## 一次正常加工的顺序

```text
挑配方与燃料 → 备原料、燃料和设备 → 走近炉子
  → 打开并确认菜单 → 检查炉子是否可用
  → 装原料 → 补燃料 → 看到真实加工进度
  → 关界面等待 → 回到原位置开炉
  → 核对这批账 → 收成品并确认背包增量
  → 退回已核实的剩料 → 关好菜单
  → 数量还没够就准备下一炉
```

第一次使用要求炉内原料、燃料、结果和活动进度都为空。若最近的炉子被占用，先关闭自己打开的菜单，再尝试另一台；同一位置不会反复试，候选尝试有上限。到场导航固定指向所选炉子，不能按类型搜索又回到已排除的忙炉。已经投入原料的炉次始终留在原炉处理。

开始装料的请求只是一个动作安排；原生搬运确认后，才把实装量记到本批账里。保护标签也在烹饪本体检查，失效的名字不会被当作没有保护。地标锚点由共享 [LandmarkProtection](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/LandmarkProtection.java) 处理，外层已提供的完整区域保护仍保留。

等待时会关闭界面，记住炉子位置和返回站位。到预计完成时刻，或已加载的炉子提前熄火时，再去检查。关界面期间不会通过后台直接读取服务器库存。

## 配方和燃料怎样比较

规划器先读客户端已经收到的配方，找能产出目标物品的路线。`fastest` 先比较单份加工时间，再比较准备成本；其他常用偏好先看准备成本。它没有承诺已经算出包括走路、备料和每次开关菜单在内的全局最短时间。

每个候选方案有一份临时库存账：设备准备、原料、燃料共用它。只有一根原木时，这根木头不能既算作烧木炭的原料，又被当成免费燃料。同组可替代材料可以合计；一根木板合成四根木棍后，没用完的木棍可以继续分配。

递归估价最多看四层获准的合成或烧炼路线，并阻止同一路线绕回自身。附近掉落、普通仓库和交易被允许时，会保留实际查找它们的机会；“没有 AE2”不能直接等于“没有仓库来源”。这些是排序估计，物品是否存在、可否取用仍由真正的取物子任务确认。

准备失败且没有实际效果时，可以排除失败候选再试另一份有限方案。已经采过材料、消耗过物品或放过设备后，失败会保留效果信息并停止当前加工方案。子任务即使报告了库存已到达，只要还带有不确定效果，也不能继续装炉。

## 一炉的大小怎样算

`CookingBatch` 同时考虑原料堆叠上限、产物堆叠上限、每份产量、燃料堆叠上限和加工时间。例如一次产三件、结果最多叠 64 件时，这一炉最多处理 21 份；不能叠放的燃料也不能当成一格能放 64 份。

燃料时长来自相应原生设备的计算方法。普通炉一块煤通常燃烧 1600 刻，高炉和烟熏炉相应为 800 刻；加工速度也不同，所以处理十六份常规原料都需要两块煤。估算新燃料、计算本批数量、折算炉内已有燃料，使用同一设备规则。

总目标超过 256 件也不改变它的含义。已有 100 件、目标 300 件时，普通一件产出的路线可以继续分成 64、64、64、8 四炉。

## 怎样避免碰错菜单

烹饪会保存本次打开的菜单对象。每次继续装料、取物或关闭时，都要确认还是它；只有同样的菜单类型或同一个编号不够，因为编号可以复用。

[CloseMenuTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/menu/CloseMenuTaskRecord.java) 支持绑定待关闭的菜单。菜单换成别人的界面时，旧关闭任务不会操作它；鼠标还拿着物品时，也会保留界面，避免用关闭来处理无法确认归属的物品。

停止烹饪时，下层搬运留下的鼠标物品也必须保留。上层不能在下层已经决定不关菜单之后，又顺手把它关掉。

## 收货要核对哪本账

`CookingBatchLedger` 计算：确认装入量减去已退回量，再减去炉内剩余量，才是已经加工的原料数。按配方产量计算出的成品，应等于结果槽里的数量加上已经取走的数量。槽位包分开到达时先只读等待；超过同步窗口仍对不上，才停止认领，不把外来物品算给自己。

取结果之后，用低层搬运回执中的实际数量核对主背包。例如计划点击时看见八件、随后实际取回十二件，账上就应是十二件。背包只装下三件时保留这三件的确认事实，停止并报告余量仍在炉内，不能把整堆算作已收。

退料期间仍可能烧好新产物：十六份原料中退回十四份、烧好两份，是正常守恒。退料后重新检查整炉账，再收这两件产物。当前仍把剩余燃料留在炉里，避免在没有完整燃料归属账时猜着取回。

如果上层取物目标被另一种替代物品满足，会调用 `requestSatisfiedSettlement`。尚未提交的装料不再点击；分堆已拿起一小堆时，先结清手中这堆，不再拿下一堆。烹饪按实际装入量记账，只收尾原炉次，不为追赶旧数量再开新炉。结果区分“上层目标满足而结束收尾”和“本烹饪数量确实达到”。父取物任务也保留所有来源的未结与不确定结果，不会仅因库存达标就把它们改报成功。

取消不会让服务器里的炉子停止燃烧。结果用 `batch_outstanding`、`outcome_uncertain` 和已确认装入、取回的数量说明未结事项；无法确认时不会硬填 `effects_started=false`。

## 结果、暂停和恢复怎么读

`execute` 接单后先跟随 `next_attention`；需要读完整旧证据时沿 `task(get)` 返回的路径读取。同一次提交丢回复时沿用 `request_key`，不要另起一炉来试探是否成功。

| 回执 | 含义 |
| --- | --- |
| `required_final_count`、`initial_count`、`observed_final_count`、`goal_satisfied` | 请求总数、开工时数量、结算时数量、是否够数；够数不自动消除别的未结效果 |
| `recipe_id`、`device`、`input_item_id`、`recipe_output_count`、`fuel_item_id` | 本次实际选用的路线；只在已经选定时出现 |
| `station_placed`、`effects_started` | 是否放了设备、是否已有确认效果；后者缺失表示不能断言“没有发生”，不是 `false` |
| `batch_outstanding` | 炉次还未结清；此时再读 `owned_input_loaded`、`owned_input_returned`、`owned_output_taken` |
| `outcome_uncertain` | 子操作或炉内归属有未知事项；先核对同一现场，不能据此直接再投一批 |
| `stopped_because_parent_satisfied` | 取物父目标已经由别的来源满足，本炉只做收尾；不代表原烹饪数量也达到 |
| `prerequisite_failure`、`preparation_plan_failures`、`workstation_attempts` | 备料失败事实与已尝试方案，用于解释为什么没有继续 |
| `decision.reason_code`、`decision.recovery_options` | 执行器保存的原因和建议。当前失败由语义父任务直接结算；即使内部 `decision.required=true`，也不能在没有实际 `decision_id` 时调用回答入口 |

普通暂停保留同一执行器中的炉位、账本和子任务，释放身体输出；服务端炉子可以继续燃烧。恢复后重新检查菜单对象和炉内守恒。永久取消会停子任务并尝试结束自己打开的菜单，保留放下的设备与实际产物，不能把取消解释为没有消耗。

死亡先走公共运行时的复活授权或待答决定；复活、断线、换世界和进程重启都不能沿用旧玩家上的菜单对象。检查点保留父目标与已完成步骤，但**当前炉次的炉位、投入账和菜单身份没有完整持久化**。未完成记录恢复后先暂停，继续会重建当前烹饪步骤；不能宣称能无损接着收原炉，更不能仅凭新背包数量判断旧炉里没有东西。先核对已知炉位和未结回执，再决定后续加工。

找某一段实现时，打开 `SemanticCookCompanionTask`：备料看 `prepare / acquire`，选站和开门看 `approachStation / waitMenu`，投料点火看 `loadInput / loadFuel / confirmStart`，收货看 `reconcileBatch / verifyOutputTake`，取消与回执看 `cleanup / resultData`。

## 已覆盖与继续审阅的范围

以下是已有离线回归入口及覆盖范围；本轮未运行回归，也未启动游戏：

- `CookGoalTest`、`GoalCheckpointCompatibilityTest`：公开参数到真实任务单、旧目标的保存恢复和取消。
- `CookingFuelTest`、`CookingBatchTest`：三种炉子的燃料时长、真实堆叠上限和多件产量。
- `CookingQuantityTest`：取物父任务传入 300 件，以及多炉边界后的下一批计算。
- `CookingStockBudgetTest`：原料与燃料共享库存、递归消耗、合成余料、混合替代材料与允许来源。
- `CookingMenuCloseTest`、`CookingMenuOwnershipTest`：同编号菜单替换、关闭绑定与鼠标物品保留。
- `CookingSettlementTest`、`CookingPreparationEffectsTest`：上层提前满足、取消未结炉次、准备阶段的实际效果与不确定性。
- `CookingOutputReceiptTest`、`CookingSynchronizationTest`：按实际回执收货退料、分批槽位同步与持久不守恒的拒绝。
- `CookingStationSelectionTest`、`CookingProtectionTest`：忙炉切换、固定目标和内部调用的保护范围。

其中数量和收尾测试会在明确注明的控制器边界提供已确认的库存变化；它们不冒充真实服务端的炉子加工与鼠标点击验收。

后续仍要继续处理并验证：开炉原生回执与来源的首次绑定、暂停时的子任务时限、燃料余量和容器剩余物、特殊组件与贵重输入，以及断线重启后未结炉次的定位与持久证据。导航算法的全链路和整合包实机场景也不能由这些离线测试替代。

可以从 [CookGoalTest](../../common/src/test/java/org/maiwithu/maicraft/intent/CookGoalTest.java)、[CookingPrerequisiteChainTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/cook/CookingPrerequisiteChainTest.java)、[CookingSettlementTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/cook/CookingSettlementTest.java)、[CookingOutputReceiptTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/cook/CookingOutputReceiptTest.java) 开始定位现有验证。它们分别对应公开请求、前置烧炼、未结收尾和实际收货量。

还有两项呈现限制需要继续处理：备料失败历史目前最多记 32 条；失败取材的嵌套数据只转发固定字段，可能省掉更细的实际材料或未完成效果。这里说明现状，不把它们当作完整交付事实的保证。
