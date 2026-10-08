// SPDX-License-Identifier: GPL-3.0-only
/**
 * 能力（L3）：每个能力（或一组相关能力）一个子包，例如 {@code ability.sleep}。
 *
 * <p>能力是薄的：只负责"这件事要达成什么、按什么顺序组合玩家行为"。
 * 跨能力只能依赖对方的 {@code api}、{@code spi} 子包；能力 ID 字面量只能出现在本能力的包里。
 * 不可以依赖 {@code compat} 与 {@code mcp}。
 */
package org.maiwithu.maicraft.ability;
