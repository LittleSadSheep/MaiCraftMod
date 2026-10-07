# 一件任务怎样开始和结束

以“去仓库拿木头，然后回来建房子”为例。接到这句话时，角色并没有立刻开始点箱子。先要登记目标，等到身体可以交给自动任务，再按眼前情况做当前的一步。

## 先分清三张单子

| 对象 | 记什么 | 谁推进它 |
| --- | --- | --- |
| `Goal` | 玩家想完成什么，有哪些限制 | 本身不执行 |
| `IntentTaskRecord` | 总目标、步骤、已完成结果、问题和暂停原因 | `IntentTask` |
| 具体 `TaskRecord` | 本次移动、取物、放置等工作的输入和期限 | 对应具体 `Task` |

总任务留在调度器的“当前任务”槽里。它创建的取料、移动等子任务由总任务自己推进，不再次占用全局当前槽，否则“去取材料”会把“造房子”顶掉。

实现入口：[IntentTask.beginTool / beginNative](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTask.java)、[TaskDispatch.captureNext](../../common/src/main/java/org/maiwithu/maicraft/task/TaskDispatch.java)。

后一步说“去前面找到的营地”时，由 [PriorResultResolver](../../common/src/main/java/org/maiwithu/maicraft/intent/PriorResultResolver.java) 读取已完成步骤的内部位置证据。多个地点会根据关系文字匹配；无法区分时保留未知，让能力适配器继续处理。失败步骤不会成为后续位置依据。

## 接单不等于完成

1. `MaiCraftRuntimeFacade` 把请求切到客户端线程，检查玩家和世界是否可用。
2. `IntentRuntime` 核对当前世界的任务记忆，并检查公开目标。
3. 新目标生成 `IntentTaskRecord`。普通执行进入当前任务槽；只读建筑设计走独立的设计处理分支。
4. 后续游戏刻继续推进工作。真正完成后才生成终态，发布 Attention 通知。

`plan` 更早结束：它只展开组合目标、登记步骤清单。此时并没有算出“保证能到达”的路径，也没有证明材料已经足够。

### 三种编号不要混用

- `request_key`：调用方给同一次提交起的稳定名字，用来识别网络重试。
- `task_id`：对外总任务的 UUID，后续查询、暂停和取消使用它。
- `t42` 这样的短编号：内部任务单编号，供身体调度器及旧内部工具使用。

相同 `request_key` 应拿回原任务，而不是另开一件活。已经完成、正在暂停或正在等待回答的原任务，都不能因为网络重试重新取得玩家控制权。

任务表最多保留 256 条记录。接新目标前可以淘汰最旧的已结束记录；如果全是未完成的任务，就先拒绝接单，直到用户明确取消不再需要的旧事。保存端也会拒绝超量快照，不能只保存前半部分再宣称成功。

### 查询只投影需要处理的事实

[TaskView](../../common/src/main/java/org/maiwithu/maicraft/mcp/TaskView.java) 给普通查询和 Attention 提供当前状态、待答问题、消费限制及终态摘要。执行中的旧步骤结果不会每次重放；`retained_attempt_count` 是当前保留的尝试数，历史本身仍在任务单里。`task(get, path=...)` 通过 [JsonReadback](../../common/src/main/java/org/maiwithu/maicraft/mcp/JsonReadback.java) 逐项读取完整快照；投影不能修改任务单或调用执行器的 `result`。

失败结算通过 `RecoveryKnowledge` 从同一份效果账本、缺料需求和现有观察生成 `recovery_options` 中的只读资料选项。`evidence_fields` 定位已报告效果、未完成部分、未知项和现场；`knowledge` 与 `ability_contracts` 提供可直接用于 `perceive` 的 `read_arguments`。原生恢复选项及风险原样保留，提示不执行动作、不扩大许可，也不把知识缺失解释为设计失败。新增提示随尝试、终态快照和 Attention 一起保存；默认失败查询保留完整 `completed_effects`，内部重复机器几何仍用现有投影整理，避免模型为确定此前消耗而再展开历史。

[PlanView](../../common/src/main/java/org/maiwithu/maicraft/mcp/PlanView.java) 避免在编译回执中复印原蓝图。`plan(plan_id=..., path=...)` 恢复保存的输入，明确标记没有重新验证现场；执行仍走原来的材料、场地和原生动作检查。

[ResponseArchive](../../common/src/main/java/org/maiwithu/maicraft/mcp/ResponseArchive.java) 处理其余超大载荷，把原始数据压缩后冻结到服务拥有的临时 SQLite 中，并按压缩字节计算容量。页面保留对象键、数组索引和连续文本偏移，省略值附上明确引用。宿主可自行缓存完整内容，模型只展开当前需要的部分。临时引用失效时明确报错，不把缺失当成空证据；数据库写入失败则回退交付原回执，保留已经发生的游戏事实。服务关闭时清理本会话临时库。

传输只发送一份普通 JSON 文本；Attention v3 的任务事件保留游标、类型、身份和关键效果标记，当前决策从任务记录读取。身体伤害等事件继续保留自己的证据，过滤任务事件不能滤掉身体安全信息。

### 网络请求取消不等于游戏任务取消

请求仍在客户端线程的队列里时，可以撤回，随后也不会接管玩家或登记任务。已经开始处理的请求只能报告“已经开始”，继续交付真实结果；要停止已经接到的游戏工作，调用 `task(cancel)`。等待 Attention 消息的挂起由独立等待器管理，不混在普通接单请求的状态里。

## 每一游戏刻，角色先做什么

[ClientRuntime.tick](../../common/src/main/java/org/maiwithu/maicraft/client/runtime/ClientRuntime.java) 按以下顺序工作：

1. 观察战斗、机器目录、背包和预览等状态。
2. 打开本刻的 `LocalPlayerContext`，确认玩家和世界。
3. 更新交通、扫描、Attention 和可选服务端回执。
4. 处理玩家替换、换世界和预览取消。
5. 判断是否可以推进任务。
6. 先收尾旧交通动作；无须继续收尾时再调度任务。
7. 保存语义进度并更新与身体绑定相关的通知。
8. 推进获准的导航，最后归还身体上下文。

最后一步放在异常收尾里。前面哪一步出错，都必须让身体边界有机会清理本刻输入。

### “角色怎么站着不动”要先看哪一关

| `lastTickStage` | 玩家眼前的情况 | 此时做什么 |
| --- | --- | --- |
| `no_body` | 玩家或世界暂时不可用 | 观察连接，必要时清理旧身体 |
| `preview_review` | 等待玩家确认蓝图 | 保留预览，冻结预览等待期限 |
| `attention_required` | 例如死亡后正在等待决定 | 保留观察和进度，不推进普通任务与自救 |
| `player_control` | 玩家自行操作 | 记录控制权不可用，让总任务暂停 |
| `control_transition` | 接管仍在交接中 | 等待身体边界完成交接 |
| `settling_native_action` | 本刻已经用来停止旧动作等 | 保留任务，下一刻再试 |
| `settling_transport` | 旧交通动作尚未清理完 | 继续交通收尾，不启动普通新工作 |
| `running_tasks` | 允许自动执行 | 调度本刻唯一的身体使用者 |

这些状态描述的是运行时停在哪一步，不能单凭 `running_tasks` 就断言某次放置或某个机器工序已经成功。

## 新能力的无进展预算

持续执行的能力使用公共 [ProgressBudget](../../common/src/main/java/org/maiwithu/maicraft/task/ProgressBudget.java)。
例如 600 刻表示连续 600 个活动游戏刻没有新进展；真实进展会立即补满，任务总耗时可以超过该值。
`record.progressBudget(600)` 绑定任务时钟，统一排除抢占、暂停时间，并把真实子任务进展传给父流程。
辅助流程没有独立任务单时，在父任务执行作用域内使用 `ProgressBudget.currentTask(600)`。

```java
// 在能力创建时保留同一个预算对象；反复重试不能另建对象来刷新余量。
private final ProgressBudget inactivity;

// 构造时绑定任务单。confirmedUnits 必须是本任务累计确认的工作量。
inactivity = record.progressBudget(30 * 20);

// 每刻推进后检查：新增确认量补满预算，相同计数或重试归零都不补时。
boolean stalled = inactivity.observeCounter(player.level().getGameTime(), confirmedUnits);
```

没有累计数量时，用 `observe(now, newVerifiedFact)` 报告新的位移、差异核查或已确认操作。
后台线程仍存活、future 尚未完成、普通轮询、重复重算相同范围都不算进展。
`diagnostics(now)` 提供 `allowance`、`idle`、`remaining`；暂停和父子传递由公共层处理。
接收子任务进展只续期，不反向发布新进展，避免父子互相空转续命。
预算耗尽只说明流程没有新进展，不能据此宣称地形无路、目标不存在或原生效果没有发生；回执仍保留已确认事实。

后台寻路在完成节点展开、路径核查或地形清单后调用 `PathPlannerPool.madeProgress(stage)`。
诊断中的 `age_ms` 保留总耗时，停滞判断使用 `no_progress_ms`。
能够交付可走分段时，A* 仍按短计算切片及时交出路线；切片长度不作为整个旅行任务的失败期限。

## 谁能使用玩家身体

[TaskSelector](../../common/src/main/java/org/maiwithu/maicraft/task/TaskSelector.java) 依次询问：自救行为、同步任务、当前任务。每组取第一个能运行的候选者。

[CompanionBrain](../../common/src/main/java/org/maiwithu/maicraft/task/CompanionBrain.java) 随后判断是否能安全交接。例如角色正在跳跃或乘坐交通工具，不能仅因为另一个任务想执行就突然撤掉原动作。已失败落地方案的紧急救援有专门的交接路径。

确实要换任务时，先停旧导航和交通输出，再通知原任务被抢占，最后执行新任务。不能让两套动作在同一刻争用按键。

MLG 反射在角色入水或游泳时停用，已有救援会话和已排队的执行入口也须让位。未提交的落地动作取消，已提交的动作只结算原生回执；已经放下的水或辅助物留在现场，不再为了反射收尾占住身体。离水后再次发生真实坠落时仍可启动新救援。普通导航自行持有的计划落地与回收流程由原导航管理。触发前反射还探测下落路径：支撑面上方有任何液体时原版会清空摔落伤害，落点为水的下落不算紧急撞击，不触发接管——水中攀沿这类需要连续输入的驾驶不再被周期性的无动作反射切碎；落点为硬面的快速下落仍照常触发自救。

随行补光使用同刻剩余资源：主任务和导航先执行，`AfterNavigationAction` 处理区域灯位，最后 `AutomaticLighting` 检查经过位置。辅助动作不能停步、改路线或覆盖主动作瞄准；副手准备也要让位。转向期间按镜头自己的平滑通道逐帧转到灯位，角度进入容差且射线命中后才提交副手使用，随后平滑交还主任务的原路线视角，不再瞬转瞬回。每刻仍只提交一次原生交互，但副手一次性放置的回执独立等待，主任务下一刻可以继续使用主手。旧的 `TorchLightingChain` 只保留能力说明与确认条件，不再参与抢占。

导航按键通过 `applyNavigationMovement` 保留路线朝向，补光转头不能再次旋转这些按键。辅助瞄准必须通过 `finishAuxiliaryLook` 归还：未提交点击时把主任务的原视角目标交回并平滑转回，已提交时同样只交还目标、由镜头自己转回；后来的主动作瞄准优先。起跳准备和沿边缘潜行不借出准星。自动灯位只在前方视野内挑选，避免按固定方向顺序反复回头。

`maicraft:auto_light` 的 `action=enable|disable|status` 是即时配置或查询，不申请身体，不替换当前任务。默认关闭，由 LLM 按需显式开启，并可随时关闭；查询状态不会开启补光。开启后以方块光 8 为目标，火把常驻副手，仅用随身材料；主任务忙碌、没有支撑或缺料时保留暗格，不绕路。关闭立即停止新放置，已经提交的动作只继续结算原回执。状态中的 `automatic_lighting` 完整保留已走位置的最低实测亮度、暗格、未知项与原生放置结果。断开会话或重置后恢复关闭，需要重新开启。

`perceive(view=situation)` 的 `inventory` 统计背包、快捷栏、副手和穿戴物品的随身总量，`location_counts` 标明 `backpack`、`off_hand` 和 `armor` 的数量。主手已经包含在快捷栏，不另加一次；`equipment` 是同一份库存的手别、护甲和耐久明细。火把换到副手只改变位置，不能被当成消耗或丢失；自动补光状态也提供 `torch_inventory` 的总数与背包、副手分布。

选定区域使用 `maicraft:light_area`，默认火把、`coverage=all`、`minimum_light=8`。区域先发现范围，再规划、补料、用副手在触及范围内放置并复测；前一盏灯已覆盖的候选直接跳过，材料耗尽时交回原供料流程续作。`lighting_observation` 保留未达标位置；只有全部声明覆盖目标达到实测阈值才能声称全覆盖。光照不能清除已有怪物，也不把特殊生成机制一并宣称为安全。

未获执行机会的任务槽会冻结本任务及其子任务共用的活动时钟。自卫、其他任务槽和后续新任务使用独立时钟；服务端已接收的操作、原生回执等待和现实时间超时仍按各自规则推进。修改暂停行为时必须把这些路径一起检查。

## `start`、`tick`、`result` 分别负责什么

[TaskSlot](../../common/src/main/java/org/maiwithu/maicraft/task/TaskSlot.java) 在接单时创建执行器并调用 `start`。`start` 是一次性准备，不是“以后每次拿到身体都重新开始”。真正被调度时才调用 `tick`。

```text
接单 → 创建执行器 → start 一次
                       ↓
                 等调度器选中
                       ↓
                   tick 多次
                       ↓
          成功 / 失败 / 超时 / 被取消
                       ↓
             收取结果 → 释放身体 → 交付结果
```

构造、启动和运行都可能抛出异常。它们都必须结束对应任务单，而不能留下一个占用槽位、又无人推进的 `PENDING` 记录。

当前执行器中，`result` 不总是纯查询；有些实现还会关闭菜单、停止导航或结算操作。因此不能为了显示状态就随便调用它，也不能在失败路径重复调用它。查询进度应使用专门的只读进度信息。

### 挡路界面由执行器收尾

世界动作需要退出旧页面时，统一调用 `MenuPort.ensureWorldVisible`：先等原生槽位或菜单协议确认，再关闭容器、背包、暂停或模组页面，最后继续同一次任务。鼠标物品和背包合成余料由游戏原生关闭流程返还；画面先消失而物品同步稍后到达时仍等待同一关闭，不重发、不手动清空，也不让模型另开关界面任务。

任务终局只停止自己的身体和导航输出，不把此时显示的页面推断为自己拥有。菜单由实际打开并管理它的流程退出；连续存取、交易开菜单等中间步骤保留父流程所需页面，导航内部也不每刻强行关页。床上界面由原版睡眠生命周期管理，自动退出不会调用其会发送起床包的 `onClose`。主动作与界面收尾分别报告，真正的原生退出失败保留其事实，已经完成的主动作不会因此改判失败。

## 子任务做不下去时，总任务怎么处理

具体执行器可以在同一任务内处理可恢复的缺料、界面和导航问题。一旦实际子任务交回失败，当前 `IntentTask.failStep` 会保留原生结果、现场和已完成效果，记录尝试，默认结束这次执行并给出 `requires_decision:false`；它不会普遍调用恢复顾问并自动进入待答状态。

显式 `on_failure:"continue"` 且还有后续兄弟步骤时，`FAILED` 会作为失败条目入账后继续，整体仍不能冒称全部成功。当前判断没有另行排除 `outcome_uncertain`，不能把这个开关当作“只继续没有副作用的失败”；详见 [顺序目标](sequences.md)。

适配器实际返回 `IntentAction.Decision` 才进入等待回答：例如选择不唯一或缺少明确许可。此时使用真实 `decision_id` 和列出的选项；`recover`、`replace_goal` 等恢复方式依具体决策处理，不能对普通终态失败照搬。子回执里的恢复建议也不等于父任务已经产生待答问题。

已经确认完成的步骤不应重做。已经提交但结果尚不确定的消费操作，应先核对原件与当前事实，再决定剩余目标，不能仅凭“重试”两个字再消费一次。

## 暂停、取消和退出世界是不同的事

| 发生什么 | 保留什么 | 清理什么 |
| --- | --- | --- |
| 普通抢占或暂停 | 当前目标与子任务逻辑进度 | 身体按键、镜头和可安全停止的具体操作 |
| 取消当前任务 | 已确认结果和必要的未决效果说明 | 当前子任务、调度槽、待回答的问题 |
| 允许的传送门交接 | 同一个语义父任务 | 旧玩家对象上的具体动作和临时任务 |
| 普通断线或换到其他世界 | 对应世界的持久检查点 | 旧世界的身体、路线、菜单和扫描状态 |
| 从磁盘恢复 | 目标、已确认步骤、问题和持久证据 | 不恢复旧的输入对象或未验证路线；未完成任务先暂停 |

操作者取消正在施工的任务时，若台账里还有角色自己搭的临时支撑，任务会先进入最长约 30 秒的取消缓期，拆净能拆的再交回 `cancelled`；缓期内 `task` 查询仍是 running，并带 `cancellation_cleanup_in_progress:true` 和 `cancellation_note`，不必重复取消。提交新任务会立即打断缓期；整体停机、换身体不走缓期。

恢复后的暂停记录还没有进入身体调度槽。取消它应只结算这张记录，不应该要求先让角色继续干活，更不能挤掉正在执行的另一件事。

实现入口：[CompanionTickDispatcher](../../common/src/main/java/org/maiwithu/maicraft/task/CompanionTickDispatcher.java)、[IntentTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTaskRecord.java)、[IntentStateCodec](../../common/src/main/java/org/maiwithu/maicraft/intent/persistence/IntentStateCodec.java)。

## 怎么判断真的完成

“请求已经发送”“客户端画面预测变化了”“背包里本来有这件物品”，都不自动等于任务成功。

具体能力需要定义自己的完成证据。例如建造核对目标方块状态，物品操作核对数量和来源，机器生产核对原生过程事件。总任务只根据子任务的结果推进下一步；如果证据仍然不确定，就要把不确定保留下来。

对外结果还会去掉内部点击槽位、计划路线等细节。调用方收到的是目标及其结果，不是一串可以绕过 Mod 再执行一次的内部动作。

字段与文字的整理集中在 [SemanticResultView](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticResultView.java)。调整结果展示时从这里开始，执行顺序仍留在 `IntentTask`。已经实际采掘的方块位置属于可核查事实，有明确保留规则；计划坐标与真实效果不能混为一谈。
