// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 溺水判断：头在水里时，氧气还够不够、要不要马上换气。
 *
 * <p>纯函数：读只读的处境，给急迫程度。头没在水里、或者有水下呼吸（药水、潮涌之力）就不归溺水需求管。
 * 剩下的氧气不够直着游上去（按水深估算，再留三秒反应），或者只剩两泡、已经在掉血，必须立刻浮上去；
 * 氧气剩不到三分之一就该找空当换气。
 */
public final class DrowningDanger {

    /**
     * 只剩这么多（两泡，60 刻）就立刻浮头。类别：玩家常识——原版氧气耗尽才开始掉血，
     * 留两泡是给上浮和被挡一下的余量。
     */
    static final int CRITICAL_AIR_TICKS = 60;

    private DrowningDanger() {}

    /** 此刻溺水有多急；头没在水里、能在水下呼吸、或氧气还很充足时返回 null，不归溺水需求管。 */
    public static Urgency assess(View situation) {
        if (!situation.headInWater() || situation.canBreatheUnderwater()) {
            return null;
        }
        int air = situation.airTicks();
        // 已经在掉血、只剩两泡、或剩下的氧气不够游上去：再等一下就淹死，必须立刻换气。
        if (air <= CRITICAL_AIR_TICKS || air <= situation.airNeededToSurface()) {
            return Urgency.NOW;
        }
        // 氧气剩不到三分之一：不着急，但该找两个动作之间的空当浮头换气了。
        if (air * 3 < situation.maxAirTicks()) {
            return Urgency.SOON;
        }
        return null;
    }

    /** 溺水判断要读的处境；由游戏接口层每刻从角色身上取。 */
    public interface View {
        /** 眼睛在水里（原版按眼睛判断缺不缺氧）。脚在水里、头露在外面时不缺氧。 */
        boolean headInWater();

        /** 有水下呼吸或潮涌之力这类效果，在水下不耗氧。 */
        boolean canBreatheUnderwater();

        /** 剩下的氧气（刻）；原版满值 300 刻，显示为 10 泡，耗到 0 以下开始掉血。 */
        int airTicks();

        /** 氧气满值（刻）。 */
        int maxAirTicks();

        /** 从现在的水深直着游上去要用掉的氧气（刻），已含三秒反应余量。 */
        int airNeededToSurface();
    }
}
