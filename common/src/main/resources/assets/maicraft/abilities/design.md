画一张建筑图纸、改它、看它、把它投影到世界里看效果，或导出成结构文件。图纸不绑地点，盖在哪由 build 决定。图纸用对象（盒子、板、楔形、棱柱、圆柱、圆锥、凸多面体、屋顶）、组件与实例、阵列、镜像、直角旋转、布尔开孔、空心与壁厚、面和棱的材质、平面图案来描述，一单位一格；图纸格式看 lookup(topic=knowledge, id=building/design)，屋顶看 lookup(topic=knowledge, id=building/roofs)，怎么设计一栋房子看 lookup(topic=knowledge, id=building/house)，风格和完整例子看 lookup(topic=knowledge, id=building/styles) 与 lookup(topic=knowledge, id=building/example-cottage)。create 和 update 会告诉你这张图纸展开后有多少格、每种材料几件、哪里重叠；不会替你改图，也不检查地形。当场返回，不控制角色。

## 怎么用

- `operation=create`：给 `drawing`（JSON 对象）。校验、展开、存盘，返回 `design_id`。校验错误一次报全，每条带 `objects[下标](名字)` 这样的定位。
- `operation=update`：给 `design_id` 和 `edits`。按名合并：对象字段逐项覆盖（只改 location 会保留原材质和尺寸），`modifiers` 给了就整份替换；`components` 整条替换；`materials` 按名覆盖；`remove_objects`、`remove_components` 删；还可以改 `name`、`block_state_axes`、`overlap_policy`。存成新的一版（新 `design_id`），父版本保留。
- `operation=inspect`：给 `design_id`。不给 `name` 看整张图纸的对象与组件列表；给对象名或展开路径看那个对象（面名、棱名、展开路径）；看组件在名字前加 `component:`。阵列的展开路径压成一组，例如 `Arcade[0..2,0,0]/Shell`；太多才分页，结果带 `next_page` 时用 `page` 接着读。
- `operation=preview`：给 `design_id` 和 `target`（可选 `rotation`）。把图纸落到锚点交给预览叠加给观众看，不阻塞任何任务；返回统计。
- `operation=export`：给 `design_id`（可选 `format`，默认 json）。写到 `schematics/maicraft-design-<编号>.json` 或 `.nbt`；json 与 build 的 `cells` 同格式，能直接喂回去。
- `operation=read_site`：给 `target`，把那里已有的一栋建筑读成一版新图纸（一个 `LAYERS` 层图对象，按层画、带方块状态，门的上下半、床的两头都在），同时给这一栋的布局。不动角色、不改世界。
  - `target` 是建筑档案名就读档案的范围（设计原点是档案的锚点，另给整栋比对 `check`）；是墙、门、坐标、记过的地点或 `here` 就从那一格起自动框住整栋（再往下带一层地面），三条边都不能超过 32 格；给 `radius` 改读以 target 为中心的方盒。
  - 只读看得见的格和自己放的格；看不见的写 `?`，空着的写 `.`，都不声明。走进屋里再读一次就看到屋里。
  - 给 `name`：这一栋还没有建筑档案时用这个名字建一份，之后 `build(target=这个名字)` 就是改它。
  - 改房子：读现场 → `update` 改层图（`layers` 换层、`set` 逐格改）→ `build(design_id=新一版, target=档案名)` 按图补建，收场对整栋比一遍。

材料表里一项可以写 `mix`：几种方块按权重混用，按坐标确定性取色，同一张图纸再编译取色不变。

## 结果怎么读

| 字段 | 含义 |
| --- | --- |
| `design_id`、`parent_design_id` | 这一版的编号、改自哪一版 |
| `object_count`、`expanded_count`、`cell_count` | 作者写的对象数、展开后的对象数（含切割体）、格数 |
| `materials` | 每种材料几件（物品 → 件数） |
| `bounds` | 相对设计原点的包围盒 `min` / `max` |
| `overlaps` | 叠加冲突：几格、几次、前几个例子；`overlap_policy=error` 时直接拒绝 |
| `inspection` | inspect 的正文：对象或组件的描述、展开路径、面与棱 |
| `file`、`offset` | export 写到哪；nbt 平移掉的量，再导入时把锚点加上它 |
| `anchor` | read_site：设计原点落在哪一格，照这一版补建就落在这里 |
| `unseen` | read_site：范围里有东西却没看到、没加载的格数 |
| `site` | read_site：读出来的层图对象（`legend` 与每一层 `rows`），整份给 |
| `layout` | 这一栋的布局（read_site 按现场、inspect 按图纸）：`floors` 每层地面（地面方块的 `y`、净高 `height`、有顶的能站格数）、`doors`（`at`、朝向、铰链、开着没、`inside` 屋里那一侧）、`openings`（门洞、窗洞）、`windows`、`beds`、`climbs`（楼梯梯子这些连着哪几层）、`facilities`、`free_floor`（屋里有顶、能站、头上两格空的空地，`from`/`to` 是人站的那一层，放床放箱子用） |
| `check` | read_site 指着建筑档案时：档案的整栋蓝图对现场比，没对上的每一格 |
| `archive` | read_site 用到或新建的建筑档案名 |

## 问题种类

- `INVALID_PARAMETER`：图纸或修改不合法，带定位；`design_id` 不存在；preview 的 target 给不了。
- `NOT_FOUND`：preview 的 target 是没记住的地点。
- `TARGET_GONE`：preview 的 target 是已经不在了的观察编号。
- `UNREACHABLE`：preview 的坐标省略了 y，那一列还没加载，取不到地表高度；read_site 指的档案在另一个维度。
- read_site 的 `INVALID_PARAMETER`：目标周围两格内没看到搭出来的方块、框到超过 32 格、范围里一格方块都没看到、方块状态多到超过一张层图的字符数（给 `radius` 分几块读）。
