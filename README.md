# MaiCraft

![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-62b47a)
![Java 21](https://img.shields.io/badge/Java-21-e76f00)
![Fabric & NeoForge](https://img.shields.io/badge/Loader-Fabric%20%7C%20NeoForge-6f4cbb)
![License GPL-3.0-only](https://img.shields.io/badge/License-GPL--3.0--only-blue)
[![CI](https://github.com/Mai-with-u/MaiCraftMod/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Mai-with-u/MaiCraftMod/actions/workflows/ci.yml)
[![CodeQL](https://github.com/Mai-with-u/MaiCraftMod/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/Mai-with-u/MaiCraftMod/actions/workflows/codeql.yml)

> 让 LLM 以第一人称玩 Minecraft。

MaiCraft 是一个 Minecraft Mod。LLM 通过游戏进程内嵌的 [MCP](https://modelcontextprotocol.io/) 服务说"要什么"（"去睡觉"、"做 8 支火把"、"把这片树砍了"），Mod 像一个正常玩家那样用游戏的正常交互把它做成，并如实报告结果：做到哪一步、拿到了什么、试过哪些办法、卡在哪、还差什么。

MaiCraft 不内置大模型，也不需要额外运行别的服务；你需要一个支持 Streamable HTTP MCP 的 AI 客户端（或宿主程序）和可用的模型。

> [!WARNING]
> 当前是 **1.0.0 的预发布版本（rc）**。1.0 是按新架构从零重建的版本，还没有经过完整的实机验收，覆盖整合包的兼容性也在继续补。请只在备份过的测试世界里使用，不要当成无人值守的代理。

## 1.0 是什么

1.0 取代了 0.1.x：

- 动作全部由客户端以第一人称完成（看、走、挖、放、点界面、合成、打、骑），服务端只提供确认、方块归属与模组数据。服务器也需要安装 MaiCraft；没装的普通玩家仍可正常进服。
- 对外只有五个 MCP 工具，每个只做一件事：`observe` 看世界和自己，`lookup` 查能力与资料，`execute` 下达目标，`goal` 查看与管理目标，`events` 读角色身上发生的事。所有工具用同一个返回格式，所有能力用同一个任务结果结构。
- 能力覆盖出行与寻路、采集与取物、存取与整理、合成与烧制、建造与改建、战斗与陪伴、睡觉与饮食、钓鱼与附魔、交易、载具与出行方式、探索与寻找、末影龙，以及 Create、AE2、Mekanism 等联动模组的机器建造与使用。
- 旧的 0.1.x 保留在 [`v0`](https://github.com/Mai-with-u/MaiCraftMod/tree/v0) 分支，只修影响使用的问题，不再加功能。

每个版本的具体变化见 [`CHANGELOG.md`](CHANGELOG.md)。

## 安装与连接

| 项目 | 要求 |
| --- | --- |
| Minecraft | `1.21.1` |
| Java | `21` |
| Fabric | Fabric Loader `0.18.1+`，并安装 Fabric API |
| NeoForge | `21.1.233+` |
| 安装位置 | 服务端必装；AI 控制的玩家客户端必装；普通玩家客户端可选 |

1. 从 [Releases](https://github.com/Mai-with-u/MaiCraftMod/releases) 下载对应加载器的 JAR，放进 `mods` 目录。
2. 启动装有 MaiCraft 的客户端并进入世界。
3. 在支持 Streamable HTTP 的 MCP 客户端里添加：

```text
http://127.0.0.1:8766/mcp
```

4. 让 AI 先调用 `lookup()` 列出能力，再开始下达目标。

MCP 服务只监听本机回环地址，默认不启用口令。**不要通过端口转发、反向代理或隧道把它暴露给其他设备或公网。** 想换端口，启动时加 JVM 参数 `-Dmaicraft.mcp.port=<端口>`；同一台电脑上同时开多个客户端时，后启动的会在端口被占时自动让到后面的端口，实际地址写在启动日志里。

### 实例配置

第一次启动会在客户端的 `config/maicraft.json` 生成默认配置。这几项只有所有者能改，LLM 的任何工具都碰不到：

- `allowGameCommands`：是否放开 `/tp`、`/give` 这类管理员命令（默认关，只发 `/msg`、`/home` 这类普通命令）。
- `trustedPlayers`：信任的玩家（名字或 UUID），他们放的箱子、AE 网络这类存储角色可以取用和存放，拆改仍当别人的。
- `machineBlockLimits`：一台机器里某种方块最多几个，默认链条输送机最多 1 个。

### 调试

游戏里按 F9 打开调试面板，看角色在干什么、卡在哪、谁在操作身体。要不经过宿主直接调用内嵌端点，用 [`tools/maicraft_cli.py`](tools/README.md)。

## 构建

需要 JDK 21。

```bash
./gradlew :fabric:build :neoforge:build   # 两个加载器打包，产物在 fabric/build/libs 与 neoforge/build/libs
./gradlew :common:test                    # 公共模块测试，含架构检查
./gradlew build                           # 全量：两个加载器加全部测试，CI 在 Linux 与 Windows 上跑这一条
```

只要安装包就用第一条，不触发测试。

## 开发

- `dev` 是日常开发分支，`main` 是发布分支：只有明确要发版时才把 `dev` 合入 `main`，合入后 CI 递增版本、跑完整构建并发布 Release，发布说明取 `CHANGELOG.md` 里对应版本的段落。
- 每个改变游戏里可见行为的提交，同时在 `CHANGELOG.md` 的"未发布"下按领域加一条，写游戏里变了什么。
- 设计文档在 [`docs/design/`](docs/design/README.md)：总览与阅读顺序见 `README.md`，每个概念叫什么见 [`09-命名与术语.md`](docs/design/09-命名与术语.md)，各领域规格在 `docs/design/领域/`。
- [`AGENTS.md`](AGENTS.md) 是对参与开发的 AI 的行为约束，人类贡献者同样适用：提交说明用中文、每个提交签名、新写的代码带中文游戏逻辑注释。

## 许可证

MaiCraft 作为整体以 [GNU General Public License v3.0 only](LICENSE) 发布（SPDX：`GPL-3.0-only`）。本项目是经过修改的作品，自 2026 年 7 月 30 日起由 MaiCraft 维护者修改和维护。

仓库包含以下第三方来源：

- 内嵌寻路代码来自 [Baritone](https://github.com/cabaletta/baritone)，基于上游提交 `5f259b7f` 修改。与 Numen 派生代码相同，本仓库依照 GNU GPLv3 第 7 条移除该副本的 LGPLv3 额外许可及非许可性附加条款，并按 `GPL-3.0-only` 分发；来源和修改说明见 [`third_party/baritone/`](third_party/baritone/)。
- 使用 [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template) 提供的多加载器项目结构。
- 发布包内置 [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) 驱动，嵌套 JAR 保留上游许可证、NOTICE 和平台原生库。

## 鸣谢

- Minecraft 与 Mojang Studios
- [Baritone](https://github.com/cabaletta/baritone)
- [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template)
- [minecraft-numen](https://github.com/Dwinovo/minecraft-numen)
