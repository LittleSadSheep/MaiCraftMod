# 切石：带料走到切石机，整批切完再对账

`maicraft:stonecut` 使用已经放在世界里的原版切石机，把带来的输入物品加工成指定产物。它不挖石头、不造切石机，也不把目标换成另一种产物。这里 `count` 是**投入几份原料、做几次配方**，与烹饪的“背包最终总数”、交易的“再换到几件”都不同。

## 请求与参数

下面是 MCP `plan` 的完整参数。示例使用原版石头和石砖；真正可用的配方要等把输入放进当前服务器的切石菜单后确认。计划本身不耗料，执行返回的真实 `plan_id` 后才会行动。

```json
{
  "goal": {
    "ability": "maicraft:stonecut",
    "outcome": "把随身四份石头切成石砖",
    "target": { "kind": "nearest" },
    "parameters": {
      "item_id": "minecraft:stone",
      "output_item_id": "minecraft:stone_bricks",
      "count": 4
    }
  }
}
```

| 字段位置 | 类型、默认和含义 |
| --- | --- |
| `goal.parameters.item_id` | 必填输入物品 ID 字符串；须在注册表中存在且不是空气。指随身原料，不是产物 |
| `goal.parameters.output_item_id` | 必填产物 ID 字符串；须为已安装非空气物品。有这个物品不等于当前输入一定有对应切石配方 |
| `goal.parameters.count` | 配方次数，整数 1～64，省略为 1；0、负数、分数和越界值拒绝。当前底层数字转换也接受可精确转整数的数字字符串；显式 `null`、布尔值不是有效数量，不等于省略 |
| `goal.target` | 省略或 `null` 时找附近设备；也接受 `nearest`、`coordinates`、`landmark`，详见下表 |

`parameters` 只接受上面三个字段。没有燃料、补料来源或建站选项；`preferences` 不提供切石策略，`children` 只属于 sequence。两种物品必须用 ID，不接受配方索引或 GUI 槽号。

| 地点写法 | 当前怎样解析 |
| --- | --- |
| 不填目标或 `{"kind":"nearest"}` | 从当前位置分刻查询已加载切石机，最终要求所选命中在 16 格球形距离内；不探未加载区块 |
| `target.kind=coordinates` | `target.position` 必填，含整数 `x/y/z`；`dimension` 可省略或为 `null` 表示当前维度，填写时必须匹配当前维度 |
| `target.kind=landmark` | `target.label` 是已记住的地点名；其锚点格必须就是切石机，不是在该地点周围继续找设备 |

位置必须来自实际观察，文档不编造现场坐标或地标名。最近设备模式的附加限定没有参与选站；已知坐标就用坐标目标。当前先取索引最近命中再检查 16 格范围，不会为一个失效目标自动建新站。

最小完整计划默认只切一次；即使背包本来已有石砖，也仍会消耗一次原料：

```json
{
  "goal": {
    "ability": "maicraft:stonecut",
    "outcome": "切制一份石砖",
    "parameters": {
      "item_id": "minecraft:stone",
      "output_item_id": "minecraft:stone_bricks"
    }
  }
}
```

## 玩家眼里的执行顺序

```text
确定已有切石机 → 原生退出挡路界面 → 查原料与成品空间
  → 不改地形走近 → 右键开可见空菜单 → 按请求份数装料
  → 读这个输入的真实配方列表 → 按产物找到配方
  → 请求保存本次操作身份 → 选择配方并等确认
  → 一次快速移动完成整批切制 → 归还余料
  → 核对主背包投入与产出 → 确认关闭菜单
```

装料是精确搬入 `count` 份，不能把一整叠多余原料塞进去。到站前可能拾到同类物品，因此第一次装料前重新记录数量基线。输入配方列表没有及时同步时等 40 游戏刻；确实没有所需配方就交回失败。

菜单对象绑定后，后来换成另一张同类界面也不能沿用旧点击。选择配方被明确确认未应用时，最多重新读两次配方列表；装料或返料只有有“未提交、无效果”的证据时才有限刷新来源格。已经发出取成品点击时不自动补点。

操作身份的持久化目前存在等待漏洞，见下文“暂停、取消、死亡和重启”；上面的流程不能读成已经保证先落盘后消费。

原版快速移动可能一次切完整批，当前实现就是整批取出，再按背包前后数量核对。不能把 `craftsDone` 的内部阶段值当成已经收到的件数；实际净增量在 `crafted_count`。

## 哪个文件负责哪一步

| 玩家步骤 | 代码入口 |
| --- | --- |
| 验证参数、解析地点、找设备 | [StonecutAbilityAdapter.validate / adapt](../../common/src/main/java/org/maiwithu/maicraft/intent/StonecutAbilityAdapter.java)、[StonecuttingParameters.parse](../../common/src/main/java/org/maiwithu/maicraft/core/task/stonecutter/StonecuttingParameters.java) |
| 接入有限原生加工流程 | [MinecraftStonecuttingProcessAdapter](../../common/src/main/java/org/maiwithu/maicraft/core/integration/machine/process/MinecraftStonecuttingProcessAdapter.java)、[MachineProductionIntent](../../common/src/main/java/org/maiwithu/maicraft/intent/MachineProductionIntent.java) |
| 原生退出页面、地面接近、开菜单 | [BlockMenuCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/BlockMenuCompanionTask.java) |
| 判断材料与菜单、组织回执 | [StonecuttingCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/stonecutter/StonecuttingCompanionTask.java) |
| 装料、选配方、整批取出和关菜单 | [StonecutterMenuFlow.advance / tickChild / verifyReturn](../../common/src/main/java/org/maiwithu/maicraft/core/task/stonecutter/StonecutterMenuFlow.java) |
| 投料基线、空间、最终数量核对 | [StonecuttingStock.prepare / loadMoves / verifiedAfterCrafts](../../common/src/main/java/org/maiwithu/maicraft/core/task/stonecutter/StonecuttingStock.java) |
| 防止恢复时再次消费 | [StonecuttingTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/stonecutter/StonecuttingTaskRecord.java)、[NativeSubmissionBinding](../../common/src/main/java/org/maiwithu/maicraft/intent/NativeSubmissionBinding.java) |

## 完成、失败和未知各看什么

完整成功需要切制库存核对、余料归位和原生关闭都完成。找不到站点时报告 `stonecutter_station_unavailable`，未提交加工；菜单中的具体问题用 `issue_code` 表示。

| 字段 | 含义 |
| --- | --- |
| `requested_count` | 请求切制次数，不是成品最后总数 |
| `crafted_count` | 核对时观察到的成品净增量；还没到核对阶段时可能没有该字段 |
| `selected_recipe_output` | 菜单中选定的实际产物 ID |
| `native_consumption_reserved` | 内存中已进入预约流程；不证明磁盘记录已经保存，更不证明已经消费 |
| `item_return_verified`、`gui_closed`、`cleanup_status` | 归位、关闭及收尾事实；“已请求关闭”不等于“已确认关闭” |
| `transfer_results` | 装料或返料子流程的原生结果，含已搬数量和未确认项 |
| `outcome_uncertain`、`mechanical_retry_allowed` | 是否仍有未知、是否允许机械重试；未知取件后不能再点一次试结果 |

公开父任务还会包上步骤结果。沿 `next_attention` 跟随，再用 `task(get)` 读取返回的明细路径；不要从外层接单成功推断切制成功。实际失败通常直接结算，没有 `decision_id` 时不要调用回答入口。

## 暂停、取消、死亡和重启

暂停会转发给当前开门、移动或搬运任务，保留同一菜单流；恢复先检查控制权、设备和菜单对象。取消不再开始新搬运；仅在仍有控制权、工作格归属明确且取件尚未提交时请求原生返还并关闭。取件已提交、鼠标物品或材料来源不明时保留界面与未知事实，不能凭取消推断没有消费。

死亡由公共运行时处理复活授权或待答决定，换世界清理旧身体。重启先恢复暂停的父任务，菜单对象与库存基线不会恢复。同一网络请求重试仍使用原 `request_key`。

通用提交机制的设计是先保存父任务、再写唯一消费记录，旧记录阻止重发。但切石的调用目前没有完整等这道手续：`NativeSubmissionTaskRecord.prepareSubmission` 在保存仍待完成时已经把 `submissionReserved` 置为真；`StonecutterMenuFlow` 下一刻只在这个标记为假时才继续调用保存屏障，所以可能提前选择配方并取件，也可能跳过旧预约的检查。**当前不能保证重启同一步不会重切。** 这属于执行器缺陷，本轮只补说明；恢复前先核对任务、已知材料与现场，不把“内存已预约”当成“可靠落盘”。

## 已知限制与验证入口

当前 `verifiedAfterCrafts` 固定要求原料减少 `count`、成品增加 `count`。**一份原料产两块台阶的合法配方，可能已经真实切好却被这条一比一判断记为未核实。** 空间估算同样只按 `count` 件成品，且没完整比较组件。不要把此类失败解释成原版配方不能用，也不要自动重发；本轮只说明这个差异，没有修改数量算法。

这里只接原版 `StonecutterMenu`。模组添加到该菜单的配方会被读取，但多件产出、组件差异和同输入同输出等特殊路线不因此获得完整验证。

现有 [StonecuttingProcessTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/stonecutter/StonecuttingProcessTest.java) 涉及参数、工序注册、缺料和失站、到站基线、搬运事实以及未应用配方选择的刷新。整批原生点击和服务器同步仍需对应实机验证。本轮只检查源码、文档 JSON 与链接，未运行测试或游戏。
