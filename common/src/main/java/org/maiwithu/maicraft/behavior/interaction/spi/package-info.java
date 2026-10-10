// SPDX-License-Identifier: GPL-3.0-only
/**
 * 交互这一块给别人实现的接口：挖掘加速（BreakAccelerator）、拆卸工具（DismantleTool）。
 *
 * <p>连锁挖、用模组自己的工具整块拆下方块这些由联动模组实现，施工引擎的清障只认这两个接口，
 * 不认具体是哪个模组；没有模组接上时照原来的办法一格一格挖。
 */
package org.maiwithu.maicraft.behavior.interaction.spi;
