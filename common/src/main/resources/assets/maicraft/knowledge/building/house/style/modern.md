# 现代住宅与退台庭院

目标：用明确体块、实体与开口的比例、退台和阴影组织空间。白色方盒子加整墙玻璃只是材料组合，仍然需要入口、房间和体量主次。

## 从用途选体量

- 紧凑住宅：一个主要体块配较低入口或车棚，公共房间开大窗，私密空间减少开口。
- 庭院住宅：L 形或 U 形围绕可使用的院子，主要玻璃面朝内，外侧保留实体墙面。
- 坡地或退台意向：高低体块逐层退让形成露台；只有现场高差已知时才声称顺应地形，否则按平地假设设计台基。

主材可选 white_concrete、smooth_quartz 或 light_gray_concrete 中的一种，搭配少量 spruce_planks、stone 或 gray_concrete。玻璃也算一个视觉角色；不要把楼板边、柱和框全做成透明材质，使楼层关系消失。生存材料受限时可以换相近色材料，但先核对实际可得性。

水平檐线宜连续，窗可凹一格或两格，用遮阳框形成深度。悬挑应有明确起止，较大的悬挑可用侧墙或柱建立视觉支撑；Minecraft 不按现实结构计算坍塌，也不能因此宣称已经做过结构安全设计。室内净高从顶板下算，低矮遮阳不要压到入口。

## 深窗与遮阳框

这只是一个面向庭院的立面模块。两格厚墙中开五格宽窗洞，玻璃位于后排；顶面向前伸出，两侧有支柱，前平台连接地面。入口需要另外设计，不能把玻璃窗当门。

```json
{
  "schema_version":2, "name":"ModernShadedWindow", "coordinate_system":"minecraft_y_up",
  "materials":{
    "Shell":{"block_id":"minecraft:white_concrete"},
    "Deck":{"block_id":"minecraft:spruce_planks"},
    "Glass":{"block_id":"minecraft:light_gray_stained_glass"}
  },
  "objects":[
    {"name":"Deck","type":"MESH","primitive":"panel","location":[4.5,-0.5,0.5],"dimensions":[9,1,5],"material":"Deck"},
    {"name":"Wall","type":"MESH","primitive":"cube","location":[4.5,3,1],"dimensions":[9,6,2],"material":"Shell","modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"WindowCut"}]},
    {"name":"WindowCut","type":"MESH","primitive":"cube","location":[4.5,3,1],"dimensions":[5,4,4]},
    {"name":"Glass","type":"MESH","primitive":"panel","location":[4.5,3,1.5],"dimensions":[5,4,1],"material":"Glass"},
    {"name":"Canopy","type":"MESH","primitive":"panel","location":[4.5,6.5,0.5],"dimensions":[9,1,5],"material":"Shell"},
    {"name":"LeftSupport","type":"MESH","primitive":"cube","location":[0.5,3,-1.5],"dimensions":[1,6,1],"material":"Shell"},
    {"name":"RightSupport","type":"MESH","primitive":"cube","location":[8.5,3,-1.5],"dimensions":[1,6,1],"material":"Shell"}
  ]
}
```

变体可以把宽窗移向一侧形成实墙端头，将入口凹入另一侧；也可将次体量降低一层，为主房保留完整檐线。不要给每个体块都加同样的突出框线，也不要在极小体量上堆许多互相切穿的盒子。

交付前检查：入口能从外部识别、玻璃对应真实房间、露台能到达、上层楼板没有穿过窗、退台与柱的位置一起调整。外观预览没有自动验证室内采光、玩家通行或现实结构性能。
