# 统一机器蓝图 JSON

Ponder 结构资源与模型自编蓝图使用同一格式。先读相关方块的使用说明，再读取所需章节结构；需要调整布局时直接编辑 blocks。完整教程和原始 NBT 不必反复加入对话。

```json
{
  "schema_version": 1,
  "blocks": [
    {"offset": [0, 0, 0], "block_id": "minecraft:barrel", "properties": {"facing": "north"}},
    {"offset": [1, 0, 0], "block_id": "minecraft:air"}
  ]
}
```

- `offset` 是相对于选定施工锚点的整数 `[x,y,z]`，允许负数；显式蓝图不会自动上移或重排。不同机器、平台与装饰可出现在同一蓝图中。
- `block_id` 使用当前安装版本的完整注册 ID。`properties` 中列出的状态属性会逐项验收；省略的属性使用施工默认值，不作为额外精确状态要求。
- 省略的位置保持原状。`minecraft:air` 表示该位置应为空，清除已有方块需要 `replace_existing: true`。替换已有方块实体还需 `replace_block_entities: true`；此选项适用于普通方块目标，AE2 部件仍保护不兼容的现存宿主。
- 门、床等会产生其他格子的方块，必须声明所有相关格子。
- AE2 部件可写作 `{"offset":[0,0,0],"item_id":"ae2:fluix_glass_cable","part":"center"}`；`part` 支持 `center/up/down/north/south/east/west`，实际物品须通过安装版本的原生部件检查。部件与普通方块不得占用同一位置。
- 可选 `metadata` 与 `evidence` 为资料对象。Ponder 的原始 NBT、实体和动画变换放在 `evidence`，不自动作为施工配置，也不能改变执行权限或验收规则。
- 可声明方块 `nbt` 对象及顶层 `entities` 数组，但当前原生施工尚不能通用复现它们。非空目标会在审查中明确返回不支持；不会悄悄忽略。请使用当前 `operate_machine` 已公布的操作配置机器，或移除不打算复现的要求。
- 液体、Create 传送带、大水车等需要专用安装动作的目标，缺少适配器时会在审查中报告不支持。注册 ID 存在不能证明普通方块放置可以完成它。

## 构建与修改

`design_machine` 和 `build_machine` 的参数中，在 `design`（旧组件图）、`blueprint`（上述对象）、`blueprint_uri`（返回的 Ponder 结构 URI）中选择且仅选择一个。资源 URI 必须先由 Ponder 回放资源生成；过期时重新读取场景提取。

`build_machine` 继续使用 `inspect_machine` 返回的 `snapshot_id`、对应语义 `target`、`allow_modify: true`、材料策略与保护标签。Dev 模式会显示完整预览。范围使用可配置的机器规划预算，不沿用旧蓝图的 512 格与 8 格半径限制。

`modify_machine` 使用 `operation: "apply_blueprint"`，提供 `blueprint` 或 `blueprint_uri`，以及同样的现场与替换参数。修改是稀疏目标补丁：只声明要改变或确保存在的格子，删除必须写空气；不会清空整个包围盒。

## 分阶段验收

构建和修改验收声明的方块、状态及原生部件。显式蓝图完成后报告 `construction_complete` 与 `machine_geometry_verified`，同时保留 `configuration_complete: false`、`configuration_status: "separate_use_phase"`、`machine_production_verified: false`。

`operate_machine` 负责现有的菜单、存取物品和控制操作。启动成功与持续产出需要相应观察证据，不能由构建完成推出。缺少某种使用操作时先读取 abilities，按具体缺口反馈，避免假定任意 NBT 或点击动作都可执行。
