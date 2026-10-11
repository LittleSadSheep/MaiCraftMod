把 target 指的那片区域照亮：先量一遍哪里暗，选好灯位，自己备够火把（没有就合成或去拿），把灯插下去，再量一遍，直到达到验收亮度。radius 不给就是照亮整间屋、整条矿道。农田给 coverage=crops，按作物能生长的亮度（默认 9）验收，灯插在田边或没种东西的地上，不踩庄稼。玩家放的东西不动，碰到会问你。operation=enable / disable / status 开、关、查随行补光：开启后走路经过暗处会顺手把手里的火把插在旁边的墙或地上，不专门跑路、不挖不垫，火把用完只报告不采购。

## 怎么用

- `target` 在 operation=area 时必填：radius 给了它就是圆心（任意写法，y 省略取那一列）；radius 不给它就是连通可行走空间的起点——从那一格走出去连得着的范围，量到没加载的边界会先走过去看看，走不到就量到这里为止。
- `radius` 单位格，1..32；要照刚盖好的房子用 landmark 或 previous 加 radius。
- `item` 给光源物品 ID；不给默认火把。身上捡到的发光方块不会改默认，免得整片基地改成昂贵材料；发射量到不了验收亮度的灯不会用。
- `coverage`：`all` 全部样本达标（默认）；`most` 九成达标；`crops` 只采作物格与耕地上方的格、全部达标，默认亮度按作物能生长的 9 算。
- `minimum_light` 是验收亮度（方块光等级 1..15，默认 8）。operation=enable 时取 1..13（火把相邻格最高 13）。
- operation=enable / disable / status 当场完成，不走路也不放灯；进世界后随行补光默认是开的。

## 结果怎么读

| 字段 | 含义 |
| --- | --- |
| `source` | 实际用的光源物品 |
| `samples` / `lit` | 验收样本总数 / 达标数 |
| `coverage_achieved` | 实测覆盖率；分母是能验收的样本（没加载的不算），结果写明 |
| `passes` / `placed` | 放灯轮数 / 放下的灯数 |
| `unlit[]` | 没达标的格按原因分组：`no_site`（找不到能放的灯位）/ `protected`（受保护插不得）/ `unloaded`（没加载走不到）；每组带坐标，多到放不下给总数与开头一段，`goal(get)` 按原因分页读完 |
| `enabled`、`minimum_light` | 随行补光的开关与阈值（enable / disable / status 的结果细节） |
| `torches_carried`、`torches_offhand` | 身上与副手的火把数 |
| `dark_on_route[]` | 最近实测仍暗的路线格：坐标、亮度、什么时候走过；只报走过并采样过的格 |

开始时就已经够亮的会直接完成，写"开始时已满足"，一支灯都不插。

## 问题种类

- `NEED_ITEM`：弄不到光源，写清要多少、什么发射量；先 obtain 再 light，会从暗格接着插。
- `NEED_APPROVAL`：够得着的灯位都在受保护的方块上，一次列出；确认能动再下达一次，或改用小范围分片。
- `NOT_FOUND`：范围内没有可行走的样本，或查过的范围里找不到能放灯具的格——查了哪里、为什么放不了都写在问题里。
- `TARGET_GONE`：观察编号指向的东西已经不在了。
- `STUCK`：放了灯同一批暗格还是暗，先如实停下。
- `INVALID_PARAMETER`：operation、radius、coverage、minimum_light、item 给得不对，一次报全。
