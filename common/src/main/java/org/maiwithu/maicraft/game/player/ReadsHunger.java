// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

/**
 * 饥饿情况只读视图：现在的饱食度，以及这个世界里有没有饥饿机制。
 *
 * <p>创造模式没有饥饿机制，普通食物吃不下也不消耗；这一条事实只在游戏接口层读一次，
 * 进食的判断和别的用途都从这里拿，不各自去翻玩家对象。
 */
public interface ReadsHunger {

    /** 此刻的饱食度，满值 20。 */
    int foodLevel();

    /** 这个角色现在有没有饥饿机制；创造模式为 false，普通食物吃不下。 */
    boolean hungerMechanicsOn();
}
