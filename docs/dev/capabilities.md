# 能力入口与审阅进度

先确定玩家想完成哪件事，再沿这一行往下读。不要看到一个名字相近的类就直接改它：同一项能力可能经过供料、导航、菜单和远端机器请求，旧逻辑也可能仍从另一条入口调用同一个执行器。

## 如何看这张表

下表记录 [IntentRuntime.KNOWN_ABILITIES](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) 中能力的适配入口与审阅进度；完整名单以运行时能力发现为准。

2026-10-04 按实际注册表核对：**49 项语义能力**，其中 `enchant` 为兼容入口，默认能力发现展示 **48 项**。它们通过 MCP 的 `perceive`、`plan`、`execute`、`task` 四个工具访问。新增或修改能力时，按 [能力说明规范](capability-contracts.md) 同步契约、贡献者文档与中文业务注释。

- **入口核对**：已确认能力注册和适配入口；尚未完成该能力全部执行分支的审阅。
- **完整审阅**：参数、动作、结果、暂停取消、换世界和恢复路径均已逐项检查，并列明相关验证。
- **完成重构**：在完整审阅基础上完成必要修改、回归和分支整合。

这几个状态分开记录，避免把机械整理导入或编译通过当成已经读懂整个功能。

## 玩家可提交的目标

下表省略公共前缀 `maicraft:`。参数约定由 [SemanticAbilityCatalog](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java) 给出，实际参数检查还要读 [SemanticGoalContract](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java)。

| 能力 | 玩家要做什么 | 从哪个适配入口继续读 | 当前进度 |
| --- | --- | --- | --- |
| `sequence` | 依次做一组目标 | `Goal.executableSteps` → `IntentTask` | [顺序、待答与失败继续边界](sequences.md)；本轮静态复盘 |
| `remember_place` | 登记或覆盖具名地点 | `AbilityAdapter.remember` → `IntentRuntime.remember` | [地点来源、覆盖与持久化边界](memory.md)；本轮静态复盘 |
| `wait_for_condition` | 先等最短游戏时间，再观察条件 | `WaitAbilityAdapter` → `IntentTask.tickWait` | [参数、时钟与恢复](waiting.md)；本轮静态复盘 |
| `chat` | 可见打字并提交一次聊天或命令 | `ChatAbilityAdapter` → `ChatTask` | [输入框、客户端提交与持久防重](chat.md)；本轮静态复盘 |
| `suicide` | 死亡不掉落时通过原生危险动作主动寻死 | `SuicideAbilityAdapter` → `SuicideTask` | [用途、参数与生命周期](suicide.md)；已有夹具入口见正文，本轮仅静态复核，尚未实机验收 |
| `travel` | 去指定地点或已观察到的位置 | `AbilityAdapter.travel` | 入口核对；[参数、到达判定与已知边界](travel.md) |
| `travel_dimension` | 准备并通过传送门换维度 | `AbilityAdapter.travelDimension` | 入口核对 |
| `prepare_portal` | 单独准备并点燃传送门，完成后停在门外 | `AbilityAdapter.preparePortal` | 入口核对 |
| `explore` | 跑图勘察，按群系、标签或结构定向发现 | `ExplorationIntent.adapt` | 契约与贡献者文档核对；[地图探索](exploration.md) |
| `find_structure` | 找到游戏中的结构 | `AbilityAdapter.findStructure` | 契约与贡献者文档核对；[结构证据和到访](exploration.md) |
| `find_entity` | 搜索指定种类的实体 | `GeneralAbilityAdapter.findEntity` | [实际可见数量与搜索范围](combat.md)；本轮静态复盘 |
| `find_block` | 查询附近有没有指定方块 | `GeneralAbilityAdapter.findBlock` | [只读扫描、池面用途和已知边界](mining.md)；本轮静态复盘 |
| `use_item` | 定点使用或按新增产物有限次持用随身物品 | `GeneralAbilityAdapter.useItem` | [交互与定点使用](interaction.md) |
| `harvest_block` | 精确采收一个观察到的资源方块 | `GeneralAbilityAdapter.harvestBlock` | [源格破坏与产物入包](mining.md)；本轮静态复盘 |
| `place_block` | 在精确坐标用随身物品放置一个方块及其状态 | `GeneralAbilityAdapter.placeBlock` | 入口核对；实机验收待安排 |
=======
| `acquire_items` | 从允许的来源拿到所需物品 | `AcquireAbilityAdapter` | 主执行器与配方推演已通读并重构；八类子任务链继续审阅，[当前实现](acquiring.md) |
| `craft` | 根据格子配方补足主背包目标数量 | `AbilityAdapter.craft` | [工作台、配方和收尾](crafting.md)；本轮静态复盘 |
| `cook` | 用炉子补足主背包成品数量 | `CookAbilityAdapter` → `SemanticCookCompanionTask` | [配方、备料、炉次收尾与恢复限制](cooking.md)；本轮静态复盘 |
| `trade` | 按真实报价补足主背包目标数量 | `AbilityAdapter.trade` → `SemanticTradeCompanionTask` | [付款政策、结果与未接通边界](trading.md)；本轮静态复盘 |
| `enchant` | 使用附魔台完成一次有预算的附魔 | `EnchantAbilityAdapter` | [原生附魔及消费确认](machine-production.md)；兼容入口，本轮静态复盘 |
| `stonecut` | 将随身输入按次数整批切制 | `StonecutAbilityAdapter` → `StonecutterMenuFlow` | [实际产量、回执与持久防重](stonecutting.md)；本轮静态复盘 |
| `design_build` | 保存、检查、修改或预览建筑设计 | `BuildDesignAdapter`、`BuildingSceneAdapter` | [设计操作与预览边界](building.md)；本轮静态复盘 |
| `build` | 供料并按冻结的设计实际施工 | `AbilityAdapter.build`、`BuildProjectAdapter` | [冻结蓝图、施工和恢复](building.md)；本轮静态复盘 |
| `light_area` | 调查指定区域、供料并按实测方块光补足所选覆盖率 | `AbilityAdapter.lightArea` → `SemanticLightAreaCompanionTask` | [参数、流程、回执与已知缺口](lighting.md)；本轮静态核对，未运行回归或实机 |
| `auto_light` | 默认关闭；独立启停或查询沿当前路线的副手补光 | `AutomaticLightingAdapter` → `AutomaticLighting` | [参数、身体让位、采样范围与保护缺口](lighting.md)；现有入口 `lightingRegression`，本轮未运行 |
| `inspect_machine` | 读取机器地图现状、整机差异与原生组件证据 | `MachineAbilityAdapter.inspect` | [机器检查契约与已知边界](machine-inspection.md)；源码静态复核，未运行本轮回归 |
| `design_machine` | 检查机器布局及需求 | `MachineAbilityAdapter.design` | [作者声明与审阅](machines.md)；本轮静态复盘 |
| `build_machine` | 供料、搭建并核对机器结构 | `MachineAbilityAdapter.build` | [原生装配与整机差异](machines.md)；本轮静态复盘 |
| `operate_machine` | 使用机器、转移物品或观察生产 | `MachineAbilityAdapter.operate` | [操作分支、原生工序与后台观察](machine-production.md)；本轮静态复盘 |
| `modify_machine` | 修改已观察机器或接入外部设施 | `MachineAbilityAdapter.modify` | [整机修改和声明合并](machines.md)；本轮静态复盘 |
| `connect_mechanical_power` | 连接 Create 动力来源与目标 | `AbilityAdapter` → `CreateMechanicalPower` | [动力接线与真实连接证据](machines.md)；本轮静态复盘 |
| `reach_milestone` | 完成阶段性生存目标 | `AbilityAdapter.reachMilestone` | 入口核对 |
| `defeat_ender_dragon` | 完成末影龙战斗流程 | `AbilityAdapter.defeatEnderDragon` | 契约与贡献者文档核对；[死亡确认](endgame.md) |
| `obtain_elytra` | 搜寻并取得鞘翅 | `AbilityAdapter.obtainElytra` | 契约与贡献者文档核对；[折跃与入包](endgame.md) |
| `quest_action` | 对 FTB 任务书执行一次原生提交、勾选或领奖 | `QuestAbilityAdapter` | 契约、执行源码与贡献者文档核对；[动作、回执和恢复](quests.md)。本轮仅静态检查，已列明结果确定性与观察收尾边界；只读索引状态和动作失败标签分开说明 |
| `physical_balance` | 起飞前受力分析、启停模拟与配平推荐 | `PhysicsAbilityAdapter` | 契约、主流程和回执静态核对；[参数、预测与施工边界](physics.md#physical_balance受力与推荐)；未新增实机验收 |
| `physical_assembly` | 强力胶/蜂蜜胶粘接、物理组装器创建与拆回 | `PhysicalAssemblyAbilityAdapter` | 契约、主流程和回执静态核对；[声明继承、原生转换与材料结算](physics.md#physical_assembly胶层结构转换和声明继承) |
| `physical_control` | 配置部件、无线打字机配键和有限输入 | `PhysicalControlAbilityAdapter` | 契约、主流程和回执静态核对；[字段互斥、反馈与松键边界](physics.md#physical_control配置有限输入与反馈) |
| `fly_vehicle` | 登记飞机映射并原生登机执行持续飞控 | `AircraftFlightAbilityAdapter` | 契约、主流程和回执静态核对；[真实起降、停稳与旅行衔接](flight.md)；完整飞艇/固定翼自动起降仍待实机验收 |

`enchant` 保留兼容已有调用。默认能力发现不展示它，指定该能力查询时仍能取得契约；新机器工序走统一机器入口。能力“已登记”、当前加载的模组“支持”、眼前条件“可以执行”是三件不同的事。

`suicide` 由模型明确调用，低饱食度或路远不会自动触发。单人世界读取真实 `keepInventory`，多人服需依据已知设置传入 `keep_inventory_confirmed: true`；不修改规则。默认 `method=auto` 从三类已观察机会中选取，候选搜索半径 24 格、地形站位上下 12 格，总预算为 120 个按执行 tick 折算的秒；搜索半径不是整个追逐路线的边界。角色只步行接近、进入岩浆、靠怪或踏出高处，不直接扣血或施工。寻死取得执行资格时自保让位，暂停或结束后恢复调度资格，原生伤害和已提交效果不会回滚。成功只确认本轮身体死亡；默认请求原生重生，`auto_respawn: false` 可关闭 Mod 自动请求，实际重生另看 `agent.respawned`。后继序列保持暂停，等待重新判断现场；完整参数、返回字段和已知差异见 [主动寻死](suicide.md)。

适配代码位置：

- [AbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java)
- [GeneralAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/GeneralAbilityAdapter.java)
- [MachineAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/MachineAbilityAdapter.java)

## 所有能力共用的部分

| 链路 | 当前进度 | 已落实的检查 |
| --- | --- | --- |
| 接单 → 创建执行器 → 失败结算 → 后继任务 | 已修复并通过回归 | 构造异常、启动异常、运行异常分别验证；失败只交付一次，后继任务可完成 |
| 客户端观察 → 暂停判断 → 执行 → 存档 → 导航收尾 | 已整理并通过回归 | 合并重复存档分支，保留原判断顺序和异常收尾边界 |
| 重复 `execute` → 原任务查询 → 玩家控制权 | 已修复并通过回归 | 经真实 MCP 运行入口验证：重试复用旧记录且不请求接管；无效参数仍拒绝 |
| 恢复记录 → 直接取消 → 通知与保存 | 已修复并通过回归 | 经真实 MCP 运行入口验证：当前其他任务不受影响、无原生动作、终态可保存、重复取消不再通知 |
| 满额历史 → 新接单 → 检查点完整性 | 已修复并通过回归 | 满额未完成记录阻止新接单；明确取消旧事后可接单；四类记录超量都拒绝保存，旧请求编号不被截断 |
| 网络撤回 → 客户端队列 → 实际接单 | 已简化并通过回归 | 撤回排队请求不产生任务或接管；执行中的请求报告已经开始，并交回真实结果 |
| 子任务证据 → 对外结果 → 通知与保存 | 已分离结果投影并通过回归 | 采掘、供料和建造证据保留，成败与超时事实不被显示层改写 |
| 子任务暂停、计时及在途动作 | 待专门审阅 | 不把“暂停任务”简单等同于“暂停服务端已经收到的操作” |

## 每一项达到完成需要什么

1. 从公开契约找出所有参数和操作分支。
2. 跟到实际创建的任务、内部工具及可替代后端。
3. 按玩家动作写清主流程，检查每个等待、重试和失败出口。
4. 查找其他入口和旧实现，确认它们不会绕过新规则。
5. 检查暂停、取消、死亡、换维度、断线和恢复时留下什么。
6. 用具体场景验证结果，更新对应文档，再整合分支。
