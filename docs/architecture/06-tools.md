# 四个公开工具

MCP 的 `tools/list` 里**只有四个工具**。它们是**通道**，不是功能。

> "建房子""找物品""聊天"这些是 `goal.ability` 的取值，**不是工具**。
> 四个通道负责"观察 / 规划 / 启动 / 控制"，具体做什么写在载荷里。

## 总览

| 工具 | 一句话 | 只读 | 会改状态 | 幂等 |
| --- | --- | :---: | :---: | :---: |
| `perceive` | 看现状，或等消息 | ✅ | ❌ | ✅ |
| `plan` | 编译目标成步骤清单，**不执行** | ❌ | ❌ | ❌ |
| `execute` | 异步启动目标或计划 | ❌ | ✅ | ❌ |
| `task` | 查询 / 暂停 / 恢复 / 取消 / 回答 | ❌ | ✅ | ❌ |

（"会改状态"指对**语义任务或世界**产生影响；`plan` 只登记计划，不算。
"幂等" = 同一个编号重复发来不会开出第二份，见[关键机制](04-mechanisms.md)第 7 节。）

---

## `perceive` —— 看，或者等

**用途**：读当前事实，或等待任务事件。

**主要参数**：

| 参数 | 作用 |
| --- | --- |
| `view` | 看哪一类：`situation` / `surroundings` / `abilities` / `tasks` / `attention` / `landmarks` / `machines` / `knowledge` … |
| `focus` | 在选定的 view 里精确读一个对象（例如某个能力的契约） |
| `detail` | 仅能力目录：默认 `names` 一次列全能力名；`summary` 一次返回全部名字和概要，与 `query` / `focus` 互斥 |
| `query` | 模糊搜索候选（与 `focus`、`resource_uri` 互斥） |
| `resource_uri` | 直接读知识或冻结回执；**给了就默认 `view=knowledge`** |
| `task_id` | 只看某一个任务（仅 `tasks` / `attention`） |
| `stream_id` / `after_cursor` | 事件游标（仅 `attention`） |
| `wait_ms` | 等多久（仅 `attention`；见[Attention 与等待](07-attention.md)） |

**边界**：不同 view 只接受自己的字段。例如 `sections` 只给 `situation` / `surroundings`，
`wait_ms` 只给 `attention`；填错就地拒绝，**不静默忽略**。

`perceive(view="abilities")` 的 `semantic_abilities` 默认是完整的能力 ID 字符串数组。模型选中名称后可直接调用 `perceive(view="abilities", focus="maicraft:能力名")` 读取参数、限制与现场可用性；无需先读取可选概要层。`detail="summary"` 一次返回全部名字与用途，`query` 一次返回全部匹配候选。能力查询不分页，`limit` 不裁剪结果，非零 `offset` 明确拒绝。

**到语义部分**：`attention` / `tasks` / `landmarks` 读 `IntentRuntime` 的记录；
其余 view 是现场观察。

**四条旁路**（不走游戏线程的常规路径）：

| 请求 | 去哪 |
| --- | --- |
| `view=knowledge` | 离线知识库，不要求进世界 |
| `view=knowledge` + 回执 URI | 直接读冻结回执，连知识库都不查 |
| `view=web_knowledge` | 专用工作线程检索百科与读取条目，不要求进世界、不申请身体控制 |
| `view=attention` + `wait_ms>0` | 挂起等待，而不是立即求值 |

联网资料通过 `perceive(view="web_knowledge", query="Stone", language="en", limit=1)` 查询 Minecraft Wiki；中文站使用 `language="zh"`（默认）。也可指定 `url` 读取 `minecraft.wiki`、`zh.minecraft.wiki` 的 `/w/` 条目，以及 `www.mcmod.cn` 的 `/item/`、`/class/`、`/post/` 条目。只传 `url` 会自动选择此视图。`query` 和 `url` 二选一，URL 不接受查询参数；URL 已确定来源，不能同时填 `source` 或 `language`。

`source` 默认为 `minecraft_wiki`。Wiki 的 API、站内搜索页受 robots 限制，因此读取站点主动公布的 sitemap，连续读取主命名空间分片，在本地匹配条目标题，再获取选中的普通百科页面。`search_scope="sitemap_article_titles"` 表示标题检索，`indexed_articles` 和 `index_fetched_at` 说明覆盖量及索引时间；不会将标题未命中冒充全文不存在。MC 百科站内 `/s` 检索被其 robots 规则禁止，`source="mcmod"` 的检索返回 `site_search_disallowed`；条目链接读取仍可用。整个查询不需要搜索服务 API Key。

`subject_id` 可附带相关模组的客户端安装版本，版本信息在客户端启动时冻结。外部文档始终返回 `version_match="unverified"`，不会把客户端版本或网页更新时间当成服务器配方的证明。正文保留段落、表格、图片替代文字和引用；图片格子布局不转换成可执行配方。大结果沿现有冻结回执机制完整交付，不能静默裁掉条件或版本备注。

每次检索读取 1～5 个候选（默认 3），`total_matches` 和 `more_search_results` 报告检索范围；各页失败单列，允许部分成功。单次请求共用 20 秒期限、每次下载最多 4 秒、每个来源至少间隔 1 秒，并遵守更长的 Crawl-delay。HTTP 只接受白名单 HTTPS 路由，所有重定向重新校验；网页缓存最多 32 页、4 Mi 字符、15 分钟，缓存命中保留原抓取时间；每个语言站另外保留一份同样有效期的标题索引。正文超过 2 MiB 明确失败，不使用残页；站点地图下载上限 8 MiB，解压上限 16 MiB。两个资料工作线程最多受理四个请求，取消查询中断下载但不取消游戏任务。

---

## `plan` —— 先算清楚，但不动手

**用途**：把组合目标展开成有编号的步骤清单，并做一次检查；**不接管身体、不走路**。

**参数**：`goal` **或** `plan_id`（二选一），外加 `path` / `offset` / `limit`（读取已存计划）。

**它做什么**：

```text
bindForRequest            检查这请求属于当前世界吗
  ├─ 给 plan_id → 读回已存计划（只读冻结输入，不重新编译）
  └─ 给 goal    → 机器类预检 → 编译成步骤清单 → 存进 plans
返回：计划摘要 + 能否执行
```

**它不做什么**：

> **不算路线、不算材料、不证明一定能做成。**
> 这些要等真正执行时，结合世界里的情况判断。

**验证范围**：能力是否认识、参数名是否声明、目标类型是否允许；
**不检查**"现在能不能做到"。

---

## `execute` —— 开始做

**用途**：异步启动一个目标或计划。返回任务编号，之后靠 Attention 观察。

**参数**：`goal` **或** `plan_id`，外加 `request_key`。

**它做什么**：

```text
bindForRequest + requireRecoveredState    世界可用？旧检查点恢复了吗？
  ├─ 有 plan_id → 取出计划里的目标
  ├─ request_key 去重                      重复请求直接返回原任务
  │                                        （命中事实同时写上任务单，task 查询可见 deduplicated_request_hits）
  ├─ 非只读设计 → 申请接管玩家身体
  └─ IntentRuntime.execute
        ├─ 校验目标、建总任务单、登记、发 started 事件
        └─ CompanionTickDispatcher.submitCurrent  ★ 丢给身体
返回：task_id / accepted / control_status / next_attention
```

**返回值**：

| 字段 | 意思 |
| --- | --- |
| `task_id` | 任务编号；之后查询、暂停、取消都用它 |
| `accepted` | **只是"已登记"**，不是做完 |
| `status` | 终态优先；否则 `waiting_for_decision` / `paused` / `running` |
| `control_status` | 这次有没有去抢玩家身体 |
| `next_attention` | 拼好的下次等待参数，原样拿去调 `perceive` |

`control_status` 三个值：

| 值 | 意思 | 什么时候出现 |
| --- | --- | --- |
| `takeover_requested` | **已申请接管**身体 | 正常的新请求 |
| `not_requested` | **没有再申请** | 这是重复请求（之前已申请过） |
| `not_required` | **不需要**接管 | 只读设计（不碰身体） |

> 申请 ≠ 立刻生效：它只是登记，真正抢在**下一个游戏刻**发生，
> 不会在处理请求的中途把玩家的键盘抢走。

**边界**：

- `accepted: true` 只表示**已登记**，不表示做完。
- 它**不做** `plan` 的那次预检。
- **`plan` 不是它的前置**：可以直接 `execute(goal)`。

---

## `task` —— 查、管、答

**用途**：查看任务、暂停 / 恢复 / 取消，或**回答任务提出的问题**。

**参数**：`action` ∈ `get` / `list` / `pause` / `resume` / `cancel` / `answer`；
`task_id`（`list` 以外必填）；`path`（仅 `get`，逐项读完整证据）；`answer`（仅 `answer`）。

**`answer` 的两种含义必须分开**：

| `choice` | 必须带什么 | 意思 |
| --- | --- | --- |
| `recover` | `details.goal`（且不能带 `parameters`） | 在当前步骤**之前插入**前置目标 |
| `replace_goal` | `details.goal` | **换掉**当前这一步 |
| `retry` | `details.parameters`（不能带 `goal`） | 用新参数重试当前步骤 |
| `skip` | 什么都不带 | 明确略过这一步 |
| `cancel` | 什么都不带 | 停止整个任务 |

**边界**：`retry` 不是万能的——**已经提交、结果不确定的操作不允许普通重试**
（否则会重复消费）。这时要用 `recover` / `replace_goal` / `skip` / `cancel`。

---

## 四个工具怎么到达语义部分

```text
tools/call
  ▼
EmbeddedMcpService.callTool
  ├─ 名字是不是四个之一？
  ├─ PublicToolCatalog.validateAndNormalize   形状校验
  ├─ execute 专属：没给 request_key 就自动生成
  └─ switch(name)
       perceive ─► runtime.perceive
                    ├─ knowledge   → 离线知识库
                    ├─ surroundings→ 分帧采样
                    ├─ attention   → 挂起等待或立即读
                    └─ 其余        → onClient → IntentRuntime 的记录
       plan     ─► runtime.plan     → onClient → IntentRuntime.compile
       execute  ─► runtime.execute  → onClient → IntentRuntime.execute ★
       task     ─► runtime.task     → onClient → IntentRuntime.task / pause / cancel / answer
```

**共同点**：都在 `onClient` 里切到游戏线程
（见[结构地图](01-structure.md)的"`onClient`：把请求搬到游戏线程"一节），
除非走了上面那三条旁路。

业务返回后，工具结果出口统一附加当前有效的 `reminders`；未订阅 Attention 的宿主
也会在成功、错误或知识返回中收到游戏生活提醒，详见 [Attention 与等待](07-attention.md)。

---

## 相关代码

- 定义与校验：`PublicToolCatalog`
- 分发：`EmbeddedMcpService`（`callTool`）
- 落地：`MaiCraftRuntimeFacade`、`IntentRuntime`
