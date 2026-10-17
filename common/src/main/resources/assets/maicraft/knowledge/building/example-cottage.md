# 完整例子：带门廊的木构小屋

从要求到一张完整图纸的示范。先按这次的委托重新设计，别每次把它换个材料就交。图纸格式看 `lookup(topic=knowledge, id=building/design)`，屋顶看 `lookup(topic=knowledge, id=building/roofs)`。

## 委托与决定

委托是"小型单层木构住宅，能从正面进入，四面有采光，带一个遮雨的门廊"。假设地块平整、空着，前方是 -z；这些是例子的假设，不是对当前世界的勘察。

纲要：主屋外宽外深都是 7 格，地板占 y=0，室内净宽 5 格、净高 4 格（y 1..4）；墙身 3 格高（y 1..3），上面一圈木梁（y=4），再上面是悬山屋顶，檐口基准 y=5，出檐 1 格；深色木框、浅色填充墙分工；入口正对门廊，门廊是唯一的记忆点。主屋占 `[0, 7)×[0, 7)`，屋顶水平占 `[-1, 8)×[-1, 8)`，门廊伸到 z=-2；算上出檐整栋占 9×10 格，不能只按主屋尺寸判断放不放得下。

这是一份完整外壳：地板、室内空区、四面墙、三面窗、上下两半门、柱、梁、屋顶、门廊。没有家具、室内照明和周边道路；门是关着放上去的，施工不会替你开门。

## 图纸

```json
{
  "name": "木构小屋",
  "coordinate_system": "minecraft_y_up",
  "materials": {
    "Base": {"block_id": "minecraft:stone_bricks"},
    "Wall": {"block_id": "minecraft:white_terracotta"},
    "Post": {"block_id": "minecraft:spruce_log", "properties": {"axis": "y"}},
    "BeamX": {"block_id": "minecraft:spruce_log", "properties": {"axis": "x"}},
    "BeamZ": {"block_id": "minecraft:spruce_log", "properties": {"axis": "z"}},
    "Roof": {"block_id": "minecraft:dark_oak_planks"},
    "Glass": {"block_id": "minecraft:glass"},
    "DoorLower": {"block_id": "minecraft:spruce_door", "properties": {"half": "lower", "facing": "north", "hinge": "left"}},
    "DoorUpper": {"block_id": "minecraft:spruce_door", "properties": {"half": "upper", "facing": "north", "hinge": "left"}},
    "Clear": {"block_id": "minecraft:air"}
  },
  "components": {
    "SideWall": {"objects": [
      {"name": "Wall", "type": "MESH", "primitive": "panel", "location": [0.5, 2.5, 3.5], "dimensions": [1, 3, 5], "material": "Wall",
       "modifiers": [{"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "WindowCut"}]},
      {"name": "WindowCut", "type": "MESH", "primitive": "cube", "location": [0.5, 3, 3.5], "dimensions": [3, 2, 3]},
      {"name": "Glass", "type": "MESH", "primitive": "panel", "location": [0.5, 3, 3.5], "dimensions": [1, 2, 3], "material": "Glass"}
    ]}
  },
  "objects": [
    {"name": "Floor", "type": "MESH", "primitive": "panel", "location": [3.5, 0.5, 3.5], "dimensions": [7, 1, 7], "material": "Base"},
    {"name": "Interior", "type": "MESH", "primitive": "cube", "location": [3.5, 3, 3.5], "dimensions": [5, 4, 5], "material": "Clear"},
    {"name": "FrontWall", "type": "MESH", "primitive": "panel", "location": [3.5, 2.5, 0.5], "dimensions": [5, 3, 1], "material": "Wall",
     "modifiers": [{"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "DoorCut"}, {"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "FrontWindowCuts"}]},
    {"name": "DoorCut", "type": "MESH", "primitive": "cube", "location": [3.5, 2, 0.5], "dimensions": [1, 2, 3]},
    {"name": "FrontWindowCuts", "type": "MESH", "primitive": "cube", "location": [1.5, 2.5, 0.5], "dimensions": [1, 1, 3], "array": {"count": [2, 1, 1], "step": [4, 0, 0]}},
    {"name": "FrontGlass", "type": "MESH", "primitive": "panel", "location": [1.5, 2.5, 0.5], "dimensions": [1, 1, 1], "material": "Glass", "array": {"count": [2, 1, 1], "step": [4, 0, 0]}},
    {"name": "DoorLower", "type": "MESH", "primitive": "cube", "location": [3.5, 1.5, 0.5], "dimensions": [1, 1, 1], "material": "DoorLower"},
    {"name": "DoorUpper", "type": "MESH", "primitive": "cube", "location": [3.5, 2.5, 0.5], "dimensions": [1, 1, 1], "material": "DoorUpper"},
    {"name": "BackWall", "type": "MESH", "primitive": "panel", "location": [3.5, 2.5, 6.5], "dimensions": [5, 3, 1], "material": "Wall",
     "modifiers": [{"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "BackWindowCut"}]},
    {"name": "BackWindowCut", "type": "MESH", "primitive": "cube", "location": [3.5, 3, 6.5], "dimensions": [3, 2, 3]},
    {"name": "BackGlass", "type": "MESH", "primitive": "panel", "location": [3.5, 3, 6.5], "dimensions": [3, 2, 1], "material": "Glass"},
    {"name": "SideWalls", "type": "INSTANCE", "component": "SideWall", "location": [0, 0, 0], "array": {"count": [2, 1, 1], "step": [6, 0, 0]}},
    {"name": "CornerPosts", "type": "MESH", "primitive": "cube", "location": [0.5, 2.5, 0.5], "dimensions": [1, 3, 1], "material": "Post", "array": {"count": [2, 1, 2], "step": [6, 0, 6]}},
    {"name": "EndBeams", "type": "MESH", "primitive": "cube", "location": [3.5, 4.5, 0.5], "dimensions": [7, 1, 1], "material": "BeamX", "array": {"count": [1, 1, 2], "step": [0, 0, 6]}},
    {"name": "SideBeams", "type": "MESH", "primitive": "cube", "location": [0.5, 4.5, 3.5], "dimensions": [1, 1, 5], "material": "BeamZ", "array": {"count": [2, 1, 1], "step": [6, 0, 0]}},
    {"name": "Roof", "type": "ROOF", "shape": "xuanshan", "location": [3.5, 5, 3.5], "footprint": [7, 7], "material": "Roof", "gable_material": "Wall", "overhang": 1},
    {"name": "PorchFloor", "type": "MESH", "primitive": "panel", "location": [3.5, 0.5, -1], "dimensions": [3, 1, 2], "material": "Base"},
    {"name": "PorchPosts", "type": "MESH", "primitive": "cube", "location": [2.5, 2.5, -1.5], "dimensions": [1, 3, 1], "material": "Post", "array": {"count": [2, 1, 1], "step": [2, 0, 0]}},
    {"name": "PorchCanopy", "type": "MESH", "primitive": "panel", "location": [3.5, 4.5, -1], "dimensions": [3, 1, 2], "material": "Roof"}
  ]
}
```

## 几处为什么这么写

- 前后墙只占 x 1..5、侧墙只占 z 1..5，四个角留给柱：墙和柱不抢同一格，结果里就没有叠加冲突。
- 墙 3 格高，第 4 格是一圈梁，梁的 `axis` 按走向分 `BeamX` / `BeamZ`；室内空区写到 y=4，梁只在四周。
- 门分两格，各自写材料里的 `half`；窗洞用切割体挖，玻璃板再放进洞里，玻璃不会和墙抢格。
- 屋顶一个 ROOF 对象：`footprint` 7×7 盖住主屋，出檐 1 格，`gable_material` 用墙的材料封两端。
- 门廊地板、两根柱、一块顶板，顶板在 y=4，不和出檐（y≥5）打架。

## 改成别的房子

- 拉长：地板、室内空区、前后墙的位置、侧墙的长度、梁、屋顶的 `footprint` 一起改；侧窗按新长度重新排。
- 加侧翼：另写一组墙和一个更低的 ROOF，交界处先写主屋再写侧翼。
- 偏置入口：门洞、两扇门、门廊一起挪，前窗重新平衡。
- 加阁楼：先画楼梯、楼板洞口和净高，再在屋顶里放一个 `minecraft:air` 的对象把阁楼掏出来；只在外面加窗不算多了一层。
- 换风格：从那种风格的体量和开窗方式重新决定，不是把白墙换成石头。
