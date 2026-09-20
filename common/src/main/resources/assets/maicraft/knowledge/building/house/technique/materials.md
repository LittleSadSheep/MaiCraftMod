# 材质角色与立面进深

适用：配色选择、立面扁平、已有设计换材。先区分结构角色，再选方块；不要逐格独立抽签。

## 一个可操作的配色方法

确定一种占大面积的主体材、一种支撑轮廓的框架材、一种屋顶材；透明面和点缀单独考虑。可从主体约六成、框架约三成、点缀约一成的视觉面积起步，这只是构图提示，不是要写进 JSON 的比例或方块数量约束。

| 角色 | 可尝试的原版方块 | 需要观察的关系 |
| --- | --- | --- |
| 明亮填充 | white_terracotta、smooth_sandstone、white_concrete | 与框架保持明度差，三者色温不同，选一为主 |
| 暖色框架 | spruce_log、dark_oak_log、stripped_spruce_log | 原木纹理有方向；梁与柱要分别设置 axis |
| 厚重基座 | stone_bricks、cobbled_deepslate、andesite | 比上部视觉更重，纹理不应盖住门窗 |
| 深色屋面 | deepslate_tiles、dark_oak_planks | 与主体轮廓分开，避免墙顶完全融在一起 |
| 工业填充 | bricks、stone、gray_concrete | 大片粗糙墙面配较清楚的框架或窗组 |

表中 ID 省略了 `minecraft:` 前缀，提交时必须补全；这是候选清单，不是库存证明。换材保持角色和明暗关系，例如深色木框可以换深石框，但风格可能随之变化。不能把所有方块都替成同一色，仍声称保留了原来的层次。

纹理应有位置依据：基座一条石带、窗下磨损、转角加固、受遮盖处较整洁。先选择两种相近材料，再用少量具名区域安排变化；满墙棋盘格和逐格随机花纹通常会淹没构图。生存材料受限时优先保留体量和窗洞深度，再减少昂贵装饰。

## 窗洞进深示例

这是一块立面教学板，不是整栋房屋。墙和玻璃位于 `z=1`，框线和窗台位于 `z=0`，让深度来自真实几何；玻璃使用独立对象填入墙上的开孔。

```json
{
  "schema_version": 2, "name": "RecessedWindow", "coordinate_system": "minecraft_y_up",
  "materials": {
    "Wall": {"block_id":"minecraft:smooth_sandstone"},
    "Trim": {"block_id":"minecraft:stone_bricks"},
    "Glass": {"block_id":"minecraft:glass"}
  },
  "objects": [
    {"name":"Base","type":"MESH","primitive":"cube","location":[4.5,0.5,1],"dimensions":[9,1,2],"material":"Trim"},
    {"name":"Wall","type":"MESH","primitive":"panel","location":[4.5,3.5,1.5],"dimensions":[9,5,1],"material":"Wall","modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"WindowCut"}]},
    {"name":"WindowCut","type":"MESH","primitive":"cube","location":[4.5,3.5,1.5],"dimensions":[3,3,3]},
    {"name":"WindowGlass","type":"MESH","primitive":"panel","location":[4.5,3.5,1.5],"dimensions":[3,3,1],"material":"Glass"},
    {"name":"LeftTrim","type":"MESH","primitive":"cube","location":[2.5,3.5,0.5],"dimensions":[1,5,1],"material":"Trim"},
    {"name":"RightTrim","type":"MESH","primitive":"cube","location":[6.5,3.5,0.5],"dimensions":[1,5,1],"material":"Trim"},
    {"name":"Lintel","type":"MESH","primitive":"cube","location":[4.5,5.5,0.5],"dimensions":[5,1,1],"material":"Trim"},
    {"name":"Sill","type":"MESH","primitive":"cube","location":[4.5,1.5,0.5],"dimensions":[5,1,1],"material":"Trim"}
  ]
}
```

把 Glass 放进开孔不会被 Boolean 空气擦除，但未开孔的实墙会与玻璃争同一格；先明确开孔关系，再决定对象顺序。窗格玻璃、栅栏等连接状态还会受邻居影响，不能用编译成功代替现场状态检查。

扩窗时同步扩大 WindowCut 和 WindowGlass，并移动两侧框线；凹窗更深时补足洞口侧壁，避免露出墙后的空缝。一格阴影已经足够的小立面，不要继续包多圈装饰。材质替换后再次看主次关系，而不只是核对 JSON 中的字符串。
