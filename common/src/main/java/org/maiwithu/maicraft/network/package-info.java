// SPDX-License-Identifier: GPL-3.0-only
/**
 * 客户端与服务端 MaiCraft 之间的网络消息：消息定义、版本协商、双方支持的功能清单。
 *
 * <p>不依赖客户端或服务端的任何一侧；{@code game.serverlink}（客户端一侧）和 {@code server}（服务端）都依赖它。
 * 只认已登录连接上的玩家身份，不信任客户端自己报的 UUID。和 MCP 无关。
 */
package org.maiwithu.maicraft.network;
