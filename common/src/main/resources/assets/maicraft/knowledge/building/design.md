# 建筑图纸格式

`design` 能力的 `drawing` 参数就是这里说的图纸：一个 JSON 对象，一单位一格。本页是格式的唯一说明，字段名、取值和上限以这里为准；每节的例子都能直接作为 `drawing` 交给 `design(operation=create)` 通过校验。屋顶对象另看 `lookup(topic=knowledge, id=building/roofs)`，怎么设计一栋房子看 `lookup(topic=knowledge, id=building/house)`。

## 整体结构

```json
{
  "name": "最小图纸",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Stone": {"block_id": "minecraft:stone"}},
  "objects": [
    {"name": "Block", "type": "MESH", "primitive": "cube", "location": [0.5, 0.5, 0.5], "dimensions": [1, 1, 1], "material": "Stone"}
  ]
}
```

顶层只认这些字段：

| 字段 | 必填 | 说明 |
| --- | :---: | --- |
| `name` | | 图纸名，最长 128 字 |
| `coordinate_system` | 建议写 | `minecraft_y_up`（y 向上）或 `blender_z_up`（z 向上，y 与 z 互换）。**不写时按 `blender_z_up` 算**，所以每张图纸都写 `minecraft_y_up` |
| `materials` | 是 | 材料表：材料名 → 方块状态，或按权重混用的 `mix` |
| `objects` | 是 | 对象列表，1..8192 项；后写的对象盖住先写的 |
| `components` | | 可复用的组件：组件名 → `{"objects": [...]}` |
| `block_state_axes` | | `local`（默认，方块朝向跟着对象转）或 `minecraft_world`（朝向按写的来，不随对象转） |
| `overlap_policy` | | `last_wins`（默认，后写的赢并在结果里记下冲突）或 `error`（叠加就拒绝） |

写错字段名时校验会把本层可用的字段一起报出；错误带 `objects[下标](名字)` 定位，照着改同一张图纸再交。

上限：作者对象、展开后的对象、材料项、阵列总份数各不超过 8192；布尔切割引用不超过 16384；坐标在 -512..512 之间，一个对象的边长不超过 1025；展开后的格数不超过 262144；图纸正文不超过 8 MiB；组件嵌套不超过 8 层；对象名与组件名最长 64 字，不能含 `/`、`[`、`]`。

## 坐标与尺寸

- 设计原点是 `[0, 0, 0]`，图纸不绑地点；`build` 时原点落在 `target`，整张图纸可以绕原点转 0 / 90 / 180 / 270 度。
- `location` 是对象**中心**，`dimensions` 是对象在三条轴上的**整数边长**。一格的中心在 `.5` 上：边长为奇数的轴，中心写 `.5`；边长为偶数的轴，中心写整数。写错了校验报"对象变换后的边界要落在方块面上"。
- 想占 `[a, b)` 这段格（含 a、不含 b），中心是 `(a + b) / 2`，边长是 `b - a`。例如地板占 x 的 0..6 这七格：中心 3.5，边长 7。
- 图纸里没写到的格表示"本图纸不放方块"，施工时不会清空它；要清空的格要写一个 `minecraft:air` 材料的对象（见下文"清空"）。

## 材料表与混色

```json
{
  "name": "材料表",
  "coordinate_system": "minecraft_y_up",
  "materials": {
    "Wall": {"block_id": "minecraft:white_terracotta"},
    "Post": {"block_id": "minecraft:spruce_log", "properties": {"axis": "y"}},
    "Stair": {"block_id": "minecraft:oak_stairs", "properties": {"facing": "north", "half": "bottom"}},
    "Rubble": {"mix": [
      {"block_id": "minecraft:cobblestone", "weight": 3},
      {"block_id": "minecraft:mossy_cobblestone", "weight": 1},
      {"block_id": "minecraft:stone_bricks"}
    ]},
    "Clear": {"block_id": "minecraft:air"}
  },
  "objects": [
    {"name": "Footing", "type": "MESH", "primitive": "cube", "location": [2.5, 0.5, 2.5], "dimensions": [5, 1, 5], "material": "Rubble"},
    {"name": "Wall", "type": "MESH", "primitive": "cube", "location": [2.5, 2.5, 2.5], "dimensions": [5, 3, 5], "material": "Wall", "fill": "hollow", "open_faces": ["top", "bottom"]},
    {"name": "Room", "type": "MESH", "primitive": "cube", "location": [2.5, 2.5, 2.5], "dimensions": [3, 3, 3], "material": "Clear"},
    {"name": "Post", "type": "MESH", "primitive": "cube", "location": [5.5, 2.5, 0.5], "dimensions": [1, 3, 1], "material": "Post"},
    {"name": "Step", "type": "MESH", "primitive": "cube", "location": [2.5, 0.5, -0.5], "dimensions": [1, 1, 1], "material": "Stair"}
  ]
}
```

- 一项材料是一种方块状态：`block_id` 写带命名空间的方块 ID；`properties` 只写要求的属性，值一律是字符串（`"facing": "north"`、`"half": "upper"`、`"type": "top"`）。写了的属性就是施工验收的标准，没写的按原生落法接受。
- 运行态属性别写：`age`、`honey_level`、`waterlogged`、`extended` 这类施工时会被归一改掉，图纸里点名它们会被拒绝。
- `mix` 是几种方块按 `weight`（1..1000，默认 1）混用，最多 16 项；按格坐标确定性取色，同一张图纸再编译、预览、续建取色不变。
- `minecraft:air` 材料的对象表示**清空**这些格：施工会把里面的东西挖掉。房间内部要留空就这么写，别指望"没画到"等于空。
- 原木的 `axis`、楼梯的 `facing` 这些朝向跟着对象的旋转与镜像一起转（`block_state_axes` 为 `local` 时）；转不出游戏里存在的状态（例如把半砖转成竖的）会被拒绝。

## 三种对象

每个对象都有 `name`（同一组里不重复）、`type`，以及可选的 `location`、`rotation_euler`、`mirror`、`array`、`modifiers`、`block_state_axes`。

| `type` | 用途 | 自己的字段 |
| --- | --- | --- |
| `MESH` | 一个图元 | `primitive`、`dimensions`、`material`、`role`、`fill`、`wall_thickness`、`face_materials`、`edge_material`、`edge_materials`、`edge_width`、`open_faces`、`segments`、`vertices`、`faces`、`pattern` |
| `INSTANCE` | 把一个组件摆一份 | `component`、`material_map` |
| `ROOF` | 参数化屋顶 | `footprint`、`material`、`shape`、`curve`、`overhang`、`corner_lift`、`gable_material`、`ridge_material`、`eave_material`、`soffit_material`、`hollow`（见屋顶那一篇） |

## MESH：十二种图元

`primitive` 可选：`cube`、`panel`、`triangle`、`wedge`、`triangular_prism`、`tetrahedron`、`triangular_pyramid`、`pyramid`、`prism`、`cylinder`、`cone`、`convex_polyhedron`。`dimensions` 永远是图元外包围盒的三条边长 `[x, y, z]`。

| 图元 | 形状 | 面名 |
| --- | --- | --- |
| `cube` | 长方体 | `left`(-x) `right`(+x) `bottom`(-y) `top`(+y) `front`(-z) `back`(+z) |
| `panel` | 一格厚的板：三条边里至少一条是 1；只有它能铺 `pattern` | 同 `cube` |
| `triangle` / `triangular_prism` | 等腰三角截面沿 z 拉伸：脊沿 z，顶在 x 的中间，两个坡面朝 ±x | `bottom` `left_slope` `right_slope` `front` `back` |
| `wedge` | 直角三角截面沿 z 拉伸：竖直面在 -x，斜面朝 +x | `bottom` `left` `slope` `front` `back` |
| `tetrahedron` / `triangular_pyramid` | 三角底的锥 | `bottom` `side_0` `side_1` `side_2` |
| `pyramid` | 方底的锥 | `bottom` `front_slope` `right_slope` `back_slope` `left_slope` |
| `prism` | 正多边形柱，`segments` 默认 6（3..32） | `bottom` `top` `side_0`…`side_n-1` |
| `cylinder` | 圆柱，`segments` 默认 16 | 同 `prism` |
| `cone` | 圆锥，`segments` 默认 16 | `bottom` `side_0`…`side_n-1` |
| `convex_polyhedron` | 自定义凸多面体：`vertices` 是 4..64 个归一到 0..1 的点，`faces` 是各面的顶点下标 | `face_0`…按 `faces` 的顺序 |

棱的名字是两个相邻面名按字母序用 `+` 连起来，例如 `front+top`、`left+top`。一格的中心落在图元内部才算这一格；斜面上的格按中心是否在体内决定，细长或很薄的图元可能一格都算不上，校验会报"没有一个格中心落在对象里"。

```json
{
  "name": "十二种图元",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Body": {"block_id": "minecraft:stone"}, "Roof": {"block_id": "minecraft:deepslate_tiles"}},
  "objects": [
    {"name": "Box", "type": "MESH", "primitive": "cube", "location": [1.5, 1, 1.5], "dimensions": [3, 2, 3], "material": "Body"},
    {"name": "Sheet", "type": "MESH", "primitive": "panel", "location": [1.5, 3.5, 1.5], "dimensions": [3, 1, 3], "material": "Roof"},
    {"name": "Gable", "type": "MESH", "primitive": "triangular_prism", "location": [6.5, 2, 1.5], "dimensions": [5, 4, 3], "material": "Roof"},
    {"name": "Ramp", "type": "MESH", "primitive": "wedge", "location": [12, 2, 1.5], "dimensions": [4, 4, 3], "material": "Body"},
    {"name": "Tent", "type": "MESH", "primitive": "pyramid", "location": [17.5, 2.5, 2.5], "dimensions": [5, 5, 5], "material": "Roof"},
    {"name": "Tower", "type": "MESH", "primitive": "cylinder", "segments": 12, "location": [23.5, 3.5, 2.5], "dimensions": [5, 7, 5], "material": "Body"},
    {"name": "Spire", "type": "MESH", "primitive": "cone", "segments": 12, "location": [23.5, 9.5, 2.5], "dimensions": [5, 5, 5], "material": "Roof"},
    {"name": "Hex", "type": "MESH", "primitive": "prism", "location": [29.5, 2, 2.5], "dimensions": [5, 4, 5], "material": "Body"},
    {"name": "Frustum", "type": "MESH", "primitive": "convex_polyhedron", "location": [35.5, 2.5, 2.5], "dimensions": [5, 5, 5],
     "vertices": [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1], [0.2, 1, 0.2], [0.8, 1, 0.2], [0.8, 1, 0.8], [0.2, 1, 0.8]],
     "faces": [[0, 3, 2, 1], [4, 5, 6, 7], [0, 1, 5, 4], [1, 2, 6, 5], [2, 3, 7, 6], [3, 0, 4, 7]], "material": "Body"}
  ]
}
```

## 组件与实例

`components` 里定义一次，`INSTANCE` 想摆几份摆几份。组件自己的坐标从它的原点算，实例的 `location` 是组件原点落在父坐标里的位置，不是中心；实例没有 `dimensions`。`material_map` 把组件里用的材料名换成另一个材料名，只影响这一份实例，两边的名字都要在材料表里；嵌套实例先换内层的，再换外层的。

```json
{
  "name": "窗间组件",
  "coordinate_system": "minecraft_y_up",
  "materials": {
    "Wall": {"block_id": "minecraft:white_terracotta"},
    "Post": {"block_id": "minecraft:spruce_log", "properties": {"axis": "y"}},
    "Glass": {"block_id": "minecraft:glass"},
    "Gold": {"block_id": "minecraft:gold_block"}
  },
  "components": {
    "Bay": {"objects": [
      {"name": "Post", "type": "MESH", "primitive": "cube", "location": [0.5, 2, 0.5], "dimensions": [1, 4, 1], "material": "Post"},
      {"name": "Infill", "type": "MESH", "primitive": "panel", "location": [2.5, 2, 0.5], "dimensions": [3, 4, 1], "material": "Wall",
       "modifiers": [{"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "WindowCut"}]},
      {"name": "WindowCut", "type": "MESH", "primitive": "cube", "location": [2.5, 2, 0.5], "dimensions": [1, 2, 3]},
      {"name": "Glass", "type": "MESH", "primitive": "panel", "location": [2.5, 2, 0.5], "dimensions": [1, 2, 1], "material": "Glass"}
    ]}
  },
  "objects": [
    {"name": "Front", "type": "INSTANCE", "component": "Bay", "location": [0, 0, 0], "array": {"count": [3, 1, 1], "step": [4, 0, 0]}},
    {"name": "EndPost", "type": "MESH", "primitive": "cube", "location": [12.5, 2, 0.5], "dimensions": [1, 4, 1], "material": "Post"},
    {"name": "Fancy", "type": "INSTANCE", "component": "Bay", "location": [0, 0, 6], "material_map": {"Post": "Gold"}}
  ]
}
```

## 阵列、镜像与旋转

- `array`：`count` 是三条轴各复制几份（含原来那份），`step` 是每份之间沿对象自己三条轴的间距，`skip` 列出不要的份的下标（从 0 起）。总份数算进对象上限；`count` 大于 1 的轴 `step` 不能是 0。
- `mirror`：沿对象自己的哪些轴镜像，取值 `"x"`、`"y"`、`"z"` 的组合。
- `rotation_euler`：绕对象自己的 x、y、z 轴依次转的弧度，**只接受直角的整数倍**：`1.5707963267948966` 是 90°，`3.141592653589793` 是 180°，负数反向。斜放 45° 不存在，用阶梯状的对象表达。
- 旋转和镜像绕对象自己的 `location`（实例绕组件原点），方块的朝向属性跟着一起转。
- 展开路径：阵列里第 x,y,z 份叫 `名字[x,y,z]`，实例里的对象叫 `实例名/对象名`，`design(operation=inspect)` 用这种路径看一份。

```json
{
  "name": "阵列与旋转",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Stone": {"block_id": "minecraft:stone_bricks"}, "Stair": {"block_id": "minecraft:stone_brick_stairs", "properties": {"facing": "north", "half": "bottom"}}},
  "objects": [
    {"name": "Columns", "type": "MESH", "primitive": "cube", "location": [0.5, 2, 0.5], "dimensions": [1, 4, 1], "material": "Stone",
     "array": {"count": [4, 1, 2], "step": [3, 0, 6], "skip": [[1, 0, 1]]}},
    {"name": "Steps", "type": "MESH", "primitive": "cube", "location": [5, 0.5, -0.5], "dimensions": [2, 1, 1], "material": "Stair"},
    {"name": "StepsTurned", "type": "MESH", "primitive": "cube", "location": [11.5, 0.5, 3], "dimensions": [2, 1, 1], "material": "Stair",
     "rotation_euler": [0, 1.5707963267948966, 0]},
    {"name": "Mirrored", "type": "MESH", "primitive": "wedge", "location": [5, 1, 3.5], "dimensions": [4, 2, 3], "material": "Stone", "mirror": ["x"]}
  ]
}
```

## 布尔开孔

`modifiers` 只有一种：`{"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "切割体名"}`，从本对象里挖掉切割体占的格。切割体是**同一组里**（同一个 `objects` 列表或同一个组件里）的另一个实心 `MESH`：不写 `material` 也行，被引用的切割体自己不会出现在成品里；它不能带 `pattern`、`fill: hollow` 或自己的 `modifiers`，但可以带 `array`。开孔只影响本对象，不会挖掉别的对象放在那里的方块；组件里的开孔只认同一份实例里的切割体。`role: "cutter"` 可以把一个对象声明成纯切割体，即使没人引用它也不会出现。

```json
{
  "name": "门洞与窗洞",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Wall": {"block_id": "minecraft:bricks"}, "Glass": {"block_id": "minecraft:glass_pane"}},
  "objects": [
    {"name": "Front", "type": "MESH", "primitive": "panel", "location": [4.5, 2.5, 0.5], "dimensions": [9, 5, 1], "material": "Wall",
     "modifiers": [{"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "Door"}, {"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "Windows"}]},
    {"name": "Door", "type": "MESH", "primitive": "cube", "location": [4.5, 1.5, 0.5], "dimensions": [1, 3, 3]},
    {"name": "Windows", "type": "MESH", "primitive": "cube", "location": [1.5, 3, 0.5], "dimensions": [1, 2, 3], "array": {"count": [2, 1, 1], "step": [6, 0, 0]}},
    {"name": "Panes", "type": "MESH", "primitive": "panel", "location": [1.5, 3, 0.5], "dimensions": [1, 2, 1], "material": "Glass", "array": {"count": [2, 1, 1], "step": [6, 0, 0]}}
  ]
}
```

## 空心与壁厚

`fill: "hollow"` 只留一层壳，`wall_thickness` 是壳的厚度（默认 1，最小 0.5）；`open_faces` 列出不要壳的面（必须配 `hollow`，面名要是这个图元有的）。空腔里的格是"不放方块"，不是清空；房间要能走进去，再放一个 `minecraft:air` 材料的对象。

```json
{
  "name": "空心塔",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Stone": {"block_id": "minecraft:stone_bricks"}, "Clear": {"block_id": "minecraft:air"}},
  "objects": [
    {"name": "Shell", "type": "MESH", "primitive": "cylinder", "segments": 12, "location": [3.5, 3, 3.5], "dimensions": [7, 6, 7], "material": "Stone",
     "fill": "hollow", "wall_thickness": 1, "open_faces": ["top"]},
    {"name": "Inside", "type": "MESH", "primitive": "cylinder", "segments": 12, "location": [3.5, 3.5, 3.5], "dimensions": [5, 5, 5], "material": "Clear"}
  ]
}
```

## 面与棱的材质

- `face_materials`：面名 → 材料名，这个面一层厚（空心时是壳厚）换成那种材料。
- `edge_material`：所有棱换成这种材料；`edge_materials`：某条棱换成某种材料，键写 `面+面`（字母序）。`edge_width` 是棱的宽度（默认 1，最小 0.5）。几条棱在角上相遇时近的赢、同距时点名的赢。
- 涂的顺序是：先定空腔，再涂面，再包棱，最后按图案留孔。

```json
{
  "name": "包边的石屋",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Wall": {"block_id": "minecraft:stone_bricks"}, "Trim": {"block_id": "minecraft:polished_andesite"}, "Floor": {"block_id": "minecraft:oak_planks"}, "Gold": {"block_id": "minecraft:gold_block"}},
  "objects": [
    {"name": "House", "type": "MESH", "primitive": "cube", "location": [3.5, 2.5, 3.5], "dimensions": [7, 5, 7], "material": "Wall",
     "fill": "hollow", "face_materials": {"bottom": "Floor"}, "edge_material": "Trim", "edge_materials": {"front+top": "Gold"}}
  ]
}
```

## 平面图案

`pattern` 只能铺在 `panel` 上：`axes` 是图案的列方向和行方向（两条不同的轴，必须张成板一格厚的那个平面，例如竖墙 `[1, 4, 7]` 厚在 x，就写 `["z", "y"]`）；`rows` 是若干行等宽的 `0`/`1` 字符串，从板的最小角起、先列后行重复铺满；`1` 用板的材料，`0` 留孔，`materials` 可以给 `"0"` 或 `"1"` 绑别的材料。孔和布尔开孔一样只影响本对象。

```json
{
  "name": "花格屏风",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Wood": {"block_id": "minecraft:dark_oak_planks"}, "Slab": {"block_id": "minecraft:dark_oak_slab", "properties": {"type": "bottom"}}, "Frame": {"block_id": "minecraft:stripped_dark_oak_log", "properties": {"axis": "y"}}},
  "objects": [
    {"name": "Screen", "type": "MESH", "primitive": "panel", "location": [3.5, 2.5, 0.5], "dimensions": [5, 5, 1], "material": "Wood",
     "pattern": {"axes": ["x", "y"], "rows": ["101", "010"]}},
    {"name": "Weave", "type": "MESH", "primitive": "panel", "location": [3.5, 2.5, 3.5], "dimensions": [5, 5, 1], "material": "Wood",
     "pattern": {"axes": ["x", "y"], "rows": ["01", "10"], "materials": {"0": "Slab"}}},
    {"name": "Frame", "type": "MESH", "primitive": "cube", "location": [0.5, 2.5, 2], "dimensions": [1, 5, 4], "material": "Frame", "array": {"count": [2, 1, 1], "step": [6, 0, 0]}}
  ]
}
```

## 叠加与顺序

两个对象占同一格时，`objects` 里后写的赢（组件里的对象按组件里的顺序，实例按实例的顺序）；结果里会报有几格叠加、几次、前几个例子。要严格的话 `overlap_policy: "error"`，一有叠加就拒绝。先写大体量，再写要盖在上面的细节；玻璃放进开好的孔里不算叠加，放在没开孔的实墙上就是叠加。

## 常见错误

- 没写 `coordinate_system`：按 z 向上算，整张图纸躺倒。
- 中心写错半格：奇数边长的轴中心要 `.5`，偶数要整数。
- 材料表里没有这个名字；`face_materials`、`material_map` 两边的名字也都要在表里。
- `panel` 没有一条边是 1；`pattern` 的 `axes` 没张成那个一格厚的平面。
- 切割体不在同一组里，或切割体带了 `pattern`、`fill: hollow`、`modifiers`。
- `rotation_euler` 不是直角的整数倍；屋顶沿 y 镜像；楼梯、半砖转成游戏里不存在的朝向。
- 点名了运行态属性（`age`、`waterlogged`……）。
- 图纸里没有一个实心对象（全是切割体），或某个对象一格都算不上。
