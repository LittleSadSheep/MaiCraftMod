// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropTaskRecord;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 验配方与备料 -> 安全站位 -> 定量投料 -> 等待原生转化 -> 本人拾取；不预制产物，也不在失败后重复投入材料。 */
final class WorldTransformTask extends AbstractCompanionTask<WorldTransformTaskRecord> {
    private enum Phase { PREPARE, APPROACH, BASELINE, FEED, WAIT_OUTPUT, COLLECT, VERIFY_PICKUP, COMPLETE }
    private Phase phase = Phase.PREPARE;
    private final VisibleMenuSession menus = new VisibleMenuSession();
    private final List<Map<String, Object>> completed = new ArrayList<>();
    private WorldProcessRecipe recipe;
    private JsonObject recipeDefinition;
    private WorldProcessBatchPlan plan;
    private WorldProcessInventory inventory;
    private WorldProcessSite site;
    private WorldProcessSite.Stand stand;
    private List<WorldProcessSite.Stand> candidates;
    private List<ItemStack> batchInputs;
    private WorldProcessEvents events;
    private WorldProcessInputs inputs;
    private WorldProcessSettlement settlement;
    private Level world;
    private Task child;
    private TaskRecord childRecord;
    private int candidateIndex, batchIndex, feedIndex, serial;
    private long batchCursor, waitUntil, nextEventRead;
    private String failure;
    private boolean approachDispatched, triggerStepStarted;
    private boolean collectionStarted;
    private String collectionDetail;
    private org.maiwithu.maicraft.core.task.build.BuildEdgeMotion alignment;

    WorldTransformTask(LocalPlayer player, WorldTransformTaskRecord record) { super(player, record); }

    @Override protected void onStart() {
        try {
            world = player.level();
            if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty())
                throw new IllegalArgumentException("world_process_existing_menu_in_use");
            recipe = NativeTransformRecipes.find(player, r.recipeId); recipeDefinition = recipe.describe();
            WorldProcessFeedRegion.requireRule(recipe);
            site = WorldProcessSite.inspect(player, r.receiver, recipe);
            requireProtection();
            var competing = NativeTransformRecipes.recipes(player).stream().filter(value -> value.supports(world.getFluidState(r.receiver))).toList();
            plan = WorldProcessBatchPlan.compile(recipe, WorldProcessInventory.snapshot(player), r.batches, competing);
            inventory = new WorldProcessInventory(player, plan, recipe.result()); candidates = site.feedingStands(player);
            events = new WorldProcessEvents(site.fluids, world.dimension().location().toString());
        } catch (RuntimeException unavailable) { failed(unavailable.getMessage(), FailureType.NO_MATERIAL); }
    }

    @Override protected TaskState onTick() {
        try { return advance(); }
        catch (RuntimeException invalid) { return failed(invalid.getMessage(), FailureType.UNKNOWN); }
    }

    private TaskState advance() {
        var context = ClientRuntime.requireContext(player);
        if (!context.permitsNativeActions()) return failed("world_process_control_handed_over", FailureType.INTERRUPTED);
        site.requireUnchanged(player); requireProtection();
        if (child != null) return tickChild();
        if (!menus.worldReady(context)) return TaskState.RUNNING;
        return switch (phase) {
            case PREPARE -> {
                if (candidateIndex >= candidates.size()) throw new IllegalStateException("world_process_safe_throw_stand_missing");
                var candidate = candidates.get(candidateIndex++);
                if (org.maiwithu.maicraft.core.task.inventory.TargetedDropGeometry.canReach(player, Vec3.atBottomCenterOf(candidate.feet()), candidate.receiver(), site.fluids)) {
                    stand = candidate; phase = Phase.APPROACH;
                }
                yield TaskState.RUNNING;
            }
            case APPROACH -> {
                // 到达导航格不等于格心；先用实际身体判断能否安全投料，只有必要时复用施工的有界微动对齐。
                context.body().releaseAll();
                if (player.getDeltaMovement().horizontalDistanceSqr() > .0004) yield TaskState.RUNNING;
                if (site.safeWaiting(player) && org.maiwithu.maicraft.core.task.inventory.TargetedDropGeometry.canReach(player, player.position(), stand.receiver(), site.fluids)) {
                    phase = Phase.BASELINE; yield TaskState.RUNNING;
                }
                if (!approachDispatched) { approachDispatched = true; yield move(stand.feet()); }
                if (!align(stand.feet())) yield TaskState.RUNNING;
                if (!site.safeWaiting(player) || !org.maiwithu.maicraft.core.task.inventory.TargetedDropGeometry.canReach(player, player.position(), stand.receiver(), site.fluids))
                    throw new IllegalStateException("world_process_actual_throw_stand_not_usable");
                phase = Phase.BASELINE; yield TaskState.RUNNING;
            }
            case BASELINE -> {
                if (!events.baseline()) yield TaskState.RUNNING;
                beginBatch(); phase = Phase.FEED; yield TaskState.RUNNING;
            }
            case FEED -> feed();
            case WAIT_OUTPUT -> waitOutput();
            case COLLECT -> collect();
            case VERIFY_PICKUP -> verifyPickup();
            case COMPLETE -> TaskState.SUCCESS;
        };
    }

    private void beginBatch() {
        if (!NativeTransformRecipes.find(player, r.recipeId).describe().equals(recipeDefinition))
            throw new IllegalStateException("world_process_recipe_changed");
        inventory.requireUnchanged();
        if (!ItemEntityReceipts.snapshot(player, site.region).isEmpty()) throw new IllegalStateException("world_process_receiver_contains_unowned_items");
        batchInputs = plan.batch(batchIndex); WorldProcessBatchPlan.requireSpace(WorldProcessInventory.snapshot(player), batchInputs, recipe.result());
        feedIndex = 0; triggerStepStarted = false;
        collectionStarted = false; collectionDetail = null;
        batchCursor = ItemEntityReceipts.cursor(player);
        inputs = new WorldProcessInputs(player, recipe, inventory, site.region, batchCursor);
        settlement = new WorldProcessSettlement(player, recipe, inventory, batchCursor, events.nativeEvents);
    }

    private TaskState feed() {
        if (!site.safeWaiting(player)) throw new IllegalStateException("world_process_input_pickup_risk");
        inventory.requireUnchanged(); var present = inputs.requirePresent();
        boolean trigger = feedIndex == batchInputs.size() - 1;
        var region = org.maiwithu.maicraft.core.task.inventory.TargetedDropRegion.ofCells(site.fluids);
        var preference = WorldProcessFeedRegion.forInput(player, recipe, site.fluids, inputs.entities(), present, trigger);
        // AE2在有效流体累计超过60刻后才尝试反应，期间物品会漂移；朝新鲜交集瞄准，但首次入水只要求完整池域。
        if (!org.maiwithu.maicraft.core.task.inventory.TargetedDropGeometry.canReach(player, player.position(), stand.receiver(), region, preference))
            throw new IllegalStateException(trigger ? "world_process_trigger_region_unreachable" : "world_process_input_region_unreachable");
        if (!r.prepareNativeConsumptionBoundary()) return TaskState.RUNNING;
        // 整个有限任务只保留一次消费边界；每份原料仍由独立原生投料回执精确确认，轮询不重发Q。
        ItemStack input = batchInputs.get(feedIndex);
        triggerStepStarted |= trigger;
        return start(new TargetedDropTaskRecord(id(), deadline(400), input, input.getCount(), stand.receiver(), region,
                () -> trigger ? WorldProcessFeedRegion.currentAim(player, recipe, site.fluids, inputs) : region,
                () -> !trigger || WorldProcessFeedRegion.permitsCurrentThrow(player, recipe, site.fluids, inputs)));
    }

    private TaskState waitOutput() {
        boolean gone = inputs.gone();
        // 最后一份原料消失后允许结算暂停期间的本人拾取，旧投料站位不能否定已发生的加工与收取。
        if (!gone && !site.safeWaiting(player)) throw new IllegalStateException("world_process_input_pickup_risk");
        var drops = ItemEntityReceipts.snapshot(player, site.region);
        // 最后入池后平滑看回真实物品或接收区，留在原站位观看原生转化；暂停与手动接管不会执行这一刻。
        var watching = drops.stream().filter(drop -> drop.stack().is(recipe.result().getItem())).findFirst().orElse(drops.isEmpty() ? null : drops.getFirst());
        org.maiwithu.maicraft.entity.InputDriver.lookAt(player, watching == null ? stand.receiver().getCenter() : watching.position());
        // 反应期间不巡视也不靠近原料；看到成品后才查询原生生产记录，并以相同UUID继续收取。
        boolean candidate = drops.stream().anyMatch(drop -> !inputs.entities().containsKey(drop.uuid()) && drop.stack().is(recipe.result().getItem()));
        if (events.nativeEvents && (candidate || world.getGameTime() >= nextEventRead)) {
            for (JsonObject event : events.poll()) {
                var found = WorldProcessEventEvidence.confirm(player, event, recipe, inputs.entities(), inputs.delivered(), java.util.Set.copyOf(site.fluids));
                settlement.acceptNative(found);
            }
            nextEventRead = world.getGameTime() + 20;
        }
        settlement.observe(drops, gone);
        inputs.requireNoForeignDrops(drops, settlement.output());
        if (settlement.ready()) {
            if (settlement.collect()) return completeBatch();
            phase = settlement.pickupAmount() > 0 ? Phase.VERIFY_PICKUP : Phase.COLLECT;
            waitUntil = world.getGameTime() + 600; return TaskState.RUNNING;
        }
        if (world.getGameTime() >= waitUntil) throw new IllegalStateException("world_process_transformation_not_verified");
        return TaskState.RUNNING;
    }

    private TaskState collect() {
        if (!settlement.ready()) throw new IllegalStateException("world_process_output_baseline_missing");
        // 暂停时可能已拾走成品，先结算冻结证据，再决定是否还需要走近或者等待。
        if (settlement.collect()) return completeBatch();
        var output = settlement.output();
        if (world.getGameTime() >= waitUntil) throw new IllegalStateException("world_process_output_collection_timeout");
        var seen = ItemEntityReceipts.snapshot(player, site.region).stream().filter(drop -> drop.uuid().equals(output.uuid())).findFirst().orElse(null);
        if (seen == null) { phase = Phase.VERIFY_PICKUP; return TaskState.RUNNING; }
        if (!ItemStack.matches(seen.stack(), output.stack())) throw new IllegalStateException("world_process_output_changed");
        if (collectionStarted) { phase = Phase.VERIFY_PICKUP; return TaskState.RUNNING; }
        collectionStarted = true;
        // 复用通用拾取的真实接触、同格短靠近与20刻同步窗口，只授权已冻结产物UUID，不重新择格或重投原料。
        return start(new CollectItemsTaskRecord(id(), deadline(600), java.util.Set.of(output.stack().getItem()), 16,
                output.stack().getHoverName().getString(), java.util.Set.of(output.uuid())));
    }

    private TaskState verifyPickup() {
        if (settlement.collect()) return completeBatch();
        if (world.getGameTime() >= waitUntil) throw new IllegalStateException("world_process_output_pickup_not_verified");
        return TaskState.RUNNING;
    }

    private TaskState completeBatch() {
        var output = settlement.output();
        // 只在真实原料、冻结成品、本人拾取及完整组件账本全部对上后换批，不再消费已经完成的批次。
        completed.add(Map.of("batch", batchIndex + 1, "item_id", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(output.stack().getItem()).toString(),
                "count", output.stack().getCount(), "native_recipe_verified", events.nativeEvents));
        if (++batchIndex == r.batches) { phase = Phase.COMPLETE; return TaskState.SUCCESS; }
        approachDispatched = false; phase = Phase.APPROACH; return TaskState.RUNNING;
    }

    private void requireProtection() {
        for (BlockPos fluid : site.fluids) if (NavigationSafetyContext.protectsMutation(fluid))
            throw new IllegalStateException("world_process_receiver_protected");
    }

    private TaskState move(BlockPos at) {
        return start(new MoveToTaskRecord(id(), deadline(1200), at.getX() + .5, (double) at.getY(), at.getZ() + .5,
                null, false, false, TransportMode.GROUND, false, true, 0, .1));
    }
    private TaskState start(TaskRecord record) { childRecord = record; child = TaskFactory.create(player, record); return TaskState.RUNNING; }
    private TaskState tickChild() {
        TaskState state = world.getGameTime() >= childRecord.getDeadlineGameTime() ? TaskState.TIMEOUT : runChild(child);
        r.extendDeadlineTo(childRecord.getDeadlineGameTime()); if (state == null) return TaskState.RUNNING;
        if (state != TaskState.SUCCESS) child.stop(player, Task.StopReason.REPLACED);
        var result = child.result(state); child = null; childRecord = null;
        if (phase == Phase.COLLECT) {
            // 子任务成功不代表父任务已收取，失败也可能先于同帧Take/背包同步；先结算，再给既有20刻同步窗口。
            if (settlement.collect()) return completeBatch();
            collectionDetail = state.name().toLowerCase(java.util.Locale.ROOT) + ": " + result.message();
            phase = Phase.VERIFY_PICKUP; waitUntil = deadline(20); return TaskState.RUNNING;
        }
        if (state != TaskState.SUCCESS && !(phase == Phase.FEED && state == TaskState.TIMEOUT))
            return failed("world_process_native_step_failed: " + result.message(), FailureType.UNKNOWN);
        if (phase == Phase.FEED) {
            ItemStack input = batchInputs.get(feedIndex);
            inputs.confirmDrop(state, result.data(), input);
            // 暂停期间原料可能已经完成转化；最后一份投料以冻结回执登记，不要求原料在恢复时仍躺在池里。
            if (++feedIndex == batchInputs.size()) { phase = Phase.WAIT_OUTPUT; waitUntil = world.getGameTime() + 400; nextEventRead = world.getGameTime(); }
            else inputs.requirePresent();
        }
        return TaskState.RUNNING;
    }

    private boolean align(BlockPos at) {
        if (alignment == null) alignment = org.maiwithu.maicraft.core.task.build.BuildEdgeMotion.alignAt(Vec3.atBottomCenterOf(at),
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(), cell -> world.isLoaded(cell)
                        && world.getFluidState(cell).isEmpty() && !NavigationSafetyContext.protectsMutation(cell));
        var state = alignment.tick(player);
        if (state == org.maiwithu.maicraft.core.task.build.BuildEdgeMotion.Status.FAILED)
            throw new IllegalStateException("world_process_anchor_alignment_failed: " + alignment.failure());
        if (state != org.maiwithu.maicraft.core.task.build.BuildEdgeMotion.Status.ARRIVED) return false;
        alignment.release(player); alignment = null; return true;
    }

    private String id() { return r.getToolCallId() + "-process-" + (++serial); }
    private long deadline(long ticks) { long until = world.getGameTime() + ticks; r.extendDeadlineTo(until); return until; }
    private TaskState failed(String issue, FailureType type) { failure = issue == null ? "world_process_failed" : issue; fail(failure, type); return TaskState.FAILED; }

    @Override public void stop(LocalPlayer player, Task.StopReason reason) {
        if (child != null) child.stop(player, reason);
        if (alignment != null) { alignment.stop(player); alignment = null; }
        super.stop(player, reason);
    }
    @Override protected void cleanup() {
        if (child != null) { child.stop(player, Task.StopReason.REPLACED); child.result(TaskState.CANCELLED); child = null; childRecord = null; }
        if (alignment != null) { alignment.release(player); alignment = null; }
        if (events != null && net.minecraft.client.Minecraft.getInstance().player == player && player.level() == world) events.close();
        // 取消后留下已经投出的真实物品供观察，不再自动补料或捡走不能证明属于本次加工的东西。
        menus.cleanup(player); super.cleanup();
    }
    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("recipe_id", r.recipeId.toString()); data.put("requested_batches", r.batches); data.put("completed_batches", batchIndex);
        data.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT)); data.put("batches", List.copyOf(completed));
        data.put("confirmed_input_count", inputs == null ? 0 : inputs.delivered().stream().mapToInt(ItemStack::getCount).sum());
        data.put("trigger_step_started", triggerStepStarted);
        if (settlement != null && !settlement.outputEvidence().isEmpty()) data.put("pending_output", settlement.outputEvidence());
        if (collectionDetail != null) data.put("collection_detail", collectionDetail);
        data.put("native_consumption_reserved", r.nativeConsumptionReserved()); data.put("mechanical_retry_allowed", !r.nativeConsumptionReserved());
        data.put("output_collected", phase == Phase.COMPLETE); data.put("outcome_uncertain", r.nativeConsumptionReserved() && phase != Phase.COMPLETE);
        boolean nativeVerified = events != null && events.nativeEvents && phase == Phase.COMPLETE;
        data.put("native_recipe_verified", nativeVerified); data.put("machine_production_verified", nativeVerified);
        data.put("evidence_scope", events != null && events.nativeEvents ? "native_recipe_event_and_player_pickup" : "client_observed_output_and_inventory");
        if (failure != null) data.put("issue_code", failure); return data;
    }
    @Override public Map<String, Object> progress() { return resultData(); }
    @Override protected String successMessage() { return "Completed finite native processing and verified collection of every requested output batch"; }
}
