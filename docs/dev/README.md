# 从玩家要做的事读懂 MaiCraft

MaiCraft 操作的是当前玩家。玩家只有一双手、一套按键和一个正在看的界面，所以“去仓库拿木头”和“正在怪物面前举盾”不能各自开一个循环，同时操作角色。

外部 AI 负责提出目标。Mod 负责看清游戏状态，决定这一刻做哪一步，执行原版或模组提供的真实操作，再确认结果。这里最需要维护的不是类的数量，而是这条顺序能否一直说得通。

## 从哪里开始

| 想弄明白什么 | 读哪里 |
| --- | --- |
| **整体分成哪几层、数据怎么流** | **[架构总览](../architecture/README.md)**（建议先读） |
| 某个 MCP 能力最后由谁操作玩家 | [能力入口与审阅进度](capabilities.md) |
| 怎样写清模型契约、贡献者文档和中文业务注释 | [能力说明规范](capability-contracts.md) |
| 机器现状蓝图、整机差异、原生库存与未知项如何交付 | [机器检查](machine-inspection.md) |
| 接单、暂停、取消、恢复为什么分散在几个类里 | [一件任务怎样开始和结束](tasks.md) |
| 等待目标到底等多久，怎样判断天亮或吃饱 | [等待能力](waiting.md) |
| 随行火把怎样让位，区域补光怎样确定范围与复测 | [随行与区域补光](lighting.md) |
| 旅行怎样指定高度容差、精确站位和交通，怎样判断到达 | [旅行能力](travel.md) |
| 定向跑图怎样确认群系／结构，怎样按需读取探索成果 | [地图探索](exploration.md) |
| 杀龙与找鞘翅怎样推进，如何确认胜利或真实入包 | [末地目标](endgame.md) |
| 一组目标如何顺序执行、补前提或跳过 | [顺序目标](sequences.md) |
| 聊天如何保留草稿，恢复后怎样避免重复发送 | [聊天能力](chat.md) |
| 任务书何时能提交、勾选或领奖，怎样区分请求和到账 | [FTB 任务书操作](quests.md) |
| 死亡不掉落时怎样主动寻死，自保和原生重生如何交接 | [主动寻死](suicide.md) |
| 缺物品时怎样选来源、拆配方、补工具并回到原任务 | [物资获取](acquiring.md) |
| 怎样分炉、算燃料、认领菜单并收回本批结果 | [烹饪与炉次收尾](cooking.md) |
| 吃东西、换装备、钓鱼、上床各确认到哪一步 | [日常动作](daily-actions.md) |
| 怎样挑商人、花什么、交易失败还留下什么 | [交易](trading.md) |
| 切石次数、实际产量与重启防重放 | [切石](stonecutting.md) |
| 点击、搬运数量和服务端同步怎样对应 | [菜单搬运与确认](menu-transfers.md) |
| 怎样整份丢物、处理火与回收余物，后续寻路为何避让 | [丢弃物品](dropping-items.md) |
| 改代码时应该沿哪条路径检查 | [修改与验证方法](contributing.md) |

能力表是查代码的地图，也记录哪些链路已经检查过。只找到入口、只看过主流程或只跑过测试，都不能标为“完整审阅”。

## 对外只有四个工具

实际的 MCP 工具名在 [PublicToolCatalog](../../common/src/main/java/org/maiwithu/maicraft/mcp/PublicToolCatalog.java) 中定义。

| 工具 | 玩家能理解的意思 | 调用完成说明什么 |
| --- | --- | --- |
| `perceive` | 看现在的世界、背包、任务或通知 | 得到了观察，不代表某件事已经做成 |
| `plan` | 把目标整理成有编号的步骤清单 | 清单已登记，还没开始走路或施工 |
| `execute` | 开始做这个目标 | 接到了任务，可用返回的编号继续观察 |
| `task` | 查看、暂停、继续、取消，或回答任务的问题 | 对应的任务控制请求已处理 |

“建房子”“找物品”“聊天”等具体能力写在 `goal.ability` 里。它们不是又一套 MCP 工具。

此外还有知识文档、任务通知和聊天流等 MCP 资源。读文档不会自动取得施工权限，看到机器里有物品也不等于证明这台机器刚刚生产了它们。

## 一次请求怎样变成角色动作

```text
玩家提出目标
  → MCP 检查请求格式
  → 把请求交给 Minecraft 客户端线程
  → 确认任务属于当前世界，并处理重复请求
  → 登记总任务
  → 结合眼前世界，把当前目标换成一个具体子任务
  → 每个游戏刻选出唯一的身体使用者
  → 走路、转头、点击、挖掘或放置
  → 等真实游戏状态确认结果
  → 完成当前步骤，或说明为什么停下
```

关键入口分别是：

- [EmbeddedMcpService](../../common/src/main/java/org/maiwithu/maicraft/mcp/EmbeddedMcpService.java)：处理 MCP 传输与请求。
- [MaiCraftRuntimeFacade](../../common/src/main/java/org/maiwithu/maicraft/mcp/MaiCraftRuntimeFacade.java)：切到游戏线程，把公开请求接到运行时。
- [IntentRuntime](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java)：保存总任务、地点、请求编号和世界检查点。
- [IntentTask](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)：推进当前语义步骤及其子任务。
- [ClientRuntime](../../common/src/main/java/org/maiwithu/maicraft/client/runtime/ClientRuntime.java)：安排一整个客户端游戏刻的观察、执行和收尾。
- [CompanionBrain](../../common/src/main/java/org/maiwithu/maicraft/task/CompanionBrain.java)：决定谁在这一刻使用玩家身体。

## 代码里的几个词

| 名字 | 在游戏里具体指什么 |
| --- | --- |
| `Goal` | 想做成的事，例如拿到 32 块石头 |
| `Plan` | 按顺序排列的目标；不等于已经算好的路线或材料方案 |
| `IntentTaskRecord` | 总任务单，记着当前步骤、暂停原因、问题和结果 |
| `TaskRecord` | 一件具体工作的任务单，例如这次移动要去哪里 |
| `Task` | 真正逐刻做事的执行对象 |
| `LocalPlayerContext` | 当前这一刻可以使用的玩家、世界及动作接口；下一刻必须重新取 |
| 身体控制权 | 现在由玩家还是自动任务决定按键和镜头 |
| 回执 | 对一次实际操作的跟踪记录；发出了操作不等于它已经成功 |
| 检查点 | 可保存的目标与已确认进度；不是把旧世界里的路线、菜单和按键照搬回来 |

## 文档怎样保持可信

每个能力的说明必须能回答：什么时候开始，角色具体做什么，先后顺序是什么，靠什么确认完成，缺条件或被打断后怎么办。

说明以当前代码和测试为准。没有接通的入口不能写成已实现；没有实机验证的行为不能写成已经在整合包中验收。发现矛盾时，先记录触发场景，再用回归测试固定问题，最后一起修改实现与文档。
