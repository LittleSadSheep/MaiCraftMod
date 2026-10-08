// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;

/**
 * 角色自身视线接缝：每刻从角色身上取一次自己的观察事实（位置、视角、生命、饥饿、
 * 氧气、手持、护甲、状态效果、落地或入水）。实现留给游戏接口层，测试用替身。
 */
public interface SelfSight {

    /** 此刻角色自己的观察事实。 */
    Facts current();

    /**
     * 角色自己的原始事实，方位词由感知整理，这里只给数值。
     *
     * @param facingYaw 视角角：0 朝南，顺时针增大
     * @param heldItem 手上物品注册 ID，两手都空为 null
     */
    record Facts(
            double x, double y, double z, float facingYaw,
            int health, int food, int air, int maxAir,
            String heldItem, List<String> armor, List<String> effects,
            boolean onGround, boolean inWater) {

        public Facts {
            armor = List.copyOf(armor);
            effects = List.copyOf(effects);
        }
    }
}
