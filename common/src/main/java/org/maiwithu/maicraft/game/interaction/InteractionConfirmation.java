// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 操作后如何检查结果：这些回调只读当前客户端事实，本身不会再发点击，也不自动证明服务器已确认。 */
@FunctionalInterface
public interface InteractionConfirmation {
    enum Verdict { PENDING, APPLIED, NOT_APPLIED, DIVERGED }

    Verdict observe(PlayerContext context);
    default int stableTicksRequired() { return 2; }
    /** 放置方块后，确认可能还需等待服务端完成本次交互的预测校正。 */
    default boolean requiresBlockAcknowledgement() { return false; }
    default Verdict observeAcknowledged(PlayerContext context) { return observe(context); }
    /** 调用方已拥有服务器发来的新实体证据时，不再多等一次稳定刻。 */
    static InteractionConfirmation serverObservedEntity(InteractionConfirmation evidence) {
        return new InteractionConfirmation() {
            public Verdict observe(PlayerContext context) { return evidence.observe(context); }
            public int stableTicksRequired() { return 1; }
        };
    }

    public static InteractionConfirmation blockBecomesAir(BlockPos target, BlockState before) {
        // 挖掉目标后通常变空气；含水方块被挖掉留下原液体也算符合预期。
        BlockPos frozen = target.immutable();
        return context -> {
            if (!context.level().isLoaded(frozen)) return Verdict.PENDING;
            return breakReplacementVerdict(before, context.level().getBlockState(frozen));
        };
    }

    /** 原版挖掉含水方块后会留下原有流体，不能把留下的水误判为破坏失败。 */
    static Verdict breakReplacementVerdict(BlockState before, BlockState live) {
        if (live.isAir()) return Verdict.APPLIED;
        if (live.equals(before)) return Verdict.PENDING;
        if (!before.getFluidState().isEmpty()
                && live.equals(before.getFluidState().createLegacyBlock())) return Verdict.APPLIED;
        return Verdict.DIVERGED;
    }

    public static InteractionConfirmation blockState(BlockPos target, BlockState before, BlockState expected) {
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

    public static InteractionConfirmation blockChanged(BlockPos target, BlockState before) {
        // 这里只检查“变过了”，并不证明变成了哪种指定效果，需要精确结果的调用方不能只用这一项。
        BlockPos frozen = target.immutable();
        return context -> !context.level().isLoaded(frozen)
                ? Verdict.PENDING
                : context.level().getBlockState(frozen).equals(before) ? Verdict.PENDING : Verdict.APPLIED;
    }

    public static InteractionConfirmation menuChanged(int beforeContainerId) {
        return context -> context.localPlayer().containerMenu.containerId == beforeContainerId
                ? Verdict.PENDING : Verdict.APPLIED;
    }

    /**
     * 右键告示牌的原生效果是打开编辑屏：不产生容器菜单、不改方块状态也不换手中物品，
     * 编辑屏出现本身就是这次点击已生效的客户端权威事实。
     */
    public static InteractionConfirmation signEditorScreenOpened() {
        return context -> {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft != null && minecraft.screen instanceof AbstractSignEditScreen
                    ? Verdict.APPLIED : Verdict.PENDING;
        };
    }

    /**
     * 打火石类点火的世界效果：落格出现火，或在有效门框中直接成为下界传送门方块。
     * 手持物耐久变化只证明原版接受了使用，不能证明世界效果；黑曜石等不可燃支撑旁的火
     * 会自然熄灭，事后扫描看不到火也不构成「点火没发生」的证据。
     */
    public static InteractionConfirmation ignitionWorldEffect(BlockPos cell) {
        BlockPos frozen = cell.immutable();
        return context -> !context.level().isLoaded(frozen) ? Verdict.PENDING
                : ignitionEffect(context.level().getBlockState(frozen)) ? Verdict.APPLIED : Verdict.PENDING;
    }

    /** 火与传送门方块共用同一判定口径；点击确认与点火检查都引用它，避免两处各认一半。 */
    public static boolean ignitionEffect(BlockState state) {
        return state.getBlock() instanceof BaseFireBlock
                || state.getBlock() instanceof NetherPortalBlock;
    }

    /**
     * 右键马、船、矿车的原生效果是骑上去：不换手、不开界面也不改方块，角色的坐骑变成这只实体
     * 本身就是这次点击已生效的客户端权威事实（服务端同意骑乘后才把乘客关系同步下来）。
     */
    public static InteractionConfirmation ridingOn(Entity vehicle) {
        int id = vehicle.getId();
        return context -> {
            Entity riding = context.localPlayer().getVehicle();
            return riding != null && riding.getId() == id ? Verdict.APPLIED : Verdict.PENDING;
        };
    }

    public static InteractionConfirmation heldItemChanged(InteractionHand hand, ItemStack before) {
        ItemStack frozen = before.copy();
        return context -> sameStack(context.localPlayer().getItemInHand(hand), frozen)
                ? Verdict.PENDING : Verdict.APPLIED;
    }

    public static InteractionConfirmation inventorySlot(int slot, ItemStack expected) {
        // 比较指定物品栏格的种类、附加属性和数量；槽位不存在说明原上下文已经不适用。
        ItemStack frozen = expected.copy();
        return context -> {
            if (slot < 0 || slot >= context.localPlayer().getInventory().getContainerSize()) {
                return Verdict.DIVERGED;
            }
            return sameStack(context.localPlayer().getInventory().getItem(slot), frozen)
                    ? Verdict.APPLIED : Verdict.PENDING;
        };
    }

    /**
     * 近战出手的结算证据：目标死了、血量比出手前低、或正处在受击红闪里，都是打中的客户端权威事实；
     * 这些一窗口内都没出现时，攻击充能被清零也算数——原版攻击入口挥出这一刀时会清零充能，
     * 出手前充能已回到位（出手门槛更高），窗口内再低于出手线只能是这次挥击留下的。
     * 打中了和目标最终被打死分开判断：确认这一刀后目标还活着，由上层任务接着决定要不要再打。
     */
    public static InteractionConfirmation entityStruck(LocalPlayer attacker, Entity target) {
        int id = target.getId();
        float health = target instanceof LivingEntity living ? living.getHealth() : Float.NaN;
        return context -> {
            // 目标实体不在了分不清是这次打死的还是别的原因，维持等待由超期如实收场。
            if (!(context.level().getEntity(id) instanceof LivingEntity living)) return Verdict.PENDING;
            return strikeVerdict(living.isAlive(), living.getHealth(), health,
                    living.hurtTime, attacker.getAttackStrengthScale(0.0f));
        };
    }

    /**
     * 近战出手的判定表（离线可测）：命中三证据（死亡、掉血、受击红闪）任一成立即确认；
     * 都没成立而攻击充能低于出手线，说明挥击已发生（充能清零的恢复要十几刻，窗口内回不到出手线以上）。
     */
    static Verdict strikeVerdict(boolean targetAlive, float liveHealth, float healthBefore,
                                 int hurtTime, float attackScale) {
        if (!targetAlive || liveHealth < healthBefore || hurtTime > 0) return Verdict.APPLIED;
        return attackScale < 0.9f ? Verdict.APPLIED : Verdict.PENDING;
    }

    public static InteractionConfirmation anyOf(InteractionConfirmation... confirmations) {
        // 任一条件匹配就通过；没有匹配但还有等待项就继续等，全部结束后再综合未生效或不一致。
        InteractionConfirmation[] frozen = Arrays.copyOf(confirmations, confirmations.length);
        if (frozen.length == 0) throw new IllegalArgumentException("at least one confirmation is required");
        return context -> {
            boolean pending = false;
            boolean notApplied = false;
            for (InteractionConfirmation confirmation : frozen) {
                Verdict verdict = confirmation.observe(context);
                if (verdict == Verdict.APPLIED) return Verdict.APPLIED;
                pending |= verdict == Verdict.PENDING;
                notApplied |= verdict == Verdict.NOT_APPLIED;
            }
            if (pending) return Verdict.PENDING;
            return notApplied ? Verdict.NOT_APPLIED : Verdict.DIVERGED;
        };
    }

    public static InteractionConfirmation itemUseStopped() {
        return context -> context.localPlayer().isUsingItem() ? Verdict.PENDING : Verdict.APPLIED;
    }

    public static InteractionConfirmation hotbarSelected(int slot) {
        // 只检查客户端当前选中的快捷栏编号；selectHotbar 自己会先设置这个本地字段，不是读取服务器确认包。
        return context -> context.localPlayer().getInventory().selected == slot
                ? Verdict.APPLIED : Verdict.NOT_APPLIED;
    }

    public static InteractionConfirmation pending() {
        return context -> Verdict.PENDING;
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount() && ItemStack.isSameItemSameComponents(left, right);
    }
}
