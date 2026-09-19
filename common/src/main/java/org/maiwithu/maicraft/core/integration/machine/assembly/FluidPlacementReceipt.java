// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.actor.BlockUseAcknowledgement;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;

/** 一次桶操作的只读凭据：服务器已校正对应使用序号、目标成为源格、满桶减少和空桶返还必须一起成立。 */
final class FluidPlacementReceipt implements NativeConfirmation {
    private final LocalPlayer player;
    private final Level world;
    private final BlockPos target;
    private final BlockState before, expected;
    private final ItemStack filled, returned;
    private final boolean free;
    private final int beforeFilled, beforeReturned, beforeSequence;
    private final BlockUseAcknowledgement acknowledgements;
    private int sequence = -1;

    FluidPlacementReceipt(LocalPlayer player, BlockPos target, BlockState expected) {
        this.player = player; world = player.level(); this.target = target; this.expected = expected;
        before = world.getBlockState(target); filled = player.getMainHandItem().copyWithCount(1);
        returned = BucketItem.getEmptySuccessItem(filled.copy(), player).copy(); free = player.hasInfiniteMaterials();
        beforeFilled = count(player, filled); beforeReturned = count(player, returned);
        if (!(world instanceof BlockUseAcknowledgement hook)) throw new IllegalStateException("fluid_placement_acknowledgement_unavailable");
        acknowledgements = hook; beforeSequence = hook.maicraft$currentBlockSequence();
    }

    void submitted() { sequence = acknowledgements.maicraft$currentBlockSequence(); }
    @Override public int stableTicksRequired() { return 1; }

    @Override public Verdict observe(LocalPlayerContext context) {
        if (context.player() != player || player.level() != world || player.hasInfiniteMaterials() != free) return Verdict.DIVERGED;
        // useItem 内部会先做一次本地轮询，此时尚未登记新序号；预测水源不能提前被当作服务器已接受。
        if (sequence <= beforeSequence || acknowledgements.maicraft$acknowledgedBlockSequence() < sequence) return Verdict.PENDING;
        if (!world.isLoaded(target)) return Verdict.PENDING;
        return compare(world.getBlockState(target), before, expected, beforeFilled, beforeReturned,
                count(player, filled), count(player, returned), free);
    }

    static Verdict compare(BlockState actual, BlockState before, BlockState expected, int beforeFilled, int beforeReturned,
                           int afterFilled, int afterReturned, boolean free) {
        int spent = free ? 0 : 1;
        if (afterFilled < beforeFilled - spent || afterFilled > beforeFilled
                || afterReturned < beforeReturned || afterReturned > beforeReturned + spent) return Verdict.DIVERGED;
        boolean source = FluidPlacementRules.matches(actual, expected);
        if (!source && !actual.equals(before) && !FluidPlacementRules.sameFluid(actual, expected)) return Verdict.DIVERGED;
        return source && afterFilled == beforeFilled - spent && afterReturned == beforeReturned + spent ? Verdict.APPLIED : Verdict.PENDING;
    }

    private static int count(LocalPlayer player, ItemStack kind) {
        // 桶的其他组件参与物品身份；物品栏位置变化不会丢账，另一种命名或配置的桶不能顶替本次返还。
        return player.getInventory().items.stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, kind))
                .mapToInt(ItemStack::getCount).sum();
    }
}
