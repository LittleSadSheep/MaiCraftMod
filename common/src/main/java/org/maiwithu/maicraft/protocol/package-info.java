// SPDX-License-Identifier: GPL-3.0-only
/**
 * 客户端与服务端共用的协议：消息定义、版本协商、能力清单。
 *
 * <p>不依赖客户端或服务端的任何一侧；{@code platform.server}（客户端一侧）和 {@code server}（服务端）都依赖它。
 * 协议只在已认证的连接上使用玩家身份，不信任客户端自报的 UUID。
 */
package org.maiwithu.maicraft.protocol;
