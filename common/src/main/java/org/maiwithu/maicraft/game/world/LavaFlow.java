// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * 岩浆流路：一格岩浆源倒下去以后会流到哪几格的保守圈，倒岩浆前挑站位、随身造危险都按它避开。
 *
 * <p>原版岩浆从源格起每往旁边流一格少一级：普通维度每格少两级，源格外再流 3 格；下界这类超热维度每格少一级，
 * 再流 7 格。往下落一格就重新是满级，落地后接着往四面流。挡得住岩浆的实心方块挡住它；别的流体、草、火把这类挡不住。
 * 这里往四面和往下的分支都算上，也不算水把岩浆冷成石头这类对人有利的反应，所以圈出来的格只多不少，
 * 不是刷石机产物或真实流动先后的模拟。比调用方关心的最低一层还低的格不算（人站不到那么低，流到那里也碰不到人）。
 * 碰到没加载的格、或者算了 512 格还没算完，就说算不清，由调用方当成哪都可能流到。
 */
public final class LavaFlow {

    /** 类别 F：普通维度岩浆从源格往旁边再流 3 格（源格满级 8，每格少两级）。 */
    public static final int REACH = 3;
    /** 类别 F：超热维度（下界）岩浆每格只少一级，从源格往旁边再流 7 格。 */
    public static final int REACH_HOT = 7;
    /** 最多算多少格：再多就当算不清，宁可换个站位也不冒险倒。 */
    static final int MAX_CELLS = 512;

    private LavaFlow() {}

    /**
     * 从 source 倒下去的岩浆可能流到的格（含源格本身）；算不清为空。
     *
     * @param loaded 这一格加载着没有：没加载的格不知道是什么，碰到就算不清
     * @param state  这一格现在的方块状态
     * @param hot    在超热维度（下界）：岩浆流得更远
     * @param lowestY 只算到这一层：再往下落的那一段不算，调用方不在那么低的地方站人
     */
    public static Optional<Set<BlockPos>> reach(Predicate<BlockPos> loaded, Function<BlockPos, BlockState> state,
            BlockPos source, boolean hot, int lowestY) {
        if (!loaded.test(source)) return Optional.empty();
        int range = hot ? REACH_HOT : REACH;
        // 每格记下流到它时还剩几格能往旁边流；同一格后来又以更多的余量流到，才要再往外算一遍。
        Map<BlockPos, Integer> seen = new HashMap<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        seen.put(source.immutable(), range);
        pending.add(source.immutable());
        int visited = 0;
        while (!pending.isEmpty()) {
            if (++visited > MAX_CELLS) return Optional.empty();
            BlockPos at = pending.removeFirst();
            int remaining = seen.get(at);
            // 先往下：落一格重新满级。往下不受余量限制，一路落到挡得住它的地方或调用方关心的最低一层为止。
            if (at.getY() > lowestY && !spread(loaded, state, seen, pending, at.below(), range)) return Optional.empty();
            if (remaining <= 0) continue;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (!spread(loaded, state, seen, pending, at.relative(side), remaining - 1)) return Optional.empty();
            }
        }
        return Optional.of(Collections.unmodifiableSet(seen.keySet()));
    }

    // 流进一格：挡得住就停；余量不比上次多就不用再算；没加载的格算不清，返回假。
    private static boolean spread(Predicate<BlockPos> loaded, Function<BlockPos, BlockState> state,
            Map<BlockPos, Integer> seen, ArrayDeque<BlockPos> pending, BlockPos next, int remaining) {
        Integer before = seen.get(next);
        if (before != null && before >= remaining) return true;
        if (!loaded.test(next)) return false;
        if (blocks(state.apply(next), next)) return true;
        BlockPos cell = next.immutable();
        seen.put(cell, remaining);
        pending.addLast(cell);
        return true;
    }

    // 挡得住岩浆：岩浆替换不了、里面没有流体、又有碰撞外形的方块。围挡要真的在那里才算数。
    private static boolean blocks(BlockState block, BlockPos pos) {
        return !block.canBeReplaced(Fluids.LAVA) && block.getFluidState().isEmpty()
                && !block.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty();
    }
}
