// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 被埋判断：角色的头卡在实心方块里，正在窒息。
 *
 * <p>纯函数：读只读的处境，给急迫程度。头被会窒息的实心方块占着就会持续掉血，
 * 没有比这更急的事，一律立刻处理——哪怕手上的活停下不安全。脚陷在方块里、头露在外面不会窒息，不归这里管。
 */
public final class BuriedDanger {

    private BuriedDanger() {}

    /** 此刻被埋有多急；头卡在实心方块里就是立刻，没有埋住返回 null，不归被埋需求管。 */
    public static Urgency assess(View situation) {
        return situation.stuckInSolidBlock() ? Urgency.NOW : null;
    }

    /** 被埋判断要读的处境；由游戏接口层每刻从角色身上取。 */
    public interface View {
        /**
         * 头卡在会窒息的实心方块里：眼睛所在的格，或身体被挤开半格后头顶所在的格。
         * 原版只拿眼睛高度的一片薄盒判断，身体被挤开时会漏判，所以两格都看。
         */
        boolean stuckInSolidBlock();
    }
}
