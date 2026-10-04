# 读任务书，再提交、勾选或领奖

玩家可能已经收集了材料，想在 FTB Quests 里交付；也可能完成了任务，想领取一项奖励。`maicraft:quest_action` 处理的是一次明确的原生按钮操作。选择哪项任务、哪件奖励，以及接下来做什么，由外部模型决定。

读取任务书和操作任务书是两条路径。知识资源给出可见要求、进度与候选，读取不会消费。`execute` 接受 `quest_action` 目标后，角色才检查当前按钮条件，等待持久提交许可，发送一次原生消息并观察变化。任务成功可能仅表示请求正常交给了客户端，也可能表示原生状态本来就已满足；不能把它直接当作任务完成或物品到账。

本文只说明当前源码。本文档整理没有运行回归、启动游戏或进行实机验收。

## 想看哪一步，打开哪里

下列链接从本文件直接指向代码；方法名用于在文件中定位对应分支。

| 想确认的玩家行为 | 文件与方法 |
| --- | --- |
| 模型能填什么，规划时究竟检查了什么 | [QuestAbilityAdapter.validate / adapt / contract](../../common/src/main/java/org/maiwithu/maicraft/intent/QuestAbilityAdapter.java) |
| 编号、空值和选择 URI 为什么被拒绝 | [FtbQuestActionRequest.parse / id / string](../../common/src/main/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbQuestActionRequest.java) |
| 这个任务是否有可操作按钮，奖励是否属于它 | [FtbQuestActionTarget.resolve / visible](../../common/src/main/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbQuestActionTarget.java) |
| 等待、单次发送、观察和终态如何衔接 | [FtbQuestActionSession.tick / evidence](../../common/src/main/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbQuestActionSession.java) |
| 游戏里究竟发了哪个消息，何时读取背包 | [MinecraftFtbQuestActions.prepare / submit / observe / snapshot](../../common/src/main/java/org/maiwithu/maicraft/core/integration/ftbquests/MinecraftFtbQuestActions.java) |
| 玩家接管、死亡、临时自救怎样影响这次操作 | [QuestActionTask.tick / stop / result](../../common/src/main/java/org/maiwithu/maicraft/core/task/quests/QuestActionTask.java) |
| 同一刻能否再发一次，控制权是否仍有效 | [FtbQuestSubmission.submit](../../common/src/main/java/org/maiwithu/maicraft/client/actor/FtbQuestSubmission.java) → [DefaultLocalPlayerContext.claimMutation](../../common/src/main/java/org/maiwithu/maicraft/client/actor/DefaultLocalPlayerContext.java) |
| 父任务和单次消费怎样绑定，重启为何不重发 | [QuestActionTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/quests/QuestActionTaskRecord.java) → [NativeSubmissionBinding.barrier / identityGoal](../../common/src/main/java/org/maiwithu/maicraft/intent/NativeSubmissionBinding.java) → [NativeSubmissionJournal.prepare](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/NativeSubmissionJournal.java) |
| 物品变体、鼠标携带栈和净变化如何报告 | [FtbInventoryEvidence.capture / difference](../../common/src/main/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbInventoryEvidence.java) |
| 为什么不能读取旧服务器或隐藏任务 | [ReflectiveFtbQuestsAccess.snapshot / catalog](../../common/src/main/java/org/maiwithu/maicraft/core/integration/ftbquests/ReflectiveFtbQuestsAccess.java)、[FtbQuestSync.matches](../../common/src/main/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbQuestSync.java) |
| 从章节、任务、奖励候选取得可用引用 | [FtbQuestsKnowledgeSource.read](../../common/src/main/java/org/maiwithu/maicraft/mcp/knowledge/FtbQuestsKnowledgeSource.java)、[FtbQuestRewards.read](../../common/src/main/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbQuestRewards.java)、[FtbRewardTables.revision / read](../../common/src/main/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbRewardTables.java) |
| 已发生效果怎样进入总任务、通知和 MCP | [IntentTask.finishChild / stop / completionResult](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)、[SemanticResultView.sanitizeEntry](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticResultView.java)、[EmbeddedMcpService](../../common/src/main/java/org/maiwithu/maicraft/mcp/EmbeddedMcpService.java) |

## 找到正确对象

从 `maicraft://knowledge/ftbquests/index` 开始，或经 `perceive(view="knowledge", resource_uri=...)` 读取。索引中的 `quest_lists` 提供 `all`、`available`、`incomplete`、`completed`、`claimable` 列表；`operation_ability` 指向本能力。

- `available` 是未完成且 FTB 允许开始的任务，并不表示每个子任务都具有手动提交按钮。
- 任务页给出 `tasks[*].id`、条件和进度。`quest_id` 是父任务编号，`task_id` 是该任务的子任务编号。
- 奖励页给出根奖励编号、当前玩家的领取状态和候选 URI。`reward_id` 使用根奖励编号；奖池内部节点不是独立根奖励。
- 选定的列表和当前奖池候选完整返回；更深的嵌套奖池有直接 URI。隐藏内容仍按当前可见性过滤。历史 `offset` 链接是只读兼容入口，不是动作参数。
- 选择项 URI 包含当前表版本。表重排或叶子内容变化后，旧引用可能失效；不能自行猜一个序号或版本摘要。

读取正文没有取得提交或领奖授权。规划成功也只证明参数结构可接受，具体的模组接口、队伍同步、可见性和原生按钮条件要到执行时核对。

## 请求层级与参数

所有专属参数都在 `goal.parameters`。`goal.ability` 固定为 `maicraft:quest_action`；`outcome` 是描述，不能替代编号，也不会指定消费数量。

`goal.target` 只能省略或为 `null`，不能填坐标、地标或其他对象。`preferences` 省略或 `{}`，`constraints` 省略或 `[]`，`children` 省略或 `[]`。需要多个明确动作时由公共 `sequence` 能力编排，不能在本目标里塞点击脚本。

| 完整参数位置 | 类型、默认与范围 | 必填及互斥规则 |
| --- | --- | --- |
| `goal.parameters.operation` | 字符串，无默认；精确小写 `submit`、`confirm`、`claim`，不裁剪空格 | 始终必填；空串、省略、`null`、`false`、数字 `0` 均无效 |
| `goal.parameters.quest_id` | 字符串，无默认；16 位非全零十六进制，无 `0x` 前缀和空格，字母统一为大写 | 始终必填，取自当前可见任务；不是章节编号或 MaiCraft 任务 UUID |
| `goal.parameters.task_id` | 与 `quest_id` 相同的编号格式，无默认 | `submit` / `confirm` 必填；`claim` 必须完全省略，写 `null` 也算混用了参数 |
| `goal.parameters.reward_id` | 与 `quest_id` 相同的编号格式，无默认 | `claim` 必填；其他操作必须完全省略；目标必须是该父任务自己的根奖励 |
| `goal.parameters.choice_uri` | 可省略的字符串，无默认选项；直接复制当前根奖励的直接候选 URI | 只供 `claim` 的选择奖励使用；普通奖励必须省略。未领取选择奖励执行时要求有效引用；已领取选择奖励有提前结算分支 |

所有 ID 必须是 JSON 字符串；即使十六进制中没有字母，也不能改成 JSON 数字。全零字符串无效；包含前导零的其他合法 16 位编号有效。任何已出现字段的 `null` 或 `false` 都不代表“使用默认值”。未知参数也会被拒绝，因此没有 `count`、消费预算、`wait_ms`、`force`、`notify` 或自动重试开关。

选择 URI 的形状是 `maicraft://knowledge/ftbquests/quest/{大写任务号}/rewards/{大写根奖励号}/{index}~{revision}`。序号为 `0..999999999`，除 `0` 外不能有前导零；版本是 16 位小写十六进制。不能附加查询参数、片段或另一层子路径。解析阶段检查形状和两个编号；执行阶段再检查当前表版本和实际索引范围。

已经领取的选择奖励在通过任务/对象可访问性检查后，可以省略 `choice_uri`；若提供了语法正确但已经过期的 URI，也会在版本检查前直接结束。语法错误仍在规划阶段被拒绝，普通奖励仍不能夹带选择 URI。这种“已满足”不证明过去发放了本次想选的物品。

`execute.request_key` 是工具请求的外层字段，不放在 `goal.parameters`。它可省略或为 `null`；若提供则是 1..128 字符的非空字符串。要能在传输重试中找回原任务，就明确提供一个键并重复使用。`plan` 和 `execute` 的 `goal` / `plan_id` 二选一；已有计划 ID 必须来自工具实际返回。

## 完整计划示例与引用来源

下面是完整的 `plan` 参数 JSON，用于说明字段放置和结构校验。编号来自既有 [FtbQuestFixture](../../common/src/test/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbQuestFixture.java) 和 [FtbQuestActionTargetTest](../../common/src/test/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbQuestActionTargetTest.java)：父任务 `FEDCBA9876543210`、物品子任务 `0000000000000003`、勾选子任务 `0000000000000004`、普通奖励 `000000000000000A`。物品操作夹具明确把 `screenOnly` 设为 `false`。

这些是仓库已有夹具场景的编号，不是当前游戏的观察结果；本轮没有运行夹具。实际调用必须用当前资源返回的同类编号替换，不能拿示例 ID 去猜服务器对象。没有在此编造可执行的选择版本摘要或计划 UUID。

向已观察到的消耗型物品任务提交一次：

```json
{
  "goal": {
    "ability": "maicraft:quest_action",
    "outcome": "向已观察到的消耗型物品任务提交一次",
    "target": null,
    "parameters": {
      "operation": "submit",
      "quest_id": "FEDCBA9876543210",
      "task_id": "0000000000000003"
    },
    "preferences": {},
    "constraints": [],
    "children": []
  }
}
```

确认手动勾选任务，不能把这里的子任务编号换成观察或击杀任务来跳过玩法：

```json
{
  "goal": {
    "ability": "maicraft:quest_action",
    "outcome": "确认已观察到的手动勾选任务",
    "parameters": {
      "operation": "confirm",
      "quest_id": "FEDCBA9876543210",
      "task_id": "0000000000000004"
    }
  }
}
```

领取一项普通根奖励，互斥的 `task_id` 和不适用的 `choice_uri` 都省略：

```json
{
  "goal": {
    "ability": "maicraft:quest_action",
    "outcome": "领取已观察到的普通根奖励一次",
    "parameters": {
      "operation": "claim",
      "quest_id": "FEDCBA9876543210",
      "reward_id": "000000000000000A"
    }
  }
}
```

选择奖励的 JSON 必须绑定真实观察。读取该根奖励后，由模型选定 `table.entries` 中的一项，将其原样 `uri` 填入 `goal.parameters.choice_uri`；`reward_id` 仍是根奖励号，不是候选的 `id`。例如宿主可以从已经读取的三个对象构造完整参数，下面只展示数据绑定，不发送请求：

```python
plan_arguments = {
    "goal": {
        "ability": "maicraft:quest_action",
        "outcome": "领取已选中的奖励一次",
        "parameters": {
            "operation": "claim",
            "quest_id": observed_quest["id"],
            "reward_id": observed_root_reward["id"],
            "choice_uri": selected_direct_option["uri"],
        },
    }
}
```

`plan` 登记计划并返回真实计划编号；执行时使用那个 `plan_id`，或直接向 `execute` 传同一个 `goal`，二者不能同时提供。通信超时先按原 `request_key` 查询已经受理的任务，不要换键创建一次新的消费来“试试看”。

## 玩家实际经历的顺序

1. `QuestAbilityAdapter` 做结构校验并创建 `QuestActionTaskRecord`。此时没有打开任务书界面，也没有发出 FTB 消息。
2. `QuestActionTask` 取得当前身体上下文并释放移动输入。本刻操作机会已被占用时等待下一刻；真正的控制权检查在准备与发送入口完成。任务单使用 `NO_DEADLINE`，没有本能力可配置的整体准备超时。
3. `MinecraftFtbQuestActions.prepare` 核对玩家 UUID、连接、有效任务书及其原生同步证明、非占位队伍数据和活着的玩家。随后 `FtbQuestActionTarget.resolve` 检查任务可见性、详情门槛、子项归属、按钮类型及领取条件。
4. 原生状态已经完成/领取时直接结算，不进入持久预约，也不构造要发送的消息。这个分支之前仍有类型和可访问性检查，不能用“已完成”绕过不支持的动作类型。
5. 要产生动作时先确认服务器接收通道和消息构造器，保存操作前事实。父 `IntentTask` 通过 `NativeSubmissionBinding` 先保存父任务检查点，再把唯一预约写入 `NativeSubmissionJournal` 的 SQLite 记录。等待期间不会发包。
6. 屏障返回成功后重新准备一次。连接、任务书对象、身体对象、玩家 UUID 或队伍 UUID 必须仍属于同一作用范围。进度已经被玩家或队友满足时，仍会直接结算；否则 `FtbQuestSubmission` 先占用本刻操作机会，再交给 Architectury 的原生客户端通道。
7. 发包后只观察状态。FTB 信号相对操作前有变化，且本次 FTB/库存与上次观察不同时，把收尾时点设为当前单调时间加 250 毫秒；达到该时点或发送起 3000 毫秒就结束观察。到点不等于服务端拒绝，也不会自动补发。

| 操作 | 原生消息 | 原生规则与当前边界 |
| --- | --- | --- |
| `submit` | `SubmitTaskMessage` | 物品必须为消费型且非任务屏幕专用；XP 可提交现有经验；自定义任务要求已启用按钮。不会预判资源是否足够完成，也不会自动补料 |
| `confirm` | `SubmitTaskMessage` | 只接受 `ftbquests:checkmark`，FTB 自己处理进度和任务顺序 |
| 普通 `claim` | `ClaimRewardMessage` | 根奖励必须可见，未领取时须原生允许领取；通知参数固定为 `true`，没有对应公开开关 |
| 选择 `claim` | `ClaimChoiceRewardMessage` | 未领取时校验直接候选的当前版本与索引，发送根奖励号和原生序号；不改选、不随机替模型选择 |

随机、战利品、全表、命令或脚本奖励仍由 FTB 的领取过程结算。代码不直接改进度、背包、经验或世界，也不负责证明任意奖励脚本都执行成功。

## 回执应该怎样读

以下位置针对原生子任务的 `TaskResult.data`，不能直接套到父任务的终态 `result.data`。`completeStep` 保存了完整原件，但当前 [IntentTask.completionResult](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java) 对普通成功步骤只生成摘要，尚未把 `quest_action` 及其未知标记放进该终态结果。这是现存的信息交付缺口，本轮没有改行为。

[TaskView.status](../../common/src/main/java/org/maiwithu/maicraft/mcp/TaskView.java) 在终态默认返回最后一步的 `last_step.result`；若最后一步恰是任务书动作，可从 `last_step.result.data.quest_action` 读取。更早的 FTB 步骤仍存于 [taskSnapshot](../../common/src/main/java/org/maiwithu/maicraft/mcp/MaiCraftRuntimeFacade.java) 的 `completed_steps`；使用实际返回的任务 UUID，经 `task(action="get", path="/completed_steps/{数组索引}/result/data/quest_action")` 可直接定位，不要为了找回回执再次执行动作。[IntentStateCodec.encodeTask](../../common/src/main/java/org/maiwithu/maicraft/intent/persistence/IntentStateCodec.java) 也保留这些完整步骤结果。

| 字段 | 能证明什么 |
| --- | --- |
| `quest_action.request` | 本次规范化后的操作、父任务号及对象号；不是服务器 ACK |
| `quest_action.submission_status` | `not_submitted`、`already_satisfied`、`submitted_to_client`、`submission_uncertain` 或 `reservation_unavailable`；反映本地状态阶段 |
| `submission_attempted` / `submitted_to_client` | 前者在进入发送调用前置真，后者只在该调用正常返回后置真；两者不同 |
| `ftb_update_observed` | 观察到了 FTB 完成次数、进度、完成标记或领取记录等变化；不证明变化来自这一次请求 |
| `before` / `after` | 存在时给出观察时间、玩家/队伍 ID、FTB 状态、完整非空库存与身体状态；观察失败时可能没有 `after` |
| `before.selected_task` / `before.selected_reward` | 当次选定内容；辅助定义编码失败会显式标为不可用，不能补造内容。已满足的领奖不会反推历史选项 |
| `observed_updates` | 确实采样到的中间变化，避免任务重置后只剩最后的零进度；不是网络包完整日志 |
| `inventory_changes.item_count_changes` | 按 `item_id` 汇总的净数量前后值与差值；组件或变体仍应看完整快照 |
| `inventory_changes.contents_changed` | 两份库存观察不同，不是对某种奖励副作用的独占归因 |
| `observation_problem` / `server_acknowledgement` / `attribution` | 观察失效原因、缺少逐请求确认，以及同期或队友行为的归因限制 |
| `outcome_uncertain` / `mechanical_retry_allowed` | 外层结果确定性及机械重试提示。进入预约阶段或尝试发包后不会放开机械重试；取消同样不允许机械重试 |

库存包含背包、装备及鼠标携带栈中的非空物品；相同可编码栈合并数量，组件编码失败的项保留真实 ID、名称和数量并标记未知。世界掉落物和任意服务端奖励副作用不在这里验证。`reported_total_experience` 是同步的玩家字段，不能只靠它替代等级、经验条或 FTB 进度的共同判断。

子任务 `success=true` 的观察结束结果仍可能带 `outcome_uncertain=true`。同样，`outcome_uncertain=false` 或 `ftb_update_observed=true` 也不能单独证明某个奖励已到账；应分别看具体领取标记、子任务完成标记和实际库存变化。通用语义整理会保留传入的 `quest_action` 原件，但不能补回父任务终态封装时没有放进去的字段。

## 暂停、取消与恢复

| 场景 | 当前实现 |
| --- | --- |
| 临时抢占 / `PREEMPTED` | 保留同一个子任务和会话，不再次预约或发包；单调时钟继续计时，恢复后没有重新赠送 3 秒窗口 |
| 取消或被替换 | 尽力再观察一次，保留已发出状态及已见变化，进入取消终态；已发出的消息无法撤回 |
| 发送中抛错 | 标记 `submission_uncertain`，尽力取回观察并保留，不用新编号重发；正常返回标记 `sent` 可能仍为假 |
| 发出后观察失败 | 可以结束为 `SUCCESS`，同时记录未知项及观察问题；成功范围仍只到已提交客户端 |
| 死亡 / 身体对象替换 | 外层停止继续操作；作用范围检查避免把新身体尚未同步的空背包当成旧身体丢失。无法比较时保留已有观察 |
| 断线、换世界、换队伍、重载任务书 | 身份或同步证明失效时停止当前作用范围的操作/观察，不拿另一份任务书的数据确认旧请求 |
| 进程重启 | 未结束父任务恢复为待显式继续的状态，旧 Java 会话和快照不是新现场。重新准备后若状态已满足可结算；否则已有预约阻止自动再发 |

操作预约使用 `ftb-quest` 命名空间、父任务身份、相同动作在步骤中的出现次数，以及规范化后的动作和原生对象 ID。`outcome`、编号字母大小写和整个 `choice_uri` 都不产生新的消费身份；同一父步骤连改选另一个候选也不能用来绕过旧预约。明确写出的第二个步骤或新任务是另一笔意图，必须先核对已发生效果。

`reservation_unavailable` 不一定表示某条消息已发出：`reserved` 在进入持久屏障时就置真，父检查点失败、预约冲突或之后的重新准备失败都可能走这个标签。应同时读 `submission_attempted`、当前事实和错误文案。预约本身也不是服务端接受证明。

## 模组条件与已知边界

- 能力注册是静态的；发现到 `quest_action` 不证明已安装 FTB。读索引的 `not_installed` 与动作执行失败的 `submission_status` 不是同一套码。
- 客户端需要当前版本的 FTB Quests、依赖库和可反射的接口；服务器需要同步任务书/队伍并开放对应接收通道。初始实现对照的是 Minecraft 1.21.1 的 FTB 接口，不能据此宣称任意版本兼容。
- 按钮机制是类型白名单；未知扩展任务、关闭按钮的自定义任务、观察/击杀任务和任务屏幕目标没有通用“提交即可完成”的后门。
- 服务端脚本的实际判定可能不在客户端，任意命令/脚本效果也没有通用完成校验。原生已经允许的按钮仍交给 FTB 判定，辅助定义解析失败不等于原生按钮一定不能执行。
- 当前父任务的普通成功终态与 `completed` 事件只携带步骤摘要；对话只消费这部分数据时，可能看不到 FTB 子任务的消费事实和未知标记。默认 `task get` 的 `last_step` 能补足最后一步，不能替代所有较早步骤的默认交付。完整步骤原件仍可按上面的直达路径读取；该缺口列为高优先级 finding，本轮未修复。
- `ftbChanged` 优先识别共享完成次数变化；队友导致次数变化而指定奖励仍未领取时，也可能让 `outcome_uncertain` 变为假。已有归因说明，调用者仍须检查具体领取记录；该确定性粒度作为本轮 finding 保留，未改判定。
- 当前 250 毫秒收尾点只在本次 FTB 状态仍偏离基线时推进。若进度/领取记录回到基线且完成次数未变，随后库存更新未必重新延长观察；这也是本轮 finding，不能把当前实现写成“最后一次库存变化后必等 250 毫秒”。
- 本轮没有实机证据来证明原生消息在某个具体整合包中已成功消费或发奖。

## 已有验证入口与本轮检查

可维护的现有入口如下。本轮只对照源码，没有新增、运行或宣称通过这些测试。

| 入口 | 覆盖内容 |
| --- | --- |
| [FtbQuestActionTargetTest](../../common/src/test/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbQuestActionTargetTest.java) | 普通按钮范围、类型限制、隐藏奖励、原生领取许可、过期选择引用与已领取分支 |
| [FtbQuestActionSessionTest](../../common/src/test/java/org/maiwithu/maicraft/core/integration/ftbquests/FtbQuestActionSessionTest.java) | 持久许可等待、单次发送、部分进度、无变化收尾、发送异常、重置记录、取消和切换作用范围 |
| [FtbQuestSubmissionTest](../../common/src/test/java/org/maiwithu/maicraft/client/actor/FtbQuestSubmissionTest.java) | 真实身体许可、旧上下文、同刻配额及发送异常后的配额保留 |
| [QuestAbilityContractTest](../../common/src/test/java/org/maiwithu/maicraft/intent/QuestAbilityContractTest.java) | 能力入口、参数校验、持久身份、真实预约去重及语义/注意流中的事实保留 |
| [FtbQuestsKnowledgeSourceTest](../../common/src/test/java/org/maiwithu/maicraft/mcp/knowledge/FtbQuestsKnowledgeSourceTest.java)、[FtbReadOnlyResourcesTest](../../common/src/test/java/org/maiwithu/maicraft/mcp/knowledge/FtbReadOnlyResourcesTest.java)、[KnowledgeHttpTest](../../common/src/test/java/org/maiwithu/maicraft/mcp/KnowledgeHttpTest.java) | 可见性、引用、完整范围读取及 Resource/工具双入口 |

操作相关入口已登记在 [GuiRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/client/actor/GuiRegressionSuite.java)，只读入口在 [KnowledgeRegressionSuite](../../common/src/test/java/org/maiwithu/maicraft/mcp/KnowledgeRegressionSuite.java)；对应 [common/build.gradle](../../common/build.gradle) 的 `:common:guiRegression` 与 `:common:knowledgeRegression`。是否运行这些回归和实机验证，应遵从当前请求的授权。

本轮只做契约 JSON、文档 JSON 示例、相对链接、提案定位及 diff 静态检查；验证记录保存在临时审阅目录，未把静态检查等同于 Java 编译、离线回归或实机验收。
