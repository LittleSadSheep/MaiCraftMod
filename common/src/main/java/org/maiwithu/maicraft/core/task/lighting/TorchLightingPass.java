// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.task.AfterNavigationAction;
import org.maiwithu.maicraft.task.ProgressBudget;
import org.maiwithu.maicraft.task.TaskState;

/** 按剩余暗格规划的灯位边走边放；只在最后由区域任务重新核实光照，不拿理论间距当验收。 */
public final class TorchLightingPass extends AbstractCompanionTask<BuildTaskRecord> {
    private final List<BuildTaskRecord.Target> remaining;
    private final List<BlockPos> samples;
    private final Set<BlockPos> protectedCells, forbiddenBody;
    private final int minimum;
    private final OffhandTorchPlacer placer = new OffhandTorchPlacer();
    private final ProgressBudget inactivity;
    private BuildTaskRecord.Target target;
    private int skipped;
    private boolean uncertain;
    private long lightSettlesAt;
    private RuntimeException deferredFailure;
    private final Set<BlockPos> attempted = new LinkedHashSet<>();

    public Set<BlockPos> attemptedPositions() { return Set.copyOf(attempted); }

    public TorchLightingPass(LocalPlayer player, BuildTaskRecord record, List<BlockPos> samples, int minimum,
                             Set<BlockPos> protectedCells, Set<BlockPos> forbiddenBody) {
        super(player, record);
        remaining = new ArrayList<>(record.targets);
        this.samples = List.copyOf(samples); this.minimum = minimum;
        this.protectedCells = Set.copyOf(protectedCells); this.forbiddenBody = Set.copyOf(forbiddenBody);
        inactivity = record.progressBudget(600);
    }

    @Override protected TaskState onTick() {
        return NavigationSafetyContext.withProtectedArea(protectedCells, forbiddenBody, this::advance);
    }

    private TaskState advance() {
        if (deferredFailure != null) {
            fail("native torch preparation failed: " + deferredFailure.getMessage(), FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        var context = ClientRuntime.requireContext(player);
        var settled = placer.poll(context);
        if (settled != null) {
            if (settled.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                r.placedOne(); r.completed(r.placed() + skipped);
                remaining.remove(target); target = null; stopNav();
                lightSettlesAt = context.tickRevision() + 5;
            } else {
                uncertain = settled.status() != NativeActionReceipt.Status.CONFIRMED_NOT_APPLIED;
                fail("native torch placement: " + settled.detail(), FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
        }
        // 已确认新火把或真实导航进展才补预算；静止等待不能无限延长一轮照明。
        if (inactivity.observe(player.level().getGameTime(), settled != null
                && settled.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED)) {
            fail("torch pass made no further progress: " + placer.state(), FailureType.PLANNING_STALL);
            return TaskState.FAILED;
        }
        if (remaining.isEmpty()) return TaskState.SUCCESS;
        if (context.tickRevision() < lightSettlesAt) return TaskState.RUNNING;
        if (target == null) {
            // 上一盏灯的实测光已覆盖的候选直接省下；隔墙仍暗的格保留，不用距离猜测它已经亮了。
            remaining.removeIf(candidate -> {
                if (couldImprove(candidate)) return false;
                skipped++; r.completed(r.placed() + skipped); return true;
            });
            if (remaining.isEmpty()) return TaskState.SUCCESS;
            target = remaining.stream().min(Comparator.comparingDouble(candidate -> candidate.pos().distSqr(player.blockPosition()))).orElseThrow();
        }
        if (!placer.pending()) {
            BuildTaskRecord.Target candidate = target;
            AfterNavigationAction.request(this, context, () -> {
                try {
                    NavigationSafetyContext.withProtectedArea(protectedCells, forbiddenBody,
                            () -> {
                                if (placer.place(context, candidate, protectedCells)) attempted.add(candidate.pos());
                                return true;
                            });
                } catch (RuntimeException failure) { deferredFailure = failure; }
            });
        }
        // 副手用尽后把本轮已完成效果交回区域父任务，由原供料流程补给并继续同一区域。
        if (placer.state().equals("missing_torches") && !placer.pending()) {
            AfterNavigationAction.cancel(this);
            fail("carried torches exhausted during lighting pass", FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        if (nav == null) {
            BlockPos destination = target.pos();
            // 朝灯位旁边走，进入真实触及范围就可点击；不会走到灯格里面挡住自己的放置。
            nav = PlayerNav.toGoal(player, () -> NavGoal.adjacent(destination), 1.0,
                    () -> player.blockPosition().distManhattan(destination) == 1, PlayerNav.ContextProvider.DEFAULT);
        }
        var navigation = nav.tick();
        if (navigation == PlayerNav.Status.FAILED && !placer.pending()) {
            AfterNavigationAction.cancel(this);
            fail("torch site could not be reached: " + nav.failReason(), nav.failType());
            return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    private boolean couldImprove(BuildTaskRecord.Target candidate) {
        for (BlockPos sample : samples) {
            if (!player.level().isLoaded(sample)) return true;
            if (candidate.desiredState().getLightEmission() - candidate.pos().distManhattan(sample) >= minimum
                    && player.level().getBrightness(LightLayer.BLOCK, sample) < minimum) return true;
        }
        return false;
    }

    @Override public void stop(LocalPlayer companion, StopReason reason) {
        // 抢占保留已经发出的回执，撤掉尚未执行的帧末点击；回到本任务时先确认旧火把。
        AfterNavigationAction.cancel(this);
        super.stop(companion, reason);
    }

    @Override protected void cleanup() {
        AfterNavigationAction.cancel(this);
        ClientRuntime.actor().activeContext().filter(context -> context.player() == player && context.isCurrent()).ifPresent(context -> {
            var settled = placer.retire(context);
            if (settled == null) return;
            if (settled.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                r.placedOne(); remaining.remove(target); r.completed(r.placed() + skipped);
            } else if (settled.status() != NativeActionReceipt.Status.CONFIRMED_NOT_APPLIED) uncertain = true;
        });
        super.cleanup();
    }
    @Override protected Map<String, Object> resultData() {
        return Map.of("placed", r.placed(), "skipped_already_lit", skipped, "remaining", remaining.size(),
                "outcome_uncertain", uncertain || placer.pending(), "placement_hand", "offhand");
    }
    @Override protected String successMessage() { return "torch placement pass completed; actual area coverage still requires verification"; }
}
