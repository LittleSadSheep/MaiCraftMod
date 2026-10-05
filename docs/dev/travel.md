# 旅行能力：目的地、到达精度与结果

`maicraft:travel` 让角色前往已定位地点，或边移动边发现平台、露天地表、群系及海岸。本文说明当前公开契约和执行链；跨维度旅行使用 `maicraft:travel_dimension`，自由跑图及结构发现使用 `maicraft:explore`。

## 参数放在哪里

能力通过 `plan` 或 `execute` 的 `goal.ability` 选择，能力字段放在 **`goal.parameters`**。读取当前游戏实例实际加载的契约：

```json
{"view":"abilities","focus":"maicraft:travel"}
```

上例交给 `perceive`。返回的 `semantic_abilities[].contract.parameters` 是该实例接受的字段；源码文档与运行中的旧构建可能不同。已掌握当前契约时直接提交目标，无须每次重新查询。

`destination` 内只接受 `x`、`y`、`z`、`dimension`。`exact`、`horizontal_radius`、`vertical_tolerance` 都与它同级，不能放进 `destination`、`target.position`、`preferences` 或目标顶层。

## 先选到达精度

| 想完成的事 | 填法 | 成功意味着什么 |
| --- | --- | --- |
| 到附近，高度未知 | `destination:{x,z}` | 水平进入指定半径，使用可达高度 |
| 到附近，高度允许偏差 | `destination:{x,y,z}` | 默认水平不超过 3 格，高度不超过 2 层 |
| 必须到同一层，水平可偏 | 已知 Y，`vertical_tolerance:0`，`exact:false` | 脚位节点在指定 Y 层，水平仍按半径判断 |
| 必须站到指定格并落地 | 已知 X/Y/Z，`exact:true` | 指定脚位格与实际地面支撑均满足要求 |
| 操作水源、容器或机器 | 交给对应交互能力选择并验证站位 | 还须满足真实交互距离、视线、命中面等条件 |

`height hint` 表示普通旅行允许按配置偏离目标高度，**不表示高度容差固定、无法设置或永远不检查**。例如目标 Y=59、实际脚位 Y=61，默认容差 2 可以成功；容差设为 0 时不满足同层要求。

| 参数 | 默认值 | 约束与适用范围 |
| --- | --- | --- |
| `destination` | 无 | `{x,z,y?,dimension?}`；坐标为有限数值，省略或置空 Y 表示未知高度；维度使用命名空间 ID |
| `exact` | `false` | 布尔值；为 `true` 时必须提供或解析出完整 X/Y/Z，覆盖两个容差字段 |
| `horizontal_radius` | `3` | 有限非负数，单位为格；控制已定位目标的水平范围；零半径的旧回退例外见文末 |
| `vertical_tolerance` | `2` | 有限非负数，**接受 0**；仅在已定位、已知 Y、`exact:false` 时约束高度 |

两个容差参数接受数值 `0`，不接受字符串 `"0"`、负数或无穷值；坐标本身可以为负。省略 Y 时，填写垂直容差也不会凭空获得一个目标高度。平台、露天地表、群系、电梯选层和登船使用各自的完成判据，不依靠坐标容差。

已定位目标必须属于当前维度；填写 `dimension` 不会自动通过传送门。跨维度需求先用 `maicraft:travel_dimension`，到达后再处理该维度的具体目的地。

坐标会向下取整为目标格；`exact` 是格级精度，不能要求角色的浮点坐标与输入逐位相等。到达判断使用 `BlockHelper.playerFeet` 的脚位节点，识别下半砖等支撑；坐标 Y 表示脚位层，不是所操作方块的顶面或交互点高度。

## 可以直接提交的示例

以下均是 `plan` 的完整请求。计划就绪后使用返回的 `plan_id` 调用 `execute`；登记成功表示任务已接收，继续按 `next_attention` 等待完成、决策或暂停。同一次执行的网络重试复用 `request_key`。

### 水平允许三格，必须到 Y=59 这一层

```json
{
  "goal": {
    "ability": "maicraft:travel",
    "outcome": "前往已观察的岸边站位，脚位保持在 Y=59 层",
    "parameters": {
      "destination": {"x": -3091, "y": 59, "z": 16},
      "horizontal_radius": 3,
      "vertical_tolerance": 0
    }
  }
}
```

默认 `exact:false`，水平与垂直约束独立。该请求完成后仍由取水动作验证水源是否可交互。

### 必须站到已观察的空脚位格

```json
{
  "goal": {
    "ability": "maicraft:travel",
    "outcome": "站到指定脚位格并落地",
    "parameters": {
      "destination": {"x": 10, "y": 64, "z": 20},
      "exact": true
    }
  }
}
```

这里的目标是角色可站的脚位格。普通空中经过、游泳或悬停不能满足 `exact:true` 的落地条件。

### 只知道水平位置

```json
{
  "goal": {
    "ability": "maicraft:travel",
    "outcome": "前往目标区域的可达落脚处",
    "parameters": {"destination": {"x": 120, "z": -40}, "horizontal_radius": 5}
  }
}
```

### 前往已记住的地点，并收紧高度

```json
{
  "goal": {
    "ability": "maicraft:travel",
    "outcome": "回到营地记录的脚位层",
    "target": {"kind": "landmark", "label": "河岸营地"},
    "parameters": {"vertical_tolerance": 0}
  }
}
```

该示例要求地点已经登记。高度来自地标；找不到地标或维度不匹配时返回待处理原因，不用当前位置替代。使用 `target.kind:"coordinates"` 时，`target.position` 必须同时包含 X/Y/Z，不能用它表达未知高度。`area` 按已记住的位置解析，不代表整片区域任意一格都可完成。

### 向东侧扇区寻找海岸

```json
{
  "goal": {
    "ability": "maicraft:travel",
    "outcome": "在东侧九十度扇区寻找海岸",
    "parameters": {
      "semantic_target": "coast",
      "direction": "east",
      "angle_degrees": 90,
      "min_distance": 32,
      "max_distance": 768
    }
  }
}
```

`coast` 对应 `minecraft:beach`。扇区限制候选地点，实际路线仍可绕行。寻找模组群系或标签时，先从 `perceive(view="exploration",focus="biomes"|"biome_tags",query=...)` 选取实际注册 ID；探索成果保存在 SQLite 记忆的 `exploration` 分区，可按需查询。

## 目的地形式与交通

一次选择一种目的地形式。坐标型 `destination` 与 `goal.target`、群系发现等目标互斥。

| 形式 | 字段与行为 |
| --- | --- |
| 已定位地点 | `destination`，或 `target` 的 `coordinates`、`landmark`、`area`；使用本节前述到达精度 |
| 前步结果 | `target.kind:"prior_result"`，用 `relation` 指明此前能力或结果；总任务绑定成功步骤的权威位置后再移动，无法绑定则等待决策 |
| 平台 | `semantic_target:"platform"`；默认 `direction:"forward"`，可用 `up/down/forward/backward/left/right/north/south/east/west` |
| 露天地表 | `semantic_target:"surface"`；向上寻找脚下列露天的支撑处，不接受方向；树叶不作为天空遮挡，固体和液体遮挡仍计入 |
| 群系／标签／海岸 | 在 `semantic_target`、`biome_id`、`biome_tag` 中选择一种；`nearest` 目标的 `label/relation` 也可表达探索目标，推荐显式参数 |
| 电梯楼层 | `elevator_floor` 可选 `ask`、`top`、`bottom`、`next_up`、`next_down` 或已同步的楼层 ID／名称；可用 `elevator_id` 指定已观察的电梯 UUID |
| 登上物理船体 | `structure_id` 是已观察的船体 UUID；仅 `auto/jetpack`，需装备可用背包。可带 `seat_position:{x,y,z}`，为相对 `origin_storage` 的整数座位偏移；确认支撑或原生乘坐后完成，不负责开船 |
| 飞机旅行 | `transport_mode:"aircraft"` 配 `aircraft_id`，使用已保存飞控配置的飞机 UUID；终点仍需已定位，按登机、飞行、着陆停稳、下机、最后地面路段顺序完成 |

`travel.structure_id` 与 `explore.structure_id` 含义不同：前者是船体 UUID，后者是要发现的世界结构 ID。公开旅行没有 `arrival_strategy`、`exact_height` 或 `horizontal_tolerance` 字段；水平参数的准确名称是 `horizontal_radius`。内部 `goto` 的 `block` 和单独 `y` 入口也不等于公开旅行参数。

| `transport_mode` | 使用条件 |
| --- | --- |
| `auto` | 默认值；在目标类型允许的原生交通中选择 |
| `ground` | 地面导航，包含原有游泳、攀爬等动作 |
| `jetpack` | 需要可用的 Create 喷气背包；支持已定位目标、平台／地表发现和登船 |
| `elevator` | 已定位目标或电梯选层；不能用于平台／地表／群系发现 |
| `aircraft` | 必须提供已登记 `aircraft_id` 和当前维度终点；不接受未定位探索、登船或电梯选层参数 |

未定位群系或海岸发现接受 `auto/ground`。平台和地表接受 `auto/ground/jetpack`。飞机可以填写有限数值 `cruise_altitude`，它控制巡航高度，最终地面到达仍遵守原 Y 与精度。

电梯选层接受 `auto/elevator`。省略楼层或填写 `ask` 时先靠近并同步楼层，再返回决策；用 `task(action="answer")` 的 `retry` 和 `details.parameters` 选择楼层。只有真正乘梯并到达目标层才结束，楼层同步完成不是旅行完成。

## 发现范围与操作许可

| 参数 | 适用范围与默认值 |
| --- | --- |
| `direction` | 平台方向见上表；群系使用八方位或 `forward/backward/left/right`。相对方向在出发时固定，已定位目的地不接受探索方向 |
| `angle_degrees` | 仅群系发现；扇区完整夹角为 1–360°，有方向时默认 90°，无方向时为 360° |
| `min_distance` | 仅群系发现；有方向时默认 16 格，否则 0；非负且不超过搜索半径 |
| `max_distance` | 平台／地表默认 64，范围 8–128 格；群系默认 768，范围 64–2048 格；不是坐标旅行的总路程上限 |
| `may_alter_terrain` | 默认 `false`；允许途中挖掘、搭桥、垫高，仍受真实权限、保护区域和现有清障策略约束 |
| `allow_water_bucket_fall` | 默认 `false`；已定位目标的临时桶装水落地许可，不授予挖掘或搭设权限 |
| `allow_landing_assists` | 默认 `false`；已定位目标可使用随身物品提供经确认的临时落地辅助，同样不授予地形拆改权限 |
| `protected_labels` | 已记住区域的标签数组；根据此前测得的范围保护格子，不从标签文字猜测范围 |

## 何时算到达，回执怎样读

坐标旅行由原目标范围检查身体，路线结束不是单独的成功证据。普通旅行检查脚位与支撑起点均在目标范围，并要求落地或处于水中；`exact:true` 额外要求实际落地。

| 回执字段 | 含义 |
| --- | --- |
| `final_x`、`final_y`、`final_z`、`ground_y` | 最终实际身体坐标及 `blockPosition().y`；`ground_y` 是身体格层，不是另行测得的地表高度 |
| `target_y_hint` | 输入目标 Y 向下取整后的格层；有目标 Y 时提供 |
| `y_hint_delta` | `player.blockPosition().y - target_y_hint`；正数表示高于目标，负数表示低于目标 |
| `planning_idle_budget`、`planning_budget_unit` | 无进展预算及其时间单位；新增真实计算、位移或已确认操作后补时 |
| `planning_work_units`、`planning_work_fuse_units` | 本次搜索累计工作量及收敛熔断阈值 |
| `best_distance_blocks` | 已观察的最近目标距离；存在有效采样时提供 |
| `navigation`、`landing_assist` | 路线与实际落地辅助证据；按是否产生相应记录提供 |
| `landing_assist_observed` | 是否记录过自动落地辅助事实；不单独代表辅助成功 |
| `arrival_grade` | `arrived_exact`（身体站在目的地格、带 Y 提示时脚位同层）或 `arrived_within_tolerance`（到达范围内但不在目的地格）；仅成功回执提供 |
| `remaining_horizontal_blocks`、`remaining_vertical_blocks` | 身体到目的地格中心的水平直线距离、脚位与目标层的差；无 Y 提示时不含垂直项 |
| `arrival_direction` | 目的地相对身体的八向水平罗盘与目标在上/在下/同层，如 `north-east, target below` |
| `landing_protection_unverified` | 到达成立但本次自动落地保护收场未验证或失败时的注记；终态仍按到达交付，调用方自行决定是否复检脚下支撑 |

`y_hint_delta` 使用原始身体格层，到达判断使用半砖修正后的脚位节点，不能把两者当作浮点高度完全一致的证明。`arrival_grade` 让调用方程序化分辨精确落位与容差内到达：容差内成功只说明本次旅行条件满足，要身体与目标同层（贴水舀取、贴站台等）应显式传 `vertical_tolerance:0` 或 `exact:true`。

`PLANNING_STALL` 说明规划停滞或没有收敛，不证明地形无路。连续无进展预算当前为 600 个活动游戏刻；另有累计工作量超过 60,000 且约 600 刻没有继续接近目标的收敛熔断。后者可以终止仍在展开节点的病态搜索。暂停计时与子任务共享规则见 [任务预算](tasks.md#新能力的无进展预算)。

暂停、取消经总任务与身体控制层处理，导航在能安全交接时释放动作。重启恢复保留语义目标和已确认结果，未完成任务以暂停状态等待继续；旧的内存路线不会直接恢复执行。

## 当前实现的边界

- **未知 Y 且 `horizontal_radius:0`**：非精确的柱列目标在找路失败后仍有接受附近 3 格的旧回退，不能据此保证零水平偏差。严格站格需提供已观察 Y 并使用 `exact:true`。
- 内部单独 Y 的移动失败后仍接受一层偏差；它不是公开 `travel` 的“同层模式”。公开同层需求使用完整目的地和 `vertical_tolerance:0`。
- 目录虽列出 `player/entity` 目标，当前 `AbilityAdapter.position` 没有将它们解析成旅行坐标；持续跟随应使用 `maicraft:follow`，不能仅凭目录枚举认定已接通移动实体追踪。
- 回执中的飞机或船体某一段成功不代替原终点成功；已发生的原生效果与未完成的后续路段分开记录。
- 寻路对耕地方块加行走代价：有替代路线时绕开农田；跳跃与跌落的落点一律禁止选在耕地上，避免踩坏耕地退回泥土。因此穿过农田区的路线可能更长，这是有意行为而非绕路故障。

## 修改时沿哪条链检查

```text
perceive(abilities) → SemanticAbilityCatalog
plan / execute → PublicToolCatalog → SemanticGoalContract → TravelDestination
  → AbilityAdapter.travel → MoveToTool → MovementOps → MoveToTaskRecord.coordinateGoal
  → PlayerNav / TransportNavigator → MoveToCompanionTask 的身体到达判定
  → SemanticResultView → 总任务结果与 MCP 回执
```

平台／地表分别转入 `RegionalTravelTask`、`TravelSurfaceTask`，群系转入探索任务；电梯选层由 `ElevatorTravelIntent` 分流，飞机旅行由 `AircraftTravelIntent` 分流。

主要入口：[公开契约](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java)、[目的地与精度校验](../../common/src/main/java/org/maiwithu/maicraft/intent/TravelDestination.java)、[任务目标](../../common/src/main/java/org/maiwithu/maicraft/core/task/move/MoveToTaskRecord.java)、[身体到达与回执](../../common/src/main/java/org/maiwithu/maicraft/core/task/move/MoveToCompanionTask.java)、[脚位目标判据](../../common/src/main/java/org/maiwithu/maicraft/core/pathing/calc/NavGoal.java)。

现有回归入口包括 [TravelTransportContractTest](../../common/src/test/java/org/maiwithu/maicraft/intent/TravelTransportContractTest.java)、[MoveToTransportCompletionTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/move/MoveToTransportCompletionTest.java) 和 [AircraftTravelContractTest](../../common/src/test/java/org/maiwithu/maicraft/intent/AircraftTravelContractTest.java)。这些入口说明如何继续验证；本次文档补齐不代表已在整合包实机验收所有交通与精度组合。
