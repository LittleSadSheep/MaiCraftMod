// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

/**
 * 死亡现场的可见事实：死亡那一刻的分数与所在位置。
 *
 * <p>客户端只看得到这些：死因一句话随死亡界面走，不留在角色身上，带不出来。
 * 拿得到的都给，拿不到的不编。
 *
 * @param score     死亡那一刻的分数
 * @param dimension 所在维度，例如 {@code minecraft:overworld}
 * @param x         死亡位置的东向坐标
 * @param y         死亡位置的高度
 * @param z         死亡位置的南向坐标
 * @param hardcore  是不是极限模式的世界：极限模式死了不能重生，死亡界面上只有"旁观世界"
 */
public record DeathFacts(int score, String dimension, double x, double y, double z, boolean hardcore) {}
