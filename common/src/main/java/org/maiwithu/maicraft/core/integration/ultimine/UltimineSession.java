// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ultimine;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import java.util.Map;

/**
 * 只负责连锁挖掘的就绪检查和持键：准星命中面 → 原生完整选区 → 持住启用键挖到确认 → 松开。
 * 实际开始、持续破坏和确认方块消失仍由所属挖掘任务按普通游戏流程完成。
 */
public final class UltimineSession implements AutoCloseable {
    public enum Status { WAITING, READY, SINGLE_BLOCK, BLOCKED, ABORT }
    public record Decision(Status status, String code, List<BlockPos> completeSelection, List<BlockPos> potentialSelection) {
        public Decision { completeSelection = List.copyOf(completeSelection); potentialSelection = List.copyOf(potentialSelection); }
        public boolean ready() { return status == Status.READY; }
    }
    private BlockPos origin;
    private Direction face;
    private LocalPlayer player;
    private Object world;
    private long bodyEpoch, controlRevision, openedTick, initialRevision, stableRevision = -1, stableTick, lastScroll;
    private int awaitingIndex = -1, scrolls;
    private ItemStack tool;
    private UltimineSelectionPolicy.Admission admitted;
    private Set<BlockPos> permitted = Set.of();
    private Predicate<BlockPos> preserved = ignored -> false;
    private String fallback;
    private long releaseBegan;
    private boolean acquired, inFlight, closed;
    private int heldBreakTicks;
    private long lastHeldTick = Long.MIN_VALUE;
    private boolean lastNativePressed, lastKeyDown;

    /** 工具已放入并选中快捷栏、可见准星已对准目标面后，才准备原生连锁。 */
    public Decision prepare(LocalPlayerContext context, BlockHitResult hit, Set<BlockPos> allowedClearCells, Predicate<BlockPos> preserve) {
        try {
            if (inFlight) return abort("ultimine_inflight_requires_keep_alive");
            if (!eligible(context) || hit == null || hit.getType() != HitResult.Type.BLOCK) return abort("ultimine_actor_or_hit_unavailable");
            if (!UltimineNative.available()) { close(); return result(Status.SINGLE_BLOCK, "ultimine_not_installed"); }
            if (UltimineInputLease.humanHeld(context)) { close(); return result(Status.BLOCKED, "ultimine_human_key_held"); }
            if (closed) return released(context, fallback == null ? "ultimine_session_finished" : fallback);
            if (!sameHit(context, hit)) return result(Status.WAITING, "ultimine_waiting_for_exact_crosshair");
            permitted = allowedClearCells == null ? Set.of() : Set.copyOf(allowedClearCells);
            preserved = preserve == null ? ignored -> false : preserve;
            var preview = UltimineNative.preview(context.player());
            if (origin == null) {
                // 固定本次准星实际命中的方块与面，后续九格范围随这个面确定，不默认在水平面挖掘。
                origin = hit.getBlockPos().immutable(); face = hit.getDirection(); player = context.player(); world = context.level();
                bodyEpoch = context.bodyEpoch(); controlRevision = context.controlRevision(); tool = normalizedTool(context.player().getMainHandItem());
                openedTick = context.level().getGameTime(); initialRevision = preview == null ? -1 : preview.revision();
            }
            if (!bound(context) || !origin.equals(hit.getBlockPos()) || face != hit.getDirection()) return abort("ultimine_bound_target_or_tool_changed");
            if (fallback != null) return released(context, fallback);
            if (context.minecraft().options.hideGui) return single(context, "ultimine_native_hud_hidden");
            if (!UltimineNative.serverAvailable()) return single(context, "ultimine_native_server_channel_unavailable");
            if (context.level().getGameTime() - openedTick > 120) return single(context, "ultimine_preview_or_shape_timeout");
            if (preview == null) return result(Status.WAITING, "ultimine_native_preview_pending");
            if (!acquired) {
                // 边缘格先查完整九格范围；注定越界或只剩一块时直接单格挖，避免反复按键让提示闪烁。
                String unsafe = UltimineSelectionPolicy.envelopeFailure(origin, face, at -> inspect(context, at));
                if (unsafe != null) return single(context, unsafe);
                long occupied = UltimineSelectionPolicy.square(origin, face).stream()
                        .filter(at -> !context.level().getBlockState(at).isAir()).count();
                if (occupied < 2) return single(context, "ultimine_only_one_remaining_block");
                if (preview.pressed()) return result(Status.WAITING, "ultimine_waiting_for_prior_key_release");
                if (!UltimineInputLease.acquire(this, context)) return single(context, "ultimine_input_binding_unavailable");
                acquired = true; initialRevision = preview.revision();
                return result(Status.WAITING, "ultimine_waiting_for_native_key_press");
            }
            if (!UltimineInputLease.heldBy(this) || !UltimineInputLease.renew(this, context,
                    !preview.shapeId().equals(UltimineSelectionPolicy.SQUARE))) return abort("ultimine_input_lease_lost");
            if (!preview.pressed()) return result(Status.WAITING, "ultimine_waiting_for_native_key_press");
            int wanted = UltimineNative.squareIndex(); if (wanted < 0) return single(context, "ultimine_native_square_unavailable");
            if (preview.shapeIndex() != wanted) {
                // 用原生形状菜单和滚轮切到小方形，保留玩家能看到的模式变化与原生限制反馈。
                if (awaitingIndex == preview.shapeIndex() && System.nanoTime() - lastScroll < 1_000_000_000L)
                    return result(Status.WAITING, "ultimine_waiting_for_native_shape_change");
                if (scrolls >= 8) return single(context, "ultimine_native_shape_cycle_budget");
                if (System.nanoTime() - lastScroll < 150_000_000L) return result(Status.WAITING, "ultimine_native_scroll_pacing");
                int count = UltimineNative.shapeCount();
                int next = Math.floorMod(wanted - preview.shapeIndex(), count), previous = Math.floorMod(preview.shapeIndex() - wanted, count);
                if (!UltimineInputLease.renew(this, context, true)) return abort("ultimine_menu_input_lease_lost");
                awaitingIndex = preview.shapeIndex(); initialRevision = preview.revision(); lastScroll = System.nanoTime(); scrolls++;
                UltimineNative.scrollShape(context.minecraft(), next <= previous);
                return result(Status.WAITING, "ultimine_native_shape_menu");
            }
            if (preview.revision() <= initialRevision) return result(Status.WAITING, "ultimine_waiting_for_fresh_full_selection");
            ItemStack held = context.player().getMainHandItem();
            int durability = held.isDamageableItem() ? held.getMaxDamage() - held.getDamageValue() : Integer.MAX_VALUE;
            var admission = UltimineSelectionPolicy.admit(preview, origin, face, at -> inspect(context, at), durability);
            if (!admission.allowed()) return single(context, admission.code());
            if (stableRevision != preview.revision()) { stableRevision = preview.revision(); stableTick = context.level().getGameTime(); }
            // 完整选区须稳定且确实绘制到原生界面，不能拿尚未显示的内部结果直接开挖。
            if (context.level().getGameTime() <= stableTick || !UltimineInputLease.previewRendered(preview.revision()))
                return result(Status.WAITING, "ultimine_waiting_for_visible_stable_preview");
            admitted = admission;
            return new Decision(Status.READY, admission.code(), admission.completeSelection(), admission.potentialSelection());
        } catch (RuntimeException unavailable) { return acquired ? abort("ultimine_native_adapter_unavailable") : single(context, "ultimine_native_adapter_unavailable"); }
    }
    /** 破坏已提交但尚未确认时逐帧续租；方块消失后原生预览清空，不代表可以提前松键。 */
    public Decision tickInFlight(LocalPlayerContext context) {
        try {
            if (admitted == null || !bound(context) || !UltimineInputLease.heldBy(this)) return abort("ultimine_inflight_binding_lost");
            inFlight = true;
            var preview = UltimineNative.preview(context.player());
            lastNativePressed = preview != null && preview.pressed();
            lastKeyDown = UltimineNative.key().isDown();
            if (!lastNativePressed || !lastKeyDown) return abort("ultimine_native_key_released_during_break");
            if (lastHeldTick != context.level().getGameTime()) { heldBreakTicks++; lastHeldTick = context.level().getGameTime(); }
            if (preview == null || !preview.shapeId().equals(UltimineSelectionPolicy.SQUARE)
                    || !preview.implementation().equals(UltimineSelectionPolicy.SQUARE_CLASS)) return abort("ultimine_shape_changed_during_break");
            // 待确认阶段只复查已批准的潜在九格安全条件，不根据缩小或消失的预览重新降级。
            for (BlockPos at : admitted.potentialSelection()) {
                var cell = inspect(context, at);
                if (!cell.loaded() || !cell.authorized() || cell.preserved() || cell.blockEntity() || cell.unbreakable() || cell.fluid() || !cell.correctTool())
                    return abort("ultimine_inflight_envelope_changed");
            }
            if (!context.level().getBlockState(origin).isAir() && !sameHit(context, new BlockHitResult(origin.getCenter(), face, origin, false)))
                return abort("ultimine_crosshair_changed_during_break");
            if (!UltimineInputLease.renew(this, context, false)) return abort("ultimine_inflight_lease_expired");
            return new Decision(Status.READY, "ultimine_native_break_in_flight", admitted.completeSelection(), admitted.potentialSelection());
        } catch (RuntimeException invalid) { return abort("ultimine_inflight_native_state_unavailable"); }
    }
    public Decision keepAlive(LocalPlayerContext context) { return tickInFlight(context); }
    public Map<String, Object> holdEvidence() {
        return Map.of("held_break_ticks", heldBreakTicks,
                "native_pressed_at_last_break_tick", lastNativePressed, "key_down_at_last_break_tick", lastKeyDown,
                "hit_face", face == null ? "none" : face.getName());
    }
    /** 挖掘回执确认后才松键；WAITING 表示原生尚未处理松键，调用方仍须等待再开始下一次破坏。 */
    public Decision finish(LocalPlayerContext context) { inFlight = false; closed = true; return released(context, "ultimine_batch_finished"); }
    @Override public void close() { UltimineInputLease.release(this); acquired = false; closed = true; }
    private Decision single(LocalPlayerContext context, String code) { fallback = code; return released(context, code); }
    private Decision released(LocalPlayerContext context, String code) {
        // 降级也必须先确认原生已松键；若玩家自己仍按着连锁键，就阻塞而非冒充安全的单格挖掘。
        UltimineInputLease.release(this); acquired = false;
        if (releaseBegan == 0) releaseBegan = System.nanoTime();
        try {
            if (UltimineNative.available() && context != null && context.player() != null) {
                if (UltimineInputLease.humanHeld(context)) return result(Status.BLOCKED, "ultimine_human_key_held");
                if (UltimineNative.pressed()) return result(System.nanoTime() - releaseBegan > 1_500_000_000L ? Status.ABORT : Status.WAITING,
                        "ultimine_waiting_for_native_key_release");
            }
            return result(Status.SINGLE_BLOCK, code);
        } catch (RuntimeException unknown) { return result(Status.ABORT, "ultimine_release_unverified"); }
    }
    private Decision abort(String code) { close(); return result(Status.ABORT, code); }
    private UltimineSelectionPolicy.Cell inspect(LocalPlayerContext context, BlockPos at) {
        if (!context.level().isLoaded(at)) return new UltimineSelectionPolicy.Cell(false, false, true, false, false, false);
        var state = context.level().getBlockState(at);
        return new UltimineSelectionPolicy.Cell(true, permitted.contains(at), preserved.test(at) || NavigationSafetyContext.protectsMutation(at),
                state.hasBlockEntity() || context.level().getBlockEntity(at) != null,
                state.getDestroySpeed(context.level(), at) < 0, !state.getFluidState().isEmpty(),
                state.isAir() || !state.requiresCorrectToolForDrops() || UltimineNative.correctTool(context.player(), at, state));
    }
    private boolean bound(LocalPlayerContext context) {
        return eligible(context) && context.player() == player && context.level() == world && context.bodyEpoch() == bodyEpoch
                && context.controlRevision() == controlRevision && ItemStack.isSameItemSameComponents(tool, normalizedTool(context.player().getMainHandItem()));
    }
    private static ItemStack normalizedTool(ItemStack stack) {
        // 正常挖掘会消耗耐久，因此绑定工具时忽略损耗数值，仍保留物品、附魔等其他身份信息。
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack copy = stack.copyWithCount(1); if (copy.isDamageableItem()) copy.setDamageValue(0); return copy;
    }
    private static boolean eligible(LocalPlayerContext context) {
        return context != null && context.isCurrent() && context.permitsNativeActions() && context.body().automationOwnsControls() && context.minecraft().screen == null;
    }
    private static boolean sameHit(LocalPlayerContext context, BlockHitResult wanted) {
        return context.minecraft().hitResult instanceof BlockHitResult actual && actual.getType() == HitResult.Type.BLOCK
                && actual.getBlockPos().equals(wanted.getBlockPos()) && actual.getDirection() == wanted.getDirection();
    }
    private static Decision result(Status status, String code) { return new Decision(status, code, List.of(), List.of()); }
}
