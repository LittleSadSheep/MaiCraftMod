// SPDX-License-Identifier: GPL-3.0-only
/**
 * 服务端（独立的一侧，指 Minecraft 服务端，不是 MCP 服务）：记录方块是谁放的、确认交互结果、读取模组数据、上报生产事件。
 *
 * <p>服务端只提供确认信息，不替角色动手，也不提供透视。
 * 只依赖 {@code network} 与 Minecraft 服务端；客户端任何层都不得引用本包。
 */
package org.maiwithu.maicraft.server;
