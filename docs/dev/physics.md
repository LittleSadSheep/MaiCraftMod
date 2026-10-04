# 物理结构：起飞前配平、粘接组装与原生控制

玩家可以让麦麦先在地面搭载具，比较加铁块、换气球蒙皮或移动螺旋桨后的受力，再用强力胶或蜂蜜胶粘接，操作物理组装器，配置转速和无线控制。设计由调用者决定；执行器负责真实取料、走位、交互和回执。预测、动作、结构符合声明以及真正能驾驶，是四件分别确认的事。

本页覆盖 `maicraft:physical_balance`、`maicraft:physical_assembly`、`maicraft:physical_control`。持续飞行和飞机旅行见 [飞控](flight.md)；通用接单、暂停、死亡和查询见 [任务生命周期](tasks.md)。发现列表展示短 `summary`，完整使用方法在能力契约的 `usage` 和 `parameters`；`usage` 不是请求字段。

## 从玩家问题找到实现

| 想检查哪一步 | 打开的位置与职责 |
| --- | --- |
| 为什么计划拒绝了参数 | [PhysicsAbilityAdapter.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/PhysicsAbilityAdapter.java)、[PhysicalAssemblyAbilityAdapter.validateTarget / anchor](../../common/src/main/java/org/maiwithu/maicraft/intent/PhysicalAssemblyAbilityAdapter.java)、[PhysicalControlAbilityAdapter.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/PhysicalControlAbilityAdapter.java)：能力入口、坐标绑定和原生任务创建 |
| 试算用什么工况 | [PhysicsBalanceParameters.parse](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/balance/PhysicsBalanceParameters.java)、[PhysicsSimulation.assess / run](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/balance/PhysicsSimulation.java)：限值、启停、扰动和隔离积分 |
| 实际质量和受力从哪里来 | [PhysicsSnapshotService](../../common/src/main/java/org/maiwithu/maicraft/server/physics/PhysicsSnapshotService.java)、[PhysicsSnapshotReader](../../common/src/main/java/org/maiwithu/maicraft/client/server/PhysicsSnapshotReader.java)：服务端采样、冻结分页及身份校验 |
| 换方块后为什么预测变化 | [PhysicsBlockEdits](../../common/src/main/java/org/maiwithu/maicraft/server/physics/PhysicsBlockEdits.java)、[PreflightPropellerEdits](../../common/src/main/java/org/maiwithu/maicraft/server/physics/PreflightPropellerEdits.java)：副本质量、惯性和新/移动螺旋桨的原生求值 |
| 推荐为什么没有找到平衡方案 | [PreflightBallast.candidates](../../common/src/main/java/org/maiwithu/maicraft/server/physics/PreflightBallast.java)、[PhysicsTrim.recommend](../../common/src/main/java/org/maiwithu/maicraft/core/integration/physics/balance/PhysicsTrim.java)：候选过滤、有界搜索和逐块施工预测 |
| apply 到底挖放了哪些格 | [PhysicalBalanceTask.onTick](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/PhysicalBalanceTask.java)、[StructureEditTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/StructureEditTask.java)：先原生施工、再读实际结构，保留部分完成效果 |
| 胶水是否真的生效、扣了多少 | [PhysicalBondTask.onTick](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/PhysicalBondTask.java)、[BondMaterialSettlement](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/BondMaterialSettlement.java)：取胶、两端走位、胶实体与背包结算分开确认 |
| 组装是否创建了新结构 | [PhysicalAssemblerTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/PhysicalAssemblerTask.java)、[AssemblyObservationReader](../../common/src/main/java/org/maiwithu/maicraft/client/server/AssemblyObservationReader.java)：先监听、再单次请求、读原生转换结果 |
| 拆回后蓝图和方向为何变化 | [AssemblyDesignSession](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/AssemblyDesignSession.java)、[AssemblyDesignMapping](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/AssemblyDesignMapping.java)、[PhysicalStructureDesignStore.mergeTargets](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/PhysicalStructureDesignStore.java)：整机声明继承、坐标迁移及显式属性旋转 |
| 控制器点了却没启动 | [PhysicalControlTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/PhysicalControlTask.java)、[NativePhysicalControl](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/NativePhysicalControl.java)：实际配置、原生命中、输入回执和后续状态 |
| 为什么不能站地面启动飞艇 | [OnboardControlApproach](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/OnboardControlApproach.java)：需要输入时确认同艇支撑/座位，座上不可达时保留座位 |
| 配键、松键或收端反馈有疑问 | [TypewriterKeyInput](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/TypewriterKeyInput.java)、[TypewriterControlSession](../../common/src/main/java/org/maiwithu/maicraft/core/task/physics/TypewriterControlSession.java)：键码、原生用户、完整绑定保存和一次有限保持 |

## 请求外壳与坐标

这些能力参数放在 `goal.parameters`。`goal.outcome` 描述用途，不会替代操作名、局部位置或设置值；能力专属 `preferences` 和硬约束为空。可选字段只有省略才采用默认值；不要用 `null`、数字字符串或布尔值冒充数值。

公共运行时接受 `auto_respawn`、`recover_after_death`，但这四个物理/飞控独立解析器仍会拒绝它们出现在 `parameters`。当前如需携带运行时授权，使用 `goal.preferences` 的 JSON 布尔值；死亡恢复意图不等于载具或遗失物品已恢复。这个入口差异仍待行为修复。

| 坐标框架 | 如何声明 | 含义 |
| --- | --- | --- |
| 已组装结构 | 真实 `structure_id` UUID；省略 `target` | 方块位置是相对观察到的 `origin_storage` 的整数偏移。结构移动时世界位置会变化，存储偏移不随之改写 |
| 世界方块 | 省略 `structure_id`；必须有 `goal.target` | `coordinates.position={x,y,z,dimension?}` 使用整数世界锚点；`landmark`/`area` 用已有 `label`；`current_place` 取适配当刻玩家所在格。偏移相对该锚点 |

组装/控制不能同时提供两个坐标框架，世界目标必须在当前维度。`physical_balance` 只接受结构 UUID，完全不接受 `target`。不能把数千万量级的船体存储坐标当作麦麦该走去的世界坐标。

组装/控制的局部对象必须恰含整数 `x/y/z`，每轴绝对值不超过 [BuildingBudgets](../../common/src/main/java/org/maiwithu/maicraft/core/build/BuildingBudgets.java) 的 `maxRadius`，默认 512 格；配置来自启动时读取的 `config/maicraft-building.properties`。配平补丁的范围独立固定为每轴 `-256..256`。

## physical_balance：受力与推荐

### 操作与参数

| 字段 | 类型、默认、限制与用途 |
| --- | --- |
| `structure_id` | 必填真实 UUID 字符串；结构必须当前可读 |
| `operation` | 区分大小写的 `analyze`（默认）、`simulate`、`recommend`、`apply`。前两者当前同路评估；推荐多一步搜索；apply 执行明确补丁 |
| `reference_rpm` | 有限 number，`-256..256` RPM，默认 64；0 合法，不设置真实电机 |
| `reference_velocity` | 可选、恰含有限 number `x/y/z`，每轴 `-256..256` 世界格/秒；省略沿用快照速度。运行/停转可用此参考，停止/启动从静止开始 |
| `balloon_fill` | `target`（默认）按已配置供气的稳定目标；`current` 按实际填充。停止推进时浮力保留，不把“目标”冒称“已经充满” |
| `controls` | 对象：已观察载荷 ID → 有限倍率 `[-4,4]`。未指定来源为 1，0 禁用该来源的试算作用；未知 ID 在评估时拒绝。轮胎只缩放驱动 RPM，不取消支撑/摩擦 |
| `wheel_brakes` | 最多 64 个已观察轮胎载荷 ID → 恰含 `{running,stopped}` 的对象；两值均有限 `0..1`，0 松、1 满刹。省略保留实际刹车；启停时插值。未知轮胎/缺模型拒绝 |
| `edits` | 0..64 项，apply 要求 1..64 项；每项 `{position:{x,y,z},block_id,properties?:{属性名:字符串值}}`。整数偏移 `-256..256`，同格不得重复。注册方块/属性由原生状态表核对；`minecraft:air` 明确拆除 |
| `ballast_candidates` | 可选 0..64 项 `{id?:字符串,position,block_id,properties?}`，坐标同 edits。id 省略从坐标生成；保留下来的 ID 必须互异。`[]` 表示不搜索，省略则自动生成附近铁块候选 |
| `max_ballast_blocks` | 整数 `0..64`，默认 8；0 不放额外配重 |
| `duration_seconds` | 有限 number `1..30` 秒，默认 6；启动过渡另加 1 秒，停止过渡另加 3 秒 |
| `max_tilt_degrees` | 有限 number `0.1..89` 度，默认 8 |
| `max_vertical_acceleration` | 有限 number `0.001..100` 格/秒²，默认 0.25 |
| `max_angular_acceleration` | 有限 number `0.00001..10` 弧度/秒²，默认 0.035；比较向量长度 |
| `perturbation_degrees` | 有限 number `0.01..20` 度，默认 2，必须严格小于允许倾角 |

所有数值限值属于模型评估，不是对真实物理引擎施加约束。`controls`、刹车、RPM 或速度出现在 `apply` 中，也只影响施工后的分析；不会顺便驾驶载具。

### 执行与证据

1. `apply` 先将明确补丁交给 `StructureEditTask`，真实取材、走位、挖放并合入整机声明。其他操作直接读取，补丁仅叠加到隔离副本。
2. 施工后等 5 游戏刻再读取；从读请求移除 edits，避免实际重量与虚拟补丁重复计算。
3. 服务端 `physics.snapshot` 检查结构已加载且在玩家 128 格内；原生采样不超过 5 游戏刻。分页每页 24 条，快照冻结 600 游戏刻；读取器连续消费全部页，校验拥有者、世界、结构和快照身份，400 游戏刻无分页进展则失败。
4. 后台在隔离刚体上评估停机、运行、启动、停转，以及正负俯仰、正负横滚。车轮接地模型包含支撑、驱动、刹车与沉降；气球浮力在停止工况保留。
5. 推荐默认在质心附近及下方半径 1..6 内寻找最多 64 个铁块格。候选必须加载、为空、邻接碰撞面且质量为正；不符合的候选被过滤。有界搜索保留多个组合，最多使用请求的块数，再评估施工中间态和最终态。

读 `physics_balance` 的受力、轨迹、来源完整性和未知项。`predicted_balanced` 只在模型工况内成立；`improved_not_balanced` 表示改善仍未平衡，`no_balanced_candidate_found` 不证明所有布局不可行。`native_flight_verified=false` 明确没有替代实机验证。后台当前预算为 400 万计算项或 5 秒现实时间；预算耗尽/取消可正常交付 `analysis_incomplete` 和已读事实，不能仅按任务 success 推定完整预测。

`apply` 的 `construction_started`、`completed_effects`、`processed_targets/total_targets`、`placement_diagnostics`、`design_declaration` 和 `declared_structure_diff` 保存真实施工事实。施工已确认，后续分析不可用时返回 `analysis_unavailable` 并保留已完成动作；不能将分析失败变成“之前没有放过方块”。

模型没有复演地形碰撞求解、绳索/多刚体约束、传动网络重建、充气时间和流体变化。新建/移动/转向的普通螺旋桨通过未注册、不开 tick 的原生对象求值，不向世界生成轴承；胶层、接线、反转配置和实际成型仍有独立未知项。显式候选的 `properties` 用于读取质量，但当前推荐结果只保留方块 ID 和位置，不保留这些属性；带状态的配重应由调用者重新明确 edits。

### 完整 plan 示例

以下 UUID 和局部格来自曾观察的飞艇，不是通用模板编号；只在确认仍为当前世界同一结构后使用。其他现场换成自己的真实观察值。`plan` 只登记，后续 `execute` 使用返回的 `plan_id`。

只读推荐，允许最多四块铁配重：

```json
{"goal":{"ability":"maicraft:physical_balance","outcome":"起飞前比较启停并给出配重候选","parameters":{"structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870","operation":"recommend","reference_rpm":64,"balloon_fill":"current","max_ballast_blocks":4,"duration_seconds":6,"max_tilt_degrees":8,"perturbation_degrees":2}}}
```

只试算明确铁块，不搜索其他候选，也不施工：

```json
{"goal":{"ability":"maicraft:physical_balance","outcome":"比较指定配重格后的结构状态","parameters":{"structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870","operation":"simulate","ballast_candidates":[],"edits":[{"position":{"x":-1,"y":-4,"z":-1},"block_id":"minecraft:iron_block"}]}}}
```

选定后才明确要求真实施工：

```json
{"goal":{"ability":"maicraft:physical_balance","outcome":"原生落实选定配重并读取完整差异","parameters":{"structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870","operation":"apply","edits":[{"position":{"x":-1,"y":-4,"z":-1},"block_id":"minecraft:iron_block"}]}}}
```

## physical_assembly：胶层、结构转换和声明继承

| 字段 | 类型、默认、限制与用途 |
| --- | --- |
| `operation` | 忽略大小写：`inspect`（默认）、`bond`、`assemble`、`disassemble` |
| `structure_id` | 可选真实 UUID 字符串；与 target、project_id、design_id 互斥。assemble 禁止，disassemble 必须 |
| `adhesive` | 仅 bond 必填，其他操作禁止；`create:super_glue` 或 `simulated:honey_glue`，不自动换胶 |
| `first` / `second` | 必须成对，bond 必填；整数局部坐标，范围见上。其他操作省略时两点均为原点，胶层观察只覆盖这一格 |
| `assembler` | 组装器整数局部偏移，默认 `{x:0,y:0,z:0}`；不是另一个世界锚点。bond 解析但不使用 |
| `project_id` | 可选当前世界已保存建筑工程 UUID，继承作者目标；可与 design_id 共存 |
| `design_id` | 可选既有世界设计 UUID，来自粘接/拆回回执，继承整份声明 |
| `declarations` | 可选，默认 []；项为 `{position,block_id,properties?}`，属性值为字符串。原始项数不超过 maxTargets（默认 262144），坐标同组装范围；声明用于保存/比较，不会建造这些格 |

合并顺序为既有世界设计 → 工程目标 → 显式 declarations。同格后项完整替换旧声明，旧属性不会隐式保留；其余声明继续保留。未声明位置不是空气要求，不能把胶区扩大解释为清空范围。inspect 只合并读取，不写回设计。

`bond` 先保存声明，检查胶区加载；若同种胶已经完整覆盖直接完成，不重复扣材料。否则等结构停稳，确认建造权限，取真实胶水，依次到达首尾端点并提交一次原生请求。强力胶与蜂蜜胶遵循各自原生选择规则，蜂蜜胶允许空气端点。新胶实体确认后继续只读等待材料结算，胶层同步与背包耐久同步分开报告。

`assemble/disassemble` 先保存声明，再接近真实组装器，取消潜行并验证原生命中。服务端监听在提交之前登记；提交前核对当前结构身份和转换方向。已观察到输入时只跟随其回执，不再次拨动组装器。请求处理完但没有转换，可能正常返回 `structure_changed=false`，应读 `native_observation` 的 outcome 和原生错误。

真实组装返回新 `structure_id`、存储原点与世界到存储偏移，整份声明随之迁移；拆回按原生旋转/锚点变换局部格及显式方向属性，保存新的世界设计编号。转换已经发生后，读取/持久化失败仍返回转换事实与未知项，不自动反向切换。

| 回执事实 | 能说明什么 |
| --- | --- |
| `native_submitted` / `input_receipt` | 是否提交及原生确认到哪一步；不是整车运行结果 |
| bond 的 `native_confirmed`、`already_bonded_observed`、`glue_before/after` | 本次新胶或已存在覆盖；已有胶不表示本次消耗过材料 |
| `material.before/after`、`material_settlement` | 实际胶物品/耐久和结算是否完整 |
| `native_observation`、`structure_changed`、`completed_effects` | 原生处理、实际转换以及已经发生的效果 |
| `design_declaration`、`declared_structure_diff` | 全部已登记目标与当前状态差异；声明保存失败/历史不完整也须保留 |
| `after_observation_unknown` | 动作后无法确认的部分，不抹去动作本身 |

完整 bond 请求使用曾观察飞艇的三个已声明格；这里不是执行指令：

```json
{"goal":{"ability":"maicraft:physical_assembly","outcome":"用蜂蜜胶粘接明确的艇内选区","parameters":{"structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870","operation":"bond","adhesive":"simulated:honey_glue","first":{"x":-1,"y":-6,"z":-1},"second":{"x":-1,"y":-4,"z":-1}}}}
```

检查当前玩家格作为世界锚点的组装器；省略角点只观察锚点格胶层：

```json
{"goal":{"ability":"maicraft:physical_assembly","outcome":"读取当前锚点的物理组装器与胶层","target":{"kind":"current_place"},"parameters":{"operation":"inspect"}}}
```

需要拆回时明确指定已观察组装器；同一请求不能同时要求重新组装：

```json
{"goal":{"ability":"maicraft:physical_assembly","outcome":"通过原生组装器将飞艇拆回世界方块","parameters":{"operation":"disassemble","structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870","assembler":{"x":-1,"y":-5,"z":-1}}}}
```

## physical_control：配置、有限输入与反馈

`operation` 忽略大小写，默认 `inspect`。通用字段为可选 `structure_id`、`design_id`、`position` 和 `require_onboard`；坐标框架见上。position 默认原点。design_id 只能用于世界方块，结构沿用自己的声明。require_onboard 默认 false，true 要求 structure_id；它在需要动作前检查同艇支撑或座位，inspect 和不需输入的已满足分支不会额外登艇。

| 操作 | 必须或可用的专属字段 | 原生行为和边界 |
| --- | --- | --- |
| `inspect` | 无 | 读取实际配置，无主动登艇或设置 |
| `set_speed` | `value` 整数，非零 `-256..256` RPM | 配旋钮；面向会影响实际 RPM 符号。0 被拒绝，应通过真实离合/刹车等停机 |
| `set_throttle` | `value` 整数 `0..15` | 设置实际红石信号，可在运动中使用 |
| `set_burner_volume` | `value` 整数 ≥5，且不高于现场原生最大值 | 按原生 volume_step 向下量化并限到范围；设置容量不会给燃烧器通电 |
| `set_spring_angle` | `value` 整数 `1..360` 度 | 设置最大偏角，实际角度/输入输出 RPM 单独读；不是立即转到此角度 |
| `set_link_mode` | `receiver` 必填 boolean | true 接收、false 发送；实际已满足时不切换 |
| `set_frequency` | `frequency_items` 恰两个有序物品 ID 字符串 | 持实际物品右键原生频率槽；颜色身份保留，air 清空槽 |
| `assemble_propeller` / `disassemble_propeller` | 无 | 空手原生交互后另观察 isRunning；转速为零不能证明组装成型 |
| `turn_crank` | 可选 `duration_seconds` 有限 `0..30`，默认 0 | 0/省略只激活一次；正数向上取整为秒×20 游戏刻，原生冷却允许时继续手摇；可在运动中使用 |
| `set_tire` | `item_id` 必填字符串 | 已注册、有原生 TIRE 组件的物品；air 取下。已有同种保留组件，不另取备胎；用轮座外侧/底面交互 |
| `bind_typewriter_key` | `key` 加 `frequency_items` | 仅改单键，合并保存所有旧键；两频率都 air 删除此键；禁止 keys、duration_seconds、observe_positions |
| `press_typewriter_keys` | `keys` 必填；可选 `duration_seconds`、`observe_positions` | 一次同时按下，有限保持后松开/断开；可在运动中使用 |

专属字段必须与操作匹配，不能给 set_speed 附带 duration_seconds，也不能将 receiver 缺省解释成 false。键名用普通键盘名字（`w`、`left`、`space`）或 `key.keyboard.space`，转为小写但不修剪空格；Escape、鼠标和未知键拒绝。

`keys` 为 1..16 项，归一化后键码互异，执行时全部已绑定。保持时长默认 1 秒，必须大于 0 且不超过 30 秒，向上取整成游戏刻。`observe_positions` 默认 []，最多 16 个不同的同框架局部位置；每 5 游戏刻只读部件，变化的完整状态与未知项保存到 `observed_feedback`。

实际配置先等停稳（持续运动超过 200 游戏刻停止）；油门、手摇和有限按键不要求停稳。打字机配键在连接前单独检查停稳。角色取实际频率物品、清空主手，原生连接并确认服务器上的用户是自己；被其他人占用则不抢占。保持期间继续检查所有权、距离、同艇要求和绑定身份，完成后只松开自己按下的键并断开。

`input_receipt` 的输入确认与 `actual_configuration` 的状态确认分开；`vehicle_operation_verified=false`。螺旋桨输入已确认但成型没有被观察到时，保留动作完成并给 `requested_propeller_state_observed=false`，不要自动再点一次。按键状态不向普通客户端同步，`key_state_confirmed=false`；packet 发送成功不证明收端信号或运动，应读观测反馈。

在曾观察的艇内打字机上进行一次升力键试验：

```json
{"goal":{"ability":"maicraft:physical_control","outcome":"在同艇有限保持升力键并松开","parameters":{"operation":"press_typewriter_keys","structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870","position":{"x":-3,"y":-5,"z":-2},"require_onboard":true,"keys":["space"],"duration_seconds":1}}}
```

显式删除该键绑定，其他键保持原值：

```json
{"goal":{"ability":"maicraft:physical_control","outcome":"删除指定无线打字机键的绑定","parameters":{"operation":"bind_typewriter_key","structure_id":"aceba7b1-00a7-481a-b59f-ec8e38cae870","position":{"x":-3,"y":-5,"z":-2},"key":"space","frequency_items":["minecraft:air","minecraft:air"]}}}
```

## 暂停、取消与恢复

| 事件 | 实际处理和恢复依据 |
| --- | --- |
| 普通暂停/人工接管 | 总任务停推进、原生输入按身体控制边界结算，走位暂停；不是回滚方块、胶层或设置 |
| 打字机按键会话暂停 | 尝试松开本会话按键并断开，标记 interrupted；恢复后拒绝重放剩余保持时段，需要读效果并提出新意图 |
| 取消 | 清理导航、快照请求、后台计算和动作回执；已发生的施工/消耗/转换留在结果中 |
| 死亡、身体替换、换世界 | 旧动作不继续向新身体发送；粘接有世界/维度一致性检查。断线时无法发送清理包就保留未知，不谎称松键已被服务端接受 |
| 客户端重启 | 已保存设计和总任务回执可读取，动作会话/后台线程不持久化；按全局恢复边界重新观察，不能从旧 native_submitted 推定新一轮可机械重放 |

这些适配器创建的原生任务期限为 15 分钟游戏刻；暂停等通用时钟规则由任务框架处理。不要把本页的短时确认窗口或现实等待时间当成动作必然失败的证明。失败、取消或后续读不到状态时，先从任务查询读取完整 `completed_effects`、声明差异和真正未知项，再为剩余目标制定计划。

## 模组前提、已知边界和验证入口

结构身份和姿态来自 Sable；胶水、座位和主要传动交互来自 Create，蜂蜜胶和物理组装器使用当前 Simulated 协议，气动/燃烧器依赖 Aeronautics，轮胎依赖 Offroad。反射接口及原生注册内容必须与当前安装兼容；缺模组/部件/服务端观察能力会返回不可用或未知，不能降级为直接写世界。

完整受力需要服务端 `physics.snapshot`，组装转换确认需要 `physics.assembly`。检查胶层不证明整机连通，保存声明不施工，推荐不自动配平，配置成功不证明能驾驶。一般施工和配置以起飞前使用为主；`apply` 和只读分析没有“预测不平衡则拒绝”的门槛，实际交互条件仍按原生事实处理。

当前参数实现的额外差异包括：配平 controls 的数字字符串可能被 Gson 接受；部分嵌套对象的额外键没有统一拒绝；候选无效位置被排除而非逐项附原因。调用者仍应按表提交规范类型和字段，不依赖这些宽松解析。与飞控相关的缺轴映射、尚未验收的起降和航空搜索见 [飞控边界](flight.md#已知边界与验证入口)。

已有离线入口见 [common/build.gradle](../../common/build.gradle) 中的 `physicsBalanceRegression`、`flightControlRegression`、`navigationRegression` 和 `machineRegression`。相关用例在 [物理计算测试](../../common/src/test/java/org/maiwithu/maicraft/core/integration/physics) 与 [原生物理任务测试](../../common/src/test/java/org/maiwithu/maicraft/core/task/physics)。离线模型/契约检查不能代替真实胶实体、材料结算、动态转子通行或驾驶验收。

本次整理仅核对源代码、JSON 示例、文档链接和文字改动边界，没有新增/运行测试，也没有启动游戏或 Luna。
