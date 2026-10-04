# 交易：先看真实报价，再花背包里的东西

`maicraft:trade` 要让主背包里的某种物品达到最终数量。例如已经有 2 个面包，要求 `count: 8`，缺的是 6 个；不是再买 8 次。主背包含快捷栏，不包含副手和装备。成品、付款和报价都按游戏里实际同步的数据处理，不生成物品，不给商人刷报价。

## 一份完整请求

这是 MCP `plan` 的参数。示例物品是原版面包，但不保证当前商人正出售面包；执行时看真实报价。`plan` 不开始购买，执行它返回的真实 `plan_id` 后再跟随 `next_attention`。

```json
{
  "goal": {
    "ability": "maicraft:trade",
    "outcome": "用绿宝石把面包补到八个",
    "parameters": {
      "item_id": "minecraft:bread",
      "count": 8,
      "merchant_kind": "villager",
      "allowed_payment_items": ["minecraft:emerald"],
      "protected_labels": [],
      "radius": 32
    }
  }
}
```

所有购买参数放在 `goal.parameters`，不能提供实体 UUID、报价下标、菜单槽号或点击脚本。

| 字段 | 类型、默认和实际含义 |
| --- | --- |
| `item_id` | 目标成品 ID 字符串；正常请求应显式填写，执行时须为已安装的非空气物品。缺少时适配器提出决定；旧实现还会把 `target.label` 当成物品 ID，不能依赖它表示交易地点 |
| `count` | 主背包最终数量，约定整数 1～256，默认 1。当前适配器对省略、`null` 或非原始 JSON 值用默认数，对数字先转整数再夹到边界；因此 0 变成 1，257 变成 256，小数可能截断，数字字符串也可能接受。这是现有宽松解析，不是严格拒绝 |
| `merchant_kind` | 字符串 `auto`、`villager`、`wandering_trader`，默认 `auto`；省略、`null`、空白或非字符串目前也落到 `auto`。兼容 `any`、`trader`、`wandering-trader`，大小写和首尾空白会整理 |
| `allowed_payment_items` | 允许花费的物品 ID 数组。省略、`null`、`[]` 都是**仅允许绿宝石**，不是任意付款，也不是禁止交易。显式清单要求报价中两种付款物品都在名单内；不合法或未知物品拒绝 |
| `protected_labels` | 已记住的地点名数组；省略或 `[]` 不新增地点保护。公开目标拒绝 `null`、空白或非字符串成员；未知地标在执行时失败。商人有自定义名字时始终排除 |
| `radius` | 以角色当前包围盒向外扩张的已加载实体搜索范围，单位格；整数 1～64，省略或 `null` 为 32。0、小数、数字字符串、布尔值和越界值由内部严格读取器拒绝。范围是包围盒，不是严格球形距离 |

公开目录接受 `nearest`、`area`、`landmark`、`prior_result`，但**当前适配器没有把地点传给交易任务**；交易始终从角色当前位置找商人。要到别处交易，先用 `maicraft:travel`。`preferences` 没有交易专属选项，不要把付款策略放在那里。

下例省略付款名单，仍然只允许绿宝石；它也不会自动开采或购买绿宝石：

```json
{
  "goal": {
    "ability": "maicraft:trade",
    "outcome": "尝试补足一个面包",
    "parameters": { "item_id": "minecraft:bread" }
  }
}
```

## 角色会怎样做

```text
看背包是否已经够数 → 原生退出挡路界面 → 找已加载商人
  → 走近并空手打开交易菜单 → 检查真实报价
  → 准备一笔付款 → 原生搬入付款格 → 取出结果
  → 看主背包数量确实增加 → 够数就退余款、关菜单
```

只考虑成年村民和流浪商人；无业村民、傻子和有自定义名字的商人跳过。保护地标按同维度锚点周围水平 12 格判断，不看高度差。默认付款政策下，主背包没有绿宝石会在开商人菜单前失败；显式其他付款名单则读取报价后判断。

候选按距离尝试。打开后必须看到商人菜单；开窗等待窗口是 80 游戏刻。付款前检查报价的库存、允许的两种付款、全部缺额需要的货币以及背包空间。物品组件不同不能混作同一付款堆，付款的两格也不能重复占用同一份库存。

一笔取结果之后，当前实现以主背包目标物品总数增长记录 `completed_trades`，继续下一笔前再检查菜单和报价。数量够时收回剩余付款，关菜单；并不等待商人补货、升级职业或解锁新交易。

## 查哪段代码

| 玩家步骤 | 文件和方法 |
| --- | --- |
| 读公开目标，生成内部交易请求 | [AbilityAdapter.trade](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java) |
| 读默认值、建立任务单 | [SemanticTradeTool.onGameCall](../../common/src/main/java/org/maiwithu/maicraft/core/task/trade/SemanticTradeTool.java)、[SemanticTradeTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/trade/SemanticTradeTaskRecord.java) |
| 选商人、排除保护范围 | [SemanticTradeCompanionTask.survey / protectionReasons / openNext](../../common/src/main/java/org/maiwithu/maicraft/core/task/trade/SemanticTradeCompanionTask.java) |
| 选报价和安排付款 | 同文件 `selectOffer / paymentPlan / affordableForTrades / pay` |
| 原生走近并空手开交易界面 | [InteractEntityCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/interact/InteractEntityCompanionTask.java) |
| 搬付款、取货、退余款 | [ContainerTransferCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/container/ContainerTransferCompanionTask.java)，交易父流程的 `take / tickChild / cleanupMenu` |
| 结算数量、失败与未知 | `SemanticTradeCompanionTask.resultData / exhausted / cleanup` |

## 结果与打断

`required_final_count`、`initial_count`、`observed_final_count` 和 `goal_satisfied` 是数量账；`completed_trades` 是当前流程确认的收货轮数，不能据此倒推出精确花费。`selected_payment` 和 `selected_output_count_per_trade` 描述最后选用的报价，并不是完整累计消费明细。

`observed_offer_outcomes` 汇总试过的报价问题，`observed_payment_item_candidates` 列出观察到的付款种类。缺钱、没空间、无货、付款政策拒绝和没有报价分别说明，不统一称“找不到商人”。`outcome_uncertain` 表示尚未确认的付款、取货或关闭；有未知项就先读回执和现场，不能直接再买一次。

普通暂停释放身体输出，保留当前任务内的商人、报价和阶段；菜单或商人可能在暂停期间变化。取消停止子任务并尝试原生关闭菜单，已完成交易不会退款。死亡走公共复活授权或待答决定，旧身体不继续点击；换世界先保留原世界检查点。重启恢复只保留父目标和已完成步骤，不恢复商人菜单、当前报价或每笔付款的内存状态。未完成记录先暂停，继续时重建当前语义步骤；先核对已有产物和未结付款。

实际执行失败由语义父任务直接结束，交易 `decision` 中保存的是恢复建议，不强制外部回答。只有适配器确实返回待答决定和 `decision_id`，才调用 `task(answer)`。网络重试用同一个 `request_key`；读取旧证据不会重复交易。

## 当前实现的边界

- 每条报价都要独自满足全部缺额；两位商人各有一半货时不会拼单。付款也按全部缺额估算。
- 没有主动选择具体报价按钮，只按支付组合在默认顺序匹配；同价商品可能挡住后面的目标商品。
- 空间按付款前计算，没算货币花掉后腾出的格子；一次报价多件产出还可能超过目标缺额。
- 商人菜单只按类型识别，没有牢固绑定本次最初打开的菜单对象。收尾也可能关闭其他当前页面，不能保证只退出自有菜单。
- 收货确认只要求目标物品数量增加，没有完整逐笔核对付款消耗与精确产出。取消活动子任务时也没有完整传递其取消回执；当前 `outcome_uncertain=false` 不能独自证明所有付款已结清。
- 支持的是原生村民菜单路线；其他模组商店、独立货币界面和职业解锁没有接通。

现有 [TradeMenuPreparationTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/TradeMenuPreparationTest.java) 检查可交易职业、默认缺绿宝石时不开窗，以及空手准备的确认。它不覆盖整场买卖，更不能代替真实服务器的价格、补货与菜单同步验收。本轮只做静态核对，未运行测试或游戏。
