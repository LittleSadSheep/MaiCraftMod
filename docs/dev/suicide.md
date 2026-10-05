# 主动寻死：`maicraft:suicide`

玩家已经知道死亡不掉落开启，长时间拿不到食物，或希望通过原生重生回去时，模型可以明确提交这个目标。角色会从附近已观察到的岩浆、威胁生物或高处入口中选择机会，先走近，再让原生伤害结算；这些现成危险都没有或都用完时，随身带着打火石或火焰弹就在附近安全格子脚下点火，站在原生火里受伤。低饱食度、缺粮时间和离重生点的距离都不是自动触发条件，也没有由执行器计算的阈值。

能力完成证明的是**开始尝试后的当前身体死亡**。它不证明某个特定危险致死，也不证明已经重生、回到指定床位、饱食度已经恢复或全部物品保留。默认会另行请求原生重生，随后由 `agent.respawned` 交付实际重生观察。只想正常回家时应使用旅行能力；本能力不指定目的地、不设置重生点、不调用 `/kill`，也不直接扣血、改背包、搭高塔或挖坑；唯一的世界改动是随身点火留下的原生火格，由原版自然熄灭。

## 想看哪一步，就从哪里读

| 要回答的问题 | 代码入口与接下来要看的行为 |
| --- | --- |
| 模型应该怎样填目标，为什么未知字段被拒绝 | [SemanticAbilityCatalog](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java) 的 `suicide` 分支给说明；[SemanticGoalContract.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java) 核对字段、目标类型和约束 |
| `plan` 和 `execute` 的外壳有什么区别 | [PublicToolCatalog](../../common/src/main/java/org/maiwithu/maicraft/mcp/PublicToolCatalog.java) 的工具定义及 `validateGoal`；[IntentRuntime.compile / execute](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) 分别登记计划和执行任务 |
| 哪一步把请求变成原生任务 | [SuicideAbilityAdapter.adapt](../../common/src/main/java/org/maiwithu/maicraft/intent/SuicideAbilityAdapter.java) → [SuicideRequest.parse](../../common/src/main/java/org/maiwithu/maicraft/core/task/suicide/SuicideRequest.java) → [SuicideTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/suicide/SuicideTaskRecord.java) |
| 怎样核对死亡不掉落、关闭挡路界面和计时 | [SuicideTask.advance / checkRule](../../common/src/main/java/org/maiwithu/maicraft/core/task/suicide/SuicideTask.java)；界面与返料交给 [GuiPreparation.ready](../../common/src/main/java/org/maiwithu/maicraft/client/actor/GuiPreparation.java) |
| 附近什么东西会被选成危险 | [SuicideHazards.scan / choose / valid / fallHeight](../../common/src/main/java/org/maiwithu/maicraft/core/task/suicide/SuicideHazards.java)；威胁生物判断复用 [Menace.threatens](../../common/src/main/java/org/maiwithu/maicraft/core/combat/Menace.java) |
| 没有现成危险时在哪里点火、怎么点 | [SuicideHazards.ignition / igniteable](../../common/src/main/java/org/maiwithu/maicraft/core/task/suicide/SuicideHazards.java) 选格；[SuicideTask.burn](../../common/src/main/java/org/maiwithu/maicraft/core/task/suicide/SuicideTask.java) 守火与重点；[SuicideSelfHazard.tick](../../common/src/main/java/org/maiwithu/maicraft/core/task/suicide/SuicideSelfHazard.java) 选物品、低头对准脚下顶面并等待原生回执 |
| 为什么自卫、吃饭和防摔没有抢走身体 | [IntentTask.suppressesSurvivalReflexes](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java) → [TaskSelector.select](../../common/src/main/java/org/maiwithu/maicraft/task/TaskSelector.java)；实际交接仍经过 [CompanionBrain.tick](../../common/src/main/java/org/maiwithu/maicraft/task/CompanionBrain.java) |
| 随行补光是否仍会插灯 | [CompanionBrain.allowsAuxiliaryWork](../../common/src/main/java/org/maiwithu/maicraft/task/CompanionBrain.java) 与 [ClientRuntime](../../common/src/main/java/org/maiwithu/maicraft/client/runtime/ClientRuntime.java) 的帧末调用；[AutomaticLighting.tick](../../common/src/main/java/org/maiwithu/maicraft/core/task/lighting/AutomaticLighting.java) 可继续读取先前动作回执，但不借用寻死独占中的身体提交新灯位 |
| 靠近以后究竟怎么寻死 | [SuicideTask.advance](../../common/src/main/java/org/maiwithu/maicraft/core/task/suicide/SuicideTask.java) 先用 [PlayerNav](../../common/src/main/java/org/maiwithu/maicraft/core/pathing/execute/PlayerNav.java) 步行接近，再用 [InputDriver](../../common/src/main/java/org/maiwithu/maicraft/entity/InputDriver.java) 转头、前进、必要时跳过靠怪途中的小障碍 |
| 没有死、无法靠近或超时后留下什么 | [SuicideTask.abandon / finish / result / progress](../../common/src/main/java/org/maiwithu/maicraft/core/task/suicide/SuicideTask.java)；父步骤失败交给 [IntentTask.failStep](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java) 的效果账本 |
| 死亡为什么没有变成普通任务取消 | [GameplayAttentionMonitor.deathDetected](../../common/src/main/java/org/maiwithu/maicraft/client/runtime/GameplayAttentionMonitor.java) 先调用 [CompanionTickDispatcher.observeExpectedDeath](../../common/src/main/java/org/maiwithu/maicraft/task/CompanionTickDispatcher.java)，经 [TaskSlot.observeDeath](../../common/src/main/java/org/maiwithu/maicraft/task/TaskSlot.java) 和 [IntentTask.observeDeath](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java) 结清当前步骤 |
| 哪一刻请求重生，怎样确认新身体 | [GameplayAttentionMonitor.suicideAutoRespawn / requestNativeRespawn / afterSemanticBind](../../common/src/main/java/org/maiwithu/maicraft/client/runtime/GameplayAttentionMonitor.java)；没有未完成任务承接问题时使用 [IntentRuntime.openDeathRecoveryDecision](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) |
| 暂停查询、断线和重启保留什么 | [IntentTaskRecord.activeExecution / restored](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTaskRecord.java)、[IntentRuntime.checkpointDeath / prepareRespawnHandoff / restoreBound](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) 与 [IntentStateCodec](../../common/src/main/java/org/maiwithu/maicraft/intent/persistence/IntentStateCodec.java) |

## 参数位置与准确含义

`suicide` 是 `goal.ability`，不是第五个 MCP 工具。先用 `perceive(view="abilities", focus="maicraft:suicide")` 读取当前契约，再把完整目标交给 `plan`。计划阶段只检查请求并保存计划，不确认现场危险、当前模式或服务器规则；`execute` 受理也只表示登记任务。

| 层级 | 怎么填 |
| --- | --- |
| `plan.goal.ability` | 必填字符串 `maicraft:suicide` |
| `plan.goal.outcome` | 必填非空字符串，1～500 字符，说明本次死亡目标；文字不会新增倒桶、拆装备或强制传送动作 |
| `plan.goal.target` | 省略或 `null`；不接受 `current_place`、坐标、实体或地标目标 |
| `plan.goal.parameters` | 对象，省略为 `{}`；四个专属字段放这里，不能移到 `preferences`。显式 `null` 不是空对象 |
| `plan.goal.preferences` | 默认 `{}`；仅通用运行时键 `auto_respawn`、`recover_after_death` 可放这里，不接受额外寻死策略 |
| `plan.goal.constraints` | 省略或 `[]`；没有声明可执行的硬约束，软约束同样不接受 |
| `plan.goal.children` | 省略或 `[]`；需要后继目标时，在外层使用 `maicraft:sequence`，把寻死作为一个子目标 |
| `plan.goal.on_failure` | 省略或 `stop`；`continue` 只允许在 `sequence` 直接子级使用，不是 `parameters` 中的字段 |
| `plan.server_id` | 可省略，默认 `minecraft-server`；它是工具参数，不是能力参数 |

新建计划给 `goal`，读取旧计划才用返回的 `plan_id`，不要混用。执行时也选择完整 `goal` 或已经返回的 `plan_id` 之一；传输重试复用原来的 `execute.request_key`。不要编造计划或任务 UUID。后续查询、暂停、恢复、取消使用返回的任务标识，公共控制规则见 [任务与回执](tasks.md)。

| `goal.parameters` 字段 | 类型、默认和范围 | 省略、`false`、零与 `null` |
| --- | --- | --- |
| `keep_inventory_confirmed` | JSON 布尔，默认 `false`。无集成服务端时，必须依据已知设置给 `true` 才能执行危险移动 | 多人服省略或 `false` 表示未确认，执行期失败；不修改规则。单人世界不以这个值否决或覆盖真实服务端规则：即使省略或填 `false`，服务端读到 `true` 仍可继续。`0`、字符串和 `null` 被专属解析器拒绝 |
| `method` | 字符串 `auto`、`lava`、`hostile`、`fall`、`fire`；默认 `auto` | 省略才选 `auto`；空串、其他拼写、数字、布尔和 `null` 均无效。`auto` 先在三类已观察危险中选，都用完才随身点火；指定一种方式后不回退到其他类型 |
| `search_radius` | JSON 数字的整数值，单位为方块，默认 `24`，范围 `4..64`，含边界 | 省略使用默认值；`0` 不表示不限距离，`null`、字符串、非整数值及越界值均无效 |
| `timeout_seconds` | JSON 数字的整数值，默认 `120`，范围 `10..600`，含边界；按每秒 20 个执行 tick 换算 | 省略使用默认值；`0` 不表示无限。`null`、字符串、非整数值及越界值均无效。规则等待、界面准备和扫描也消耗预算 |

四个专属字段相互没有互斥关系，也没有按饥饿、剩余生命或距离添加的隐式参数。`true` 只确认规则，不保证此刻有可用危险，也不保证模组最终保留全部物品。执行还要求角色不是创造、旁观或极限模式；单人世界读到规则关闭，即使确认字段为 `true` 也会结束。

搜索起点是**规则与界面准备完成后首次创建勘查对象时的脚位**，不是提交计划的位置。地形候选站位同时受三维距离和上下 12 格限制；危险入口在站位相邻格，落地列另向下检查最多 64 格。生物在选择时接受起点半径筛选，之后追踪的是同一 UUID 的实时位置。`search_radius` 不是角色全部轨迹的围栏；绕路和继续追逐移动生物都可能越过这个范围。

### 通用重生与恢复键

这两个键由公共生命周期读取，不在 `SuicideRequest` 中解析。优先放在当前寻死子目标的 `parameters` 中，避免对优先级产生误解。

| 键 | 当前行为 |
| --- | --- |
| `auto_respawn` | 当前寻死被认作预期死亡后，省略默认请求原生重生；`true` 请求，`false` 不由 Mod 自动发送重生请求。先看当前步骤，再看根目标；同一目标内 `parameters` 优先于 `preferences`。若角色在寻死尚未开始尝试前就死亡，走普通死亡恢复逻辑，不获得这个隐式默认授权 |
| `recover_after_death` | 省略或 `false` 不记录遗物恢复请求；`true` 记录请求事实。当前步骤或根目标任一为真即为真，不采用上面那套逐级覆盖规则。当前实现始终报告 `item_recovery_started=false`、`item_recovery_claimed=false`，不会因此自动捡遗物 |

两者应传 JSON `true`/`false`。当前公共校验只检查键名，没有逐键严格验证类型；监视器使用 Gson 的布尔读取，显式 `null` 或数字 `0` 会读成 `false`，不会获得省略时的寻死自动重生默认值，部分字符串也会被转换。这个宽松行为是已发现的实现差异，不是推荐写法，也不能在契约里谎称已经严格拒绝。

监视器虽有根目标偏好的读取分支，当前公共 `sequence` 请求不接受非空 `preferences`，其 `parameters` 也仅接受 `protected_labels`。通过正式工具编排序列时，把重生键放在寻死子目标，不要依赖不可提交的根级写法。`auto_respawn=false` 仅控制 Mod 的请求，不能阻止服务器或其他模组自行重生角色。

## 可直接解析的计划示例

以下代码块都是 **`plan` 工具的完整参数对象**，不含虚构的观察引用或 UUID。计划返回 `ready_to_execute` 后，用其真实 `plan_id` 调用 `execute`；执行返回后沿 `next_attention` 等待，不把 `accepted` 当作死亡或重生成功。

多人服已经明确开启死亡不掉落，允许在附近三类危险中选择：

```json
{
  "goal": {
    "ability": "maicraft:suicide",
    "outcome": "通过原生危险动作死亡，再请求原生重生以便返程",
    "target": null,
    "parameters": {
      "keep_inventory_confirmed": true,
      "method": "auto",
      "search_radius": 24,
      "timeout_seconds": 120,
      "auto_respawn": true
    },
    "preferences": {},
    "constraints": [],
    "children": []
  },
  "server_id": "minecraft-server"
}
```

单人世界让执行器读取集成服务端规则，其他专属字段使用默认值。这份计划合法，不表示执行时一定能通过规则检查：

```json
{
  "goal": {
    "ability": "maicraft:suicide",
    "outcome": "在真实死亡不掉落规则开启时尝试原生死亡",
    "parameters": {}
  }
}
```

只尝试已观察高处，死亡后由调用者查看实际死亡决定，再决定是否重生。没有可用高处时结束，不改为靠怪或进入岩浆：

```json
{
  "goal": {
    "ability": "maicraft:suicide",
    "outcome": "尝试从附近高处坠落，确认死亡后等待重生决定",
    "parameters": {
      "keep_inventory_confirmed": true,
      "method": "fall",
      "search_radius": 16,
      "timeout_seconds": 90,
      "auto_respawn": false,
      "recover_after_death": false
    }
  }
}
```

## 从接单到结束，角色依次做什么

1. **取得调度资格。** `IntentTask` 的当前步骤是寻死且没有暂停、待答决定或终态时，调度器让它先于反射和同步动作成为候选。这个豁免在首次适配前就生效，避免吃饭等反射一直挡住明确请求；它不是写入全局“永远关闭自保”的配置。已有跳跃或交通动作的安全交接仍由 `CompanionBrain` 处理。
2. **检查身体、计时和规则。** 执行器只接收原来的身体；先检查已观察的死亡，再累计执行 tick 并检查总预算与模式。集成服务端规则在服务端线程异步读取，以后约每 20 个执行 tick 刷新；首次读取未完成时等待，后续读取在途时仍可能使用上一次已确认值。多人服只读取调用方确认，不把客户端默认 `GameRules` 当作服务器证据。等待规则期间已取得的反射豁免仍存在。
3. **准备界面。** 等已有菜单事务，尝试原生关页并等待返料；不通过直接清空槽位解决界面阻挡。普通聊天框可以保留，睡眠中的角色等待自然醒来。界面拒绝、返料未确认或总预算耗尽都会结束本次尝试。
4. **勘查并选候选。** 地形扫描每个执行 tick 最多遍历 1024 个脚位，完成后保留候选表。`auto` 即使附近已有怪，也先完成这轮地形扫描；`hostile` 和 `fire` 跳过地形扫描。每次重新选择时刷新威胁生物，按角色与候选接近点的距离取最近者，尚未证明路径可达，更没有估算哪一种死得最快。`auto` 只有在已观察的三类候选全部不存在或已放弃时，才改选随身点火格；`fire` 直接选点火格。
5. **步行接近。** 使用 `PlayerNav.walkingOnly()` 和保留地形的默认许可。地形入口要走到接近点约 0.6 格以内并落地；怪物在约 5 格以内且角色落地后转入直接移动。进入直接移动前会重查危险，并等普通导航能够安全交回控制。开始接近就把本轮身体标为已尝试寻死。
6. **接触危险。** 点火格先站到格内，停步低头，用打火石（优先，主背包、快捷栏或副手）或火焰弹右键脚下支撑面顶部一次，等服务端确认且真实出现火格；火还烧着时站着受伤，自然熄灭后原地再点。进入岩浆后停留；靠怪时只走近，距离小于约 1.5 格后停下，不攻击、不举盾、不自动换掉护甲；遇小障碍可跳跃。高处则踏出边缘，开始下落后停止水平输入，不主动放水或展开鞘翅。原生爆炸、燃烧、摔伤和装备消耗仍由游戏或模组结算。
7. **继续、换候选或结算。** 选中候选时开始一个 400 个执行 tick 的窗口，接近阶段也在其中；转入直接移动时重置，之后任何观察到的生命值下降都会续期。走得更近不会续期。窗口耗尽、导航失败、目标消失等情况会留下原因并放弃该入口或 UUID。坠落后仍活着也会另选候选。没有剩余候选或超过总预算则失败或超时；只在同一身体真实报告死亡时完成寻死步骤。

`fire` 以身体当前位置为中心，在水平 8 格（不超过 `search_radius`）、上下 2 格内找最近的未放弃空气格：脚下顶面坚固，格子和支撑方块都不在保护范围内，并且原生蔓延范围（水平各 1 格、向下 1 格、向上 4 格）全部已加载且没有可燃方块，避免把房子一起点着；身上没有点火物就没有这类候选。已经提交的点火若没有出现火格，按原生拒绝或结果未知处理，本执行器之后不再换格反复点火。死亡或放弃后火格留给原版自然熄灭，`doFireTick` 关闭时可能一直保留。

`lava` 需要有可站立的邻接点、无碰撞的进入空间，以及入口或下方的岩浆标签流体；流动岩浆也可匹配。`fall` 要在 64 格下探内看到碰撞落地面、估计落差至少 6 格，遇任意流体、未加载格或禁止进入的格子就排除该列。它不包含虚空跳跃，也不保证经过附魔、药效或模组结算后一定受伤。`hostile` 只从 `Mob` 中按 `Menace.threatens` 筛选，玩家不在这份选择集合中；“被选中”不保证那只生物实际攻击。

扫描不会加载新区域，也不会在地形扫描完成后整片重扫。`auto` 配上大范围和很短的总预算时，可能扫描尚未结束就超时；例如半径 64 的扫描循环包含 416025 个脚位，至少需要 407 个获调度 tick，10 秒的 200 tick 预算不足以走完。这里描述的是现有扫描工作量，不是新增的参数拒绝条件。

## 回执怎样读

单步成功的语义完成回执在 `result.data.steps[i].confirmed_effect.data` 保留寻死事实。任务查询中的当前执行快照带有观察时间，不应覆盖更晚的完成结果。父任务失败另附已完成效果、未完成部分和当时身体观察；不要把普通失败当成自动生成了可答复的决定。

| 字段 | 当前证据含义 |
| --- | --- |
| `task`、`method` | `task` 为 `suicide`；有候选时报告候选类型，没有时回到请求方式，可能是 `auto`。它不是已确认死因 |
| `phase` | `observing`、`approaching`、`exposing_to_hazard` 或 `finished`；`finished` 还需结合成功、取消或超时状态 |
| `keep_inventory_confirmed`、`rule_evidence` | 记录本执行器读到的规则结论和来源：`unconfirmed`、`caller_confirmation`、`integrated_server`。这不是逐物品保留证明 |
| `survival_reflexes_suppressed` | 子任务执行意图经父任务的暂停、决定和终态修正后，表达调度豁免状态；不表示重力、燃烧、怪物攻击已经暂停 |
| `death_observed` | 本轮已经开始尝试、同一身体报告死亡且尚未结束时置真；取消后的死亡不会补记成旧任务成功 |
| `respawn_observed` | 寻死子回执固定为 `false`；只有后续重生事件提供重生事实，不回写成子任务已经确认了重生 |
| `health_lost` | 规则和界面准备后观察到的生命值下降累计值，含最后致死下降；不归因于某次危险，不统计吸收生命或护甲耐久 |
| `ignitions_confirmed` | 本执行器提交点火后观察到真实火格的次数；不含未确认的点击，也不证明火造成了致死伤害 |
| `execution_ticks` | 本执行器获得推进机会的计数，超过 `timeout_seconds × 20` 时超时；不是现实墙钟时长 |
| `attempts` | 新候选记录 `method` 与 `attempt`；放弃时该项替换为 `method` 与 `outcome`。成功项未单独补致死结论，须看整体死亡字段 |
| `mechanical_retry_allowed` | 固定 `false`，不授权机械地重放危险动作；审阅新事实后仍可明确计划一份新任务 |

`GameplayAttentionMonitor` 在普通任务 tick 前处理死亡，先结清寻死步骤，再保存检查点、记录死亡时背包并处理重生。`agent.died` 可见 `expected_death_completed`、自动重生请求与允许状态、检查点是否保存及死亡时库存。`agent.respawn_requested` 表示尝试请求；仍需等待 `agent.respawned` 的 `respawn_observed=true`，失败事件也可能报告请求结果未知。

`agent.respawned` 比较的是**死亡观察时**与重生后的物品计数，提供 `inventory_missing_count`，不等价于与寻死任务开始前的装备完整性逐项比较。模组改变掉落、复活、伤害或重生点时，要据实际事件判断；本能力不把 `keepInventory=true` 冒称为全部物品与耐久无损。

## 暂停、取消、死亡与恢复

| 发生了什么 | 保留与后续动作 |
| --- | --- |
| 暂停或人工接管 | 父任务撤销寻死的反射豁免，旧子任务尝试停导航、松键；恢复同一身体上的任务后，保留预算、候选表、已放弃集合及受伤累计，重新接近。已提交的移动效果、正在下落或燃烧不会回滚，物理交接也可能等当前动作可安全交接 |
| 明确取消、被另一任务替换 | 本次尝试结束并释放身体；之后发生的死亡不能补成这个任务成功。若还活着，原有自救重新参与调度，但不能保证赶得及撤销已经发生的危险 |
| 无候选、规则不满足、界面或导航失败 | 返回实际失败与效果；默认终结本次执行，`requires_decision=false`。根据回执决定是否换地点、改方式或提交新目标，不对一个已经终结的任务盲发 `resume` |
| 超时 | 超过累计执行预算后返回超时并结束；不是证明当前区域没有危险。序列中的 `on_failure=continue` 不放行超时或取消 |
| 寻死步骤处于 `sequence` 中且确认失败 | 只有显式 `on_failure=continue`、状态确为 `FAILED` 且仍有兄弟步骤时继续；失败事实保留，整条序列不会因此变成全部成功 |
| 观察到预期死亡 | 完成当前寻死步骤。单步任务成功结束；序列只前移这一格，后继工作仍要在重生后重新判断，不能连带冒称完成 |
| 默认自动重生或显式 `true` | 在模式允许、连接可用且检查点交接成功时调用原生 `respawn()`；等待实际重生事件。普通后继任务在恢复后保持暂停 |
| `auto_respawn=false`，或自动请求未能完成 | 使用实际返回的 `death_recovery` 决定选择 `respawn`、适用时的 `spectate` 或 `cancel_task`。单步寻死已经结束时，决定由另一份恢复记录承载，不能假定其任务 ID 等于已完成寻死任务 |
| 断线、换世界或身体被替换 | 旧原生子任务按 `BODY_GONE` 收尾，不制造死亡成功。通用持久化可能保留语义目标和已完成步骤；恢复的未完成目标先暂停，不携带旧路线、异步规则读取、候选表或本地 tick 计数 |
| 重启后明确恢复未完成寻死 | 为新身体重新创建执行器、核对规则并从新勘查起点寻找危险。它不是从旧按键继续；旧目标的确认字段仍是保存的输入，调用方需考虑服务器设置是否已变。已保存的完成步骤不会再次寻死 |

自动补光等助手在寻死取得身体时不提交新动作，先前已经发出的原生操作仍可以完成并回报。自保恢复的是**调度资格**，不是免伤、传送或对现场的回滚。

## 已知差异与尚未保证的事

这条专属执行链没有 Create、AE2、FTB 或可选服务端辅助协议的前置条件；它复用 MaiCraft 的客户端身体控制、普通步行导航和原生重生入口，仍需正常连接与身体控制权。多人服确认字段来自调用方，不是服务器规则查询结果。模组生物只有进入 `Mob` / `Menace.threatens` 的现有分类才会成为候选；自定义伤害、流体标签和复活机制最终仍以观察为准。

这些是从当前源码对照得到的边界。本页没有改变它们；涉及行为的修正需要另行审阅授权。

- **运行时布尔没有严格校验。** 四个专属参数会拒绝错误类型，但 `auto_respawn`、`recover_after_death` 的零、空值及部分字符串目前进入宽松读取。不要把两套解析行为写成一致。
- **有限候选不等于区域可达性证明。** `choose` 先选最近的候选，路径随后才尝试；已扫描地形不刷新，同一入口的其他接近点也共用放弃键。失败文字 `No remaining reachable native hazard...` 只说明这一轮没有剩余可选候选，不能推导为周围所有危险均不可达；允许点火时，后面会补一句说明是没带点火物、点火已被原生拒绝，还是附近没有安全点火格。
- **范围与准备期存在差别。** 搜索半径不约束整个追逐路径；反射豁免也早于规则读取完成。未确认规则时没有提交寻死移动，不等于身体在准备阶段仍受到原先反射保护。
- **界面异常的未知项没有完整透传。** `GuiPreparation.Failure` 有结构化证据与 `uncertain()`，但 `SuicideTask.tick` 当前只把异常类型和文字放进失败说明，没有把这份界面证据及未知标记加入寻死数据。不能从缺少未知字段推断原生关页或返料结果已知。
- **死亡不是特定方式的因果验收。** 开始接近即设置本轮尝试标记，放弃候选或暂时让出身体不会清除它。后来的本人死亡可以完成仍有效的目标，但不能据 `method` 宣称岩浆、怪物或坠落就是实际死因。
- **不保证一定死得了。** 只支持上述三类已观察机会和随身点火，不含倒岩浆桶、其他自造陷阱、溺水、虚空或主动攻击玩家；防火、护甲、饱和回血、下雨灭火、图腾、模组伤害机制以及原生拒绝都可能使尝试存活或结束。

## 现有验证入口与本轮检查范围

[common/build.gradle](../../common/build.gradle) 的 `:common:suicideRegression` 运行 [SuicideRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/core/task/suicide/SuicideRegressionSuite.java)。下面说明已有代码覆盖的场景，不表示本轮执行过它们：

| 已有入口 | 贡献者可以核对什么 |
| --- | --- |
| [SuicideHazardsTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/suicide/SuicideHazardsTest.java) | 专属参数拒绝、岩浆凝固、落地列积水、未加载列、已放弃候选；有岩浆时 `auto` 不先点火、无点火物不造候选、副手火焰弹、可燃方块旁换格、点火被拒后不再选格 |
| [SuicideSelfHazardTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/SuicideSelfHazardTest.java) | 无现成危险时 `auto` 原地点火、火未灭不重复点、熄灭后重点、点火后死亡结算、原生拒绝只点一次、缺点火物的失败说明 |
| [SuicideTaskTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/suicide/SuicideTaskTest.java) | 规则未确认、创造与旁观模式、原生移动请求、暂停松键、取消、执行超时、实际掉血与存活坠落 |
| [SuicideHostileTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/SuicideHostileTest.java) | 靠怪不攻击、自卫让位与恢复、实体编号复用时的 UUID 核对 |
| [SuicideAbilityTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SuicideAbilityTest.java) | 注册与契约参数名、宿主暂停、缓存保护状态、单步与序列死亡结算、自动重生偏好 |
| [SuicideRespawnTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SuicideRespawnTest.java) | 调度槽、死亡观察、完成事实入检查点、一次原生重生请求及后续生命观察；使用受控夹具，不是实机验收 |
| [TaskSlotFailureTest](../../common/src/test/java/org/maiwithu/maicraft/task/TaskSlotFailureTest.java) | 通用任务槽的预期死亡、保护恢复与只结算一次 |

随身点火这一轮新增了上述夹具场景，并运行 `:common:suicideRegression` 全部通过；夹具里的火格和服务端确认由测试注入，没有启动游戏实机验收。真实火焰伤害节奏、下雨或防火药水下的表现、模组环境、服务器规则变化及真实重生效果仍不能据此宣称已验收。
