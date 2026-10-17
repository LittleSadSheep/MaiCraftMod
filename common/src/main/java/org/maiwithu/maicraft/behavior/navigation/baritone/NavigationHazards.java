// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import baritone.Baritone;

/**
 * 把吃过亏的方块交给寻路：算路时这几种格子身体不进、能绕就绕，实在要过去才挖（挖它算十倍工夫）；
 * 飞行落点、贴身判断也问这里。内嵌 Baritone 的设置是全局一份，这里在它原有名单后面接上，整份替换；
 * 后台算路线程也会问，名单整份替换、任何线程都能读。
 */
public final class NavigationHazards {

    private static final AtomicReference<Set<Block>> LEARNED = new AtomicReference<>(Set.of());
    /** Baritone 自带的要绕开、尽量别挖的名单：第一次装名单时抄下，之后接名单都保留它们。 */
    private static final AtomicReference<Defaults> DEFAULTS = new AtomicReference<>(null);

    private record Defaults(List<Block> avoid, List<Block> avoidBreaking) {
    }

    private NavigationHazards() {
    }

    /** 换上一份吃过亏的方块：寻路下一次算路起生效。 */
    public static synchronized void install(Set<Block> learned) {
        LEARNED.set(Set.copyOf(learned));
        Defaults defaults = DEFAULTS.updateAndGet(known -> known != null ? known
                : new Defaults(List.copyOf(Baritone.settings().blocksToAvoid.value),
                        List.copyOf(Baritone.settings().blocksToAvoidBreaking.value)));
        Baritone.settings().blocksToAvoid.value = merged(defaults.avoid(), learned);
        Baritone.settings().blocksToAvoidBreaking.value = merged(defaults.avoidBreaking(), learned);
    }

    /** 这格是不是吃过亏记下的那几种。 */
    public static boolean learned(BlockState state) {
        return LEARNED.get().contains(state.getBlock());
    }

    private static List<Block> merged(List<Block> defaults, Set<Block> learned) {
        List<Block> all = new ArrayList<>(defaults);
        for (Block block : learned) {
            if (!all.contains(block)) all.add(block);
        }
        return all;
    }
}
