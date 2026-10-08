// SPDX-License-Identifier: GPL-3.0-only
/**
 * 服务端（独立上下文）：方块归属记账、动作旁证、模组数据读取、生产事件。
 *
 * <p>服务端只作证、不替角色动手，也不提供透视（docs/design/02 第 3 节）。
 * 只依赖 {@code protocol} 与 Minecraft 服务端；客户端任何层都不得引用本包。
 */
package org.maiwithu.maicraft.server;
