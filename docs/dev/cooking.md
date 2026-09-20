# 从“还缺铁锭”到“这一炉已经收好”

`cook` 要完成的是主背包最终数量。例如已经有 256 个铁锭，再要求 300 个，还需要烧 44 个。目标数量、一次装多少原料、一次消耗多少燃料，是三件不同的事。

当前支持普通熔炉、高炉和烟熏炉。能识别营火配方，但营火偏好会明确返回尚不支持，不会偷偷改用熔炉。这一页记录已贯通的主执行器、数量、估价和菜单收尾；整个烹饪能力仍有待继续审阅的分支，见文末。

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

必须给 `item_id`。`count` 默认 1，接受 1～2304 的整数；小数、数字字符串、超限数字和显式 `null` 不会被改成另一个数量。这个上限与取物一致，实际能装多少还取决于背包容量和物品堆叠规则。

`allowed_fuels` 限定能烧哪些燃料；省略或空列表使用普通燃料集合。`allowed_sources` 决定原料、燃料和工作站从哪里取得，不能填写 `cook` 再递归开炉。默认伤害许可仍为关闭。

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

递归估价最多看四层普通合成，并阻止同一路线绕回自身。附近掉落、普通仓库和交易被允许时，会保留实际查找它们的机会；“没有 AE2”不能直接等于“没有仓库来源”。这些是排序估计，物品是否存在、可否取用仍由真正的取物子任务确认。

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

## 已覆盖与继续审阅的范围

当前离线回归覆盖：

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
