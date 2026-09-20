# 欧式石造宅邸与门廊

目标：用厚重基座、深窗、入口主次和稳定的屋顶轮廓形成石造建筑感。先明确是住宅、庄园还是防御意向建筑；石头材质并不会自动使方盒子成为城堡。

## 三种组织方法

- 小型石宅：紧凑主屋、陡一些的双坡顶、偏置门廊。立面以少量深窗为重点，不强塞塔楼。
- 庄园意向：入口主轴明确，主厅较高，两侧附翼较低；可以近似对称，但后院和生活翼按用途调整。
- 边堡意向：主楼加一座较高塔体或围合院墙；塔有可进入空间和连接路径，不能只是贴在墙角的实心柱。

主墙可用 stone_bricks 或 smooth_sandstone，基座选较粗糙、较暗的石材；少量 chiseled_stone_bricks 标记入口或檐线。深色屋顶可压住明亮墙面。不要把所有墙面随机混入同等面积的圆石、安山岩、苔石和深板岩；先统一主体，再给局部变化位置依据。

厚墙允许门窗向内退一格，窗组宜有竖向比例；转角、基座、窗台不必全部用一圈粗框。先定柱间与楼层线，再决定哪处值得突出。较大的拱门可以用阶梯内轮廓表达，小门宁可用清楚的平 lintel，不用一堆方块挤出不可走的假拱。

## 阶梯拱入口模块

此例是九格宽的厚墙入口，开口下部五格宽，上部依次收窄为三格和一格；三个切割体都只切 EntryWall。它不包含整座宅邸。中央通道有明确净空，左右窄带用于支撑视觉重量。

```json
{
  "schema_version":2, "name":"SteppedStonePortal", "coordinate_system":"minecraft_y_up",
  "materials":{
    "Wall":{"block_id":"minecraft:stone_bricks"},
    "Trim":{"block_id":"minecraft:chiseled_stone_bricks"},
    "Cap":{"block_id":"minecraft:smooth_stone"}
  },
  "objects":[
    {"name":"EntryWall","type":"MESH","primitive":"cube","location":[4.5,3.5,1],"dimensions":[9,7,2],"material":"Wall","modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"LowerCut"},{"type":"BOOLEAN","operation":"DIFFERENCE","object":"MiddleCut"},{"type":"BOOLEAN","operation":"DIFFERENCE","object":"TopCut"}]},
    {"name":"LowerCut","type":"MESH","primitive":"cube","location":[4.5,2,1],"dimensions":[5,4,4]},
    {"name":"MiddleCut","type":"MESH","primitive":"cube","location":[4.5,4.5,1],"dimensions":[3,1,4]},
    {"name":"TopCut","type":"MESH","primitive":"cube","location":[4.5,5.5,1],"dimensions":[1,1,4]},
    {"name":"LeftPier","type":"MESH","primitive":"cube","location":[0.5,3.5,-0.5],"dimensions":[1,7,1],"material":"Trim"},
    {"name":"RightPier","type":"MESH","primitive":"cube","location":[8.5,3.5,-0.5],"dimensions":[1,7,1],"material":"Trim"},
    {"name":"KeyAccent","type":"MESH","primitive":"cube","location":[4.5,6.5,-0.5],"dimensions":[1,1,1],"material":"Trim"},
    {"name":"Cornice","type":"MESH","primitive":"cube","location":[4.5,7.5,0.5],"dimensions":[11,1,3],"material":"Cap"}
  ]
}
```

拱门轮廓不应在所有窗上等比例复制。住宅可用矩形深窗，主入口保留拱形作为焦点。放大入口时检查墙两侧仍有足够实体，缩小时优先减少层级，不把一格尖顶压到角色头上。

变化选择：轴线入口加侧庭、偏置门楼加长侧翼、低主楼加单塔。保持同一主石色与窗台高度，使不同体量仍像一栋建筑。验收时检查塔与主楼是否连接、深窗有没有被后方实墙堵死、装饰是否遮挡主入口。
