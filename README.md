# MaiCraft

MaiCraft 是一个 Minecraft 1.21.1 客户端 Mod：LLM 表达游玩意图、分析环境并提出机器的组件、连接、风格与约束，Mod 内的规划器、任务系统和游戏 API 负责具体布局、校验、寻路、操作、建造与结果确认。

当前正在进行首次完整迁移，尚未通过实机验收；在真实 Minecraft 测试通过前，不把它标记为可用版本。

## 架构

- 玩家只安装一个 MaiCraft Mod，不需要 Python 项目或伴随进程。
- Mod 在 JVM 内嵌 Streamable HTTP MCP，默认地址为 `http://127.0.0.1:8766/mcp`。
- MCP 使用四个入口：`maicraft_perceive`、`maicraft_plan`、`maicraft_execute`、`maicraft_task`；能力契约可以扩展。
- LLM 可提交机器的组件图、连接意图、风格、高层约束和来自真实菜单观察的条目引用；精确方块位置、状态、施工顺序、路径和点击全部由 Mod 负责，公开 MCP 不接受逐格蓝图。
- 所有身体动作共用一个 `LocalPlayer` 任务调度器，并通过第一人称输入与原生客户端交互完成。
- `common`, `fabric`, and `neoforge` are platform source modules in this repository; each loader build produces one installable Mod jar.

## 支持平台

- Fabric 1.21.1
- NeoForge 1.21.1
- Java 21

## 机器理解、设计与操作

采用“标记 → 勘察 → LLM 分析／表达设计意图 → Mod 编译布局并原生执行 → 再观察”的闭环，支持地图内已有机器和新设计。观察是通用的；施工必须由 Mod 内已有的通用规划器或专用适配器编译，不能编译的任意模组机器会明确返回 `semantic_machine_layout_compiler_unavailable`，不会反过来要求 LLM 逐格放置。

- `maicraft:inspect_machine`：按地标或当前位置标记机器，返回注册名、方块属性、相对结构、可见部件证据、候选邻接及快照。相邻不代表连通；未知状态和截断会明确标出。
- `maicraft:design_machine`：校验 LLM 提出的组件连接图、风格、高层空间／维护／吞吐约束与物料数量；按目标产物读取当前客户端同步的配方证据。它不接收方块坐标、状态或施工脚本。
- `maicraft:build_machine`：请求 Mod 把同一份语义设计编译成实体机器。当前没有匹配的原生布局编译器时会明确返回“不支持”，保持现场不变；逐格蓝图不是恢复选项。
- `maicraft:modify_machine`：调用已有的高层专用修改能力；当前包括由 Mod 自动规划 Create 机械动力连接的 `connect_mechanical_power`。
- `maicraft:operate_machine`：打开指定机器的原生菜单，根据菜单观察执行投入／取出、控制现有拉杆，或通过 AE2 终端取料与提交已有合成模式。

结构观察不等于知道机器的所有设置，建造完成也不等于产线已通过运行测试。AE2 部件、化学品／流体接口、侧面配置、过滤器和特殊 GUI 控件需要相应原生接口的真实证据；未覆盖的接口会说明技术缺口，不伪造配置或产量。

首先用 `maicraft_perceive(view="abilities", focus="maicraft:inspect_machine")` 读取契约，例如提交：

```json
{
  "goal": {
    "ability": "maicraft:inspect_machine",
    "outcome": "观察这套加工线的结构、输入输出和动力关系",
    "target": {"kind": "current_place"},
    "parameters": {"label": "加工线", "radius": 4}
  }
}
```

把它传给 `maicraft_execute` 后，用返回的 `task_id` 查询 `maicraft_task(action="get")`。完整机器信息在 `completed_steps[].result.data.machine`；`maicraft_perceive(view="machines")` 返回当前会话的快照摘要。LLM 可以据此解释现有结构、提出缺失证据，并描述组件、连接、风格与高层约束；Mod 决定具体布局和动作。原生菜单打开后，`maicraft_perceive(view="machine_menu")` 提供菜单证据与事务凭据。

快照只在当前世界会话内有效，修改前复查，使用后重新观察。单次范围与输出上限用于控制客户端工作量；大型机器可分区域勘察与施工。已有用户授权可以贯穿整个流程，标记位置本身不会自动授予更改其他玩家机器的权限。

设计原则、语义／执行边界和实机验收矩阵见 [机器能力 ADR](docs/adr/0009-machine-evidence-design-and-execution.md)。

## 构建

为避免占满机器资源，构建必须禁用并行并只使用一个 worker：

```powershell
.\gradlew.bat build --no-parallel --max-workers=1 --no-daemon
```

`common:check` 包含 `machineRegression`，覆盖结构推断、设计验证、原生请求边界与机器能力参数。编译和回归测试不能替代装有目标模组的实机验收。

## 许可证

MaiCraft 全部代码采用 GNU General Public License v3.0 only（SPDX：`GPL-3.0-only`），详见 [LICENSE](LICENSE)。
