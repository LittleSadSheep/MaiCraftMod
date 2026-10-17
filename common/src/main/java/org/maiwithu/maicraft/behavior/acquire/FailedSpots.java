// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import net.minecraft.core.BlockPos;

/**
 * 刚试过做不成的格：走不到跟前、挖了半天挖不下来的那几格记一阵。这一阵里采掘先挑别的格、别的树、别的矿，
 * 下一次拿东西也不先去撞同一根够不着的原木；过了这一阵现场可能变了（垫了脚、挖开了路），再算回候选。
 * 只在客户端线程读写。
 */
final class FailedSpots {

    /** 记多久：五分钟里先不再挑它，过后重新算。玩家常识：刚够不着的树过一会儿才回头试。 */
    static final long REMEMBER_MILLIS = 5 * 60_000L;

    /** 一格：维度加位置；维度不知道时为 null。 */
    private record Spot(String dimension, BlockPos pos) {}

    private final LongSupplier clock;
    /** 每格记下的时刻（毫秒）。 */
    private final Map<Spot, Long> noted = new HashMap<>();

    /** @param clock 当前时刻（毫秒）；生产用系统时钟，测试给可拨的钟 */
    FailedSpots(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 记下这一格刚试过做不成。 */
    void note(String dimension, BlockPos pos) {
        noted.put(new Spot(dimension, pos.immutable()), clock.getAsLong());
    }

    /** 这一格是不是这一阵里刚试过做不成；过了这一阵顺手忘掉。 */
    boolean recent(String dimension, BlockPos pos) {
        Spot spot = new Spot(dimension, pos);
        Long when = noted.get(spot);
        if (when == null) return false;
        if (clock.getAsLong() - when < REMEMBER_MILLIS) return true;
        noted.remove(spot);
        return false;
    }
}
