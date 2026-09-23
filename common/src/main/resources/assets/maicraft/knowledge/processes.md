# 统一机器生产与原生加工

继续使用 `design_machine`、`build_machine` 与 `operate_machine`：生成结构、建造机器，再使用机器。结构设计或施工完成不证明已经产出；`operate_machine` 的 `operation="run_production"` 执行明确的生产意图，`build_machine` 也可在施工后运行同一 `production`。配方名称和教程文字本身不证明当前场地能够加工。

先 `inspect_machine` 标记实际场地并取得新鲜 `snapshot_id`，后续使用同一目标标签。`native_processes` 只为标记位置实际匹配的机制提供契约和只读观察。其他位置可以重新观察，v2 的 `offset` 始终相对该次观察或建造锚点。

## v2：有限原生过程

```json
{
  "schema_version": 2,
  "process": "namespace:mechanism",
  "offset": [0, 0, 0],
  "parameters": {}
}
```

`process` 选择原生机制，不为每种产物增加一种能力。`parameters` 必须严格遵守本页末尾或现场观察返回的机制契约。`offset` 可以省略；坐标、数量与预算必须是精确整数。运行请求继续使用外层 `allow_use=true`。v2 加工只使用现有主背包原料，直接运行的外层 `material_policy` 仅接受 `inventory_only`；缺料时组合已有 `acquire_items` 再运行。`build_machine` 的外层材料策略仍负责施工补给，不会自动补齐加工原料。

附魔使用 `minecraft:enchanting`，参数为 `item_id`、可选 `offer_tier`、必需的 `max_levels_spent` 和 `max_lapis`。例如 `{"item_id":"minecraft:iron_pickaxe","offer_tier":1,"max_levels_spent":1,"max_lapis":1}`。位置来自机器锚点，不再传 `search_radius`。过程保留可见 GUI、真实报价、一次提交、费用与成品核验、返还及关闭；等级门槛不等于实际扣除等级，报价提示不保证隐藏随机附魔。旧 `maicraft:enchant` 请求与显式能力查询继续兼容，默认能力列表不再展示该别名，其持久目标与消费标识不作迁移。

AE2 世界流体加工使用 `ae2:transform`，例如 `{"recipe_id":"<现场返回的配方 ID>","batches":1}`。从实际安装的原生配方及机制契约选择，不根据产物名称猜测配方、投入数量或环境条件。过程逐批投料并回收，不能把附近既有成品当成本次产物。`native_recipe_verified=true` 才表示原生配方事件已验证；客户端仅确认产物与拾取时返回 `evidence_scope="client_observed_output_and_inventory"`，不把这种观察当成原生配方事件或持续产线证明。

`build_machine` 可以同时提供 v2 `production` 与 `allow_use=true`：先执行同一建造任务，再核验建成位置并运行同一原生过程。蓝图中的源流体通过原生桶装配，核验源格与桶变化。消费屏障先确认父任务身份落盘，再预留一次原生消费；中断后不会通过换观察编号自动重发材料或经验消耗。沿用原 `request_key` 处理网络重试；暂停、取消或手动接管后出现 `outcome_uncertain` 时先核对现场，不能用普通重试重复消费。

## v1：机器网络与持续验收

```json
{
  "schema_version": 1,
  "nodes": [{"id":"name","kind":"source|process|transport|sink","offset":[0,0,0],"recipe_id":"process节点需要","batches":1,"material_policy":"source节点可用"}],
  "ports": [{"id":"port","node":"name","offset":[0,0,0],"face":"up","medium":"items","direction":"input|output"}],
  "links": [{"id":"link","from":"output_port","to":"input_port","medium":"items","resource":"namespace:item","amount":1,"path":[[0,0,0],[1,0,0]],"configurations":[]}],
  "configurations": [{"id":"setting","node":"name","operation":"machine.configure","stage":"configure|start","arguments":{"action":"原生配置动作"}}],
  "target": {"node":"sink_name","medium":"items","resource":"namespace:item"},
  "observation": {"window_ticks":20,"minimum_output":1,"minimum_events":2,"max_idle_ticks":100}
}
```

上方展示字段位置，不是一份可直接执行的网络：所有名称与端口必须实际对应。只有 process 节点声明 `recipe_id`/`batches`，只有 source 节点声明 `material_policy`。材料策略为 `inventory_only`、`storage_available` 或 `ordinary`；介质包括 `items`、`fluids`、`chemicals`、`energy`、`kinetic`。资源身份可以使用原生观察返回的组件敏感身份。

`storage_available` 不授权探索陌生箱子。自动取料只复访近期通过真实菜单确认有目标材料的普通容器；未知、已知没有目标材料或线索失效的箱子会被跳过。自动整理余料也只续用已知存放对应材料的容器。玩家明确要求访问某个箱子，或告示牌、可信记忆、聊天说明指向具体容器和材料时，先按该依据使用定向 `use_container` / `manage_container`；地标名称、附近有箱子或缺材料本身都不是访问与取用授权。已存在的保护标签继续生效。

`path` 和 link 的 `configurations` 可省略。配置 arguments 支持 `action` 及原生定义的 `value`、`clear`、`item_id`、`components`、`resource_id`、`side`、`transmission`、`relative_side`、`data_type`、`enabled`、`mode`、`recipe_id`；未知动作或字段不得当成可执行。数量是有限观察窗口的预算，kinetic 数量表示最低转速。

v1 保留至少两次原生产出事件、指定时间跨度和实际交付到 sink 的验收要求。`window_ticks` 是输出证据的最短跨度，`max_idle_ticks` 独立限制无进展间隔；上限均为72000刻。网络配方、端口、连接与事件仍要求相应服务器支持，设计检查不会降低这些证据要求。

## 前台运行与后台观察

`run_production` 可以执行 v1 或 v2。`watch_production` 本轮只接受 v1，注册前先于未来批次开始；注册成功仅表示开始只读观察。它释放身体，不巡逻、不自动补料、不强制加载区块，完成或需要处理时通过 Attention 报告。重连或换维度后需要重新注册。

`cancel_watch` 仅停止观察，不关闭机器。v2 附魔、投料和回收属于前台有限过程，不能用后台观察隐式再次消费。
