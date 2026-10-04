# 传送门准备、跨维度与生存里程碑

玩家说“把门建好，我自己进去”时，使用 `maicraft:prepare_portal`；说“带我去下界”时，使用 `maicraft:travel_dimension`；需要把准备装备、找要塞、进入末地和获取鞘翅串起来时，使用 `maicraft:reach_milestone`。三个入口共享备门执行器，独立备门没有另一套更弱的供料逻辑。

| 入口 | 玩家得到什么 | 完成证据 |
| --- | --- | --- |
| `prepare_portal` | 准备并激活门，停在门外 | `portal_prepared=true`；浇筑还应检查整框观察 |
| `travel_dimension` | 通过已观察到的原版传送门到目标维度；可选先备门 | `verified=true` 且 `final_dimension` 等于请求维度 |
| `reach_milestone` | 推进一个明确的生存进度目标 | `completed=true` 与对应的 `completion_fact` |

`plan` 接受请求、原生子动作成功、门被点亮和角色进入新维度是不同事实。特别是浇筑动作全部结束但产物不符时，备门子任务可以成功结清动作，同时返回 `portal_prepared=false` 和结构差异；调用方不能只看外层 `success` 就宣布门建好了。

## 请求层级与参数

能力名称放在 `goal.ability`，人话目标放在 `goal.outcome`，下列能力参数放在 `goal.parameters`。`target` 和 `preferences` 与 `parameters` 同级；把“用单桶”“允许建造”只写进 `outcome` 不会替代 `portal_method`、`prepare_portal` 或 `may_alter_terrain`。

`prepare_portal` 的 `target` 可省略或使用 `{"kind":"current_place"}`，不能指定门框坐标。当前 `travel_dimension` 的目标位置只可能提供目的维度的后备信息，不会先走到该位置；`reach_milestone` 只在未给 `milestone` 时尝试读取 `target.label`。想先去一个地点，应先执行 `travel`，不要把地点标签当成这两个入口已实现的选门范围。

### 备门、材料与权限

| 参数 | 类型与默认 | 当前执行语义 |
| --- | --- | --- |
| `destination_dimension` | 维度 ID 字符串 | 独立备门省略或 `null` 时为 `minecraft:the_nether`；维度旅行应明确填写。旅行入口有目标维度后备解析，里程碑不接收此字段 |
| `prepare_portal` | boolean，默认 `false` | 仅旅行和里程碑可配置。附近没有可用活动门时允许准备入口；独立备门入口内部强制开启，不能传 `false` 将它变为只观察 |
| `portal_method` | `obsidian` / `lava_cast`，默认 `obsidian` | 黑曜石供料建框与单桶浇筑是显式选择，不会因缺钻石自动换手法。末地门准备不使用这项下界建框选择 |
| `may_alter_terrain` | boolean，默认 `false` | 允许路线开路及缺失门框、模具和操作空间的施工。浇筑需要开启；完整现有门框的点火不等同于重新建框 |
| `material_policy` | string，默认 `ordinary` | `ordinary` 使用常规供料；`storage_available` 启用存储但不是“只用仓库”；`inventory_only` 通常只检查随身现货，不自动用原料合成。存储冲突规则见下文 |
| `allowed_sources` | array of string，默认 `[]` | 可用值：`inventory`、`nearby`、`wireless`、`storage`、`harvest`、`craft`、`cook`、`mine`、`trade`、`hunt`。空数组等同于未收窄来源，不表示禁止全部来源 |
| `allow_combat` | boolean，默认 `false` | 主动战斗与被允许的材料狩猎许可；不会自动授予改地形或珍贵物品消费许可，也不是关闭既有自卫反射的开关 |
| `allow_rare_consumables` | boolean，默认 `false` | 备末地门时控制投掷及插入末影之眼；里程碑还传给战斗和末地折跃门等子任务。普通点火用品和桶的使用不由这个开关控制；获取物品与消费物品分开 |
| `protected_labels` | array of string，默认 `[]`，至多 64 个去重标签 | 使用已登记的真实标签。备门分支要求标签能解析到位置；未知标签不会被当作无需保护。覆盖范围的限制见“已知边界” |

`may_alter_terrain` 的适配结果是 `goal.parameters.may_alter_terrain` 与 `goal.preferences.may_alter_terrain` 的逻辑或。因此参数写 `false` 不能撤销偏好中的 `true`。其他权限不要据此类推为也从 `preferences` 读取。

来源以 [SemanticMaterialSupplyCoordinator.resolveSources](../../common/src/main/java/org/maiwithu/maicraft/core/task/supply/SemanticMaterialSupplyCoordinator.java) 的实际结果为准：始终保留随身库存；未授权开路时备门供料移除 `mine`，未授权战斗时移除 `hunt`。显式启用 `storage` 会附带 `craft`，当前还会覆盖 `inventory_only` 的通常限制；不要使用相互矛盾的组合表达“绝不取外部物资”。

这些来源限制针对普通材料供给。`lava_cast` 本身的原生取水、取岩浆及缺失资源探索仍会执行；`inventory_only` 不是禁止所有世界交互。施工时整理普通土石也不等同于取材来源选择，不能只凭 `allowed_sources` 推断不会发生存回容器的尝试。

### 搜索距离和里程碑

| 参数 | 适用入口、类型与默认 | 范围和边界 |
| --- | --- | --- |
| `max_search_radius` | 独立备门、维度旅行；integer，128 格 | 就近已加载门框或池岸候选查询；16..512。适配器会把 0、负数或越界数裁剪到边界，0 不是关闭搜索 |
| `max_resource_search_distance` | 三个入口；integer，768 格 | 缺水、缺回倒岩浆源或缺合格池子时，每次资源探索的水平半径。`0` 不发额外探索任务；正值须为 64..2048。不是整个任务的总路程上限 |
| `max_search_distance` | 维度旅行、里程碑；integer，4096 格 | 物理找要塞及里程碑委派的末地搜索范围；128..4096，越界会裁剪。独立备门未公开此参数，需找要塞时使用策略默认的 4096 |
| `max_portal_search_radius` | 里程碑；integer，128 格 | 委派维度旅行时的活动门查询半径；16..512，0 会裁剪为 16。不要误写成里程碑未公开的 `max_search_radius` |
| `milestone` | 里程碑；必填 string | `nether`、`stronghold`、`defeat_dragon`、`elytra`；去首尾空格并忽略大小写。推荐始终明确给出，不依赖标签后备 |
| `minimum_health` | 里程碑；number，10 HP | 传给末影龙战斗的最低生命门槛，有限值 1..1024；10 HP 是原版五颗心。0 不是关闭门槛；超过身体实际最大生命时战斗子任务会拒绝 |

资源探索从该次探索开始的位置计算范围，沿途再观察已加载可见资源。最后靠近水源、回池和普通材料供给的路程不并入这个探索半径。资源只读查询的水平半径最多 128 格；`0` 模式仍可能为了接近已观察资源而走动，不表示角色原地不动。候选查询半径也不是所有施工方块的保护边界。

**省略与 `null` 不统一。** 适配器中的目的维度和就近半径会把 `null` 当缺省，里程碑 `minimum_health:null` 由工具取默认 10；但备门策略的布尔、列表、方法、材料策略及资源搜索距离仍会直接读取已出现的字段，显式 `null` 可能解析失败。可选字段不用时应省略。普通距离的整数读取还可能发生转换、截断和裁剪；本轮仅说明现状，没有改成严格校验。

## 完整请求示例

下列 JSON 是 MCP `plan` 工具的完整参数。计划返回后，将实际返回的 `plan_id` 交给 `execute`；计划接受不代表已经拿到水、材料或门。示例不假造任务、地标或临时引用。

先准备资源，再用单桶法建下界门并停在外面；寻找资源允许有界跑图：

```json
{
  "goal": {
    "ability": "maicraft:prepare_portal",
    "outcome": "准备前置资源后用单桶浇筑并点燃下界门，停在门外",
    "target": {"kind": "current_place"},
    "parameters": {
      "portal_method": "lava_cast",
      "may_alter_terrain": true,
      "max_search_radius": 128,
      "max_resource_search_distance": 768,
      "material_policy": "ordinary"
    }
  }
}
```

只通过附近已有活动门去下界；缺门时报告缺口，`prepare_portal:false` 不会因为人话中写了目的地就自动建门：

```json
{
  "goal": {
    "ability": "maicraft:travel_dimension",
    "outcome": "通过已有传送门进入下界并核实维度",
    "parameters": {
      "destination_dimension": "minecraft:the_nether",
      "max_search_radius": 128,
      "prepare_portal": false,
      "may_alter_terrain": false
    }
  }
}
```

角色已经到达门厅并携带足够末影之眼时，激活现有末地门；代码不能从零生成生存模式下缺失的末地门框：

```json
{
  "goal": {
    "ability": "maicraft:prepare_portal",
    "outcome": "用随身末影之眼激活现有末地门并停在门外",
    "target": {"kind": "current_place"},
    "parameters": {
      "destination_dimension": "minecraft:the_end",
      "allow_rare_consumables": true,
      "material_policy": "inventory_only",
      "max_search_radius": 64
    }
  }
}
```

推进到取得鞘翅，明确允许相应战斗、珍贵消耗及缺门准备；子阶段仍逐项核对实际资源和效果：

```json
{
  "goal": {
    "ability": "maicraft:reach_milestone",
    "outcome": "准备所需物资并推进到实际取得鞘翅",
    "parameters": {
      "milestone": "elytra",
      "prepare_portal": true,
      "portal_method": "lava_cast",
      "may_alter_terrain": true,
      "allow_combat": true,
      "allow_rare_consumables": true,
      "minimum_health": 10,
      "max_search_distance": 2048,
      "max_portal_search_radius": 128,
      "max_resource_search_distance": 768
    }
  }
}
```

只查已加载资源可将浇筑请求的 `max_resource_search_distance` 设为 `0`。若要“备门后立即穿越”，把第一个示例的能力改为 `travel_dimension`，同时明确加上 `destination_dimension:"minecraft:the_nether"` 和 `prepare_portal:true`。

## 想看哪一步，打开哪里

| 要追的问题 | 代码入口与阅读重点 |
| --- | --- |
| 参数放错层级、名称未公开 | [SemanticGoalContract.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java)：对照 [SemanticAbilityCatalog](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java) 的字段集合 |
| 三个目标怎么适配 | [AbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java) 的 `preparePortal`、`travelDimension`、`reachMilestone`：默认值、目标后备及权限传递 |
| 桶法与普通门框为何走不同分支 | [PortalPreparationTask.onStart/advance/tickChild](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/PortalPreparationTask.java)：识别路线，再进入浇筑或现有门框调查 |
| 单桶前置与原生动作顺序 | [NetherPortalCastingTask.onTick/tickChild](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/NetherPortalCastingTask.java)、[PortalCastingStep.plan](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/PortalCastingStep.java)：先准备，再清理、补台、导流和逐桶浇筑 |
| 水不近时怎么继续 | [PortalResourceSearchTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/PortalResourceSearchTask.java)：可见静源查询、委派探索、新视点观察、发现后结束探索 |
| 填岸后还剩多少岩浆 | [PortalCastingSurvey](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/PortalCastingSurvey.java)、[PortalCastingTerrain](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/PortalCastingTerrain.java)：源格、四格起手行、站台成本和保守余量 |
| 为什么已有岩浆仍要真实倒桶 | [FluidPlacementTaskRecord.emptyIntoSource](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/assembly/FluidPlacementTaskRecord.java)、[FluidPlacementTask](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/assembly/FluidPlacementTask.java)：腾桶不能用已有源格提前结算 |
| 倒桶如何确认、取消后是否重发 | [FluidPlacementReceipt](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/assembly/FluidPlacementReceipt.java)：服务端使用序号、准确桶账与世界结果分别核对 |
| 什么时候准备点火用品 | [PortalPreparationSupplies](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/PortalPreparationSupplies.java)、[PortalPreparationPolicy](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/PortalPreparationPolicy.java)：供料事实、来源与独立权限 |
| 放方块与开挖为何失败 | [FirstPersonBuildCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/FirstPersonBuildCompanionTask.java)：原生施工、菜单、腾位、支撑与路线；不要把所有失败都归为池岸不合适 |
| 门面是否真的激活 | [PortalActivation](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/PortalActivation.java)、[NetherPortalFrame](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/NetherPortalFrame.java)、[EndPortalFrame](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/EndPortalFrame.java)：准星、确认序号和真实门面 |
| 为什么到了门边还没算换维度 | [DimensionTravelCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/dimension/DimensionTravelCompanionTask.java)：找活动门、选入口、进入、交接玩家对象及核对目标维度 |
| 里程碑会自行拆哪些前置 | [ReachMilestoneCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/progression/ReachMilestoneCompanionTask.java)、[ProgressionRequirementProfile](../../common/src/main/java/org/maiwithu/maicraft/core/task/progression/ProgressionRequirementProfile.java)、[ProgressionChildFactory](../../common/src/main/java/org/maiwithu/maicraft/core/task/progression/ProgressionChildFactory.java) |
| 什么事实决定里程碑完成 | [ProgressionFacts.milestoneDone/completionFact](../../common/src/main/java/org/maiwithu/maicraft/core/task/progression/ProgressionFacts.java)：不要以子阶段返回成功代替目标事实 |
| 暂停、跨维度和重启怎样恢复 | [IntentTask.stop](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)、[TaskSlot.detachForHandoff](../../common/src/main/java/org/maiwithu/maicraft/task/TaskSlot.java)、[IntentRuntime](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) |

## 实际动作与结果如何对应

### 备门与浇筑

`obsidian` 路线调查可复用门框，缺块才供料、施工并点火。末地路线只对完整的现有门框补眼；没有门厅证据时可委派真实投眼找要塞，不能直接生成门框。

`lava_cast` 只在主世界进入预定义单桶流程：可用桶 → 实际取水 → 打火石或火焰弹 → 合格池岸与工具、方块 → 清理操作空间、补台、放水、逐格取放岩浆 → 收水 → 点火。已带水桶会复用；只有岩浆桶时，要真实回倒到已观察的岩浆源并确认空桶返还。浇筑使用的源池和施工地点需要真实存在，一只岩浆桶不等于一个池子。

可见资源搜索不会调用种子定位或强制加载；缺口未满足时可委派现有地面探索，在新视点继续查询。发现所需资源后结束的是探索子任务，不是“整片区域已探索完”；取水还要由桶任务另行确认。远处取水后，保存的池边干燥站位用于返回施工。

桶操作成功以原生确认和桶账为依据。水蒸发、生成圆石或黑曜石属于实际世界结果，不能把已经确认的操作改称未知；同样不能把返还空桶当作整扇门完成。临时模具与已确认施工效果保留，取消或失败不会回滚现场。

`resource_preparation`、`pending_supply`、`operation`、`native_steps` 和 `construction_phase_started` 分别说明已准备资源、当前缺口、动作阶段、原生经过以及是否进入施工阶段。`portal_observation` 包含门框数量、激活状态和逐格差异。给模型的结论应区分：资源已到手、动作已结清、门框合格、门已激活。

背包空间不足是独立卡点。普通建造在少于四个空槽时可能尝试存回普通土石；没有可用容器时会失败。不能因此宣称岩浆池不可达，也不能隐含授权丢掉玩家物品。真实路线或站位失败、且代码判定没有放置站台时，才进入池岸候选回退。

### 维度旅行

当前通用路线只有主世界与下界互通、主世界与末地互通。下界与末地之间需要经过主世界；注册了模组维度 ID 不等于此执行器支持对应门。已经位于目标维度时会直接确认完成，此时没有新的穿门动作。

旅行先查活动门。只有没有可用活动门、开启 `prepare_portal` 且尚未尝试准备时，才进入共享备门流程。它从门的底部入口选择真实站位并走入门面，授权同一连接上的玩家对象替换，再检查目标维度；断线或单纯靠近门不是成功。

查看 `destination_dimension`、`final_dimension`、`verified`、入口尝试数及 `portal_entry_failures`。备门结果也保留在旅行回执里。即使浇筑动作全部完成，只要 `portal_prepared=false`，旅行就不能继续声称到达目的地。

### 生存里程碑

里程碑每刻先看真实维度、库存、装备与世界证据，再选择供料、装备、结构搜索、跨维度、战斗或鞘翅搜索子任务。成功子任务若没有带来可观察变化，也不能无限重派同一阶段。

| `milestone` | 当前完成判据 | 不应推导成什么 |
| --- | --- | --- |
| `nether` | 当前维度是下界 | 不要求这次重新建门；曾经到过但当前已返回主世界不算 |
| `stronghold` | 在主世界、已有末地门框证据且距锚点中心不超过 8 格 | 不验证天然结构注册身份，也不等于完整门环已激活 |
| `defeat_dragon` | 当前在末地，龙战状态有出口门特征或本连接已确认阶段转换等佐证 | 没看到龙不等于龙已死亡，也不一定证明由当前角色击杀 |
| `elytra` | 主背包、装备或副手中实际持有鞘翅 | 不保证已经穿戴、耐久适合飞行或已准备烟花 |

末影之眼、折跃门珍珠和战斗用品仍受相应消费许可约束；子任务要求另一个维度取材时，聚合层可据明确的 `requires_dimension` 事实安排旅行，但不会因此自动开启 `prepare_portal`、战斗或改地形权限。主要回执是 `aggregate_stage`、`completed`、`completion_fact`、`blocked_facts` 和 `child_evidence`。

## 暂停、取消与恢复

| 情况 | 应如何理解 |
| --- | --- |
| 自卫抢占、失去控制权或同进程暂停 | 按 `PREEMPTED` 停止当前输入，保留内存中的逻辑子任务；调度冻结相应等待时间，不把等待当有效工作 |
| `task` 暂停/继续 | 暂停标记不等于瞬间撤回已发出的点击；恢复前读任务实际状态。存在可回答的 `decision` 时先按其 ID 和选项回答 |
| 取消、死亡或身体失效 | 停止子任务并尽量取得清理回执；已放方块、已取水和已消费物品留在世界中，未知结果仍须保留 |
| 预期跨维度 | 只在有效交接许可内接到新玩家对象，并核对新维度；旧世界路线和菜单不复用 |
| 意外换世界、断线、重启 | 按世界身份恢复语义目标和已有证据；恢复的非终态任务先暂停，需要明确继续，不恢复旧身体的输入句柄 |

当前浇筑的布局、逐桶游标和原生子任务保存在执行器对象里，没有专用的持久化逐桶续建协议。跨进程恢复不能承诺从第几桶精确接着做。先读已保留回执与当前现场，尤其是 `outcome_uncertain`、`bucket_submitted`、`mechanical_retry_allowed` 和门框差异；不要盲目重复不确定动作。`requires_decision` 是结果提示，不保证一定已有可回答的 `decision_id`。

## 已知边界与当前验证状态

- 地点型 `target` 不是维度旅行的门址选择器，也不是里程碑的区域执行范围。适配器目前只取部分后备信息，其余位置没有接到先行移动。
- 距离存在静默裁剪，策略字段的 `null` 处理不统一；隐藏里程碑工具把 `minimum_health` 声明为 integer，但公开契约及执行记录使用 number/Float，需统一说明。
- `material_policy` 与 `allowed_sources` 有存储优先例外；不应把 `inventory_only + storage` 描述为严格只用背包。
- `protected_labels` 的覆盖依赖具体路径：备门会解析地标锚点；`IntentTask` 另叠加先前步骤留下、明确点名的区域保护回执。不能把一个地标名概括成任意整片基地或所有旅行路径的完整区域保护。
- 浇筑仍使用四格源岩浆起手行、固定导流模板和填岸后至少十五格源岩浆的保守余量。十五格不是对任意地形、水流和模组反应的成功证明。几何站位预检也不是导航成功。
- 池岸候选收集有上限，取桶未提交时的换源也有次数限制。回退集合耗尽不等于全世界或全部已加载地形都不存在方案；“尚未放置”的判断目前依据缺格数量比较，复杂并发变化需结合动作账本复核。
- `prepare_portal` 的浇筑模式直接进入浇筑分支，不先按活动门做旅行式复用；已经有门时需要分清“再准备一扇门”和“使用已有门”。
- 本次文档复盘基于当前工作区。自动远水前置、资源探索、点火前置及失败类型传递仍有未提交的功能改动；契约发布须与这些实现一起核对，不能把文档提交视为功能发布。
- 此前实机场景已确认单岩浆桶复用、到 50 格外取水及用一块铁合成打火石；满包场景随后因施工腾位失败停下，不能称为完整浇筑通过。有空槽场景的最新完整实机复测尚未完成。本轮文档整理没有运行测试、游戏或 Luna。

已有验证入口供贡献者继续检查，本轮只静态确认入口与源码关系：

| 入口 | 覆盖点 |
| --- | --- |
| `:common:portalRegression` | 门框、激活、权限、前置顺序和原生回执边界；入口为 [PortalRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/core/task/dimension/PortalRegressionSuite.java) |
| `:common:portalCastingHonestSelectionRegression` | 候选可达性、回退、失败原因与观察统计；见 [PortalCastingHonestSelectionTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/dimension/PortalCastingHonestSelectionTest.java) |
| `:common:semanticBlockSearchRegression` | 可见方块与按用途查池，不冒称未知区域无资源 |
| `:common:guiRecoveryRegression` | 包括桶的真实动作端口、确认、占位与取消边界；见 [FluidPlacementTaskTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/FluidPlacementTaskTest.java) |
| [PortalCastingTestHarness](../../neoforge/src/portalTest/java/org/maiwithu/maicraft/neoforge/testing/PortalCastingTestHarness.java) | 开发用专用 TEST 存档夹具，会初始化场景和物品，不应用于正式存档；原生任务结果必须另行验收 |

两种加载器共用这些核心任务。水、岩浆、门框和点火走原生交互，不靠直接改世界或背包来宣布成功；普通材料供给可能委派已安装的存储或加工集成，是否存在该模组、站点和材料必须由各子任务按实际状态报告。
