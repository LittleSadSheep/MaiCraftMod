# 寻找生物、跟随与战斗

玩家说“找两只带毛的成年白羊”“跟着附近这头牛”或“打退溺尸”时，分别涉及 `maicraft:find_entity`、`maicraft:follow`、`maicraft:combat`。查找提供观察证据，跟随持续维持距离，战斗提交真实攻击并收取回执。查找成功不授权伤害，也不表示已经取得羊毛；跟随靠近不代表任务完成；弓的发射确认不代表命中。

本文对照当前执行代码，供修改适配器、战斗或导航的贡献者使用。通用要求见[能力契约](capability-contracts.md)。本轮只做源码、JSON 和链接静态核对，没有执行回归或启动游戏。

## 从玩家场景找代码

| 想看哪一步 | 打开的位置与关键方法 |
| --- | --- |
| 公开能力声明及允许的字段 | [SemanticAbilityCatalog.describe](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java)、[SemanticGoalContract.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java)；声明参与参数白名单，添加说明不能顺便扩大字段范围 |
| 从名字、类型或羊属性选目标 | [GeneralAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/GeneralAbilityAdapter.java) 的 `combat`、`follow`、`findEntity`、`selector`、`findEntities`；内部生成实体编号，模型不填写编号 |
| 先搜视野，再走到新区域观察 | [GenericEntitySearchCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/entity/GenericEntitySearchCompanionTask.java) 的 `scanLoadedEntities`、`observeThenContinue`、`tickFrontierTravel` |
| 查找数量、距离和预算 | [GenericEntitySearchTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/entity/GenericEntitySearchTaskRecord.java)、[SemanticEntitySearchApi.newRecord](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/SemanticEntitySearchApi.java) |
| 分辨无主、敌对、被圈养及羊毛状态 | [EntitySemanticSafety](../../common/src/main/java/org/maiwithu/maicraft/core/task/entity/EntitySemanticSafety.java)、[SheepTraits](../../common/src/main/java/org/maiwithu/maicraft/core/task/entity/SheepTraits.java) |
| 锁定跟随身份、近了停步、远了重走 | [FollowTool.onGameCall](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/FollowTool.java)、[FollowCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/move/FollowCompanionTask.java) 的 `target`、`canRun`、`goal`、`closeEnough` |
| 开始一场战斗与延长有进展的战斗 | [CombatOps.attack](../../common/src/main/java/org/maiwithu/maicraft/core/tools/CombatOps.java)、[AttackCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/combat/AttackCompanionTask.java) 的 `onTick`、`surveyField`、`renewCombatProgress` |
| 选武器、瞄准、出刀和收回执 | [Loadout.forTarget](../../common/src/main/java/org/maiwithu/maicraft/core/combat/Loadout.java)、`AttackCompanionTask.tickWeapon`／`settleSubmittedMelee`、[Interaction.fireAttackEntity](../../common/src/main/java/org/maiwithu/maicraft/core/act/Interaction.java) |
| 拉弓、装弩、取消与箭的弹道 | [RangedShot](../../common/src/main/java/org/maiwithu/maicraft/core/task/combat/RangedShot.java)、[Ballistics.findArrowShot](../../common/src/main/java/org/maiwithu/maicraft/core/act/Ballistics.java)、`AttackCompanionTask.shootAt` |
| 自卫、低氧与玩家授权 | [CombatThreats](../../common/src/main/java/org/maiwithu/maicraft/core/combat/CombatThreats.java)、[MobDefenseChain](../../common/src/main/java/org/maiwithu/maicraft/core/task/chain/MobDefenseChain.java)、[BreathChain](../../common/src/main/java/org/maiwithu/maicraft/core/task/chain/BreathChain.java)、[PvpEngagement](../../common/src/main/java/org/maiwithu/maicraft/core/combat/PvpEngagement.java) |
| 判定死亡、掉落归属和战斗结果 | `AttackCompanionTask.settleFinishedTargets`／`resultData`、[LootSweep](../../common/src/main/java/org/maiwithu/maicraft/core/task/combat/LootSweep.java) |
| 暂停、决策与磁盘恢复 | [IntentTask](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)、[IntentRuntime](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java)、[MaiCraftRuntimeFacade.task](../../common/src/main/java/org/maiwithu/maicraft/mcp/MaiCraftRuntimeFacade.java) |

## 请求的层级与公共约定

完整 `plan` 请求形如 `{"goal":{"ability":"maicraft:combat","outcome":"…","parameters":{…}}}`。能力参数放在 `goal.parameters`；`goal.target` 可省略，存在时为对象，包含 `kind`、可选 `label`／`relation`。`outcome` 是目的说明，不会替代筛选字段或改变胜负条件。规划不执行攻击，实际动作由 `execute` 接受目标或返回的真实 `plan_id` 后开始。

这三项没有专属 `preferences` 或硬约束声明，使用空对象／空数组或省略即可。底层存在的兼容分支不等于公开输入可用，例如 `entity_type` 别名、`target.kind=entity_type` 和 `preferences.may_alter_terrain` 会被当前公开契约拒绝。不要传运行时实体 ID、UUID、路径、点击脚本、武器槽或未声明的 `strictAuthorized`。

下表中的整数范围是适配器实际归一范围。`GeneralAbilityAdapter.integer` 对缺失、`null`、无法读取的值使用默认值，对能读取的越界整数夹到上下限；`0` 因而通常成为最小值，不表示关闭。某些小数和字符串还会被 Gson 转成整数；应发送真实 JSON 整数，不依赖这些宽松转换。普通布尔开关缺失、`null` 为默认 `false`；应发送 JSON 布尔，不能用 `0` 代替。羊属性和 `protected_labels` 有独立严格校验，见下文。

### 附近目标：战斗与跟随共用的选择规则

| 字段／位置 | 类型、默认与实际含义 |
| --- | --- |
| `parameters.entity_type_id` | 可选已注册资源 ID 字符串，例如 `minecraft:zombie`。无默认种类；未知 ID 进入决策，不转成“任意生物” |
| `parameters.entity_name` | 可选显示名／自定义名字符串，不区分大小写比较；不是唯一身份，重名可能进入决策 |
| `parameters.player_name` | 可选玩家档案名字符串，不区分大小写精确比较；不会按昵称模糊搜索 |
| `target.kind` | 两者公开接受 `entity`、`player`、`nearest`。`player.label` 补足玩家名；`entity.label` 若是注册实体 ID 则当类型，否则当显示名；`nearest` 只允许就近消歧，不能单独提供目标种类 |
| `parameters.selection` | 字符串；仅 `nearest` 有特殊效果。省略、`null` 或其他值不主动解除歧义。`target.kind=nearest` 或 `target.relation=nearest` 也启用就近选择，不能被 `selection=unique` 覆盖 |

类型、玩家名、显示名等条件组合时同时匹配。注意一个兼容细节：已注册的 `target.kind=entity` 标签会覆盖参数里的类型；不要提交互相矛盾的标签和类型。字符串字段缺失、`null`、空白一般视为未提供，不是匹配字面值 `null`。

初选扫描角色碰撞箱向各轴扩张 `radius` 的已加载区域，按实际距离排序，并排除自己、被移除和不存活实体；战斗额外要求 `isAttackable`。这是轴对齐范围扫描，没有视线遮挡检查，也不保证目标可走到。不要把初选提示中的 “visible” 当成射线已通过；真正近战和射箭仍有各自的原生触及／遮挡检查。

### `maicraft:combat`

| `goal.parameters` 字段 | 类型、默认、范围与分支 |
| --- | --- |
| `mode` | 可选字符串。`defend`（兼容 `defence`）且没有选择器时，创建动态自卫；`engage`／`defeat` 与省略值没有不同的执行算法。带选择器时即使写 `defend` 也走点名战斗。其他字符串没有专门拒绝分支，不应据此假定支持新模式 |
| `allow_harm` | 布尔，必须为 `true` 才派发，包括显式 `defend`；省略／`null`／`false` 均要求决策，不攻击 |
| `confirm_risky_target` | 布尔，默认 `false`。选出的目标含玩家、命名生物、已驯服生物或非 `Enemy` 时，还须确认具体目标后设 `true`。它不代替 `allow_harm` |
| `count` | 整数，默认 1，归一到 1～20；是最多选择的数量。现有目标不足数量时仍可只打现有目标。数量为 1 且存在多个匹配、又未允许 `nearest` 时进入决策；数量大于 1 直接按距离取前几个 |
| `radius` | 整数，方块，默认 32，归一到 4～128；只用于初选，不是武器射程、后续追逐边界或任务期限 |
| `sheep_color`／`sheep_baby`／`sheep_sheared` | 可选羊属性，详见下表；与类型／名字一起筛选。战斗名单携带要求，继续出手前复核 |

无选择器的动态 `defend` 直接生成内部 `attack({})`，没有使用 `count`、`radius`，也没有逐个目标的风险确认分支；不要用这些字段声称限制这条自卫分支。普通显式语义战斗生成的记录不是严格名单模式：额外真实袭击可能先触发自卫，再回到原名单。内部严格水晶任务的爆炸约束不等于公开参数。

以下完整 `plan` 示例要求近处最多一个可接受的僵尸；例子中的原版 ID 是注册类型，不是虚构的实体引用：

```json
{"goal":{"ability":"maicraft:combat","outcome":"击败附近最近的僵尸","target":{"kind":"nearest"},"parameters":{"entity_type_id":"minecraft:zombie","mode":"defeat","selection":"nearest","count":1,"radius":32,"allow_harm":true,"confirm_risky_target":false}}}
```

动态自卫的完整请求无需编造附近实体：

```json
{"goal":{"ability":"maicraft:combat","outcome":"打退正在威胁我的生物","parameters":{"mode":"defend","allow_harm":true}}}
```

### `maicraft:find_entity`

| `goal.parameters` 字段 | 类型、默认、范围与分支 |
| --- | --- |
| `entity_type_ids` | 注册资源 ID 的数组；与单数项合并去重，最终要求 1～32 种。空数组本身不提供类型；非注册类型进入决策 |
| `entity_type_id` | 可选单个注册 ID，与数组相加，不互斥。两者没有有效类型时才尝试 `target.kind=entity` 的 `label`，该标签此处必须是类型，不是显示名 |
| `relation` | 可选字符串 `wild`、`hostile`、`unowned`、`any`；先读参数，再读 `target.relation`，最后默认 `any`。省略／`null` 不构成独立筛选；不支持的关系进入决策 |
| `count` | 整数，默认 1，归一到 1～32；必须在本次扫描中同时观察到足够多的不同 UUID。不是每种类型各找几个，也不是沿途累计数量 |
| `max_distance` | 整数，方块，默认 512，归一到 16～2048；以搜索开始的角色位置为圆心的水平范围。实体须在范围内；角色偏离超过范围加 8 格容差时停止并失败 |
| `may_alter_terrain` | 布尔，默认 `false`。`true` 将导航子任务切为可改地形的上下文，不保证任意方块都能破坏；`false` 仍允许移动观察 |
| `protected_labels` | 可选字符串数组，省略／`[]` 为空；`null`、非数组、空白／非字符串条目拒绝。内部去重后最多 64 个，用已有语义记忆标签，不传坐标。见下方保护边界 |
| 羊属性 | 与战斗相同；数量只统计仍符合要求的羊 |

公开接受 `target.kind=entity|nearest|area|landmark|current_place`，但执行器仍以角色当前位置初始化 `origin`；`area`／`landmark` 标签不会让它先旅行到该地点。`nearest` 在这里也不是“距离最近实体”的额外输出承诺。若要换地点，应先使用移动能力，不能靠 `outcome` 或 `target` 文本暗示已完成旅行。

关系先筛实体，再检查保护证据：`wild` 是非敌对 `Mob`；`hostile` 接受 `Enemy` 或 `MONSTER` 分类；`unowned` 是非玩家的活物候选；`any` 不限制这些关系。`wild`／`unowned` 会排除命名、驯服、原生所有者、拴绳、乘坐载具或承载乘客的对象。公开查找没有内部 `harmIntent` 开关，`any`／`hostile` 不能理解为已经证明无主或可攻击。

实体保护解释器中的 `protected_labels` 目前只将同维度、带 `MANAGED_SETTLEMENT` 角色的已记忆地标（水平 12 格内）与实际圈围证据结合，用于需要无主证据的关系；标签未解析、其他地标角色或仅位置接近都不会直接排除实体。上层语义任务可能另有已测量区域保护收据，见 `IntentTask.withExplicitAreaProtection`／`refreshProtectionCache`；不能把实体筛选的标签解释成一套已经覆盖所有实体与所有路线的禁行保证。

```json
{"goal":{"ability":"maicraft:find_entity","outcome":"找到两只目前带毛的成年白羊","target":{"kind":"current_place"},"parameters":{"entity_type_ids":["minecraft:sheep"],"relation":"wild","count":2,"max_distance":512,"may_alter_terrain":false,"protected_labels":[],"sheep_color":"white","sheep_baby":false,"sheep_sheared":false}}}
```

### 羊属性的真假与空值

| 字段 | 真实语义 |
| --- | --- |
| `sheep_color` | 字符串，16 种原生染料名：`white, orange, magenta, light_blue, yellow, lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red, black`。精确小写；省略不限颜色，`null`／空串／错字拒绝 |
| `sheep_baby` | JSON 布尔；`true` 仅幼年，`false` 仅成年，省略不限年龄。`null`、数字、字符串拒绝 |
| `sheep_sheared` | JSON 布尔；`true` 已剪毛，`false` 仍带毛，省略不限。不是“是否允许剪毛”；`null`、数字、字符串拒绝 |

任何一个羊属性出现，候选就必须是羊。战斗期间状态改变会停止进一步攻击并报告 `requested_sheep_traits_changed`，保留 `changed_sheep_targets`；不能换一只不同毛色的羊补数。`follow` 的公开字段没有羊属性，虽然共用选择器实现，也不能传这些参数。查找羊／杀羊均不自动保证具体羊毛入包；需要物品目标时应使用取物能力及其实际库存确认。

### `maicraft:follow`

除上述附近目标字段外，仅接受以下专属参数。至少提供一个有效类型或名字；无目标时进入决策，不自动选默认主人。

| `goal.parameters` 字段 | 类型、默认与实际含义 |
| --- | --- |
| `radius` | 整数，方块，默认 64，归一到 4～128；只限定第一次解析候选的已加载区域 |
| `distance` | 整数，方块，默认 3，归一到 2～16；用角色和目标实体位置的三维距离判断，`0` 归一为 2 |
| `may_alter_terrain` | 布尔，默认 `false`。默认路线不挖不垫；`true` 允许导航在其原生规则内开路。导航失败可能附带地形需求，不能假定一定有完整可改方块清单 |

```json
{"goal":{"ability":"maicraft:follow","outcome":"跟着附近最近的一头牛，不改动地形","target":{"kind":"nearest"},"parameters":{"entity_type_id":"minecraft:cow","selection":"nearest","radius":64,"distance":3,"may_alter_terrain":false}}}
```

## 执行与事实确认

### 查找：观察与移动反复进行

从当前位置记录水平边界，先在已加载的 112 格扫描区中核对视线、类型、关系、羊属性和保护证据。每次扫描替换当前可接受 UUID 集合；一旦达到数量，停止尚在进行的移动路段，返回观察结果，不攻击或拴住实体。

证据不足时选择有界前沿点，交给 `MoveToCompanionTask` 真实行走并在途中重扫。地面生成型生物优先抽样干燥地面，水中尝试回岸；没有路线、航点预算耗尽和搜索越界都保留部分观察。失败不是“世界里不存在这种生物”。运行中看 `stage`、`farthest_body_distance`、`frontier_legs_*`、`acceptable_observed`；结束看 `verified`、`observed_acceptable_count`、`observed_acceptable_by_type`、`observed_sheep`、`failure_code`、`protection_reason_counts`、`suggestions`。

返回内部 UUID 仅供 Java 父流程重新核验，不作为公开稳定目标句柄。之后单独发出的 combat／follow 仍按当时的语义条件重新选目标。

### 跟随：距离迟滞与同一实体

工具先解析已加载实体，把 UUID 与运行编号一起冻结；每刻核对编号仍指向相同 UUID。超过 `distance + 2` 格才从静止起步，移动中进入 `distance` 内停步，从而避免边界反复起停。目标移动时导航重新取其位置；跟随没有正常 `SUCCESS` 分支，不应放在希望自动执行下一步的顺序任务前面。

已加载对象消失、移除、编号被另一 UUID 复用时返回 `TARGET_LOST`；当前 `target()` 没有单独调用 `isAlive()`，所以死亡动画期间尚未移除的实体可能短暂继续被跟随。飞行目标的导航锚点会向下找最多 64 格地面，但起停判断仍用实体本体三维距离；到达其脚下不一定满足跟随距离，可能反复起步。没有跨维度追踪或靠 UUID 自动重新寻回对象的实现。

### 战斗：持续选位、原生出手与收场

先观察战场和低血／爆炸危险，再选择近战、弓战或撤离。攻击与导航逐刻配合，只有武器、冷却、目标受击保护、准星射线和原生触及满足条件时才提交出刀。近战等待原生确认；目标死亡、失踪或转入拾取时也先收已有回执，不能重复发送致命一击。

弓与弩共用箭的弹道求解：重力、阻力、目标移动和遮挡参与计算；弓按原生持用刻数蓄力，弩区分已有弹药、装填和发射。普通 Mob 的站位环与射距门使用水平距离，弹道仍保留三维范围上限；玩家分支和严格水晶分支有各自判据。持续持用通过输入租约维持；取消弓使用切槽，避免把取消变成松弓射箭，操作预算已耗尽时由原生端口延后收尾。

点名目标必须确认死亡才能记为击败；消失或卸载一般记丢失，不按击杀补账。非玩家战斗会继续处理因果掉落，物品消失、合并和库存增量要分别核对；无法确认掉落归属可能使整场任务失败。玩家对战不会为追逐其背包掉落延长战斗。

结果中的 `strikes_scope=confirmed_melee_receipts_and_ranged_releases` 很关键：`strikes` 混合了确认近战回执和确认远程发射，不是总命中数。`last_ranged_shot` 只描述最后一轮弓弩状态；成功击败之后取消下一轮蓄力，也可能留下 `cancelled`／`misfire`，不能覆盖之前的发射和击败证据。结合 `completion`、请求／击败／丢失／不可达数量、`loot_receipt`、`loot_gained` 及撤退观察解释结果。

自动自卫另由真实生物伤害、明确攻击目标和近处苦力怕警戒触发，不要求模型先发主动 combat。低氧时 `BreathChain` 仍负责上浮与逃生，只借用近战反击，不另开追击、拾取或水下弓战。玩家伤害不自动授予 PVP；点名玩家的语义确认与 `PvpEngagement` 身份绑定仍需满足。

低血撤退的失败回执始终披露拒战线并给出出路（`maicraft:suicide` 死亡重置或前往已知食物点）。当血量已在拒战线下、背包没有任何无效果食物、饥饿又低于原版自然回血线（18）三者同时成立时，回执额外点破互锁处境——此时「进食回血 → 有体力作战」的常规恢复链断裂，上述出路就是仅剩的恢复手段。背包里有无效果食物或饥饿在回血线上时，回执保持普通低血话术，不插入互锁长文。

## 暂停、取消、死亡和恢复

接单只表示任务进入执行，不能当作成功。使用返回的真实任务 ID 查询 `task(action=get)`；`pause` 先设暂停标记，身体调度再释放输入。`resume` 只恢复可恢复的任务；存在待决策问题时必须用 `answer`，不能用 resume 绕过。根据返回的真实 `decision_id` 和选项，用 `retry` 补充 `details.parameters`，或用 `recover`／`replace_goal` 提供新的 `details.goal`；未知原生副作用可能阻止普通重试。不要编造任务或决策 ID。

同一执行实例被生存反射临时抢占时，父任务延长等待时间并保留进度；恢复后核对当前世界事实。取消不会撤回已造成的伤害、已飞出的箭、已拾到的物品或开路时已改变的方块。射击取消和旧原生回执结算有独立职责，停止输入不是“服务器没做过动作”的证据。

死亡、断线或世界绑定改变时，身体控制与玩家对战许可失效；语义历史保留已观察副作用，死亡恢复按当前真实决策处理。磁盘恢复的非终态任务先暂停，恢复时重新创建业务执行器并重新解析当前语义目标；不能声称恢复了旧箭的飞行过程、同一批瞬时实体编号或所有已观测 UUID。跟随内部的 UUID 核验保护同一实例内的身份，语义任务重建仍可能重新选取匹配者。

## 模组边界、待修差异与验证入口

注册实体类型可来自模组，标准 `Mob`／`LivingEntity` 的观察和原生攻击可以复用；这不等于已经理解 Boss 阶段、无敌、弱点或多部位机制。远程选装目前识别 `BowItem`／`CrossbowItem`，按箭弹道计算，不能保证枪械、法杖、火箭弹或自定义速度／重力武器正确。已有静止目标弓战实机记录不覆盖移动 Boss、全部弩弹药或真人 PVP。

本轮将以下差异交协调者处理，没有更改条件或返回语义：

- combat 的模式名多数共用逻辑，未拒绝未知模式；无选择器 defend 忽略 count／radius。羊属性本身已构成选择器，带羊条件的 defend 会转为点名战斗，不进入无选择器分支。
- find_entity 接受的 area／landmark 没有落实旅行或搜索锚点；可接受目标列表还未覆盖其内部 entity_type 兼容分支。
- 附近初选的 “visible” 文案强于实现，且 radius 是扩张碰撞箱，不是严格球形半径。
- 参数整数采用默认回退和边界夹取，不能宣称对非法输入一律拒绝；三项的 count 语义不同。
- find_entity 的标签保护范围窄于“所有记忆地点与财产均排除”的直觉，未解析标签不构成实体保护证据。
- follow 的飞行锚点与起停距离不一致，死亡检测依赖移除；公开层也没有羊属性筛选或重连同一实体保证。

现有验证入口（仅导航，本轮未运行）：[CombatThreatsTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/CombatThreatsTest.java) 组织战斗回归；[RangedShotTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/RangedShotTest.java) 与 [RangedDistanceBandTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/RangedDistanceBandTest.java) 检查持用、取消与高差；[BreathDefenseTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/BreathDefenseTest.java) 检查换气反击；[SheepSelectionTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SheepSelectionTest.java)、[SheepCombatTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SheepCombatTest.java) 检查颜色选择与状态变化。构建入口在 [common/build.gradle](../../common/build.gradle) 的 `combatRegression` 及相关语义／GUI 回归定义；未找到独立的 Follow 专属回归文件，不将源码检查冒称跟随验收。
