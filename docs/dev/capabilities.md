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
| `sequence` | 依次做一组目标 | `Goal.executableSteps` → `IntentTask` | 完成重构；[编排与恢复规则](sequences.md) |
| `remember_place` | 记住地点或区域 | `AbilityAdapter.remember` → `IntentRuntime.remember` | 入口核对 |
| `wait_for_condition` | 等一段时间、天亮或身体条件 | `WaitAbilityAdapter` → `IntentTask.tickWait` | 完成重构；[实现与回归](waiting.md) |
| `chat` | 在真实聊天框输入并提交 | `ChatAbilityAdapter` → `ChatTask` | 完成重构；[发送与恢复规则](chat.md) |
| `suicide` | 死亡不掉落时通过原生危险动作主动寻死 | `SuicideAbilityAdapter` → `SuicideTask` | [用途、参数与生命周期](suicide.md)；已有夹具入口见正文，本轮仅静态复核，尚未实机验收 |
| `travel` | 去指定地点或已观察到的位置 | `AbilityAdapter.travel` | 入口核对；[参数、到达判定与已知边界](travel.md) |
| `travel_dimension` | 准备并通过传送门换维度 | `AbilityAdapter.travelDimension` | 入口核对 |
| `prepare_portal` | 单独准备并点燃传送门，完成后停在门外 | `AbilityAdapter.preparePortal` | 入口核对 |
| `explore` | 跑图勘察，按群系、标签或结构定向发现 | `ExplorationIntent.adapt` | 契约与贡献者文档核对；[地图探索](exploration.md) |
| `find_structure` | 找到游戏中的结构 | `AbilityAdapter.findStructure` | 契约与贡献者文档核对；[结构证据和到访](exploration.md) |
| `find_entity` | 搜索指定种类的实体 | `GeneralAbilityAdapter.findEntity` | 入口核对 |
| `find_block` | 查询附近有没有指定方块 | `GeneralAbilityAdapter.findBlock` | 入口核对 |
| `use_item` | 定点使用或按新增产物有限次持用随身物品 | `GeneralAbilityAdapter.useItem` | [交互与定点使用](interaction.md) |
| `harvest_block` | 精确采收一个观察到的资源方块 | `GeneralAbilityAdapter.harvestBlock` | 入口核对 |
| `collect_items` | 走到掉落物旁靠原生接触拾取 | `GeneralAbilityAdapter.adapt` 的 `COLLECT` 分支 | 入口核对 |
| `follow` | 跟随已识别的目标 | `GeneralAbilityAdapter.follow` | 入口核对 |
| `combat` | 与明确指定的目标战斗 | `GeneralAbilityAdapter.combat` | 入口核对 |
| `interact` | 与方块或实体交互 | `GeneralAbilityAdapter.interact` | [交互与定点使用](interaction.md) |
| `use_container` | 靠近并打开容器 | `GeneralAbilityAdapter.interact` 的容器分支 | 入口核对 |
| `manage_container` | 存入、取出或平衡背包数量 | `GeneralAbilityAdapter.manageContainer` | 入口核对 |
| `consume` | 吃或使用指定物品 | `GeneralAbilityAdapter.consume` | 入口核对 |
| `equip` | 穿戴或手持合适物品 | `GeneralAbilityAdapter.equip` | 入口核对 |
| `drop_items` | 按件数分堆丢弃，可移动、开挖侧袋并尝试点火；特定走廊余物回收后换点 | `GeneralAbilityAdapter.drop` | [丢弃物品](dropping-items.md)：参数、主流程、结果边界与已有回归入口 |
| `fish` | 钓鱼并确认收获 | `GeneralAbilityAdapter.fish` | 入口核对 |
| `sleep` | 找到床并睡觉 | `AbilityAdapter.sleep` | 入口核对 |
| `acquire_items` | 从允许的来源拿到所需物品 | `AcquireAbilityAdapter` | 主执行器与配方推演已通读并重构；八类子任务链继续审阅，[当前实现](acquiring.md) |
| `craft` | 根据配方制作物品 | `AbilityAdapter.craft` | 入口核对 |
| `cook` | 烹饪或烧炼所需物品 | `CookAbilityAdapter` | 主执行器已通读，数量、估价和菜单收尾已重构；[已验证与待审范围](cooking.md) |
| `trade` | 与村民完成指定交易 | `AbilityAdapter.trade` | 入口核对 |
| `enchant` | 使用附魔台完成一次有预算的附魔 | `EnchantAbilityAdapter` | 入口核对；兼容入口 |
| `stonecut` | 在切石机把输入切成指定产物 | `StonecutAbilityAdapter` | 入口核对 |
| `design_build` | 保存、检查、修改或预览建筑设计 | `BuildDesignAdapter`、`BuildingSceneAdapter` | 入口核对 |
| `build` | 供料并按冻结的设计实际施工 | `AbilityAdapter.build`、`BuildProjectAdapter` | 入口核对 |
| `light_area` | 调查指定区域、供料并按实测方块光补足所选覆盖率 | `AbilityAdapter.lightArea` → `SemanticLightAreaCompanionTask` | [参数、流程、回执与已知缺口](lighting.md)；本轮静态核对，未运行回归或实机 |
| `auto_light` | 默认关闭；独立启停或查询沿当前路线的副手补光 | `AutomaticLightingAdapter` → `AutomaticLighting` | [参数、身体让位、采样范围与保护缺口](lighting.md)；现有入口 `lightingRegression`，本轮未运行 |
| `inspect_machine` | 读取机器地图现状、整机差异与原生组件证据 | `MachineAbilityAdapter.inspect` | [机器检查契约与已知边界](machine-inspection.md)；源码静态复核，未运行本轮回归 |
| `design_machine` | 检查机器布局及需求 | `MachineAbilityAdapter.design` | 入口核对 |
| `build_machine` | 供料、搭建并核对机器结构 | `MachineAbilityAdapter.build` | 入口核对 |
| `operate_machine` | 使用机器、转移物品或观察生产 | `MachineAbilityAdapter.operate` | 入口核对 |
| `modify_machine` | 修改已观察机器或接入外部设施 | `MachineAbilityAdapter.modify` | 入口核对 |
| `connect_mechanical_power` | 连接 Create 动力来源与目标 | `AbilityAdapter` → `CreateMechanicalPower` | 入口核对 |
| `reach_milestone` | 完成阶段性生存目标 | `AbilityAdapter.reachMilestone` | 入口核对 |
| `defeat_ender_dragon` | 完成末影龙战斗流程 | `AbilityAdapter.defeatEnderDragon` | 契约与贡献者文档核对；[死亡确认](endgame.md) |
| `obtain_elytra` | 搜寻并取得鞘翅 | `AbilityAdapter.obtainElytra` | 契约与贡献者文档核对；[折跃与入包](endgame.md) |
| `quest_action` | 对 FTB 任务书执行一次原生提交、勾选或领奖 | `QuestAbilityAdapter` | 入口核对；能力发现不按 FTB 门控，未安装时在执行期报 `not_installed` |
| `physical_balance` | 起飞前受力分析、启停模拟与配平推荐 | `PhysicsAbilityAdapter` | 入口核对；读原生受力需要服务端 `physics.snapshot` |
| `physical_assembly` | 蜂蜜胶选区粘接、物理组装器创建与拆回结构 | `PhysicalAssemblyAbilityAdapter` | 入口核对；需安装 Create，粘接布局由模型决定、Mod 只执行原生操作 |
| `physical_control` | 对已观察物理结构执行原生控制 | `PhysicalControlAbilityAdapter` | 入口核对；按声明动作核对控制器及实际效果 |
| `fly_vehicle` | 配置或驾驶已登记的物理飞机 | `AircraftFlightAbilityAdapter` | 入口核对；飞控、巡航与终点落地分别确认 |

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
