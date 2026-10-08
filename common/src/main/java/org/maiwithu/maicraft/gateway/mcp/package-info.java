// SPDX-License-Identifier: GPL-3.0-only
/**
 * MCP 传输与工具处理：HTTP / JSON-RPC、会话、超时语义，以及 observe、lookup、execute、task、wait 五个工具。
 *
 * <p>超时后区分"请求还没动过游戏"和"已经开始、结果未知"（沿用 v1 的设计）。传输层不出现任何领域特判。
 */
package org.maiwithu.maicraft.gateway.mcp;
