# 补光：沿当前路线插火把，或照亮一个区域

挖矿时需要让观众看清角色附近，可以显式开启 `maicraft:auto_light`：角色沿主任务原路线行动，只有身体资源空闲且脚部或眼部方块光不足时，才用副手火把补光。给基地、农田集中补光使用 `maicraft:light_area`：它会接管主任务，调查范围、取料、前往灯位、放置并复测。

| 要解决的事 | 使用哪个能力 | 完成意味着什么 |
| --- | --- | --- |
| 挖矿、旅行过程中顺路补光 | `auto_light` | 开关或查询完成；不会保证以后经过的每个暗格都能插灯 |
| 检查随行补光的效果 | `auto_light`，`action=status` | 重新读取本身体已采样路线的光照和火把分布 |
| 把指定区域补到所选覆盖率 | `light_area` | 已闭合范围内的目标采样格达到所选方块光阈值和比例 |

自动补光初始关闭，连接清理后也恢复关闭。两种能力都读游戏的 **BLOCK light（方块光，0–15）**，不把天空光、显示器亮度或光影效果算成照明。默认阈值 8 是执行器的照明目标，不是从所有原版、模组或服务器刷怪规则中动态算出的统一阈值；照亮采样格也不表示消除了已有怪物或所有特殊生成机制。

## 公开请求与参数位置

补光是 `goal.ability` 中的能力，仍通过 MCP 的 `plan`、`execute`、`task`、`perceive` 使用。以下 JSON 都是完整的 **`plan` 请求参数**；规划不会开启补光或放灯，执行应使用真实返回的 `plan_id`，也可向 `execute` 提交同一份 `goal`。不要把参数写成 `auto_light(action=...)` 的独立工具调用。

补光选项都放在 `goal.parameters`。`light_area` 的地点放在与 `parameters` 同级的 `goal.target`；`auto_light` 不接受地点目标。两者没有专用 `goal.preferences` 或硬约束，留空或省略。自然语言 `outcome` 只说明目的，不能替代阈值、开关或范围参数。顺序任务另见[顺序目标](sequences.md)。

### 随行补光

| `goal.parameters` 字段 | 类型、默认值和实际作用 |
| --- | --- |
| `action` | 字符串 `enable`、`disable`、`status`；**显式调用本能力时**省略为 `enable`，不同于会话初始关闭。严格区分大小写，`null`、空串、布尔值、数字都非法 |
| `minimum_light` | 整数 1–13，省略为 8；脚部与眼部较低的方块光小于它才尝试。显式 `null`、0、小数、字符串数字、布尔值非法 |
| `protected_labels` | 已记住地点名称的字符串数组，省略或 `[]` 表示本次不增加名称；显式 `null`、空白成员非法。与所在 sequence 的继承名称合并；运行时再合并当前主任务的名称 |

开启时建议把意图写全：

```json
{
  "goal": {
    "ability": "maicraft:auto_light",
    "outcome": "沿当前挖矿路线按需补光",
    "parameters": { "action": "enable", "minimum_light": 8 }
  }
}
```

只查询和随时关闭分别使用以下请求。要立即查询或关闭，需要执行它们；只调用 `plan` 不会改变开关。

```json
{
  "goal": {
    "ability": "maicraft:auto_light",
    "outcome": "检查已走路线的补光状态",
    "parameters": { "action": "status" }
  }
}
```

```json
{
  "goal": {
    "ability": "maicraft:auto_light",
    "outcome": "停止后续自动插火把",
    "parameters": { "action": "disable" }
  }
}
```

`enable`、`disable` 每次都重建阈值和本次保护名称：例如先设 10，再仅传 `action=enable`，阈值会回到 8；省略保护名称也不会保留此前单独配置的列表。`status` 校验参数后不应用它们，回执中的阈值仍是当前实际配置。未知保护名称不会让配置请求立即失败，而会在尝试补光时表现为 `unresolved_protection`。

单独执行 `auto_light` 是即时独立请求，不替换或暂停当前任务，也不接管玩家手动控制。放在 sequence 里时仍按子目标顺序执行该配置步骤；整个 sequence 是正常主任务，不能据此把任意 sequence 当作独立请求。开关成功不会立即换副手；只有后续游戏刻出现暗格且允许借用身体时才准备火把。

### 区域补光

`goal.target` 必须能解析到当前维度的调查种子：

| `kind` | 同级字段和含义 |
| --- | --- |
| `current_place` | 无其他必填字段；以角色当前位置为种子 |
| `coordinates` | `position: {"x":整数,"y":整数,"z":整数,"dimension":可选字符串}`，单位为方块；省略维度使用当前维度，异维度不直接施工 |
| `landmark`、`area` | `label` 使用 `perceive(view=landmarks)` 中已有的准确名称；名称不是自动识别任意“我的基地”边界的能力 |
| `prior_result` | 引用同一任务中早先成功步骤的已确认位置；多候选时通过 `label`、`relation` 区分。未匹配时要求决定，不改用玩家脚下；具体解析见 `PriorResultResolver.resolve` |

`area` 始终走连通区域调查，显式半径只为它提供外边界。其他目标省略半径时也走连通调查，提供半径则逐列调查水平圆形区域。`label` 和 `relation` 不提供逐灯位坐标；`relation` 仅在前序结果解析时参与匹配。

| `goal.parameters` 字段 | 类型、默认值和组合关系 |
| --- | --- |
| `radius` | 可选水平圆半径，单位格，声明范围 1–29,999,984；省略或 `null` 不设显式半径，按匹配地面发现连通边界。不能凭空编一个半径缩小用户范围。旧公开适配器对 0、越界和部分错误类型的处理有差异，见后文 |
| `minimum_light` | 声明为整数 1–15；省略或 `null` 时，`crop_growth` 为 9，其余为 8。该值是方块光阈值，不是灯距 |
| `coverage` | 字符串；省略或 `null` 为 `all`。`all` 验收全部可行走脚部样本；`most` 验收其中至少 90%；`player_visibility` 验收同类样本至少 95%；`crop_growth` 验收全部作物格或耕地上方格。`most`、`player_visibility` 仍可能留暗格；`player_visibility` 没有额外眼部或画面可见性检查 |
| `block_id` | 可选发光方块或对应物品 ID，例如 `minecraft:torch`；省略、`null`、空白不加偏好。插在 `light_preferences` 之前，**只是首选，不锁定材质** |
| `light_preferences` | 有序 ID 字符串数组；省略、`null`、`[]` 都不增加偏好。无法解析或不适用的光源会被跳过；随身灯具和默认备选仍可能被采用 |
| `style` | 字符串 `auto`（省略或 `null` 默认）、`ground`、`unobtrusive`。均使用落地支撑候选；`unobtrusive` 会影响靠边偏好与耕地候选。公开入口不支持 `wall`、`hanging` |
| `placement_preference` | 字符串 `coverage_optimal`（省略默认）、`central_unplanted`、`unobtrusive`；显式 `null` 被拒绝。`central_unplanted` 只可与 `crop_growth` 组合。先比较预计覆盖增益，再以偏好打破平局，不保证固定中央灯位 |
| `material_policy` | 字符串 `ordinary`（省略、`null`、空白默认）、`storage_available`、`inventory_only`；决定供料来源，不决定严格灯具材质。内部保留旧别名，公开请求使用这三个正式名称 |
| `allowed_sources` | 来源字符串数组：`inventory`、`nearby`、`wireless`、`storage`、`harvest`、`craft`、`cook`、`mine`、`trade`、`hunt`。省略、`null`、`[]` 使用策略默认；它经共享供料层扩展，当前不是严格白名单，见下文 |
| `allow_harm` | 布尔值，省略、`null`、`false` 都不许可有伤害的取材；仅显式 `true` 传递该许可。数字、字符串布尔值非法 |
| `protected_labels` | 同维度、已记住的名称数组；省略或 `[]` 不增加名称，显式 `null` 或空白成员非法。任务自身保护每个锚点各轴正负 4 格，并保留已观察的敏感格；不能把名称解释为任意完整多边形区域 |
| `max_placements` | 可选整数 1–24,000；省略或 `null` 无显式总预算，显式 0、小数、字符串数字、布尔值、越界均拒绝。快速火把按已提交灯位计，普通施工按派发灯位计，不是最终火把消耗量 |

`coverage`、`placement_preference` 使用精确小写枚举；`style`、`material_policy` 会去两端空白并规范大小写。以上没有单位的数值均不是秒数。数组成员必须为非空白字符串；类型错误不能当成“无限制”。

共享供料层的现状是：先计背包，`ordinary` 且没有来源列表时加入库存、合成、附近掉落、收获、烹饪与挖掘；`storage_available` 开放库存与合成。`inventory_only` 在列表不含 `storage` 时只允许背包；若显式列表含 `storage`，当前实现会越过这一限制并加入 `craft`。希望仅用随身材料时应使用 `inventory_only` 并省略 `allowed_sources`。此处是已存在的行为差异，不应称为严格来源保证。

下面示例假设用户明确要求“当前位置周围六格内的地面全部补光”，仅使用已携带材料，不伪造地标或任务 UUID：

```json
{
  "goal": {
    "ability": "maicraft:light_area",
    "outcome": "照亮当前位置水平半径六格内的目标地面",
    "target": { "kind": "current_place" },
    "parameters": {
      "radius": 6,
      "minimum_light": 8,
      "coverage": "all",
      "block_id": "minecraft:torch",
      "material_policy": "inventory_only"
    }
  }
}
```

角色已在目标农田旁，用户希望寻找中央未种植落点时，省略半径让 Mod 根据实际匹配地面调查，仍不提供点击坐标：

```json
{
  "goal": {
    "ability": "maicraft:light_area",
    "outcome": "为身边连通农田补足生长照明，优先中央空种植位",
    "target": { "kind": "current_place" },
    "parameters": {
      "coverage": "crop_growth",
      "placement_preference": "central_unplanted",
      "material_policy": "inventory_only"
    }
  }
}
```

## 随行补光怎样与主任务共用身体

每刻先推进主任务和导航，再运行区域火把轮预约的帧末点击，最后调用 `AutomaticLighting.tick`。随行补光不创建导航目标；先读上次放置回执，再决定本刻是否有资格提交新动作。主任务的精确瞄准、交互、菜单、身体自救优先。

身体允许自动控制、角色在地面、未骑乘或睡眠、未处于水或岩浆、未使用物品、无打开界面且菜单游标为空时，才可能借到空闲准星和原生修改额度。主任务忙碌时仍记录已加载的脚部和眼部样本，保留经过的暗格。主任务暂停、区域补光占用当前步骤或没有原生动作权限时，不开始随行插灯。

触发后先使用已有副手火把；否则从主背包 36 格找一叠，用原生菜单交换到副手，原副手物品回到被交换的背包格。主手选择保持不变，不凭空创建火把。准备完成后，选当前站位真实可达、可见、有结实支撑的空格，优先少转头的墙面或地面。只有落在当前视线前方约 78° 内的候选才值得为它转头；前方两格与斜前都够不着时这一轮不出手，等角色走到下一处再补，不为身后两格的壁面把镜头整圈甩过去。快速路径不会清草、拆块或搭支撑。

补光转向走镜头自己的平滑通道：每刻把灯位方向登记为目标，镜头按与普通导航相同的角速度和加减速逐帧转过去，不再一帧瞬移到位。角度进入 0.75° 容差并复核过真实射线之后才提交副手使用，随后把主任务的原路线目标交还，镜头再平滑转回路线方向，不瞬跳也不停顿。转向期间照旧受"走够两格才补下一支"的限制；单支灯最多转 40 游戏刻，超预算或灯位在转向途中失效就撤回转向、放掉这一格，由调用方换下一个候选。原生确认需要服务器方块确认、目标状态和火把总量变化相符；火把从背包换到副手不算消耗。成功后等光传播，失败也有冷却；距上一次已提交动作的角色站位不足两格时不会再放，无论上次是否确认成功。缺火把只报告 `missing_torches`，没有自动采购和绕路补漏。

关掉自动补光会停止新动作，并继续读取已经提交的放置回执。它不取回已插火把，也不把旧副手物品换回来。两个能力使用副手的动作与主手使用物品的交互路径是不同入口；不能仅凭本能力已成功就推断其他物品使用不存在误触发。

## 区域补光怎样调查、放置和验收

1. **确定地点与范围。** 未找到点名地标、维度不匹配、前序结果不明确时交回决定。连通模式寻找附近匹配种子，以相邻地面、高差一格及实际观察的一格水渠或通道扩展；必要时移动以加载未知边缘。连通可行走地面可能一直延伸到基地外，名称本身不构成围墙。显式圆形扫描若含卸载列则失败，不静默缩小分母。
2. **建立样本与保护。** 普通模式选脚部和头部无碰撞、无流体且脚下有支撑的地面；作物模式选作物或耕地上方。显式扫描主要看锚点上下 12 格及地表附近；连通种子搜索看上下 16 格及地表附近，再按连接关系扩展。它不是整根世界高度柱的全体积验收。容器、机器、红石、流体、作物、耕地、路径等敏感格进入保护，作物及耕地还限制行走落脚。
3. **规划本轮灯位。** 默认首选火把；显式偏好优先，但随身和标准备选仍可参与。地面候选要有合法支撑并避开保护和狭窄通道；在允许的作物场景下可以考虑未种植耕地上方。对实际暗格用发光值减曼哈顿距离估计覆盖，贪心挑选能增加覆盖的灯位，再按偏好打破平局。估计不计遮挡，真正效果在后面量光。
4. **补齐本轮材料。** 材料不足时先由共享供料器按策略取材并返场；拿到材料后重扫场地再规划，保留已发生效果。火把规划计入副手，其余灯具按施工器可用背包计数；供料器自身的最终数量核对仍只计主背包，可能额外补足一整批。
5. **执行一轮。** 生存消耗模式的火把由 `TorchLightingPass` 边接近灯位边尝试副手放置：到位后先按正常转头速度把镜头平滑转到灯位，角度到位且真实射线命中目标后才提交副手使用。前一支灯已让附近样本实测达标时，跳过后续冗余候选；单个灯位导航无路、到位后资格不再成立、或转向途中这一格失效时，只淘汰该灯位并换下一候选，拒绝明细（坐标、闸名、原因）进入该轮回执，不再因首个灯位被拒而放弃整轮候选。其他灯具以及免费材料模式走普通 `BuildCompanionTask`。区域任务拥有导航和供料行为，因此不能承诺像 `auto_light` 一样不绕路。区域执行期间自动补光让位。
6. **等待传播并复测。** 每轮结束等待光传播，再读完整目标样本。即使子轮失败，也先承认已经发生的灯光效果；区域达标以实际覆盖为准。未达标则再规划；预算用完、缺供料、无可用灯位、重复无增益候选或期限耗尽时停止并报告部分效果。卸载样本导致未知或失败，不能从分母删掉再宣称全覆盖。

每轮最多派发 48 个候选，单次候选集合最多 4096 个；这些是规划工作批量，不是用户授权的区域边界或总火把上限。初始进展期限为 2400 游戏刻，观察、导航或施工取得可确认进展时续期，不是固定两分钟就停止所有区域工作。只有显式 `max_placements` 是调用方给定的总放置决策边界。

## 回执怎样读

`execute` 的 `accepted` 只表示接单。任务终态、分步结果、尝试记录和待决策内容通过返回的任务引用及 `next_attention` 查看；回复丢失时用同一个 `request_key` 找回原请求。要进行新的实时状态查询，应提交新的请求标识，重复旧标识会复用原记录。

### `automatic_lighting`

| 字段 | 能据此判断什么 |
| --- | --- |
| `enabled`、`minimum_light`、`state` | 当前开关、阈值和最近运行状态；`enabled=true` 不表示已装备或已完成覆盖 |
| `source`、`hand`、`torch_inventory` | 预期来源为火把、手别为副手；实际数量看 `total_count`、`backpack_count`、`off_hand_count`，不能把预期手别当实际背包证据 |
| `scope`、`dimension`、`observed_cells` | 本身体采样过的脚部与眼部格，以及本次仍能读取的样本数；不是整个矿洞或基地 |
| `minimum_observed_block_light` | 本次可读取样本的最低方块光；没有有效样本时为 `null` |
| `below_target`、`unloaded_cells` | 所有当前未达标位置及亮度；卸载或读取失败的位置另列未知 |
| `placements` | 已读到终态的放置位置、原生状态和说明；数量不是区域验收 |
| `coverage_verified` | 至少一个样本可读、无暗格或未知格、无未结清放置；范围仍仅限这些采样格，停用后也可以为真 |
| `protected_labels`、`observation_problem` | 显式配置列表，以及最近观察异常（若有）；列表不包含所有合并后的主任务名称，也不携带完整区域几何 |

常见等待状态包括 `yielding_to_primary`、`preparing_offhand`、`awaiting_native_confirmation`、`waiting_for_route_progress`、`no_reachable_support`、`missing_torches`、`unresolved_protection`、`observation_unavailable`。等待不应解释为主任务失败。副手交换失败和放置原生终态还会作为状态或放置记录出现。

### 区域结果

先看 `verified`、`area_boundary_verified`、`coverage`、`required_coverage` 与 `achieved_coverage`，再核对 `target_cells`、`lit_cells`、`dark_cell_count`。`most` 或 `player_visibility` 成功仍可有暗格，不能改述为最低光全部达标。

`lighting_observation` 直接交付 `minimum_required`、`minimum_observed_block_light`、`dark_cells`（位置及方块光）、`sampled_cells`、`lit_cells`、`boundary_verified`。最低值为 -1 表示有未加载样本或没有样本，不能解释为实际负亮度。暗格和覆盖计数来自最近一次完整观察或复测；若中途卸载或取消，它们不一定是终态此刻的完整世界快照。

`build_receipts` 区分各轮放置成功、已放和剩余数量及原生未知，快速火把轮还携带 `rejected_sites`（每个被淘汰灯位的坐标、拒绝闸与原因）；`supply_receipts`、`supply_failure` 区分取料效果。`passes`、`requested_placements`、跨轮累计的 `placement_rejections` 解释做过多少轮、计入多少预算、哪些灯位在哪道闸被拒。`scope`、`boundary_source`、`radius`（若显式给定）和加载、前沿、勘测计数说明范围是怎样取得的。敏感格统计在 `protected_footprint_facts`。

失败可能包含 `failure_code`、`requires_decision`、`recovery_options`。典型原因包括找不到保护地标、区域未完全加载、无匹配地面、无可用光源、无安全灯位、取材失败、`placement_budget_reached`、`verified_coverage_not_reached`。这些不撤销已放下的灯；不要为证明“完成”而自动降低用户阈值或改成九成覆盖。

共享投影 `SemanticResultView` 对 `automatic_lighting` 和 `lighting_observation` 保留完整空间证据。其余父级字段仍走通用语义投影；需要核对结构时同时查看原始任务记录，不能假设所有内部字段都以同一键名出现在每层摘要中。

## 暂停、取消、死亡和重连

自动配置是内存会话状态：取消一次已经完成的配置请求不能替代 `action=disable`。主任务结束也不自动关闭已开启的随行服务；它仍受身体控制权和空闲条件限制。换 `LocalPlayer` 实例会清掉采样路线、放置记录及旧动作对象，但单纯 `bind` 保留开关；客户端进入 `bodyGone` 清理、断线或停服时 `reset` 才恢复默认关闭和阈值 8。死亡、重生和切维度是否经过这两种路径取决于实际客户端生命周期，不能统一描述为“任何换维度都必定保留”或“重生必定关闭”。

区域补光使用普通总任务的暂停、继续和取消入口。临时暂停保留内存任务及当前阶段；永久结束会清理勘测、施工和供料。已插灯具及实际取到的材料不回滚。重启恢复保存的是语义目标、已处理步骤与结果，未完成任务先暂停，明确继续后重新创建未完成的区域步骤并调查现有世界，不恢复旧准星、菜单、灯位列表或子轮动作对象。死亡及换身体按总任务交接规则处理，参见[任务生命周期](tasks.md)。

当前父任务的暂停和取消存在下述证据链缺口，不能据上述通用流程宣称子动作完全无遗漏。

## 已知实现差异

本节是静态复核发现，未在本轮修改行为，也未作实机复现。

| 差异与触发场景 | 当前证据及影响 |
| --- | --- |
| 区域公开适配器未严格执行全部类型与范围契约 | `AbilityAdapter.integer` 对 `radius`、`minimum_light` 先 `getAsInt` 再裁到范围；0 或越界可能被裁剪、小数可能截断、字符串数字可能被接收，对象等非原始值会用默认值。`coverage` 的对象或数组也被旧 `string` 辅助方法当成省略，最终采用 `all`。内部严格解析器已看不到原请求；`max_placements` 则直接到严格解析器。不能宣称所有非法补光参数都在计划期被拒绝 |
| 保护名称不等于完整区域 | 自动补光仅通过 `LandmarkProtection` 标记锚点格，且帧末运行没有重新套上主任务历史区域足迹。区域任务自身只扩成正负 4 格；正常 Intent 步骤还能继承先前实际测得的足迹，但不能据此保证自动服务也继承同一几何。完整区域保护承诺尚不成立 |
| 供料列表会扩展权限 | `SemanticMaterialSupplyCoordinator.resolveSources` 总会加入背包，开放库存时还加入合成；`inventory_only` 与显式 `storage` 冲突时仍开放库存。不得把现有 `allowed_sources` 描述为严格白名单 |
| 区域规划候选与快速火把可执行条件不同 | `groundCandidate` 接受部分可替换格及未种植耕地例外；`RoutineTorchPlacement.usable` 要求空气和结实支撑面。某些候选会在快速放置阶段一直不满足条件；中央偏好不能证明中央灯位可执行 |
| 区域父任务暂停没有逐层通知当前子任务 | `SemanticLightAreaCompanionTask` 未重写 `stop`；基类只暂停自己的导航并释放身体，没有向所持勘测、供料或火把子任务转发。`TorchLightingPass.stop` 能撤销帧末点击，但从父级暂停是否覆盖这些对象仍需修复和验证，不能凭直接子轮测试认定整链已覆盖 |
| 取消或超时会丢掉当前子轮的部分证据 | `cleanup` 调用 `buildChild.result(CANCELLED)` 后未把结果追加到父级 `build_receipts`，也未汇入本轮提交数量或未知状态。已经结清的火把及未知动作可能不出现在默认父回执；此前完整结束的轮次仍有记录 |

大区域还有明确的观察边界：连通调查结束和验收时要求全部样本仍加载；超过客户端同时可验证范围可能返回 `discovered_area_no_longer_loaded` 或 `verification_area_unloaded`。地面照明比率不是任意三维空间、所有楼层或游戏画面的可见性保证。

## 按问题找实现

| 想看哪一步 | 实现入口 |
| --- | --- |
| 模型读到哪些补光字段和挖矿提示 | [SemanticAbilityCatalog.describe / describeContract](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java) |
| 独立启停为什么不替换主任务 | [AutomaticLightingAdapter.parse / adapt](../../common/src/main/java/org/maiwithu/maicraft/intent/AutomaticLightingAdapter.java)、[IntentRuntime.isIndependentRequest / execute](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) |
| 自动服务怎样采样、让位、限频和报告 | [AutomaticLighting.advance / protection / snapshot / reset](../../common/src/main/java/org/maiwithu/maicraft/core/task/lighting/AutomaticLighting.java) |
| 补光在一个游戏刻的哪个位置执行 | [ClientRuntime.tick / bodyGone](../../common/src/main/java/org/maiwithu/maicraft/client/runtime/ClientRuntime.java)、[AfterNavigationAction.run](../../common/src/main/java/org/maiwithu/maicraft/task/AfterNavigationAction.java) |
| 火把为什么进副手，什么时候能借准星 | [OffhandTorchPlacer.prepare / place / idle](../../common/src/main/java/org/maiwithu/maicraft/core/task/lighting/OffhandTorchPlacer.java) |
| 灯位是否有支撑、可达、可见 | [RoutineTorchPlacement.find / usable](../../common/src/main/java/org/maiwithu/maicraft/core/task/lighting/RoutineTorchPlacement.java) |
| 如何证明原生火把实际放下 | [TorchLightingChain.confirmation](../../common/src/main/java/org/maiwithu/maicraft/core/task/chain/TorchLightingChain.java) |
| 地点目标如何变为内部区域任务 | [AbilityAdapter.lightArea](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java)、[PriorResultResolver.resolve](../../common/src/main/java/org/maiwithu/maicraft/intent/PriorResultResolver.java)、[SemanticLightAreaApi.newRecord](../../common/src/main/java/org/maiwithu/maicraft/core/tools/work/SemanticLightAreaApi.java) |
| 默认比例、阈值范围和内部未设预算标记 | [SemanticLightAreaTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/lighting/SemanticLightAreaTaskRecord.java) |
| 调查范围、选光源、候选规划、补料和验收 | [SemanticLightAreaCompanionTask.tickObserve / chooseSource / groundCandidate / tickSupply / tickVerify](../../common/src/main/java/org/maiwithu/maicraft/core/task/lighting/SemanticLightAreaCompanionTask.java) |
| 生存火把边走边放、跳过冗余候选和退出结算 | [TorchLightingPass.advance / couldImprove / stop / cleanup](../../common/src/main/java/org/maiwithu/maicraft/core/task/lighting/TorchLightingPass.java) |
| 材料策略为何开放这些来源 | [SemanticMaterialSupplyCoordinator.resolveSources / inventoryCount](../../common/src/main/java/org/maiwithu/maicraft/core/task/supply/SemanticMaterialSupplyCoordinator.java)及[物资获取](acquiring.md) |
| 地标点保护与已有区域足迹在哪里接入 | [LandmarkProtection.resolve](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/LandmarkProtection.java)、[IntentTask.withExplicitAreaProtection / stop](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java) |
| 暗格证据经过对外投影是否保留 | [SemanticResultView](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticResultView.java) |

## 已有验证入口

下列是现有回归源码的覆盖范围；本轮只检查文档、链接、提案和差异，没有运行测试或启动游戏。

- [AutomaticLightingContractTest](../../common/src/test/java/org/maiwithu/maicraft/intent/AutomaticLightingContractTest.java)：独立启停、默认关闭、请求幂等、非法自动配置、挖矿 tips。
- [AutomaticLightingTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/AutomaticLightingTest.java)：副手交换、主任务让位、原生确认等待、缺料、限频、停用后读取已提交回执及光照未知。
- [AreaLightingTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/AreaLightingTest.java)：默认全覆盖、最后一个暗格、已亮候选跳过、直接暂停火把子轮及恢复后的副手动作；不覆盖上表中的所有父任务生命周期缺口。
- [AuxiliaryActionTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/AuxiliaryActionTest.java)、[AuxiliaryNavigationTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/AuxiliaryNavigationTest.java)、[AuxiliaryLookReturnTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/AuxiliaryLookReturnTest.java)：同刻原生交互额度、辅助动作与导航方向、准星归还。
- [LightingRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/client/actor/LightingRegressionSuite.java) 对应 `:common:lightingRegression`；支撑和射线候选另见 [RoutineTorchPlacementTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/RoutineTorchPlacementTest.java)。这些离线入口不能代替整合包、服务端延迟和真实光传播的实机验收。
