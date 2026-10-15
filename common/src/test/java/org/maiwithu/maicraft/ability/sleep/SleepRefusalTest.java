// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.OverlayMessages;

/** 点床被拒的分流：1.21.1 中英原文各认成哪一种；点床之后动作栏新冒出来的拒绝才算这一下的回应。 */
class SleepRefusalTest {

    @Test
    void 认得出原版一点二一点一的五句中文拒绝() {
        assertEquals(SleepRefusal.OCCUPIED, SleepRefusal.of("这张床已被占用"));
        assertEquals(SleepRefusal.MONSTERS_NEARBY, SleepRefusal.of("你现在不能休息，周围有怪物在游荡"));
        assertEquals(SleepRefusal.NOT_SLEEP_TIME, SleepRefusal.of("你只能在夜间或雷暴中入眠"));
        assertEquals(SleepRefusal.TOO_FAR_OR_BLOCKED, SleepRefusal.of("你现在不能休息，床太远了"));
        assertEquals(SleepRefusal.TOO_FAR_OR_BLOCKED, SleepRefusal.of("这张床已被阻挡"));
    }

    @Test
    void 认得出原版的英文拒绝() {
        assertEquals(SleepRefusal.OCCUPIED, SleepRefusal.of("This bed is occupied"));
        assertEquals(SleepRefusal.MONSTERS_NEARBY, SleepRefusal.of("You may not rest now; there are monsters nearby"));
        assertEquals(SleepRefusal.NOT_SLEEP_TIME,
                SleepRefusal.of("You can sleep only at night or during thunderstorms"));
        assertEquals(SleepRefusal.TOO_FAR_OR_BLOCKED,
                SleepRefusal.of("You may not rest now; the bed is too far away"));
        assertEquals(SleepRefusal.TOO_FAR_OR_BLOCKED, SleepRefusal.of("This bed is obstructed"));
    }

    @Test
    void 多人服务器里的入睡人数提示不当成拒绝() {
        // 躺下成功后原版也会在动作栏说人数够不够跳过夜晚，这些不是拒绝。
        assertEquals(SleepRefusal.OTHER, SleepRefusal.of("1/2名玩家已入睡"));
        assertEquals(SleepRefusal.OTHER, SleepRefusal.of("已入睡玩家的数量不足以跳过夜晚"));
        assertEquals(SleepRefusal.OTHER, SleepRefusal.of("今夜将在睡梦中度过"));
        assertEquals(SleepRefusal.OTHER, SleepRefusal.of("No amount of rest can pass this night"));
    }

    @Test
    void 点床之前就挂着的旧话不算这一下的回应() {
        OverlayMessages.Shown before = new OverlayMessages.Shown("你现在不能休息，床太远了", 40);
        // 同一句话一刻一刻往下减：还是点床前那一条。
        assertEquals(Optional.empty(), SleepRefusal.newRefusal(before,
                new OverlayMessages.Shown("你现在不能休息，床太远了", 38)));
        // 换站位再点，原版又说了一遍：文字一样，剩余显示刻数跳回满格，算新的一条。
        assertEquals(Optional.of("你现在不能休息，床太远了"), SleepRefusal.newRefusal(before,
                new OverlayMessages.Shown("你现在不能休息，床太远了", 60)));
    }

    @Test
    void 点床之后新冒出来的拒绝才判没生效() {
        // 点床前动作栏是空的，点下去冒出一句有怪。
        assertEquals(Optional.of("你现在不能休息，周围有怪物在游荡"), SleepRefusal.newRefusal(null,
                new OverlayMessages.Shown("你现在不能休息，周围有怪物在游荡", 60)));
        // 动作栏还是空的：再等等，不下结论。
        assertEquals(Optional.empty(), SleepRefusal.newRefusal(null, null));
        // 别的模组在动作栏刷的字认不出是拒绝，不当成这一下没生效。
        assertEquals(Optional.empty(), SleepRefusal.newRefusal(null,
                new OverlayMessages.Shown("法力 50/100", 60)));
    }
}
