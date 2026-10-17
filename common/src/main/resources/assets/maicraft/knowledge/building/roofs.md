# 参数化屋顶（ROOF 对象）

图纸里 `type: "ROOF"` 的对象按参数生成一整片坡屋顶：在 `footprint` 的矩形上起坡，长边作屋脊方向，用同材质的半砖表达半格的高度变化，脊、垂脊、山墙、檐口、翘角都由参数给。做法是从四栋手工建筑逐格量出来的中式屋顶，欧式坡顶用它也合适。图纸格式本身看 `lookup(topic=knowledge, id=building/design)`。

## 字段

| 字段 | 必填 | 说明 |
| --- | :---: | --- |
| `location` | 是 | 檐口基准高度上 `footprint` 矩形的中心：奇数边的轴写 `.5`，偶数边写整数，y 写整数（通常是墙顶那一格的上面一格的 y） |
| `footprint` | 是 | `[宽, 深]`，屋顶盖住的矩形（不含出檐），长的那条边是屋脊方向 |
| `material` | 是 | 屋面材料；有同材质半砖（`deepslate_tiles` → `deepslate_tile_slab`、`dark_oak_planks` → `dark_oak_slab`）就用半砖做半格，没有就整块砌 |
| `shape` | | 九个取值对应五种形：`xuanshan` / `gable`（悬山，两坡带山墙，默认）、`wudian` / `hip`（庑殿，四坡）、`xieshan` / `half_hip`（歇山，下段四坡上段两坡带山花）、`zuanjian` / `pyramid`（攒尖，四坡收到一点）、`shed`（单坡，整片倒向一侧） |
| `curve` | | `concave`（默认，举架曲线，越往上越陡）或 `straight`（直坡，每进一格升一格） |
| `overhang` | | 出檐几格，0..4 |
| `corner_lift` | | 四个檐角向上翘几格，0..3 |
| `gable_material` | | 山墙（悬山两端的三角墙、歇山腰线的山花）用的材料；不写就不封山墙 |
| `ridge_material` | | 正脊、垂脊、博风板用的材料；不写就用屋面材料 |
| `eave_material` | | 檐口那一圈用的材料；不写就用屋面材料 |
| `soffit_material` | | 屋面下的底衬，从屋里往上看少露缝；不写就没有 |
| `hollow` | | `true`（默认）留阁楼空着；`false` 把檐口基准到屋面之间填实 |

屋顶可以 `rotation_euler`（绕 y 转直角）、`mirror`（不能沿 y）、`array`，不能带 `modifiers`。屋顶不是凸图元，没有面名和棱名，`face_materials` 这类字段不认。

## 有多高

檐口基准 y 是屋顶自己的 0。坡面从檐口往脊每进一格就升一些，短边越长屋顶越高；脊顶在屋面上再突出两格。大致高度（檐口基准到脊顶，含脊，单位格）：

| 短边跨度 | 3 | 4–5 | 6–7 | 8–9 | 10–11 | 12–13 | 14–15 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 曲坡（默认） | 4 | 4 | 5 | 5 | 6 | 7 | 7 |
| 直坡 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |

单坡按整个短边跨度算一坡，会比同跨度的两坡高一倍左右。准确的格范围用 `design(operation=inspect)` 看包围盒。

## 悬山（默认）

两坡对开，两端是竖直的山墙面；写了 `gable_material` 才封山墙，否则两端露着阁楼。

```json
{
  "name": "悬山顶",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Wall": {"block_id": "minecraft:white_terracotta"}, "Tile": {"block_id": "minecraft:deepslate_tiles"}, "Gable": {"block_id": "minecraft:spruce_planks"}},
  "objects": [
    {"name": "Hall", "type": "MESH", "primitive": "cube", "location": [3.5, 2, 2], "dimensions": [7, 4, 4], "material": "Wall", "fill": "hollow"},
    {"name": "Roof", "type": "ROOF", "location": [3.5, 4, 2], "footprint": [7, 4], "material": "Tile", "gable_material": "Gable", "overhang": 1}
  ]
}
```

## 庑殿（四坡）

四面都是坡，四条垂脊从檐角收到正脊；大殿、方正的主屋用它。

```json
{
  "name": "庑殿顶",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Wall": {"block_id": "minecraft:stone_bricks"}, "Tile": {"block_id": "minecraft:dark_oak_planks"}, "Ridge": {"block_id": "minecraft:dark_oak_log", "properties": {"axis": "x"}}},
  "objects": [
    {"name": "Hall", "type": "MESH", "primitive": "cube", "location": [4.5, 2, 2.5], "dimensions": [9, 4, 5], "material": "Wall", "fill": "hollow"},
    {"name": "Roof", "type": "ROOF", "shape": "wudian", "location": [4.5, 4, 2.5], "footprint": [9, 5], "material": "Tile", "ridge_material": "Ridge", "overhang": 1}
  ]
}
```

## 歇山（下四坡上两坡）

下段四坡，往上转成两坡并在腰线处露出三角山花；比庑殿轻巧，比悬山完整，长条的主屋常用。

```json
{
  "name": "歇山顶",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Wall": {"block_id": "minecraft:white_terracotta"}, "Tile": {"block_id": "minecraft:deepslate_tiles"}, "Gable": {"block_id": "minecraft:red_terracotta"}},
  "objects": [
    {"name": "Hall", "type": "MESH", "primitive": "cube", "location": [5.5, 2, 2.5], "dimensions": [11, 4, 5], "material": "Wall", "fill": "hollow"},
    {"name": "Roof", "type": "ROOF", "shape": "xieshan", "location": [5.5, 4, 2.5], "footprint": [11, 5], "material": "Tile", "gable_material": "Gable", "overhang": 1}
  ]
}
```

## 攒尖（收到一点）

方形或近方形的底，四坡收成一个尖；亭子、塔顶、小阁用它。`footprint` 两边相等时没有正脊，只有一个尖。

```json
{
  "name": "攒尖亭",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Post": {"block_id": "minecraft:spruce_log", "properties": {"axis": "y"}}, "Tile": {"block_id": "minecraft:deepslate_tiles"}},
  "objects": [
    {"name": "Posts", "type": "MESH", "primitive": "cube", "location": [0.5, 1.5, 0.5], "dimensions": [1, 3, 1], "material": "Post", "array": {"count": [2, 1, 2], "step": [6, 0, 6]}},
    {"name": "Roof", "type": "ROOF", "shape": "zuanjian", "location": [3.5, 3, 3.5], "footprint": [7, 7], "material": "Tile", "overhang": 1, "corner_lift": 1}
  ]
}
```

## 单坡

整片屋面倒向一侧，从一边的檐口升到另一边；披屋、附翼、工棚用它，直坡更像棚子。

```json
{
  "name": "单坡披屋",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Wall": {"block_id": "minecraft:oak_planks"}, "Tile": {"block_id": "minecraft:dark_oak_planks"}},
  "objects": [
    {"name": "Shed", "type": "MESH", "primitive": "cube", "location": [2, 1.5, 1.5], "dimensions": [4, 3, 3], "material": "Wall", "fill": "hollow"},
    {"name": "Roof", "type": "ROOF", "shape": "shed", "curve": "straight", "location": [2, 3, 1.5], "footprint": [4, 3], "material": "Tile"}
  ]
}
```

## 把细节都用上

檐口换材料、底衬、翘角、填实，一次写全；屋面材料、山墙、脊三色分开，远看轮廓才清楚。

```json
{
  "name": "全参数屋顶",
  "coordinate_system": "minecraft_y_up",
  "materials": {
    "Wall": {"block_id": "minecraft:white_terracotta"},
    "Tile": {"block_id": "minecraft:deepslate_tiles"},
    "Eave": {"block_id": "minecraft:polished_deepslate"},
    "Soffit": {"block_id": "minecraft:spruce_planks"},
    "Gable": {"block_id": "minecraft:spruce_planks"},
    "Ridge": {"block_id": "minecraft:polished_blackstone"}
  },
  "objects": [
    {"name": "Hall", "type": "MESH", "primitive": "cube", "location": [4.5, 2, 2.5], "dimensions": [9, 4, 5], "material": "Wall", "fill": "hollow"},
    {"name": "Roof", "type": "ROOF", "location": [4.5, 4, 2.5], "footprint": [9, 5], "material": "Tile", "curve": "concave",
     "overhang": 2, "corner_lift": 2, "hollow": false,
     "gable_material": "Gable", "ridge_material": "Ridge", "eave_material": "Eave", "soffit_material": "Soffit"}
  ]
}
```

## 怎么用

- 屋顶落在墙顶上：墙占 y 0..3 时，`location` 的 y 写 4。出檐从檐口基准那一层往外伸，别让它压在窗上。
- 脊的方向由 `footprint` 决定，长边就是脊；想换方向就换 `footprint` 的两个数，不用旋转。
- 两栋相接的屋顶各写各的 ROOF，交界处会叠加（后写的赢）；先写主屋再写附翼。
- 屋顶里面默认是空的（`hollow: true`）但不是清空；要做阁楼，再放一个 `minecraft:air` 材料的对象把阁楼掏出来。
- 悬山不写 `gable_material` 两端是开着的；要封就写。
