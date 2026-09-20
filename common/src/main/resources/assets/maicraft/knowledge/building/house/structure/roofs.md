# 坡屋顶、屋檐与山墙

适用：木屋、石宅、门楼和需要坡顶轮廓的建筑。屋顶首先决定轮廓，其次遮盖房间；不能用一整个实心棱柱把阁楼填死。

## 先定截面和脊线

在平面中选脊线方向，让主屋顶覆盖主空间；附翼使用较低的次屋顶或单坡。两组屋顶相交时算清交界体积，避免留下漏空角或互相穿出的檐口。普通小屋可从每水平前进一格、上升一格的阶梯截面开始；缓坡可以延长每级水平段，陡坡则减少跨度或提高脊线。

屋檐在墙外多伸一格就能产生层次；小建筑无需三四圈檐线。对称双坡以中间一列作脊时较容易对齐奇数宽度；偶数宽度可用两格平脊，但要有意识地选择。楼梯和半砖能细化轮廓，不过它们有真实方块方向和占据形状，不能靠任意旋转获得不存在的竖直半砖。

## 可复用的九格宽坡顶

这是屋顶部件，连接外宽 7 格、墙顶边界 `y=5` 的主体。屋顶向四周外挑一格，屋脊沿 Z；`RoofStrip` 是一格深的空截面，复制九次形成屋面。山墙封闭两端，封檐条补齐侧墙与上一级屋面的连接，屋内保留空区。它不包含主体房间。

```json
{
  "schema_version": 2, "name": "GabledRoof", "coordinate_system": "minecraft_y_up", "overlap_policy": "error",
  "materials": {
    "Roof": {"block_id": "minecraft:deepslate_tiles"},
    "Gable": {"block_id": "minecraft:white_terracotta"}
  },
  "components": {
    "RoofStrip": {"objects": [
      {"name":"LeftEave","type":"MESH","primitive":"cube","location":[0.5,0.5,0.5],"dimensions":[1,1,1],"material":"Roof"},
      {"name":"LeftLow","type":"MESH","primitive":"cube","location":[1.5,1.5,0.5],"dimensions":[1,1,1],"material":"Roof"},
      {"name":"LeftMid","type":"MESH","primitive":"cube","location":[2.5,2.5,0.5],"dimensions":[1,1,1],"material":"Roof"},
      {"name":"LeftHigh","type":"MESH","primitive":"cube","location":[3.5,3.5,0.5],"dimensions":[1,1,1],"material":"Roof"},
      {"name":"Ridge","type":"MESH","primitive":"cube","location":[4.5,4.5,0.5],"dimensions":[1,1,1],"material":"Roof"},
      {"name":"RightHigh","type":"MESH","primitive":"cube","location":[5.5,3.5,0.5],"dimensions":[1,1,1],"material":"Roof"},
      {"name":"RightMid","type":"MESH","primitive":"cube","location":[6.5,2.5,0.5],"dimensions":[1,1,1],"material":"Roof"},
      {"name":"RightLow","type":"MESH","primitive":"cube","location":[7.5,1.5,0.5],"dimensions":[1,1,1],"material":"Roof"},
      {"name":"RightEave","type":"MESH","primitive":"cube","location":[8.5,0.5,0.5],"dimensions":[1,1,1],"material":"Roof"}
    ]},
    "GableEnd": {"objects": [
      {"name":"Base","type":"MESH","primitive":"cube","location":[3.5,0.5,0.5],"dimensions":[7,1,1],"material":"Gable"},
      {"name":"Middle","type":"MESH","primitive":"cube","location":[3.5,1.5,0.5],"dimensions":[5,1,1],"material":"Gable"},
      {"name":"Upper","type":"MESH","primitive":"cube","location":[3.5,2.5,0.5],"dimensions":[3,1,1],"material":"Gable"},
      {"name":"Tip","type":"MESH","primitive":"cube","location":[3.5,3.5,0.5],"dimensions":[1,1,1],"material":"Gable"}
    ]}
  },
  "objects": [
    {"name":"Roof","type":"INSTANCE","component":"RoofStrip","location":[-1,5,-1],"array":{"count":[1,1,9],"step":[0,0,1]}},
    {"name":"Gables","type":"INSTANCE","component":"GableEnd","location":[0,5,0],"array":{"count":[1,1,2],"step":[0,0,6]}},
    {"name":"EaveClosures","type":"MESH","primitive":"cube","location":[0.5,5.5,3.5],"dimensions":[1,1,5],"material":"Gable","array":{"count":[2,1,1],"step":[6,0,0]}}
  ]
}
```

组件内部这些单格对象表达有限的截面折点，深度由阵列复用；不要把整个屋顶展开成单格清单。延长房子时改 Roof 阵列深度和后山墙位置；加宽时必须重算截面和山墙，不能给 INSTANCE 填 dimensions。旋转脊线时同时转山墙、入口关系和局部材质方向。

屋顶内部没有声明的坐标依赖现场已有空区；示例并不清除阁楼里的旧方块。需要清理时另行明确空气范围，并由父 Agent 核实现场和施工权限。

## 让变化有理由

同一木屋可以用长脊覆盖工作区，或用主脊加较低附翼区分居住区；朝景观的一侧可以有一组老虎窗，狭小屋面不必每格都插窗。烟囱落在壁炉或厨房附近，并避免堵塞室内通道。平顶也要画出边缘收口与通往露台的路径。

检查屋面是否覆盖墙外轮廓、两端是否需要山墙、檐下是否碰窗、阁楼是否被填满。JSON 编译能检出表达错误，不能替你确认这些设计选择。
