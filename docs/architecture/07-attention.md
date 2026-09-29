# Attention 与等待

任务可能跑很久（盖房子、打龙）。外部不能一直问"好了没"，所以项目提供了一条
**事件流**，让外部"有事才醒"。

## 一、Attention 是什么

一条**任务事件流**。任务有进展、要你回答、或结束时，往上发一条事件。

常见事件类型：

| 事件 | 含义 |
| --- | --- |
| `started` | 任务已登记 |
| `step_completed` / `step_skipped` | 某一步完成 / 被明确跳过 |
| `plan_changed` | 插入了前置目标，或替换了当前步骤 |
| `decision` | **需要你回答** |
| `paused` / `resumed` | 暂停 / 恢复 |
| `completed` / `failed` / `cancelled` | 终态 |
| `state_restored` | 从检查点恢复 |
| `runtime.unavailable` | 身体断开，需要重新对齐 |

**事件只提示"发生了什么"**；完整事实在任务记录里，用 `task(get, path=...)` 按需读。

---

## 二、两种等法

| | 长轮询 | 资源订阅 |
| --- | --- | --- |
| 怎么做 | `perceive(view=attention, wait_ms=N)` | 订阅 `maicraft://attention` |
| 谁挂起 | **HTTP 请求**挂着，直到有事或超时 | 服务端**主动推**通知 |
| 游标放哪 | 跟着这次请求 | **客户端自己存** |
| 适合 | 简单客户端 | 常驻客户端 |

两者项目都支持，客户端按自己的架构选。

---

## 三、`next_attention` 是什么

它是一个**拼好的参数表**，下一次调 `perceive` 时**原样填进去**就行：

```json
{
  "view": "attention",
  "task_id": "…",
  "stream_id": "…",
  "after_cursor": 0,
  "wait_ms": 30000
}
```

| 字段 | 作用 |
| --- | --- |
| `task_id` | 盯住某一个任务 |
| `stream_id` | 用来**发现重启或换世界**（对不上就说明事件流断了） |
| `after_cursor` | 从哪接着读 |
| `wait_ms` | 最多等多久（毫秒） |

**`wait_ms` 的小设计**：上一页还有没读完的，就设成 `0`（马上读下一页）；
读完了才设成 `30000`（挂起等新消息）。

**为什么要给拼好？** 分页参数容易填错，服务端替你填。

---

## 四、醒来时看到什么：`wake_reason`

| `wake_reason` | 意思 |
| --- | --- |
| `events` | 有新事件 |
| `decision_required` | **它在等你回答**，去 `task(answer)` |
| `task_paused` | 任务暂停了 |
| `task_terminal` | 任务结束了 |
| `task_unavailable` | 指定的任务不存在 |
| `resync_required` | 事件流断了，要重新对齐 |
| `runtime_unavailable` | 没有可用世界 |
| `idle` | 什么都没发生 |
| `timeout` | 等够时间了，什么都没发生 |

响应里**又会带一个新的 `next_attention`**，继续等。

---

## 五、谁在等？（最容易误解）

> **等的是"这次 HTTP 请求"，不是游戏。游戏一刻都不停。**

```text
① 调用方    发请求，等回复        ← "我"在等
② 服务端    把这个请求挂着        ← 网络线程等一个 future
③ 游戏      照常跑，一秒不停      ← 完全不受影响
```

- 等待期间**不占用游戏刻、不暂停游戏**。
- 有事件 → 立刻回复；没事件 → 到 `wait_ms` 才回。
- 从协议看是"这次请求在等"；具体什么时候唤醒模型，由 MCP 客户端（宿主）决定。

---

## 六、两条最容易搞错的规则

### 等超时 ≠ 任务超时

`wake_reason: "timeout"` 只表示"这段时间没人说话"。
**游戏里的任务照常在跑。** 接着再等就行。

### 取消等待 ≠ 取消任务

如果网络断了、客户端不等了，取消的只是"这次等待"。
**玩家正在做的事不受影响。** 要停游戏里的事，得调 `task(cancel)`。

---

## 七、大结果怎么办：冻结回执

普通业务载荷过大时，服务端给一个**冻结回执 URI**（`maicraft://receipts/...`）：

- 它是**当时那次观察的只读快照**，不会重新观察世界
- 按需展开，读完继续读 `next_uri`
- **临时有效**（会过期）；过期后要么查仍在保留的任务，要么**重做那次只读观察**
- ⚠️ **绝不能为了找回输出而重跑 `execute`**

---

## 相关代码

- 事件发布：`IntentRuntime`（`publish` / `terminal` / `gameEvent`）
- 等待实现：`AttentionWait`
- 读取与投影：`AttentionSnapshot`、`TaskView`、`JsonReadback`
- 大结果：`ResponseArchive`
