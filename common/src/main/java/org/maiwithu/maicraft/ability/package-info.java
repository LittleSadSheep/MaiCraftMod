// SPDX-License-Identifier: GPL-3.0-only
/**
 * 能力模块（L3）：每个能力（或能力族）一个子包，结构见 docs/design/08 第 1 节。
 *
 * <p>能力是薄的：只负责"这件事要达成什么、按什么顺序组合行为模型"。
 * 跨能力只能依赖对方的 {@code api}、{@code spi} 子包；能力 ID 字面量只能出现在本能力的包里。
 * 不可以依赖 {@code integration} 与 {@code gateway}。
 */
package org.maiwithu.maicraft.ability;
