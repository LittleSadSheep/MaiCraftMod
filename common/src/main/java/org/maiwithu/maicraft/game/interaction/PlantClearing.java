// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 近战时准星前面挡着草、花、树上长的树叶：原版准星先点到它，打不着后面的怪。
 * 像人一样先把它砍掉（草一下就掉，树叶要砍几下），再打目标。只砍这些天然长的，火把、红石、人放的树叶不碰。
 * 实机 10-14 点名杀羊，羊卡在树冠里，准星一直点在树叶上，三十秒一下没挥。
 */
final class PlantClearing {

    /** 砍一格最多砍这么多刻；空手砍树叶六刻、剑四刻，到时还没掉就停手。 */
    private static final int TIMEOUT_TICKS = 20;

    private final InteractionSender sender;
    private PendingInteraction pending;
    private int ticks;

    PlantClearing(InteractionSender sender) {
        this.sender = sender;
    }

    /** 正在砍挡路的植物：这一刻接着砍（树叶要按住砍几刻），不出手打目标。 */
    boolean busy(PlayerContext context) {
        if (pending == null) return false;
        if (!pending.terminal()) {
            try {
                pending = sender.continueBreaking(context, pending);
            } catch (IllegalStateException noChance) {
                // 这一刻没有交互机会：下一刻接着砍。
            }
        }
        if (pending.terminal()) {
            pending = null;
            ticks = 0;
            return false;
        }
        if (++ticks > TIMEOUT_TICKS) stop(context);
        return true;
    }

    /** 准星打到的是挡路的草叶就开砍，返回真；不是、或这一刻砍不了返回假。 */
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

    // 树上长的树叶（人放的不算）；没有碰撞的草、蕨、藤蔓、花。流体、火把、红石线这类不碰。
    private static boolean trivial(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof LeavesBlock) {
            return !(state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT));
        }
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()
                && !state.isAir() && (state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.FLOWERS));
    }
}
