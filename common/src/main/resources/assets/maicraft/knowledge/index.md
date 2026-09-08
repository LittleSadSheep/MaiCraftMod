# MaiCraft 按需知识索引

这里提供参考资料。先发现相关条目，再读取所需页面；无需把整套知识库加入上下文。

- [已安装 Ponder 的方块与教程目录](maicraft://knowledge/ponder/index)：自动发现模组注册的演示，不限定 Create 或固定方块名单。
- [如何解释教程与限制](maicraft://knowledge/guide)：区分原始旁白、控制提示、演示坐标和真实运行证据。
- [统一机器蓝图 JSON](maicraft://knowledge/blueprint)：引用教程结构或自行编辑布局，分别构建、修改、使用。
- 方块说明：使用 `maicraft://knowledge/block/{namespace}/{path}`，例如 `maicraft://knowledge/block/create/deployer`。页面给出状态属性、普通物品说明、可用的 Create Shift/Ctrl 说明，以及该组件的 Ponder 场景链接。

LLM 可调用 `perceive`，传入 `view="knowledge"`、`focus="物品 ID 或关键词"`，取得简短搜索结果；随后使用返回的 URI 调用 `resources/read`。如果客户端未开放资源读取，也可调用 `perceive(view="knowledge", resource_uri="返回的 URI")`。

`resources/list` 仅列元数据，并支持分页；读取 Ponder 组件页只返回它关联的场景列表，读取具体场景才编译说明文字。长场景会提供后续页链接。

场景页还提供章节结构回放入口；主动读取后才创建独立演示世界并分批提取。进度页返回章节旁白及结构资源链接；完整 JSON 按需读取或通过 blueprint_uri 直接引用。

机制知识不等于执行能力。施工前另读 `perceive(view="abilities")`，配方、库存、机器模式和实际产出仍需当前世界的证据。缺少 Ponder 教程不代表方块没有功能。
