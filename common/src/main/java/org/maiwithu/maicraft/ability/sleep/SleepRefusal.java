// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.Locale;
import java.util.Optional;

import org.maiwithu.maicraft.game.interaction.OverlayMessages;

/**
 * 点床被游戏拒绝后的分流：按服务端的拒绝提示语认出是哪种拒绝，纯函数。
 *
 * <p>原版拒绝入睡只有几种：床被占用、附近有怪物、还不到能睡的时间、床太远或被挡。
 * 提示语有中英两套，按关键词认，认不出按"其他"如实上报，不猜。1.21.1 的原文：
 * 「这张床已被占用」「你现在不能休息，周围有怪物在游荡」「你只能在夜间或雷暴中入眠」
 * 「你现在不能休息，床太远了」「这张床已被阻挡」，英文分别是 This bed is occupied、
 * there are monsters nearby、You can sleep only at night or during thunderstorms、
 * the bed is too far away、This bed is obstructed。
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
        // 只认拒绝入睡那一句：多人服务器里"几名玩家已入睡""人数不足以跳过夜晚"也会出现在动作栏，那不是拒绝。
        if (text.contains("only at night") || text.contains("thunderstorm") || text.contains("sleep at night")
                || text.contains("夜间或雷暴") || text.contains("只能在夜间")
                || text.contains("晚上睡觉") || text.contains("夜晚睡觉")
                || text.contains("只有晚上") || text.contains("只能晚上")) {
            return NOT_SLEEP_TIME;
        }
        if (text.contains("too far") || text.contains("obstructed") || text.contains("太远")
                || text.contains("被挡") || text.contains("阻挡")) {
            return TOO_FAR_OR_BLOCKED;
        }
        return OTHER;
    }

    /**
     * 点床之后动作栏上新冒出来、认得出是拒绝入睡的那句话；没有就是空。原版拒绝入睡时不让人躺下，
     * 只在动作栏写一句为什么，等再久也等不到躺下：认出这句就是这一下没生效，不用等到超时。
     * 点床之前就挂着的旧话不算（同一句又说了一遍算新的），认不出的话也不算，别的模组在动作栏上
     * 刷的字不当成拒绝。
     *
     * @param lastSeen 上一次读到的动作栏提示；点床前那一刻读到的就是基准，没在显示时为 null
     * @param now      这一刻读到的动作栏提示；没在显示时为 null
     */
    static Optional<String> newRefusal(OverlayMessages.Shown lastSeen, OverlayMessages.Shown now) {
        if (now == null || !now.newerThan(lastSeen) || of(now.text()) == OTHER) {
            return Optional.empty();
        }
        return Optional.of(now.text());
    }
}
