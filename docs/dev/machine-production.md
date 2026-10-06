# 使用机器、生产证据与附魔兼容入口

机器已经搭好之后，玩家可能只想打开箱子、切换一个拉杆、做一次附魔，也可能要求一条产线在一段时间内确实把产物送到输出箱。这些不是同一种完成条件。`maicraft:operate_machine` 用 `operation` 选择流程；`maicraft:enchant` 保留旧单件附魔入口，实际进入同一原生机制执行器。

结构设计、施工和动力连接见 [机器设计与施工](machines.md)。完整的游戏内工艺资料由 [processes.md](../../common/src/main/resources/assets/maicraft/knowledge/processes.md) 和匹配位置的 `native_processes` 提供；后者只枚举注册的 v2 机制，空列表不能证明普通机器不能运行。

## 请求层级与共同边界

能力参数位于 `goal.parameters`；v1/v2 清单位于 `goal.parameters.production`；v2 机制参数再放入 `goal.parameters.production.parameters`。不要把附魔预算、配方次数或传送带相对位置混放在外层。

所有 `operate_machine` 分支都要求玩家已有操作授权且 `allow_use:true`。省略取 `false`，不会执行；`null` 不等于默认值。普通机器参数为严格类型和操作白名单，整数不接受小数/溢出，未知字段拒绝。多数操作只接收 `{kind:"landmark"或"area",label:已知机器名}`，不附带坐标或关系；菜单交易、关菜单和取消观察不接收 `target`。

使用已有机器的 `snapshot_id` 是当前连接中的真实观察引用，不是机器持久 ID，也不是任务 ID。编号须与目标同址同名；不会仅因思考时间变长失效。适配器仍按操作检查场地，消费临时绑定。失败若带 `latest_snapshot`，先核对同址新事实再绑定重提，不以新编号重放尚未结算的消耗。

## `operate_machine` 各分支

本表列出每个分支的完整外层参数集合；`operation` 与 `allow_use` 均在其中。字符串编号须来自真实回执，示例中的 `$...` 是明确的绑定占位，不能照抄为编号。

| `operation` | 其他可用字段 | 角色动作及确认 |
| --- | --- | --- |
| `run_production` | 必填 `snapshot_id`、`production`；可选 `protected_labels`、`material_policy` | 运行 v1 网络或 v2 有限原生过程，依据各自证据完成 |
| `watch_production` | 必填 `snapshot_id`、v1 `production`；可选 `minimum_process_events`、`idle_ticks`、`max_duration_ticks` | 走近授权相关位置、注册服务端只读观察后释放身体；注册成功不是生产完成 |
| `cancel_watch` | 必填 UUID `job_id`；省略 `target` | 请求取消本连接/玩家/维度的观察，不关机器；查看 `monitor` 回执判断实际结果 |
| `open_menu` | 必填 `snapshot_id`；可选 `component_index` | 靠近、准备空手、原生右键、等待真正菜单及可见界面；未确认不会重复右键 |
| `close_menu` | 无其他参数，省略 `target` | 关闭本流程拥有的机器菜单，或符合空鼠标/空合成格条件的玩家背包；不关闭任意外来菜单 |
| `deposit` / `withdraw` | 必填 `menu_receipt_id`、`entry_index`、`item_id`；可选 `count`；省略 `target` | 绑定当前菜单的真实条目，验证规则与数量后进行可见原生搬运 |
| `set_control` | 必填布尔 `powered`；可选 `snapshot_id`、`control_label` | 对指定或唯一的原版拉杆设置目标状态：已是目标状态直接成功，否则走近、空手只拨一次并核对。带 `snapshot_id` 时沿用同址观察；省略时按 `target` 就地划范围——`coordinates` 只认该格，`landmark`/`area`（含附近唯一同名告示牌）取半径 4，`nearest` 以角色为中心取半径 6，范围内须恰好一根拉杆，点击前复核这一范围结构未变。不是“开机成功/产出达标”的证明 |
| `ae2_supply` | 必填 `item_id`；可选 `count`、`allow_crafting` | 必须 `target:{kind:"nearest"}`，不能带名字/位置/关系；使用真实可达终端，按许可取现货或提交既有样板合成 |
| `drive_vehicle` | 必填观察所得 UUID `structure_id`，目标为目的地 | 原生移动结构的兼容入口，见专属物理/车辆实现；此分支不接受机器快照、库存和生产字段 |

### 可选数值与开关

| 字段 | 默认、范围和容易混淆之处 |
| --- | --- |
| `snapshot_id` / `menu_receipt_id` | 1..36 字符，必需分支不能省略或给 `null`；只有实际缓存/菜单身份匹配才可用。 |
| `component_index` | 0..767；省略使用观察中心，显式 `0` 使用 `snapshot.relative_blocks[0]`，二者不一定是同一方块。不是任意槽位或全局组件序号。 |
| `entry_index` | 0..511，交易必填，必须来自本次 `perceive(view="machine_menu")`；0是第一个条目。它不由 `component_index` 代替。 |
| `count` | `deposit/withdraw` 默认1、范围1..64；`ae2_supply` 默认1、范围1..256，目标是本次获准的净增量。0不是全部搬走。 |
| `powered` | `set_control` 必填布尔值。`false` 是明确要求关拉杆，不能按“未指定”处理。 |
| `control_label` | 1..160 字符，指已记住地点或附近唯一同名告示牌所在的确切拉杆，须落在本次范围内；省略仅在区域中有唯一拉杆时可判定。 |
| `allow_crafting` | AE2 默认 `false`；`true` 也只许可既有样板，不能凭名称建立新自动化样板。 |
| `material_policy` | `run_production` 缺省 `inventory_only`。v1 外层策略用于配置工具供给，各 source 节点还有独立策略；v2 外层显式值仅接受字面 `inventory_only`，先备原料再运行。 |
| `protected_labels` | `run_production` 可选字符串数组；与继承的保护名称合并，空数组不撤销已有保护。 |
| `minimum_process_events` | watch 每个 process 的原生完成事件下限，默认1，1..100；不是成品件数。 |
| `max_duration_ticks` | watch 默认72000，20..72000游戏刻；标准20 TPS下约1秒..60分钟，卡顿不改成墙钟计时保证。 |
| `idle_ticks` | watch 默认 `min(6000,max_duration_ticks)`，范围20..总时长；区块卸载不会当成已加载无进展。0不表示永不超时。 |

上述字段显式 `null` 都不是合法数值/开关。`production.offset` 的 `[0,0,0]` 则是合法锚点位置；省略它也使用锚点，不能误当成不使用机器。

### 完整示例：开箱及按观察条目取物

前提：实际 `inspect_machine` 已返回名为“加工区”的目标和快照。先把 `$snapshot_id` 与目标替换为同次回执值，再提交这个完整 `plan` 模板：

```json
{
  "goal": {
    "ability":"maicraft:operate_machine",
    "outcome":"打开加工区观察中心的机器菜单",
    "target":{"kind":"landmark","label":"加工区"},
    "parameters":{"operation":"open_menu","snapshot_id":"$snapshot_id","allow_use":true}
  }
}
```

完成后读取 `perceive(view="machine_menu")`。下面假设真实菜单的条目0是允许取出的铁锭，库存至少4件；只复制该回执的编号，不另选目标机器：

```json
{
  "goal": {
    "ability":"maicraft:operate_machine",
    "outcome":"从刚打开的菜单取出四个铁锭",
    "parameters":{
      "operation":"withdraw","menu_receipt_id":"$menu_receipt_id",
      "entry_index":0,"item_id":"minecraft:iron_ingot","count":4,"allow_use":true
    }
  }
}
```

每次交易后旧菜单条目版本可能已变化，重新读当前菜单；这里的菜单刷新与重做机器区域勘测是不同的事情。交易后的事实由 [MachineMenuTransferTask](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachineMenuTransferTask.java) 及底层菜单回执确认，不从鼠标动画猜测数量。

## 两种 `production` 格式

### v2：对已有位置执行有限原生过程

| `production` 字段 | 规则 |
| --- | --- |
| `schema_version` | 必填数值2。字符串 `"2"` 不等价；缺失不会自动选择附魔或转化机制。 |
| `process` | 必填已注册机制 ID；不是产物物品 ID，也不是任意配方的名字。 |
| `offset` | 可省略，默认 `[0,0,0]`；三个精确整数相对本次观察/建造锚点，每轴绝对值不超过当前机器规划半径（默认128）。 |
| `parameters` | 必填对象，允许空对象与否由具体机制决定；不接收按钮号、槽位脚本或伪造回执。 |

目前注册的是 `minecraft:enchanting`、`minecraft:stonecutting`、`ae2:transform`。注册表不是所有可运行机器的列表：Create 压机、流水线等普通原生工作不会因缺少对应 v2 ID 而变成不可运行。

- 附魔见下节；默认可用不表示背包有物品、青金石或足够等级。
- 切石接受 `item_id`、`output_item_id` 和可选 `count`（默认1，1..64次切制），都依赖已携带输入与真实菜单配方；位置来自本次锚点。
- AE2 流体转化接受现场返回的 `recipe_id` 和 `batches`（默认1，1..64）。逐批投料、回收、核对归因；可选原生事件缺失时仅报告客户端实物证据，不冒称已验证原生事件。配方来源与流体条件由安装版本决定。

建造携带 v2 清单时，`NativeProcessTask` 先推进建造子任务，再对实际建成的锚点创建工序。已有机器直接创建具体机制任务。两种入口共享同一消费预约身份，既有原生附魔或投料不能因重新绑了快照就再做一次。

### v1：声明网络、投入预算和生产窗口

字段都在 `goal.parameters.production`。除标记可选的内容，表内结构必须存在；显式 `null` 不等于省略。ID 在各自节点/端口/连线/配置集合中唯一，引用须存在，字符串长度1..1024。

| 层级 | 字段、默认及范围 |
| --- | --- |
| 根 | `schema_version:1`、`nodes`、`ports`、`links`、`target`、`observation`；`configurations` 可省略或 `[]`。 |
| `nodes[]` | `id`、`kind:source/process/transport/sink`、`offset:[x,y,z]`。process 必填真实 `recipe_id`、`batches:1..1000000`；其他 kind 不接受这两项。source 可给 `material_policy`，默认 `inventory_only`；其他 kind 不接受该策略。 |
| `ports[]` | `id`、已有 `node`、相对 `offset`、六面之一 `face`、`medium`、`direction:input/output`。 |
| `links[]` | `id`、端口引用 `from/to`、`medium`、`resource`、有限整数 `amount:1..Long.MAX_VALUE`；`path` 可省略，提供时至少两个相邻三元组，累计路径格不超过目标预算；`configurations` 可省略或引用已声明配置 ID。 |
| `configurations[]` | `id`、节点 `node`、`operation:"machine.configure"`、可选 `stage:configure/start`（默认configure）、必填 `arguments` 对象。 |
| `configurations[].arguments` | 必须 `action`；其余允许键为 `value/clear/item_id/components/resource_id/side/transmission/relative_side/data_type/enabled/mode/recipe_id`。不同原生 action 还会进一步校验；不是任意 NBT。 |
| `target` | 接收节点 `node`、`medium`、`resource`，不是另一个世界位置或目标自然语言。 |
| `observation` | 全部必填：`window_ticks:20..72000`、`minimum_output:1..Long.MAX_VALUE`、`minimum_events:2..10000`、`max_idle_ticks:1..72000`。0不表示无限等待。 |

介质为 `items/fluids/chemicals/energy/kinetic`；数量单位以原生资源观察为准，动力预算表示最低 RPM，不是可搬运库存。普通注册 ID、标签或带组件的资源选择必须最终解析成真实身份；同名物品有不同组件时不能合并成一份已核实原料。

节点数量受组件预算、端口/连线/配置数量受连接预算约束；所有偏移受同一规划半径约束。当前默认值与配置入口见 [机器规划预算](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/MachinePlanningBudget.java)，不能以“改大扫描半径”替代这些规则。

[ProductionGraph](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/production/ProductionGraph.java) 要求物品、流体和化学品依赖图能按有限阶段排序，直接回到上游节点的物料环会被拒绝；能量和动力不计入这项物料环检查。实际机器可以有回流结构，但当前清单需要显式展开有限加工阶段，不能把运行中的物理环线直接当成已支持的无限批次计划。

下面是完整 v1 `plan` 绑定模板，前提是现场确有相邻的原料端、每轮1入1出的加工端和接收端，且这些端口/配方已观察到。将 `$snapshot_id/$recipe_id/$input_resource/$output_resource` 换成真实回执值；若实际工艺需要燃料、动力或其他输入，必须据实增加节点与连线，不能照用此简图宣称条件齐全。

```json
{
  "goal": {
    "ability":"maicraft:operate_machine",
    "outcome":"运行已观察的两批工艺并核对成品交付",
    "target":{"kind":"landmark","label":"已观察生产线"},
    "parameters":{
      "operation":"run_production","snapshot_id":"$snapshot_id","allow_use":true,
      "production":{
        "schema_version":1,
        "nodes":[
          {"id":"s","kind":"source","offset":[0,0,0],"material_policy":"inventory_only"},
          {"id":"p","kind":"process","offset":[1,0,0],"recipe_id":"$recipe_id","batches":2},
          {"id":"o","kind":"sink","offset":[2,0,0]}
        ],
        "ports":[
          {"id":"s-out","node":"s","offset":[0,0,0],"face":"east","medium":"items","direction":"output"},
          {"id":"p-in","node":"p","offset":[1,0,0],"face":"west","medium":"items","direction":"input"},
          {"id":"p-out","node":"p","offset":[1,0,0],"face":"east","medium":"items","direction":"output"},
          {"id":"o-in","node":"o","offset":[2,0,0],"face":"west","medium":"items","direction":"input"}
        ],
        "links":[
          {"id":"feed","from":"s-out","to":"p-in","medium":"items","resource":"$input_resource","amount":2},
          {"id":"deliver","from":"p-out","to":"o-in","medium":"items","resource":"$output_resource","amount":2}
        ],
        "target":{"node":"o","medium":"items","resource":"$output_resource"},
        "observation":{"window_ticks":20,"minimum_output":2,"minimum_events":2,"max_idle_ticks":1200}
      }
    }
  }
}
```

### 前台生产如何形成证据

顺序是能力检查、可选施工、配置、现场准备及精确资源绑定、记录库存/事件基线、有限补料、启动动作、持续观察和最后连接核验。已存在的输入只记一次；补料不能超过冻结预算，也不会每轮重新发送启动动作。

`window_ticks` 表示首尾生产证据的最小跨度，空等不增加产量；`minimum_events` 要求多个原生事件。目标接收端的既有库存、无法归因的增长和消耗前就在途的旧货不能当作本轮新交付。实际输出概率为零的有效工序可以增加工序完成证据，但不能增加目标产量。

v1 前台要求服务器提供 `machine.snapshot/recipe/connections/production_events` 和 `inventory.quote/transfer`；有配置还要求 `machine.configuration` 及相应写操作。客户端模式不能悄悄降低这组证据标准。详细结果看 `production_preparation`、`production_supply`、`production_observation`、`last_server_operation`；只有该前台流程进入 `DONE` 才设置 `machine_production_verified:true`。

### 后台观察与前台验收不能互换

`watch_production` 只注册 v1 的**物品**生产观察：每个 process 必须有且只有一个原生物品产物位置，目标需有物品输入端。注册前角色仍需走近并授权位置；注册后不供料、不配设备、不启动设备、不巡逻，也不强制加载区块。

当前 `MachineWatchTask.specification` 采用 `minimum_process_events`、`idle_ticks`、`max_duration_ticks` 以及清单的 `observation.minimum_output`，**没有把清单的 `window_ticks/minimum_events/max_idle_ticks` 传给后台服务**。这些字段仍要满足 v1 解析格式，不能把它们写大就声称后台会按同一前台窗口验收。这是已记录的行为差异。

注册任务成功返回 `job_id`、`monitor_registered:true`、`machine_production_verified:false`、`uses_player_body_after_registration:false`。后续结果经 Attention 和机器观察返回；`cancel_watch` 成功受理不等于机器被关闭。重连、换维度会使观察作用域失效，旧 `job_id` 不能在另一个会话当作正在监控的证据。

## 一次附魔的完整路径

推荐统一入口 `operate_machine/run_production`，机制为 `minecraft:enchanting`。旧 `maicraft:enchant` 仍能显式调用，但默认能力发现隐藏该兼容别名；它没有 `allow_use` 字段，也不要求机器快照。

| 附魔字段 | 规则 |
| --- | --- |
| `item_id` | 主背包中一件兼容、尚未附魔的物品注册 ID，保留其余组件；不是槽位或物品总数量。 |
| `offer_tier` | 默认1，整数1..3，不自动降档/改档。 |
| `max_levels_spent` | 必填，0..3，指实际扣除玩家等级；0表示不许扣级。显示要求30级不等于消耗30级。 |
| `max_lapis` | 必填，0..3，指青金石件数；0表示不许消耗。 |
| `search_radius` | 仅兼容 `enchant` 接受，默认32，1..64格；目标省略/null或 nearest 时寻找已加载的最近台子。指定坐标/地标不会扩展搜索；v2机制内不能传此字段。 |

以上整数不接受 `null`、小数或缺失的必需预算。创造/无限材料身体也读取实际报价：青金石可为零成本，当前实现仍核对实际扣级（最多为当前等级与所选档位的较小值），不能把创造模式统一写成所有成本为零。

兼容入口的完整请求如下，无需虚构观察 UUID；前提是近处确有原版附魔台，背包已有一本普通书、所需青金石，且玩家达到实际报价的等级门槛：

```json
{
  "goal": {
    "ability":"maicraft:enchant",
    "outcome":"按第一档真实报价附魔一本书，遵守消费上限",
    "parameters":{
      "item_id":"minecraft:book","offer_tier":1,
      "max_levels_spent":1,"max_lapis":1,"search_radius":32
    }
  }
}
```

统一入口使用真实附魔台观察绑定；以下完整模板的 `$snapshot_id` 须替换成同名目标的真实值：

```json
{
  "goal": {
    "ability":"maicraft:operate_machine",
    "outcome":"在观察到的附魔台附魔一件铁镐",
    "target":{"kind":"landmark","label":"已观察附魔台"},
    "parameters":{
      "operation":"run_production","snapshot_id":"$snapshot_id","allow_use":true,
      "material_policy":"inventory_only",
      "production":{
        "schema_version":2,"process":"minecraft:enchanting","offset":[0,0,0],
        "parameters":{"item_id":"minecraft:iron_pickaxe","offer_tier":1,"max_levels_spent":1,"max_lapis":1}
      }
    }
  }
}
```

角色先选择自有单件物品并准备返还容量，不改地形靠近台子，打开可见 GUI，装物品和青金石，等三档报价同步。提交前报价变化会重读并等待稳定，仍核对同一档位和原费用上限；不会依据客户端种子推算隐藏随机结果。

发按钮前先持久化消费预约，随后核对附魔组件、青金石和等级变化。成品与成本可能分包到达，继续等待确认，不据第一次动画重复消费。`CONFIRMED_NOT_APPLIED` 可在同一消费身份下刷新并最多重试两次；未知、已应用或无法恢复确认的交易不能盲重发。

书会转为附魔书；普通装备只允许预期附魔组件改变。成功还要求成品和自有剩余青金石取回、菜单确认关闭。`enchantment_confirmed` 与“成品已入包并完成收尾”是不同阶段；取消后看 `cleanup_status`、`outcome_uncertain` 和 `mechanical_retry_allowed`，不要只看 `button_attempted`。

## 从玩家动作定位代码

| 想看哪一步 | 打开哪个文件/方法 | 重点 |
| --- | --- | --- |
| 操作和参数分流 | [MachineAbilityAdapter.validate/operate](../../common/src/main/java/org/maiwithu/maicraft/intent/MachineAbilityAdapter.java) | 操作白名单、目标、快照、授权与默认值 |
| v1/v2如何选择执行器 | [MachineProductionIntent](../../common/src/main/java/org/maiwithu/maicraft/intent/MachineProductionIntent.java)、[NativeProcessRequest.parse](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/process/NativeProcessRequest.java) | 数值版本、嵌套参数、锚点偏移及模组可用性 |
| 声明的网络怎样校验 | [ProductionManifest.parse](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/production/ProductionManifest.java)、[ProductionDesignCompiler.compile](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/production/ProductionDesignCompiler.java) | 形状、引用、资源身份、配方及未解决证据 |
| 怎么备料、启动、持续观察 | [MachineProductionTask.advance](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/runtime/MachineProductionTask.java) | 基线必须在本轮补料前，启动不重复，末尾复核连接 |
| 怎样避免重复投料或把旧货算新货 | [ProductionInputSupply](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/runtime/ProductionInputSupply.java)、[ProductionOutputMonitor](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/runtime/ProductionOutputMonitor.java) | 总预算、已有输入计账、真实完成/交付事件、接收端净增长 |
| 后台任务为何已经成功却未出货 | [MachineWatchTask.onTick/specification](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/runtime/MachineWatchTask.java)、[MachineWatchService](../../common/src/main/java/org/maiwithu/maicraft/server/machine/watch/MachineWatchService.java) | 注册、arm、释放身体、事件和观察失效 |
| 建完再加工怎样收尾 | [NativeProcessTask.tick/settleChild/stop](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/process/NativeProcessTask.java) | 同一消费屏障，子结果必须结清，世界改变和停止行为 |
| 旧附魔入口如何进入统一过程 | [EnchantAbilityAdapter.adapt](../../common/src/main/java/org/maiwithu/maicraft/intent/EnchantAbilityAdapter.java)、[MinecraftEnchantProcessAdapter](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/process/MinecraftEnchantProcessAdapter.java) | 已有台子定位、旧父目标/消费编号保留、v2参数范围 |
| 角色站位、开菜单和背包条件 | [EnchantCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/enchant/EnchantCompanionTask.java)、[BlockMenuCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/BlockMenuCompanionTask.java) | 既有台子、空工作槽、原生界面所有权；不获取缺料 |
| 报价、扣费、返还怎样确认 | [EnchantmentQuote](../../common/src/main/java/org/maiwithu/maicraft/core/task/enchant/EnchantmentQuote.java)、[EnchantTransaction](../../common/src/main/java/org/maiwithu/maicraft/core/task/enchant/EnchantTransaction.java)、[EnchantMenuFlow](../../common/src/main/java/org/maiwithu/maicraft/core/task/enchant/EnchantMenuFlow.java) | 真实三档、提交前刷新、确认未执行的有限重试、成功当刻成本副本与返还 |

## 暂停、取消、死亡与恢复

公共身体控制见 [任务生命周期](tasks.md)。暂停不撤销服务器已经收到的消耗或搬运，取消也不把放入机器的物品自动变回背包。前台 v1 收尾取消未完成请求、供料和菜单呈现，并释放观察保留；原生结果未知时保留消费事实。后台 watch 取消只解除观察，机器仍按游戏逻辑运行。

建造后执行 v2 的包装任务在自卫抢占时保留同一个子任务，取消/离开世界才结清并释放它。具体附魔/切石/世界转化的消费记录使用 [NativeSubmissionTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/NativeSubmissionTaskRecord.java) 及父任务持久化屏障。重启恢复目标不能恢复旧菜单对象，更不能把“还没收到响应”解释为“没有消费”。先用原 `request_key` 找回任务，核对真实库存和原生证据；不通过重新生成消费编号绕过保护。

附魔时菜单变化、身体替换或物品被外部移动，会停止当前工作流并报告已确认/未知的返还状态。恢复后的当前等级可能已受后来拾取经验影响，历史费用使用确认当刻保存的副本，不拿当前等级重新倒算。

## 已知边界与已有验证入口

- `drive_vehicle` 的公开说明/校验允许 `prior_result`，而机器适配器本地 `resolve` 只处理 current_place、coordinates、landmark、area；该分支无法直接解析这种引用，应由协调会话与物理维护者复核。不能在文档里承诺已支持完整 prior_result 车辆路线。
- 后台 watch 没有采用前台的全部 `observation` 字段，且要求物品产出位置，见上文；形式上可解析的其他介质网络并不保证可注册后台观察。
- v2 是现有适配器白名单，不接受任意 Create 过程 ID；缺服务器事件的世界转化证据与 v1 持续交付证据范围不同。
- 当前 `ae2_supply` 的无名 nearest 已可通过 [PublicTargetContract](../../common/src/main/java/org/maiwithu/maicraft/mcp/PublicTargetContract.java)。机器适配器中“外层还要求名称”的旧注释已过时，不能继续把它当成现存阻塞。
- 现有验证入口有 [MachineProductionContractTest](../../common/src/test/java/org/maiwithu/maicraft/intent/MachineProductionContractTest.java)、[MachineWatchProgressTest](../../common/src/test/java/org/maiwithu/maicraft/server/machine/watch/MachineWatchProgressTest.java)、[EnchantIntentTest](../../common/src/test/java/org/maiwithu/maicraft/intent/EnchantIntentTest.java)、[EnchantDurableCheckpointTest](../../common/src/test/java/org/maiwithu/maicraft/intent/EnchantDurableCheckpointTest.java)。本轮只做源码、JSON、链接与 diff 静态核对，未运行测试或游戏。
