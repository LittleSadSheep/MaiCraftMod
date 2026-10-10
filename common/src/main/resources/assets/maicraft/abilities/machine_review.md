审阅一份机器蓝图：能不能建、声明的工序这台机器做不做得了、动力连不连得上、要哪些材料、身上缺多少。只读分析，不下"通过 / 不通过"的结论——报了问题也照建，审阅只是把看不出来的事算出来。

## 怎么用

- `blueprint` 与 `design_id` 二选一。`blueprint` 是机器蓝图正文：`cells` 逐格清单（offset、block、可选 properties；minecraft:air 是清空，水源与岩浆源是倒桶）、`parts` 部件（宿主 offset、side 六向之一、item）、`installations` 安装段（kind 加 offsets）、`settings` 装好后的设置（offset、key、value）、`processes` 声明的工序（offset 加要做的产物，只给审阅用）。写错的地方一次全报出来。
- 安装段与部件的形状规则由认领它们的模组自报；没装对应联动时如实说认不出。

## 结果怎么读

- `issues[]`：发现，每条带位置、问题与建议；没有就是这一眼没看出问题，不保证建出来一定能转。
- `materials[]`：每种材料要几件、身上有几件、缺几件；安装段按格数估。
- `power`、`channels`：动力与频道的估计；数值要装了对应模组的联动才读得到，读不到就写估不出。

## 问题种类

- `INVALID_PARAMETER`：blueprint 与 design_id 的给法不对，或蓝图正文写错了，错误带定位。
- `UNSUPPORTED`：design_id 现在引用不了。
