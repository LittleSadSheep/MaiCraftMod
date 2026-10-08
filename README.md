# MaiCraft v2

第一人称的 Minecraft 伙伴运行时：通过内嵌的 MCP 接口接受 LLM 的语义目标（"去睡觉"、"做 8 支火把"、"把这片树砍了"），由 Mod 像一个讲道理的真人玩家那样，用原生输入完成它，并如实回报结果。

> **v2 正在建设中。** 这是 `v2` 分支：按 `docs/design/` 的设计从零重建。直播请继续使用 `dev` 分支的 v1，直到 v2 在对应里程碑达到切换标准。

## 构建

需要 JDK 21。

```bash
./gradlew :fabric:build :neoforge:build   # 两个加载器打包，产物在 fabric/build/libs 与 neoforge/build/libs
./gradlew :common:test                    # 公共模块测试，含架构护栏
```

Minecraft 1.21.1；NeoForge 21.1.233；Fabric Loader 0.18.1、Fabric API 0.116.7。服务器也需要安装 MaiCraft，没装的普通玩家仍可正常进服。

## 文档

- `docs/design/README.md`：设计总览、现状与阅读顺序
- `AGENTS.md`：对参与开发的 AI 的行为约束（人类贡献者同样适用）
- `CHANGELOG.md`：面向玩家与 Agent 的变化

## 许可证

GPL-3.0-only，见 `LICENSE`。
