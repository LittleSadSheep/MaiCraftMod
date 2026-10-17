// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

/**
 * 一次原生交互的最终结论。生效与没生效分开判断，提交了却说不清结果的单独一类，
 * 因为涉及消耗的交互在没能确认时绝不能盲目重做。
 */
public enum InteractionVerdict {
    /** 游戏确认这次交互生效了。 */
    APPLIED,
    /** 游戏明确没让它生效，现场事实随交互结果一起给出。 */
    NOT_APPLIED,
    /** 世界变成了提交时预料之外的样子，由上层看现场决定。 */
    UNEXPECTED,
    /** 已经提交，但等到期限也没能确认结果；这类交互不能盲目重试。 */
    UNCONFIRMED
}
