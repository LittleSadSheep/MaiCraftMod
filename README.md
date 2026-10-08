# MaiCraft v1

让 LLM 以第一人称玩 Minecraft 的模组。LLM 通过内嵌的 MCP 服务说"要什么"（"去睡觉"、"做 8 支火把"、"把这片树砍了"），Mod 像一个正常玩家那样用游戏的正常交互把它做成，并如实报告结果。

> **v1 正在建设中。** 这是 `v1` 分支：按 `docs/design/` 的设计从零重建。直播请继续使用 `dev` 分支的 v0，直到 v1 在对应里程碑达到切换标准。

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

GPL-3.0-only，见 `LICENSE`。
