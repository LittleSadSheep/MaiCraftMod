// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 角色自己的观察事实：位置、脸的朝向（东南西北）、生命、饥饿、氧气、手持、护甲、
 * 状态效果，以及落地或入水。场景里的方位都拿它当基准，所以先有它再整理别的观察。
 *
 * @param facingYaw 角色的视角角：0 朝南，顺时针增大
 * @param heldItem 手上拿着的物品注册 ID，两手都空时为 null
 */
public record SceneSelf(
        double x, double y, double z, float facingYaw, String facingWord,
        int health, int food, int air, int maxAir,
        String heldItem, List<String> armor, List<String> effects,
        boolean onGround, boolean inWater) {

    public SceneSelf {
        armor = List.copyOf(armor);
        effects = List.copyOf(effects);
    }

    /** 角色脚底所在的方块位置，观察编号登记与记忆写入都用它。 */
    public WorldPosition position() {
        return WorldPosition.here((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
}
