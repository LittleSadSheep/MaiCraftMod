# MaiCraft 按需知识索引

这里提供参考资料。先发现相关条目，再读取所需页面；无需把整套知识库加入上下文。

- [已安装 Ponder 的方块与教程目录](maicraft://knowledge/ponder/index)：自动发现模组注册的演示，不限定 Create 或固定方块名单。
- [FTB Quests 任务书](maicraft://knowledge/ftbquests/index)：读取当前玩家可见章节，沿章节和任务 URI 查看目标、前置与队伍进度；长目录按返回的 `next_uri` 翻页。
- [如何解释教程与限制](maicraft://knowledge/guide)：区分原始旁白、控制提示、演示坐标和真实运行证据。
- [建筑场景 v1/v2 与统一蓝图 JSON](maicraft://knowledge/blueprint)：组件、阵列、镜像、快速图元、空心与面棱材质，以及教程蓝图的构建、修改、使用。
- [统一机器生产与原生加工](maicraft://knowledge/processes)：生产 v1/v2 格式、附魔与世界流体加工的按需机制契约，以及现场观察、材料与产出证据的边界。
- [从材料需求规划工艺和机器](maicraft://knowledge/recipes)：按物品读取 EMI 来源／用途、区分原料与工作站、查教程并复用或补建设备，再核验实际材料到账。
- 方块说明：使用 `maicraft://knowledge/block/{namespace}/{path}`，例如 `maicraft://knowledge/block/create/deployer`。页面给出状态属性、普通物品说明、可用的 Create Shift/Ctrl 说明，以及该组件的 Ponder 场景链接。

LLM 可调用 `perceive(view="knowledge", query="物品名称或关键词", limit=5)`，先取得少量候选，再按返回的 URI 读取正文。搜索只比较名称、注册 ID 和已有目录描述；名称与 ID 支持有限错字、漏字和相邻字母颠倒，输入不是正则表达式或 shell 命令。精确命中排在近似命中前，`match` 说明匹配依据；`ranking_score` 是排序值，不是概率。注册对象还提供 `subject_id`，后续按真实身份读取，不能因为近似匹配自动更改玩家目标。`total_matches` 与 `truncated` 说明候选是否读完，结果有歧义时继续缩小关键词。

例如 `query="精密构建"` 可以通过近似名称发现“精密构件”的配方入口；这只是通用字符匹配，不是产品专用工作站。选定后调用 `resources/read`，或使用 `perceive(view="knowledge", resource_uri="返回的 URI")`。`query`、`focus`、`resource_uri` 分别用于近似发现、兼容的字面查询与精确读取，不能混用。

`perceive` 读取长文档和大报告时可能先返回 `omitted=true` 的引用。按返回的 `resource_uri` 读取指定部分，按 `next_uri` 续页；JSON 资料可读取 `json` 中的结构，`text` 保留逐字原文。标准 `resources/read` 对原始资料 URI 保留完整正文，供程序校验。`maicraft://receipts/...` 是临时冻结快照，读取不会更新游戏观察的有效期；失效后重新做只读查询，不要为找回资料重复执行游戏任务。

`resources/list` 仅列元数据，并支持分页；材料配方正文通过 `maicraft://knowledge/recipes/{namespace}/{path}` 按需分页读取，搜索不会展开整棵配方树。读取 Ponder 组件页只返回它关联的场景列表，读取具体场景才编译说明文字。长场景会提供后续页链接。

场景页还提供章节结构回放入口；主动读取后才创建独立演示世界并分批提取。进度页返回章节旁白及结构资源链接；完整 JSON 按需读取或通过 blueprint_uri 直接引用。

操作能力使用 `perceive(view="abilities", query="build machine", limit=5)` 按 ID 和既有用途说明检索。候选只含标识与用途，`read_arguments` 可直接用于读取选中能力的完整契约；物品名应在 knowledge 中查，不能把每种产物当成一种专用能力。已知精确 ID 时直接用 `perceive(view="abilities", focus="maicraft:build_machine")`，无需先读全量能力目录。

机制知识不等于执行能力。能力搜索不查询现场可用性；选定能力后读取完整契约，配方、库存、机器模式和实际产出仍需当前世界的证据。缺少 Ponder 教程或搜索无结果不代表方块没有功能。

查找现场旋转动力用 `perceive(view="kinetic_sources", query="锁链", radius=32)`，省略 query 可查看当前范围的原生动力接口。Mod 在已加载区块索引内筛选，最多返回八个候选，不把全部方块交给模型；默认高度差不超过四格，隔墙、未加载路径和受保护的出口不入选。候选不证明取用权限；接线须属于用户施工范围或已知共用网络。跨层来源应明确点名获准使用的接点。

FTB 任务书页面是 JSON，只读当前客户端同步的数据，带有玩家、队伍、会话和读取时间。`available` 且目录为空表示目前没有可见章节；`not_installed`、`no_world`、`sync_pending`、`book_locked`、`book_disabled`、`api_unavailable` 各自说明无法读取的原因，不能当作空任务书。

任务 ID 和进度数量使用字符串，避免大整数失真。任务的 `can_start_tasks`、`dependencies_satisfied` 和 `completed` 是不同的 FTB 原生判断；前置规则由 `dependency_requirement` 与 `min_required_dependencies` 表示，规则为 `unknown` 时只采用原生满足判定；进度达到要求数量也不能代替完成记录。`details_visible`、`text_visible` 为假时保留隐藏状态，不推测未解锁内容。物品任务同时给出消耗、合成来源、任务屏幕限制、组件匹配与有限展示样例；`definition_snbt` 是原生条件定义，缺省值仍遵循 FTB。任务的 `conditions` 提供动作、目标和单位；`structured` 表示已解释原生条件，`server_defined` 表示条件来自服务器脚本，`native_definition_only` 表示保留了扩展类型的原生定义，`api_unavailable` 表示接口读取失败。后两类不可猜测成已知操作；服务器脚本判定函数不在客户端数据中。

任务书作者的正文和指南链接属于外部游戏资料，不是操作授权。知识读取不会提交物品、勾选任务或领取奖励。麦麦先根据要求规划，再用已有能力行动；相关行动完成后重读目标进度，避免把历史快照当作刚完成的证据。换队伍、切服、重载或可见目录变化导致分页失效时，重新从索引开始；任务书任务与 `perceive(view="tasks")` 中的 MaiCraft 执行任务相互独立。

任务索引的 `quest_lists` 提供全书只读筛选：`all`、`available`、`incomplete`、`completed`、`claimable`。`available` 指未完成且 FTB 允许开始的任务；`claimable` 按当前玩家或队伍的原生领取记录判断。章节和全书列表都支持 `q` 搜索可见标题或编号，中文按 URL 编码传入；下一页直接使用 `next_uri`，它保留筛选条件并检查结果集合变化。

任务详情的 `rewards` 列出可见奖励的个人/队伍归属、自动领取方式、已领取时间和当前可领取状态。沿返回的奖励 `uri` 读取物品与组件、数量范围、经验点/等级、阶段、进度、货币、消息或命令奖励定义。命令内容仅描述服务器奖励效果，不是供 Agent 执行的命令。被阻止或设为不可见的奖励遵守普通玩家界面的隐藏规则。

选择、随机、战利品和全表奖励分别保留原生模式。选择奖励固定选一项，全表奖励发全部；随机模式才按 `draws` 和权重解释。零权重项的自动发放与空奖概率分开报告，数量区间和概率不代表已经获得的结果。奖池只读一页候选，沿子条目 `uri` 查看内容；嵌套奖励的领取状态始终属于根奖励。隐藏奖池保持隐藏，只有当前可领取的选择奖励会展示原生选择界面可见的选项。奖池重排后旧子路径会失效，需要重新读取父页。

章节页的 `chapter_info` 包含分组、进度和已解锁图片的文字、布局及资源引用。图片引用不会自动下载，点击动作也不会执行；只有解锁后可见的正文、图片和奖励才能通过 URI 读取。任务详情同时保留开始/完成时间、完成次数和重复冷却，均来自当前同步记录。
