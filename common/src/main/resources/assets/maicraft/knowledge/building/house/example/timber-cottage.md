# 完整案例：带门廊的木构小屋外壳

适用：需要参考从需求到完整作者场景的组合方式。先按本次委托重新设计，不要每次把这个案例原样换材交付。

## 委托与设计决定

示例委托是“小型单层木构住宅，能从正面进入，四面有采光，带一个遮雨门廊”。假设为平坦、可用的空地，前方是 `-Z`；这些是示例假设，不是对当前世界的探测结果。

设计纲要：主屋外宽和外深均 7 格，地板占 `y=0`，室内净宽 5 格，墙身高 4 格，坡顶额外留空；深木框与浅墙分工，双坡屋脊沿 Z；入口正对门廊，用小尺度前伸形成一个记忆点。主体占 `[0,7)×[0,7)`，屋顶水平占 `[-1,8)×[-1,8)`，门廊另伸到 `z=-2`。含外挑后的总水平范围为 9×10 格，不能只用主屋尺寸判断是否放得下。

这是一份完整外壳，含地板、空气区、墙、窗、上下两半门、框架、空屋顶、山墙和门廊。它未提供家具、室内照明、周边道路或环境清理方案；这些需要按最终委托补齐。门扇是否已打开、材料是否齐全、角色能否走到门口仍由游戏现场确认。

## 作者场景

墙上的切割体各有明确目标；屋顶用九段长条表达空截面，山墙重复两份。门的上下半分别建模，不能把两格高的盒子都标成 lower。材质只声明需要保留的状态，未强求门的开关状态。

```json
{
  "schema_version":2, "name":"TimberCottage", "coordinate_system":"minecraft_y_up",
  "materials":{
    "Base":{"block_id":"minecraft:stone_bricks"},
    "Wall":{"block_id":"minecraft:white_terracotta"},
    "Post":{"block_id":"minecraft:spruce_log","properties":{"axis":"y"}},
    "BeamX":{"block_id":"minecraft:spruce_log","properties":{"axis":"x"}},
    "BeamZ":{"block_id":"minecraft:spruce_log","properties":{"axis":"z"}},
    "Roof":{"block_id":"minecraft:dark_oak_planks"},
    "Glass":{"block_id":"minecraft:glass"},
    "DoorLower":{"block_id":"minecraft:spruce_door","properties":{"half":"lower","facing":"north","hinge":"left"}},
    "DoorUpper":{"block_id":"minecraft:spruce_door","properties":{"half":"upper","facing":"north","hinge":"left"}},
    "Clear":{"block_id":"minecraft:air"}
  },
  "components":{
    "SideWall":{"objects":[
      {"name":"Wall","type":"MESH","primitive":"panel","location":[0.5,3,3.5],"dimensions":[1,4,5],"material":"Wall","modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"WindowCut"}]},
      {"name":"WindowCut","type":"MESH","primitive":"cube","location":[0.5,3,3.5],"dimensions":[3,2,3]},
      {"name":"Glass","type":"MESH","primitive":"panel","location":[0.5,3,3.5],"dimensions":[1,2,3],"material":"Glass"}
    ]},
    "Gable":{"objects":[
      {"name":"Base","type":"MESH","primitive":"panel","location":[3.5,5.5,0.5],"dimensions":[7,1,1],"material":"Wall"},
      {"name":"Middle","type":"MESH","primitive":"panel","location":[3.5,6.5,0.5],"dimensions":[5,1,1],"material":"Wall"},
      {"name":"Upper","type":"MESH","primitive":"panel","location":[3.5,7.5,0.5],"dimensions":[3,1,1],"material":"Wall"},
      {"name":"Tip","type":"MESH","primitive":"panel","location":[3.5,8.5,0.5],"dimensions":[1,1,1],"material":"Wall"}
    ]}
  },
  "objects":[
    {"name":"Floor","type":"MESH","primitive":"panel","location":[3.5,0.5,3.5],"dimensions":[7,1,7],"material":"Base"},
    {"name":"InteriorClear","type":"MESH","primitive":"cube","location":[3.5,3,3.5],"dimensions":[5,4,5],"material":"Clear"},
    {"name":"FrontWall","type":"MESH","primitive":"panel","location":[3.5,3,0.5],"dimensions":[7,4,1],"material":"Wall","modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"DoorCut"},{"type":"BOOLEAN","operation":"DIFFERENCE","object":"FrontLeftCut"},{"type":"BOOLEAN","operation":"DIFFERENCE","object":"FrontRightCut"}]},
    {"name":"DoorCut","type":"MESH","primitive":"cube","location":[3.5,2,0.5],"dimensions":[1,2,3]},
    {"name":"FrontLeftCut","type":"MESH","primitive":"cube","location":[1.5,3,0.5],"dimensions":[1,2,3]},
    {"name":"FrontRightCut","type":"MESH","primitive":"cube","location":[5.5,3,0.5],"dimensions":[1,2,3]},
    {"name":"FrontLeftGlass","type":"MESH","primitive":"panel","location":[1.5,3,0.5],"dimensions":[1,2,1],"material":"Glass"},
    {"name":"FrontRightGlass","type":"MESH","primitive":"panel","location":[5.5,3,0.5],"dimensions":[1,2,1],"material":"Glass"},
    {"name":"DoorLower","type":"MESH","primitive":"cube","location":[3.5,1.5,0.5],"dimensions":[1,1,1],"material":"DoorLower"},
    {"name":"DoorUpper","type":"MESH","primitive":"cube","location":[3.5,2.5,0.5],"dimensions":[1,1,1],"material":"DoorUpper"},
    {"name":"BackWall","type":"MESH","primitive":"panel","location":[3.5,3,6.5],"dimensions":[7,4,1],"material":"Wall","modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"BackWindowCut"}]},
    {"name":"BackWindowCut","type":"MESH","primitive":"cube","location":[3.5,3,6.5],"dimensions":[3,2,3]},
    {"name":"BackGlass","type":"MESH","primitive":"panel","location":[3.5,3,6.5],"dimensions":[3,2,1],"material":"Glass"},
    {"name":"SideWalls","type":"INSTANCE","component":"SideWall","location":[0,0,0],"array":{"count":[2,1,1],"step":[6,0,0]}},
    {"name":"CornerPosts","type":"MESH","primitive":"cube","location":[0.5,3,0.5],"dimensions":[1,4,1],"material":"Post","array":{"count":[2,1,2],"step":[6,0,6]}},
    {"name":"EndBeams","type":"MESH","primitive":"cube","location":[3.5,4.5,0.5],"dimensions":[7,1,1],"material":"BeamX","array":{"count":[1,1,2],"step":[0,0,6]}},
    {"name":"SideBeams","type":"MESH","primitive":"cube","location":[0.5,4.5,3.5],"dimensions":[1,1,5],"material":"BeamZ","array":{"count":[2,1,1],"step":[6,0,0]}},
    {"name":"EaveClosures","type":"MESH","primitive":"cube","location":[0.5,5.5,3.5],"dimensions":[1,1,5],"material":"BeamZ","array":{"count":[2,1,1],"step":[6,0,0]}},
    {"name":"Gables","type":"INSTANCE","component":"Gable","location":[0,0,0],"array":{"count":[1,1,2],"step":[0,0,6]}},
    {"name":"RoofLeftEave","type":"MESH","primitive":"cube","location":[-0.5,5.5,3.5],"dimensions":[1,1,9],"material":"Roof"},
    {"name":"RoofLeftLow","type":"MESH","primitive":"cube","location":[0.5,6.5,3.5],"dimensions":[1,1,9],"material":"Roof"},
    {"name":"RoofLeftMid","type":"MESH","primitive":"cube","location":[1.5,7.5,3.5],"dimensions":[1,1,9],"material":"Roof"},
    {"name":"RoofLeftHigh","type":"MESH","primitive":"cube","location":[2.5,8.5,3.5],"dimensions":[1,1,9],"material":"Roof"},
    {"name":"RoofRidge","type":"MESH","primitive":"cube","location":[3.5,9.5,3.5],"dimensions":[1,1,9],"material":"Roof"},
    {"name":"RoofRightHigh","type":"MESH","primitive":"cube","location":[4.5,8.5,3.5],"dimensions":[1,1,9],"material":"Roof"},
    {"name":"RoofRightMid","type":"MESH","primitive":"cube","location":[5.5,7.5,3.5],"dimensions":[1,1,9],"material":"Roof"},
    {"name":"RoofRightLow","type":"MESH","primitive":"cube","location":[6.5,6.5,3.5],"dimensions":[1,1,9],"material":"Roof"},
    {"name":"RoofRightEave","type":"MESH","primitive":"cube","location":[7.5,5.5,3.5],"dimensions":[1,1,9],"material":"Roof"},
    {"name":"PorchFloor","type":"MESH","primitive":"panel","location":[3.5,0.5,-1],"dimensions":[3,1,2],"material":"Base"},
    {"name":"PorchPosts","type":"MESH","primitive":"cube","location":[2.5,2.5,-1.5],"dimensions":[1,3,1],"material":"Post","array":{"count":[2,1,1],"step":[2,0,0]}},
    {"name":"PorchCanopy","type":"MESH","primitive":"panel","location":[3.5,4.5,-1],"dimensions":[3,1,2],"material":"Roof"}
  ]
}
```

## 复用的是方法

扩成长屋时同时延长地板、空区、侧墙、屋面，移动后墙与后山墙，并重排侧窗；加侧翼时另定低屋顶与内部连接。换成偏置入口时一起移动门洞、门扇和门廊，窗组重新平衡。加阁楼时先设计楼梯、楼板洞口和净高，不能仅在外观上添窗就声称多了一层。

用户要求不同风格时，从该风格的体量和开窗方式重新决定，不能只把本例的木头全部换成白色方块。借用本例的具名对象、完整门半部和几何检查方式，保留本次委托自己的比例、入口和记忆点。
