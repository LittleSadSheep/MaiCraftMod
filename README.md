# MaiCraft

![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-62b47a)
![Java 21](https://img.shields.io/badge/Java-21-e76f00)
![Fabric & NeoForge](https://img.shields.io/badge/Loader-Fabric%20%7C%20NeoForge-6f4cbb)
![License GPL-3.0-only](https://img.shields.io/badge/License-GPL--3.0--only-blue)

> 让支持 MCP 的 AI 代理在 Minecraft 中感知环境、规划目标，并通过真实的第一人称操作完成任务。

MaiCraft 是一个仅客户端运行的 Minecraft Mod。它在游戏进程内提供本地
[Model Context Protocol（MCP）](https://modelcontextprotocol.io/) 服务，把大模型给出的语义目标转换为寻路、采集、合成、交互、建造等具体游戏行为。

MaiCraft 不内置大模型，也不要求额外运行 Python 服务；你仍需准备一个支持 Streamable HTTP MCP 的 AI 客户端和可用模型。服务端无需安装 MaiCraft。

> [!WARNING]
> MaiCraft 目前处于 `0.1.0` 预览阶段，尚未发布稳定构建，也没有完成覆盖模组整合包的实机验收。请只在备份过的测试世界中使用，不要把它当作无人值守的生产级代理。

## 主要能力

- **感知与记忆**：读取当前状态、周边环境、任务进度、地标和机器快照。
- **移动与探索**：前往坐标或语义地点、寻找结构和实体、跨维度旅行，并处理游泳、开门和受限地形改造。
- **生存流程**：采集物品、合成、烹饪、交易、整理容器、进食、装备、钓鱼、睡觉和照明。
- **建造与进度目标**：根据用途、尺寸、风格和材料策略生成并执行建筑计划，也可组合多个目标形成连续任务。
- **战斗与里程碑**：处理防御或明确授权的战斗任务，并支持末影龙、鞘翅等长流程目标。
- **模组机器**：从组件关系与工艺模块生成 Create、AE2、Mekanism 和混合设备布局，规划输送网络，分批供料施工，安装 AE2 部件与存储盘，配置已支持的 Mek 接口，并核验机器几何及同步运行证据。
- **Dev 蓝图预览**：在世界中显示半透明待建结构和差异轮廓，支持逐层查看；确认前归还玩家操控，确认后执行冻结的方案。
- **只读房屋设计**：`maicraft:design_build` 直接显示蓝图，不移动或施工；普通建造在 Dev 确认之后才开始供料。
- **按需知识资源**：自动发现安装模组的 Ponder 教程，按组件和场景读取原始旁白、操作提示及方块状态；支持 MCP Resources 和 `perceive(view="knowledge")`。
- **任务观察与控制**：优先通过 Attention 事件等待取得权威状态、决策和完整终态结果；支持暂停、恢复和取消。
- **游戏聊天与命令**：打开原版聊天框逐字输入，再自动提交消息或 `/` 命令；支持后台运行和人工接管。

与逐格蓝图或远程点击脚本不同，MCP 客户端只描述“要达成什么”。方块位置、路径、施工顺序、菜单操作、重试和结果校验由 MaiCraft 在游戏内负责。

## MCP 入口与语义能力

MaiCraft 在 MCP 的 `tools/list` 中注册四个通用入口：

| 工具 | 用途 |
| --- | --- |
| `perceive` | 以 Attention 为首选等待任务事件、决策和结果；也读取游戏状态、能力契约与世界证据 |
| `plan` | 将语义目标编译为计划，但不立即执行 |
| `execute` | 异步启动目标或计划，返回任务 ID 和可直接传给 `perceive` 的 `next_attention` |
| `task` | 显式检查/恢复任务，暂停、恢复、取消，或回答问题；日常等待使用 Attention |

以上是服务器在 MCP `tools/list` 中注册的名称；客户端可以附加服务器前缀来区分不同连接。旧版使用的 `maicraft_` 工具名前缀已移除，升级后请让客户端重新获取工具列表，并更新固定工具名配置。

执行后按返回的 `next_attention` 等待，读取响应中的 `task` 和 `wake_reason`，再按新的 `next_attention` 续等。Attention 直接引用任务记录，即使历史事件已被挤出缓存，也能返回仍保留的任务决策和最终结果；无需轮询 `task(get)` 或包装同步执行工具。原生资源订阅可使用 `maicraft://attention`（任务监控）和 `maicraft://chatflow`（收到的游戏内聊天，供专门对话的 Agent 使用），模型唤醒行为由宿主决定。

四个入口不等于只有四种功能。运行时提供多项 `maicraft:*` 语义能力，包括 `chat`、`inspect_machine`、`design_machine`、`operate_machine`、`build_machine`、`connect_mechanical_power`、`travel`、`acquire_items`、`craft`、`build` 和 `combat` 等。它们作为 `goal.ability` 交给 `plan` 或 `execute`。

每项能力的参数和限制由 `perceive(view="abilities")` 动态公开。AI 客户端应先读取能力契约，再提交目标，而不是猜测方块坐标、物品栏槽位或内部动作。

例如将以下参数交给 `execute`，会自动打开聊天框、逐字输入并发送；`plan` 只校验和规划，不打开界面：

```json
{
  "goal": {
    "ability": "maicraft:chat",
    "outcome": "在游戏里向大家问好",
    "parameters": {"text": "大家好，我回来了！", "typing_interval_ms": 100}
  },
  "request_key": "greeting-001"
}
```

`text` 以 `/` 开头时走原版命令流程，例如 `/home`；服务器命令和客户端模组命令沿用当前玩家的权限与加载器处理。文字必须为单行，长度最多 256 个 UTF-16 字符，空白按原版规则整理。每个完整显示字符默认间隔 100 毫秒，可设置为 50–1000 毫秒；全部输入后停留 250 毫秒再自动提交。中文、组合 emoji 和重音组合不会被拆开显示。

无需窗口前台或模拟键盘。任务可从失焦产生的不可见暂停画面开始；保留玩家手动打开的暂停菜单、容器和已有聊天草稿。低帧率下输入会变慢，不会一次补打很多字。按 Esc 关闭或手动编辑/切换界面会取消自动发送；任务暂停会释放界面，恢复后继续原草稿。

同一次逻辑发送的网络重试应复用 `request_key`，新的消息使用新的键。结果中的 `delivery_status="submitted_to_client"` 表示已调用原版提交入口，服务器接收和命令执行效果仍需观察 Attention 中的后续消息。提交结果不确定时不会自动重发。

移动目标还未定位时，可以使用 `maicraft:travel` 的 `semantic_target="platform"` 与 `direction="down"`，让 Mod 边移动边寻找下方平台，无须给坐标。`transport_mode="jetpack"` 保持同一次飞行控制，在平台进入局部观察后转入着陆；`ground` 使用普通步行寻路，`auto` 可选择可用的喷气背包。

所有平台搜索都使用 `semantic_target="platform"`，通过 `direction` 选择方向（`up`、`down`、`forward`、`backward`、`left`、`right` 或四个英文方位，默认 `forward`）。相对方向在任务开始时固定；区域搜索半径 `max_distance` 默认 64，范围 8–128 格。普通坐标移动仍支持省略 Y 和到达容差，`exact=true` 用于需要准确站位的动作。

`perceive(view="surroundings")` 的 `terrain_overview` 提供地形缩略信息：大致方位、相对高度、水平范围、`surface_material` 材质、支撑样本及未知区域。预览覆盖已加载地形的水平半径 128 格、向下 256 格，按距离使用 4／8／32 格采样间距。同一次请求会等待后续客户端帧补充结果；达到采样或响应预算后返回明确的完整／部分采样状态。远处不同材质的支撑面会优先保留，未采到的平台不代表不存在。

飞行航点允许高度偏差和观察区域内到达，后续路径仍检查真实身体碰撞。静止飞艇的已验证下降柱支持关包快速下降；确认无伤的短落可以直接关包到地面。地面移动会退出喷气背包飞行模式，落地保护也会在材料就绪后退出缓慢悬停；下一次飞行任务按需重新启用背包。

电梯的实际楼层见 `surroundings.elevators`，也可用 `perceive(view="situation", focus="maicraft:travel")` 读取。`maicraft:travel` 支持 `elevator_floor="top"`、`bottom`、`next_up`、`next_down` 或同步列表中的楼层 ID／名称；可用 `elevator_id` 指定轿厢，交通模式使用 `auto` 或 `elevator`，无须填写目的地高度。

使用 `elevator_floor="ask"`，或仅指定 `transport_mode="elevator"` 而不提供目的地，会先到电梯附近同步楼层，再返回 `waiting_for_decision`。LLM 用 `task(action="answer")` 的 `retry` 和 `details.parameters` 选择 `elevator_id`、`elevator_floor`。同步楼层是中间步骤，实际乘梯并出梯后才完成移动目标；`needs_sync` 表示信息未知，不代表没有楼层。

## 快速开始

### 运行要求

| 项目 | 要求 |
| --- | --- |
| Minecraft | `1.21.1` |
| Java | `21` |
| Fabric | Fabric Loader `0.18.1+`，并安装 Fabric API |
| NeoForge | `21.1.233+` |
| 安装位置 | 客户端 |

### 从源码构建

当前没有稳定版下载，请克隆仓库后自行构建：

```powershell
git clone https://github.com/LittleSadSheep/MaiCraftMod.git
cd MaiCraftMod
.\gradlew.bat build --no-daemon --no-parallel --max-workers=1
```

Linux 或 macOS 使用：

```bash
./gradlew build --no-daemon --no-parallel --max-workers=1
```

构建产物位于：

- Fabric：`fabric/build/libs/maicraft-fabric-1.21.1-<version>.jar`
- NeoForge：`neoforge/build/libs/maicraft-neoforge-1.21.1-<version>.jar`

将与你的加载器匹配、文件名不含 `sources` 的 JAR 放入客户端 `mods` 目录。Fabric 版本还需要 Fabric API。

### 连接 MCP 客户端

1. 启动装有 MaiCraft 的 Minecraft 客户端。
2. 在游戏中执行 `/maicraft status`，确认 MCP 显示为可用。
3. 在支持 Streamable HTTP 的 MCP 客户端中添加以下地址：

```text
http://127.0.0.1:8766/mcp
```

4. 进入世界后，让 AI 客户端先读取 `perceive` 提供的能力，再开始任务。

默认服务只监听本机回环地址，但当前默认配置不启用 Bearer Token。**不要通过端口转发、反向代理或隧道将 `8766` 端口暴露给其他设备或公网。**

## 当前限制

- 所有执行依赖客户端实际加载到的世界状态；未观察到的信息会保持未知，不会被假定为事实。
- 自动化会操作本地玩家的真实身体、物品和方块。破坏地形、攻击、丢弃物品或修改机器等行为需要明确授权，但测试世界和备份仍然必不可少。
- 机器编译器支持已建模的组件、工艺模块与接口。未知模组结构或介质会返回具体编译问题；机器摆放、配置、成型和实际产出使用分别可核验的证据。
- AE2、Create、Mekanism 及其他模组的兼容能力仍在扩展；特殊 GUI、过滤器、侧面配置和动态配方可能无法操作。
- Fabric 与 NeoForge 构建可以通过自动回归测试，但这不能替代真实客户端、服务器和整合包测试。

## 开发

仓库采用多加载器结构：

| 目录 | 内容 |
| --- | --- |
| `common/` | MCP、语义任务、第一人称执行、寻路与共享游戏逻辑 |
| `fabric/` | Fabric 客户端入口和加载器配置 |
| `neoforge/` | NeoForge 客户端入口和加载器配置 |
| `third_party/baritone/` | 内嵌寻路代码及其许可证 |

完整验证：

```powershell
.\gradlew.bat check --no-daemon --no-parallel --max-workers=1
```

提交问题时请附上 Minecraft 版本、加载器及版本、相关模组列表、复现步骤，以及日志中与 `MaiCraft` 有关的片段。欢迎提交聚焦单一问题的 Issue 和 Pull Request。

## 许可证

MaiCraft 作为整体以 [GNU General Public License v3.0 only](LICENSE) 发布（SPDX：`GPL-3.0-only`）。本项目是经过修改的作品，自 2026 年 7 月 30 日起由 LittleSadSheep 修改和维护。

仓库包含以下第三方来源：

- 部分代码派生自 [minecraft-numen](https://github.com/Dwinovo/minecraft-numen) 的 `1.21.1` 分支，原许可证为 `LGPL-3.0-only`。本仓库依照 GNU GPLv3 第 7 条移除该副本的 LGPLv3 额外许可，将修改后的 Numen 派生代码按 `GPL-3.0-only` 分发；原项目及贡献者仍保留其版权。本仓库不包含 Numen 的美术、音频或品牌资产。
- 内嵌寻路代码来自 [Baritone](https://github.com/cabaletta/baritone)，基于上游提交 `5f259b7f` 修改。与 Numen 派生代码相同，本仓库依照 GNU GPLv3 第 7 条移除该副本的 LGPLv3 额外许可及非许可性附加条款，并按 `GPL-3.0-only` 分发；来源和修改说明见 [`third_party/baritone/`](third_party/baritone/)。
- 使用 [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template) 提供的多加载器项目结构。

## 鸣谢

- Minecraft 与 Mojang Studios
- [minecraft-numen](https://github.com/Dwinovo/minecraft-numen)
- [Baritone](https://github.com/cabaletta/baritone)
- [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template)
