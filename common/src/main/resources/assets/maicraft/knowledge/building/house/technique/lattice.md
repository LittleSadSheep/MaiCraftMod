# 镂空格栅与交替半砖

适用：花窗、屏风、通风墙、遮阳面和网状装饰。先定孔隙用途：需要通行的开口应单独留出，装饰网格不能自动充当门；视觉通透也不代表箭矢、玩家或设备能够通过。

## 二进制图案怎样变成网格

`panel.pattern` 把小图案重复到一格厚的平面。`axes` 第一项沿列、第二项沿行，第一字符从局部最小角开始，行沿正轴增长。`["x","y"]` 用于竖直立面，`["x","z"]` 用于水平面；厚度是剩余轴。旋转或镜像作用于整个面板，不会重排输入字符串。

每个字符是一整个方块格，不是像素贴图。默认 `1` 使用面板材质，`0` 是空气孔洞。用 `111 / 100 / 100` 可以形成一格线条、两格孔宽的格网；`101` 单行是竖条重复；`01 / 10` 是交错孔。尺寸不是周期整数倍时边缘会留下部分周期，所以先算窗框内净尺寸，或者有意用边框遮住余量。

下面是独立屏风，八格宽、六格高的图案区外加一格边框。`rows` 的顺序从低 Y 往高 Y，不能把第一行当作屏幕上方。

```json
{
  "schema_version":2, "name":"LatticeScreen", "coordinate_system":"minecraft_y_up",
  "materials":{"Frame":{"block_id":"minecraft:dark_oak_planks"}},
  "objects":[
    {"name":"Screen","type":"MESH","primitive":"panel","location":[5,4,0.5],"dimensions":[8,6,1],"material":"Frame","pattern":{"axes":["x","y"],"rows":["111","100","100"]}},
    {"name":"LeftFrame","type":"MESH","primitive":"panel","location":[0.5,4,0.5],"dimensions":[1,8,1],"material":"Frame"},
    {"name":"RightFrame","type":"MESH","primitive":"panel","location":[9.5,4,0.5],"dimensions":[1,8,1],"material":"Frame"},
    {"name":"BottomFrame","type":"MESH","primitive":"panel","location":[5,0.5,0.5],"dimensions":[8,1,1],"material":"Frame"},
    {"name":"TopFrame","type":"MESH","primitive":"panel","location":[5,7.5,0.5],"dimensions":[8,1,1],"material":"Frame"}
  ]
}
```

## 每格都有方块的半砖编织

给 `0` 也映射材质，零格便不再是整格空气。下面每格都有 stone_slab，上下半砖交错形成错位孔隙；相邻两行错位时局部空隙可达到一格高，不能一律理解为半格细缝。单行 `rows:["01"]` 重复更接近逐层半格缝，交错行则产生更大的孔隙与连接变化。不要把这份模型旋转成游戏不支持的竖直半砖状态。

```json
{
  "schema_version":2, "name":"AlternatingSlabs", "coordinate_system":"minecraft_y_up",
  "materials":{
    "Upper":{"block_id":"minecraft:stone_slab","properties":{"type":"top"}},
    "Lower":{"block_id":"minecraft:stone_slab","properties":{"type":"bottom"}}
  },
  "objects":[
    {"name":"WovenPanel","type":"MESH","primitive":"panel","location":[2,2,0.5],"dimensions":[4,4,1],"material":"Upper","pattern":{"axes":["x","y"],"rows":["01","10"],"materials":{"0":"Lower","1":"Upper"}}}
  ]
}
```

## 组合与变体

格栅需要墙上真实开孔。图案的零格、Boolean 开孔和空心内腔只影响本对象，不会擦掉另一块完整实墙；把屏风贴到实墙上仍然没有通风孔。窗框、玻璃和格栅可以是独立对象，但各自的层次应明确，例如外框 `z=0`、格栅 `z=1`、玻璃 `z=2`。不需要玻璃就不要为了“完整”默认补上。

圆形花窗可以用墙体的圆柱切割限定外轮廓，但圆柱本身不接受 pattern。此时在 objects 数组中先写矩形格栅、后写开好圆孔的墙；二者必须占据同一方块层，或墙的厚度覆盖格栅所在层，才能用 `last_wins` 让墙覆盖圆外格栅。“数组中在前”不表示空间上摆到墙前另一个 Z 层。检查最终孔内、孔外的格子，切割方向和能力需按当前 Schema 核对，不能把图案面板作为切割器。

变化来自孔宽、实线粗细、边框留白、局部密度与材质角色。整面立面不必全用同一网格：主窗通透、下部防护更密、入口留空，通常比完全均匀更有用途依据。图案编辑整体替换 pattern，必须同时写回 axes、rows 和要保留的材料映射。
