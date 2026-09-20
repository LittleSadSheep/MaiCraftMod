# 欧式木构小屋与街屋

目标：用清楚的框架、浅色填充和坡顶形成温暖、可居住的街景。这里是 Minecraft 风格化设计方法，不代表某一地区建筑的考据复原。

## 先选一种体量路线

| 路线 | 体量与屋顶 | 门窗与记忆点 |
| --- | --- | --- |
| 乡间小屋 | 低层主屋加较低工具间，长脊双坡 | 少量宽窗，偏置烟囱或门廊 |
| 紧凑街屋 | 窄面宽、两层，脊线强调高度 | 一层入口与橱窗、二层较小窗组 |
| 酒馆或客栈 | 主厅加侧翼，不同高度坡顶 | 醒目入口、局部挑廊或一组塔窗 |

用途没有指定时选与地块相符的一条，不把烟囱、塔楼、挑廊、老虎窗全部堆到小屋上。变化优先来自附翼位置、屋顶方向和入口位置；同一街区可以共用材质与窗间尺度，同时保持各栋轮廓不同。

## 形体与用材

主体可选 white_terracotta 或 smooth_sandstone；框架用 spruce_log 或 dark_oak_log；基座用 stone_bricks；屋顶可选深色木板或 deepslate_tiles。主木色选一种，避免每根梁都换木种。柱用竖纹，水平梁用对应轴向，不让所有原木端面朝外。

先把立面分成 3–5 格左右的窗间，再设置柱、楼层横梁和窗。框架与填充要有清楚位置关系；局部框架向外一格形成阴影，但窄房间不要让粗柱吞掉室内。斜撑可以用少量阶梯块表达，任意角度旋转不在当前变换能力内。

一层以入口和工作功能为主，二层窗组较有规律。坡顶需真实空截面和山墙，可选“坡屋顶、屋檐与山墙”教程。后立面至少保留对应房间的窗和收口，不能只雕正脸。

## 可复用窗间

此例只有三个相接的窗间，未包含侧墙、后墙、入口或屋顶。每个窗间占五格宽，步距四格使相邻边柱共用；同材质重叠是有意的。将其用于住宅前，先按房间确定重复次数，并以独立的门间替换入口位置。

```json
{
  "schema_version":2, "name":"TimberWindowBays", "coordinate_system":"minecraft_y_up",
  "materials":{
    "Base":{"block_id":"minecraft:stone_bricks"},
    "Plaster":{"block_id":"minecraft:white_terracotta"},
    "Post":{"block_id":"minecraft:spruce_log","properties":{"axis":"y"}},
    "Beam":{"block_id":"minecraft:spruce_log","properties":{"axis":"x"}},
    "Glass":{"block_id":"minecraft:glass"}
  },
  "components":{
    "WindowBay":{"objects":[
      {"name":"Base","type":"MESH","primitive":"cube","location":[2.5,0.5,1],"dimensions":[5,1,2],"material":"Base"},
      {"name":"Infill","type":"MESH","primitive":"panel","location":[2.5,3,1.5],"dimensions":[5,4,1],"material":"Plaster","modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"WindowCut"}]},
      {"name":"WindowCut","type":"MESH","primitive":"cube","location":[2.5,3,1.5],"dimensions":[3,2,3]},
      {"name":"Glass","type":"MESH","primitive":"panel","location":[2.5,3,1.5],"dimensions":[3,2,1],"material":"Glass"},
      {"name":"LeftPost","type":"MESH","primitive":"cube","location":[0.5,3.5,0.5],"dimensions":[1,5,1],"material":"Post"},
      {"name":"RightPost","type":"MESH","primitive":"cube","location":[4.5,3.5,0.5],"dimensions":[1,5,1],"material":"Post"},
      {"name":"TopBeam","type":"MESH","primitive":"cube","location":[2.5,5.5,0.5],"dimensions":[5,1,1],"material":"Beam"},
      {"name":"Sill","type":"MESH","primitive":"cube","location":[2.5,1.5,0.5],"dimensions":[3,1,1],"material":"Beam"}
    ]}
  },
  "objects":[
    {"name":"FrontBays","type":"INSTANCE","component":"WindowBay","location":[0,0,0],"array":{"count":[3,1,1],"step":[4,0,0]}}
  ]
}
```

不要把窗间均匀铺满四面后才找门。入口可占一个较宽窗间，侧翼可减少一个窗间并降低屋顶；材质替换用角色映射，比例变化则修改组件几何。每次变体选一个主要特征，其余保持克制。

交付前检查：木框有结构节奏、填充墙不抢主次、屋顶没有实心填满房间、至少存在可进入的入口，以及变化没有仅停留在换木种。
