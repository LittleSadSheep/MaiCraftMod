# 交互与定点使用物品

玩家说“给门框嵌眼”“把岩浆倒进这个坑”“用剪刀剪那只白羊”时，公开入口接收的是目标与物品，角色的站位、镜头、选物和原生提交由 Mod 完成。这里说明 `maicraft:interact` 与 `maicraft:use_item`；它们是 `plan` / `execute` 的 `goal.ability`，不是另外注册的 MCP 工具。

两者共享定点交互执行器，但并非所有参数和结果都相同。原生动作完成、目标格最终状态、返还物增加与整台机器能否工作必须分别判断。

## 按玩家意图选入口

| 玩家要做什么 | 入口与目标 | 角色实际执行什么 |
| --- | --- | --- |
| 用空手按按钮、打开方块、乘坐或交谈 | `interact`；方块坐标、方块类型或实体描述 | 选目标、走近、收好原手持物，再执行普通右键；不自动交易或搬运容器物品 |
| 给指定末地门框嵌眼 | `use_item` 的坐标目标与 `item_id=minecraft:ender_eye`；也可用 `interact` | 对指定框执行方块使用，观察眼数量和框的 `eye` 属性；不自动补齐十二框或保证激活传送门 |
| 往指定格倒水或岩浆 | 两个入口均可，满桶加坐标 | 坐标表示流体落格，可以是空气；执行器寻找支撑面，再沿桶自己的原生物品射线操作 |
| 用空桶取水或岩浆 | 两个入口的坐标目标；或 `interact` 查找源方块 | 坐标表示被收取的源格；流水会在提交前报告不能收取，不替换成另一个源格 |
| 剪羊毛、给动物使用指定物品 | `interact` 的实体筛选与 `item_id` | 跟随已选实体，复核筛选条件和准星命中，再使用物品；剪下的羊毛不等于已收入背包 |
| 沿当前视线投掷、喝瓶装物品或持用加工 | 不带坐标的 `use_item` | 保留当前视线；优先使用已持有该物品的手，物品本身决定如何作用于世界或副手 |
| 要若干件新增加工产物 | `use_item` 的 `count`、`expected_output_item_id`，可选 `ingredient_item_id` | 从随身库存准备双手，逐次持用并检查每次产物增量，缺料或未确认时保留部分结果后停止 |
| 手摇 Create 曲柄一段时间 | `interact`、`purpose=use`、正的 `duration_seconds`，不指定 `item_id` | 在曲柄处空手重复原生使用并观察其转动；超载和生产结果单独报告 |
| 在已放置的告示牌上写字 | `interact`、`purpose=write`、`text`，不指定 `item_id` | 空手右键打开原版告示牌编辑屏，逐行清旧填新后按原版 Done 提交；确认以告示牌真实文字与提交内容逐行一致为准，`sign_write` 报告命中的正面或背面 |

`interact_at`、`interact_entity` 是 [内部工具桥接](../../common/src/main/java/org/maiwithu/maicraft/core/tools/interact/InteractAtTool.java)，不要把其 `button`、`hold_ticks`、运行期 `entity_id` 或槽位字段放进公开目标。攻击和拆除分别使用战斗、采矿能力；容器存取使用相应容器能力。

## 请求层级和参数

`goal.ability` 选择入口，`goal.outcome` 描述玩家目的，`goal.target` 选择地点或对象，动作参数放在 `goal.parameters`。`goal.preferences` 不是本页参数的备用位置。不能用 `outcome` 的一句“定点”代替结构化坐标，也不要把坐标塞进 `parameters.x/y/z`。

先看 [SemanticAbilityCatalog](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java) 的两个分支，再对照 [GeneralAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/GeneralAbilityAdapter.java) 的解析。契约校验会拒绝未知字段和不支持的 `target.kind`，但并不统一严格检查每个字段的值类型。

### `goal.target`

| 字段或形式 | 当前含义与边界 |
| --- | --- |
| `interact` 省略目标 | 有 `block_id` 时围绕玩家搜索；有实体筛选时搜索加载的实体；两类都没有则返回待决策 |
| `interact` 的 `kind` | 公开接受 `coordinates`、`entity`、`player`、`nearest`、`landmark`、`area`；不要显式填未声明的 `current_place`，需要玩家附近时可省略目标 |
| `use_item` 省略目标或 `kind=current_place` | 沿当前视线使用物品；本身不选择空间落点，满桶也可能对当前视线命中的位置产生实际副作用 |
| `use_item` 的 `kind=coordinates` | 一次定点使用；不支持实体目标、批次或副手原料准备 |
| `target.position` | 对象，含整数 `x/y/z`，单位为方块格；三轴一起提供。可带 `dimension` 注册 ID，省略或 `null` 按当前维度，异维度坐标不在本地同编号位置执行 |
| 方块坐标 | 精确使用该格，不搜索替代物。普通方块目标需存在；满桶的落格可以为空。指定 `parameters.block_id` 时同时核对当前方块类型 |
| `kind=nearest` | 允许从本轮加载的匹配候选中选最近者；目标 `relation=nearest` 或参数 `selection=nearest` 也能表达同一选择许可 |
| `kind=landmark/area` | 非坐标方块查找的搜索中心可来自目标位置或已记住的同维度 `label`；名称不能解析时待决策，不自动改为玩家附近。这里的 `area` 不是任意多边形施工边界 |
| `kind=entity` 的 `label` | 能解析为已注册实体类型时作为类型，否则按显示名匹配；`kind=player` 的 `label` 可作玩家名 |

方块类型、精确方块坐标不能与实体筛选混用。多个实体筛选条件取交集；命名实体并不免除范围、存活与实际交互距离检查。方块和实体查找都不主动探索未加载世界。

### `interact` 的 `goal.parameters`

| 字段 | 类型、默认与执行含义 |
| --- | --- |
| `block_id` | 已注册命名空间方块 ID。非坐标方块搜索必需；坐标请求可省略，提供时要求该格仍是该类型 |
| `entity_type_id` | 可选实体类型 ID；不是临时运行编号。不填时可用名字或羊属性筛选 |
| `entity_name` / `player_name` | 可选非空字符串；按显示名 / 玩家档案名不区分大小写匹配。名字不一定唯一，仍可能需要 `selection=nearest` |
| `item_id` | 可选已注册物品 ID；显式指定的物品须在随身库存中。省略、`null` 或空白会走空手准备；`purpose=till` 且未给物品是自动准备锄的例外 |
| `item_resource_id` | 可选字符串，仅方块交互且必须同时提供 `item_id`；复制当前观察给出的组件身份，内部记录要求非空且不超过 512 字符。不适用于实体或 `use_item`；身份消失或组件变化后不会换用同名其他工件 |
| `purpose` | 可选字符串，不是严格枚举。`till` 会准备锄并要求目标变耕地；`attack/break` 返回改用战斗/采矿的决策；正时长要求 `use`。`open/talk/trade` 等其他值仍只是普通右键，不完成购买或菜单内操作。`write` 是唯一被强校验的值：要求方块目标、不点名 `item_id` 且 `text` 必填 |
| `text` | 仅 `purpose=write`：调用方显式提供的告示牌文字，角色不自拟、不做语义审查。按换行拆行，至多 4 行，计划期行长上限 64 字符；原版按约 90 像素行宽校验，超宽字符会被编辑屏静默丢弃，请把每行控制在约 18 个半角或 10 个全角字符以内 |
| `duration_seconds` | JSON 数字，秒，范围 0～30；省略和 0 都是一次交互。正值仅支持空手、`purpose=use` 的已加载原生 Create 手摇曲柄，换算为 `ceil(秒×20)` 游戏刻。`null`、布尔、数字字符串及越界值拒绝 |
| `selection` | 字符串；只有不区分大小写的 `nearest` 开启任意最近匹配。省略时多个候选待决策；其他值不形成独立策略，也不能覆盖 `target.kind/relation=nearest` 已给出的许可 |
| `radius` | 推荐整数，单位格，4～128。非坐标方块搜索默认 64，实体默认 48；解析为 `int` 后夹到 4～128，0 因而变为 4，省略、`null` 或解析失败取对应默认。底层 `getAsInt` 可能接受数字字符串、截断小数或转换过大数值，调用方应传范围内整数；坐标请求不使用它 |
| `may_alter_terrain` | 布尔，省略、`null`、`false` 默认不准为方块接近路线改地形；`true` 允许在普通路线失败后尝试地形准备。实体分支当前未传递这个参数，不能据此承诺实体导航会开路 |
| `sheep_color` | 可选原版染料名：`white/orange/magenta/light_blue/yellow/lime/pink/gray/light_gray/cyan/purple/blue/brown/green/red/black`；任一羊属性使候选限定为羊。省略不筛颜色，`null`、空串、错拼拒绝 |
| `sheep_baby` | 可选严格布尔；省略不筛年龄，`false` 为成年，`true` 为幼年；`null`、0 和字符串拒绝 |
| `sheep_sheared` | 可选严格布尔；省略不筛剪毛状态，`false` 要求有毛，`true` 要求已剪毛；`null`、0 和字符串拒绝。靠近期间再次检查，变化后不换另一只羊 |

方块搜索围绕中心做加载索引查询并按水平距离筛选；实体搜索使用玩家包围盒向各轴扩张 `radius` 的区域，再按身体距离排序，候选筛选阶段没有视线检查。实际操作前才核对可见性和准星。两种“半径”不要解释成完全相同的球形搜索。

### `use_item` 的 `goal.parameters`

| 字段 | 类型、默认与执行含义 |
| --- | --- |
| `item_id` | 必需的已注册随身物品 ID。省略、`null` 或空白会要求补充物品，不自动沿用当前手持物。标称原生持用时长超过 1200 游戏刻的物品要求改用专门活动；这是物品时长检查，不是所有任务的统一超时 |
| `block_id` | 可选原方块类型，仅坐标分支读取。向空气格倒桶通常省略它；在 `current_place` 提供此字段目前不会选中或核对方块 |
| `may_alter_terrain` | 布尔，仅坐标分支用于接近路线；省略、`null`、`false` 均不授权地形准备。在 `current_place` 中没有路线可供此字段控制 |
| `count` | JSON 数字且数值必须精确为整数 1～64，省略默认 1；0、`null`、`false`、非整数数值、数字字符串或越界值拒绝。批次按新增产物件数计，不按动作次数；坐标目标必须是 1 |
| `ingredient_item_id` | 可选已注册随身原料 ID；非空时进入批次准备，把原料放副手、工具放主手。省略、`null`、空白不要求准备副手；即使 `count=1`，提供原料也要求产物 ID。坐标目标不能提供原料 |
| `expected_output_item_id` | 可选已注册非空气物品 ID；单次使用可省略，`count>1` 或提供原料时必需。声明后每次完成要观察同类型库存增加；`null`/空白相当于未提供，在必需场景会拒绝。定点倒桶可以用空桶作为返还物 |

批次的工具、原料（若有）和产物必须是不同物品类型。没有副手原料时，单次持用仍可优先使用已经握在副手的目标物品。补料、换工具只从随身库存拿下一件，不自动采集、合成或从仓库取货。

`interact` 没有公开的 `expected_output_item_id`；需要单次返还物数量检查时使用 `use_item`。`use_item` 没有 `item_resource_id`，不能据此保证使用某个特定组件变体。

## 完整 `plan` 请求示例

下列 JSON 是 `plan` 的完整参数对象；`execute` 也可使用同一 `goal` 直接执行。示例坐标用于说明层级，提交前替换为同世界的实际观察坐标；Create 示例还要求当前实例确实装有该模组和原生曲柄。

### 对准门框嵌眼

```json
{
  "goal": {
    "ability": "maicraft:use_item",
    "outcome": "给指定空门框嵌入一颗末影之眼",
    "target": {"kind": "coordinates", "position": {"x": 10, "y": 64, "z": 10, "dimension": "minecraft:overworld"}},
    "parameters": {"item_id": "minecraft:ender_eye", "block_id": "minecraft:end_portal_frame"}
  }
}
```

这里使用单框，不要求完整传送门环。对同一已嵌眼框再次使用可能没有效果；用 `target_observation.after.properties.eye` 判断这一格，不把动作完成等同于开门成功。

### 往指定空格倒熔岩并观察返桶

```json
{
  "goal": {
    "ability": "maicraft:use_item",
    "outcome": "把熔岩倒入指定坑格并观察空桶返还",
    "target": {"kind": "coordinates", "position": {"x": 12, "y": 63, "z": 10, "dimension": "minecraft:overworld"}},
    "parameters": {"item_id": "minecraft:lava_bucket", "expected_output_item_id": "minecraft:bucket", "may_alter_terrain": false}
  }
}
```

不把支撑块坐标当成落格，也不为了允许空气落格传内部点击面。未提供 `block_id` 的满桶请求不会锁死原来的空气快照；流体相遇后读实际产物与返桶，不能为了得到原计划材料自动重倒。

### 在附近选一个工作台，用空手交互

```json
{
  "goal": {
    "ability": "maicraft:interact",
    "outcome": "打开附近最近的工作台",
    "target": {"kind": "nearest"},
    "parameters": {"block_id": "minecraft:crafting_table", "selection": "nearest", "radius": 32, "purpose": "open"}
  }
}
```

这是普通使用，不是合成目标。`interact` 未开启专用容器的菜单等待标记；需要确认具体页面时读实际菜单或用对应的菜单/容器入口。

### 给最近的成年白羊剪毛

```json
{
  "goal": {
    "ability": "maicraft:interact",
    "outcome": "给最近的成年且有毛的白羊剪毛",
    "parameters": {"entity_type_id": "minecraft:sheep", "sheep_color": "white", "sheep_baby": false, "sheep_sheared": false, "item_id": "minecraft:shears", "selection": "nearest", "radius": 32}
  }
}
```

成功后的掉落统计和库存交付是两回事；若目标是把羊毛拿到背包，随后还需按真实掉落引用收集，或从一开始选择取物能力。

### 持续空手摇曲柄

```json
{
  "goal": {
    "ability": "maicraft:interact",
    "outcome": "在曲柄处空手摇动十秒并观察实际转动",
    "target": {"kind": "coordinates", "position": {"x": 15, "y": 64, "z": 10, "dimension": "minecraft:overworld"}},
    "parameters": {"block_id": "create:hand_crank", "purpose": "use", "duration_seconds": 10}
  }
}
```

持续时间在建立交互时开始，不包含走近。发电、轴是否真正转动、是否超载和机器是否生产分开报告；`duration_seconds=0` 仅做一次，不等待十秒。

### 在指定告示牌上写两行仓库标注

```json
{
  "goal": {
    "ability": "maicraft:interact",
    "outcome": "在坐标处的告示牌上写两行仓库标注",
    "target": {"kind": "coordinates", "position": {"x": 18, "y": 64, "z": 12, "dimension": "minecraft:overworld"}},
    "parameters": {"purpose": "write", "text": "仓库 A
只放铁锭"}
  }
}
```

角色面对告示牌的一侧是写入侧；编辑屏关闭后 Mod 等待告示牌真实文字与提交四行逐行一致才算成功，回执 `sign_write.verified` 与 `verified_side` 说明对账结果。该侧已含样式或命令文字、告示牌被蜡封或他人正在编辑时编辑屏不会打开，按失败回执如实报告两侧现有文字。放置告示牌本身仍是 `place_block`/`build` 的单一意图，「放置 + 写字」用 `sequence` 串两个目标；放置流程遗留的编辑屏会由写字任务先按原版 Done 退出再重新打开。

### 不带坐标的批次：喝蜜瓶并累计新增玻璃瓶

```json
{
  "goal": {
    "ability": "maicraft:use_item",
    "outcome": "使用随身蜜瓶，累计获得三只新增玻璃瓶",
    "target": {"kind": "current_place"},
    "parameters": {"item_id": "minecraft:honey_bottle", "count": 3, "expected_output_item_id": "minecraft:glass_bottle"}
  }
}
```

例子要求角色带有足够蜜瓶，喝瓶和背包改变都是实际副作用。若原生物品一次产出多件，完成数量可以超过请求数，不存在“最后一次只执行部分原生动作”的承诺。

## 从请求到结果的代码地图

| 想看哪一步 | 打开哪里 | 核对什么 |
| --- | --- | --- |
| 公开能力和参数层级 | [SemanticAbilityCatalog](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java) 的 `INTERACT` / `USE_ITEM` 分支；[SemanticGoalContract](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java) | 字段名、目标种类、哪些值交给适配器检查 |
| 物品直接用、批次用或定点用 | [GeneralAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/GeneralAbilityAdapter.java) 的 `useItem` | 必带物品、物品原生时长、严格数量、坐标与原料互斥、输出要求 |
| 方块还是实体、多个候选怎么办 | 同文件 `interact`、`interactBlock`、`findEntities`、`nearest`、`semanticCenter` | 默认半径、同维度中心、候选歧义和就近许可；实体筛选本身不查视线 |
| 为什么只操作这个格 | 同文件 `interactExactBlock`、`compileBlockInteraction` | 加载、方块类型、满桶空气目标、默认旧快照保护与显式 `block_id` 的区别 |
| 公开参数如何进入动作单 | [InteractAtTool](../../common/src/main/java/org/maiwithu/maicraft/core/tools/interact/InteractAtTool.java)、[InteractEntityTool](../../common/src/main/java/org/maiwithu/maicraft/core/tools/interact/InteractEntityTool.java)、[InteractAtTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/interact/InteractAtTaskRecord.java) | 空手、组件身份、自动接近和返还物要求是否传到底层 |
| 角色怎么走近、换位和对准 | [InteractAtCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/interact/InteractAtCompanionTask.java) 的 `buildNav`、`handleNavFailure`、`reached`、`act` | 现有路线优先、授权后准备地形、到达与真实可见性分别判断、只在未提交时换位 |
| 为什么桶落在这里 | [FirstPersonInteractionTargeting](../../common/src/main/java/org/maiwithu/maicraft/core/act/FirstPersonInteractionTargeting.java) 的 `visibleBucketHit`、`bucketRay`、`acceptsBucketHit` | 空桶命中源流体；满桶忽略流体遮挡并核对邻接实际落格，不把预测命中当最终命中 |
| 何时真的发出一次动作 | [Interaction](../../common/src/main/java/org/maiwithu/maicraft/core/act/Interaction.java) 的 `useAir`、`fireUseBlock`、`fireUseEntity`、`settleUseReceipt`；[DefaultNativeActionPort](../../common/src/main/java/org/maiwithu/maicraft/client/actor/DefaultNativeActionPort.java) | 原生物品使用与方块/实体使用的区别；确认、明确未生效与超时未知 |
| 持用后怎样核对返还物 | `InteractAtCompanionTask.useHeldItem`、`verifyExpectedOutput`；[PressReceipt](../../common/src/main/java/org/maiwithu/maicraft/core/act/PressReceipt.java) | 先记库存，再使用，再等返回；快照差异与原生确认不是同一种证据 |
| 实体移动、变色或消失后怎么办 | [InteractEntityCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/interact/InteractEntityCompanionTask.java) 的 `reached`、`act`、`finishInteraction`；[SheepTraits](../../common/src/main/java/org/maiwithu/maicraft/core/task/entity/SheepTraits.java) | 始终跟随同一运行实体；剪毛后只结算一次；消失分支的已知成功判据缺口 |
| 批次何时补料、计数和停下 | [UseItemBatchCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/interact/UseItemBatchCompanionTask.java)、[UseItemBatchTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/interact/UseItemBatchTaskRecord.java) | 工具/原料/产物互异、每步库存增量、次数与件数、暂停后不盲目续作 |
| 曲柄的转动证据 | [CreateManualInput](../../common/src/main/java/org/maiwithu/maicraft/core/integration/create/CreateManualInput.java) | 时间解析、真实方块实体、使用序号与转动同步、运行期间的超载观察 |
| 任务如何暂停、保存和恢复 | [任务与身体控制](tasks.md)、[AbstractCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/AbstractCompanionTask.java)、[IntentRuntime](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) | 身体输入释放、已发生副作用、死亡决定、旧世界对象与持久任务记录的区别 |

### 定点方块和桶的主流程

1. 适配器先核对随身物品和当前加载的目标。精确坐标不经过附近搜索；非坐标请求等待索引完成，有歧义时先交回决策。
2. 执行器寻找同时可站、可见且能触及的候选。先走现有路；方块请求允许改地形时，普通路线失败后才尝试地形准备。原目标不会因某个站位失败而被换成别的方块。
3. 选定物品或准备空手，等待原生背包交换与页面收尾；组件敏感工件会再次核对身份。到达导航格后仍检查真实眼位，不保证格心视线就是实际准星。
4. 满桶按目标格寻找支撑面，空桶使用源流体射线，普通方块使用命中该方块的射线。镜头收敛后再核对真实命中；未提交时可以有限换位，不能把未确认的动作当作没发生而重发。
5. 建立前后观察，再提交物品或方块原生使用。等待同一笔回执；持续手摇到期后停止新点击，并结清已提交的最后一次。
6. 有 `expected_output_item_id` 时，额外等最多 40 游戏刻的库存同步。默认回执带实际目标状态和可见变化；它不验证完整机器功能。

方块普通交互当前采用 4.5 格距离常量；桶使用玩家的 `blockInteractionRange()`。站位搜索允许有高度差，不存在“必须先让角色与目标同 Y”的统一条件。Create 置物台、机械手等还检查各自原生交互面。

### 实体与批次的差别

实体请求先选定一个加载且存活的实体，内部用运行编号跟随，不会在目标丢失时悄悄改找最近另一只。任务检查约 3 格距离、视线与真实实体准星；第一次部分导航失败可放宽到目标周围 2.5 格重新选站位，但动作距离要求不随之放宽。不能被原生准星选中的掉落物不适用此入口。

批次不寻找空间目标。提供原料时先装备副手原料、再装备主手工具；随后运行一次持用子任务。只有子任务成功、`native_use_completed=true` 且输出库存增量为正，才累加件数和次数。工具或原料耗尽即停止；没有仓库补料、配方推断或自动恢复原批次的分支。

## 看回执，而不只看 `success`

`execute` 返回接单结果和 `next_attention`，不等于游戏操作已经完成。按返回的 Attention 参数等待；遇到不确定动作先查原任务和现场，网络重试应复用原 `request_key`，不要另开任务重放点击。

| 回执事实 | 可以说明什么 | 不能据此推断什么 |
| --- | --- | --- |
| 方块/持用的 `native_action_kind/status`、`submission_attempted` | 哪类原生请求已进入执行器，是否确认、未生效或未知 | 一个确认动作不保证满足 `outcome` 中所有自然语言要求；字段缺失也不等于未提交 |
| `target_observation` | 指定格的坐标、可读性与前后 `block_id/properties`，例如 `eye=false→true` 或水变黑曜石 | 客户端快照不是独立的服务端操作归因；未加载后的 `unknown` 不是空气 |
| `changes` | 双手、目标格、新实体和菜单等可见变化 | 全部变化不一定都由这次操作引起；空列表不能独立证明原生请求未执行 |
| `expected_output` | 指定物品类型的 `before/after/observed_increase` | 不是按组件、掉落来源或加工交易严格归因；与实际配方结果要合并判断 |
| `held_item_use_started/native_use_completed/used_hand` | 无坐标单次持用的推进与结束状态 | 持用结束本身不说明某个方块被正确操作 |
| `interaction_approach`、`post_expiry_facts` | 寻路尝试、换位、实际脚位、拒绝下降及超时时的目标/手持快照 | 未提交前的站位失败不能解释成倒桶已失败；没有观察的状态不能填零 |
| `manual_generator` | 已确认使用次数、原生转动、当前或过程中超载等 | `machine_production_verified=false`，手摇成功不代表整台机器有产出 |
| `sign_write`（`submitted`/`verified`/`verified_side`/`editor_lines_before`） | 写字目标提交了哪四行、告示牌真实文字是否逐行对上、命中正面还是背面、写字前的旧行 | 编辑屏由原版提交更新包，客户端文字与提交一致即是确认条件本身；`verified=false` 不区分「编辑屏没打开」与「文字没对上」，要读失败消息与 `observed_at_fail` |
| 实体的羊属性、`attributed_shearing_drop_count` | 当下筛选事实与归属到剪毛动作的新掉落数 | 不等于掉落已进包；实体回执目前没有完整透传原生状态 |
| 批次的 `completed_output_count/completed_uses/remaining_output_count`、`last_step` | 已接受的每步增量、已完成次数、剩余目标及最后一步 | `count` 不是点击次数；成功一次多产时实际件数可超目标；不要直接重跑原 `count` |

普通 `interact` 即使 `purpose=open` 也不保证返回专用容器入口的 `menu_open_verified`。原生拒绝、确认超时、选物失败、目标变化和缺材料需分别处理；通用恢复入口是读取 `task` / Attention 给出的事实与决策，而不是固定“失败就再右键一次”。

## 暂停、取消、死亡与换世界

| 事件 | 当前行为与维护要求 |
| --- | --- |
| 单次方块/实体任务暂停或抢占 | 公共层暂停导航并释放身体输入，任务对象保留已有阶段；恢复不自动成为一次全新点击。原生已提交动作可能继续在世界中结算，仍需检查其回执 |
| 批量用物品暂停或抢占 | `UseItemBatchCompanionTask.stop` 对所有停止原因设置 `interrupted`；恢复后的下一刻失败收场，要求查看已完成产物和最后一步。不能描述成可无缝续做 |
| 取消或超时 | 任务收尾释放选物、持用和导航；倒出的流体、消耗的眼、产物和世界反应不回滚。批次结算当前子任务并保留最后一步，不把未确认的一次算进完成数量 |
| 角色死亡 | 运行时保存任务进度并提出死亡恢复决定，不由交互任务自动复活。复活替换玩家对象后，未完成任务以暂停状态重新绑定，旧准星、路线或实体编号不能当新身体的有效现场 |
| 断线、换存档或普通换世界 | 旧身体与临时动作撤销，保存对应世界的任务检查点；这两个能力不承担传送门跨维度交接。不要把另一世界同坐标的方块当作旧目标 |
| 从磁盘恢复 | 保存的是语义目标、步骤、回执和问题，不是可恢复的 Java `Interaction` 对象。先读已完成与未知效果，再决定继续剩余目标；库存、组件身份、实体和加载状态均可能已变化 |

恢复选项以当前任务提供的决策为准；没有明确重试许可时，应先作最小相关观察。批次回执固定 `mechanical_retry_allowed=false`，需要重新计算剩余产量。

## 已知边界与行为差异

以下边界来自当前执行路径，不应在能力说明中写成更强的保证：

- **实体的地形许可未接线（INT-01）**：`interact` 的实体分支不传 `may_alter_terrain`，内部记录也没有对应字段；不能承诺该参数改变实体导航。
- **实体消失可能过早成功（INT-02）**：`act` 在开始推进交互时便设置 `acted=true`；目标随后消失会按这个标记返回成功，没有再次核对原生确认或消失归因。
- **实体原生证据缺口（INT-03）**：`resultData` 没有透传 `Interaction.useEvidence()`，因此不可承诺实体回执始终包含 `native_action_status/outcome_uncertain`。
- **无坐标字段被忽略（INT-04）**：`use_item(current_place)` 接受声明过的 `block_id/may_alter_terrain` 字段，但这条分支不读取它们；必须使用坐标目标表达定点。
- **产物确认不是严格归因（INT-05）**：单步返还物检查比较物品类型总数；同时拾取同类型物品可能满足增量条件，组件和来源没有独立核实。批次累计的是这些单步增量。
- **批次中断不能无缝继续（INT-06）**：所有 `stop` 原因都锁住批次；这是当前恢复边界，不应只凭普通任务保留逻辑进度便承诺持续加工。
- **站位说明须按真实几何解释（INT-07）**：“先站到与目标同高度”不是当前执行条件，候选会比较实际射线和可达性，不能把同高写成统一前提。

没有附加服务端模组也不能绕过服务器权限；这两个入口以本地玩家的原生交互为基础。原版桶、眼、按钮不要求 Create；曲柄、置物台、机械手、砂纸等依赖安装模组提供的原生机制与同步。不同模组物品的行为不能由 `item_id` 名字推断。

## 已有验证入口

| 要检查的行为 | 现有入口 |
| --- | --- |
| 精确坐标、旧方块变化、空桶源筛选、无坐标批次适配 | [ExactInteractionTargetTest](../../common/src/test/java/org/maiwithu/maicraft/intent/ExactInteractionTargetTest.java) |
| 两种公开入口的空格倒桶、返还物、流体变化和定点嵌眼 | [TargetedItemUseTest](../../common/src/test/java/org/maiwithu/maicraft/intent/TargetedItemUseTest.java)，由前一入口调用 |
| 批次双手准备、数量与中断 | [UseItemBatchTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/UseItemBatchTest.java) |
| 羊的筛选与剪毛观察 | [SheepTraitsTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/SheepTraitsTest.java)、[ShearingDropReceiptTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/ShearingDropReceiptTest.java) |
| 曲柄时间与转动证据 | [CreateManualInputTest](../../common/src/test/java/org/maiwithu/maicraft/core/integration/create/CreateManualInputTest.java) |
| 菜单、动作和收尾的组合回归 | [GuiRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/client/actor/GuiRegressionSuite.java)，Gradle 任务 `:common:guiRegression` |

离线夹具通过不等于整合包或多人服实机通过。验证记录需区分源码静态核对、回归执行与实机场景，并注明使用的构建、模组环境和实际回执；JSON 与链接检查不能代替原生操作验收。
