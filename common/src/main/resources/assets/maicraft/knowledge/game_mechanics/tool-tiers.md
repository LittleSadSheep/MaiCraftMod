# 工具等级与挖掘资格

方块有最低工具等级要求；等级不够时**挖不动或挖了不掉落**。

## 规则（镐系常撞项）

| 目标 | 最低镐等级 |
| --- | --- |
| 煤矿 minecraft:coal_ore、圆石、石头 | 木镐 wooden_pickaxe |
| 铜矿 minecraft:copper_ore、铁矿 minecraft:iron_ore | 石镐 stone_pickaxe |
| 金矿、红石矿、钻石矿 minecraft:diamond_ore、绿宝石矿 | 铁镐 iron_pickaxe |
| 黑曜石 minecraft:obsidian | 钻石镐 diamond_pickaxe |

铁砧、熔炉等含金属部件的方块同样需要石镐以上；斧/锹各有自己的资格表，逻辑相同。

## 这个规则会怎么坑你

- 等级不够时目标要么挖掘进度极慢（黑曜石几乎挖不动），要么破坏了也不掉落——"挖了没掉"先对照此表，再怀疑掉率或拾取。
- WRONG_TOOL 类失败的处理顺序：对照此表 → 确认背包里有没有合格工具（回执 inventory 可查）→ 先装备再挖。
- 采集深部矿物前先核对等级，否则 travel 清障下去后仍是空手。

## 边界

资格以当前持有工具的实际物品组件为准；本页不覆盖附魔（效率/时运/精准采集）对结果的影响。
