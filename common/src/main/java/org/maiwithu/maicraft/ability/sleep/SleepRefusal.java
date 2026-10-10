// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.Locale;
import java.util.Optional;

/**
 * 点床被游戏拒绝后的分流：按服务端的拒绝提示语认出是哪种拒绝，纯函数。
 *
 * <p>原版拒绝入睡只有几种：床被占用、附近有怪物、还不到能睡的时间、床太远或被挡。
 * 提示语有中英两套，按关键词认，认不出按"其他"如实上报，不猜。
 */
enum SleepRefusal {
    /** 床上已经有人（或别的什么）躺着。 */
    OCCUPIED,
    /** 床边有怪，原版不让睡。 */
    MONSTERS_NEARBY,
    /** 还不在原版可睡窗口里（天没黑透、雷停了）。 */
    NOT_SLEEP_TIME,
    /** 床太远或被挡住：换一次站位再看。 */
    TOO_FAR_OR_BLOCKED,
    /** 认不出的拒绝。 */
    OTHER;

    /** 从动作栏提示语或拒绝原文里认拒绝种类。 */
    static SleepRefusal of(String message) {
        String text = Optional.ofNullable(message).orElse("").toLowerCase(Locale.ROOT);
        if (text.contains("occupied") || text.contains("占用")) {
            return OCCUPIED;
        }
        if (text.contains("monster") || text.contains("怪物")) {
            return MONSTERS_NEARBY;
        }
        if (text.contains("sleep at night") || text.contains("晚上睡觉") || text.contains("夜晚睡觉")
                || text.contains("只有晚上") || text.contains("只能晚上")) {
            return NOT_SLEEP_TIME;
        }
        if (text.contains("too far") || text.contains("obstructed") || text.contains("太远") || text.contains("被挡")) {
            return TOO_FAR_OR_BLOCKED;
        }
        return OTHER;
    }
}
