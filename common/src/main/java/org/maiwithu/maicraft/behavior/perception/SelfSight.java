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
     * @param gearBars 身上装备与手里东西自己画的那条还剩多少（耐久、背罐的气、电量），物品不画条的不列
     * @param offhandItem 副手拿着的物品注册 ID，空着为 null
     */
    record Facts(
            double x, double y, double z, float facingYaw,
            int health, int food, int air, int maxAir,
            String heldItem, List<String> armor, List<String> effects,
            boolean onGround, boolean inWater, List<GearBar> gearBars, String offhandItem) {

        public Facts {
            armor = List.copyOf(armor);
            effects = List.copyOf(effects);
            gearBars = gearBars == null ? List.of() : List.copyOf(gearBars);
        }

        /** 不读副手的一份：离线测试用。 */
        public Facts(double x, double y, double z, float facingYaw, int health, int food, int air, int maxAir,
                     String heldItem, List<String> armor, List<String> effects, boolean onGround, boolean inWater,
                     List<GearBar> gearBars) {
            this(x, y, z, facingYaw, health, food, air, maxAir, heldItem, armor, effects, onGround, inWater, gearBars,
                    null);
        }

        /** 不读物品条的一份：离线测试用。 */
        public Facts(double x, double y, double z, float facingYaw, int health, int food, int air, int maxAir,
                     String heldItem, List<String> armor, List<String> effects, boolean onGround, boolean inWater) {
            this(x, y, z, facingYaw, health, food, air, maxAir, heldItem, armor, effects, onGround, inWater, List.of());
        }
    }

    /**
     * 一件装备或手里东西自己画的那条：会磨损的东西是耐久，Create 的背罐是罐里的气，有些模组是电量；
     * 物品自己说画不画、画多长，这里只换成百分比，不认是哪种。
     *
     * @param slot    在哪：mainhand / offhand / head / chest / legs / feet
     * @param item    物品注册 ID
     * @param percent 条还剩百分之几（0~100）
     */
    record GearBar(String slot, String item, int percent) {
    }
}
