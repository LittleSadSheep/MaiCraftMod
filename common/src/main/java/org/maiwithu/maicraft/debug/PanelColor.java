// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

/**
 * 面板上文字的颜色，按意思取，不按好看取：青是在做，黄是在等或在让路，红是出错或断开，
 * 绿是完成或正常，灰是次要文字，白是普通正文，标签用更浅的灰。取值是原版聊天颜色，看惯原版的人一眼认得。
 */
public enum PanelColor {
    /** 在做：目标进行中、此刻在推进的任务。 */
    DOING(0xFF55FFFF),
    /** 在等或在让路：等回答、暂停、临时任务插进来、生存需求想插没插。 */
    WAITING(0xFFFFFF55),
    /** 出错或断开：失败、报错、连接断了、角色死了。 */
    PROBLEM(0xFFFF5555),
    /** 完成或正常：目标完成、连接正常、满速。 */
    GOOD(0xFF55FF55),
    /** 次要文字：时间、编号、压着的任务。 */
    MINOR(0xFFAAAAAA),
    /** 普通正文：purpose、事件消息。 */
    TEXT(0xFFFFFFFF),
    /** 行首标签。 */
    LABEL(0xFFA8A8A8);

    private final int argb;

    PanelColor(int argb) {
        this.argb = argb;
    }

    /** 画字用的 ARGB 颜色。 */
    public int argb() {
        return argb;
    }
}
