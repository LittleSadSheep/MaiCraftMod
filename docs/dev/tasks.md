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

[PlanView](../../common/src/main/java/org/maiwithu/maicraft/mcp/PlanView.java) 避免在编译回执中复印原蓝图。`plan(plan_id=..., path=...)` 恢复保存的输入，明确标记没有重新验证现场；执行仍走原来的材料、场地和原生动作检查。

[ResponseArchive](../../common/src/main/java/org/maiwithu/maicraft/mcp/ResponseArchive.java) 处理其余超大载荷，把原始数据冻结在服务拥有的临时压缩文件里。页面保留对象键、数组索引和连续文本偏移，省略值附上明确引用。宿主可自行缓存完整内容，模型只展开当前需要的部分。临时引用失效时明确报错，不把缺失当成空证据；临时文件写入失败则回退交付原回执，保留已经发生的游戏事实。

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

## 谁能使用玩家身体

[TaskSelector](../../common/src/main/java/org/maiwithu/maicraft/task/TaskSelector.java) 依次询问：自救行为、同步任务、当前任务。每组取第一个能运行的候选者。

[CompanionBrain](../../common/src/main/java/org/maiwithu/maicraft/task/CompanionBrain.java) 随后判断是否能安全交接。例如角色正在跳跃或乘坐交通工具，不能仅因为另一个任务想执行就突然撤掉原动作。已失败落地方案的紧急救援有专门的交接路径。

确实要换任务时，先停旧导航和交通输出，再通知原任务被抢占，最后执行新任务。不能让两套动作在同一刻争用按键。

未获执行机会的任务槽会延长自己的截止时间。但这不是全局时间暂停：子任务的期限、服务端已接收的操作、回执等待和现实时间的超时都有各自规则。修改暂停行为时必须把这些路径一起检查。

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

## 子任务做不下去时，总任务怎么处理

例如建房需要木头，但附近没有允许采集的来源：

1. 子任务交回失败原因和已经发生的效果。
2. `IntentTask` 记录这一次尝试，清理当前子任务。
3. `RecoveryAdvisor` 生成可选的恢复方式。
4. 总任务暂停，等待外部调用方明确回答。
5. 回答“先找材料”时，把恢复目标插到当前步骤前；回答“换目标”时只替换尚未做成的当前步骤。

已经确认完成的步骤不应该重做。已经提交、但结果尚不确定的消费操作，也不能因为“重试”两个字就再点一次。

## 暂停、取消和退出世界是不同的事

| 发生什么 | 保留什么 | 清理什么 |
| --- | --- | --- |
| 普通抢占或暂停 | 当前目标与子任务逻辑进度 | 身体按键、镜头和可安全停止的具体操作 |
| 取消当前任务 | 已确认结果和必要的未决效果说明 | 当前子任务、调度槽、待回答的问题 |
| 允许的传送门交接 | 同一个语义父任务 | 旧玩家对象上的具体动作和临时任务 |
| 普通断线或换到其他世界 | 对应世界的持久检查点 | 旧世界的身体、路线、菜单和扫描状态 |
| 从磁盘恢复 | 目标、已确认步骤、问题和持久证据 | 不恢复旧的输入对象或未验证路线；未完成任务先暂停 |

恢复后的暂停记录还没有进入身体调度槽。取消它应只结算这张记录，不应该要求先让角色继续干活，更不能挤掉正在执行的另一件事。

实现入口：[CompanionTickDispatcher](../../common/src/main/java/org/maiwithu/maicraft/task/CompanionTickDispatcher.java)、[IntentTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/intent/IntentTaskRecord.java)、[IntentStateCodec](../../common/src/main/java/org/maiwithu/maicraft/intent/persistence/IntentStateCodec.java)。

## 怎么判断真的完成

“请求已经发送”“客户端画面预测变化了”“背包里本来有这件物品”，都不自动等于任务成功。

具体能力需要定义自己的完成证据。例如建造核对目标方块状态，物品操作核对数量和来源，机器生产核对原生过程事件。总任务只根据子任务的结果推进下一步；如果证据仍然不确定，就要把不确定保留下来。

对外结果还会去掉内部点击槽位、计划路线等细节。调用方收到的是目标及其结果，不是一串可以绕过 Mod 再执行一次的内部动作。

字段与文字的整理集中在 [SemanticResultView](../../common/src/main/java/org/maiwithu/maicraft/intent/SemanticResultView.java)。调整结果展示时从这里开始，执行顺序仍留在 `IntentTask`。已经实际采掘的方块位置属于可核查事实，有明确保留规则；计划坐标与真实效果不能混为一谈。
