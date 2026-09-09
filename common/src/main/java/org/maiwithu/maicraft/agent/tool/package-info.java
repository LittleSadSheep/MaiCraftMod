/**
 * Mod 内部工具的基础接口和辅助数据。
 * 当前 IntentTask 按 ToolRegistry 名字找到工具，直接调用 onGameCall；ToolCall 和本地调用回调桥是保留的旧通道。
 * Schema 只编写参数格式说明，ToolArgs 提供部分参数读取方法。真正执行时仍须检查玩家、权限和当前世界。
 * 这里登记的内部工具不等于 MCP 对外工具；公开入口由 mcp 包中的 PublicToolCatalog 定义。
 */
package org.maiwithu.maicraft.agent.tool;
