# 工业厂房与生存工坊

目标：让外形与工作空间一致。机器区、运输口、检修通道、仓储和人员入口先于管线装饰；厂房壳体不代表机器已经安装或能够生产。

## 从工作流程定空间

先留出一条通道贯穿入口、工作区和仓储。已知设备有操作面、检修面或多方块占地时，将这些范围一起交给房间设计；不知道设备尺寸就保留可调整区并说明假设，不凭空写出已适配的设备布局。

小工坊可以用低侧屋加较高主厅，仓库可以采用重复柱网和明确装卸口，展示型工厂可以增加高窗或天窗。大面积重复需要少量变化点：入口跨加宽、设备区增高、端墙收口不同。只为了外观随机打断柱距会削弱整体节奏。

主体可选 bricks、stone 或 gray_concrete，框架用 polished_andesite、deepslate_tiles 或合适木材，屋顶采用深色材质。窗组集中在上部可保留下方连续工作墙。铁块不必铺满整个屋壳；生存预算有限时，先保留柱网、尺度和开窗关系。

## 高窗与宽入口端墙

这是十三格宽、九格高的端墙局部，中央开口五格宽、五格高，两侧各一组高窗，外侧框架避开开口。这里只预留装卸尺度，没有安装门扇、机器或运输机构；实际载具可达性还需要相应游戏证据。

```json
{
  "schema_version":2, "name":"WorkshopEndWall", "coordinate_system":"minecraft_y_up",
  "materials":{
    "Infill":{"block_id":"minecraft:bricks"},
    "Frame":{"block_id":"minecraft:polished_andesite"},
    "Glass":{"block_id":"minecraft:glass"}
  },
  "objects":[
    {"name":"Wall","type":"MESH","primitive":"panel","location":[6.5,4.5,1.5],"dimensions":[13,9,1],"material":"Infill","modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"EntryCut"},{"type":"BOOLEAN","operation":"DIFFERENCE","object":"LeftWindowCut"},{"type":"BOOLEAN","operation":"DIFFERENCE","object":"RightWindowCut"}]},
    {"name":"EntryCut","type":"MESH","primitive":"cube","location":[6.5,2.5,1.5],"dimensions":[5,5,3]},
    {"name":"LeftWindowCut","type":"MESH","primitive":"cube","location":[2,7,1.5],"dimensions":[2,2,3]},
    {"name":"RightWindowCut","type":"MESH","primitive":"cube","location":[11,7,1.5],"dimensions":[2,2,3]},
    {"name":"LeftGlass","type":"MESH","primitive":"panel","location":[2,7,1.5],"dimensions":[2,2,1],"material":"Glass"},
    {"name":"RightGlass","type":"MESH","primitive":"panel","location":[11,7,1.5],"dimensions":[2,2,1],"material":"Glass"},
    {"name":"LeftEdge","type":"MESH","primitive":"cube","location":[0.5,4.5,0.5],"dimensions":[1,9,1],"material":"Frame"},
    {"name":"LeftPier","type":"MESH","primitive":"cube","location":[3.5,4.5,0.5],"dimensions":[1,9,1],"material":"Frame"},
    {"name":"RightPier","type":"MESH","primitive":"cube","location":[9.5,4.5,0.5],"dimensions":[1,9,1],"material":"Frame"},
    {"name":"RightEdge","type":"MESH","primitive":"cube","location":[12.5,4.5,0.5],"dimensions":[1,9,1],"material":"Frame"},
    {"name":"EntryBeam","type":"MESH","primitive":"cube","location":[6.5,5.5,0.5],"dimensions":[13,1,1],"material":"Frame"},
    {"name":"TopBeam","type":"MESH","primitive":"cube","location":[6.5,8.5,0.5],"dimensions":[13,1,1],"material":"Frame"}
  ]
}
```

设备进出需要的门洞应与后续柱网、通道和屋面梁一起检查。只把端墙开口做大而内部仍被柱堵住，不算适配设备。模型中的装饰管线、烟囱和齿轮若没有对应实际功能，应明确作为外观设计；不能声称它们已通电、传动或排烟。

变化选择：主厅与低侧仓、纵向高窗与局部天窗、端部高塔意向或偏置装卸雨棚。一次挑一两项，材料和柱网保持统一。用户更关注生存效率时，优先保证完整壳体、出入口和检修空间，再添加装饰。
