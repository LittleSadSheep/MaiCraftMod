// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 溺水判断：头在水里时，氧气还够不够、要不要马上换气。
 *
 * <p>纯函数：读只读的处境，给急迫程度。头没在水里就不归溺水需求管；
 * 氧气还剩不到三分之一就该找空当换气，只剩两泡以内或者已经开始掉血，必须立刻浮上去。
 */
public final class DrowningDanger {

    /** 原版氧气条满值是 10 泡（300 刻空气，每 30 刻一格泡）。F 游戏事实，来源：原版氧气规则。 */
    public static final int MAX_BUBBLES = 10;

    /** 原版氧气剩两泡以下再不换气就开始掉血，把这两泡留作浮上去的时间。F 游戏事实，来源：原版溺水规则。 */
    private static final int CRITICAL_BUBBLES = 2;

    private DrowningDanger() {}

    /** 此刻溺水有多急；头没在水里、或氧气还很充足时返回 null，不归溺水需求管。 */
    public static Urgency assess(View situation) {
        if (!situation.headInWater()) {
            return null;
        }
        // 已经在掉血，或者氧气只剩两泡：再等一下就淹死，必须立刻换气。
        if (situation.drowning() || situation.airBubbles() <= CRITICAL_BUBBLES) {
            return Urgency.NOW;
        }
        // 氧气剩不到三分之一：不着急，但该找两个动作之间的空当浮头换气了。
        if (situation.airBubbles() * 3 < MAX_BUBBLES) {
            return Urgency.SOON;
        }
        return null;
    }

    /** 溺水判断要读的处境；由游戏接口层每刻从角色身上取。 */
    public interface View {
        /** 头部（眼睛所在的一格）是否在水里。脚在水里、头露在外面时不缺氧。 */
        boolean headInWater();

        /** 氧气条还剩几泡，0 到 {@link DrowningDanger#MAX_BUBBLES}。 */
        int airBubbles();

        /** 是否已经因为缺氧开始掉血。 */
        boolean drowning();
    }
}
