// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** 操作后如何检查结果：这些回调只读当前客户端事实，本身不会再发点击，也不自动证明服务器已确认。 */
@FunctionalInterface
public interface NativeConfirmation {
    enum Verdict { PENDING, APPLIED, NOT_APPLIED, DIVERGED }

    Verdict observe(LocalPlayerContext context);
    default int stableTicksRequired() { return 2; }
    /** 调用方已拥有服务器发来的新实体证据时，不再多等一次稳定刻。 */
    static NativeConfirmation serverObservedEntity(NativeConfirmation evidence) {
        return new NativeConfirmation() {
            public Verdict observe(LocalPlayerContext context) { return evidence.observe(context); }
            public int stableTicksRequired() { return 1; }
        };
    }

    public static NativeConfirmation blockBecomesAir(BlockPos target, BlockState before) {
        // 挖掉目标后通常变空气；含水方块被挖掉留下原液体也算符合预期。
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
        // 变成期望状态算匹配，还是原样继续等，变成第三种状态则报告不一致；读到的世界也可能包含本地预测。
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
        // 这里只检查“变过了”，并不证明变成了哪种指定效果，需要精确结果的调用方不能只用这一项。
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
        // 比较指定物品栏格的种类、附加属性和数量；槽位不存在说明原上下文已经不适用。
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
        // 按原实体编号寻找目标，看到血量下降或死亡即匹配；这不单独区分是哪一次攻击造成的伤害。
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
        // 任一条件匹配就通过；没有匹配但还有等待项就继续等，全部结束后再综合未生效或不一致。
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
        // 只检查客户端当前选中的快捷栏编号；selectHotbar 自己会先设置这个本地字段，不是读取服务器确认包。
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
