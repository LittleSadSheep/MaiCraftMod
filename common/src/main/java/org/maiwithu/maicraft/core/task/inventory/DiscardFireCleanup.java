// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.ArrayDeque;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseFireBlock;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.task.TaskState;

/** 点火任务取消后仍结清已经授权的扑火；只在原身体重新获得自动控制时执行，不抢玩家的按键。 */
public final class DiscardFireCleanup {
    private record Pending(LocalPlayer player, Object world, BlockPos cell, long awaitUntil) {}
    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private static DiscardBlockAction action;
    private DiscardFireCleanup() {}
    static void enqueue(LocalPlayer player, Set<BlockPos> cells) {
        enqueue(player, cells, 0);
    }
    static void awaitLateFire(LocalPlayer player, Set<BlockPos> cells) {
        // 点火已经提交却尚未同步时，短期被动观察原格；空地时不占身体，迟到的火出现后再扑灭。
        enqueue(player, cells, player.level().getGameTime() + 100);
    }
    private static void enqueue(LocalPlayer player, Set<BlockPos> cells, long deadline) {
        for (BlockPos cell : cells) {
            var request = new Pending(player, player.level(), cell.immutable(), deadline);
            if (!pending.contains(request)) pending.addLast(request);
        }
    }
    public static boolean tick(LocalPlayerContext context) {
        int available = pending.size();
        while (!pending.isEmpty() && available-- > 0) {
            var request = pending.getFirst();
            if (request.player != context.player() || request.world != context.level()) {
                pending.removeFirst(); action = null; continue;
            }
            if (action == null && context.level().getGameTime() < request.awaitUntil
                    && context.level().isLoaded(request.cell) && !(context.level().getBlockState(request.cell).getBlock() instanceof BaseFireBlock)) {
                pending.addLast(pending.removeFirst()); continue;
            }
            if (action == null) action = new DiscardBlockAction(request.cell, DiscardBlockAction.Kind.EXTINGUISH);
            TaskState state = action.tick(context);
            if (state != TaskState.RUNNING) { action.close(context); pending.removeFirst(); action = null; }
            return true;
        }
        return false;
    }
}
