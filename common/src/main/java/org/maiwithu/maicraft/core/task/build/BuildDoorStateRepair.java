// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 施工收尾时把已安装木门开到或关到作者要求的状态：走近、空手瞄准、右键一次，等服务器确认上下两半都正确。
 * 只处理可手动开启且未受红石供电的完整门；材质、明确要求的朝向等其他差异不会在这里靠拆换解决。
 */
final class BuildDoorStateRepair {
    private final LocalPlayer player;
    private final BuildTaskRecord.Target target;
    private final Map<Long, BuildTaskRecord.Target> declared;
    private final Predicate<BlockPos> allowed;
    private final BiFunction<LocalPlayer, Double, HitResult> ray;
    private final ActualViewConvergenceGate aim = new ActualViewConvergenceGate();
    private final FirstPersonActionGate handSelection = new FirstPersonActionGate();
    private final VisibleMenuSession menu = new VisibleMenuSession();
    private PlayerNav nav;
    private NativeActionReceipt receipt;
    private int emptySlot = -1;
    private String failure = "door state repair was not confirmed";
    private FailureType failureType = FailureType.TARGET_LOST;
    private boolean changed, uncertain;

    BuildDoorStateRepair(LocalPlayer player, BuildTaskRecord.Target target,
                         Predicate<BlockPos> allowed, BiFunction<LocalPlayer, Double, HitResult> ray) {
        this(player, target, Map.of(), allowed, ray);
    }

    BuildDoorStateRepair(LocalPlayer player, BuildTaskRecord.Target target, Map<Long, BuildTaskRecord.Target> declared,
                         Predicate<BlockPos> allowed, BiFunction<LocalPlayer, Double, HitResult> ray) {
        this.player = player; this.target = target; this.declared = declared; this.allowed = allowed; this.ray = ray;
    }

    /**
     * 先确认只改变开关状态就能同时满足上下两半的要求，且两格都允许操作；没有办法靠这一次开关满足的情况交回父任务判断。
     */
    static boolean canRepair(BuildTaskRecord.Target target, Map<Long, BuildTaskRecord.Target> declared,
                                    Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states,
                                    Predicate<BlockPos> allowed) {
        return canRepair(target, BuildPlacementGeometry.generatedBy(target), declared, loaded, states, allowed);
    }

    static boolean canRepair(BuildTaskRecord.Target target,
                             List<BuildPlacementGeometry.GeneratedCell> generated,
                             Map<Long, BuildTaskRecord.Target> declared, Predicate<BlockPos> loaded,
                             Function<BlockPos, BlockState> states, Predicate<BlockPos> allowed) {
        BlockPos lower = target.pos(), upper = lower.above();
        BlockState expected = target.desiredState();
        if (!(expected.getBlock() instanceof DoorBlock)
                || expected.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
                || generated.size() != 1 || !generated.getFirst().pos().equals(upper)
                || !generated.getFirst().expected().equals(expected.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
                || !loaded.test(lower) || !loaded.test(upper) || !allowed.test(lower) || !allowed.test(upper)) return false;
        BlockState live = states.apply(lower), liveUpper = states.apply(upper);
        if (!(live.getBlock() instanceof DoorBlock door) || !door.type().canOpenByHand()
                || !live.is(expected.getBlock()) || live.hasBlockEntity() || liveUpper.hasBlockEntity()
                || live.getValue(DoorBlock.POWERED) || live.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
                || !liveUpper.equals(live.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))) return false;
        BlockState adjusted = live.setValue(DoorBlock.OPEN, requestedOpen(target, declared));
        return !pairMatches(target, declared, live, liveUpper) && target.matches(adjusted)
                && upperMatches(target, declared, adjusted.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    TaskState tick() {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            InputDriver.halt(player);
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED && exact()) {
                changed = true; return TaskState.SUCCESS;
            }
            uncertain = receipt.status() == NativeActionReceipt.Status.UNCERTAIN
                    || receipt.status() == NativeActionReceipt.Status.DIVERGED;
            return fail("native door use did not confirm both authored halves: " + receipt.detail(), FailureType.TARGET_LOST);
        }
        if (exact()) { stopNav(); return TaskState.SUCCESS; }
        if (!canRepair(target, BuildPlacementGeometry.generatedBy(target), declared,
                player.level()::isLoaded, player.level()::getBlockState, allowed))
            return fail("door identity, orientation, power or protection changed before use", FailureType.TARGET_LOST);
        if (visible() == null || !player.onGround()) {
            if (nav == null) nav = PlayerNav.to(player, () -> GoalCompiler.interact(target.pos()), 1,
                    () -> player.onGround() && visible() != null, PlayerNav.ContextProvider.DEFAULT).walkingOnly();
            return switch (nav.tick()) {
                case RUNNING -> TaskState.RUNNING;
                case FAILED -> fail("could not reach door for final state repair: " + nav.failReason(), nav.failType());
                case ARRIVED -> { stopNav(); yield visible() == null
                        ? fail("door has no visible interaction face", FailureType.OCCLUDED) : TaskState.RUNNING; }
            };
        }
        stopNav(); InputDriver.halt(player);
        // Avoid held axes, buckets and other item-specific effects on wooden or copper doors.
        if (!menu.worldReady(context)) return TaskState.RUNNING;
        // 当前主手有物品时会优先用空副手；但原版木门的空手开关分支只处理主手，这个选择可能使收尾点击不生效。
        InteractionHand hand = player.getMainHandItem().isEmpty() ? InteractionHand.MAIN_HAND
                : player.getOffhandItem().isEmpty() ? InteractionHand.OFF_HAND : null;
        // 两手都不空时才寻找普通背包空格，并通过已有物品选择器腾出主手；已开始的切换必须先完成。
        if (handSelection.started() || hand == null) {
            if (emptySlot < 0) for (int i = 0; i < 36; i++)
                if (player.getInventory().getItem(i).isEmpty()) { emptySlot = i; break; }
            if (emptySlot < 0) return fail("door state repair needs an empty hand or inventory slot", FailureType.NO_SUPPORT);
            var selected = handSelection.select(player, emptySlot);
            if (selected == FirstPersonActionGate.Status.FAILED) return fail(handSelection.failure(), FailureType.UNKNOWN);
            if (selected != FirstPersonActionGate.Status.READY) return TaskState.RUNNING;
            if (!player.getMainHandItem().isEmpty()) return fail("empty interaction hand changed", FailureType.TARGET_LOST);
            hand = InteractionHand.MAIN_HAND;
        }
        BlockHitResult visible = visible();
        if (visible == null) return fail("door face became occluded before use", FailureType.OCCLUDED);
        InputDriver.lookAt(player, visible.getLocation());
        if (!aim.ready(player, visible.getLocation().subtract(player.getEyePosition()))
                || player.isShiftKeyDown() || !context.mutationAvailable()) return TaskState.RUNNING;
        HitResult hit = ray.apply(player, 4.5);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK
                || !blockHit.getBlockPos().equals(target.pos()))
            return fail("native crosshair does not hit the intended door", FailureType.OCCLUDED);
        BlockState before = player.level().getBlockState(target.pos());
        receipt = context.actions().useBlock(context, hand, blockHit,
                confirmation(target.pos(), before, before.setValue(DoorBlock.OPEN,
                        requestedOpen(target, declared))), 40);
        return TaskState.RUNNING;
    }

    private boolean exact() {
        return player.level().isLoaded(target.pos()) && player.level().isLoaded(target.pos().above())
                && pairMatches(target, declared, player.level().getBlockState(target.pos()),
                        player.level().getBlockState(target.pos().above()));
    }

    private static boolean pairMatches(BuildTaskRecord.Target target, Map<Long, BuildTaskRecord.Target> declared,
                                       BlockState lower, BlockState upper) {
        return target.matches(lower) && upperMatches(target, declared, upper);
    }
    // 下半没有明确要求开关时，可以采用已声明上半的要求；否则以下半要求为准，并仍检查两半是否能同时满足。
    private static boolean requestedOpen(BuildTaskRecord.Target target, Map<Long, BuildTaskRecord.Target> declared) {
        var upper = declared.get(target.pos().above().asLong());
        var required = target.finalProperties() == null ? target.exactProperties() : target.finalProperties();
        return upper != null && upper.desiredState().hasProperty(DoorBlock.OPEN) && !required.contains("open")
                ? upper.desiredState().getValue(DoorBlock.OPEN) : target.desiredState().getValue(DoorBlock.OPEN);
    }
    private static boolean upperMatches(BuildTaskRecord.Target target, Map<Long, BuildTaskRecord.Target> declared,
                                        BlockState upper) {
        var authored = declared.get(target.pos().above().asLong());
        return authored != null ? authored.matches(upper) : upper.is(target.block())
                && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                && target.matches(upper.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
    }

    private BlockHitResult visible() {
        return FirstPersonInteractionTargeting.visibleBlockHit(player.level(), player,
                player.getEyePosition(), target.pos(), 4.5);
    }

    // 客户端看到门变化还不能结束，外层还要等服务器确认这次右键；上下半只改变一半时继续等，变成无关状态则失败。
    static NativeConfirmation confirmation(BlockPos pos, BlockState before, BlockState expected) {
        return new NativeConfirmation() {
            public boolean requiresBlockAcknowledgement() { return true; }
            public Verdict observe(LocalPlayerContext context) { return observe(context, false); }
            public Verdict observeAcknowledged(LocalPlayerContext context) { return observe(context, true); }
            private Verdict observe(LocalPlayerContext context, boolean acknowledged) {
                if (!context.level().isLoaded(pos) || !context.level().isLoaded(pos.above())) return Verdict.PENDING;
                BlockState lower = context.level().getBlockState(pos), upper = context.level().getBlockState(pos.above());
                BlockState oldUpper = before.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
                BlockState desiredUpper = expected.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
                if (lower.equals(expected) && upper.equals(desiredUpper)) return Verdict.APPLIED;
                if (!lower.equals(before) && !lower.equals(expected) || !upper.equals(oldUpper) && !upper.equals(desiredUpper))
                    return Verdict.DIVERGED;
                return acknowledged && lower.equals(before) && upper.equals(oldUpper) ? Verdict.NOT_APPLIED : Verdict.PENDING;
            }
        };
    }

    private TaskState fail(String detail, FailureType type) {
        stopNav(); failure = detail; failureType = type; return TaskState.FAILED;
    }
    String failure() { return failure; }
    FailureType failureType() { return failureType; }
    boolean changed() { return changed; }
    boolean uncertain() { return uncertain; }
    void pause() { stopNav(); aim.reset(); InputDriver.halt(player); }
    // 结束时松开输入、收回临时物品选择和菜单操作，并作废仍在等待的点击记录；不再额外点一次门。
    void stop() {
        pause(); handSelection.reset(); menu.cleanup(player);
        if (receipt != null && !receipt.terminal()) ClientRuntime.actor().activeContext()
                .filter(context -> context.player() == player).ifPresent(context -> context.actions()
                        .retireOneShotForTaskBoundary(context, receipt, "build door state repair ended"));
    }
    private void stopNav() { if (nav != null) { nav.stop(); nav = null; } }
}
