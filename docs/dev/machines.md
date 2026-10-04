# 机器设计、施工、修改与动力接入

玩家说“在平台上搭一台压机”时，模型负责选择机件、位置和用途；Mod 负责取料、走位、原生放置、确认和收尾。玩家说“把旧传动线改短”时，省略的旧部件仍属于原机器，不能因为这次只改两格就丢掉整机比较目标。

本页覆盖 `maicraft:design_machine`、`maicraft:build_machine`、`maicraft:modify_machine`、`maicraft:connect_mechanical_power`。设备使用、生产和兼容附魔入口见 [机器生产与操作](machine-production.md)。观察接口由 [MachineInspectionBlueprintView](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineInspectionBlueprintView.java) 和机器检查相关实现提供，本页只说明这些回执怎样被施工使用。

## 先判断需要哪一种完成

| 玩家眼前的进展 | 对应证据 | 不代表什么 |
| --- | --- | --- |
| 审阅了一份设计 | `design_review`、`layout_compiler.validation` | 不代表已经取料、建成或产出 |
| 原生施工动作结清 | `native_execution_complete`、各子阶段实际效果 | 不代表最终结构没有受流体或邻接更新影响 |
| 整机声明目标匹配 | `blueprint_diff.comparison_complete` 与 `structure_matches_blueprint` | 不代表传动方向、供料或配方完成 |
| 接线端获得转速 | `power_ready`、两端网络和转速证据 | 不代表整台机器、所有皮带或产量达标 |
| 实际运行并交付产物 | 原生事件、实际库存变化和约定观察窗口 | 不能只拿静态结构或一次库存快照替代 |

## 公开请求的层级

`plan` 请求最外层是 `goal`；能力名放 `goal.ability`，人类用途放 `goal.outcome`，场地引用放 `goal.target`，本页字段全部放 `goal.parameters`。这些能力没有自定义 `goal.preferences`。不要把方块坐标数组放入 `target`：图纸中的 `offset` 是相对锚点，机器目标使用已经记住的名称。

`execute` 使用返回的 `plan_id`，或直接提交同一完整 `goal`，两者互斥；`request_key` 放在 `execute` 最外层。网络重试沿用原键，再查询返回的任务，不能为了取回响应换键重做。共同生命周期见 [任务开始与结束](tasks.md) 和 [序列与恢复](sequences.md)。

`MachineAbilityAdapter` 对机器参数逐操作列白名单。可选参数省略才取默认；显式 `null` 通常因类型不符被拒绝，不能当作“自动”。整数必须精确且在范围内；`0` 不是统一的“无限制”。`target` 整体省略与 `target:null` 都表示没有目标，但只有允许无场地审阅等分支能使用。

## 设计与建造参数

| `goal.parameters` 字段 | 实际契约 |
| --- | --- |
| `blueprint` / `blueprint_uri` / `design` | 必须且只能选一个。`design_machine`、`build_machine` 接受三种；`modify_machine/apply_blueprint` 不接受旧 `design` 图。对象不能是 `null`。 |
| `blueprint` | `schema_version:1`、`blocks`、可选 `assembly` 等；具体格式见下节。不是自然语言产物模板。 |
| `blueprint_uri` | 长度 1..2048 的字符串，必须是 Ponder 已导出的 `maicraft://knowledge/ponder/structure/...`。不是任意文件路径或网页；缓存引用丢失要恢复资料。 |
| `design` | 旧逻辑图：`components`、`connections`、可选约束、外部输入等。布局在 `MachineLayoutJobs` 中后台计算；其锚点可能由场地中心换算到地板，不等同于显式图纸直接使用中心。 |
| `snapshot_id` | 建造必填的 1..36 字符观察编号；必须与同次观察的目标匹配。通用设计审阅同时省略目标和编号；现场审阅两者同时提供。修改可省略此字段，当前实现自行读取目标位置，即使提供也不据此绑定旧场地。 |
| `allow_modify` | `build_machine`、`modify_machine` 必须显式 `true`；默认 `false` 不能开工。仅在玩家已授权施工或修改时设置。设计审阅不接受这个字段。 |
| `material_policy` | 建造与图纸修改默认 `ordinary`；规范值还包括 `storage_available`、`inventory_only`。解析器兼容 `auto/survival`、`storage/ae`、`carried_only` 等旧别名；新调用使用规范值。空字符串或 `null` 在机器适配入口拒绝。 |
| `replace_existing` | 默认允许替换声明格；旧 `design.constraints.preserve_existing:true` 会使缺省值为 `false`，显式字段优先。`false` 收紧替换范围，不是开启自动换址。 |
| `replace_block_entities` | 默认 `true`，只在 `replace_existing` 有效时允许替换方块实体；`replace_existing:false` 时不会因此扩大替换。已有明确保护仍生效。 |
| `protected_labels` | 已登记保护区域名称的字符串数组；省略或空数组不额外添加名称。不是坐标、点击脚本或对任意区域的授权。 |
| `production` | 可在设计审阅或建造中给出 v1/v2 意图，见 [生产格式](machine-production.md#两种-production-格式)。建造带生产清单还须 `allow_use:true`；普通建造反而不接受孤立的 `allow_use`。 |

施工目标仅接受 `{kind:"landmark"或"area",label:已有名称}`，不能附带 `position` 或 `relation`。名称被解析为当前维度的记忆位置；仅写一个看起来合理的新名字不会创建场地。

### 图纸字段怎样变成游戏目标

| 图纸层级 | 用法与边界 |
| --- | --- |
| `blocks[]` | 普通格为 `offset:[整数x,y,z]`、真实 `block_id`、可选 `properties:{属性名:"序列化值"}`。省略格保留，`minecraft:air` 明确清除；不是对整个包围盒的拆除许可。 |
| `blocks[].part` | AE2 部件使用 `item_id`、`part` 安装面；不与普通整格方块混用。只有已实现的原生部件安装适配可执行。 |
| `assembly.installations[]` | Create 带段可写 `type:"create:belt"` 加 `first/second`，或 `path` 拆成多个直段；两端准备轴可由编译器补齐。`pulleys` 是坐标三元组数组，`[[0,0,2]]` 与 `[0,0,2]` 不等价。 |
| `assembly.installations[].flow` | `first_to_second` 或 `second_to_first` 是声明的物流方向，不是已测转向。几何首尾次序与 RPM 正负都不能单独证明实际输送方向。 |
| `assembly.processing[]` | `processor`、`surface` 表达加工关系；当前生产编译器仍要求被引用的组件出现在本份图纸并校验工作位置。稀疏补丁引用旧节点的限制见文末。 |
| `expected_output` | 产品目标可声明真实物品 ID，用于资料和预期描述；没有这个字段不妨碍可复用工作台，写了也不会自动产出。 |
| `constraints.forbidden_mods` | 禁用的命名空间数组，作用于编译出的方块、部件和原生安装材料。 |
| `nbt` / `entities` | 资料允许携带观察信息，但当前原生施工不执行任意非空 NBT 或实体安装；配置、投料和生产分别走其原生能力。 |

预算来自 [MachinePlanningBudget](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachinePlanningBudget.java)：默认目标 32768、组件 1024、连接 4096、相对坐标半径 128，实际值可由 `maicraft.machine.planning.*` 启动属性改变。预算不是 Create 机器的物理尺寸规则；不要把默认值硬写成所有安装环境的上限。普通方块规范见 [正式蓝图资料](../../common/src/main/resources/assets/maicraft/knowledge/blueprint.md)；外部输入及原生安装格式由 [MachineAssemblyResources](../../common/src/main/java/org/maiwithu/maicraft/mcp/knowledge/MachineAssemblyResources.java) 动态生成 `maicraft://knowledge/machine_assembly`。

### 完整示例：先审阅，再使用真实场地引用施工

以下审阅请求无需 UUID，也不会放下压机；前提是当前安装了 Create，机件 ID 与状态可用。它展示结构与加工面，未声称已有动力。

```json
{
  "goal": {
    "ability": "maicraft:design_machine",
    "outcome": "审阅压机与置物台的结构，列出尚未验证的条件",
    "parameters": {
      "blueprint": {
        "schema_version": 1,
        "blocks": [
          {"offset": [0, 0, 0], "block_id": "create:depot"},
          {"offset": [0, 2, 0], "block_id": "create:mechanical_press", "properties": {"facing": "north"}}
        ],
        "assembly": {"processing": [{"processor": [0, 2, 0], "surface": [0, 0, 0]}]}
      }
    }
  }
}
```

正常建造不必先调用上述审阅。先取得 `perceive` 的场地回执；已有同会话、同锚点的足够观察可以复用：

```json
{"view":"construction_site","label":"压机工地"}
```

下列为完整 `plan` **绑定模板**：`$snapshot_id` 和整个 `target` 必须替换成这次真实回执中的值；占位字符串不是可发送的观察编号。这样无需在文档里编造 UUID。

```json
{
  "goal": {
    "ability": "maicraft:build_machine",
    "outcome": "在已授权工地安装压机与置物台",
    "target": {"kind":"landmark","label":"压机工地"},
    "parameters": {
      "snapshot_id":"$snapshot_id",
      "allow_modify":true,
      "material_policy":"ordinary",
      "blueprint": {
        "schema_version":1,
        "blocks":[
          {"offset":[0,0,0],"block_id":"create:depot"},
          {"offset":[0,2,0],"block_id":"create:mechanical_press","properties":{"facing":"north"}}
        ]
      }
    }
  }
}
```

检查 `ready_to_execute` 与编译诊断后，把真正返回的 `plan_id` 填入 `{"plan_id":"$plan_id","request_key":"press-build-001"}` 再调用 `execute`。`plan` 会对显式建造蓝图做原生安装预审，仍不等于材料足够或实际生产可行。

## 修改已有机器

`operation` 必填，三种分支不共用同一组参数：

| 操作 | 额外参数与默认 | 会执行什么 |
| --- | --- | --- |
| `apply_blueprint` | 必须二选一 `blueprint/blueprint_uri`；材料、替换和保护选项同建造 | 按本次声明拆换和补建，再把最终目标合回整机比较 |
| `connect_mechanical_power` | `source_label` 必填，1..160 字符；没有可传的材料策略、方向或保护数组 | 兼容接线分支固定 `inventory_only`、自动传动方案；不要把独立能力的参数塞进来 |
| `connect_external_input` | `input_id` 必填，1..64 字符；可选 `source_label`、`source_radius`、`material_policy`、`protected_labels` | 连接已经登记的外部资源接收端；动力省略来源时本地比较来源，其他介质要求指定来源 |

三种分支都需要已登记的机器目标和 `allow_modify:true`，可选 `snapshot_id` 只按字符串格式检查，实际位置由修改入口重新读取。

外部输入的 `source_radius` 为水平格数，8..128，默认 64；0 或 `null` 均非法。它仅用于省略动力来源时的自动发现：已加载、可见、当前作业高度上下4格、有真实转速，最多保留8个候选；不是全世界最近源保证，也不自行开采地下源。已明确指定来源时不会因报价更低换源。

`external_inputs` 声明允许 `kinetic/energy/fluids/chemicals/items`，但 **接线执行器目前只支持 kinetic 和 energy**；声明入口并不创建物品物流，其他介质会报不支持。动力按 Create 原生接口和整机轮数约束选线；能量线需相应服务端支持和 Mekanism 适配。建造含外部输入时须先独立建造、再连接，不能同时附带生产清单跳过接入。

前提：`压机工地` 是现有机器档案且偏移 `[2,0,0]` 已获修改授权。这份 `plan` 不依赖临时快照编号，也不清除其他位置：

```json
{
  "goal": {
    "ability":"maicraft:modify_machine",
    "outcome":"拆除机器指定位置的旧支撑",
    "target":{"kind":"landmark","label":"压机工地"},
    "parameters":{
      "operation":"apply_blueprint",
      "allow_modify":true,
      "material_policy":"inventory_only",
      "blueprint":{"schema_version":1,"blocks":[{"offset":[2,0,0],"block_id":"minecraft:air"}]}
    }
  }
}
```

## 独立动力连接能力

`maicraft:connect_mechanical_power` 不使用 `snapshot_id`、`allow_modify` 或绝对路线格；目标可为已知 `landmark/area/prior_result`，适配器解析到具体端点后创建内部工具任务。

| `goal.parameters` 字段 | 语义 |
| --- | --- |
| `source_label` | 必须能解析为同维度的已有动力源名称；省略不会自动找最近源，而是要求补充来源。 |
| `target_label` | 可省略，使用目标位置；也可给已登记设备名，或当前注册的方块 ID，在目标区域中筛选该类型。拼错普通名称不会悄悄换成任意设备。 |
| `transmission` | 默认 `auto`；`chain_conveyor` 指锁链传动轮，`encased_chain_drive` 指链式传动箱，旧别名 `chain_drive` 映射到后者。 |
| `belt_direction` | 可选 `north/south/east/west`，用于现有皮带的实际物品方向；只用于 `auto/chain_conveyor`，不能搭配有效的自由接收端。省略或 `null` 不声明方向要求。 |
| `allow_new_receiver` | 默认 `false`；`auto` 下设 `true` 会走允许空接收端的旧路径，`chain_conveyor` 下当前实现强制归为 `false`。这不是建新动力源授权。 |
| `material_policy` | 默认 `ordinary`；其余规范值同建造。此内部工具路径接受 `null` 为默认，与严格机器适配入口不同。 |
| `allowed_sources` | 字符串数组，省略、`null` 或 `[]` 都交给材料策略选默认来源，不表示禁止取材。可选 inventory、nearby、wireless、storage、harvest、craft、cook、mine、trade、hunt。 |
| `allow_harm` | 默认 `false`；只有明确的玩家授权才可为取材设置 `true`。省略或 `null` 不开放伤害。 |
| `protected_labels` | 已记区域数组；省略、`null` 或 `[]` 不额外增加保护项。 |

规范调用应传上述声明类型。该独立能力部分字段沿用较宽松的通用解码，不应依赖数字代字符串或字符串代布尔值的强制转换。

前提：两个名称都来自实际观察/记忆，玩家已经授权这次接线；不由文档虚构坐标。

```json
{
  "goal": {
    "ability":"maicraft:connect_mechanical_power",
    "outcome":"把已有公共动力接入已观察的皮带，核对实际物流方向",
    "target":{"kind":"landmark","label":"压机工地"},
    "parameters":{
      "source_label":"公共动力出口",
      "target_label":"create:belt",
      "transmission":"auto",
      "belt_direction":"south",
      "material_policy":"inventory_only",
      "allow_new_receiver":false,
      "allow_harm":false
    }
  }
}
```

经济接线回执分别看 `route_built`、`power_ready`、`source_power_evidence`、`destination_power_evidence`、`server_verified` 和 `belt_direction_check`。旧链式传动箱及自由接收端路径使用 `network_live`、`source_speed`、`destination_speed` 等字段，并以 `numeric_stress_margin_supported:false` 标明没有数值应力余量证据；不能假定所有分支都有同一组字段。方向检查的 `matched/mismatch/unknown/not_requested` 不等于整机产出结论；`machine_production_verified`、`throughput_verified` 不由接线成功推出。`no_change` 表示核验了已有连接，不能算成又放了一条线。链条消耗、已放传动件及不确定原生点击留在子回执中。

当前每台机器最多一个 `create:chain_conveyor`，累计既有目标及已确认施工归属。已有超额机器仍允许不增轮的修理/拆除；不要通过拆成多次接线重置额度。规则入口见 [MachineChainConveyorLimit](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineChainConveyorLimit.java)。

## 从玩家动作定位代码

| 想看哪一步 | 打开哪个文件/方法 | 应核对的事实 |
| --- | --- | --- |
| 字段是否接受、三类入口如何分派 | [MachineAbilityAdapter.validate/adapt](../../common/src/main/java/org/maiwithu/maicraft/intent/MachineAbilityAdapter.java) | 操作白名单、授权开关、目标/观察绑定 |
| 没有工地能否先审图 | [MachineDesignBindings.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/MachineDesignBindings.java)、[MachineLayoutJobs.poll](../../common/src/main/java/org/maiwithu/maicraft/intent/MachineLayoutJobs.java) | 纯审阅与现场审阅配对；旧设计计算尚未完成的等待 |
| 为什么 plan 就报蓝图错误 | [MachinePlanPreflight.review](../../common/src/main/java/org/maiwithu/maicraft/intent/MachinePlanPreflight.java)、[MachineBlueprintDocument.compile](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineBlueprintDocument.java) | 声明格式、注册状态、原生安装、创造资源资格 |
| 方块如何拆成实际施工批次 | [MachineConstructionPlan.compile/blockTask/attachmentTask](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineConstructionPlan.java) | 普通方块、带段、依赖附件、源流体和部件 |
| 为什么先收水、再补墙、最后倒桶 | [MachineBuildTask.tickMachine/removeFluids/fillFluid](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineBuildTask.java) | 当前阶段、桶效果与剩余流体任务，不只看顶层成功 |
| 缺料和临时支撑如何续建 | [SemanticBuildSupplyCompanionTask.tickChild](../../common/src/main/java/org/maiwithu/maicraft/core/task/supply/SemanticBuildSupplyCompanionTask.java) | 已完成格、最后子任务原因、补料及容量证据 |
| 修改为何必须比较旧部件 | [ClientMachineCatalog.installationBuilt](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/catalog/ClientMachineCatalog.java)、[MachineComparisonTargets.record](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineComparisonTargets.java) | 合并本次最终目标，范围外旧目标保留；与可执行安装关系分离 |
| 如何计数结构差异 | [MachineBlueprintDiff.page](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineBlueprintDiff.java)、[MachineBuildTask.beginComparison/resultData](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineBuildTask.java) | unknown 不补成空气；比较不可用不伪装整机匹配 |
| 语义动力源如何转成端点 | [AbilityAdapter.connectPower](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java)、[CreateMechanicalPowerTool.onGameCall](../../common/src/main/java/org/maiwithu/maicraft/core/integration/create/CreateMechanicalPowerTool.java) | 真实名称、类型筛选与内部坐标；LLM不写点击路线 |
| 为什么选轴、齿轮箱或链条 | [EconomicKineticTask.plan/build/checkTarget](../../common/src/main/java/org/maiwithu/maicraft/core/integration/create/transmission/EconomicKineticTask.java)、[KineticRouteChoice.rank](../../common/src/main/java/org/maiwithu/maicraft/core/integration/create/transmission/KineticRouteChoice.java) | 全路线材料、供料缺口、接口、转速、显式方向与整机轮数 |
| 中断后是否重复接链 | [ChainConveyorLinkTask](../../common/src/main/java/org/maiwithu/maicraft/core/integration/create/transmission/ChainConveyorLinkTask.java)、[KineticRouteContinuations.find](../../common/src/main/java/org/maiwithu/maicraft/core/integration/create/transmission/KineticRouteContinuations.java) | 已提交点击、原生选点、同世界同玩家和现场复核；没有时钟到期即重放的许可 |

## 暂停、取消和恢复

施工顺序为勘测、移除声明旧流体、方块、原生安装、依赖附件、部件、封洞、新流体、计划内初始内容/过滤/配置、观察、整机 diff。Dev 预览可以要求本机确认。初始截止时间按至少45分钟及目标数估算，子任务有实际进展时可续期；它不是公开的无限运行参数。

同世界的自卫/身体抢占通过 `PREEMPTED` 通知实际子任务并暂停供料，保留已确认效果。取消或超时会停止导航、释放预览、结算子任务、取消补给；不会回滚已放方块、已经使用的桶或已经取走的材料。死亡、换身体、换维度及断线先受公共运行时控制；重启恢复的是目标、历史和消费边界，不是尚未确认的鼠标动作。详见 [任务生命周期](tasks.md)。

施工后的 `observation_code`、结构/形成不符和完整 diff 可以随成功的动作任务返回。真正的子动作失败、材料不足、权限拒绝、无法到达、在途效果未知仍分别返回原因。先核对 `last_native_stage`、`construction_progress`、`cause_code`、`outcome_uncertain`、`mechanical_retry_allowed`，再复用现有蓝图或修改明确目标；不要把 `native_execution_complete:true` 当作重新做一次的理由。

动力线路的会话续接保留实际前段，换来源或要求会重新规划；旧轮、轴和链条仍留在地图。换世界、端点身份变了、某格变成其他物品或存在未结原生连接时，不能凭旧缓存继续。`continuation_token` 由总任务保管，模型不能自行造一个。

## 现行边界与验证入口

- 正式施工仍会经过 `MachineAssemblyDocument.review`、`MachineProcessingRelation.compile` 等编译检查，包含加工净空和引用完整性；稀疏补丁引用未重新声明的处理器会被拒。不能把测试生成器忽略加工说明的规则写成正式施工行为，也不能声称所有预测门控都已移除。
- `inventory_only` 与独立接线的 `allowed_sources:["storage"]` 组合存在优先级问题：[resolveSources](../../common/src/main/java/org/maiwithu/maicraft/core/task/supply/SemanticMaterialSupplyCoordinator.java) 会开放库存及合成。不要把这种组合当作可靠的“只用背包”限制；本轮只记录差异。
- 当前材料策略 `storage_available` 也可能经补给协调器使用普通获取来源，不是“仅打开现货箱”的保证；限制来源要同时对照具体供料执行器。
- 名字本身不授权修改他人机器，外部输入声明也不证明已经接通。Create 不可用、FE/Mek 服务端操作缺失或未支持介质应按回执处理。
- 现有入口包括 [MachineBlueprintAbilityTest](../../common/src/test/java/org/maiwithu/maicraft/intent/MachineBlueprintAbilityTest.java)、[MachineConstructionPlanTest](../../common/src/test/java/org/maiwithu/maicraft/core/integration/machine/MachineConstructionPlanTest.java)、[MachineCompletionArchiveTest](../../common/src/test/java/org/maiwithu/maicraft/core/integration/machine/MachineCompletionArchiveTest.java)、[KineticRouteContinuationTest](../../common/src/test/java/org/maiwithu/maicraft/core/integration/create/transmission/KineticRouteContinuationTest.java) 和 [KineticRpmBudgetTest](../../common/src/test/java/org/maiwithu/maicraft/core/integration/create/transmission/KineticRpmBudgetTest.java)。本轮仅对照源码及静态检查，没有运行这些测试或实机。
- `neoforge/src/blueprintTest` 是显式开启的创造测试执行器，直接生成并不能证明上述第一人称施工流程通过；贡献者不要把其世界写入、夹具投料或档案回放移入生产动作路径。
