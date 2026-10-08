// SPDX-License-Identifier: GPL-3.0-only
/**
 * MCP 入口（L4）：内嵌的 MCP 服务，LLM 通过它观察世界、查资料、下达目标、管理任务、读任务事件。
 *
 * <p>只负责协议、校验、整理成 LLM 看的形状与转发，不写游戏规则。可以依赖：{@code kernel}、{@code behavior}（只读）、
 * 各能力的 {@code api}。不可以依赖：能力与联动模组的内部包。
 */
package org.maiwithu.maicraft.mcp;
