// SPDX-License-Identifier: GPL-3.0-only
/**
 * MaiCraft：让 LLM 以第一人称玩 Minecraft 的模组。LLM 通过内嵌的 MCP 服务说"要什么"，
 * Mod 像一个正常玩家那样用游戏的正常交互把它做成，并如实报告结果。
 *
 * <p>分层（依赖只能往下走，构建时由 ArchUnit 检查）：
 * <pre>
 * L5 bootstrap      启动：创建服务、登记能力与联动模组
 * L4 mcp            MCP 入口：传输、五个 MCP 工具、观察视图、知识库
 * L3 ability        能力            L3 compat  联动模组（只实现 spi）
 * L2 behavior       玩家行为：站位、交互、拿东西、背包、生存需求、许可……以及寻路
 * L1 kernel         内核：任务模型、打断规则、目标推进、能力框架、任务事件、存储
 * L0 game           游戏接口：角色、原生交互、容器界面、读世界、和服务端通信的客户端一侧
 * 另外三块：network（客户端与服务端之间的消息）、server（Minecraft 服务端一侧）、debug（只读的调试界面）
 * </pre>
 *
 * <p>写代码前先读仓库根的 AGENTS.md，以及 docs/design 里的架构、玩家行为与实现规范。
 */
package org.maiwithu.maicraft;
