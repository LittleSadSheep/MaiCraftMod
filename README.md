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
- **模组机器**：观察和分析 Create、AE2、Mekanism 等机器结构；基于真实菜单证据操作已支持的接口，并可规划 Create 机械动力连接。
- **任务控制**：异步查询、暂停、恢复和取消任务；遇到歧义、风险或缺失条件时返回可处理的决策请求。

与逐格蓝图或远程点击脚本不同，MCP 客户端只描述“要达成什么”。方块位置、路径、施工顺序、菜单操作、重试和结果校验由 MaiCraft 在游戏内负责。

## MCP 入口与语义能力

MaiCraft 在 MCP 的 `tools/list` 中注册四个通用入口：

| 工具 | 用途 |
| --- | --- |
| `maicraft_perceive` | 读取游戏状态、能力契约、任务、地标和机器证据 |
| `maicraft_plan` | 将语义目标编译为计划，但不立即执行 |
| `maicraft_execute` | 启动语义目标或已编译计划，并返回任务 ID |
| `maicraft_task` | 查询、暂停、恢复、取消任务，或回答任务提出的问题 |

四个入口不等于只有四种功能。当前运行时注册了 32 项 `maicraft:*` 语义能力，包括 `inspect_machine`、`design_machine`、`operate_machine`、`build_machine`、`connect_mechanical_power`、`travel`、`acquire_items`、`craft`、`build` 和 `combat` 等。它们作为 `goal.ability` 交给 `maicraft_plan` 或 `maicraft_execute`。

每项能力的参数和限制由 `maicraft_perceive(view="abilities")` 动态公开。AI 客户端应先读取能力契约，再提交目标，而不是猜测方块坐标、物品栏槽位或内部动作。

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

4. 进入世界后，让 AI 客户端先读取 `maicraft_perceive` 提供的能力，再开始任务。

默认服务只监听本机回环地址，但当前默认配置不启用 Bearer Token。**不要通过端口转发、反向代理或隧道将 `8766` 端口暴露给其他设备或公网。**

## 当前限制

- 所有执行依赖客户端实际加载到的世界状态；未观察到的信息会保持未知，不会被假定为事实。
- 自动化会操作本地玩家的真实身体、物品和方块。破坏地形、攻击、丢弃物品或修改机器等行为需要明确授权，但测试世界和备份仍然必不可少。
- 通用机器观察不代表通用机器施工。没有原生布局编译器或菜单适配器时，任务会明确返回不支持，而不会让大模型临时生成逐格操作。
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
