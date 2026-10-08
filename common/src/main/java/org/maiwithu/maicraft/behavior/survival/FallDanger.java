// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 坠落判断：正往下掉时，这一掉会不会摔死。
 *
 * <p>纯函数：读只读的处境，给急迫程度。预计落地伤害够得到当前生命，或者下面是虚空，都必须立刻处理；
 * 摔不死就不归坠落需求管，落地时扣多少血由游戏自己结算。
 */
public final class FallDanger {

    /** 原版落地伤害按"超出三格的部分"每格一颗心扣：三格以内落地不掉血。F 游戏事实，来源：原版坠落伤害公式。 */
    private static final double SAFE_FALL_BLOCKS = 3.0;

    private FallDanger() {}

    /** 此刻坠落有多急；摔不死（或没有在往下掉）时返回 null，不归坠落需求管。 */
    public static Urgency assess(View situation) {
        if (situation.fallDistance() <= 0) {
            return null;
        }
        // 下面是虚空：掉下去就是死，不看还剩多少血。
        if (situation.overVoid()) {
            return Urgency.NOW;
        }
        // 预计落地伤害按当前下落距离估：够得到当前生命就必须立刻自救（放水、落地前抓住什么）。
        double expectedDamage = situation.fallDistance() - SAFE_FALL_BLOCKS;
        return expectedDamage >= situation.health() ? Urgency.NOW : null;
    }

    /** 坠落判断要读的处境；由游戏接口层每刻从角色身上取。 */
    public interface View {
        /** 已经下落的距离（格）。站着或贴地时为 0，不大于 0 表示没有在往下掉。 */
        double fallDistance();

        /** 当前生命（一颗心算 2 点，满值 20）。 */
        double health();

        /** 正下方直到摔落底的深渊是不是虚空（末地外岛外、自定义世界的虚空层）。 */
        boolean overVoid();
    }
}
