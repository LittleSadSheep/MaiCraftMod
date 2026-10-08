// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 被埋判断：角色卡在实心方块里，正在窒息。
 *
 * <p>纯函数：读只读的处境，给急迫程度。头部或躯干被实心方块埋住就会持续掉血，
 * 没有比这更急的事，一律立刻处理——哪怕手上的活停下不安全。
 */
public final class BuriedDanger {

    private BuriedDanger() {}

    /** 此刻被埋有多急；卡在实心方块里就是立刻，没有埋住返回 null，不归被埋需求管。 */
    public static Urgency assess(View situation) {
        return situation.stuckInSolidBlock() ? Urgency.NOW : null;
    }

    /** 被埋判断要读的处境；由游戏接口层每刻从角色身上取。 */
    public interface View {
        /** 角色是否被实心方块卡住（头部或身体所在的空间被实心方块占据，正在窒息）。 */
        boolean stuckInSolidBlock();
    }
}
