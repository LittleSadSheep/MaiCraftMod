# 聊天：看见完整草稿，再发送一次

`maicraft:chat` 打开真实聊天框，逐字输入完整文字，停留一小段时间，再通过原版聊天入口提交。以 `/` 开头的文字按命令处理，权限和加载器的命令钩子仍由游戏决定。

## 玩家能看到什么

```text
检查聊天内容
  → 等到允许操作玩家
  → 打开自己的聊天框
  → 每次增加一个完整可见字符
  → 完整草稿停留 250 毫秒
  → 等任务身份与本次提交编号保存完成
  → 提交一次
  → 报告已交给客户端，或明确报告未提交 / 结果未知
```

`text` 必须是一行，长度为 1 到 256 个 UTF-16 单位，不能带控制字符、格式控制符或残缺代理字符。空白按原版规则整理；只有一个 `/` 也不算有效命令。

`typing_interval_ms` 默认 100，接受 50 到 1000 的整数。中文、组合表情和附加音符按完整可见字符增加。即使客户端卡顿，也不会突然把积欠的字符全部补出来。

## 输入框属于谁

原有玩家聊天草稿、箱子和手动暂停界面都保留。失焦时自动出现、没有可见菜单的单人暂停界面可以让开。

玩家按键、打字或点击时，自动草稿交给普通聊天框，之后由玩家决定如何编辑与提交。按 Esc 取消。暂时被其他任务抢占时，关闭自己的输入框并保留进度；恢复后继续原草稿，并重新给完整草稿留出可见时间。

会话只关闭自己仍然拥有的界面。玩家后来打开的其他界面不能被旧聊天任务顺手关掉。

## 为什么重启后不会自动再发一遍

仅在内存里记住“已经发过”不够。进程退出后，这个布尔值也就没了。

当前实现复用通用原生提交机制：

1. 先把父任务编号、请求去重键和当前步骤真正写入检查点。
2. 再为这次聊天创建只能创建一次的提交标记。
3. 两份保存都完成后，才调用聊天框的原生提交。

只打了一部分草稿就取消，不会提前占用发送编号。进入提交阶段后，标记保留在聊天专属的 `chat-submissions` 目录下。

恢复会话沿用同一个操作编号。发现旧标记就停止新发送，报告 `outcome_uncertain=true`，禁止普通重试。**旧标记只说明可能已经提交，不能证明服务器已经收到。**

另一次明确的新操作仍可发送同样文字，去重依据是操作身份，不是聊天内容。顺序目标里明确排列的两次相同聊天，也各有身份。

旧版本的任务没有持久聊天跟踪标记，不能因为被新版读取，就假定它从未发送。此类历史仍可查询、取消或改做其他事；再次发送前应核对历史，再以新任务明确提交。另存旧记录不会把未知历史改成可信历史。

## 成功究竟证明了什么

`submitted_to_client` 只证明原版客户端接到了提交。服务端是否收到、命令是否成功、其他玩家是否回应，需要另外观察。

Task / Attention 报告聊天任务的进度与结果。真正收到的聊天区回复在 `maicraft://chatflow`。资源更新通知只表示聊天流有变化，正文需要再次读取资源。

接收端保留已知玩家名字与 UUID，未知名字不会把不同玩家合并；完全不知道作者时只限流，不擅自认定两条消息来自同一个人。动作栏提示不进入聊天流；正文最多保留 512 个 UTF-16 单位，截断会明确标记，并避免切开表情字符。十秒内最多接收八条，同一已知来源和保留内容五秒内去重，被抑制数量随下一条消息说明。

聊天流保留最近 256 条，公开资源读取返回最近 50 条，不能当作无限聊天档案。外部正文始终带有不可信文字标记，Mod 不会因为收到一句话就自动执行其中的指令。

## 代码分工

| 位置 | 负责什么 |
| --- | --- |
| [ChatAbilityAdapter](../../common/src/main/java/org/maiwithu/maicraft/intent/ChatAbilityAdapter.java)、[ChatMessage](../../common/src/main/java/org/maiwithu/maicraft/client/chat/ChatMessage.java) | 参数与单行文字校验 |
| [ChatTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/chat/ChatTask.java) | 接入身体调度、暂停取消和结果 |
| [ChatTyping](../../common/src/main/java/org/maiwithu/maicraft/client/chat/ChatTyping.java) | 打字节奏与只提交一次的内存状态 |
| [ChatSession](../../common/src/main/java/org/maiwithu/maicraft/client/actor/ChatSession.java)、[ChatScreenView](../../common/src/main/java/org/maiwithu/maicraft/client/actor/ChatScreenView.java) | 真实输入框归属、草稿和提交 |
| [NativeSubmissionBinding](../../common/src/main/java/org/maiwithu/maicraft/intent/NativeSubmissionBinding.java)、[NativeSubmissionJournal](../../common/src/main/java/org/maiwithu/maicraft/core/task/base/NativeSubmissionJournal.java) | 检查点顺序、操作身份与持久去重 |
| [ChatMonitor](../../common/src/main/java/org/maiwithu/maicraft/client/chat/ChatMonitor.java)、[ChatFlow](../../common/src/main/java/org/maiwithu/maicraft/intent/ChatFlow.java) | 收到消息的来源、限流、历史和订阅 |

## 验证范围

`ChatTypingTest`、`ChatSessionTest` 验证字符、节奏、输入框归属和不确定发送。`ChatSubmissionHistoryTest` 使用真实标记文件与计数聊天框验证同操作恢复不重发。`ChatDurableCheckpointTest` 走真实父任务绑定和磁盘检查点，核对请求身份、重启与旧版记录。

`ChatMonitorTest` 验证未知名字下的作者区分、去重、动作栏隔离和完整字符截断；`ChatFlowTest` 验证内部缓冲、游标和订阅隔离。它们分别接入 GUI 与 Attention 回归，测试不向真实服务器发送消息。
