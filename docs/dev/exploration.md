# 地图探索与结构发现

`maicraft:explore` 从角色当前位置跑图、找群系或找结构；`maicraft:find_structure` 专门寻找世界结构。已定位终点的到达精度见 [旅行能力](travel.md)。LLM 根据用途选择地点，Mod 负责移动、观察、确认和保存事实。

## 先选择目标，再填范围

全部字段放在 `goal.parameters`。`explore` 不接受 `goal.target`，四个目标选择器至多填写一个；都省略时执行 `survey` 跑图。

| 参数 | 当前含义 |
| --- | --- |
| `biome_id` | 实际注册的群系 ID；允许模组命名空间 |
| `biome_tag` | 实际注册的群系标签，执行时按标签成员判断 |
| `structure_id` | 世界结构 ID，转入 `find_structure`；与 `travel` 登船用的 UUID 不同 |
| `semantic_target` | `survey`、`coast`、群系 ID 或 `#群系标签`；`coast` 就是 `minecraft:beach` |
| `max_distance` | 群系／跑图 64–2048 格，默认 768；结构 64–4096 格，默认 4096 |
| `direction` | 八方位或 `forward/backward/left/right`，省略时四周搜索；不接受 `up/down` |
| `angle_degrees` | 完整扇区角，1–360°；有方向默认 90°，无方向只能用 360° |
| `min_distance` | 候选地点距起点的最小水平距离；有方向默认 16 格，否则 0；允许 0，不得超过 `max_distance` |
| `transport_mode` | `auto/ground`，默认 `auto`；探索入口没有飞机或喷气背包模式 |
| `may_alter_terrain` | 默认 `false`；明确允许途中挖掘、搭桥或垫高，仍沿用现有保护和通行规则 |
| `reach_structure` | 仅结构搜索，默认 `true`；是否移动到线索地点并再次确认 |
| `allow_rare_consumables` | 仅结构搜索，默认 `false`；要塞搜索需要真实投掷末影之眼的许可 |

相对方向在出发时固定。90° 扇区包括中心方向左右各 45°；约束的是候选位置，角色可以绕湖、绕山。最小距离用于避免刚接单就用脚下群系完成探索，不是让每段路线都直走这么远。

群系／标签选择前可调用 `perceive` 查询目录：

```json
{"view":"exploration","focus":"biomes","query":"beach"}
```

`focus` 还可用 `biome_tags`、`structures`；按返回目录选择实际 ID。结构已注册与已有可用证据配置是两件事，不能据此承诺任意模组结构都已适配。程序依据已加载世界证据，不读取种子或调用隐藏定位指令。

## 完整调用示例

以下是 `plan` 请求；计划就绪后用返回的 `plan_id` 调用 `execute`，并跟随 `next_attention` 等结果。

```json
{
  "goal": {
    "ability": "maicraft:explore",
    "outcome": "在东侧扇区寻找沙滩并记录沿途地形",
    "parameters": {
      "biome_id": "minecraft:beach",
      "direction": "east",
      "angle_degrees": 90,
      "min_distance": 32,
      "max_distance": 768
    }
  }
}
```

```json
{
  "goal": {
    "ability": "maicraft:explore",
    "outcome": "向前跑图，记录实际观察到的地点",
    "parameters": {"direction": "forward", "max_distance": 256}
  }
}
```

```json
{
  "goal": {
    "ability": "maicraft:find_structure",
    "outcome": "投眼寻找要塞并到线索地点复核",
    "parameters": {
      "structure_id": "minecraft:stronghold",
      "max_distance": 4096,
      "allow_rare_consumables": true,
      "reach_structure": true
    }
  }
}
```

结构搜索直接从当前身体起点推进；即使目录接受 `area/prior_result` 目标，也没有先移动到该区域再搜索的实现。要从其他地区出发，应先完成 `travel`。

## 角色实际怎样探索

1. 解析目标和注册表，固定初始维度、起点与方向扇区。
2. 扫描当前加载的地形证据；符合目标时选择可接近位置，原生移动后再次核对。
3. 当前视野未发现目标则选未访问的边界路段，通过实际行走加载新地形。
4. 无法到达某路段时更换候选方向；候选耗尽或搜索范围耗尽时按事实结束，不无限重试相同路段。
5. 把实际看见和到访的地点写入探索记忆；中断或失败不抹掉已经取得的观察。

`survey` 在到达范围边界或走过新边界后耗尽可达候选时可完成，结果标明停止原因。它记录采样观察，不保证逐格覆盖整个圆形范围。定向找群系的 `verified` 与普通跑图完成也不能互换。

结构搜索使用独立的证据配置。要塞依赖原生投眼与方向线索，其他结构依赖已加载世界里的方块组合和现场复核；`reach_structure:false` 不承诺角色已经站在结构旁。

## 结果与长期记忆

群系／跑图回执保留 `target`、`scope`、`max_distance`、`search_sector`、起终点、已观察列数、路段统计及 `exploration_memory`。有目标搜索报告 `verified`；普通跑图报告 `survey_stop_reason` 与 `coverage:"sampled_observed_terrain_not_exhaustive"`。

`frontier_legs_circuit_broken` 表示当前可尝试方向反复不可达；未发现匹配项不表示全世界不存在它。无进展预算、原生无路、搜索范围耗尽和记忆保存异常分别读取，不混成一个“找不到”。

探索结果保存在当前世界的 SQLite 记忆 `exploration` 分区，按需查询，避免每次把历史全文塞进上下文：

```json
{"view":"exploration","focus":"discoveries","query":"minecraft:beach"}
```

按返回的 `details_focus` 读完整地点，按 `next_query` 继续读下一页；`travel_target` 可直接作为后续旅行的 `goal.target`。`pending_places`、`pending_query` 与 `save_errors` 区分待写观察和落盘失败，不能把尚未写入查询快照的数据当作不存在。历史发现不保证当前世界仍未变化。

暂停时归还身体控制，继续仍在当前任务中推进。取消只停止后续搜索，保留已发生的移动、拆改和观察；重启后恢复的是语义任务与记忆，不是旧工作线程或路线。具体控制规则见 [任务执行](tasks.md)。

## 贡献者从哪里读

| 想看什么 | 实现入口 |
| --- | --- |
| 参数互斥与结构分流 | [ExplorationIntent.adapt](../../common/src/main/java/org/maiwithu/maicraft/intent/ExplorationIntent.java) |
| 方向、扇区与最小距离 | [ExplorationSector](../../common/src/main/java/org/maiwithu/maicraft/core/task/explore/ExplorationSector.java) |
| 扫描、路段、复核及跑图完成 | [SemanticExploreCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/explore/SemanticExploreCompanionTask.java) |
| 投眼与结构证据搜索 | [PhysicalStructureSearchCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/structure/PhysicalStructureSearchCompanionTask.java) |
| 模组结构证据配置 | [StructureEvidenceProfiles](../../common/src/main/java/org/maiwithu/maicraft/core/task/structure/StructureEvidenceProfiles.java) |
| 记录沿途实际发现 | [ClientExplorationMemory](../../common/src/main/java/org/maiwithu/maicraft/core/task/explore/ClientExplorationMemory.java) |
| 数据库与按需查询 | [ExplorationMemoryStore](../../common/src/main/java/org/maiwithu/maicraft/intent/persistence/ExplorationMemoryStore.java)、[ExplorationMemoryView](../../common/src/main/java/org/maiwithu/maicraft/mcp/ExplorationMemoryView.java) |

现有验证入口为 `ExplorationRegressionSuite` 和 `VisibleStructureEvidenceTest` 等结构／探索回归；文档整理只做源码对照与示例静态检查，不代表所有模组地形已经实机验收。
