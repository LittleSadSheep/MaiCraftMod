# 房间、入口与通行

适用：住宅、店铺、工坊、多层建筑。先确定角色要走的空区，再围出墙体；通道不是建完后随便挖出的缝。

## 从功能反推平面

把房间写成邻接关系：外部道路 → 入口 → 公共空间 → 工作或生活空间；卧室、仓库等可以侧向分支。主要通道不必穿过设备或家具。院落是需要保留的空区，应先放进平面，再安排侧翼。

画出可行尺寸后再换算模型：外宽 = 两侧墙厚 + 室内净宽；层高 = 室内净高 + 楼板厚度。一个外宽 7 格、墙厚 1 格的房间，净宽只有 5 格。门洞至少留出角色的站位高度，主入口和常用通道宜更宽；门槛、柱脚和地毯等也会改变实际可走空间。

多层时先保留楼梯井、上层洞口和平台，再补楼板。直跑楼梯每升一格需要相应水平前进，折返时另留平台；净空沿每级站位检查，不能只看起点和终点。楼梯方块用 `half:top/bottom` 和 `facing`，半砖用 `type:top/bottom`，门用 `half:lower/upper` 且上下两格分别声明。属性值必须为字符串，实际支持范围以 Mod 对该方块的校验为准，通用 Schema 不枚举全部原生方块属性。

阵列的 step 是各轴的间距：`count:[4,1,1],step:[1,1,0]` 不会逐级升高，因为 Y 轴没有复制。楼梯各级应给出明确位置，或使用经过核对的组合；不用斜放的实心长盒子冒充可走楼梯。

## 7×7 单层房间骨架

下面是地板、四面墙、朝 `-Z` 的入口和明确的室内空气区。地板占 `y=0`，室内地面上方空区为 `y=1..4`，门洞占 `x=3,y=1..3,z=0`。它没有屋顶、门扇和窗；需要按最终委托继续设计，不能把骨架当完工住宅。

```json
{
  "schema_version": 2, "name": "RoomSkeleton", "coordinate_system": "minecraft_y_up",
  "materials": {
    "Floor": {"block_id": "minecraft:stone_bricks"},
    "Wall": {"block_id": "minecraft:oak_planks"},
    "Clear": {"block_id": "minecraft:air"}
  },
  "objects": [
    {"name":"Floor","type":"MESH","primitive":"cube","location":[3.5,0.5,3.5],"dimensions":[7,1,7],"material":"Floor"},
    {"name":"Front","type":"MESH","primitive":"cube","location":[3.5,3,0.5],"dimensions":[7,4,1],"material":"Wall","modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"EntryCut"}]},
    {"name":"EntryCut","type":"MESH","primitive":"cube","location":[3.5,2.5,0.5],"dimensions":[1,3,3]},
    {"name":"Back","type":"MESH","primitive":"cube","location":[3.5,3,6.5],"dimensions":[7,4,1],"material":"Wall"},
    {"name":"Left","type":"MESH","primitive":"cube","location":[0.5,3,3.5],"dimensions":[1,4,5],"material":"Wall"},
    {"name":"Right","type":"MESH","primitive":"cube","location":[6.5,3,3.5],"dimensions":[1,4,5],"material":"Wall"},
    {"name":"InteriorClear","type":"MESH","primitive":"cube","location":[3.5,3,3.5],"dimensions":[5,4,5],"material":"Clear"}
  ]
}
```

入口切割体只切 Front，不自动切地板、装饰或别的墙。新增门框后重新检查门洞，不要用一条横梁把头顶堵住。模型中的空气目标也计预算，实际清除现场方块由父 Agent 的施工策略决定；设计 Agent 不能自行打开替换权限。

## 修改时保持空间完整

加层时同时修改楼梯、洞口、平台和屋顶高度；扩房时同时修改墙、地板、顶面与开窗节奏。只拉长地板不是扩建完成。具名编辑没有提到的对象会保留，移除旧墙或旧顶应明确点名，已在世界中的旧方块还需施工层处理。

验收时区分三种问题：几何不连通应改设计；现场被地形或旧建筑堵住应回报现场问题；通道几何有空区但没有角色行走证据时只能说“预留了通道”。
