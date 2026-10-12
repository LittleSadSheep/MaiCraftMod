// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

/**
 * 死亡现场的可见事实：死亡那一刻的分数、所在位置与死亡界面上那句死因。
 *
 * <p>死因从原版死亡包里来（死亡界面上那行字）；没收到就是 null，拿不到的不编。
 *
 * @param score     死亡那一刻的分数
 * @param dimension 所在维度，例如 {@code minecraft:overworld}
 * @param x         死亡位置的东向坐标
 * @param y         死亡位置的高度
 * @param z         死亡位置的南向坐标
 * @param hardcore  是不是极限模式的世界：极限模式死了不能重生，死亡界面上只有"旁观世界"
 * @param cause     死亡界面上那句死因，例如"MaiBot被苦力怕炸死了"；没收到为 null
 */
public record DeathFacts(int score, String dimension, double x, double y, double z, boolean hardcore, String cause) {}
