# 怎么设计一栋房子

这一页讲方法：从要求到一张能过校验、能住人的图纸。图纸怎么写看 `lookup(topic=knowledge, id=building/design)`，屋顶看 `lookup(topic=knowledge, id=building/roofs)`，风格看 `lookup(topic=knowledge, id=building/styles)`，完整例子看 `lookup(topic=knowledge, id=building/example-cottage)`。这里的尺寸都是方块尺度的建议，不是现实的建筑规范。

## 先定纲要，再画

把要求收成一段短纲要：用途与人数、能用的地块、入口朝哪、几层与净高、主次体量、几种材料各演什么角色、一个记忆点。地块和尺寸不清楚时写明哪些是"已知"、哪些是"假设"。按这个顺序做决定，每一步只做到够决定下一步：

1. 空间与通行：哪些格要能走、门在哪、楼梯在哪。
2. 主要体量：主屋多大、附翼多大、谁高谁低。
3. 屋顶轮廓：脊朝哪、几种屋顶。
4. 门窗节奏：窗间多宽、入口怎么醒目。
5. 材料层次：主体、框架、基座、屋面、透明面、点缀。
6. 少量细节。

画好用 `design(operation=create)` 校验，看返回的格数、材料用量、包围盒和叠加冲突；用 `design(operation=inspect)` 查某个对象落在哪；要改就 `design(operation=update)` 按名改，不要整张重写。`preview` 投到世界里给观众看；真正盖由 `build` 做，图纸原点落在 `build` 的 `target`。

## 尺寸怎么算

- 外宽 = 两侧墙厚 + 室内净宽；层高 = 室内净高 + 楼板厚。外宽 7、墙厚 1 的房间净宽只有 5。
- 普通室内净高 3–4 格，主要通道净宽 2 格，门洞至少 1 宽 2 高（上面再留 1 格更好走），主入口比次入口醒目。
- 想占 `[a, b)` 这段格：中心 `(a + b) / 2`，边长 `b - a`。奇数边长的轴中心写 `.5`，偶数写整数。
- 房间内部要写一个 `minecraft:air` 材料的对象：图纸没画到的格施工时不清空，地形、旧建筑会留在屋里。
- 多层先留楼梯井和上层洞口再铺楼板；楼梯每级给明确位置，楼梯方块写 `facing` 与 `half`，半砖写 `type`，门分上下两格各写一个对象（`half: lower` / `upper`）。
- 两栋相接、屋顶相交的地方算清谁盖谁：后写的对象赢。

## 三种尺度都要成立

- 远看：主屋、附翼、屋顶能看出轮廓；一个主导体量，次体量明显小。
- 中看：入口、窗组、柱网有节奏；以一个窗间宽度为模数，在入口或转角有理由地打破。正面、侧面、背面都要处理。
- 近看：窗有窗台或凹入，檐有投影，柱脚接地面；厚度够的地方用一格进退做阴影。小房子宁可删装饰，别让一圈外框吃掉室内。

同一种风格要做出不同的房子，从这几条变化轴里选两三项，其余保持一致：平面（紧凑矩形、L 形、前后院、分栋相连）、轮廓（低宽、窄高、主楼加低翼、退台）、屋顶（单坡、双坡、错高双坡、四坡）、入口（正中门廊、转角凹口、侧院进入）、开窗（均匀窗间、大厅高窗、转角窗）、记忆点（烟囱、塔窗、挑廊、院树、天窗，只选一项）。

## 材料分角色

| 角色 | 可试的原版方块 | 要盯住的关系 |
| --- | --- | --- |
| 明亮填充 | `white_terracotta`、`smooth_sandstone`、`white_concrete` | 和框架保持明度差，三者色温不同，选一种为主 |
| 暖色框架 | `spruce_log`、`dark_oak_log`、`stripped_spruce_log` | 原木有纹理方向：柱写 `axis: y`，梁按走向写 `x` 或 `z` |
| 厚重基座 | `stone_bricks`、`cobbled_deepslate`、`andesite` | 比上部视觉重，别盖住门窗 |
| 深色屋面 | `deepslate_tiles`、`dark_oak_planks` | 和主体分开；ROOF 会自动用它们的半砖 |
| 工业填充 | `bricks`、`stone`、`gray_concrete` | 大片粗糙墙面配清楚的框架或窗组 |

主体约六成、框架约三成、点缀约一成，这是构图提示不是要写进图纸的数字。纹理要有位置依据：基座一条石带、转角加固、窗下一行点缀；满墙棋盘格和逐格随机通常淹没构图。`mix` 材料适合石墙、碎石地这类本来就杂的面，不适合窗框。

## 七格见方的房间骨架

地板、四面墙、朝 -z 的门洞、明确的室内空区；没有屋顶、门扇和窗，是接着往上画的底子。

```json
{
  "name": "房间骨架",
  "coordinate_system": "minecraft_y_up",
  "materials": {
    "Floor": {"block_id": "minecraft:stone_bricks"},
    "Wall": {"block_id": "minecraft:oak_planks"},
    "Clear": {"block_id": "minecraft:air"}
  },
  "objects": [
    {"name": "Floor", "type": "MESH", "primitive": "cube", "location": [3.5, 0.5, 3.5], "dimensions": [7, 1, 7], "material": "Floor"},
    {"name": "Walls", "type": "MESH", "primitive": "cube", "location": [3.5, 3, 3.5], "dimensions": [7, 4, 7], "material": "Wall", "fill": "hollow", "open_faces": ["top", "bottom"],
     "modifiers": [{"type": "BOOLEAN", "operation": "DIFFERENCE", "object": "EntryCut"}]},
    {"name": "EntryCut", "type": "MESH", "primitive": "cube", "location": [3.5, 2.5, 0.5], "dimensions": [1, 3, 3]},
    {"name": "Interior", "type": "MESH", "primitive": "cube", "location": [3.5, 3, 3.5], "dimensions": [5, 4, 5], "material": "Clear"}
  ]
}
```

墙是一个空心盒子开了上下两面，比四面墙各写一个对象省事；门洞的切割体只切墙，不切地板。加窗就是再加切割体和一块玻璃板；加屋顶在 y=5 放一个 ROOF；加一层先把楼梯和楼板洞口画出来。

## 交付前检查

1. 对照要求：用途、范围、层数、风格、入口都真的写进几何了吗。
2. 对照空间：门洞连通内外，楼梯连通楼层且有净空，屋顶和梁没有填满房间，装饰没堵门、挡窗。
3. 对照表现：远看有主次，中看有节奏，近看有进深。
4. 对照结果：`create` 返回的材料用量是不是拿得到，叠加冲突是不是有意的；格数太多先减体量，不是减墙厚。

校验通过只说明图纸表达得对，不说明好看、能走、材料够；盖好后的差异由 `build` 的结果说。
