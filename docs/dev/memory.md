# 记住地点：把当前位置变成以后可引用的名字

玩家说“记住这里是营地”，`maicraft:remember_place` 会登记一个名字、一个方块坐标和区域类型。之后的旅行、施工或保护请求可以引用这个名字。它不会走到目标处、打开箱子、修改世界或验证机器；指定坐标时，也不会确认该处已加载、可通行或属于玩家。

记忆步骤本身没有原生点击，但 `execute` 仍走普通语义任务的唯一角色任务槽，并可替换当前任务。它不是与施工并行的独立配置请求。`plan` 只编译目标；`current_place` 在真正执行记忆步骤时才取角色所在的方块格，不冻结计划创建时的位置。

## 想看哪一步，打开哪里

| 想检查的问题 | 入口和关键方法 |
| --- | --- |
| LLM 会看到哪些参数 | [SemanticAbilityCatalog.describe](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticAbilityCatalog.java) 的 `maicraft:remember_place` 分支 |
| JSON 层级、目标形状是否合法 | [PublicToolCatalog.validateGoal / validateTarget](../../common/src/main/java/org/maiwithu/maicraft/mcp/PublicToolCatalog.java)、[PublicTargetContract.validate](../../common/src/main/java/org/maiwithu/maicraft/mcp/PublicTargetContract.java)、[SemanticGoalContract.validate](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticGoalContract.java) |
| 名字、区域类型和地点怎样解析 | [AbilityAdapter.remember / rememberPosition](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java) |
| 何时真正写入、怎样完成步骤 | [IntentTask.begin](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java) 的 `IntentAction.Remember` 分支；[IntentRuntime.remember](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) |
| 同名覆盖、容量和其他地点来源 | [IntentRuntime.landmark / normalizeLabel / trimOldest](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentRuntime.java) |
| 查询为何不返回坐标 | [MaiCraftRuntimeFacade.landmarks](../../common/src/main/java/org/maiwithu/maicraft/mcp/MaiCraftRuntimeFacade.java) |
| 保存与重启恢复 | [IntentStateCodec.encode / decode](../../common/src/main/java/org/maiwithu/maicraft/intent/persistence/IntentStateCodec.java)、[IntentStateStore.load / saveAsync](../../common/src/main/java/org/maiwithu/maicraft/intent/persistence/IntentStateStore.java)、[StateIdentity.resolve](../../common/src/main/java/org/maiwithu/maicraft/intent/persistence/StateIdentity.java) |
| 被保护的是一格还是区域 | [LandmarkProtection.resolve / run](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/LandmarkProtection.java)、[EntitySemanticSafety.protectedAreaEvidence / insideLandmark](../../common/src/main/java/org/maiwithu/maicraft/core/task/entity/EntitySemanticSafety.java) |

## 请求字段与实际默认行为

能力参数写在 `goal.parameters`，地点选择写在并列的 `goal.target`。`outcome` 是请求说明，不会被拿来代替地点名字。能力没有自己的偏好项或硬约束；通常省略 `preferences`、`constraints`、`children`，或分别使用 `{}`、`[]`、`[]`。这些对象/数组显式写成 `null` 会被公开入口拒绝。

下表的字符串长度按 Java `String.length()` 计数，即 UTF-16 代码单元。

| 完整字段 | 类型、单位与当前规则 |
| --- | --- |
| `goal.ability` | 固定为 `maicraft:remember_place`。 |
| `goal.outcome` | 字符串，公开入口要求长度 1–500；描述要记住什么，不替代结构化字段。 |
| `goal.parameters.label` | 应为非空白字符串，是本次写入的名字；省略或 `null` 时回退到 `goal.target.label`。两处都没有可用名字时进入待决策。空字符串或纯空白字符串不会继续回退，而是要求替换目标。建议使用 1–160 字符，便于随后作为 `target.label` 引用；这不是该参数当前已实现的长度校验。 |
| `goal.parameters.area_role` | 精确字符串 `ordinary` 或 `managed_settlement`；省略或 `null` 为 `ordinary`。大小写或首尾空白不同也不会被归一为合法枚举。只有主人明确把该处声明为管理中的基地、城市或聚居地时，才填写后者。 |
| `goal.target` | 可省略或为 `null`，等同使用当前角色方块位置。若提供对象，必须有 `kind`；不是填在 `parameters` 内。 |
| `goal.target.kind` | 本能力接受 `current_place`、`coordinates`、`landmark`、`area`。`area` 在这里也只解析一个已有地标点，没有尺寸、半径或边界。 |
| `goal.target.label` | 字符串长度 1–160，空字符串不合法。`landmark`、`area` 必须提供它作为来源名字，不能用 `parameters.label` 代替来源。其他两种目标可省略或设为 `null`，也可用它提供写入名字的后备值。 |
| `goal.target.position` | 仅 `coordinates` 必须提供非空对象；其他目标不能带非空 `position`。 |
| `goal.target.position.x / y / z` | 三项全部必填，单位为绝对方块坐标，必须为 32 位整数（−2147483648 至 2147483647）。`0` 是实际坐标；省略、`null`、小数或 `false` 都不是默认位置。入口不据此检查世界边界、建筑高度、区块加载或可达性。 |
| `goal.target.position.dimension` | 可省略或 `null`，若填写须为维度资源 ID，例如当前观察明确给出的 `minecraft:overworld`。当前代码会把省略值保留为空，不自动补当前维度；固定坐标记忆应显式填写已知维度，详见下文边界。 |
| `goal.target.relation` | 通用目标对象允许省略、`null` 或长度 1–120 的字符串，但本能力不读取它；它不会把名字转换成“附近”、偏移或区域关系。 |

通用运行时另识别 `auto_respawn`、`recover_after_death`；它们是死亡处理授权，不是位置选择或区域字段。本能力不会通过这些开关补充名字、维度或保护范围，参见 [GameplayAttentionMonitor](../../common/src/main/java/org/maiwithu/maicraft/client/runtime/GameplayAttentionMonitor.java)。

当前 `parameters` 的值类型检查存在宽松路径，不能把下面行为当作推荐的调用方式：

- `label: 0`、`label: false` 会被适配器转换成名字 `"0"`、`"false"`；对象或数组会被当成缺省值，可能继续回退到 `target.label`。
- `area_role: 0`、`area_role: false` 会转成非法枚举字符串并进入待决策；对象或数组却会被当成缺省值并使用 `ordinary`。
- `goal.parameters` 整体省略会补 `{}`，整体为 `null` 或非对象则在公开入口被拒绝。参数值为 `null` 与整个参数对象为 `null` 不是同一件事。

## 可直接解析的计划示例

以下代码块都是 MCP `plan` 的完整参数。计划本身不登记地标；检查计划后，把返回的真实 `plan_id` 交给 `execute`，或把同一份 `goal` 直接提交给 `execute`。不要编造计划编号，也不要仅因计划编译成功就认为来源地标已经解析成功。

玩家要求记住实际执行时所在的营地，可省略 `target` 和区域类型：

```json
{
  "goal": {
    "ability": "maicraft:remember_place",
    "outcome": "把当前位置记为营地",
    "parameters": {"label": "营地"}
  }
}
```

如果主人明确声明这里是受管理的聚居地，使用显式类型；名字含“基地”或“村庄”本身不产生该类型：

```json
{
  "goal": {
    "ability": "maicraft:remember_place",
    "outcome": "登记主人指定的管理聚居地",
    "target": {"kind": "current_place"},
    "parameters": {"label": "管理基地", "area_role": "managed_settlement"}
  }
}
```

第一个记忆目标实际完成、已有 `营地` 后，可给同一位置登记另一个名字。来源放在 `target.label`，新名字放在 `parameters.label`；区域类型按本次参数决定，不继承来源类型：

```json
{
  "goal": {
    "ability": "maicraft:remember_place",
    "outcome": "把已登记营地的位置另记为补给点",
    "target": {"kind": "landmark", "label": "营地"},
    "parameters": {"label": "补给点", "area_role": "ordinary"}
  }
}
```

需要核对登记名字和维度时，使用 `perceive` 的完整参数 `{"view":"landmarks"}`。返回的 `landmarks[]` 包含 `label`、`area_role`、可用时的 `dimension` 和 `available_here`；不返回精确坐标。`available_here` 只比较维度，不证明已加载、到达、可通行或有权取用附近容器。

## 实际执行顺序与结果

1. 公开入口检查请求形状，语义校验检查能力名、参数名和目标种类。`plan` 不调用记忆适配器写入地点；`execute` 先绑定当前游戏世界并检查旧检查点能否恢复，再登记总任务。
2. 步骤启动时，`AbilityAdapter.remember` 依次解析写入名字、区域类型和位置。名字缺失或类型枚举非法时提供 `replace_goal`、`cancel`；来源地标解析不到时还提供 `skip`。未知参数名和不支持的目标种类会在更早的校验阶段拒绝。
3. `rememberPosition` 对当前地点读取角色 `blockPosition()`，对坐标直接取所给位置，对 `landmark`/`area` 调用 `IntentRuntime.landmark`。后者先查手工地标，再按适用标签查询跑图记忆或机器目录；没有找到时不把当前位置当成来源。
4. `IntentTask.begin` 处理 `IntentAction.Remember`：写入运行时地标表、保留本步骤的内部位置，再完成步骤。步骤结果消息为 `remembered <label>`，数据包含 `label` 和 `area_role`。坐标留给内部步骤引用，不作为公开记忆结果返回；总任务仍使用统一的步骤与终态包装。
5. 登记把世界检查点标成待保存。正常 tick 约每五秒检查脏状态并异步写入 SQLite，断线、死亡交接和退出也有检查点路径。步骤成功和 `completed` 通知没有等待此处的磁盘保存回执，因此只能直接证明运行时登记完成，不能单凭该结果断言已经完成持久化提交。

`execute.accepted=true` 仅表示接单。应继续读取该任务的状态或 `next_attention`，在待决策时从当前回执复制真实 `decision_id` 和已列出的选项。`replace_goal` 需要 `answer.details.goal` 中的完整替代目标；不要用另一能力的字段修补本能力。仅在当前决策确实列出 `skip` 时跳过，跳过结果会保留 `skipped` 事实，地点不会写入。

## 同名、保护和跨世界的含义

手工地标以去掉首尾空白、按 `Locale.ROOT` 转小写后的名字为键。同键登记直接覆盖位置与 `area_role`，没有“同名确认”或 `replace` 参数。重新登记管理聚居地时若省略 `area_role`，会变为 `ordinary`；给已有位置取别名也只复制位置，不复制区域类型。

`managed_settlement` 是下游判断的区域背景。实体安全判断还要求调用者选中对应 `protected_labels`、实体位于地标水平十二格内，并按具体意图核对当前已加载的物理围护证据。它不是声明了一块任意大小的保护区，也不证明实体已被围住。`LandmarkProtection` 对取料/施工传入的保护名字则只标记地标那一格；完整建筑范围由其他任务提供。给箱子起名不会自动授权开箱或取物。

手工地标属于当前世界检查点：单人身份来自存档路径，多人身份目前来自服务器地址，不包含玩家账号，也不能区分同一服务器地址更换后的世界。它与按玩家区分的机器档案不是同一种身份键。`perceive(view="landmarks")` 只列手工登记项；跑图和机器标签的补充解析不等于已把这些来源全部复制进手工地标表。

## 暂停、取消、死亡和恢复

| 发生时机 | 当前行为 |
| --- | --- |
| 记忆步骤尚未执行时暂停 | 调度器不推进该步骤；之后恢复时，`current_place` 取恢复执行时的位置。 |
| 写入前取消，或明确跳过待决策步骤 | 不调用 `remember`，不会登记该地点。 |
| 地标已经写入，之后暂停或取消总任务 | 已登记效果保留，不回滚到覆盖前的同名地标；能力没有公开删除或撤销参数。 |
| 死亡、断线或换世界 | 运行时通过检查点交接保留已接受进度；换世界后清理旧运行时表并读取对应世界的记忆。不要把旧身体、按键或路线当成恢复内容。 |
| 重启后有未完成任务 | 从已保存检查点恢复的任务先暂停；已经完成的记忆步骤随步骤进度保留，尚未执行的当前地点步骤在真正继续时重新解析位置。死亡是否自动复活另由显式运行时授权决定。 |
| 旧检查点不可读、版本/预算不兼容 | 保留旧记录并报告恢复受阻，拒绝用新任务或新地标覆盖空状态；单纯重发同一记忆目标不是恢复办法。 |

地标随 `IntentStateCodec` 的 `landmarks` 数组进入游戏目录 `config/maicraft/memory.sqlite` 的 `state` 分区；旧 JSON 由存储层兼容导入。范围、保存回执和迁移细节见[世界身份与检查点](../architecture/04-mechanisms.md#4-世界身份绑定与检查点)。这项能力不依赖 Create、AE2 或可选服务端模组，但执行仍需要当前本地玩家与世界，以及普通语义任务的控制权处理。

## 已知边界与检查入口

以下是源码中仍存在的行为，不代表本轮已经修复：

- **空维度坐标**：省略 `target.position.dimension` 会保存空维度，查询可能在多个维度都显示 `available_here=true`。`constructionAnchor` 对已有位置的维度直接调用 `equals`，这样的名字再用作工地锚点存在空指针路径。使用实际已知的明确维度可以避开此路径。
- **标签长度与持久化不一致**：`parameters.label` 没有专属的长度拒绝；公开 `target.label` 最长 160 字符，检查点的 `bounded` 又会把名字截到 4096 个 UTF-16 代码单元。过长名字可能在登记成功后无法作为公开目标引用，或在重启后变成另一名字。
- **容量淘汰**：手工地标最多 256 个。新增第 257 个不同规范化名字时直接删除插入顺序最早的项；覆盖同名不把它移到队尾，步骤结果也没有列出被淘汰项。
- **宽松类型与无效关系**：参数原始类型的隐式转换、非字符串区域类型回退，以及不被读取的 `target.relation`，见参数表。不能把这些字段表面上被接收当成其业务含义已执行。
- **完成不等于落盘**：地标登记成功先于后台保存确认；异常退出可能丢失尚未落盘的变化，普通成功回执没有单独的地标持久化确认字段。
- **发现元数据与调度边界不同**：[SemanticAbilityAvailability](../../common/src/main/java/org/maiwithu/maicraft/mcp/SemanticAbilityAvailability.java) 把该能力归入 `requested_read_or_review`，但实际 `execute` 仍请求接管并进入普通任务槽。不能据此推断它能与当前施工并行登记。

| 已有验证入口 | 已有检查覆盖什么 |
| --- | --- |
| [SequenceSkipTest](../../common/src/test/java/org/maiwithu/maicraft/intent/SequenceSkipTest.java)；`:common:attentionRegression` | 来源地标缺失时待决策；跳过不生成地点，不伪造成功。 |
| [PriorResultResolverTest](../../common/src/test/java/org/maiwithu/maicraft/intent/PriorResultResolverTest.java)；`:common:attentionRegression` | 后续步骤使用已保留的内部位置，旧回执兼容路径。 |
| [IntentRecoveryBudgetTest](../../common/src/test/java/org/maiwithu/maicraft/intent/persistence/IntentRecoveryBudgetTest.java)；`:common:buildingBudgetRegression` | 地标和任务恢复受阻时保留旧检查点，恢复兼容设置后找回原身份。 |
| [IntentStateStoreTest](../../common/src/test/java/org/maiwithu/maicraft/intent/persistence/IntentStateStoreTest.java)、[MemoryRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/intent/persistence/MemoryRegressionSuite.java)；`:common:memoryRegression` | 快照与真实存储的边界、后台保存、重启恢复和 SQLite 迁移。 |
| [AcquisitionProtectionTest](../../common/src/test/java/org/maiwithu/maicraft/core/task/acquire/AcquisitionProtectionTest.java)；`:common:navigationRegression` | 保护名字解析、明确的一格范围和不同维度的处理。 |

本轮只做源码对照、说明与注释整理，以及 JSON/本地链接/diff 静态检查；没有新增或执行测试，也没有实机、Luna 或重启验收。上述入口是后续验证导航，不能把这些列表当成本轮运行结果。
