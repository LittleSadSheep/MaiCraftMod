按图纸在 target 处施工：先对图核一遍，缺什么料自己去拿，挡着的挖掉，从底往上一层层砌，够不着就垫临时方块、用完收走，最后对图检查并把门关好。材料不够就建到材料尽头如实停下，再下达一次同样的目标会从没建完的地方接着做。玩家的东西不动，碰到会问你。蓝图没写的格子不会被清空；要清空的格子写 minecraft:air。只放一个方块给 block 就行。

## 怎么用

- `design_id`、`cells`、`block`、`file` 四选一。`cells` 是逐格清单：`[{"offset":[x,y,z],"block":"minecraft:stone","properties":{"facing":"north"}}]`，offset 相对锚点；水源、岩浆源表示倒桶。`block` 配 `properties` 只放一格。`file` 还没接上。
- `target` 必填，是锚点：设计原点或 offset [0,0,0] 落在这里。`here` 是脚下；坐标省略 y 时取那一列的地表；记得的地点按名字；观察编号查它在哪。
- `rotation` 绕锚点转 0 / 90 / 180 / 270 度，只配 `design_id`。
- 许可里的 `change_blocks` 决定能拆什么（默认 `natural`）；`none` 时只放不拆，`temporary` 时只能垫临时方块。

## 结果怎么读

| 字段 | 含义 |
| --- | --- |
| `anchor`、`bounds_min`、`bounds_max` | 锚点与包围盒 |
| `cells` | 每种结局几格：`matches` / `placed` / `cleared` / `poured` / `wrong_block` / `wrong_state` / `missing` / `unknown` / `impossible` / `protected` / `checked_by_machine` |
| `problems[]` | 没做成的格按原因分组：原因、几格、全部坐标 |
| `materials_used` | 用掉的材料（物品 → 件数） |
| `temporary_blocks_left[]` | 没收回的临时方块，下一次施工接着收 |

`changes` 记放下、挖掉、倒桶与开关门、用掉的材料；`remaining` 写"还有 N 格没砌"；全部对了是完成，否则部分完成带问题。

## 问题种类

- `NEED_APPROVAL`：有格是玩家的东西或在玩家的地盘里，没动；一次列出全部。确实要拆改，把 `change_blocks` 设为 `any` 再下达一次。
- `NEED_ITEM`：材料用完了，写明还缺什么；拿到后再下达一次同样的目标。
- `UNREACHABLE`：有格够不着，也找不到能垫临时方块接过去的路，或身上的建材不够垫。
- `STUCK`：有临时方块没收回（下面落不稳、走不到）；下一次施工接着收。
- `UNSUPPORTED`：有格放好了但点名的属性对不上，施工调不了；或给了 `file`。
- `INVALID_PARAMETER`：四选一没给对、`cells` 写错（带下标）、`rotation` 不是四个取值之一、`target` 给不了。
