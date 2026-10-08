// SPDX-License-Identifier: GPL-3.0-only
/**
 * MaiCraft v2：第一人称的 Minecraft 伙伴运行时，通过内嵌 MCP 接口接受 LLM 的语义目标。
 *
 * <p>分层（依赖只能往下走，由 ArchUnit 在构建时强制，见 docs/design/02 第 2 节）：
 * <pre>
 * L5 bootstrap      启动装配
 * L4 gateway        对外入口（MCP、对外接口 v2、视图投影、知识库）
 * L3 ability        能力模块            L3 integration  联动模组（只实现 SPI）
 * L2 behavior       玩家行为模型 M2–M12 与步行引擎
 * L1 kernel         内核：调度、任务模型、目标推进、能力注册框架、事件、持久化框架
 * L0 platform       平台：身体端口、原生操作与回执、菜单、世界读取、与服务端协议的客户端一侧
 * 旁路：protocol（客户端与服务端共用协议）、server（服务端）、debug（只读调试界面）
 * </pre>
 *
 * <p>写代码前先读 docs/design 的 02（架构）、03（行为模型）、08（实现规范），以及仓库根的 AGENTS.md。
 */
package org.maiwithu.maicraft;
