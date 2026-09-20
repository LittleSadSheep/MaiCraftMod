# 东亚意向院落与回廊

目标：通过院落留白、柱网、屋檐和室内外过渡形成整体。先选具体方向，避免把红柱、白墙、格栅、鸟居和夸张翘角同时堆到每个角落；用户要求考据复原时，不能用这份意向教程冒充史料。

## 选择一个组织方向

轴线院落可以采用正房、较低侧翼和明确院门，让主要视线落到正房；柱色可以更深或带暖色，白墙与灰色屋面保持清楚分层。偏置庭园可以用低矮横向体量、自然木色、较深屋檐和曲折进入路径，把主房面向庭园。二者都是游戏构图选项，应按用户要求选择，不当作地域建筑的统一定律。

先留出院子的完整空区，再放建筑。小地块宜用 L 形房屋加一段回廊，不必强塞四合围满的布局。正房、侧房和廊的屋顶高度有主次，连接处的柱、檐和屋面要一起对齐。

材质可选 stripped_spruce_log 或 dark_oak_log 作柱，white_terracotta 作填充墙，deepslate_tiles 作屋面，stone_bricks 作台基。先固定一套木色；需要红色意向时可局部用 red_terracotta，但它没有原木纹理，不必伪装成同一种结构表达。

## 可走的重复回廊

这是三个相接的廊间，地板一格厚，柱位在两侧，中央通道保持开放。每个廊间五格宽，步距四格，共用边柱；屋面用有限的台阶层表达坡度。它是回廊组件示例，不含住宅、庭园和院门。

```json
{
  "schema_version":2, "name":"CourtyardGallery", "coordinate_system":"minecraft_y_up",
  "materials":{
    "Floor":{"block_id":"minecraft:stone_bricks"},
    "Post":{"block_id":"minecraft:stripped_spruce_log","properties":{"axis":"y"}},
    "Beam":{"block_id":"minecraft:stripped_spruce_log","properties":{"axis":"x"}},
    "Roof":{"block_id":"minecraft:deepslate_tiles"}
  },
  "components":{
    "GalleryBay":{"objects":[
      {"name":"Floor","type":"MESH","primitive":"panel","location":[2.5,0.5,2.5],"dimensions":[5,1,5],"material":"Floor"},
      {"name":"FrontLeft","type":"MESH","primitive":"cube","location":[0.5,2.5,0.5],"dimensions":[1,3,1],"material":"Post"},
      {"name":"FrontRight","type":"MESH","primitive":"cube","location":[4.5,2.5,0.5],"dimensions":[1,3,1],"material":"Post"},
      {"name":"BackLeft","type":"MESH","primitive":"cube","location":[0.5,2.5,4.5],"dimensions":[1,3,1],"material":"Post"},
      {"name":"BackRight","type":"MESH","primitive":"cube","location":[4.5,2.5,4.5],"dimensions":[1,3,1],"material":"Post"},
      {"name":"FrontBeam","type":"MESH","primitive":"cube","location":[2.5,4.5,0.5],"dimensions":[5,1,1],"material":"Beam"},
      {"name":"BackBeam","type":"MESH","primitive":"cube","location":[2.5,4.5,4.5],"dimensions":[5,1,1],"material":"Beam"},
      {"name":"RoofBase","type":"MESH","primitive":"panel","location":[2.5,5.5,2.5],"dimensions":[7,1,7],"material":"Roof"},
      {"name":"RoofRise","type":"MESH","primitive":"panel","location":[2.5,6.5,2.5],"dimensions":[7,1,3],"material":"Roof"},
      {"name":"RoofRidge","type":"MESH","primitive":"panel","location":[2.5,7.5,2.5],"dimensions":[7,1,1],"material":"Roof"}
    ]}
  },
  "objects":[
    {"name":"Gallery","type":"INSTANCE","component":"GalleryBay","location":[0,0,0],"array":{"count":[3,1,1],"step":[4,0,0]}}
  ]
}
```

廊是低矮过渡空间，此例的连续顶板可以作为廊顶；把它直接放大成正房会填满阁楼，应改用空屋顶截面。拐角连接时先处理重叠屋檐，再处理角柱，不能让两条回廊在角上互堵。阵列只能生成轴向网格，台阶上升用显式位置，不能把 step 当作斜向复制向量。

## 变化与收口

在院落宽深比、入口偏移、单侧回廊、主房高低和庭园焦点中选择两项变化。树、水面或石景只选与空间尺度匹配的重点；场地或能力未给出时先预留庭园位置，不编造植物自动生长或水流效果。格栅可读“镂空格栅与交替半砖”，用在局部屏风，不堵住全部门窗。

交付前检查：院子保持可用空区、回廊连接各入口、柱子没有落在主通道中央、屋檐不撞侧房窗、材质和屋顶节奏保持一致。几何预留通路与角色已经走通是两种不同证据。
