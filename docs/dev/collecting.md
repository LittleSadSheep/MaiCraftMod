# 把地上的物品捡进背包

玩家挖掉一块黑曜石后，产物可能落在坑底、浮在水中，或者仍有原版拾取冷却。`maicraft:collect_items` 负责选择掉落实体、走到能接触它的位置、等待服务器拾取和背包同步。模型决定捡哪些物品、是否允许为靠近而开路，执行器决定路线和最后的站位。

它适合收取现场已有的掉落物，不表示“背包最终达到某个数量”。需要最终库存数量、合成或仓库来源时用 [获取物品](acquiring.md)；采掘、攻击等已有自己的产物收尾，应先看原任务回执是否确有未收取部分。不要仅因一次 Attention 等待超时就重复提交拾取。

## 从请求到执行

公开调用使用 `plan` 或 `execute`，能力名放在 `goal.ability`，下面四个专属字段全部放在 `goal.parameters`。`outcome` 必填，用于描述目的，不能代替筛选、数量或范围参数。`target` 可省略或为 `null`，显式目标只使用 `{"kind":"current_place"}`；这不是固定场地锚点，不支持坐标、地标或 `nearest` 目标。需要异地拾取时先移动。

`parameters` 使用对象，全部默认值可以写成 `{}`；字段自身的 `null` 不等于省略。本能力没有专属偏好或硬约束，普通请求让 `preferences` 保持 `{}`、`constraints` 保持 `[]`，或省略它们。不要添加 `count`、`item_id`、`item_tags`、`protected_labels`、槽位或点击脚本。外层运行时另有死亡恢复授权键，见[任务生命周期](tasks.md)；它们不改变本能力的拾取确认规则。

| 完整字段路径 | 类型、默认和合法输入 | 对游戏行为的影响 |
| --- | --- | --- |
| `goal.parameters.drop_ref` | 可选字符串；从 `perceive(view="surroundings", sections=["nearby_entities"])` 的实际掉落观察中原样复制。格式为维度资源 ID、`|` 和规范 UUID | 只追踪那一堆，接单时核对当前维度。省略表示不按身份限定；`null`、空串、格式错误都拒绝。引用不是数字实体 ID，也不是坐标 |
| `goal.parameters.item_ids` | 可选非空字符串数组；每项必须能解析为当前注册表中的非空气物品，推荐显式命名空间；重复项合并 | 仅筛注册物品类型，不筛附魔、损伤等组件。省略表示不限类型；`null`、`[]`、非字符串、未知物品或 `air` 拒绝。标签不支持 |
| `goal.parameters.radius` | 可选 JSON 数值，必须为整数值且在 `1..48` 内，单位方块，省略为 `16` | 每轮扫描把角色当前身体盒向 x/y/z 各扩展这个距离。`0`、负数、超过 48、非整数值、数字字符串、`null` 和布尔值拒绝；数学上为整数的 `16.0` 可解析为 16 |
| `goal.parameters.may_alter_terrain` | 可选 JSON 布尔值；省略与 `false` 都不授权开路，`true` 授权 | 允许正常导航按白名单和保护条件挖路、搭桥或垫高。`null`、`0`、`1`、`"true"` 均拒绝；许可不会把尚未挖开的站位当成已到达 |

四个字段都不是必填。`drop_ref` 和 `item_ids` 可以同时提供，取交集，不是互斥模式；类型不匹配会让点名物品保持未确认，不会改捡另一堆。同样，刚启动时点名堆必须已加载且在扫描框内。

`radius` 不是球形距离，也不是固定施工边界。扫描中心会随角色移动；一旦选中实体，`targetGoal()` 持续读取它的当前位置，没有再次用 `radius` 截断追踪距离。掉落受流水影响漂出原扫描框，不会因此自动换目标。

## 可提交的请求

以下是完整 `plan` 参数：收取附近的黑曜石，沿现有路线靠近。

```json
{
  "goal": {
    "ability": "maicraft:collect_items",
    "outcome": "拾取附近地上的黑曜石",
    "target": {"kind": "current_place"},
    "parameters": {
      "item_ids": ["minecraft:obsidian"],
      "radius": 16,
      "may_alter_terrain": false
    },
    "preferences": {},
    "constraints": []
  }
}
```

只在用户已允许开路时，将许可传到同一拾取目标。例如矿洞洞口少了角色头部空间，可以提交以下完整 `plan` 参数，而不让模型逐块指定清障动作。

```json
{
  "goal": {
    "ability": "maicraft:collect_items",
    "outcome": "允许为靠近开路，收取附近的粗铁掉落",
    "parameters": {
      "item_ids": ["minecraft:raw_iron"],
      "radius": 16,
      "may_alter_terrain": true
    }
  }
}
```

扫取全部类型可以提交 `{"goal":{"ability":"maicraft:collect_items","outcome":"拾取附近地面物品","parameters":{}}}`。这允许范围扫空后以零收取结束，不是“保证至少拿到一件”。

精确引用必须来自当前观察，文档不提供虚构或过期 UUID。宿主已有真实观察对象 `observedDrop` 时，可由它生成完整请求；这段代码只构造 JSON，不调用游戏：

```javascript
const request = {
  goal: {
    ability: "maicraft:collect_items",
    outcome: "捡起刚观察到的这一堆物品",
    target: {kind: "current_place"},
    parameters: {
      drop_ref: observedDrop.drop_ref,
      item_ids: [observedDrop.item_id],
      radius: 16,
      may_alter_terrain: false
    },
    preferences: {},
    constraints: []
  }
};
JSON.stringify(request, null, 2);
```

已有观察够用时直接复用，不为填写示例再勘测一次。计划成功只说明请求可规划；用返回的 `plan_id` 执行后，沿 `next_attention` 等待。`accepted`、导航到格和物品实体消失都不是最终拾取成功。

## 想看哪一步，就打开哪里

| 想看哪一步 | 文件和入口 | 核对重点 |
| --- | --- | --- |
| 模型发现用途和字段 | [SemanticAbilityCatalog 的 COLLECT 分支](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java) | 默认值、精确/范围收取差异、结果解释 |
| 请求接单前校验 | [SemanticGoalContract.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java)、[CollectItemsRequest.parse / task](../../common/src/main/java/org/maiwithu/maicraft/core/task/collect/CollectItemsRequest.java) | 参数白名单、显式 null、合法类型、维度校验 |
| 从能力接到原生任务 | [GeneralAbilityAdapter.adapt 的 COLLECT 分支](../../common/src/main/java/org/maiwithu/maicraft/intent/GeneralAbilityAdapter.java)、[CollectItemsTool.onGameCall](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/CollectItemsTool.java) | 公开与内部入口共用同一请求解析，不另开旅行任务 |
| 固定筛选和开路许可 | [CollectItemsTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/collect/CollectItemsTaskRecord.java) | `filter`、UUID 集合、维度与 `mayAlterTerrain` 分别保存 |
| 找下一堆、跟随和结算 | [CollectItemsCompanionTask.tickScan / tickApproach](../../common/src/main/java/org/maiwithu/maicraft/core/task/collect/CollectItemsCompanionTask.java) | 空扫描、库存满、冷却、同步、合堆及失败分支 |
| 岸边或坑边能否够到 | [CollectItemsApproach.goal / preparableContact / safeNudge](../../common/src/main/java/org/maiwithu/maicraft/core/task/collect/CollectItemsApproach.java) | 身体接触盒、脚底和头顶、授权清障候选、最后短走 |
| 暂时无路是否继续 | [PickupNavigationRetry.afterFailure / waiting](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/PickupNavigationRetry.java) | 同一 UUID 的有限重寻，不刷新总窗口 |
| 物品到底有没有入包 | [NativePickupReceipt.poll](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/NativePickupReceipt.java)、[ItemEntityReceipts.taking / pickedUp](../../common/src/main/java/org/maiwithu/maicraft/client/actor/ItemEntityReceipts.java) | 同组件库存增量与本人 UUID 拾取包是两种证据 |
| 观察、组件和选择引用 | [DroppedItemObservation.describe / refresh](../../common/src/main/java/org/maiwithu/maicraft/core/scan/DroppedItemObservation.java) | 维度、位置、数量、组件、观察时刻和拾取冷却 |
| 收尾保留哪些副作用 | [CollectItemsCompanionTask.resultData / stopNav](../../common/src/main/java/org/maiwithu/maicraft/core/task/collect/CollectItemsCompanionTask.java)、[TerrainBill.snapshot](../../common/src/main/java/org/maiwithu/maicraft/core/pathing/execute/TerrainBill.java) | 已收物品、未确认引用、已经挖放的完整位置账本 |
| 暂停、取消和恢复 | [IntentTask.stop](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)、[TaskSlot](../../common/src/main/java/org/maiwithu/maicraft/task/TaskSlot.java)、[IntentTaskRecord.restored](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTaskRecord.java) | 保留活子任务与重新创建子任务不是同一件事 |

## 游戏中实际怎样推进

1. **建立观察基线。** 公开请求给出 1,200 游戏刻的初始期限，通常按 20 TPS 约一分钟。启动执行器时记录本人拾取事件游标，清空本任务的现场观察和跳过名单。实际入包进展、导航进展可续时，不应把一分钟解释为不可延长的墙钟总时长。
2. **扫描候选。** `nearestItem()` 枚举当前扫描框内存活、非空的物品实体，依次应用 UUID 和类型筛选，记录堆量，按距离选择未跳过的最近一堆。不因物品属于别人或仍有冷却而提前排除。点名目标不在框内、已经消失或类型不符时，返回未确认；不改捡其他物品。
3. **选择接触站位。** 角色可以站在岸边或坑边接触物品，不必占据它所在的格子。候选检查脚底支撑、身体碰撞、世界边界、显式保护和物理障碍。`may_alter_terrain=true` 还允许提交需要补挖身体空间的候选，实际清障仍交给导航的原生执行与确认。
4. **按许可走过去。** 点名 UUID 的任务使用地面导航；未限定 UUID 的范围收取沿用自动交通选择。两者都继承开路许可；`false` 不承诺绝不移动，只是不授权路线挖放。不能把短走的浅水判断推广为已经支持任意深水或游泳收取。
5. **接触后等待。** 进入原版扩展身体盒后先停移动。物品仍有冷却就继续等；冷却结束而没有可接纳槽位时返回 `no_space`。有容量且持续接触约 20 游戏刻仍未被接纳，则记为原生拾取未接受，不把接近当成成功。
6. **同步和归因。** 每刻先看实体和背包，再处理导航。实体消失后给背包包约 20 游戏刻同步窗口。普通范围收取沿用“实体消失 + 同物品同组件库存增量”的组合证据；公开 `drop_ref` 还要求自任务启动以来服务器明确发给本人的同 UUID 拾取数量，必要时再等一个 20 刻窗口。点名模式的已确认数量取两种证据的较小值。
7. **继续或收场。** 一堆确认后累计入包数量，回到扫描。范围模式没有剩余候选且没有待报告失败可成功，包括零件；点名模式必须完成所选身份。最终先停止导航，再形成回执，已发生的拾取与开路效果不回滚。

短暂无路只对 `no_path`、`terrain_blocked` 重寻：同一 UUID 从首次失败起最多留 40 游戏刻，每次至少间隔 10 刻；导航本身可能还需要额外计算或执行时间。窗口内仍先处理接触和入包证据，不空等到下一次寻路。未知效果、背包满等问题不能借此自动重发动作。

原版合堆使旧实体消失时，执行器只在已观察的同组件幸存堆增长足够、且其 UUID 仍获许可时接续追踪。公开单一 `drop_ref` 不会自动扩展到另一个 UUID；被别的堆吸收后可能诚实报告未确认，需要模型依据新观察重新选择。

## 回执怎样读

下表是具体拾取任务的 `result.data`。公开总任务会把它纳入当前终态或保留的步骤/尝试结果；默认查询若给出 `detail_path`，使用相同 `task_id` 和该路径读取，不以重新 `execute` 恢复输出。外层还可能补充 `failure_type`、`outcome_uncertain` 或恢复决策；并非每次失败都会有待答问题。

| 字段 | 含义与边界 |
| --- | --- |
| `label`、`radius` | 本次选择说明和扫描距离，不是物品来源证明或固定场地区域 |
| `collected`、`collected_items` | 本任务已经结算的数量，以及按注册物品 ID 聚合的数量；不是完整背包快照，也不保证列出所有顺带吸入物品 |
| `pickup_navigation.may_alter_terrain` | 本次拾取带入的开路许可 |
| `pickup_navigation.confirmed_terrain_changes` | `broken`、`placed` 分别按方块 ID 保存坐标数组；保存已确认挖放，不是未来路线计划。取消、失败时同样保留 |
| `unreachable_drop_stacks` | 已耗尽内部重寻、因导航失败跳过的堆数，不涵盖所有可能的几何失败 |
| `disappeared_without_inventory_receipt` | 跟踪实体消失但未满足入包确认的堆数；点名模式缺少本人原生拾取包也计入此类，不能仅凭字段名推断一定缺背包包 |
| `pickup_rejected_after_contact` | 清除冷却、有容量且持续接触后仍未确认接纳的堆数 |
| `last_uncollected_detail` | 最近一次未收取原因，不是逐堆失败明细的完整列表 |
| `drop_collection` | 仅在目标 UUID 集合非空时出现；含 `requested_drop_refs`、`collected_drop_refs`、`unconfirmed_drop_refs` 和 `observations` |
| `drop_collection.observations` | 已观察目标的最近位置、剩余数量、时刻、冷却和组件事实；不存在观察不等于物品数量为零，也不说明它已被销毁 |

开路可能消耗搭桥材料或改变障碍；拾取可能顺带吸入筛选外物品。能力没有打开背包取货、清空背包、直接删除掉落实体或修改库存的步骤。满包时应根据真实回执处理容量；`may_alter_terrain=true` 不保证材料充足、原生权限允许或最终一定可达。

## 暂停、死亡、换世界和恢复

- **暂停与抢占：** `task(action="pause")` 记录暂停，身体调度器实际释放输入。临时抢占通知子任务停止输出但保留它的 SCAN/APPROACH 状态和库存基线；正常 `resume` 不重新调用拾取器的 `onStart()`。主任务截止时间会冻结，但这里的短期游戏时钟窗口不全部扣除暂停时间，见下节。
- **取消、替换和超时：** 外层要求子任务结算并清理；点名模式会在结算时补记可确认的部分入包，未整堆完成的引用仍放在 `unconfirmed_drop_refs`。已经挖放的路线与已收物品留在世界和背包中。
- **死亡：** 拾取器检测死亡返回取消，外层可能更早处理身体丢失。复活、旁观和死亡现场回收由 [GameplayAttentionMonitor](../../common/src/main/java/org/maiwithu/maicraft/client/runtime/GameplayAttentionMonitor.java) 的实际可用动作、授权和决策处理。死亡或复活均不证明原掉落已回收。
- **维度或身体变化：** 点名引用在创建时和运行中核对维度；本人拾取事件会在换玩家、客户端世界或时间回退时清空。不要把同维度字符串当成跨存档身份。外层 [IntentRuntime.tickPersistence](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) 绑定当前存档/服务器，旧原生路线和菜单不能跨世界复用。
- **重启恢复：** 持久化保留语义目标、已经记录的结果和尝试；未完成记录以 `paused_restored` 等待明确恢复。旧 `CollectItemsCompanionTask` 的活对象、实体基线和拾取事件缓存不被序列化重建。恢复后重新观察当前世界，不能承诺接着旧路线无缝拾取或重新证明断线前的在途效果。
- **恢复入口：** 有当前 `decision_id` 才按返回选项调用 `task(action="answer")`；普通暂停用 `resume`，已经终结的失败不能用 `resume` 复活。引用失效时只补查必要的附近实体，确认余物、库存和已发生开路效果，再提出修订目标。

## 模组条件与目前的证据边界

基础掉落实体、碰撞和拾取包来自 Minecraft；它们不依赖 AE2 或随身背包模组。模组物品可以通过当前注册表的真实 ID 和观察组件参与收取。开路受 [ClearanceWhitelist](../../common/src/main/java/org/maiwithu/maicraft/core/pathing/settings/ClearanceWhitelist.java) 和导航保护范围约束；`config/maicraft-clearance.json` 是配置位置，不是能力参数。只观察过区域不会自动产生保护许可；来自外层组合目标的明确保护由 `NavigationSafetyContext` 传入。

贡献者排查时要保留以下区别，不能通过加强描述把它们写成已解决：

1. **范围模式的归因较弱。** 它不强制本人同 UUID 拾取包，别处同组件库存增长与目标消失碰巧同时出现，仍可能满足组合证据；`drop_ref` 的严格身份归因不能推广到所有内部或范围任务。收尾补记部分入包也只覆盖带 `targetDimension` 的模式；范围模式中途取消/超时，正在收取但未结算的部分可能不进入统计。
2. **目标附加字段不产生效果。** 当前通用校验主要检查 `target.kind`，COLLECT 适配分支不消费 `target.label`、`position` 或 `relation`。即使某条入口接受了附加元数据，也不能用它限定拾取地点；正常请求只填 `current_place` 或省略目标。
3. **短期窗口直接用游戏时钟。** `PickupNavigationRetry` 的 40/10 刻、实体消失同步以及点名拾取包等待都比较原始 `getGameTime()`；普通暂停保留进度不等于冻结这些窗口。恢复后可能立即耗尽已过期窗口。不能把主任务的暂停期限机制当成这里已经接通了同样的计时。
4. **原生事件缓存有期限。** `ItemEntityReceipts` 最多保留 2,048 个事件、1,200 游戏刻；更早的事件不再可用于新证明。长暂停、断线、换身体或模组改变原生拾取数据包都可能使严格确认缺失，需要如实留为未确认。
5. **内部多目标合堆仍可能留下未确认引用。** 若内部任务获准收取 A、B 两个 UUID，A 合入 B 后 `retargetProvenMerge()` 改追 B，成功分支只把当前 B 加入 `completedTargets`。A 不会因此被登记完成，最后的全部目标检查仍可能失败；公开单一 `drop_ref` 本来就不授权转向另一个 UUID。这里是源码对照发现的边界，未做动态复现。

## 已有验证入口

这些是现有回归源码和场景入口，阅读它们不等于已经执行。离线夹具也不等于当前整合包、服务器及地形的实机验收。

| 想核对的场景 | 现有源码 |
| --- | --- |
| 公开字段、坏引用、开路许可、持久化组件 | [CollectItemsContractTest](../../common/src/test/java/org/maiwithu/maicraft/intent/CollectItemsContractTest.java) |
| 指定一堆、漂移、别人拿走、取消时的部分入包 | [CollectItemsSelectionTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/CollectItemsSelectionTest.java) |
| 身份与组件替换、合堆、冷却、浅水和岸边接触 | [CollectItemsIdentityTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/CollectItemsIdentityTest.java) |
| 一格矿洞、脚位头顶清障、保护格和开路账本 | [PickupClearanceTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/PickupClearanceTest.java) |
| 有限重寻、UUID 隔离、真正未知效果不重试 | [PickupNavigationRetryTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/PickupNavigationRetryTest.java) |
| 观察全量、物品归属与采掘收尾 | [DroppedItemObservationTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/DroppedItemObservationTest.java)、[DroppedItemPickupTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/DroppedItemPickupTest.java) |
| 回归汇总与任务注册 | [PickupRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/client/actor/PickupRegressionSuite.java)、[common/build.gradle 中的 pickupRegression](../../common/build.gradle) |

需要执行现有回归时，入口是 `gradlew.bat :common:pickupRegression`；只整理说明的工作不因此自动启动它。静态审阅应分别检查请求 JSON、引用来源、相对链接、注释与代码条件的一致性，再决定后续是否需要授权实机验证。
