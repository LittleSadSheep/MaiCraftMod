// SPDX-License-Identifier: GPL-3.0-only
/**
 * 游戏接口层（L0）：Mod 和 Minecraft 客户端直接打交道的唯一地方。
 *
 * <p>对游戏的写操作（游戏模式交互、发包、按键状态、移动输入）只允许出现在本层；
 * 其他层可以只读地使用 {@code LocalPlayer}、{@code ClientLevel} 与 {@code BlockPos}、{@code ItemStack} 等值类型。
 *
 * <p>Mixin 放在 {@code game.mixin}，该包只放 Mixin 类，不放任何普通类（包括 package-info），
 * 因为 Mixin 会拒绝被直接加载的类。
 *
 * <p>可以依赖：{@code network}、Minecraft 客户端。不可以依赖：任何上层。
 */
package org.maiwithu.maicraft.game;
