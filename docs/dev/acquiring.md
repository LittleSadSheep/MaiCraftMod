# 想要物品时，角色究竟会做什么

`acquire_items` 的 `count` 是“再拿多少”，不是“背包里最后有多少”。已经带着 4 根木棍再要 16 根，本步开始时记下起始数 4，目标就是主背包合计 20 根；如果允许橡木板和桦木板替代，起始数和目标都按两种木板合计。这里只数主背包和快捷栏，不把装备栏、工作台格子或仓库里的物品当成已经到手。

这页说明 `maicraft:acquire_items` 如何从一个库存目标选择来源、拆分需求并结算实际结果。只想用随身材料合成时，先看 [craft 与工作台流程](crafting.md)；炉次细节见 [烹饪](cooking.md)，菜单转移见 [菜单事务](menu-transfers.md)。读到配方、看到仓库现货和最终物品入包是三个不同阶段。

## 先读这条主线

```text
检查请求 → 盘点背包 → 选择允许的来源
                    ↓
              缺中间材料或工具？
              先补这一项小需求
                    ↓
           创建一个实际操作子任务
                    ↓
       等子任务收尾，再看真实背包数量
                    ↓
         回到上层需求，或说明做不了的原因
```

例如做木棍时缺木板，木板又缺原木，执行器会记住“木棍还在等”，先处理原木，再做木板，最后回到木棍。它不会同时让挖树、开箱和合成三个任务争抢鼠标。

入口是 [AcquireAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/AcquireAbilityAdapter.java)，随后经过内部工具和 [SemanticAcquireApi](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/SemanticAcquireApi.java)，生成 [SemanticAcquireTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/SemanticAcquireTaskRecord.java)。实际逐刻推进由 [SemanticAcquireCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/SemanticAcquireCompanionTask.java) 负责。

## 请求不能被偷偷改成另一件事

能力字段全部放在 `goal.parameters`，不放在 `goal.preferences`。`goal.outcome` 说明目的，不代替数量、取材许可或来源提示。下面的 JSON 是完整 `plan` 工具参数；`plan` 只登记计划，不验证背包已经够料，也不开始取物。执行时使用它返回的真实 `plan_id`，或向 `execute` 提交同一 `goal` 和稳定的 `request_key`；跟随 `next_attention` 查看真正结果。

```json
{
  "goal": {
    "ability": "maicraft:acquire_items",
    "outcome": "再获取16张可替代的木板，优先使用桦木材料",
    "parameters": {
      "item_tag": "minecraft:planks",
      "count": 16,
      "preferred_materials": ["minecraft:birch_log"]
    }
  }
}
```

| `goal.parameters` 字段 | 类型、默认值与实际含义 |
| --- | --- |
| `item_id` / `item_ids` | 单个物品 ID 字符串 / 字符串数组；与下面两种标签输入至少提供一种，可同时提供并合并。须能解析为当前注册的非空气物品，合并去重后最多 256 种。数组不是逐项采购单。 |
| `item_tag` / `item_tags` | 单个物品标签 / 标签数组，可带前导 `#`；执行时展开实时成员，空标签或未知标签报错，展开后同样计入 256 种上限。建议始终写 `namespace:id`。 |
| `count` | 要再获取的件数，整数 1～2304，省略或 `null` 为 1。本步第一次启动时冻结主背包 36 格内所有可接受物品的合计作为起始数，目标为起始数 + `count`；已带物品不算新获取。0、小数、数字字符串、越界值拒绝。2304 是件数上限，不保证不可堆叠物品装得下。 |
| `allowed_sources` | 字符串数组，枚举见下表；省略、`null`、`[]` 都选择默认来源。非空数组才收窄获取许可，不规定执行顺序。每个元素非空；解析时忽略大小写并去除两侧空白。 |
| `preferred_materials` | 明确带命名空间的物品 ID 数组，去重后最多 256 种；省略、`null`、`[]` 无偏好。当前注册表中必须存在且不能是空气；不是配方 ID、标签或禁止其他材料的清单。 |
| `allow_harm` | 布尔值，省略或 `null` 为 `false`；`true` 才允许狩猎来源伤害核实后的目标，不豁免实体关系及保护检查。 |
| `allow_prospecting` | 布尔值，省略或 `null` 为 `false`；允许 `mine` 公平扫描空手后按已知生成带掘进找矿。探矿目标层按就近选取：当前位置已在生成带内取当前层水平掘进，带上方取带顶、带下方取带底，全域矿（如煤）不强制走到峰值暴露层。仍须允许 `mine`；未知生成带不会凭空猜层位。 |
| `may_alter_terrain` | 布尔值，省略或 `null` 为 `true`；控制采矿接近目标时的挖阶梯、通道和垫高，`false` 收窄为普通行走接近，不改变源筛选，也不是禁止挖目标方块的总开关。探矿有自己的显式许可。 |
| `radius` | 整数 1～48 格，0 拒绝。显式整数用于附近来源，容器范围最多 32 格；省略时采矿使用有效已加载视距、其他附近来源默认 16 格、容器默认 32 格。`null` 时采矿仍用已加载视距，但容器取 16 格，见已知边界。 |
| `max_distance` | 整数 16～2048 格，省略或 `null` 为 512；仅传给狩猎前沿搜索，0 拒绝。当前每次搜索从该子任务启动位置计算水平距离，不能当作整项取物始终固定的地理围栏。 |
| `protected_labels` | 已记忆保护地点的名称数组，去重后最多 64 项；省略、`null`、`[]` 无新增标签。无法解析的保护名称会停止任务，不当成没有保护。 |
| `source_hint` | 可选对象，只接受下一表中的键；省略相当于空对象，`{}` 合法，显式 `null` 拒绝。它补充顶层目标的语义线索，不授予新来源，也不指定路线、坐标或槽位。 |

布尔参数的 `false` 是明确关闭，字符串 `"false"` 和数字 0 不能代替布尔值。单值文本 `item_id`、`item_tag` 及提示中的 `description` 若显式传 `null` 或空白会被拒绝；数组可整体为 `null`，但数组内部不能出现 `null` 或空白元素。未知参数键直接报错。

| `goal.parameters.source_hint` 字段 | 类型、范围与使用位置 |
| --- | --- |
| `block_ids` | 来源方块字符串数组；与 `block_tags` 合并去重后最多 64 项，交给采矿来源解析。 |
| `block_tags` | 方块标签字符串数组，带不带 `#` 均可；只补线索，不把未加载或受保护方块变成可采来源。 |
| `entity_type_ids` | 已注册实体类型 ID 数组，去重后最多 32 项；不是运行时实体 UUID。 |
| `expected_item_ids` | 已注册非空气物品 ID 数组，去重后最多 64 项；狩猎时必须与当前需求相交，表示预期掉落关系，实际产量仍看入包。 |
| `trade_profession_ids` | 可解析的职业资源 ID 数组，去重后最多 32 项；当前保存并合并，但 `reviewTrade` 尚未用它筛选商人。不要把它当作已生效的职业限制。 |
| `description` | 非空字符串，说明来源与目标的关系；无默认内容，不转成动作脚本。 |

各提示数组省略、`null`、`[]` 都为空。显式提示与内置来源线索求并集，不覆盖内置线索；递归材料使用各自推断出的线索，顶层 `source_hint` 不逐层复制。狩猎需要合并后同时有实体类型及相交的预期物品，内置知识不足时可这样提供：

```json
{
  "goal": {
    "ability": "maicraft:acquire_items",
    "outcome": "允许捕捉野生鳕鱼，再获取4条鳕鱼",
    "target": {"kind": "nearest"},
    "parameters": {
      "item_id": "minecraft:cod",
      "count": 4,
      "allowed_sources": ["inventory", "hunt"],
      "allow_harm": true,
      "radius": 16,
      "max_distance": 64,
      "source_hint": {
        "entity_type_ids": ["minecraft:cod"],
        "expected_item_ids": ["minecraft:cod"],
        "description": "野生鳕鱼是目标物品的来源，仍须核对实际掉落和入包"
      }
    }
  }
}
```

`item_id`、`item_ids`、`item_tag`、`item_tags` 至少填一种。标签在执行时按游戏当前注册表展开；多种选择方式合并、去重后成为同一组可替代物品。

取物从角色当前位置开始。可以不填 `target`，或只填 `{"kind":"nearest"}`。要去营地取材，就用 `sequence` 明确安排“先 `travel` 到营地，再 `acquire_items`”。以前声明的地点目标没有接入执行，不能继续接受后悄悄忽略。

`source_hint` 只补充方块、标签、生物种类、预期掉落和村民职业等线索。不认识的字段会报错；坐标、槽号或点击脚本不能藏在这里。线索也不等于世界里已经存在可取用的目标。

## 允许来源，与下一步动作是两件事

[AcquisitionSources](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionSources.java) 集中处理来源继承和排序。

| 来源 | 角色做什么 | 不能误解为什么 |
| --- | --- | --- |
| `inventory` | 核对主背包，并在获准时打开支持的随身背包取货 | 主背包数量总会检查，包内现货仍需实际取出，不是生成物品 |
| `wireless` | 用随身 AE2 无线入口观察并领取现货；现货不够而网络里有该物品的合成或加工样板时，提交 AE 合成补足缺口 | 不搜索普通箱子；合成消耗网络原料，由网络里的机器加工，产物送进背包后才计入；需要真实可用的终端和网络 |
| `nearby` | 让拾取子任务靠近符合条件的掉落物 | 不要求归属证明；范围、类型、拾取冷却、明确保护区域和原生拾取条件仍适用 |
| `storage` | 先按记忆顺序调查视线可达的普通容器，再尝试支持的 AE2 网络 | 普通取材默认包含；明确来源限制仍生效，旧库存必须重新开箱确认 |
| `craft` | 使用普通合成配方 | 缺料时继续拆需求，不凭配方计划认定成品已经存在 |
| `cook` | 委托烹饪任务准备原料、燃料和设备 | 前置保留已允许的多段烹饪，靠加工祖先链及嵌套限制防循环，并非一律删掉 `cook` |
| `harvest` | 采收已加载的成熟作物并补种 | 补种尚未完成不能仅因作物入包就报完整成功 |
| `mine` | 只取过公平闸的源（即时可见或观察记忆里见过）；空手且带 `allow_prospecting` 授权时，按该物品已知自然生成带下降并掘进矿道，边暴露边采 | 工具也受原来源限制，不能顺手开放合成或仓库；授权不是深度参数，表外物品或生成带在另一维度时如实拒绝 |
| `trade` | 委托交易任务确认并执行可接受的交易 | 需要明确允许，不把职业线索当成现成交易 |
| `hunt` | 找到合适生物，检查关系与保护后攻击并结算掉落 | 必须允许伤害；击杀本身不等于已获得目标物品 |

没有填写来源，或给空来源列表时，默认考虑背包、仓库、无线现货、附近掉落、采收、合成、烹饪、采矿和狩猎；交易需要明确加入。默认伤害许可关闭，所以列出了狩猎也不能直接动手。

普通箱子按“记忆中有目标物品 → 从未翻过 → 记忆中没有目标物品”调查，每个需求内每只箱子只访问一次；大箱子两半算同一只。真实菜单尚未同步时保留未知，同步后保存完整库存和稳定箱子标识，取物后再覆盖旧数量。箱子记忆随世界检查点保存，过期数量只作为复访线索，不计入当前现货预算。

箱子调查和接近路线默认限于工具接单起点三维 32 格，显式更小 `radius` 会收窄；排队、递归补料和走到下一只箱子都不会扩大范围。`ObservationVisibility` 统一从角色眼睛按视觉外形核对箱子、方块和实体探索证据，透明材质与不完整外形的空隙允许观察，实心墙、关闭的实心门与未加载地形仍遮挡目标。观察到箱子不代表原生准星已经能点中它。

起始数与容器范围一样按语义步骤保存：同一步 `retry`、`recover` 补完前置后回到本步、同进程暂停和重启恢复都沿用首次启动时的起始数，只有 `replace_goal` 换掉本步才重新起算；`retry` 改了物品范围时，原有物品保留旧起始数，新加入的物品按当时数量补记。升级前保存、尚未完成的取物步骤没有起始数，恢复后仍按旧版“最终合计数”执行，不会再多拿一轮。公开 `craft`、`cook`、`trade` 的 `count` 也按同一规则理解为“再做/再烧/再换几件”；交互前取工具、施工补料、取物内部的烧炼与交易来源等内部组合直接给最终合计数，不受这条增量语义影响。

语义取物步骤首次捕获的维度、原点和半径随任务检查点保存，第一次调查箱子前等待实际写入完成。同进程暂停或重启恢复都复用该范围；插入前置步骤不会把原范围转给新的步骤。旧检查点缺少范围时，回执明确报告 `container_search_scope.status=unknown`，不从恢复地点重新开始搜索；背包已满足目标时仍直接完成，取物尝试为空。磁盘保存失败也会在开箱前结束并说明原因。

来源填写顺序不会成为执行脚本。每刻先确认最终主背包数量及在途子任务，再收起已由现货满足的前置；允许 `inventory` 或 `storage` 时先尝试支持的随身背包取货，然后调查获准的可见容器、观察可用无线现货，再由来源排序选择剩余操作。已能合成或烹饪的路线、已知采矿或狩猎线索会影响顺序。已经耗尽的来源按名字记录，不能因重新排序又凭换下标重试。

主背包数量始终检查，即便显式来源不含 `inventory`；但打开 Sophisticated Backpacks 取货需要 `inventory` 或 `storage`。包内、AE 网络或箱内数量只作为现货线索，取入主背包后才满足最终目标。没有相应模组或原生入口时不能把未知库存当作现成材料。

随身带多只背包时逐只开包。开包前记下服务端将采用的原生地址（主手选中格、副手或穿戴 handler 格）、背包种类和 `sophisticatedcore:storage_uuid`；菜单打开后，服务端下发的地址、种类和内容身份三者都对上才算“就是发起的那只”。打开标签、排序、渲染清单这类组件会被模组在开包前后原地改写，不参与判断。三项中任一不符或无法核对时，不在这只包里存取：鼠标上有物品先放下（依次尝试整叠并入主背包同类、主背包空格、部分并入、这只包的空存储格；都放不下就交给原生关闭返还），再原生关包。确认关闭后以 `backpack_open_mismatch`、`menu_closed=true` 的确定失败返回，取物接着开下一只包，整理存包也换下一只。`access_detail` 写明哪一项不符，并附 `menu_vs_carried_components` 和 `carried_vs_initiating_components` 两组变化的组件名；`wrong_menu_settlement` 列出确认过的鼠标放置，以及交给原生关闭返还的余物。某次放置没被确认时不再点击，照样原生关包；只有回执明确“未生效”才算确定结果，否则标记结果不确定，由上层停下复核。关闭本身未得到确认时，仍按 `backpack_open_failed` 报告，并标记结果不确定。

必需工具继承当前来源许可。富余铁或钻石带来的工具升级，只在已允许的背包、仓库、合成范围内尝试；不能为一次可选升级另起野外采集链。未授权仓库的库存也不参与升级预算。

## 配方规划只负责回答“先缺哪一项”

[AcquisitionNeed](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionNeed.java) 是一项尚未满足的需求，保存目标数量、允许来源、已经经过的物品和配方、失败过的路线，以及是否已经发生实际效果。执行器把小需求压到栈顶：最上面的一项先做完，然后回到下面等着的原任务。

[CraftOps](../../common/src/main/java/org/maiwithu/maicraft/core/tools/CraftOps.java) 先分配真实背包材料。例如一条配方分别需要“任意木板”和“橡木板”，背包只有一块橡木和一块桦木，分配不能先用掉唯一橡木，再误报第二格缺料。

做不了的候选直接通过 [CraftRecoveryCandidate](../../common/src/main/java/org/maiwithu/maicraft/core/task/craft/CraftRecoveryCandidate.java) 交给 [AcquisitionRecipePlanner](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionRecipePlanner.java)。内部保留完整材料替代项；对外报告最多展示八个候选、每格六十四种材料，这个展示上限不会截断内部规划。

规划按以下规则继续：

1. 现在已有材料能完成的配方优先，不再为尚未凑齐的旧方案找额外材料。
2. 材料齐了但缺工作台，就先补工作台；同一配方不会因为摆台失败无限追加工作台。
3. 先扣已有实物；剩余必需材料若只能靠重新制造祖先物品满足，就拒绝这条循环路线。不能为了“先有床才能染床”的缺床配方，把所有染料先做一遍；已在包里的祖先材料仍可使用。
4. 同样的材料组先合并数量。优先暴露伤害许可等限制，以及转换层数更深、数量更多的材料需求。
5. 两条配方各只差一件时，可以把那两种材料当作替代项一起找；各差多件时不能混算半套材料。
6. 已承诺的必需生产路线产生实际效果后关键前提失败，留下证据并停止，不无声切到另一条仍需花材料的路线。可选效率工具升级是单独分支：没有未知效果或未解决的许可边界时，结清升级尝试后仍可返回原采集。

成本是排序分值，不是秒数或成功概率。完整材料树先比较能否展开、是否全由现货覆盖，再看命中的材料偏好和成本；普通合成一批成本 1，已知采矿来源通常 40，已观察的附近来源按距离取 1～20，已许可狩猎 100，未知叶子 10000。未知叶子仍可能形成 `feasible=true` 的待补料计划，这不证明现场有货或原生动作一定可执行。

[RecipeMaterialPlan.estimate](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/RecipeMaterialPlan.java) 在总计 8192 次展开预算内按 1、2、4、8、16、32 层逐步前瞻；另一个结构排序估计只看六层，二者不要混淆。裁剪或预算耗尽会使 `preparation_plan.search_complete=false`，并非穷尽所有模组配方。库存账在分支中扣除已分配材料和整机其他部件的预留量，不能重复使用同一根原木，也不消耗真实背包。

允许采矿且确实缺料时，[附近材料观察](../../common/src/main/java/org/maiwithu/maicraft/core/task/mine/NearbyMaterialSources.java) 复用 [天然树判定](../../common/src/main/java/org/maiwithu/maicraft/core/task/mine/NaturalTreeSource.java)，给实际观察到的来源更低估价；发现来源不会直接增加库存。`preferred_materials` 是同一规划器共享的软偏好，可穿过中间配方，但没有全局“原版一定第一”或“偏好一定成功”的保证。已经持有的祖先材料可用于工具前置，循环检查只约束继续制造缺口。

## 什么才算完成，什么情况要停

主背包目标数量满足是完成条件，但仍有在途原生事务的子任务需要先收尾。父任务读取子任务结果、库存增量和已发生效果，再决定继续哪一项需求。

任何来源报告不确定效果，或烹饪报告仍有未结炉次时，父任务都会停下；即使此时背包增加，也不能用数量达标掩盖未知事务。仓库已经发生效果却没完成收尾的失败也保留。狩猎由攻击子任务收取死亡现场的候选掉落，旧堆、混堆及未知归属不阻止原生拾取；来源推断与实际背包增加分别返回，父任务不重复启动拾取。

取消不会撤销已经挖掉的方块或取走的物品。收尾会停止子任务，并保留尝试、库存变化与不确定性。主要结果字段包括 `goal_satisfied`、`attempts`、`recipe_trace`、`issues`、`effects_observed` 和 `outcome_uncertain`；配方记录里的 `allowed_sources` 表示许可，不能当作已经执行的顺序。

`requested_additional_count`（请求的件数）、`baseline_count`（本步起始数）、`net_gained_count`（当前合计减起始数，消耗时可为负）、`required_final_count`（起始数 + 件数）、`observed_final_count`、`missing` 和前后分物品计数说明目标事实；`attempts[].child_data` 说明具体子任务。`recipe_trace[].preparation_plan` 记录补料清单、加工顺序、估价、偏好及附近来源证据；中间件后来到包时会记录收起的旧材料分支。`issues[].facts.crafting_surface` 区分已有台、尚需取台及无摆放点。不要只凭最终英文错误猜是缺材料还是缺空间。

容量故障可能启动 `AcquisitionInventoryTidy`：保留任务材料、工具等物品，把其他物品存入随身背包或可用随身 AE 以腾空间，同一调用最多四轮，有未确认转移则停下。这个存入分支目前不按 `allowed_sources` 过滤，取货限制不是完整的“禁止任何存储副作用”开关；看 `inventory_maintenance` 和 `inventory_capacity` 确认实际转移。

普通路线确实耗尽后，允许制造的需求可以通过 [MaterialProcessPlanning](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/MaterialProcessPlanning.java) 返回知识链接和仍缺的材料。它不会自行造机器，也不会把 EMI 配方展示当作物品已经生产出来。

需求栈属于当前执行器。世界恢复保留总目标、步骤和历史，先以暂停状态恢复，不把旧菜单和路线直接搬到新世界。旧版曾接受的小数数量或被忽略的地点仍可查询、取消和修订；重新执行必须满足现在的规则。

| 控制事件 | 保留什么、调用方怎样继续 |
| --- | --- |
| 同进程暂停 | `task(action="pause")` 暂停总任务调度并释放身体；进程内子任务和需求状态保留。无待答问题时 `resume` 才能继续，暂停不承诺原生服务器上的加工也冻结。 |
| 失败待决策 | 读取实际 `decision_id` 和列出的 `choice` 后用 `task(action="answer")`。`retry` 可更新 `details.parameters`；`recover` / `replace_goal` 使用 `details.goal`，不要把 `recovery_options[].id` 直接当成必定有效的选择。 |
| 取消、超时 | 停止并结算子任务，保留实际物品、世界变化及未知事务；不回滚库存，不把部分完成改成完整成功。 |
| 死亡、玩家替换、断线或换世界 | 总任务层先保存检查点并清理旧身体；未完成工作恢复为暂停。死亡问题需按实际回执处理，重生不等于物品找回或自动恢复旧菜单。 |
| 重启后恢复 | 使用原任务查询历史再继续，重新读当前库存和现场；取物起始数和容器范围沿保存的值恢复，旧档缺范围时明确为未知。参见 [任务生命周期](tasks.md)。 |

## 想看哪一步，打开哪里

| 排查入口 | 文件和方法 |
| --- | --- |
| 参数为什么拒绝、标签何时展开 | [AcquireAbilityAdapter.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/AcquireAbilityAdapter.java)、[SemanticAcquireApi.validateArguments / newRecord](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/SemanticAcquireApi.java) |
| 数量、默认来源和子任务记录 | [SemanticAcquireTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/SemanticAcquireTaskRecord.java)、[PlayerInv.buildableCount](../../common/src/main/java/org/maiwithu/maicraft/core/PlayerInv.java) |
| 当前需求为何还在追原料 | [SemanticAcquireCompanionTask.tickAcquisition / completeCarriedPrerequisite / attemptCraft](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/SemanticAcquireCompanionTask.java) |
| 为什么挑这个木种或模组分支 | [AcquisitionRecipePlanner.materialPlan / chooseFrontier](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionRecipePlanner.java)、[RecipeMaterialPlan.estimate](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/RecipeMaterialPlan.java) |
| 工具、燃料会不会扩大来源 | [AcquisitionSources.forTool / forCookingInputs](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionSources.java)，再跟踪父任务的 `attemptMine`、`attemptCook` |
| 容量故障与随身库存 | [AcquisitionBackpackInventory.next](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionBackpackInventory.java)、[AcquisitionInventoryTidy.prepare / settle](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionInventoryTidy.java) |
| 子任务完成但父任务未结束 | [SemanticAcquireCompanionTask.tickActiveChild / failAcquisition / resultData / cleanup](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/SemanticAcquireCompanionTask.java)；加工知识交接看 [MaterialProcessPlanning](../../common/src/main/java/org/maiwithu/maicraft/core/task/acquire/MaterialProcessPlanning.java) |
| 暂停、死亡和持久化 | [IntentTask.stop](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)、[IntentRuntime.prepareRespawnHandoff / restoreBound](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java)、[IntentTaskRecord.pause / resume](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTaskRecord.java) |

## 当前边界与说明差异

- `radius` 用严格解析器取默认值，但容器范围又按字段是否存在选择：省略为 32，显式 `null` 为 16；两者对采矿都使用已加载视距。不要把 `null` 当作所有可选字段通用的省略写法。
- `source_hint.trade_profession_ids` 已接受但没有传入交易筛选。`max_distance` 只约束一次狩猎搜索，搜索原点不是总任务冻结原点。
- 内部 `SemanticAcquireTool.parameterSchema` 尚未列出已由 API 接通的 `allow_prospecting`、`may_alter_terrain`、`max_distance`。公开能力契约和内部工具元数据需分别核对。
- 全局目标校验允许 `auto_respawn`、`recover_after_death`，但取物 API 的参数白名单没有这两项；当前不能宣称它们能直接放入本能力的 `goal.parameters`。死亡控制沿实际总任务回执处理。
- 合成子任务的光标或网格占用也可能归为 `NO_SPACE`，父层再包装为容量问题；具体原因仍在原始子回执，见 [合成故障解释](crafting.md)。

## 修改后怎样核对

- [AcquireGoalTest](../../common/src/test/java/org/maiwithu/maicraft/intent/AcquireGoalTest.java)：检查新目标数量、来源提示、地点边界及发现上限。
- [AcquireAdditionalCountTest](../../common/src/test/java/org/maiwithu/maicraft/intent/AcquireAdditionalCountTest.java)：已带物品时 `count` 仍要再拿一件；起始数随暂停、重试和重启沿用，旧检查点按旧版最终合计数执行。
- [AcquisitionSourceInheritanceTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionSourceInheritanceTest.java)：经过实际工具准备分支，检查来源不扩大；只盘点背包不会扫描配方。
- [AcquisitionRecipePlanningTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionRecipePlanningTest.java)：原生配方与真实背包分配、完整候选、循环、材料组与替代路线。
- [AcquisitionPrerequisiteRefreshTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionPrerequisiteRefreshTest.java)：工作台或木板后来到包后退出旧原料分支，保留已经发生的效果。
- [NearbyRecipePreferenceTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/NearbyRecipePreferenceTest.java)、[RecipeFrontierFallbackTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/RecipeFrontierFallbackTest.java)：附近来源、材料软偏好，以及无副作用替代失败与已产生效果后的停止边界。
- [StorageSupplyRadiusTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/StorageSupplyRadiusTest.java)：建筑补料的仓库半径与配方库存提示一致，不扩大野外采集范围。
- [GoalCheckpointCompatibilityTest](../../common/src/test/java/org/maiwithu/maicraft/intent/GoalCheckpointCompatibilityTest.java)：实际保存、恢复和取消旧目标。

这些是已有的离线验证入口，不等于走完所有来源的整合包验收。材料和合成回归由 `:common:craftingRegression` 汇集，配方分支恢复另有 `:common:recipeFrontierFallbackRegression`，探矿交接有 `:common:acquisitionProspectingRegression`。文档说明与静态检查不能代替这些入口的实际运行结果；改具体来源时还需核对对应执行器的导航、菜单确认和生命周期。
