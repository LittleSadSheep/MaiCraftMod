// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchCompanionTask;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord;
import org.maiwithu.maicraft.intent.IntentRuntime;

/**
 * find_block 视线验证过的位置必须写入共享观察记忆：公平闸的"已观察"半边从此覆盖
 * find_block → travel → acquire 链路，不再出现同一方块"find_block 视线验证通过、
 * mine 公平闸却说不可见"的口径失配。
 */
public final class FairnessMemoryFeedsMineTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos ore = new BlockPos(2, 1, 3);
            h.set(ore, Blocks.COAL_ORE.defaultBlockState());
            var search = new SemanticBlockSearchCompanionTask(h.player,
                    new SemanticBlockSearchTaskRecord("fb", 10_000, List.of(Blocks.COAL_ORE), 1, 32));
            start(search);
            observe(search, ore);
            var memory = IntentRuntime.get().observedSourceMemory();
            String dimension = h.level.dimension().location().toString();
            check(memory.seen(dimension, ore), "find_block 视线验证命中应写入共享观察记忆");
            // 遮挡后即时可见不再成立；公平闸仍须凭"已观察"半边放行该位置。
            h.set(new BlockPos(1, 2, 3), Blocks.STONE.defaultBlockState());
            var mine = new MineCompanionTask(h.player, new MineBlockTaskRecord(
                    "mine", 10_000, Set.of(Blocks.COAL_ORE), 1, "coal_ore"));
            admit(mine, ore);
            check(knownOres(mine).contains(ore), "记忆命中的位置应被公平闸放行为挖掘目标");
        }
        System.out.println("FairnessMemoryFeedsMineTest: passed");
    }

    private static void start(Object task) throws Exception {
        Method start = task.getClass().getDeclaredMethod("onStart");
        start.setAccessible(true);
        start.invoke(task);
    }

    private static void observe(Object task, BlockPos at) throws Exception {
        Method observe = task.getClass().getDeclaredMethod("observe", BlockPos.class);
        observe.setAccessible(true);
        observe.invoke(task, at);
    }

    private static void admit(MineCompanionTask task, BlockPos at) throws Exception {
        Method admit = MineCompanionTask.class.getDeclaredMethod("admitObserved", BlockPos.class);
        admit.setAccessible(true);
        admit.invoke(task, at);
    }

    private static java.util.List<BlockPos> knownOres(MineCompanionTask task) throws Exception {
        var field = MineCompanionTask.class.getDeclaredField("knownOres");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var ores = (java.util.List<BlockPos>) field.get(task);
        return ores;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
