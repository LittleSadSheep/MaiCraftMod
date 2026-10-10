# 几种风格怎么起手

每种风格先选一条体量路线和一套材料角色，再从变化轴里挑两三项；别把所有符号堆到一栋小房子上。这里的例子都是局部模块，不是整栋房子；图纸格式看 `lookup(topic=knowledge, id=building/design)`，设计方法看 `lookup(topic=knowledge, id=building/house)`。

## 欧式木构

深色承重框架、浅色填充墙、坡顶。主体 `white_terracotta` 或 `smooth_sandstone`，框架 `spruce_log` / `dark_oak_log`（柱 `axis: y`，梁按走向），基座 `stone_bricks`，屋面深色木板或 `deepslate_tiles`。立面按 3–5 格的窗间分段，柱、楼层横梁、窗各就各位；框架向外一格就有阴影。路线：乡间小屋（低主屋加更低的工具间，长脊双坡，少量宽窗）、紧凑街屋（窄面宽两层，一层入口与橱窗、二层小窗）、酒馆客栈（主厅加侧翼，不同高度的坡顶，醒目入口）。窗间组件的写法见图纸格式那一页的"组件与实例"。

## 欧式石造

厚重基座、深窗、入口主次、稳定的屋顶轮廓。主墙 `stone_bricks` 或 `smooth_sandstone`，基座更粗更暗，少量 `chiseled_stone_bricks` 标记入口或檐线，屋顶用深色压住墙面。厚墙让门窗向内退一格，窗组竖向比例；拱门用阶梯状的内轮廓表达。路线：小型石宅（紧凑主屋、陡双坡、偏置门廊）、庄园（入口主轴、主厅高、两翼低）、边堡（主楼加一座能进的塔）。

```json
{
  "name": "阶梯拱入口",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Wall": {"block_id": "minecraft:stone_bricks"}, "Trim": {"block_id": "minecraft:chiseled_stone_bricks"}, "Cap": {"block_id": "minecraft:polished_andesite"}},
  "objects": [
    {"name": "EntryWall", "type": "MESH", "primitive": "cube", "location": [4.5, 3.5, 1], "dimensions": [9, 7, 2], "material": "Wall",
     "modifiers": [{"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "LowerCut"}, {"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "MiddleCut"}, {"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "TopCut"}]},
    {"name": "LowerCut", "type": "MESH", "primitive": "cube", "location": [4.5, 2, 1], "dimensions": [5, 4, 4]},
    {"name": "MiddleCut", "type": "MESH", "primitive": "cube", "location": [4.5, 4.5, 1], "dimensions": [3, 1, 4]},
    {"name": "TopCut", "type": "MESH", "primitive": "cube", "location": [4.5, 5.5, 1], "dimensions": [1, 1, 4]},
    {"name": "LeftPier", "type": "MESH", "primitive": "cube", "location": [0.5, 3.5, -0.5], "dimensions": [1, 7, 1], "material": "Trim"},
    {"name": "RightPier", "type": "MESH", "primitive": "cube", "location": [8.5, 3.5, -0.5], "dimensions": [1, 7, 1], "material": "Trim"},
    {"name": "KeyAccent", "type": "MESH", "primitive": "cube", "location": [4.5, 6.5, -0.5], "dimensions": [1, 1, 1], "material": "Trim"},
    {"name": "Cornice", "type": "MESH", "primitive": "cube", "location": [4.5, 7.5, 0.5], "dimensions": [11, 1, 3], "material": "Cap"}
  ]
}
```

九格宽的厚墙入口：开口下部五格宽，往上收成三格、一格，三个切割体都只切 `EntryWall`。拱门留给主入口，窗用矩形深窗。

## 东亚院落

院落留白、柱网、屋檐和室内外过渡。先留出完整的院子再放房子；正房、侧房、回廊的屋顶有主次，连接处的柱、檐一起对齐。柱 `stripped_spruce_log` 或 `dark_oak_log`，填充墙 `white_terracotta`，屋面 `deepslate_tiles`，台基 `stone_bricks`；要红色意向时局部用 `red_terracotta`。屋顶直接用 ROOF 对象：正房庑殿或歇山，侧房悬山，亭子攒尖，廊子单坡。

```json
{
  "name": "回廊三间",
  "coordinate_system": "minecraft_y_up",
  "materials": {
    "Floor": {"block_id": "minecraft:stone_bricks"},
    "Post": {"block_id": "minecraft:stripped_spruce_log", "properties": {"axis": "y"}},
    "Beam": {"block_id": "minecraft:dark_oak_log", "properties": {"axis": "x"}},
    "Tile": {"block_id": "minecraft:deepslate_tiles"}
  },
  "components": {
    "GalleryBay": {"objects": [
      {"name": "Floor", "type": "MESH", "primitive": "panel", "location": [2.5, 0.5, 2.5], "dimensions": [5, 1, 5], "material": "Floor"},
      {"name": "Posts", "type": "MESH", "primitive": "cube", "location": [0.5, 2.5, 0.5], "dimensions": [1, 3, 1], "material": "Post", "array": {"count": [2, 1, 2], "step": [4, 0, 4]}},
      {"name": "Beams", "type": "MESH", "primitive": "cube", "location": [2.5, 4.5, 0.5], "dimensions": [5, 1, 1], "material": "Beam", "array": {"count": [1, 1, 2], "step": [0, 0, 4]}}
    ]}
  },
  "objects": [
    {"name": "Gallery", "type": "INSTANCE", "component": "GalleryBay", "location": [0, 0, 0], "array": {"count": [3, 1, 1], "step": [4, 0, 0]}},
    {"name": "Roof", "type": "ROOF", "shape": "shed", "location": [6.5, 5, 2.5], "footprint": [13, 5], "material": "Tile", "overhang": 1}
  ]
}
```

三个相接的廊间共用边柱（同材料叠在同一格不算冲突），上面盖一片单坡屋顶。拐角处先处理叠在一起的屋檐，再处理角柱。

## 现代

明确的体块、实体与开口的比例、退台和阴影。主材 `white_concrete`、`smooth_quartz` 或 `light_gray_concrete` 选一种，配少量 `spruce_planks`、`stone` 或 `gray_concrete`；玻璃也算一个视觉角色，楼板边、柱、框别做成透明的。檐线连续，窗凹一两格用遮阳框做深度，悬挑要有起止和视觉支撑。路线：紧凑住宅（一个主体块配低入口）、庭院住宅（L 形或 U 形围院，玻璃朝内）、退台（高低体块逐层退让，只有现场高差已知才说顺应地形）。

```json
{
  "name": "深窗与遮阳框",
  "coordinate_system": "minecraft_y_up",
  "materials": {"Deck": {"block_id": "minecraft:smooth_stone"}, "Shell": {"block_id": "minecraft:white_concrete"}, "Glass": {"block_id": "minecraft:glass"}},
  "objects": [
    {"name": "Deck", "type": "MESH", "primitive": "panel", "location": [4.5, -0.5, 0.5], "dimensions": [9, 1, 5], "material": "Deck"},
    {"name": "Wall", "type": "MESH", "primitive": "cube", "location": [4.5, 3, 1], "dimensions": [9, 6, 2], "material": "Shell",
     "modifiers": [{"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "WindowCut"}]},
    {"name": "WindowCut", "type": "MESH", "primitive": "cube", "location": [4.5, 3, 1], "dimensions": [5, 4, 4]},
    {"name": "Glass", "type": "MESH", "primitive": "panel", "location": [4.5, 3, 1.5], "dimensions": [5, 4, 1], "material": "Glass"},
    {"name": "Canopy", "type": "MESH", "primitive": "panel", "location": [4.5, 6.5, 0.5], "dimensions": [9, 1, 5], "material": "Shell"},
    {"name": "LeftSupport", "type": "MESH", "primitive": "cube", "location": [0.5, 3, -1.5], "dimensions": [1, 6, 1], "material": "Shell"},
    {"name": "RightSupport", "type": "MESH", "primitive": "cube", "location": [8.5, 3, -1.5], "dimensions": [1, 6, 1], "material": "Shell"}
  ]
}
```

两格厚的墙开五格宽的窗洞，玻璃在后排；顶板向前伸、两侧有支柱、前面是平台。入口另外设计，玻璃窗不是门。

## 工业

外形跟着工作流程：机器区、运输口、检修通道、仓储、人员入口先于管线装饰。先留一条通道贯穿入口、工作区和仓储；设备占地已知就一起交给平面。主体 `bricks`、`stone` 或 `gray_concrete`，框架 `polished_andesite`、`deepslate_tiles` 或木材，屋顶深色；窗组集中在上部，下方留连续的工作墙。大面积重复要有少量变化点：入口跨加宽、设备区增高、端墙收口不同。

```json
{
  "name": "高窗与宽入口端墙",
  "coordinate_system": "minecraft_y_up",
  "overlap_policy": "last_wins",
  "materials": {"Infill": {"block_id": "minecraft:bricks"}, "Frame": {"block_id": "minecraft:polished_andesite"}, "Glass": {"block_id": "minecraft:glass"}},
  "objects": [
    {"name": "LeftEdge", "type": "MESH", "primitive": "cube", "location": [0.5, 4.5, 0.5], "dimensions": [1, 9, 1], "material": "Frame"},
    {"name": "LeftPier", "type": "MESH", "primitive": "cube", "location": [3.5, 4.5, 0.5], "dimensions": [1, 9, 1], "material": "Frame"},
    {"name": "RightPier", "type": "MESH", "primitive": "cube", "location": [9.5, 4.5, 0.5], "dimensions": [1, 9, 1], "material": "Frame"},
    {"name": "RightEdge", "type": "MESH", "primitive": "cube", "location": [12.5, 4.5, 0.5], "dimensions": [1, 9, 1], "material": "Frame"},
    {"name": "EntryBeam", "type": "MESH", "primitive": "cube", "location": [6.5, 5.5, 0.5], "dimensions": [13, 1, 1], "material": "Frame"},
    {"name": "TopBeam", "type": "MESH", "primitive": "cube", "location": [6.5, 8.5, 0.5], "dimensions": [13, 1, 1], "material": "Frame"},
    {"name": "Wall", "type": "MESH", "primitive": "panel", "location": [6.5, 4.5, 1.5], "dimensions": [13, 9, 1], "material": "Infill",
     "modifiers": [{"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "EntryCut"}, {"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "WindowCuts"}]},
    {"name": "EntryCut", "type": "MESH", "primitive": "cube", "location": [6.5, 2.5, 1.5], "dimensions": [5, 5, 3]},
    {"name": "WindowCuts", "type": "MESH", "primitive": "cube", "location": [2, 7, 1.5], "dimensions": [2, 2, 3], "array": {"count": [2, 1, 1], "step": [9, 0, 0]}},
    {"name": "Panes", "type": "MESH", "primitive": "panel", "location": [2, 7, 1.5], "dimensions": [2, 2, 1], "material": "Glass", "array": {"count": [2, 1, 1], "step": [9, 0, 0]}}
  ]
}
```

十三格宽、九格高的端墙：中央开口五格宽五格高，两侧各一组高窗，框架在墙前一格。横梁和柱在交点叠在同一格，材料相同不算冲突；材料不同时后写的赢，所以先写柱再写梁。设备进出的门洞要和后面的柱网、通道、屋面梁一起检查。
