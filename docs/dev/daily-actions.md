# 日常动作：吃东西、换装备、钓鱼和上床

这四项能力都操纵真实玩家。吃东西要等使用动画，换装备要等物品到位，钓鱼要把战利品拿回来，上床要观察玩家真的躺下。它们没有“把属性直接改好”的捷径。

本页的 JSON 都是传给 MCP `plan` 的完整参数。`plan` 只生成计划；执行返回的真实 `plan_id` 后才行动。接单后跟随 `next_attention`，需要细节时用 `task(get)` 读取回执给出的路径。同一次网络重试沿用 `request_key`。

## 吃一口：maicraft:consume

```json
{
  "goal": {
    "ability": "maicraft:consume",
    "outcome": "吃一份随身面包补充饥饿值",
    "parameters": { "item_id": "minecraft:bread", "allow_effects": false }
  }
}
```

| 字段位置 | 类型、默认和边界 |
| --- | --- |
| `goal.parameters.item_id` | 可选食物 ID 字符串；不填、`null` 或空白时自动选食物。指定时检查当前随身物品与 `FOOD` 组件，不是可使用的任何物品都能吃 |
| `goal.parameters.allow_effects` | 布尔值，默认 `false`；仅表示允许**点名食物**的状态效果。`true` 不会让自动选择开始挑有副作用的食物，不代替 `item_id` |
| `goal.target` | 省略、`null` 或 `{"kind":"current_place"}`；不买食物，不去另一处吃 |

先检查饥饿值：达到 20 时直接提出跳过或取消决定，这一步发生在识别具体食物之前。没点名食物时，只选没有额外状态效果的候选；先比较营养值与当前缺口的距离，再比较饱和度。点名有额外效果的食物而未允许时，交回实际候选与决定；LLM 可以在已授权的游戏任务内按策略设置 `allow_effects`，不额外要求人工审批。

选好后，角色把食物拿到主手，持续原生使用，等动画结束，再比较同类物品总数。物品减少才算吃过；红心与饥饿值变化一起报告。它不保证吃这一口就满饥饿，更不保证立刻满血。药水、牛奶等没有 `FOOD` 的物品用相应的物品使用能力。

`food_count_before`、`food_count_after`、`consumed_count`、`hp`、`hunger` 给出实际观察；`native_use_duration_ticks` 是手中食物的原生使用时长，`elapsed_use_ticks` 是开始使用以来的游戏刻数。当前以同类总量减少作证据，期间拾到同类食物会掩盖消耗，其他原因减少同类食物也可能干扰判断。不能把净变化当成独立的逐口服务端收据。

当前边界：创造模式会被执行器拒绝；满饥饿时即使食物原生允许食用，语义入口仍先拦下；语义选食物看真实堆的组件，执行器前置检查却看该物品默认堆，组件定制食物可能不一致。参数读取也并非全严格：`allow_effects: null` 回到默认值，字符串布尔值可能被接受，错误形状的 `item_id` 可能被当成未提供。请求应使用表里的正式类型。

## 换一件装备：maicraft:equip

把镐拿在手里不会挥镐，把水桶拿在手里不会倒水。自然护甲位置上的装备通过原生使用穿上；明确要求主手时只拿着它。

```json
{
  "goal": {
    "ability": "maicraft:equip",
    "outcome": "把随身铁镐拿到主手",
    "parameters": {
      "action": "equip",
      "item_id": "minecraft:iron_pickaxe",
      "equipment_location": "mainhand"
    }
  }
}
```

| 字段位置 | 类型、默认和边界 |
| --- | --- |
| `goal.parameters.action` | 字符串 `equip` 或 `unequip`，省略、`null` 或空白时为 `equip`；名称转小写，未知动作提出决定 |
| `goal.parameters.item_id` | 穿戴的随身物品 ID。穿戴时通常必填；省略、`null` 或空白时，必须指定部位且只有一种兼容物品 ID 才能自动选。卸下时忽略 |
| `goal.parameters.equipment_location` | 字符串 `mainhand`、`offhand`、`head`、`chest`、`legs`、`feet`、`armor`。穿戴时省略、`null` 或空白表示按物品自然部位；卸下时必填。`armor` 只供卸下四件护甲，不能自动穿一套 |
| `goal.target` | 省略、`null` 或 `current_place`；不是实体目标，没有背包槽号参数 |

兼容候选按物品 ID 去重，没有比较附魔、耐久或名字的优劣。语义层扫描随身物品，真正穿戴的来源只查主背包与快捷栏前 36 格；物品只有一件且已经戴好时，仍可能报“背包没有”。同类多件不能用这套参数精确挑某个组件版本。

穿戴顺序是：找物品 → 检查部位 → 主手选择或副手交换 → 护甲再使用一次 → 观察目标部位。副手通过可见背包交换并等确认，成功后关闭界面。`item` 和 `slot` 表明最终检查的类型与部位，不能由此推断附魔等完整组件完全相符。

下面是卸下全部护甲的完整计划。执行顺序为头、胸、腿、脚，不丢弃装备来腾空间：

```json
{
  "goal": {
    "ability": "maicraft:equip",
    "outcome": "把四件护甲收回背包",
    "parameters": { "action": "unequip", "equipment_location": "armor" }
  }
}
```

卸下主手时优先切到空快捷栏；否则尝试把手持物收回主背包。其他部位当前要求先有一个空格，即使可以并入已有堆叠也可能留下。**现在遍历完请求部位就可能报成功，即使 `still_worn` 还有物品；全部留下时提示还可能写“已经为空”。** 必须一起读 `removed` 和 `still_worn`，不能把成功外壳当成裸装证据。

副手已有完全相同的物品堆时，当前交换确认还要求“和原来不同”，所以相同堆交换可能无法确认。参数读取会把部分错误原始值转换成字符串；错误形状可能走缺省，不是严格类型校验。这些都是当前边界，本轮没有修改穿戴或卸下算法。

## 钓一竿：maicraft:fish

```json
{
  "goal": {
    "ability": "maicraft:fish",
    "outcome": "钓取并收回一竿战利品",
    "parameters": { "count": 1 }
  }
}
```

唯一业务参数是 `goal.parameters.count`：请求确认收获的竿数，约定整数 1～64，公开能力默认 1。省略或 `null` 不是无限钓鱼；0 被夹为 1，超上限夹为 64，小数可能截断、数字字符串可能接受，其他读不出的值回到 1。内部旧 `FishTool` 省略数量会无限钓鱼，但公开适配器总会传数量，不能混为同一契约。

背包先要有原版 `minecraft:fishing_rod`，没有就提出补料、跳过或取消决定；不自动合成鱼竿。目录接受 `current_place`、`area`、`landmark`，但适配器没有传递地点，执行器只从当前位置找近处水面。要去湖边先旅行；命名目标的 `label` 不会改变这里的钓点。

角色按下面的顺序行动：

```text
找干燥站位与水面 → 选主手鱼竿 → 转好视角 → 原生抛竿
  → 看鱼钩和咬钩同步 → 收线 → 等战利品飞回
  → 必要时走近原生拾取 → 确认物品到包 → 记一竿收获
```

当前已站在干燥地面时，只从原地找水平 4～10 格、上下 4 格内的水源表面；没有可抛落点就失败。只有当前站位不干燥时，才在水平 12 格、上下 4 格内寻找其他干地，最多检查 256 个候选。它不会因为原地不合适就沿整条河岸继续探索。轨迹检查只排除方块阻挡，原生钩住实体时仍可能产生拉拽与耐久消耗。

鱼钩 5 秒未落水或一竿 60 秒没有咬钩时可重试，连续 5 次失败停止。收线后先等 20 刻让原生冲量把掉落拉回来，再决定是否追赶；拾取使用原生接触与库存确认。鱼、垃圾和宝藏都算一竿，`count` 不是“获得多少条鱼”。

结果给 `requested`、`caught`、`casts`，失败选址还给 `positioning_observation`，把身体是否在水里与“是否找到抛竿位置”分开。当前还有这些限制：

- 没看见掉落实体时，会用任意背包正增长兜底；这不足以独立证明增量来自钓鱼。
- 失败重抛的收竿与抛竿共用回执，旧的收竿确认可能被误计成新抛竿。
- 找竿包含副手，但主手选择器不接受副手来源；竿只在副手时可能失败。模组自定义鱼竿不在当前类型判断内。
- 默认结果只有次数和选址证据，没有逐竿完整物品清单；不要从 `caught` 猜具体鱼种或宝藏。

## 上床：maicraft:sleep

```json
{
  "goal": {
    "ability": "maicraft:sleep",
    "outcome": "找一张可用的床并躺下",
    "parameters": {}
  }
}
```

没有本能力专属参数。`target` 省略、`null` 或 `current_place`，不接受指定床坐标；需要去别处先旅行。当前语义是**确认躺下**，不是保证天亮或全服其他玩家也入睡。`wait_until_awake` 是内部任务单选项，不是公开参数；自动夜间休息会用它，这条公开能力没有打开它。

先按原版维度规则检查床是否能用；会爆炸的维度不会去找床或放床。再查询附近已加载的床，没查完就等；找到后先走到该床类型附近，再由内部睡眠工具找伸手可及的床。没有现成床但随身有床时，会在水平五格内找两格有支撑的地表位置，原生放床，再走近躺下。当前找放床位置按地表高度，不保证能利用洞穴或室内地板。

白天且没有雷暴时提出等待夜晚、跳过或取消的决定，不在内部反复点床。没有床则提出取床等前置决定。对着床的真实视线确认后只右键一次，观察 `player.isSleeping()`；床被占用、附近危险或服务端拒绝时交回原生失败事实。

结果中的 `entered_sleep` 表示躺下，`wait_until_awake` 表明是否采用等待自然醒模式，`wake_observed` 和 `morning_observed` 只说明各自实际观察。公开上床请求一般不会观察自然醒；原生睡眠可以在任务成功后继续，床上界面也保留。内部等待自然醒模式最多留 200 游戏刻等醒来后的时间/天气同步，不代表公开能力自动等这一段。

## 打断之后会怎样

共同规则是：暂停先松开身体输出，继续时核对现场；取消保留已发生的世界和物品变化。死亡由公共运行时依据已有授权处理自动复活或交付待答决定，能力本身不恢复掉落物。换世界或断线先保留对应世界的检查点，再清理旧身体；重启恢复未完成父目标时先暂停，不恢复旧菜单和原生动作对象。

| 能力 | 普通暂停、取消及重建执行器的差别 |
| --- | --- |
| 吃东西 | 暂停会停止持续使用，内存中保留原动作对象；恢复不保证继续同一段咀嚼。取消时可能已吃掉一口，读数量变化。重建步骤会重新按当前饥饿和库存判断，没有聊天那样的持久消费预约 |
| 换装备 | 暂停保留当前流程，原生交换可能已到服务端；取消做菜单与使用动作收尾，不回滚已穿上的装备。重建会重新查库存，现有“已经穿好仍报缺料”的限制仍在 |
| 钓鱼 | 暂停尝试收竿；已进入拾取阶段保留该竿跟踪，其他阶段重新准备站位。取消不删除地上战利品。重启不恢复每竿内存跟踪，继续会重建请求次数，不能当成接着旧鱼钩钓 |
| 上床 | 取消只结清自己的床点击，不用新点击强行叫醒。已经躺下是原生状态，不因任务结束而撤销；重建后再检查当前床和玩家状态 |

适配器真的提出选择时，用返回的 `decision_id` 和列出的选项回答；原生动作失败通常直接结束当前步骤。若在 sequence 中显式声明继续策略，其行为见[顺序目标](sequences.md)，不能把后续执行当作前项全部成功。

## 贡献者从哪里读

| 玩家步骤 | 文件和方法 |
| --- | --- |
| 选食物、部位、鱼竿和参数 | [GeneralAbilityAdapter.consume / equip / fish](../../common/src/main/java/org/maiwithu/maicraft/intent/GeneralAbilityAdapter.java) |
| 转成进食或穿脱任务 | [InventoryOps.eatItem / equipItem](../../common/src/main/java/org/maiwithu/maicraft/core/tools/InventoryOps.java) |
| 持续进食、确认消耗、停止使用 | [EatCompanionTask.onTick / finish / stop](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/EatCompanionTask.java) |
| 主手、副手、护甲的原生路径 | [EquipCompanionTask.onTick / equipOffhand / verifyEquipped](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/EquipCompanionTask.java) |
| 逐件卸下和保留放不下的装备 | [UnequipCompanionTask.onTick / freeMainHand](../../common/src/main/java/org/maiwithu/maicraft/core/task/inventory/UnequipCompanionTask.java) |
| 钓鱼站位、抛收竿、战利品确认 | [FishCompanionTask.findFishingSetup / aimAndCast / collectCaughtLoot](../../common/src/main/java/org/maiwithu/maicraft/core/task/fish/FishCompanionTask.java) |
| 找床、摆床、白天提出决定 | [AbilityAdapter.sleep / nearbyBedSite](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java) |
| 把可及的床转换成任务 | [SleepOps.plan](../../common/src/main/java/org/maiwithu/maicraft/core/tools/SleepOps.java)、[SleepTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/sleep/SleepTaskRecord.java) |
| 真正躺下、等待自然醒的内部分支 | [SleepCompanionTask.onTick / resultData](../../common/src/main/java/org/maiwithu/maicraft/core/task/sleep/SleepCompanionTask.java) |
| 死亡、世界归属和恢复 | [IntentRuntime](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java)、[任务生命周期](tasks.md) |

现有验证入口包括 [ItemUseTimingTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/ItemUseTimingTest.java)、[EquipRoutingTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/EquipRoutingTest.java)、[FishingBiteTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/FishingBiteTest.java)、[FishingPositionObservationTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/FishingPositionObservationTest.java)、[SleepSafetyTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/SleepSafetyTest.java)。分别覆盖使用时序、装备路由、咬钩、选址事实和危险维度拒绝；不能替代真实服务器的进食、满包卸甲、收获归属或多人睡眠验收。本轮未增加、运行测试，也未启动游戏。
