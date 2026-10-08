// SPDX-License-Identifier: GPL-3.0-only
/**
 * Mixin：把对 Minecraft 内部方法的观察与拦截接回游戏接口层。
 *
 * <p>各归属：游戏接口层需要的（输入、伤害事件、方块变化、容器界面数据槽、
 * 物品使用与掉落的确认、屏幕可见性）放这里；施工预览与寻路路线渲染归 {@code debug}；
 * 具体联动模组的归 {@code compat}。Mixin 拿不到构造注入，实例经 {@link ClientHooks} 登记。
 */
package org.maiwithu.maicraft.game.mixin;
