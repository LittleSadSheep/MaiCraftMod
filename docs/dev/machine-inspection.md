# 看清地图上的机器：`maicraft:inspect_machine`

玩家问“这台机器现在是什么样，和我设计的差在哪”，使用本能力。角色在当前位置读取地图，必要时请求服务端补读附近部件；检查本身不导航、不打开菜单、不投料、不改方块。检查完成表示已经交付可取得的观察，不表示机器符合蓝图、原料足够或已经生产成功。

有登记档案时，默认 `full` 同时交付地图现状、整机差异和运行事实；`diff` 省去局部勘测与服务端库存补读，只比较登记目标。没有档案也能读取地图，但不能凭空生成参考设计。物理子结构使用独立的 `structure_id` 分支，返回控制回路观察，不返回固定机器蓝图。

新建机器已有场地观察、修改已有施工锚点时，不必为了形式再检查一遍。只在需要当前布局、原生组件证据，或现有观察确实失效时调用。完工默认差异和修改后的整机档案合并属于施工流程，本能力只读取它们保存的参考目标。

## 想看哪一步，打开哪里

以下链接从本文件所在目录出发；方法名用于在文件内定位。

| 玩家场景或排查问题 | 实现入口与应关注的行为 |
| --- | --- |
| 模型看到哪些字段、哪些模式 | [SemanticAbilityCatalog.describeContract](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java)：`INSPECT` 分支是公开契约；[MachineAbilityAdapter.validate / inspect](../../common/src/main/java/org/maiwithu/maicraft/intent/MachineAbilityAdapter.java) 决定实际校验和分流 |
| 地点、坐标、前一步结果怎样绑定 | [PublicTargetContract.validate](../../common/src/main/java/org/maiwithu/maicraft/mcp/PublicTargetContract.java)、[IntentTask.resolvedCurrentGoal](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)、[PriorResultResolver.resolve](../../common/src/main/java/org/maiwithu/maicraft/intent/PriorResultResolver.java) |
| 如何从机器编号找回设计和范围 | [ClientMachineCatalog.blueprint / comparison](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/catalog/ClientMachineCatalog.java)、[MachineComparisonTargets.read](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineComparisonTargets.java)：读取保存的最终声明目标，不用现场反推设计 |
| 为何 full 有 diff、半径为何不约束全部观察 | [MachineInspectionBlueprintView.read / componentRadius](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineInspectionBlueprintView.java)：地图范围、登记目标范围和局部扫描半径分别处理 |
| 现状方块、空气和未知从哪里来 | [MachineWorldBlueprint.page](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineWorldBlueprint.java)：客户端逐格读取；[MachineBlueprintDiff.page](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineBlueprintDiff.java)：按声明目标分类差异 |
| 皮带有无转动、机械手拿着什么 | [MachineOperatingState.observe](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineOperatingState.java)：读取已同步的动力字段和持物；这些事实不是生产达标证据 |
| 局部几何、组件索引和 snapshot_id | [MachineSurvey.inspect](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineSurvey.java)、[MachineSnapshots.inspect / withInspectionView / enrich](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineSnapshots.java) |
| 为什么 native_processes 没列出旁边设备 | [NativeProcessRegistry.inspect / OBSERVATION_SCOPE](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/process/NativeProcessRegistry.java)：只匹配锚点本格的 v2 工艺适配器 |
| 库存分页、距离、拒绝和等待 | [ServerMachineObservationTask.onStart / onTick / poll / finish](../../common/src/main/java/org/maiwithu/maicraft/client/server/ServerMachineObservationTask.java)、[MachineObservationPages.report](../../common/src/main/java/org/maiwithu/maicraft/client/server/MachineObservationPages.java) |
| 移动物理结构是否有控制路径 | [MachineControlInspection.structure / capture / Observation.report](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/control/MachineControlInspection.java)；位置经结构姿态投影，存储坐标不是走路目的地 |
| 为什么查询结果变成 palette/cells | [TaskView.result / machineDifference](../../common/src/main/java/org/maiwithu/maicraft/mcp/TaskView.java)、[MachineSnapshotView.present / layout](../../common/src/main/java/org/maiwithu/maicraft/mcp/MachineSnapshotView.java)：提取差异、合并重复状态，不重新采样地图 |
| 暂停、取消、死亡或重连之后 | [IntentTask.stop](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)、[AbstractCompanionTask.stop / result](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/AbstractCompanionTask.java)、[IntentRuntime.bodyUnavailable / prepareRespawnHandoff / restoreBound](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java)；公共流程见[任务生命周期](tasks.md) |

## 请求层级与字段

完整能力说明从 `perceive(view="abilities", focus="maicraft:inspect_machine")` 读取。提交时 `goal.ability` 固定为本能力，`goal.outcome` 写希望得到的观察，机器参数全部放在 `goal.parameters`。`goal.target` 与 `parameters` 同级；`goal.preferences` 没有本能力专属字段，省略或使用 `{}`。自然语言 outcome 不会缩小扫描范围或代替结构化参数。

三个选址入口如下。建议只选一个；当前代码对部分重叠输入的处理见下文边界。

| 入口 | 需要哪些字段 | 实际解析 |
| --- | --- | --- |
| 固定机器编号 | `goal.parameters.machine_id`；省略 `goal.target` | 从当前世界/玩家绑定的已加载档案读取位置、维度和名称。编号来自完工回执或 `perceive(view="machines")` 的 `recorded_machines`；不是临时 `snapshot_id` |
| 语义地点 | `goal.target.kind` 与对应字段 | `current_place` 使用执行时玩家方块位置；`coordinates` 使用 `position`；`landmark`、`area` 使用记住的 `label`；`prior_result` 先由总任务解析此前成功步骤的位置 |
| 物理结构 | `goal.parameters.structure_id`；省略 `goal.target` | 使用实际观察返回的结构 UUID。可从 `perceive(view="situation", focus="maicraft:physical_structures")` 读取可见结构；不能编造 UUID 或把固定机器编号当作结构编号 |

`target.position` 仅适用于 `coordinates`，其 `x/y/z` 是必填的 32 位整数方块坐标，`dimension` 可省略或为 `null`，表示当前维度。显式其它维度的坐标/地标会被拒绝；通过机器编号找回其它维度的档案则保留档案并报告未知地图。`landmark/area` 的 `label` 必填，长度 1–160；`prior_result.relation` 必填，长度 1–120，可加 label 区分多个前序结果，无法唯一绑定则进入待决策。可选的 target 字段允许省略或 `null`，与下面能力参数的规则不同。

| `goal.parameters` 字段 | 类型、默认和范围 | 实际作用、组合限制 |
| --- | --- | --- |
| `label` | 非空字符串，1–160 个 UTF-16 代码单元；无固定默认 | 固定机器依次使用此字段、档案名称、target.label；仍无名称则待决策。加载成功的 full 和 diff 将此名称记为地点。它不是改名整机设计；structure_id 分支接受但不使用它 |
| `machine_id` | 非空字符串，1–80；省略时用 target | 首选复制登记编号。底层也兼容当前维度唯一档案名称；多个同名档案要求真实编号。当前代码同时收到 target 时优先编号，target 不参与实际选址 |
| `mode` | 字符串 `full` 或 `diff`，省略为 `full`，区分大小写 | full 读地图并在有档案时附整机 diff；diff 只读整机声明目标和运行事实，不创建新 snapshot_id、不补读库存。structure_id 必须省略此字段，不能显式传 `full` |
| `radius` | 整数，单位方块，0–8，省略基础值为 4 | full 显式半径把地图范围替换为锚点两侧含端点的立方体，0 表示锚点一格。省略时有存档范围就按该范围导出，局部组件勘测半径从 4 扩至最多 8；无存档范围时读半径立方体。它不缩小登记目标 diff、运行表或登记组件目标。diff 接受但不使用它 |
| `offset` | 整数，0–2147483647，默认 0 | full 的捕获格子序号，包含空气和未知，依次 X 最快、Z 次之、Y 最慢；不是非空气数组下标。0 一次读取全部范围，只有大于 0 才启用 limit。diff 接受但忽略，始终从目标 0 比较全部声明目标 |
| `limit` | 整数，1–512，默认 256 | 仅 full 且 offset>0 时限制读取格子数；默认 full 和 diff 均不以它截短事实。部分页返回 `has_more/next_offset`，不同调用不是同一时刻的快照 |
| `component_offset` | 整数，0–768，默认 0 | full 的兼容起读索引，指本次局部 `relative_blocks`；仅未登记机器按此跳过前项。有登记目标时仍遍历整机。diff 不使用；structure_id 禁用 |
| `resource_offset` | 整数，0–4096，默认 0 | full 第一个实际选中组件的原生资源起点，后续组件从 0 读；Mod 会连续取后续资源页。只有有明确未读游标时才使用，默认不让模型逐页调用。diff 不使用；structure_id 禁用 |
| `structure_id` | 非空、可解析为 UUID 的字符串，最长 36；无默认 | 与 target、radius、machine_id、mode、offset、limit、component_offset、resource_offset 互斥；必须全部省略，传 0 仍属于冲突。结果是控制回路报告，不支持固定机器 full/diff |

能力参数中显式 `null`、数字字符串、布尔值或非整数数值均不等价于省略，会被校验拒绝。`false` 不是任何字段的关闭开关；本能力没有 `allow_use` 或 `allow_modify`。`limit:0` 无效，三个 offset 的 0 是实际起点，`radius:0` 是一格。字段名拼错和其它能力的字段也会被拒绝。

### 可以直接解析的 plan 请求

以下 JSON 是 `plan` 的完整参数对象。第一份以玩家执行时位置为锚点，主动命名“现场机器”；有真实位置后可改为 coordinates。`plan` 只登记目标，不采样；随后执行返回的 plan_id 才检查世界，execute 使用同一次提交的稳定 request_key。

```json
{
  "goal": {
    "ability": "maicraft:inspect_machine",
    "outcome": "读取当前位置机器的地图布局和可取得的原生状态",
    "target": {"kind": "current_place"},
    "parameters": {"label": "现场机器", "mode": "full"}
  }
}
```

第一份 full 完成并记住名称后，可用下例只看参考设计与现状的差异。若此名称没有登记设计，返回 `available:false`；给地点命名既不创建原设计，也不为原档案新增名称别名。这也是合法请求与“有足够证据比较”不同的例子。有完工编号时，应把 target 替换为实际 `parameters.machine_id`，无需另做勘测来取得新编号。

```json
{
  "goal": {
    "ability": "maicraft:inspect_machine",
    "outcome": "只查看现场机器的整机声明目标差异",
    "target": {"kind": "landmark", "label": "现场机器"},
    "parameters": {"mode": "diff"}
  }
}
```

要观察一个指定锚点格，显式传 radius=0；以下仍以执行时玩家所在格为例。即便只捕获一格，若解析到了登记机器，其整机 diff 与运行目标也不会被裁成一格。观察没有登记的普通场地时只得到这一格现状。

```json
{
  "goal": {
    "ability": "maicraft:inspect_machine",
    "outcome": "读取当前锚点这一格的实际状态",
    "target": {"kind": "current_place"},
    "parameters": {"label": "锚点观察", "mode": "full", "radius": 0}
  }
}
```

结构 UUID、机器编号和 task_id 必须取自真实回执，本页不提供可误用的虚构编号。结构分支在 `parameters` 中只填实际 structure_id 即可，不要复制上例的 mode/radius。

## 从接单到观察结束

1. 公共入口校验 JSON，`SemanticGoalContract` 调用机器适配器检查参数。执行时，结构 UUID 直接进入物理控制回路观察；固定机器先解析编号或地点，再确定报告名称。
2. 找到档案后，`MachineInspectionBlueprintView` 用保存的最终声明目标读取整机差异及 `operating_state`。原设计仅是比较参考，`as_built_blueprint` 的实际方块和 properties 始终来自客户端地图；记录无法展开时写明 `recorded_targets_unavailable`。
3. diff 在此返回，不做周围区域扫描，不产生新 snapshot_id。没有原设计也正常交付 `blueprint_diff.available=false`，不能把顶层成功解释为比较通过。
4. full 读取所选范围的方块。如果锚点未加载或编号属于另一维度，交付可得布局、未知格及 `structure_complete=false`，不创建局部快照。执行器不会为检查主动走近或加载远端区块。
5. 可读取锚点时，`MachineSnapshots.inspect` 生成局部几何、指纹、组件证据、锚点 v2 工艺以及新 snapshot_id，再合入地图、整机差异和运行表。它会记住观察到的 AE2 原生终端访问点；适配器记住地点，并通知机器目录观察设备及安排区域发现。这些是记忆副作用，不是游戏物品或世界方块变化。
6. full 创建 `ServerMachineObservationTaskRecord`。没有协商到 `machine.snapshot` 就以客户端证据结束；支持时按组件位置逐个异步请求，只读取距玩家中心不超过 16 格、已加载的原生部件。有部件因距离超过 16 格没读到时，回执顶层附 `observation_range`（上限、人到机器中心的距离、超范围与已读部件数、下一步“走近到 16 格内再检视”），成功消息也直接写明这些部件的实时状态没有读取，避免把远处的检视当成看清了现场。登记机器使用完整声明目标，未登记机器使用局部几何候选；没有候选时可能读取锚点本格。
7. 每个请求使用 `resource_limit=128`、`occupied_items_only=true`。收到响应后核对服务端身份、维度和位置，连续读取同一组件剩余页，再读下个组件。单次等待超过 120 游戏刻就记录该组件缺口；有新页时将任务期限续至当前刻后 1200 刻。按 20 TPS 分别约为 6 秒和 60 秒，实际墙钟时间随游戏速度变化。
8. 单个组件超距、卸载、原生拒绝或字段未知，保留原因后继续其它组件。收尾把全部已收集页附回同一 snapshot_id，并保留客户端/服务端各自时刻。玩家或世界变化、快照已丢失等则无法沿用这一任务的观察锚点。

正式 MCP 游戏入口先经 [MaiCraftRuntimeFacade.requireWorld](../../common/src/main/java/org/maiwithu/maicraft/mcp/MaiCraftRuntimeFacade.java) 和 [ServerSessionRuntime.requireConfirmed](../../common/src/main/java/org/maiwithu/maicraft/client/server/ServerSessionRuntime.java)，要求已安装并确认 MaiCraft 服务端。内部任务的“缺 machine.snapshot 时仅返回客户端证据”不能被扩写为公开支持纯客户端服务器。

普通方块观察不要求 Create、AE2 或 Mekanism 存在；对应模组的已同步字段与具体服务端能力按真实可用性出现。缺少 server_evidence 不表示原生库存为空。物理结构分支依赖 Sable 桥能找到 ready、带姿态和存储边界的结构，不满足时返回 `physical_structure_unavailable`。

## 怎样理解回执

内部任务结果把观察放在 `data.machine`。任务查询会把 blueprint_diff 提升到结果层，并附 `blueprint_diff_path`、`blueprint_differences_path`；以返回路径为准。服务端原始页可能以 `raw_pages_path` 引用，默认部件样本已经保留其观察事实。

| 字段 | 能证明什么，以及不能据此推断什么 |
| --- | --- |
| `recorded_machine` | 身份、锚点、设计版本和历史施工记录；不是刚刚重新施工或当前地图仍符合设计 |
| `as_built_blueprint` | `maicraft.observed_blueprint.v1`；anchor、min/max_offset、实际非空气方块及完整 block-state properties。未携带菜单配置、库存或可重建的原生安装意图，不能直接当作施工蓝图提交 |
| `blocks` 或 `palette/cells` | 原始结果逐块列 block_id/properties/offset；公开查询把相同状态合为 palette，cells 为 `[x偏移,y偏移,z偏移,palette索引]`，仍是同一份读图证据 |
| `air_cells / unknown_cells / capture_complete` | 空气省略逐格行但计数；未加载或另一维度的格子有 reason。只有整个捕获范围从 0 完整读取且无未知格时 capture_complete=true；否则未列出的坐标不能统一当空气 |
| `blueprint_diff` | matched、missing、wrong_block、wrong_state、unexpected、part_mismatch、unknown 计数及 differences 中的 expected/actual。只比较声明方块状态与原生部件；unexpected 只适用于显式空气目标，未声明格子不是隐含空气约束 |
| `comparison_complete / structure_matches_blueprint` | 已读完全部目标且无未知才给确定的匹配布尔值，否则匹配值为 null；即使 true，`production_verified=false`，静态匹配不是功能验收 |
| `operating_state` | 已同步的 Create 转速、网络、过载、皮带分组和方向、机械手持物及真正未知项；可能落后服务端。没有检测到运动部件不等于不存在任何机器工艺 |
| `snapshot_id / structure_fingerprint` | 同一会话局部观察身份；指纹含方块状态、方块实体类型等，不含库存、配方设置、权限或全部网络成员。diff 和未加载分支不产生新 snapshot_id |
| `server_evidence` | 经原生响应确认的资源/接口样本、时间、未知和拒绝原因。`atomic_snapshot=false`；多面视图和跨刻样本可能重复同一库存，不能相加冒充总库存；静态库存也不证明物品由本机生产 |
| `native_processes` | 仅锚点适用的 v2 工艺契约和只读配方观察。空数组不能推出旁边没有普通交互或 v1 机器网络配方 |
| `control_analysis` 或结构分支的 `analysis/control_plan` | 观察到的接触、线路、输入和候选驾驶站；`control_connected_candidate` 仍需原生入座、可达控制器及实际运动确认，不能直接说结构可驾驶 |

## 结果怎么到达模型

[IntentTask.completeStep](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java) 将结果交给 [SemanticResultView](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticResultView.java)，machine 及观察子对象按只读事实保留。步骤结果、终态和 Attention 共享这些事实。[IntentStateCodec.sanitize](../../common/src/main/java/org/maiwithu/maicraft/intent/persistence/IntentStateCodec.java) 对 machine 等对象完整复制，历史观察可跨重启读取；这不恢复内存中的可操作 snapshot_id。

[TaskView](../../common/src/main/java/org/maiwithu/maicraft/mcp/TaskView.java) 和 [MachineSnapshotView](../../common/src/main/java/org/maiwithu/maicraft/mcp/MachineSnapshotView.java) 提升差异、压缩重复几何、合并同一请求中的部件状态。[ResponseArchive.present](../../common/src/main/java/org/maiwithu/maicraft/mcp/ResponseArchive.java) 对大回执只增加临时 details_uri，仍交付完整值；归档读取不是重新采样。[EmbeddedMcpService.toolResult](../../common/src/main/java/org/maiwithu/maicraft/mcp/EmbeddedMcpService.java) 把业务 JSON 写入一份 `content[0].text`，并附当前提醒。外部宿主怎样把 MCP 内容再次组合进模型请求不在这些文件内，本页没有替外部宿主保证上下文保留。

需要旧观察详情时读取 `task(action="get", task_id=实际任务编号, path=回执给出的路径)`。路径读取的 offset/limit 属于任务详情接口，与 `goal.parameters.offset/limit` 不是同一层级。临时归档过期优先读任务原件；仅在确实需要新现场或原件无法恢复时再执行只读观察，不重复执行建造来找回回执。

## 暂停、取消、死亡与恢复

| 情况 | 当前行为与恢复依据 |
| --- | --- |
| 普通暂停/抢占 | 总任务保留同一个检查子任务，不重新 onStart；不提交新页，已发出的只读请求可能在后台返回。恢复后继续轮询，仍以真实时刻和距离判断，不能把暂停前布局当成更新后的地图 |
| 取消或终态收尾 | cleanup 撤回仍待决的原生请求，释放输入；没有本能力需要回滚的物品事务。当前中途取消尚未调用 finish 时已读页不会合入 machine，见下方已知边界 |
| 玩家死亡、复活换身体 | 共用任务层保存语义进度、处理复活决定，未完成任务按暂停状态交接。检查器不自行复活或捡回物品；旧玩家对应的原生读请求不能继续当新身体观察 |
| 断线、换世界、换账号 | 原生观察检测玩家/Level 变化；MachineSnapshots 在重新绑定世界或账号时清空内存。持久档案按世界/玩家分隔，不能用旧世界 snapshot_id 操作新世界机器 |
| 重启后恢复 | 历史任务观察可以读取；未完成的语义目标恢复后重新适配，旧原生分页对象不持久化。machine_id 可在对应目录加载后重新查档；current_place 将使用恢复时玩家位置，固定地点应用已知编号或命名锚点 |
| 编号丢失或现场变化 | 内存最多留 16 份观察；按需补最小现场，不按时间机械重检。普通操作前发现指纹变化时 `MachineSnapshotRejection` 带 latest_snapshot；施工锚点的复用规则与普通操作不同 |

格式错误在 plan/execute 校验阶段拒绝。运行时档案未就绪、编号不存在、名称歧义或无法解析目标，适配器通常给 `machine_precondition_failed` 决策，选 `replace_goal` 携带完整 `details.goal`，或 cancel；这不表示已经尝试原生改世界。只读观察成功但未知项非空时，先按具体未知原因决定是否靠近、等待同步或换目标，不把差异直接改判为动作失败。

## 已知边界与待协调差异

以下为源码对照发现，未在本轮改变行为，也未作实机复现。后续修复须同时更新公开契约和验证入口。

1. **参数说明落后于默认整机交付。** full 的 offset=0 忽略 limit，diff 忽略 offset/limit；radius、component_offset、resource_offset 在 diff 也不参与读取。有登记目标时 component_offset 不筛选组件。旧契约仍描述普遍分页，需要按上述实际规则修订，不能承诺每次最多 256 格或手动翻完原生页。
2. **多个选址字段没有全部互斥。** machine_id 与 target 可同时通过校验，实际优先编号；structure_id 的 label 被接受却不使用。显式 target 路径按名称查档时，`ClientMachineCatalog.blueprint` 不检查名称命中的档案锚点是否等于 resolved target。名称和坐标不一致时可能把 saved 的比较目标与另一个中心混用，当前不应宣称已经验证一致性。
3. **取消中途补读可能缺已读事实。** `ServerMachineObservationTask` 的 pages 只在 finish 合入 report；取消、换身体或异常在 finish 前收尾时 resultData 返回原报告，已成功取得的服务端页未被交付。不能将这些未交付页解释为原生从未响应。
4. **范围标记不代表工艺全覆盖。** `native_component_bounds_covered` 只描述八格局部扫描能否包住登记边界，native_processes 仍只匹配锚点。radius 也不会限制登记组件的服务端目标，服务端读取另受 16 格玩家距离限制。
5. **未登记机器仍受旧勘测展示上限影响。** MachineSurvey 限制 768 个几何条目、256 个调色板状态、256 份组件细节和 1024 个相邻关系，并报告遗漏；未登记机器的服务端候选来自这些几何条目。完整 as_built_blueprint 不能弥补被漏选的库存证据。结构分支也有 32768 格、2048 个非空气组件和控制节点预算，超限返回不完整观察。
6. **默认投影与旧组件索引入口有接缝。** 完整现状布局存在时 MachineSnapshotView 会去掉 relative_blocks/palette，并把 component_evidence.block_index 转为 offset；原生汇总样本也按 offset 定位。后续 open_menu 仍要求 component_index，可能需要按任务原件路径找回索引，不能直接拿地图 cells 的下标替代。

目前没有发现本能力成功回执宣称已验证生产；它的真实问题是部分说明与字段可用范围不一致，以及中断/旧几何上限可能少交付原生证据。机器结构不匹配、库存未知和观察任务失败应继续分别呈现。

## 已有验证入口

修改执行行为时可从下列已有入口选择验证。本轮仅阅读源码、核对 JSON 示例/本地链接/补丁，不新增、不运行测试，不启动游戏或 Luna；不能把源码对照写成实机通过。

| 关注的问题 | 现有源码与运行入口 |
| --- | --- |
| 默认读地图、改图后再次读取、diff 与设计分离 | [MachineInspectionModesTest](../../common/src/test/java/org/maiwithu/maicraft/intent/MachineInspectionModesTest.java)、[MachineWorldBlueprintTest](../../common/src/test/java/org/maiwithu/maicraft/core/integration/machine/MachineWorldBlueprintTest.java)，`:common:machineRegression` |
| 缺失、错误状态、未知格、仅显式空气计多余 | [MachineBlueprintDiffTest](../../common/src/test/java/org/maiwithu/maicraft/core/integration/machine/MachineBlueprintDiffTest.java)，`:common:machineRegression` |
| 快照变化、消费、最新现场和查询投影 | [MachineSnapshotRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/intent/MachineSnapshotRegressionSuite.java)、[TaskViewTest](../../common/src/test/java/org/maiwithu/maicraft/mcp/TaskViewTest.java)，`:common:snapshotRegression` |
| 原生页归属、未知、样本时刻与服务端补读 | [MachineSnapshotEnrichmentTest](../../common/src/test/java/org/maiwithu/maicraft/client/server/MachineSnapshotEnrichmentTest.java)、[MachineObservationPagesTest](../../common/src/test/java/org/maiwithu/maicraft/client/server/MachineObservationPagesTest.java)，`:common:optionalServerRegression`（包含服务端协助回归） |
| MCP 文本是否保留完整事实 | [KnowledgeHttpTest](../../common/src/test/java/org/maiwithu/maicraft/mcp/KnowledgeHttpTest.java)、[AttentionRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/mcp/AttentionRegressionSuite.java)；执行任务定义见 [common/build.gradle](../../common/build.gradle) |

这些用例覆盖特定离线场景，不覆盖所有模组服务端、取消中途页丢失或外部宿主上下文组合；已知边界应另行验证后才能移除。
