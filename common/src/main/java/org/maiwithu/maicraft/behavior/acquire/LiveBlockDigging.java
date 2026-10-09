// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.survival.BlockBreaking;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖方块的生产实现：每要挖一格，就请一套原生挖掘基础代码上场，
 * 瞄准、选工具、持续挖和等确认都在里面，这里只把一次挖掘包成"挖到这一格没了才算做完"的动作。
 *
 * <p>原生挖掘每挖开一格只报一次进展、从不自己说做完（它也给刨出这种逐刻换格子的用）；
 * 这里每刻看一眼目标格：变成空气，或者只剩原来泡着的水（含水方块挖掉后留下的流体），就是挖完了。
 * 挖的许可（动谁的方块）由调用方的许可检查先把关，到这里的一格都是获准动土的。
 */
public final class LiveBlockDigging implements DigsBlocks {

    private final Supplier<BlockBreaking> diggings;

    public LiveBlockDigging(Supplier<BlockBreaking> diggings) {
        this.diggings = diggings;
    }

    @Override
    public Optional<Action> dig(BlockPos target) {
        // 挖掘本身就是一个动作：瞄准这一格交给它，推进、被打断停手、收尾结清都由它自己管。
        BlockBreaking digging = diggings.get();
        digging.aimAt(target.immutable());
        return Optional.of(new UntilGone(target.immutable(), digging));
    }

    /** 挖到目标格没了才做完：每刻先看现场，还在就再挖一刻。 */
    private record UntilGone(BlockPos cell, BlockBreaking digging) implements Action {

        @Override public ActionStatus tick(TickContext context) {
            if (gone(context.player())) {
                return ActionStatus.done();
            }
            return digging.tick(context);
        }

        // 目标格没了：空气，或只剩流体（含水的方块挖掉后留下原来的水）。没加载时不冒充挖完了。
        private boolean gone(PlayerContext player) {
            if (player == null || player.level() == null || !player.level().hasChunkAt(cell)) return false;
            BlockState now = player.level().getBlockState(cell);
            return now.isAir() || now.getBlock() instanceof LiquidBlock;
        }

        @Override public void pause() {
            digging.pause();
        }

        @Override public void close() {
            digging.close();
        }

        @Override public Interruptibility interruptibility() {
            return digging.interruptibility();
        }

        @Override public String describe() {
            return digging.describe();
        }
    }
}
