# 建筑场景与统一蓝图 JSON

## LLM 自主建模：使用现有 build 能力

`maicraft:build` 接受 LLM 直接设计的具名对象。通过已有 `plan` / `execute` 提交，建模、查询、预览和导出都是此能力的 `operation`，没有新增 MCP tool。原来的自然语言模板建造仍可使用；显式模型不会进入模板选址、尺寸调整或自动换材质流程。

接口沿用 Blender 的 `MESH` 对象、中心 `location`、`dimensions`、弧度 `rotation_euler`、具名材质和 `BOOLEAN/DIFFERENCE` 修改器。它实现可确定编译的声明式对象子集，不执行 BlenderMCP 的任意 Python / `bpy` 代码，也不宣称兼容任意网格。

以下在一块 7×4 的橡木墙板上开窗，放入独立玻璃对象。`WindowCut` 是修改器引用的切割体，不会作为实体建造。开孔只减去指定墙体，不会误删玻璃等其他对象。

```json
{
  "ability": "maicraft:build",
  "outcome": "设计一面带玻璃窗的橡木墙",
  "target": {"kind": "current_place"},
  "parameters": {
    "operation": "create_scene",
    "scene": {
      "schema_version": 1,
      "coordinate_system": "blender_z_up",
      "materials": {
        "WallMaterial": {"block_id": "minecraft:oak_planks"},
        "GlassMaterial": {"block_id": "minecraft:glass"}
      },
      "objects": [
        {"name":"Wall","type":"MESH","primitive":"cube","location":[3.5,0.5,2],"dimensions":[7,1,4],"material":"WallMaterial",
         "modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"WindowCut"}]},
        {"name":"WindowCut","type":"MESH","primitive":"cube","location":[3.5,0.5,2],"dimensions":[3,3,2]},
        {"name":"WindowGlass","type":"MESH","primitive":"panel","location":[3.5,0.5,2],"dimensions":[3,1,2],"material":"GlassMaterial"}
      ]
    }
  }
}
```

返回 `scene_id` 和 `scene_uri` / `blueprint_uri`。完整场景与展开后的逐方块 JSON 可通过现有 `perceive(view="knowledge", resource_uri=...)` 或 `resources/read` 读取。场景编号绑定原存档、维度和锚点；后续使用该编号时省略 `target`，不会跟随玩家移动。

每一步继续提交 `ability: "maicraft:build"`，只更换 `parameters`：

| 操作 | parameters 示例 | 结果 |
| --- | --- | --- |
| 查看场景 | `{"operation":"get_scene_info","scene_id":"返回的编号","page":0}` | 对象、材质数量，每页 10 个对象及是否还有下一页 |
| 查看对象 | `{"operation":"get_object_info","scene_id":"返回的编号","object_name":"Wall"}` | 对象变换、尺寸、材质、修改器与包围盒 |
| 编辑对象 | `{"operation":"update_scene","scene_id":"返回的编号","edits":{"objects":[{"name":"Wall","dimensions":[9,1,4]}]}}` | 新场景编号，原版本保留；未提及对象保持原样 |
| 换指定材质 | `{"operation":"update_scene","scene_id":"返回的编号","edits":{"materials":{"WallMaterial":{"block_id":"minecraft:stone_bricks"}}}}` | 新版本中的具名材质改变 |
| 预览 | `{"operation":"preview","scene_id":"返回的编号"}` | 加载区域内只读蓝图，不走路、不取物、不施工 |
| 导出 | `{"operation":"export_scene","scene_id":"返回的编号","format":"nbt"}` | `schematics/` 中的原版结构；`json` 导出统一蓝图 |
| 施工 | `{"operation":"build","scene_id":"返回的编号","replace_existing":false}` | 既有施工任务，报告 `project_id` |
| 续建 | `{"project_id":"施工返回的编号"}` | 重新核对固定施工单，只处理未满足的目标 |

`update_scene.edits.objects` 按名字合并字段；新对象须完整定义；`remove_objects` 删除指定名字。对象引用、最终网格、材料语法全部通过检查才保存新版本。删除建模对象不会自动删除世界里的旧建筑；需要清除的位置应明确设计为空气，或通过墙上的开孔表达。修改场景不会改变已开始施工的项目。

坐标与材料约定：

- 默认 Blender Z 向上；几何点 `[x,y,z]` 转成 Minecraft `[x,z,-y]`，再加原锚点。方块体积按边界换算，例如 Blender `y=0..1` 对应 MC 方块 `z=-1`。可显式选 `minecraft_y_up`。
- 一个建模单位等于一个方块。`location` 是几何中心，可为半整数；`dimensions` 是正整数，面必须对齐整数网格。`cube` 可做墙、地板、柱或单方块，`panel` 要求至少一个轴厚度为 1。
- `rotation_euler` 使用弧度，目前支持绕上轴的 90° 整数倍。其他旋转、曲面及修改器会明确拒绝，不会偷偷取整或忽略。
- 材质使用完整 `block_id`，可带 `properties`。状态属性中的朝向固定使用 Minecraft 世界轴，不随网格旋转隐式改写。未知材质、非法属性和施工时会被归一化掉的明确状态要求都会报错。
- 对显式建筑模型，`properties` 没有填写的属性不参与验收。例如门不填 `open`，开着或关着都可以；填写 `"open":"false"` 才要求最终关闭。没有额外的重要性字段。
- 显式属性是最终状态要求。同种方块仅有状态差异时，开工/续建不把它当作需要替换的错误材料，也不补领一件替代材料。新放置仍尽量直接满足结构属性；可手动开关的木门在结构施工和脚手架清理后通过原生交互调整，并等待服务端确认及上下半核验。
- 目前不会为满足最终状态偷偷拆换已有方块；没有安全原生调整方式的状态差异会报告 `final_state_adjustment_unsupported`。预览里未声明属性显示的是默认状态，不意味着约束实际方块必须保持该默认值。
- 单色墙保持单色；不同对象中的橡木与云杉木等同类材料不会合并。想要纹理变化，应由 LLM 明确设计。
- 多个实体重叠时后面的实体覆盖前面的实体。切割体只从引用它的实体中减去体积；没有其他实体填充的孔洞编译为空气，清除世界已有方块仍需 `replace_existing:true`。
- 当前单次最多 16,384 个最终目标，并检查对象数、半径和展开工作量。完整建筑可由多个墙板、柱、楼板和细节构成，不需要 LLM 逐格枚举。
- JSON 保留负相对坐标；原版 NBT 将最小角归零，同时记录 `maicraft_offset`。用传统蓝图导入时，导入锚点应为原锚点加返回的 `minecraft_offset`。

也可以直接在 `build` 参数里提供下方统一格式的 `blueprint`，自由指定每个方块；它与 `scene`、`scene_id` 三选一。此路径同样不调用旧模板。建模和导出不证明建筑已经建成，最终进度与验收仍由施工任务报告。

续建保存已经选定的材质与绝对目标位置，不保存需要相信的“已完成百分比”；恢复后以当前世界为准核对。取消、失败和游戏重启后均可通过 `project_id` 重开施工。旧版本从未保存过的施工单无法凭空恢复；不要用新的 `current_place` 规划冒充续建。

建造失败结果的 `build_diagnostics` 会给出冻结目标数组中的零起始 `target_index`、预期和实际方块状态；临时支撑失败还会带 `support_for` 指向其服务的目标。`placed` 是本次执行的放置计数，`completed` 才是本次核验已满足的目标数；不能把续建单次放置计数当作整座建筑完成度。

施工器按蓝图的竖向结构自动划分施工区域，共用地板或屋顶不会强制独立门柱与主体同步升层。先持续完成当前区域，区域内部逐层、沿相邻轮廓推进；construction_region 给出区域编号和总数，construction_layer 是该区域的当前层。区域由几何推断，不会修改图纸坐标、材料或验收状态。优先脚边向下放置、踏上已确认完成的永久方块；站位搜索允许沿现有墙顶转弯和踩一级台阶。construction_access 依次为保持高度的 retain_height、允许下降但不改地形的 existing_footing、最后才启用的 construction_access。局部落脚点搜索有界，回退不代表已证明整个世界无路；最终仍逐格验收所有区域及原有状态、通行和支撑清理要求。

临时点击支撑在排入施工队列前，会用只读投影视图检查：全部候选支撑放下后，当前区域内是否仍有可达落脚点和有效目标点击面。每个支撑实际放置前还会重新检查；支撑与其服务的目标保持连续施工，期间不再为接近站位额外修改地形。没有证明、观察改变或支撑会封住目标时，返回 `temporary_support_access_unproven`，`support_access` 包含具体原因和六邻面的方块信息，不继续盲目垫土，也不自动拆建筑。检查有局部范围与工作量上限，失败不表示所有其他支撑方案都不可能；真实放置仍经过原版预测、准星和服务端确认。

建造导航在当前目标、当前寻路阶段内记录失败站位；同一站位的其他瞄准点不会再次触发同一轮寻路。切换目标、放宽寻路阶段或确认附近施工变化后，允许重新尝试。construction_navigation 提供本阶段的 route_attempts、failed_stances、duplicate_gestures_skipped、最近失败原因，以及当前目标的 target_index（如属于冻结蓝图）；这些是本轮诊断，不能用历史失败列表替代。

## 统一机器蓝图 JSON

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

不带生产清单的构建和修改验收声明的方块、状态及原生部件。显式蓝图完成后报告 `construction_complete` 与 `machine_geometry_verified`，同时保留 `configuration_complete: false`、`configuration_status: "separate_use_phase"`、`machine_production_verified: false`。

`operate_machine` 负责现有的菜单、存取物品和控制操作。启动成功与持续产出需要相应观察证据，不能由构建完成推出。缺少某种使用操作时先读取 abilities，按具体缺口反馈，避免假定任意 NBT 或点击动作都可执行。

## 可选服务端生产清单

客户端必装，服务端可选。先查看 `perceive(view="abilities")` 中的 `server_assistance` 与具体操作支持情况。没有服务端增强时，普通构建继续使用客户端模式；生产证明不会自动降级为只看方块或库存。

- `build_machine` 可额外提供 `production` 和 `allow_use: true`，在同一任务中执行建造、配置、供料和观察。
- 已建机器使用 `operate_machine`、`operation: "run_production"`、`production`、`snapshot_id` 与 `allow_use: true`。
- `production.schema_version` 为 `1`；`nodes` 描述 `source/process/transport/sink`，工序声明原生 `recipe_id` 和 `batches`；`ports` 指定节点、相对位置、侧面、介质及输入/输出方向；`links` 声明资源、有限预算和完整路径。
- `configurations` 仅使用能力契约中的语义配置，阶段为 `configure` 或 `start`。不接受槽位脚本、原始点击或任意 NBT 写入；所需工具和空白样板仍要从真实材料取得。
- `target` 指定目标接收节点与资源；`observation` 包含 `window_ticks`、`minimum_output`、`minimum_events` 和 `max_idle_ticks`。时间窗口是首尾实际产出事件之间的最小跨度，等待本身不会增加产量或事件数。

所有位置共用冻结的蓝图锚点。源的 `material_policy` 可为 `inventory_only`、`storage_available` 或 `ordinary`；库存预存量只计一次，后续真实注入不得超过声明预算。后续批次由对应工序的原生完成事件放行，概率性零产出可以证明工序已完成，但不增加目标产量。

运行时重新读取当前配置，按真实资源身份保留组件，区分连接、运行条件与实际流量。重复回执、任务开始前已在途的物品及无归因库存增长不能充当本次生产证明。结果中的 `production_observation` 说明已覆盖的加工与目标资源交付范围；这不是所有上游介质的流量证明。

只有工序、时间窗口及真实目标交付均满足要求后，生产任务才报告 `machine_production_verified: true`。其中嵌套的 `construction` 仍只是施工阶段证据。有限原料耗尽后停机不会抹去已经验证的窗口，也不代表系统具备无限供料能力。

## 短时调试与后台观察

`window_ticks` 是实际产出样本的最小跨度；`max_idle_ticks` 独立限制两次加工之间的间隔，两者都有 72000 tick 上限。调试可以只取少量真实完成样本，正常加工所需时间不必恰好等于最小样本跨度。

机器已有合适输入、连接和配置时，在下一批开始前使用 `operate_machine` 的 `watch_production` 登记后台观察，再通过普通原生操作备料、启动。该操作使用新鲜 `snapshot_id`、同一锚点的 `production` 和 `allow_use: true`，目标数量来自 `production.observation.minimum_output`；`minimum_process_events` 单独指定每个工序所需原生完成次数，默认 1。它先就近验证登记位置，随后不占用身体、不巡检或自行补料。

`monitor_registered: true` 只表示登记成功，不能说目标产品已完成。后续从 `perceive(view="machines")` 读取 `production_watches`，或消费 Attention 的完成/异常事件。监测依据登记后的原生加工、配送及真实目标库存增长，不计入登记前已有产物；区块未加载不算缺料或成功。当前只支持已接入的原生物品加工与限定收货位置，同一连接/维度内有效，不负责强加载区块。

`perceive(view="machines", focus="产线标签/节点ID")` 可查登记节点与端口，标签也可作为后续定位目标。档案中的用途推测、声明的设计和历史调试证明各有自己的来源；记住位置不等于获得修改许可，也不证明当前配置没有变化。
