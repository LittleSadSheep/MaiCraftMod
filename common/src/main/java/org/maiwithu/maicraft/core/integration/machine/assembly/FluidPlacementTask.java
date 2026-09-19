// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/** 拿真实满桶 -> 在已有结构内找可达站位 -> 等视线同步 -> 只倒一次 -> 核对服务器源格与桶账。 */
public final class FluidPlacementTask extends AbstractCompanionTask<FluidPlacementTaskRecord> {
    private final Level world;
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate aimGate = new ActualViewConvergenceGate();
    private final Set<Long> rejected = new HashSet<>();
    private NativeActionReceipt receipt;
    private FluidPlacementReceipt evidence;
    private BlockPos stance;
    private Vec3 aim;
    private int aimTicks;
    private int stanceAttempts;
    private long waitingSince;
    private String stage = "preflight";
    private boolean actualRayAvailable, bodyOverTarget, actionAvailable;
    private boolean selected, submitted, verified, alreadyPresent, relocate;
    private String failureCode = "";
    private Map<String,Object> stoppedProgress;

    public FluidPlacementTask(LocalPlayer player, FluidPlacementTaskRecord record) { super(player, record); world = player.level(); }

    @Override protected void onStart() {
        waitingSince = world.getGameTime();
        if (!world.isLoaded(r.target)) { failure("fluid_target_unloaded"); return; }
        if (FluidPlacementRules.matches(world.getBlockState(r.target), r.expected)) { alreadyPresent = true; return; }
        String issue = FluidPlacementRules.placementProblem(world, r.target, r.expected, r.sourceRegion);
        if (issue != null) failure(issue);
    }

    @Override protected TaskState onTick() {
        if (player.level() != world) return failure("fluid_world_changed");
        if (alreadyPresent) { waiting("already_present"); return TaskState.SUCCESS; }
        if (submitted) return confirm();
        if (!selection.pending() && world.isLoaded(r.target) && FluidPlacementRules.matches(world.getBlockState(r.target), r.expected)) {
            alreadyPresent = true; return TaskState.SUCCESS;
        }
        String issue = FluidPlacementRules.placementProblem(world, r.target, r.expected, r.sourceRegion);
        if (issue != null) return failure(issue);
        if (!selected) {
            waiting("selecting_bucket");
            int slot = -1;
            for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(r.bucket)) { slot = i; break; }
            if (slot < 0) return failure("fluid_bucket_missing");
            var status = selection.select(player, slot);
            if (status == FirstPersonActionGate.Status.FAILED) return failure("fluid_bucket_selection_failed");
            if (status == FirstPersonActionGate.Status.READY) selected = true;
            return TaskState.RUNNING;
        }
        if (!player.getMainHandItem().is(r.bucket)) return failure("fluid_held_bucket_changed");
        var context = ClientRuntime.requireContext(player);
        if (player.containerMenu != player.inventoryMenu || context.minecraft().screen != null) return failure("fluid_menu_busy");
        BlockHitResult visible = visibleFrom(player.getEyePosition());
        actualRayAvailable = visible != null; bodyOverTarget = player.getBoundingBox().intersects(new AABB(r.target));
        if (relocate || visible == null || bodyOverTarget) return approach();
        stopNav(); aim = visible.getLocation(); InputDriver.halt(player); InputDriver.lookAt(player, aim);
        // 只把真正等待转头的刻数记入瞄准超时；动作端口忙时不应反复丢弃已经正确的站位。
        if (!aimGate.ready(player, aim.subtract(player.getEyePosition()))) {
            waiting("aligning_camera"); if (++aimTicks > 80) rejectStand(); return TaskState.RUNNING;
        }
        aimTicks = 0;
        var hit = FirstPersonInteractionTargeting.bucketRay(world, player, player.getEyePosition(),
                player.getEyePosition().add(player.getViewVector(1).scale(player.blockInteractionRange())), r.bucket);
        if (!FirstPersonInteractionTargeting.acceptsBucketHit(world, r.target, r.bucket, hit)) { rejectStand(); return TaskState.RUNNING; }
        if (!returnFits()) return failure("fluid_empty_bucket_return_space_missing");
        issue = FluidPlacementRules.placementProblem(world, r.target, r.expected, r.sourceRegion);
        if (issue != null) return failure(issue);
        actionAvailable = context.mutationAvailable();
        if (!actionAvailable) { waiting("awaiting_native_action"); return TaskState.RUNNING; }
        evidence = new FluidPlacementReceipt(player, r.target, r.expected); submitted = true;
        waiting("awaiting_bucket_receipt");
        // 满桶沿原生 USE_ITEM 的射线放置，不能把背后的墙或机器当作右键激活目标，也不直接改格子或背包。
        receipt = context.actions().useItem(context, InteractionHand.MAIN_HAND, evidence, 120);
        evidence.submitted();
        return TaskState.RUNNING;
    }

    private BlockHitResult visibleFrom(Vec3 eye) {
        return FluidPlacementAim.find(world, player, eye, r.target, player.blockInteractionRange(), r.bucket);
    }
    private TaskState approach() {
        // 倒桶阶段不许导航拆开已建好的围挡；可站在尚未填充的干燥区域，但不能站在本次要填的格子里。
        return NavigationSafetyContext.withProtectedArea(r.installation, List.of(r.target), () -> {
            waiting("approaching_stance");
            if (stance == null) {
                stance = AssemblyInteractionGeometry.nearestStand(player, r.target, rejected, eye -> {
                    var hit = visibleFrom(eye); return hit == null ? null : hit.getLocation();
                }, Pose.STANDING);
                stanceAttempts++;
            }
            if (stance == null) return failure("fluid_target_has_no_native_bucket_stance");
            // 路线只负责抵达格子，不会保证站在中心小数坐标；实际眼位点不到时换格，不能在已到达的格里永远寻路。
            if (atStance()) return settleStance();
            if (nav == null) nav = PlayerNav.to(player, () -> GoalCompiler.standOn(stance), 1.0,
                    this::atStance,
                    PlayerNav.ContextProvider.DEFAULT);
            var status = nav.tick();
            if (status == PlayerNav.Status.FAILED) rejectStand();
            else if (status == PlayerNav.Status.ARRIVED) return settleStance();
            return TaskState.RUNNING;
        });
    }
    private boolean atStance() { return stance != null && player.onGround() && PlayerNav.playerFeet(player).equals(stance); }
    private TaskState settleStance() {
        stopNav(); actualRayAvailable = visibleFrom(player.getEyePosition()) != null;
        bodyOverTarget = player.getBoundingBox().intersects(new AABB(r.target));
        if (!actualRayAvailable || bodyOverTarget) rejectStand();
        else { relocate = false; aimTicks = 0; aimGate.reset(); waiting("stance_ready"); }
        return TaskState.RUNNING;
    }
    private void rejectStand() {
        rejected.add(PlayerNav.playerFeet(player).asLong()); if (stance != null) rejected.add(stance.asLong());
        stance = null; relocate = true; aimTicks = 0; aimGate.reset(); stopNav();
        waiting("reselecting_stance");
    }
    private boolean returnFits() {
        if (player.hasInfiniteMaterials() || player.getMainHandItem().getCount() == 1) return true;
        ItemStack empty = BucketItem.getEmptySuccessItem(player.getMainHandItem().copyWithCount(1), player);
        return player.getInventory().items.stream().anyMatch(stack -> stack.isEmpty()
                || ItemStack.isSameItemSameComponents(stack, empty) && stack.getCount() < stack.getMaxStackSize());
    }
    private TaskState confirm() {
        if (receipt == null) return failure("fluid_receipt_missing_after_submission");
        var context = ClientRuntime.requireContext(player); receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED || evidence.observe(context) != NativeConfirmation.Verdict.APPLIED)
            return failure("fluid_placement_not_confirmed_no_retry");
        verified = true; waiting("complete"); return TaskState.SUCCESS;
    }
    private TaskState failure(String code) { failureCode = code; fail(code, FailureType.UNSUPPORTED); return TaskState.FAILED; }
    private void waiting(String next) { if (!stage.equals(next)) { stage = next; waitingSince = world.getGameTime(); } }

    @Override protected void cleanup() {
        if (stoppedProgress == null) stoppedProgress = progress();
        selection.reset();
        if (receipt != null && !receipt.terminal()) {
            try {
                var context = ClientRuntime.requireContext(player); receipt = context.actions().poll(context, receipt);
                // 取消只结束旧持用，不再倒桶；已经发生但尚未证实的液体变化仍按不确定结果保留。
                if (!receipt.terminal() && context.mutationAvailable()) {
                    receipt = context.actions().cancelMainHandUse(context, receipt);
                    if (!receipt.terminal()) receipt = context.actions().retireOneShotForTaskBoundary(context, receipt, "fluid placement task ended");
                }
            } catch (RuntimeException revoked) { /* 身体已被撤销时由运行层结束旧回执，不补发另一桶。 */ }
        }
        InputDriver.halt(player); super.cleanup();
    }
    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return submitted && !verified; }
    @Override protected String successMessage() { return alreadyPresent ? "Declared source fluid already present; no bucket used." : "Source fluid and native bucket return confirmed."; }
    @Override protected String timeoutMessage() { return "Source-fluid placement timed out while " + stage + "; bucket_submitted=" + submitted; }
    @Override public Map<String, Object> progress() {
        // 只读阶段、距离和门槛，不暴露可重放动作；失败或超时后也保留最后一次等待原因。
        var data = new java.util.LinkedHashMap<String,Object>(); data.put("task", name()); data.put("phase", stage);
        data.put("waiting_ticks", Math.max(0,world.getGameTime()-waitingSince)); data.put("bucket_selected",selected);
        data.put("selection_pending",selection.pending()); data.put("bucket_submitted",submitted);
        data.put("actual_bucket_ray_available",actualRayAvailable); data.put("body_over_target",bodyOverTarget);
        data.put("native_action_available",actionAvailable); data.put("aim_wait_ticks",aimTicks);
        data.put("stance_attempts",stanceAttempts); data.put("rejected_stances",rejected.size());
        data.put("navigation_active",nav!=null); data.put("in_selected_stance_cell",atStance());
        data.put("native_reach",player.blockInteractionRange());
        if (aim!=null) {
            Vec3 direction=aim.subtract(player.getEyePosition()); data.put("aim_distance",direction.length());
            double alignment=player.getViewVector(1).normalize().dot(direction.normalize());
            data.put("camera_error_degrees",Math.toDegrees(Math.acos(Math.clamp(alignment,-1,1))));
        }
        if(receipt!=null)data.put("native_receipt",receipt.status().name().toLowerCase(java.util.Locale.ROOT));
        return Map.copyOf(data);
    }
    @Override protected Map<String, Object> resultData() {
        var data = new java.util.LinkedHashMap<String,Object>(Map.of("source_fluid_verified", verified || alreadyPresent, "already_present", alreadyPresent,
                "bucket_submitted", submitted, "native_effect_verified", verified, "outcome_uncertain", submitted && !verified,
                // 桶已提交却尚未结清时禁止机械重试；已有正确源格可只读复用，不需要重复倒桶。
                "mechanical_retry_allowed", !submitted || verified, "failure_code", failureCode, "machine_production_verified", false));
        data.put("placement_progress",stoppedProgress == null ? progress() : stoppedProgress); return data;
    }
}
