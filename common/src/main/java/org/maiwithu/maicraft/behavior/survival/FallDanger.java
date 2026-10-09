// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 坠落判断：正往下掉时，这一掉落地会不会摔死。
 *
 * <p>纯函数：读只读的处境，给急迫程度。要在刚开始掉的时候就判断出"会摔死"，
 * 才来得及低头、换水桶、等落点进入够得着的距离再放水；等摔到一半才发现就晚了。
 * 所以看的是预计落地伤害（落点、安全摔落距离与摔落伤害倍率、落点方块、药水效果、附魔都算进去），
 * 不是已经掉了多远。下面是虚空时无论如何都必须立刻处理；落点是水时落进水里不掉血，不归坠落需求管。
 */
public final class FallDanger {

    private FallDanger() {}

    /** 此刻坠落有多急；没在往下掉、落进水里、或这一掉摔不死时返回 null，不归坠落需求管。 */
    public static Urgency assess(View situation) {
        if (!situation.falling()) {
            return null;
        }
        // 下面是虚空：掉下去就是死，不看还剩多少血。
        if (situation.overVoid()) {
            return Urgency.NOW;
        }
        // 落点是水：水本身就是缓冲，不用自救。
        if (situation.landsInWater()) {
            return null;
        }
        return situation.survivesLanding() ? null : Urgency.NOW;
    }

    /** 坠落判断要读的处境；由游戏接口层每刻从角色身上取。 */
    public interface View {
        /** 正在往下掉：不在地上、在往下走，且不是在水里、爬梯子、飞行、滑翔或坐着载具。 */
        boolean falling();

        /** 正下方一直到世界底都没有能落脚的东西（虚空）。 */
        boolean overVoid();

        /** 落点是水：落进水里不掉血。 */
        boolean landsInWater();

        /** 按预计落地伤害算，落地后还活着（黄心先抵伤害）。 */
        boolean survivesLanding();
    }
}
