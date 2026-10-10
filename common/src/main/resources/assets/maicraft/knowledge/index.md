# MaiCraft 按需知识索引

这里提供参考资料。先发现相关条目，再读取所需页面；无需把整套知识库加入上下文。

当前收录的内容：

- 游戏机制常识（原版规则，动手前读一条即可建立正确预期；具体怎么让角色去做，看对应能力的说明）：
  - [重力方块与塌落](maicraft://knowledge/game_mechanics/gravity-blocks)：挖掉砾石、沙子的下方会整列塌落，地形和掉落物的位置随之改变。
  - [流体流动与灌满](maicraft://knowledge/game_mechanics/fluid-flow)：挖开的空间会被水或岩浆灌满；倒水自救前先看流向。
  - [掉落物](maicraft://knowledge/game_mechanics/item-drops)：拾取半径约 1 格、会漂走、5 分钟后消失；“方块碎了没进包”的排查顺序。
  - [关键掉率与方差](maicraft://knowledge/game_mechanics/drop-rates)：燧石 10% 掉率是常态，连挖几块不掉不是故障。
  - [工具等级与挖掘资格](maicraft://knowledge/game_mechanics/tool-tiers)：等级不够挖不动或不掉落；黑曜石要钻石镐。
  - [矿物生成高度](maicraft://knowledge/game_mechanics/ore-heights)：各种矿在哪一层最多；采掘只在角色附近找，先到对应高度再要。
  - [食物与饥饿](maicraft://knowledge/game_mechanics/food)：饥饿与回血规则、食物从哪来、耕地保湿。
  - [睡眠与夜晚](maicraft://knowledge/game_mechanics/sleep-night)：黑暗处刷怪、床跳夜与重设重生点、同色羊毛、3 天不睡刷幻翼。
  - [往下挖与地下通行](maicraft://knowledge/game_mechanics/tunneling)：不要垂直往下挖；阶梯式下降和两格高的通道更安全。
  - [世界刻速与失焦](maicraft://knowledge/game_mechanics/tick-rate)：游戏窗口失焦时世界可能停住或变慢；任务变慢先想到它。
  - [照明与刷怪](maicraft://knowledge/game_mechanics/lighting)：方块光照为 0 才刷敌对生物；火把 = 煤或木炭 + 木棍。

- 建筑设计与施工（画图纸交给 `maicraft:design`，盖出来交给 `maicraft:build`；图纸格式以这里为准，别凭印象猜字段）：
  - [建筑图纸格式](maicraft://knowledge/building/design)：`drawing` 的全部字段、坐标与尺寸怎么算、材料表与混色、十二种图元、组件与阵列、开孔、空心、面与棱、图案；每节一个能直接交的例子。
  - [参数化屋顶](maicraft://knowledge/building/roofs)：ROOF 对象的字段与九种 shape（悬山、庑殿、歇山、攒尖、单坡），有多高，怎么配山墙、脊、檐口。
  - [怎么设计一栋房子](maicraft://knowledge/building/house)：从要求到图纸的方法、尺寸怎么算、三种尺度、材料分角色、房间骨架、交付前检查。
  - [几种风格怎么起手](maicraft://knowledge/building/styles)：欧式木构、欧式石造、东亚院落、现代、工业，各带一个局部模块例子。
  - [完整例子：带门廊的木构小屋](maicraft://knowledge/building/example-cottage)：从委托到一张完整图纸，以及怎么改成别的房子。

- 查阅外部百科（Minecraft Wiki、MC 百科）与注册表事实的资料来源随能力层接入逐步登记；这里只列已经登记的来源，不预告尚未接入的内容。
