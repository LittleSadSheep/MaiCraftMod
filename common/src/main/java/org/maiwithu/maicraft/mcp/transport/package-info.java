// SPDX-License-Identifier: GPL-3.0-only
/**
 * MCP 传输：HTTP 与 JSON-RPC、会话、版本协商、鉴权与超时。
 *
 * <p>超时后要分清"请求还没动过游戏"和"已经开始、结果未知"。传输层不出现任何游戏规则或能力特例。
 */
package org.maiwithu.maicraft.mcp.transport;
