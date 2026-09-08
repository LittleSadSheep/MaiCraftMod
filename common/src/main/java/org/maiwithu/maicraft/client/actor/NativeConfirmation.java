// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only postcondition used to reconcile one submitted native action. */
@FunctionalInterface
public interface NativeConfirmation {
    enum Verdict { PENDING, APPLIED, NOT_APPLIED, DIVERGED }

    Verdict observe(LocalPlayerContext context);
    default int stableTicksRequired() { return 2; }
    /** A newly received entity/vehicle identity already comes from the server's entity stream. */
    static NativeConfirmation serverObservedEntity(NativeConfirmation evidence) {
        return new NativeConfirmation() {
            public Verdict observe(LocalPlayerContext context) { return evidence.observe(context); }
            public int stableTicksRequired() { return 1; }
        };
    }

    public static NativeConfirmation blockBecomesAir(BlockPos target, BlockState before) {
        BlockPos frozen = target.immutable();
        return context -> {
            if (!context.level().isLoaded(frozen)) return Verdict.PENDING;
            return breakReplacementVerdict(before, context.level().getBlockState(frozen));
        };
    }

    /** Vanilla removal leaves the original fluid behind when breaking a waterlogged block. */
    static Verdict breakReplacementVerdict(BlockState before, BlockState live) {
        if (live.isAir()) return Verdict.APPLIED;
        if (live.equals(before)) return Verdict.PENDING;
        if (!before.getFluidState().isEmpty()
                && live.equals(before.getFluidState().createLegacyBlock())) return Verdict.APPLIED;
        return Verdict.DIVERGED;
    }

    public static NativeConfirmation blockState(BlockPos target, BlockState before, BlockState expected) {
        BlockPos frozen = target.immutable();
        return context -> {
            if (!context.level().isLoaded(frozen)) return Verdict.PENDING;
            BlockState live = context.level().getBlockState(frozen);
            if (live.equals(expected)) return Verdict.APPLIED;
            if (live.equals(before)) return Verdict.PENDING;
            return Verdict.DIVERGED;
        };
    }

    public static NativeConfirmation blockChanged(BlockPos target, BlockState before) {
        BlockPos frozen = target.immutable();
        return context -> !context.level().isLoaded(frozen)
                ? Verdict.PENDING
                : context.level().getBlockState(frozen).equals(before) ? Verdict.PENDING : Verdict.APPLIED;
    }

    public static NativeConfirmation menuChanged(int beforeContainerId) {
        return context -> context.player().containerMenu.containerId == beforeContainerId
                ? Verdict.PENDING : Verdict.APPLIED;
    }

    public static NativeConfirmation heldItemChanged(InteractionHand hand, ItemStack before) {
        ItemStack frozen = before.copy();
        return context -> sameStack(context.player().getItemInHand(hand), frozen)
                ? Verdict.PENDING : Verdict.APPLIED;
    }

    public static NativeConfirmation inventorySlot(int slot, ItemStack expected) {
        ItemStack frozen = expected.copy();
        return context -> {
            if (slot < 0 || slot >= context.player().getInventory().getContainerSize()) {
                return Verdict.DIVERGED;
            }
            return sameStack(context.player().getInventory().getItem(slot), frozen)
                    ? Verdict.APPLIED : Verdict.PENDING;
        };
    }

    public static NativeConfirmation entityHurt(Entity entity) {
        int id = entity.getId();
        float health = entity instanceof LivingEntity living ? living.getHealth() : Float.NaN;
        return context -> {
            Entity live = context.level().getEntity(id);
            if (!(live instanceof LivingEntity living)) return Verdict.PENDING;
            if (!living.isAlive() || living.getHealth() < health) return Verdict.APPLIED;
            return Verdict.PENDING;
        };
    }

    public static NativeConfirmation anyOf(NativeConfirmation... confirmations) {
        NativeConfirmation[] frozen = Arrays.copyOf(confirmations, confirmations.length);
        if (frozen.length == 0) throw new IllegalArgumentException("at least one confirmation is required");
        return context -> {
            boolean pending = false;
            boolean notApplied = false;
            for (NativeConfirmation confirmation : frozen) {
                Verdict verdict = confirmation.observe(context);
                if (verdict == Verdict.APPLIED) return Verdict.APPLIED;
                pending |= verdict == Verdict.PENDING;
                notApplied |= verdict == Verdict.NOT_APPLIED;
            }
            if (pending) return Verdict.PENDING;
            return notApplied ? Verdict.NOT_APPLIED : Verdict.DIVERGED;
        };
    }

    public static NativeConfirmation itemUseStopped() {
        return context -> context.player().isUsingItem() ? Verdict.PENDING : Verdict.APPLIED;
    }

    public static NativeConfirmation hotbarSelected(int slot) {
        return context -> context.player().getInventory().selected == slot
                ? Verdict.APPLIED : Verdict.NOT_APPLIED;
    }

    public static NativeConfirmation pending() {
        return context -> Verdict.PENDING;
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount() && ItemStack.isSameItemSameComponents(left, right);
    }
}
