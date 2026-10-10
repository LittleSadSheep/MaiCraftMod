// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import net.minecraft.client.multiplayer.ClientLevel;

import org.maiwithu.maicraft.game.world.WorldTime;

/**
 * 睡觉的维度与时间规则：这个维度的床会不会爆炸、离能睡还要等多久。全仓只有这一处回答这两问。
 *
 * <p>下界与末地的天空是固定的，床在那里一点就会炸，所以不尝试、直接结束；
 * 可不可以睡的判断本身在游戏接口层的 {@link WorldTime}，这里只把它包装成"还要等多久"的人话。
 */
public final class SleepRules {

    /** 一分钟多少游戏刻。 */
    private static final long TICKS_PER_MINUTE = 1_200;

    private SleepRules() {}

    /** 这个维度床会不会爆炸（下界、末地）：会爆炸就不该点床，点了也无法事后补救。 */
    public static boolean bedsExplodeHere(ClientLevel level) {
        return !level.dimensionType().bedWorks();
    }

    /** 现在起还要等约多少分钟才能睡；现在就能睡返回 0。等多少刻的探查归世界时间规则管。 */
    public static long minutesUntilSleepable(ClientLevel level) {
        long ticks = WorldTime.ticksUntilSleepable(level.getDayTime(),
                level.dimensionType().hasFixedTime(),
                level.getRainLevel(1.0F),
                level.getThunderLevel(1.0F));
        return (ticks + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE;
    }
}
