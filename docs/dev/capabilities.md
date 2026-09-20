# 能力入口与审阅进度

先确定玩家想完成哪件事，再沿这一行往下读。不要看到一个名字相近的类就直接改它：同一项能力可能经过供料、导航、菜单和远端机器请求，旧逻辑也可能仍从另一条入口调用同一个执行器。

## 如何看这张表

下表列出 [IntentRuntime.KNOWN_ABILITIES](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) 的 35 项能力。

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
| `travel` | 去指定地点或已观察到的位置 | `AbilityAdapter.travel` | 入口核对 |
| `travel_dimension` | 准备并通过传送门换维度 | `AbilityAdapter.travelDimension` | 入口核对 |
| `find_structure` | 找到游戏中的结构 | `AbilityAdapter.findStructure` | 入口核对 |
| `find_entity` | 搜索指定种类的实体 | `GeneralAbilityAdapter.findEntity` | 入口核对 |
| `follow` | 跟随已识别的目标 | `GeneralAbilityAdapter.follow` | 入口核对 |
| `combat` | 与明确指定的目标战斗 | `GeneralAbilityAdapter.combat` | 入口核对 |
| `interact` | 与方块或实体交互 | `GeneralAbilityAdapter.interact` | 入口核对 |
| `use_container` | 靠近并打开容器 | `GeneralAbilityAdapter.interact` 的容器分支 | 入口核对 |
| `manage_container` | 存入、取出或平衡背包数量 | `GeneralAbilityAdapter.manageContainer` | 入口核对 |
| `consume` | 吃或使用指定物品 | `GeneralAbilityAdapter.consume` | 入口核对 |
| `equip` | 穿戴或手持合适物品 | `GeneralAbilityAdapter.equip` | 入口核对 |
| `drop_items` | 按要求把物品丢到目标区域 | `GeneralAbilityAdapter.drop` | 入口核对 |
| `fish` | 钓鱼并确认收获 | `GeneralAbilityAdapter.fish` | 入口核对 |
| `sleep` | 找到床并睡觉 | `AbilityAdapter.sleep` | 入口核对 |
| `acquire_items` | 从允许的来源拿到所需物品 | `AcquireAbilityAdapter` | 主执行器与配方推演已通读并重构；八类子任务链继续审阅，[当前实现](acquiring.md) |
| `craft` | 根据配方制作物品 | `AbilityAdapter.craft` | 入口核对 |
| `cook` | 烹饪或烧炼所需物品 | `CookAbilityAdapter` | 主执行器已通读，数量、估价和菜单收尾已重构；[已验证与待审范围](cooking.md) |
| `trade` | 与村民完成指定交易 | `AbilityAdapter.trade` | 入口核对 |
| `enchant` | 使用附魔台完成一次有预算的附魔 | `EnchantAbilityAdapter` | 入口核对；兼容入口 |
| `design_build` | 保存、检查、修改或预览建筑设计 | `BuildDesignAdapter`、`BuildingSceneAdapter` | 入口核对 |
| `build` | 供料并按冻结的设计实际施工 | `AbilityAdapter.build`、`BuildProjectAdapter` | 入口核对 |
| `light_area` | 给实际识别出的区域补光 | `AbilityAdapter.lightArea` | 入口核对 |
| `inspect_machine` | 观察机器及其接口和结构 | `MachineAbilityAdapter.inspect` | 入口核对 |
| `design_machine` | 检查机器布局及需求 | `MachineAbilityAdapter.design` | 入口核对 |
| `build_machine` | 供料、搭建并核对机器结构 | `MachineAbilityAdapter.build` | 入口核对 |
| `operate_machine` | 使用机器、转移物品或观察生产 | `MachineAbilityAdapter.operate` | 入口核对 |
| `modify_machine` | 修改已观察机器或接入外部设施 | `MachineAbilityAdapter.modify` | 入口核对 |
| `connect_mechanical_power` | 连接 Create 动力来源与目标 | `AbilityAdapter` → `CreateMechanicalPower` | 入口核对 |
| `reach_milestone` | 完成阶段性生存目标 | `AbilityAdapter.reachMilestone` | 入口核对 |
| `defeat_ender_dragon` | 完成末影龙战斗流程 | `AbilityAdapter.defeatEnderDragon` | 入口核对 |
| `obtain_elytra` | 搜寻并取得鞘翅 | `AbilityAdapter.obtainElytra` | 入口核对 |

`enchant` 保留兼容已有调用。默认能力发现不展示它，指定该能力查询时仍能取得契约；新机器工序走统一机器入口。能力“已登记”、当前加载的模组“支持”、眼前条件“可以执行”是三件不同的事。

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
