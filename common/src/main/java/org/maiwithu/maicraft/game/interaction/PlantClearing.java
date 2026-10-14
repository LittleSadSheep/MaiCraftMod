// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 近战时准星前面挡着草、花这类没有碰撞、一碰就掉的方块：原版准星先点到它，打不着后面的怪。
 * 像人一样先一刀把它砍掉，下一下再打目标。只砍这种一下就掉的，别的方块不碰。
 * 实机 10-14 点名杀羊，羊就在两格外，眼前一丛高草把准星挡住，三十秒一下没挥。
 */
final class PlantClearing {

    /** 砍一下最多等这么多刻确认；到时还没掉就停手。 */
    private static final int TIMEOUT_TICKS = 10;

    private final InteractionSender sender;
    private PendingInteraction pending;
    private int ticks;

    PlantClearing(InteractionSender sender) {
        this.sender = sender;
    }

    /** 正在砍挡路的植物：这一刻不出手打目标。 */
    boolean busy(PlayerContext context) {
        if (pending == null) return false;
        if (!pending.terminal()) pending = sender.poll(context, pending);
        if (pending.terminal()) {
            pending = null;
            ticks = 0;
            return false;
        }
        if (++ticks > TIMEOUT_TICKS) stop(context);
        return true;
    }

    /** 准星打到的是挡路的小植物就开砍，返回真；不是、或这一刻砍不了返回假。 */
    boolean clear(PlayerContext context, HitResult aimed) {
        if (!(aimed instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !trivial(context.level(), hit.getBlockPos()) || !context.canInteractThisTick()) {
            return false;
        }
        try {
            pending = sender.startBreaking(context, hit, TIMEOUT_TICKS);
        } catch (IllegalStateException busy) {
            return false;
        }
        ticks = 0;
        return true;
    }

    /** 不打了：砍到一半的停手。 */
    void stop(PlayerContext context) {
        if (pending != null && !pending.terminal() && context != null && context.isCurrent()) {
            try {
                sender.cancelBreaking(context, pending);
            } catch (RuntimeException ignored) {
                // 已经结清或换了角色：没有要停的。
            }
        }
        pending = null;
        ticks = 0;
    }

    // 没有碰撞、一下就掉、不是流体：草、花、蕨这类。
    private static boolean trivial(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getDestroySpeed(level, pos) == 0.0f
                && state.getFluidState().isEmpty();
    }
}
