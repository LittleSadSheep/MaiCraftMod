# MaiCraft

MaiCraft 是一个 Minecraft 1.21.1 客户端 Mod：LLM 只表达游玩意图，Mod 内的规划器、任务系统和游戏 API 负责寻路、操作、建造与结果确认。

当前正在进行首次完整迁移，尚未通过实机验收；在真实 Minecraft 测试通过前，不把它标记为可用版本。

## 架构

- 玩家只安装一个 MaiCraft Mod，不需要 Python 项目或伴随进程。
- Mod 在 JVM 内嵌 Streamable HTTP MCP，默认地址为 `http://127.0.0.1:8766/mcp`。
- 对 LLM 只公开四个语义工具：`maicraft_perceive`、`maicraft_plan`、`maicraft_execute`、`maicraft_task`。
- 低层工具、技能、配方递归、寻路、菜单点击和方块放置都是 Mod 内部实现，不交给 LLM 逐步控制。
- 所有身体动作共用一个 `LocalPlayer` 任务调度器，并通过第一人称输入与原生客户端交互完成。
- `common`, `fabric`, and `neoforge` are platform source modules in this repository; each loader build produces one installable Mod jar.

## 支持平台

- Fabric 1.21.1
- NeoForge 1.21.1
- Java 21

## 构建

为避免占满机器资源，构建必须禁用并行并只使用一个 worker：

```powershell
.\gradlew.bat build --no-parallel --max-workers=1 --no-daemon
```

## 许可证

MaiCraft 全部代码采用 GNU General Public License v3.0，详见 [LICENSE](LICENSE)。
