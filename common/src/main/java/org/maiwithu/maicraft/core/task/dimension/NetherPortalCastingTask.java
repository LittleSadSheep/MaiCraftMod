// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementTaskRecord;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 按池岸模板执行真实施工；设计产物与桶的原生效果分开记账，点火由备门父任务接续。 */
final class NetherPortalCastingTask extends AbstractCompanionTask<PortalPreparationTaskRecord> {
    private final ClientLevel world;
    private final BiFunction<LocalPlayer, TaskRecord, Task> factory;
    private final List<Map<String, Object>> receipts = new ArrayList<>();
    private final Set<BlockPos> unavailableSources = new HashSet<>();
    private PortalCastingSurvey survey;
    private NetherPortalCastingLayout layout;
    private List<PortalCastingStep> steps = List.of();
    private Task child;
    private TaskRecord childRecord;
    private PortalPreparationSupplies.Need supplyNeed;
    private BlockPos mutation;
    private String operation = "survey", issue = "";
    private int cursor, serial, sourceAttempts;
    private long drainStarted = -1;
    private boolean nextAfterChild, cleared, initialSupplied, waterPrepared, complete, cleaned;

    NetherPortalCastingTask(LocalPlayer player, PortalPreparationTaskRecord record) { this(player, record, TaskFactory::create); }
    NetherPortalCastingTask(LocalPlayer player, PortalPreparationTaskRecord record, BiFunction<LocalPlayer, TaskRecord, Task> factory) {
        super(player, record); world = player.clientLevel; this.factory = factory;
    }

    @Override protected void onStart() {
        if (!r.mayAlterTerrain) { failure("portal_construction_permission_required"); return; }
        survey = new PortalCastingSurvey(world, player.blockPosition(), r.radius);
    }

    @Override protected TaskState onTick() {
        if (player.level() != world) return failure("casting_world_changed");
        if (child != null) return tickChild();
        if (layout == null) {
            layout = survey.tick();
            if (layout == null) return survey.complete() ? failure("casting_lava_pool_not_observed") : TaskState.RUNNING;
            steps = PortalCastingStep.plan(layout);
        }
        // 起手只准备普通工具和一只桶；模具材料消耗完时内部补给，仍接着同一张施工单。
        if (!initialSupplied) {
            var need = PortalCastingStep.supplies(player, true);
            if (need != null) return supply(need);
            initialSupplied = true;
        }
        if (!waterPrepared) {
            if (PlayerInv.count(player.getInventory(), Items.WATER_BUCKET) > 0) waterPrepared = true;
            else return fill(Blocks.WATER);
        }
        if (cursor >= steps.size()) { complete = true; return TaskState.SUCCESS; }
        var step = steps.get(cursor);
        BlockPos at = step.target();
        BlockState actual = PortalPreparationSite.read(world, at);
        if (actual == null) return failure("casting_target_unloaded");
        if (NavigationSafetyContext.protectsMutation(at)) return failure("casting_target_protected");
        return switch (step.kind()) {
            case CLEAR -> {
                if (actual.isAir() || !actual.getFluidState().isEmpty()
                        || actual.is(Blocks.OBSIDIAN) && layout.frame().frame().contains(at)) yield next();
                yield start(PortalCastingStep.clear(player, id(), deadline(), at, actual), at, true, "clear");
            }
            case BUILD -> {
                var need = PortalCastingStep.supplies(player, false);
                if (need != null) yield supply(need);
                yield start(PortalCastingStep.build(player, id(), deadline(), at, layout, r.policy), at, true, "build_mold");
            }
            case POUR_WATER -> start(place(at, Blocks.WATER.defaultBlockState()), at, true, "place_water");
            case TAKE_WATER -> {
                // 回收仅针对仍然存在的那一格水源；源格变化是现场事实，不能跑到旁边取另一桶冒充完成。
                if (!actual.is(Blocks.WATER) || !actual.getFluidState().isSource()) yield failure("casting_water_source_changed");
                yield start(remove(at, actual), at, true, "recover_water");
            }
            case CAST -> {
                if (actual.is(Blocks.OBSIDIAN)) yield next();
                if (!cleared && !actual.isAir() && actual.getFluidState().isEmpty()) {
                    cleared = true;
                    yield start(PortalCastingStep.clear(player, id(), deadline(), at, actual), at, false, "excavate_cast_cell");
                }
                if (PlayerInv.count(player.getInventory(), Items.LAVA_BUCKET) == 0) yield fill(Blocks.LAVA);
                // 一桶已被服务器结清就推进，即使产物错误也不再向原格倒第二桶或自动拆掉产物。
                yield start(place(at, Blocks.LAVA.defaultBlockState()), at, true, "cast_lava");
            }
            case DRAIN -> {
                operation = "draining";
                if (drainStarted < 0) drainStarted = world.getGameTime();
                if (layout.frame().interior().stream().allMatch(p -> world.getFluidState(p).isEmpty())
                        || world.getGameTime() - drainStarted >= 200) yield next();
                yield TaskState.RUNNING;
            }
        };
    }

    private TaskState fill(Block fluid) {
        operation = fluid == Blocks.WATER ? "find_water_source" : "find_lava_source";
        if (PlayerInv.count(player.getInventory(), Items.BUCKET) == 0) return failure("casting_empty_bucket_missing");
        var excluded = new HashSet<>(layout.footprint()); excluded.addAll(unavailableSources);
        var observed = survey.source(fluid, player.blockPosition(), excluded);
        if (observed.position() == null) return observed.complete() ? failure(operation + "_not_observed") : TaskState.RUNNING;
        return start(remove(observed.position(), world.getBlockState(observed.position())), observed.position(), false,
                fluid == Blocks.WATER ? "fill_water" : "fill_lava");
    }

    private TaskState supply(PortalPreparationSupplies.Need need) {
        supplyNeed = need;
        return start(need.acquire(id(), deadline(), r.policy, true), null, false, "supply");
    }
    private FluidPlacementTaskRecord place(BlockPos at, BlockState expected) {
        return new FluidPlacementTaskRecord(id(), deadline(), at, expected, layout.footprint());
    }
    private FluidPlacementTaskRecord remove(BlockPos at, BlockState source) {
        var scope = new HashSet<>(layout.footprint()); scope.add(at);
        return FluidPlacementTaskRecord.removeSource(id(), deadline(), at, source, scope);
    }
    private long deadline() { return Math.max(r.getDeadlineGameTime(), world.getGameTime() + 6000); }
    private String id() { return r.getToolCallId() + "-cast-" + (++serial); }

    private TaskState start(TaskRecord record, BlockPos target, boolean advance, String purpose) {
        childRecord = record; mutation = target; nextAfterChild = advance; operation = purpose;
        child = guarded(() -> factory.apply(player, record));
        return TaskState.RUNNING;
    }
    private <T> T guarded(Supplier<T> action) {
        if (layout == null) return action.get();
        return NavigationSafetyContext.withPreservedStructures(layout.footprint().stream()
                .filter(p -> !p.equals(mutation)).toList(), action);
    }

    private TaskState tickChild() {
        TaskState terminal;
        if (world.getGameTime() >= childRecord.getDeadlineGameTime()) {
            guarded(() -> { child.stop(player, StopReason.REPLACED); return null; }); terminal = TaskState.TIMEOUT;
        } else terminal = guarded(() -> runChild(child));
        r.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (terminal == null) return TaskState.RUNNING;
        TaskState ended = terminal;
        TaskResult result = guarded(() -> child.result(ended));
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("step", cursor); evidence.put("operation", operation);
        evidence.put("success", result != null && result.success());
        if (mutation != null) evidence.put("position", NetherPortalCastingLayout.position(mutation));
        if (result != null) { evidence.put("message", result.message()); evidence.put("effects", result.data()); }
        receipts.add(evidence); child = null; childRecord = null;
        if (result == null) return failure("casting_child_receipt_missing");
        if (terminal != TaskState.SUCCESS || !result.success()) {
            // 取桶尚未提交且只是站位/源格变化时换一个真实源格；不确定或已提交的动作绝不机械重放。
            if (operation.startsWith("fill_") && Boolean.FALSE.equals(result.data().get("bucket_submitted")) && sourceAttempts++ < 8) {
                unavailableSources.add(mutation); return TaskState.RUNNING;
            }
            issue = "casting_" + operation + "_failed";
            // 默认失败说明直接给出最后一个原生卡点，不让模型翻遍前面已经完成的每桶历史才能决策。
            fail(issue + ": " + result.message(), FailureType.TARGET_LOST); return TaskState.FAILED;
        }
        if (supplyNeed != null) {
            if (!supplyNeed.satisfied(player)) return failure("casting_supply_unverified");
            supplyNeed = null;
        }
        sourceAttempts = 0;
        return nextAfterChild ? next() : TaskState.RUNNING;
    }

    private TaskState next() { cursor++; cleared = false; drainStarted = -1; return TaskState.RUNNING; }
    private TaskState failure(String code) { issue = code; fail(code, FailureType.TARGET_LOST); return TaskState.FAILED; }
    NetherPortalCastingLayout layout() { return layout; }

    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("method", "lava_cast"); data.put("casting_actions_completed", complete);
        data.put("casting_step", cursor); data.put("casting_step_count", steps.size()); data.put("operation", operation);
        data.put("native_steps", List.copyOf(receipts));
        if (layout != null) data.put("portal_observation", layout.observation(p -> PortalPreparationSite.read(world, p)));
        if (!issue.isEmpty()) {
            data.put("issue_code", issue);
            if (!receipts.isEmpty() && Boolean.FALSE.equals(receipts.getLast().get("success")))
                data.put("native_failure", receipts.getLast());
        }
        return data;
    }
    @Override public Map<String, Object> progress() {
        var data = new LinkedHashMap<>(resultData());
        if (child != null) data.put("child", child.progress()); return data;
    }
    @Override public void stop(LocalPlayer player, StopReason why) {
        if (child != null) guarded(() -> { child.stop(player, why); return null; });
        super.stop(player, why);
    }
    @Override protected void cleanup() {
        if (cleaned) return; cleaned = true;
        if (child != null) guarded(() -> {
            child.stop(player, StopReason.REPLACED); child.result(TaskState.CANCELLED); return null;
        });
        child = null;
        if (survey != null) survey.close();
        super.cleanup();
    }
    @Override protected String successMessage() { return "Native casting actions completed; frame outcome is reported separately."; }
}
