# MaiCraft v1

让 LLM 以第一人称玩 Minecraft 的模组。LLM 通过内嵌的 MCP 服务说"要什么"（"去睡觉"、"做 8 支火把"、"把这片树砍了"），Mod 像一个正常玩家那样用游戏的正常交互把它做成，并如实报告结果。

> **这是 `v1` 分支，按 `docs/design/` 的设计重建。** 已经发布过的版本在 `main`。

## 构建

需要 JDK 21。

```bash
./gradlew :fabric:build :neoforge:build   # 两个加载器打包，产物在 fabric/build/libs 与 neoforge/build/libs
./gradlew :common:test                    # 公共模块测试，含架构检查
```

Minecraft 1.21.1；NeoForge 21.1.233；Fabric Loader 0.18.1、Fabric API 0.116.7。服务器也需要安装 MaiCraft，没装的普通玩家仍可正常进服。

## 文档

- `docs/design/README.md`：设计总览、现状与阅读顺序
- `docs/design/09-命名与术语.md`：每个概念叫什么、哪些叫法容易被误解
- `AGENTS.md`：对参与开发的 AI 的行为约束（人类贡献者同样适用）
- `CHANGELOG.md`：面向玩家与 Agent 的变化

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