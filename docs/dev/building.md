# 建筑设计、预览与施工

玩家说“先看看这栋房子”，调用 `maicraft:design_build`；玩家已授权在指定位置建造，调用 `maicraft:build`。两者都需要作者模型、逐格蓝图或已保存的工程编号。只放一个随身方块时调用 `maicraft:place_block`，不需要设计来源，见下文专节。自然语言 `outcome` 只说明目的，不会让 Mod 自行设计房屋、调整尺寸或寻找另一块地。

LLM 负责确定形状、材质和修改方案。Mod 把设计转换为目标格，使用角色的原生移动、挖掘、持物、放置和菜单操作执行，再报告现场事实。设计保存、预览建立、原生动作确认和建筑满足要求要分别判断。本文按当前源码说明已接通的行为；与仓库最新原生施工规则尚未一致的部分集中列在文末。

## 单格放置：maicraft:place_block

只放一个随身方块时不需要设计来源：目标只接受 `goal.target.kind:"coordinates"` 的精确坐标，参数只有 `block_id`（必填，已注册方块）、`properties`（可选对象，声明的状态取值是终态要求，规则与逐格蓝图的 `properties` 相同）和 `replace_existing`（默认 `false`）。材料必须随身携带：适配器钉死 `inventory_only`，缺料如实失败并点名物品，不会发起取料。

适配器把请求合成为一份恰好一格的逐格蓝图（锚点即坐标、偏移 `[0,0,0]`），再交给与 `maicraft:build` 完全相同的施工链路；本文“从场地到原生回执”的备料、清障、放置与核验各节同样适用。执行层没有第二条放置路径，施工链路的改进自动惠及单格放置。

| 提交 | 行为 |
| --- | --- |
| 目标格未加载 | 暂停并要求先观察或加载；不就近替换，缺席不等于不存在 |
| `block_id` 不是已注册方块 | 拒绝；实体 ID（展示框、盔甲架、画）会得到如实说明：它们不是方块，LLM 蓝图不安装实体，摆设只随结构文件导入落地 |
| 混入 `scene`、`blueprint`、`operation` 等 build 参数 | 计划期被未知参数白名单拒绝；绕过计划直接执行时决策指回 `maicraft:build` |
| 目标格被占用且未授权 | 施工 `PREFLIGHT` 拒绝并携带格证据；`replace_existing:true` 按替换策略执行 |
| 声明的状态取值当前游戏无法表达 | 翻译层拒绝，不偷偷放上默认状态 |

成功证据与 build 相同：目标格持有声明方块与状态、背包对应物品减少，`VERIFY` 终态核对通过；只看角色动作或画面变化不算完成。告示牌文字、讲台放书等放置后的 BlockEntity 内容不在放置语义内，目前也没有任何能力入口（只有结构文件导入路径会搬运告示牌文字这类装饰数据）；需要多格或布局设计时用 `maicraft:build`。

## 先确定需要哪一种操作

`plan` 的新请求使用 `{"goal":{...}}`。能力名放在 `goal.ability`，操作和设计来源放在 `goal.parameters`，地点放在 `goal.target`。`plan` 校验并保存目标，不执行编译后的身体动作；拿到实际 `plan_id` 后再 `execute`。也可通过 `execute` 的 `goal` 入口直接执行，同一次网络重试复用实际 `request_key`，不要重复创建施工任务。

| 场景 | 能力与 `goal.parameters.operation` | 必要来源 | 实际效果 |
| --- | --- | --- | --- |
| 保存一份可编辑设计 | 两能力均可，`create_scene` | `scene` 和地点 | 完整编译、验证方块注册名和状态，保存新 `scene_id`；不走路 |
| 修改设计 | 两能力均可，`update_scene` | `scene_id`、`edits` | 保存带父版本编号的新场景，原版本及进行中的工程不随之改变 |
| 看场景源对象 | `get_scene_info` | `scene_id` | 返回源对象/组件及分页信息 |
| 看一个对象或实例路径 | `get_object_info` | `scene_id`、`object_name` | 返回几何、材质、范围等作者模型信息；不是世界勘测 |
| 看组件定义 | `get_component_info` | v2 的 `scene_id`、`component_name` | 返回具名组件及其局部展开示例 |
| 显示设计 | `preview` | `scene`、`scene_id`、`blueprint` 三选一 | 建立只读预览；新 `scene` 也会先保存 |
| 导出设计 | `export_scene` | `scene_id` | 写入实例的 `schematics/maicraft-scene-<scene_id>.json` 或 `.nbt` |
| 采用设计修订 | `revise_project` | `scene_id`、`project_id` | 更新已有工程的冻结要求；不开始施工 |
| 实际建造 | 仅 `maicraft:build` 的 `build` | `scene`、`scene_id`、`blueprint` 三选一 | 创建施工单，必要时分批供料，执行原生动作 |
| 预览冻结工程 | `maicraft:design_build`，省略 `operation` | 仅 `project_id` | 展开已保存目标并显示，不创建身体任务 |
| 续建冻结工程 | `maicraft:build`，省略 `operation` | 仅 `project_id` | 读取同世界、同维度的绝对目标与材料，重新核对现场后续作 |

省略 `operation` 时，`design_build` 默认 `preview`，`build` 默认 `build`。`design_build` 显式提交 `operation:"build"` 会被拒绝；它仍可以保存设计、导出文件和修订工程，因此“只读”指不执行世界中的身体操作，不代表不写资料。

设计操作可独立执行，不替换正在运行的身体任务。`revise_project` 额外要求当前身体任务已终态；先结束当前施工，再采用修订，之后另行续建。设计预览不要求开启 Dev，按本地 confirm 也不会开工。Dev 开启时，真正施工的预览需要玩家确认一次，各供料批次复用这次决定。

## 参数放在哪里

### 地点与来源

| 字段 | 形状与含义 |
| --- | --- |
| `goal.target.kind:"current_place"` | 新模型以执行时角色的整数脚位为锚点，不是 `plan` 时锁定的脚位 |
| `goal.target.kind:"coordinates"` | 使用 `goal.target.position:{x,y,z,dimension?}`；三个坐标是世界方块整数坐标，零和负数合法；省略维度使用当前维度，不会自动跨维度 |
| `goal.target.kind:"landmark"` / `"area"` | 用 `goal.target.label` 读取已记住地点的位置；缺失或异维度时不猜坐标，也不改用角色附近 |
| `goal.target.kind:"prior_result"` | 顺序任务中，由 `IntentTask.resolvedCurrentGoal` 将可确认的前序结果转为坐标；独立设计请求没有可凭空解析的前序位置 |
| `goal.parameters.scene_id` | UUID 字符串；保存的场景固定世界、维度和锚点。建议省略 `target`；若仍提供地点，必须解析为原锚点 |
| `goal.parameters.project_id` | UUID 字符串；必须来自真实施工回执。保存层要求规范 UUID。续建使用原绝对目标，不接受新设计参数；不要用新的 `current_place` 冒充续建 |

除单独引用 `project_id` 的分支外，`scene`、`scene_id`、`blueprint` 必须恰好出现一个。显式 `null` 仍算字段出现，随后会因类型错误被拒绝。`scene_uri`、`blueprint_uri` 是结果中的资源地址，不能直接作为这两个能力的参数名；读出内容后使用相应来源字段，或复用 `scene_id`。

### `goal.parameters` 的顶层字段

| 字段 | 类型、默认值及有效范围 | 限制与省略语义 |
| --- | --- | --- |
| `operation` | 非空字符串，九种取值见上表 | 默认随能力不同；`null`、空字符串、未知操作无效 |
| `scene` | 对象 | 作者模型，见下节；新模型需要地点 |
| `scene_id` | 非空 UUID 字符串 | 编辑、查询、导出、采用修订必须提供 |
| `blueprint` | 对象 | 普通方块逐格目标；与两种 scene 来源互斥 |
| `project_id` | 非空 UUID 字符串 | 单独续建/预览，或与 `operation:"revise_project"`、`scene_id` 合用；其他模型操作不接收 |
| `edits` | 非空对象 | 仅 `update_scene`，且该操作必填；`{}`/`null` 无效，其他操作带此字段会被拒绝 |
| `object_name` | 非空字符串，最多 256 字符 | 仅 `get_object_info`，匹配源对象名或实际查询返回的展开路径 |
| `component_name` | 非空字符串，最多 256 字符 | 仅 `get_component_info`，匹配组件定义名，不是实例名 |
| `page` | 整数 `0..1024`，默认 `0` | 仅三个查询操作；`0` 是第一页，`null` 无效；当前校验也拒绝 `1.0` 这种带小数标度的写法 |
| `format` | `"json"` / `"nbt"`，默认 `"json"` | 仅导出可用；JSON 保留负偏移，NBT 将最小角移到零并回报 `minecraft_offset` |
| `material_policy` | `specified`（默认）、`ordinary`、`storage_available`、`inventory_only` | `specified` 转成 `ordinary` 取材策略；所有模式保留作者指定材料。`inventory_only` 不自动补取；其他模式仍受供料源与库存实际条件限制 |
| `replace_existing` | 布尔，普通模型入口默认 `false` | 当前 `false`/省略保留占用格，`true` 允许原生拆换普通障碍；零、字符串和 `null` 无效。该旧开关与最新默认授权规则的差异见文末 |
| `protected_labels` | 非空字符串组成的数组 | 省略/`[]` 不增加本步骤标签，仍保留继承保护；`null`、空标签无效。保护依赖已记住且已测得的范围，不凭名称猜出保护盒 |
| `expected_capability_revision` | 从 `maicraft://building/index` 取得的非空字符串 | 可独立省略；出现时检查当前编译语义、模型格式、能力及设计预算版本，随后也检查保存场景的校验凭据 |
| `expected_design_schema_revision` | 同一索引返回的非空字符串 | 可独立省略；出现时必须匹配完整设计 JSON Schema 指纹，不能自行拼造哈希 |

除来源对象自身的限制外，建模操作使用的顶层字符串校验上限为 256 字符。查询和预览带取材/替换参数也不会产生对应动作；`revise_project` 更严格，只允许操作、两个编号及两个可选版本凭据，且不能带 `target`。单独 `project_id` 分支不能附模型版本检查字段，使用冻结目标重新核对。

这些参数属于 `goal.parameters`，不能只写在 `outcome` 或 `preferences` 中。尺寸、风格、房间、屋顶和选材需要落实到模型；旧模板字段 `purpose`、`size`、`style`、`features` 等不会生成建筑。

内部 `BuildTool` 使用的 `ops`、`exact_states`、`allow_partial`、`project_targets`、`broaden_material_families` 不是这两个公开能力的参数；不要把内部动作单直接复制到 `goal.parameters`。

### 作者模型的嵌套层级

开始写复杂模型前读取 `maicraft://building/index`，再读取它返回的 `design_schema_uri`。完整字段形状由 [BuildingModelJsonSchema](../../common/src/main/java/org/maiwithu/maicraft/core/blueprint/BuildingModelJsonSchema.java) 导出，运行时还检查引用、几何、注册表状态与总体预算。编辑格式位于同一 Schema 的 `$defs/scene_edits`。

| 层级 | 必填项、默认值和单位 |
| --- | --- |
| `scene.schema_version` | 省略或 `1` 走旧 cube/panel 规则；明确写 `2` 才使用组件、图案和更多图元；`0`/`null` 不合法 |
| `scene.name` | 可选非空字符串，最多 128 字符；查询时省略显示 `Scene` |
| `scene.coordinate_system` | 默认 `blender_z_up`，点 `[x,y,z]` 映射到 Minecraft `[x,z,-y]`；`minecraft_y_up` 直接使用游戏 XYZ；一单位是一格 |
| `scene.materials.<材料名>` | 至少一种，材料名最多 64 字符；值是 `{block_id,properties?}`，注册 ID 必须存在，属性值使用字符串；`properties` 省略等于没有额外显式属性要求，不等于锁定全部默认状态 |
| `scene.objects[]` | 至少一个有名称的源节点；同一对象列表不能重名 |
| `scene.components.<组件名>.objects[]` | v2 可选；组件至少有一个源节点。只展开被根对象引用的定义；循环引用、未知组件会拒绝 |
| `scene.block_state_axes` | v2 默认 `local`，使朝向、轴与半块状态随合法旋转/镜像变化；`minecraft_world` 固定游戏方向；v1 固定世界轴 |
| `scene.overlap_policy` | v2 默认 `last_wins`，后写实体覆盖先写实体；`error` 拒绝不同材质/状态的实体重叠。孔洞和空腔不抹掉其他独立对象的实体 |
| `objects[].name` / `type` | v2 名字最多 64 字符，不含 `/`、`[`、`]`；`type` 是 `MESH` 或 `INSTANCE`，旧 `cube`/`panel` 简写仍可用 |
| `MESH.primitive` / `location` / `dimensions` | 完整网格必填；中心位置可含半格，长度是正整数三元组，变换后边界须落在整数方块面；`dimensions` 每轴 `1..2*maxRadius+1` |
| `INSTANCE.component` / `location` | 组件名必填；位置是组件原点，省略为 `[0,0,0]`；实例不接受 `dimensions`，改尺寸要改组件网格 |
| 节点 `rotation_euler` / `mirror` | 旋转是 XYZ **弧度**三元组，默认零；v2 只支持各轴四分之一周的整数倍，v1 只绕上轴；镜像是去重的轴名数组，默认空，最多三个轴 |
| 节点 `block_state_axes` | 可覆盖继承的轴规则；不合法的原生状态不会被悄悄近似化 |
| 节点 `array:{count,step,skip?}` | `count` 为三个正整数，`step` 为三个有限数，沿节点局部轴先复制再变换；`skip` 是零起始三元索引数组，省略无跳过。`count:0` 无效，`step:0` 可表达重叠但仍受叠加规则限制 |
| `INSTANCE.material_map` | 可选材料名映射；逐层应用，保持明确方块/属性，不是缺料时自动替材 |
| `MESH.material` / `role` | 实体网格需要材料名；默认 `role:"solid"`，`"cutter"` 不独立建造 |
| `MESH.modifiers[]` | 条目必须是 `{type:"BOOLEAN",operation:"DIFFERENCE",object:同列表对象名}`；切割器不再带切割器或图案，组件内部引用按实例隔离 |
| `MESH.fill` / `wall_thickness` / `open_faces` | 默认实心 `solid`；`hollow` 产生空腔，壁厚默认 1 格，可填 `0.5..2*maxRadius+1`；开口面是图元定义的面名数组，默认空 |
| `face_materials` / `edge_material` / `edge_materials` / `edge_width` | 命名面、棱材质覆盖；未点名处沿用主体材质，棱宽默认 1 格，范围同壁厚。面和棱名称来自实际图元，不随镜头方向改名 |
| `segments` | 只用于 `prism`、`cylinder`、`cone`，整数 `3..32`；默认分别为 6、16、16 |
| `vertices` / `faces` | `convex_polyhedron` 两者必填；4..64 个归一化 `[0,1]` 三元顶点，4..64 个面，每面 3..64 个顶点索引；还会验证索引、封闭性和凸性 |
| `panel.pattern:{axes,rows,materials?}` | 两个不同轴依次表示列、行；等宽 `0/1` 字符串行从局部最小角平铺，剩余轴厚度为 1；未映射 `1` 用网格材质，未映射 `0` 留孔。可映射到具名材料，省略 `materials` 即采用上述规则 |

v2 的图元包括 `cube`、`panel`、`triangle`、`wedge`、`triangular_prism`、`tetrahedron`、`triangular_pyramid`、`pyramid`、`prism`、`cylinder`、`cone`、`convex_polyhedron`。节点数量、数组展开、布尔比较和体素总工作量都计入预算，不能仅用去重后的方块数判断开销。无脚本执行、任意角度取整或无限组件递归。

默认建筑预算为 262144 个目标格（包含空气）、8192 个对象/展开节点、相对半径 512 格、作者模型 8 MiB。启动配置 `config/maicraft-building.properties` 可以改变这些值，应以能力目录和模型索引返回的实际预算为准。预览、导出、冻结工程各自还有容量限制；提高预算不等于加载区块或证明实机帧率。

### 逐格蓝图与编辑

普通 `blueprint` 的可执行内容是 `schema_version:1` 和非空 `blocks` 数组。每项为 `{"offset":[整数x,整数y,整数z],"block_id":"命名空间:方块名","properties":{"属性名":"值"}}`，`properties` 可省略，偏移使用 Minecraft XYZ，范围受 `maxRadius` 限制。零/负偏移合法，重复位置被拒绝。属性名最多 64 字符、值最多 128 字符、每格最多 32 个属性；注册表还会核对真实属性及取值。

`minecraft:air` 是明确的清空目标，未声明格保持原状。门、床等必须声明原生放置会生成的相关格子。普通建筑拒绝部件安装、非空方块 NBT、非空实体安装，以及 `constraints`、`assembly`、`expected_output`；这些属于机器能力。`metadata`/`evidence` 可作为资料，但不自动成为世界配置。当前校验还接受部分机器供料元数据而普通适配器不消费，见下文边界。

`edits.objects` 按根对象名合并字段，未点名对象保留；新增对象必须完整。`remove_objects` 按名称删除源对象，删除模型不等于拆世界里的旧方块。`edits.components` 与 `edits.materials` 按名字替换整条定义，组件的节点数组不是局部拼接。`remove_components` 删除定义，仍被引用则整次编辑失败。不能同次编辑又删除又修改一个名字。`schema_version:2` 只允许升级；v2 不降级，旧场景添加 v2 字段也不会自动升级。需要删除已建内容时，在新设计中保留该坐标并明确写空气。

`revise_project` 只接受工程当前场景的**直接子版本**，锚点和完整目标坐标集合必须相同，支撑账也必须仍与现场一致。它可以改变这些格子的要求，不能增删目标坐标或偷偷扩大施工范围。采用成功后得到原 `project_id`、新旧场景编号、改动目标数与保留支撑数；仍须执行一次仅带 `project_id` 的 `build` 才产生世界动作。

## 完整请求示例

以下均是可解析的 `plan` 请求，只使用原版注册 ID；示例本身不会执行游戏动作。提交者仍需具备对应场地的真实授权与材料条件。

先保存玩家当前脚位旁的一块石材模型。位置 `[2.5,0.5,0.5]` 是网格中心，展开为相对锚点 `[2,0,0]` 的一格。

```json
{
  "goal": {
    "ability": "maicraft:design_build",
    "outcome": "保存当前地点旁的单格石材设计，暂不开工",
    "target": {"kind": "current_place"},
    "parameters": {
      "operation": "create_scene",
      "scene": {
        "schema_version": 2,
        "coordinate_system": "minecraft_y_up",
        "materials": {"Stone": {"block_id": "minecraft:stone"}},
        "objects": [{"name": "Marker", "type": "MESH", "primitive": "cube", "location": [2.5, 0.5, 0.5], "dimensions": [1, 1, 1], "material": "Stone"}]
      }
    }
  }
}
```

只看一份逐格蓝图，省略 `operation` 会走设计预览。这里没有工程 UUID，也不会因确认预览而开工。

```json
{
  "goal": {
    "ability": "maicraft:design_build",
    "outcome": "只预览两格石台",
    "target": {"kind": "current_place"},
    "parameters": {
      "blueprint": {
        "schema_version": 1,
        "blocks": [
          {"offset": [2, 0, 0], "block_id": "minecraft:stone"},
          {"offset": [3, 0, 0], "block_id": "minecraft:stone"}
        ]
      }
    }
  }
}
```

玩家已授权在当前地点旁铺石并清出头部空间，且本次只用随身材料。为对应当前普通入口的实际旧开关，示例显式写 `replace_existing:true`；这不替代玩家已经给出的范围授权。

```json
{
  "goal": {
    "ability": "maicraft:build",
    "outcome": "在当前地点旁铺一格石台并清出两格高的空间",
    "target": {"kind": "current_place"},
    "parameters": {
      "operation": "build",
      "material_policy": "inventory_only",
      "replace_existing": true,
      "protected_labels": [],
      "blueprint": {
        "schema_version": 1,
        "blocks": [
          {"offset": [2, -1, 0], "block_id": "minecraft:stone"},
          {"offset": [2, 0, 0], "block_id": "minecraft:air"},
          {"offset": [2, 1, 0], "block_id": "minecraft:air"}
        ]
      }
    }
  }
}
```

涉及旧编号时，从真实回执绑定变量，不在文档里编造可提交的 UUID。下面展示组装层级；`savedSceneId`、`savedProjectId`、`readyPlanId` 分别来自真实场景、工程和计划回执。

```javascript
const inspectRequest = {goal: {ability: "maicraft:design_build", outcome: "查看设计的第一页", parameters: {operation: "get_scene_info", scene_id: savedSceneId, page: 0}}};
const editRequest = {goal: {ability: "maicraft:design_build", outcome: "将石材标记改为两格长", parameters: {operation: "update_scene", scene_id: savedSceneId, edits: {objects: [{name: "Marker", location: [3, 0.5, 0.5], dimensions: [2, 1, 1]}]}}}};
const resumeRequest = {goal: {ability: "maicraft:build", outcome: "按冻结施工单续建", parameters: {project_id: savedProjectId}}};
const executeRequest = {plan_id: readyPlanId};
```

扩大几何的编辑可以得到新场景，但不能作为旧工程的同坐标修订；这时需要新工程。查询页码为零时也要传数字 `0`，不要用 `null` 表示第一页。

## 从场地到原生回执

1. **设计准备。** 校验来源互斥、操作专用字段及可选版本；解析固定锚点；编译模型或读取逐格目标，再验证已安装方块与属性。源模型可持久化，预览只显示设计，不主动探索未加载区块。
2. **冻结施工要求。** `BuildTool.onGameCall` 保存完整绝对目标与具体材料；场景仍保留可编辑对象。取消后不依赖旧进度百分比，工程恢复会重新读取世界。当前持久化通过 `MemoryDocuments` 进入世界范围数据库，旧 JSON 记录兼容读取。
3. **备料与开工。** 生存模式进入 `SemanticBuildSupplyCompanionTask`，绑定精确材料、检查已加载场地、等待必要的 Dev 确认，之后分批取料。缺工具、支撑材料或普通材料时，已接通的恢复分支刷新缺口并续建同一份目标；不把“一块支撑”伪装成新的整机设计。创造模式仍走原生物品选择与放置确认。
4. **清障与接近。** `PREFLIGHT` 逐格检查，未加载时只走近加载；`EXCAVATE` 从暴露层开挖，必要时先出坑再取料或存土石。声明目标格的清障不再要求自然方块白名单；普通替换开关、明确保护、方块实体策略及原生不可挖条件仍在当前代码中检查。寻路自身的清障白名单属于另一层，不扩张作者的声明范围。
5. **放置。** 按结构区域、施工层和可复用站位推进。站位依次尝试保持高度、已有落脚面和施工接近；临时点击支撑先检查后排队。选物、瞄准、原生使用、等服务端确认分阶段推进。客户端先显示方块或库存数量变化，不能单独替代原生动作回执。
6. **收尾与观察。** 核对每一格，清理确认属于本项目的临时支撑；有明确最终属性要求时尝试支持的原生调整，例如木门开关。当前通用路径仍会有限修补并可能以核验失败终态；已确认的原生落点偏移则有专门分支返回动作完成、`construction_complete:false` 和整份声明目标 diff。

同一材质仅属性不同会保留为最终状态义务，避免先把门拆掉再买一扇。`properties` 省略的属性不额外约束；不能用未写 `open` 的木门目标要求自动关门。蓝图未声明的位置不视为空气要求。水流、含水固体和原生联动应以真实效果判断：当前代码允许直接覆盖可替换流水，也允许原生挖含水固体；纯流体没有普通镐的破坏目标。

施工间隙的吃饭、备餐和等回血由 `BuildFoodPreparation` 处理。普通维护失败会结清动作、记录事实并暂缓 400 游戏刻后交回施工；死亡等身体失效另行终止。不能把“维护未达目标”与“原生建造动作未知”合并为一条失败结论。

## 想看哪一步，打开哪里

| 想追踪的玩家行为 | 入口与继续阅读 |
| --- | --- |
| 为什么参数在 plan 阶段被拒绝 | [BuildingSceneContract.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/BuildingSceneContract.java)，上层接线在 [SemanticGoalContract](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java) |
| 单格放置怎样合成普通施工单 | [GeneralAbilityAdapter.placeBlock / synthesizePlaceBuild](../../common/src/main/java/org/maiwithu/maicraft/intent/GeneralAbilityAdapter.java)、[BuildingSceneAdapter.adapt](../../common/src/main/java/org/maiwithu/maicraft/intent/BuildingSceneAdapter.java) |
| 创建、编辑、查询、导出分别做了什么 | [BuildingSceneAdapter.adapt](../../common/src/main/java/org/maiwithu/maicraft/intent/BuildingSceneAdapter.java) |
| 只读设计为什么不占身体 | [IntentRuntime.isIndependentRequest / execute](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java)、[BuildDesignAdapter.design](../../common/src/main/java/org/maiwithu/maicraft/intent/BuildDesignAdapter.java) |
| 当前脚位、地标和旧场景怎么变成锚点 | [BuildingAnchor.resolve](../../common/src/main/java/org/maiwithu/maicraft/intent/BuildingAnchor.java)、[BuildingSceneStore.load](../../common/src/main/java/org/maiwithu/maicraft/core/blueprint/BuildingSceneStore.java) |
| 模型结构、数组、孔洞和材质如何展开 | [BuildingSceneCompiler](../../common/src/main/java/org/maiwithu/maicraft/core/blueprint/BuildingSceneCompiler.java)、[BuildingModelCompiler](../../common/src/main/java/org/maiwithu/maicraft/core/blueprint/BuildingModelCompiler.java)、[BuildingModelExpansion](../../common/src/main/java/org/maiwithu/maicraft/core/blueprint/BuildingModelExpansion.java) |
| 源对象和组件查询为什么分页 | [BuildingSceneInspection](../../common/src/main/java/org/maiwithu/maicraft/core/blueprint/BuildingSceneInspection.java)、[BuildingModelInspection](../../common/src/main/java/org/maiwithu/maicraft/core/blueprint/BuildingModelInspection.java) |
| 场景版本凭据、资源正文与导出文件 | [BuildingModelContract](../../common/src/main/java/org/maiwithu/maicraft/core/blueprint/BuildingModelContract.java)、[BuildingSceneResources](../../common/src/main/java/org/maiwithu/maicraft/mcp/knowledge/BuildingSceneResources.java)、[BuildingSceneExport](../../common/src/main/java/org/maiwithu/maicraft/core/blueprint/BuildingSceneExport.java) |
| 保存工程、续建、采用同范围修订 | [BuildTool.onGameCall](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/BuildTool.java)、[BuildProjectAdapter.plan](../../common/src/main/java/org/maiwithu/maicraft/intent/BuildProjectAdapter.java)、[BuildProjectStore.reviseFromScene / bindScaffolds](../../common/src/main/java/org/maiwithu/maicraft/core/blueprint/BuildProjectStore.java) |
| 生存缺料、背包满、支撑缺料如何继续 | [SemanticBuildSupplyCompanionTask.onTick / tickChild](../../common/src/main/java/org/maiwithu/maicraft/core/task/supply/SemanticBuildSupplyCompanionTask.java)、[BuildSupplyExit](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/BuildSupplyExit.java) |
| 清障拒绝与附近选址建议 | [BuildClearanceSurvey.advance / report](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/BuildClearanceSurvey.java)、[FirstPersonBuildCompanionTask.finishPreflight / clearingPermitted](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/FirstPersonBuildCompanionTask.java) |
| 放置站位、贴边与原生确认 | [BuildStanceNavigation](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/BuildStanceNavigation.java)、[BuildPlacementAccessDrive](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/BuildPlacementAccessDrive.java)、[BuildPlacementConfirmation](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/BuildPlacementConfirmation.java) |
| 为何等手中物品与界面稳定后才点击 | [FirstPersonActionGate](../../common/src/main/java/org/maiwithu/maicraft/core/task/FirstPersonActionGate.java)、[NativeActionReceipt](../../common/src/main/java/org/maiwithu/maicraft/client/actor/NativeActionReceipt.java) |
| 收支撑、调木门、最终核验 | [BuildScaffoldLedger](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/BuildScaffoldLedger.java)、[BuildDoorStateRepair](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/BuildDoorStateRepair.java)、[FirstPersonBuildCompanionTask.verifyTick / finalStateTick](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/FirstPersonBuildCompanionTask.java) |
| 暂停、取消与身体更换后的收场 | [FirstPersonBuildCompanionTask.stop / cleanup](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/FirstPersonBuildCompanionTask.java)、[CompanionTickDispatcher](../../common/src/main/java/org/maiwithu/maicraft/task/CompanionTickDispatcher.java)、[IntentTask](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java) |
| 失败与已确认效果怎样交给模型 | [BuildFailureEvidence](../../common/src/main/java/org/maiwithu/maicraft/core/task/build/BuildFailureEvidence.java)、[SemanticResultView](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticResultView.java)、[IntentRuntime.terminal](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) |

## 回执、暂停和恢复

`execute` 接单只表示任务登记，后续看任务结果与 Attention。设计分支通常直接完成报告；身体施工持续运行，需等待匹配的任务终态。用真实 `task_id` 调用 `task` 的 `get`、`pause`、`resume`、`cancel`；`resume` 不会替玩家确认 Dev 蓝图。

| 回执/事件 | 应怎样解释 |
| --- | --- |
| `scene_id`、`parent_scene_id`、两个 revision、两个资源 URI | 可编辑设计及其编译凭据；不能证明场地可达、材料齐备或建筑完成 |
| `construction_started:false`、`preview_created`、`preview_id` | 设计/预览操作的结果；预览失败前可能已经保存场景，创建新场景和画出预览不是原子操作 |
| `project_id` | 冻结绝对施工要求和材料的恢复入口；续建不相信历史百分比 |
| `requested`、`completed`、`placed`、`cleared` | 分别是目标数、当前满足数、本轮放置数、本轮破坏数；续建放了零格也可能已经满足目标 |
| `stopped_phase`、`construction_region`、`construction_layer`、`construction_access`、`construction_navigation` | 定位身体当前的工作阶段和站位尝试；字段分布于进度和结果，不保证每种模式都出现 |
| `build_diagnostics`、`failure_code`、`failure_position` | 预期/实际状态及失败位置；当前诊断列表仍有截断，见下文 |
| `clearance_report` | 声明目标的保护、替换或原生破坏限制；含维度、实际方块、坐标、原因和可能的水平偏移，当前错误码为 `build_clearance_blocked`，不再是声明格“不在自然方块名单” |
| `suggested_offsets` 和 `search` | 半径 16 格内、同高度整份蓝图的最多三个等距最近候选；只读观察预算 262144 次，未知区块不算空地；不自动迁移旧工程，也不证明地基、通路和功能 |
| `already_satisfied:true`、`placed_cells:0` | 开工前目标格就已全部满足、没有开施工批次：按目标达成报成功，话术写明 0 格变更，不冒称这次建好；`placed_cells` 始终是本任务带来的净变化格数 |
| `native_placement_completed:true`、`construction_complete:false`、`declared_structure_diff` | 已确认的原生放置落点偏离声明，动作与设计满足度分开；供料父任务也保留 `goal_satisfied:false`，交回 LLM 修改 |
| `world_change_uncertain`、`outcome_uncertain`、原生 confirmation | 真正未结算的动作/库存/世界事实；先查对应回执，不把方块看起来存在等同于服务端确认，也不把已确认偏移重新当作未知 |
| `temporary_supports_remaining`、`remaining_scaffolds` | 仍待回收的本项目支撑；中断不会把它们或已建实体自动撤销 |

暂停/防卫抢占会停下挖掘、松开连锁键和角色输入，保留可继续的任务阶段。普通取消停止旧动作、释放预览和临时控制，不撤销已消费材料或世界变化。死亡、断线、换世界意味着旧身体动作不能重放；语义检查点和工程档案保留已记录事实，新身体恢复按暂停/观察流程继续。场景和工程仍要求原世界与原维度，不能换到另一个世界用同一个编号施工。

版本不匹配时只补查模型索引或原场景，按返回原因重验证；缺料由已接通的供料分支处理。场地真的变化、原生拒绝或动作未结算时做对应的最小补查。已保存的模型编辑不会自动改变正在施工的工程，缺少旧档案也不能靠新的 `current_place` 重建历史。

模组方块必须在实际注册表中存在，并能用其原生放置物品表达。Create 扳手拆换、Ultimine 连锁、AE2 存储供料等由可选适配器和真实原生条件决定；缺少可选模组不代表普通方块不能施工。普通 `build` 不等于机器装配、接口配置或生产验收，不能因为摆出了方块就宣称机器已生产。

## 已知边界及尚未对齐处

1. **普通替换授权仍有旧门控。** `BuildingSceneAdapter.buildArguments` 默认写入 `replace_existing:false`，`BuildTaskRecord` 随后选 `DONT_REPLACE`；普通入口没有 `replace_block_entities` 字段，内部默认也为 false。`BuildClearanceSurvey` 仍可能建议补这些选项，其中方块实体选项对普通能力并不可提交。这与最新“已授权声明范围默认拆改”的仓库规则尚未一致，不能将机器专用自动修改分支的行为泛化到普通建筑。
2. **最终核验并非全部改为观察回执。** 原生落点偏移有明确的动作完成+整图 diff 分支，但普通 `verifyTick` 仍会重排修补，重复不匹配返回 `final_verification_no_progress`；最终状态不支持或原生调整失败也会返回失败。不能笼统承诺“所有已执行动作的设计偏差只返回完成”。
3. **工程预览与场景预览的加载检查不同。** 场景/逐格预览在适配器逐格检查加载与高度；仅 `project_id` 的预览没有对应检查，`showDesign` 只核对会话类型、世界和预览占用。当前公共描述中的统一“未加载就失败”不适用于这条分支。
4. **续建附加参数不改冻结计划。** 上层当前接受续建的 `protected_labels`，也未统一拒绝新 `target`；`BuildProjectAdapter.plan` 仍仅加载旧参数。这些值可能参与外层继承保护，但不能据此宣称工程锚点或持久化策略已改变；正常续建只传 `project_id`。
5. **公共校验有少量未消费的机器元数据。** `validateBuildingWire` 已拒绝机器约束、装配和预期产物，但通用格式仍接受 `external_inputs`、`supply_preference`、`onsite_reason`；普通 `buildArguments` 没有把它们接入施工。不要在这两个能力中使用这些字段来要求接线或生产；应由机器能力处理。
6. **普通诊断仍可能丢失后续条目。** `addBlocked` 最多保留 64 项，`recordTargetDiagnostic` 及最终 `build_diagnostics` 最多 16 项；维护进食回执也只保留最近 32 条。即使后续语义/Attention 层完整传递，也无法恢复执行器已丢掉的事实。它们与最新完整观察要求有差异，不应被描述成完整 diff；显式查询分页和有说明的搜索预算则是另一种边界。

## 已有验证入口

需要修改行为时，可从这些已有回归定位场景；离线检查不能替代实际角色、服务端同步和模组联动验收。

| 范围 | 现有入口 |
| --- | --- |
| 公开模型请求、只读预览、不可变版本和冻结续建 | [MachineRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/intent/MachineRegressionSuite.java)，包含 [BuildingSceneRuntimeTest](../../common/src/test/java/org/maiwithu/maicraft/intent/BuildingSceneRuntimeTest.java)、[BuildingSceneVersionRuntimeTest](../../common/src/test/java/org/maiwithu/maicraft/intent/BuildingSceneVersionRuntimeTest.java)、[BuildProjectContinuationTest](../../common/src/test/java/org/maiwithu/maicraft/intent/BuildProjectContinuationTest.java)、[BuildDesignPreviewTest](../../common/src/test/java/org/maiwithu/maicraft/intent/BuildDesignPreviewTest.java)；Gradle 入口 `:common:machineRegression` |
| 清障实际执行、原生放置、支撑恢复、食物维护与模型诊断 | [GuiRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/client/actor/GuiRegressionSuite.java)，包含 [BuildClearanceExecutionTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/build/BuildClearanceExecutionTest.java)、[BuildProjectRevisionTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/build/BuildProjectRevisionTest.java)、[BuildFoodBoundaryTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/build/BuildFoodBoundaryTest.java)、[BuildFailureEvidenceTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/build/BuildFailureEvidenceTest.java)；Gradle 入口 `:common:guiRegression` |
| 模型预算、文件容量、预览预算 | [BuildingBudgetRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/intent/BuildingBudgetRegressionSuite.java)；Gradle 入口 `:common:buildingBudgetRegression` |

本次说明整理仅做源码对照、示例 JSON 解析、文档本地链接和 diff 静态检查；未新增或运行测试，未启动游戏或 Luna。
