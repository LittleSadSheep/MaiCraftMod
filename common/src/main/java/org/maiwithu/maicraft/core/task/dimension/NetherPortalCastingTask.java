// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementTaskRecord;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
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
    private final List<Map<String, Object>> previousSearches = new ArrayList<>();
    private final Set<BlockPos> unavailableSources = new HashSet<>();
    private Set<BlockPos> platformFillSnapshot = Set.of();
    private PortalCastingSurvey survey;
    private NetherPortalCastingLayout layout;
    private List<PortalCastingStep> steps = List.of();
    private Task child;
    private TaskRecord childRecord;
    private PortalPreparationSupplies.Need supplyNeed;
    private BlockPos mutation;
    private BlockPos waterSource, lavaReturnSource, poolReturn;
    private boolean poolLookupDone, ignitionPrepared;
    private String operation = "prepare_water", issue = "";
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
        if (child != null) {
            // 取水可能离开原来已知的池边；离开前保留这里已经观察到的候选，回来后继续同一扇门。
            if (!waterPrepared && layout == null && !survey.complete()
                    && !(childRecord instanceof PortalResourceSearchTaskRecord search && search.resource == PortalResourceSearchTaskRecord.Resource.LAVA_POOL)) {
                var observed = survey.tick(); if (observed != null) selectLayout(observed);
            }
            return tickChild();
        }
        // 先拿桶并真正装水，再找可浇筑的池岸；附近没有岩浆不能让取水阶段永远得不到执行。
        if (!waterPrepared) {
            if (PlayerInv.count(player.getInventory(), Items.WATER_BUCKET) > 0) waterPrepared = true;
            else {
                if (PlayerInv.count(player.getInventory(), Items.BUCKET) == 0) {
                    // 现有桶装着岩浆时先原生倒回已观察的池子；不因为桶被占用就另挖三块铁制作第二只桶。
                    if (PlayerInv.count(player.getInventory(), Items.LAVA_BUCKET) > 0) return emptyLavaBucket();
                    return supply(new PortalPreparationSupplies.Need(List.of(Items.BUCKET), 1, "single casting bucket"));
                }
                return fill(Blocks.WATER);
            }
        }
        // 水桶 -> 点火用品 -> 池岸与施工材料；不能等整扇门浇完才发现没有点火材料。
        if (!ignitionPrepared) {
            var ignition = PortalPreparationSupplies.ignition();
            if (!ignition.satisfied(player)) return supply(ignition);
            ignitionPrepared = true;
        }
        if (layout == null) {
            operation = "survey_lava_pool";
            var observed = survey.tick();
            if (observed == null) return survey.complete() ? searchResource(PortalResourceSearchTaskRecord.Resource.LAVA_POOL) : TaskState.RUNNING;
            selectLayout(observed);
        }
        // 远处取水后原池可能已经卸载；先回到勘查时确认的干燥站位，让正常移动加载现场，再继续施工。
        if (!world.isLoaded(layout.origin()) || !initialSupplied && poolReturn != null
                && poolReturn.distSqr(player.blockPosition()) > 16) {
            if (poolReturn == null) return failure("casting_pool_return_stance_unknown");
            return start(MoveToTaskRecord.strictStance(id(), deadline(), poolReturn, r.mayAlterTerrain), null, false, "return_to_pool");
        }
        // 起手只准备普通工具和一只桶；模具材料消耗完时内部补给，仍接着同一张施工单。
        if (!initialSupplied) {
            // 补工具或点火用品可能移动、消耗物品；正式施工前再核实水桶和点火用品仍真实在包里。
            if (PlayerInv.count(player.getInventory(), Items.WATER_BUCKET) == 0) { waterPrepared = false; return TaskState.RUNNING; }
            if (!PortalPreparationSupplies.ignition().satisfied(player)) { ignitionPrepared = false; return TaskState.RUNNING; }
            var need = PortalCastingStep.supplies(player, true, PortalCastingTerrain.missingPlatform(world, layout).size());
            if (need != null) return supply(need);
            initialSupplied = true;
        }
        // 已经空着的操作空间只需核对一次，不为每个空气格空等一个游戏刻；真正拆块仍逐次走原生回执。
        while (cursor < steps.size() && steps.get(cursor).kind() == PortalCastingStep.Kind.CLEAR) {
            BlockPos at = steps.get(cursor).target();
            BlockState actual = PortalPreparationSite.read(world, at);
            if (actual == null || NavigationSafetyContext.protectsMutation(at)) break;
            if (!actual.isAir() && actual.getFluidState().isEmpty()
                    && !(actual.is(Blocks.OBSIDIAN) && layout.frame().frame().contains(at))) break;
            next();
        }
        if (cursor >= steps.size()) { complete = true; return TaskState.SUCCESS; }
        var step = steps.get(cursor);
        BlockPos at = step.target();
        BlockState actual = PortalPreparationSite.read(world, at);
        if (actual == null) return failure("casting_target_unloaded");
        if (NavigationSafetyContext.protectsMutation(at)) return failure("casting_target_protected");
        return switch (step.kind()) {
            case PREPARE_SITE -> {
                var missing = PortalCastingTerrain.missingPlatform(world, layout);
                if (missing.isEmpty()) yield next();
                platformFillSnapshot = Set.copyOf(missing);
                var need = PortalCastingStep.supplies(player, true, missing.size());
                if (need != null) yield supply(need);
                yield start(PortalCastingStep.platform(player, id(), deadline(), missing, layout, r.policy),
                        at, true, "prepare_bank_platform");
            }
            case CLEAR -> {
                if (actual.isAir() || !actual.getFluidState().isEmpty()
                        || actual.is(Blocks.OBSIDIAN) && layout.frame().frame().contains(at)) yield next();
                yield start(PortalCastingStep.clear(player, id(), deadline(), at, actual, r.mayAlterTerrain), at, true, "clear");
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
                    yield start(PortalCastingStep.clear(player, id(), deadline(), at, actual, r.mayAlterTerrain), at, false, "excavate_cast_cell");
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
        var excluded = new HashSet<BlockPos>();
        if (layout != null) excluded.addAll(layout.footprint());
        excluded.addAll(unavailableSources);
        if (fluid == Blocks.WATER && waterSource != null && !unavailableSources.contains(waterSource)) {
            var actual = PortalPreparationSite.read(world, waterSource);
            if (actual != null && actual.is(Blocks.WATER) && actual.getFluidState().isSource())
                return start(remove(waterSource, actual), waterSource, false, "fill_water");
            unavailableSources.add(waterSource); waterSource = null;
        }
        // 装水只采用正式可见静源查询，避免先命中地下水或流水，耗尽站位尝试后才发现地表水在远处。
        if (fluid == Blocks.WATER) return searchResource(PortalResourceSearchTaskRecord.Resource.WATER);
        var observed = survey.source(fluid, player.blockPosition(), excluded);
        if (observed.position() == null) return !observed.complete() ? TaskState.RUNNING : fluid == Blocks.WATER
                ? searchResource(PortalResourceSearchTaskRecord.Resource.WATER) : failure(operation + "_not_observed");
        return start(remove(observed.position(), world.getBlockState(observed.position())), observed.position(), false,
                fluid == Blocks.WATER ? "fill_water" : "fill_lava");
    }

    private TaskState supply(PortalPreparationSupplies.Need need) {
        supplyNeed = need;
        String stage = need.purpose().equals("portal ignition") ? "prepare_ignition"
                : need.purpose().equals("single casting bucket") ? "prepare_bucket" : "supply";
        return start(need.acquire(id(), deadline(), r.policy, true), null, false, stage);
    }
    private TaskState searchResource(PortalResourceSearchTaskRecord.Resource resource) {
        String missing = resource == PortalResourceSearchTaskRecord.Resource.WATER ? "find_water_source_not_observed"
                : !waterPrepared ? "casting_lava_bucket_pool_not_observed" : "casting_lava_pool_not_observed";
        if (resource == PortalResourceSearchTaskRecord.Resource.LAVA_POOL && (poolLookupDone || r.policy.resourceSearchDistance() == 0))
            return failure(missing);
        var record = new PortalResourceSearchTaskRecord(id(), deadline(), resource,
                r.policy.resourceSearchDistance() == 0 ? r.radius : Math.min(128, r.policy.resourceSearchDistance()),
                r.policy.resourceSearchDistance(), r.mayAlterTerrain);
        record.excluded = Set.copyOf(unavailableSources);
        return start(record, null, false, switch (resource) {
            case WATER -> "locate_water";
            case LAVA_SOURCE -> "find_pool_for_bucket_reuse";
            case LAVA_POOL -> "locate_lava_pool";
        });
    }
    private void selectLayout(NetherPortalCastingLayout selected) {
        layout = selected; steps = PortalCastingStep.plan(layout);
        poolReturn = PortalCastingTerrain.platform(layout).stream().map(BlockPos::above)
                .filter(p -> BlockHelper.isDryStandable(world, p))
                .min(Comparator.comparingDouble(p -> p.distSqr(player.blockPosition())))
                .orElseGet(() -> BlockHelper.isDryStandable(world, player.blockPosition()) ? player.blockPosition().immutable() : null);
    }
    private TaskState emptyLavaBucket() {
        operation = "find_pool_for_bucket_reuse";
        // 腾桶只需已有的真实岩浆源，不先强求它已满足整扇门的余量；合法施工池在前置用品齐备后单独核实。
        if (lavaReturnSource == null || unavailableSources.contains(lavaReturnSource)) {
            return searchResource(PortalResourceSearchTaskRecord.Resource.LAVA_SOURCE);
        }
        BlockPos at = lavaReturnSource;
        var actual = PortalPreparationSite.read(world, at);
        if (actual == null || !actual.is(Blocks.LAVA) || !actual.getFluidState().isSource())
            return failure("casting_bucket_return_source_changed");
        return start(FluidPlacementTaskRecord.emptyIntoSource(id(), deadline(), at, actual, Set.of(at)),
                at, false, "empty_lava_bucket");
    }
    private FluidPlacementTaskRecord place(BlockPos at, BlockState expected) {
        return new FluidPlacementTaskRecord(id(), deadline(), at, expected, layout.footprint());
    }
    private FluidPlacementTaskRecord remove(BlockPos at, BlockState source) {
        var scope = new HashSet<BlockPos>();
        if (layout != null) scope.addAll(layout.footprint());
        scope.add(at);
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
        // 一批补台可以同时修改所声明的多个格子；其余门框、模具和后续操作空间继续受到导航保护。
        var writable = childRecord instanceof BuildTaskRecord build
                ? build.targets.stream().map(BuildTaskRecord.Target::pos).toList() : mutation == null ? List.<BlockPos>of() : List.of(mutation);
        return NavigationSafetyContext.withPreservedStructures(layout.footprint().stream()
                .filter(p -> !writable.contains(p)).toList(), action);
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
        receipts.add(evidence); TaskRecord finishedRecord = childRecord; child = null; childRecord = null;
        if (result == null) return failure("casting_child_receipt_missing");
        if (finishedRecord instanceof PortalResourceSearchTaskRecord search) {
            if (terminal != TaskState.SUCCESS || !result.success() || search.observedPosition == null)
                return failure(search.resource == PortalResourceSearchTaskRecord.Resource.WATER ? "find_water_source_not_observed"
                        : !waterPrepared ? "casting_lava_bucket_pool_not_observed" : "casting_lava_pool_not_observed");
            if (search.resource == PortalResourceSearchTaskRecord.Resource.WATER) waterSource = search.observedPosition;
            else if (search.resource == PortalResourceSearchTaskRecord.Resource.LAVA_SOURCE) lavaReturnSource = search.observedPosition;
            else {
                // 使用刚完成的可见查池结果作为选址中心，不能又在旧的无池位置重复勘查。
                previousSearches.add(survey.observations()); survey.close();
                survey = new PortalCastingSurvey(world, search.observedPosition, Math.max(r.radius, search.loadedRadius));
                poolLookupDone = true;
            }
            return TaskState.RUNNING;
        }
        if (terminal != TaskState.SUCCESS || !result.success()) {
            FailureType reason = FailureType.fromCode(result.data().get("failure_type"), FailureType.TARGET_LOST);
            // 取桶尚未提交且只是站位/源格变化时换一个真实源格；不确定或已提交的动作绝不机械重放。
            if ((operation.startsWith("fill_") || operation.equals("empty_lava_bucket"))
                    && Boolean.FALSE.equals(result.data().get("bucket_submitted")) && sourceAttempts++ < 8) {
                unavailableSources.add(mutation); return TaskState.RUNNING;
            }
            // 只有真正的路线或站位失败才换池；背包满、缺料和预算耗尽必须保留原意，不能伪装成池岸不可达。
            if ("prepare_bank_platform".equals(operation) && (reason == FailureType.NO_PATH
                    || reason == FailureType.STANCE_DUD || reason == FailureType.OUT_OF_REACH)) {
                var stillMissing = PortalCastingTerrain.missingPlatform(world, layout);
                if (stillMissing.size() >= platformFillSnapshot.size()) {
                    var fallback = survey.nextFallback(true);
                    if (fallback != null) {
                        selectLayout(fallback.layout());
                        cursor = 0; cleared = false; drainStarted = -1;
                        return TaskState.RUNNING;
                    }
                    return siteUnreachableFailure();
                }
            }
            issue = "casting_" + operation + "_failed";
            // 默认失败说明直接给出最后一个原生卡点，不让模型翻遍前面已经完成的每桶历史才能决策。
            fail(issue + ": " + result.message(), reason); return TaskState.FAILED;
        }
        if (supplyNeed != null) {
            if (!supplyNeed.satisfied(player)) return failure("casting_supply_unverified");
            supplyNeed = null;
        }
        // 特殊模式或其他原生规则可能保留满桶；未实际返空桶就停下报告，不能反复倒桶或伪造已能装水。
        if ("empty_lava_bucket".equals(operation) && PlayerInv.count(player.getInventory(), Items.BUCKET) == 0)
            return failure("casting_empty_bucket_return_unverified");
        sourceAttempts = 0;
        return nextAfterChild ? next() : TaskState.RUNNING;
    }

    private TaskState next() { cursor++; cleared = false; drainStarted = -1; return TaskState.RUNNING; }
    private TaskState failure(String code) {
        issue = code;
        String detail = switch (code) {
            case "casting_lava_pool_not_observed" -> "No usable lava-pool bank was observed after the bounded preparation search. Water preparation completed; no casting construction started. Search and exploration receipts retain the checked scope.";
            case "find_water_source_not_observed" -> "No carried water bucket or collectable water source was obtained after the bounded preparation search. No casting construction started; inspect the retained source and exploration receipts.";
            case "casting_lava_bucket_pool_not_observed" -> "The carried bucket contains lava, but no existing lava source was observed for returning it after the bounded preparation search. The filled bucket is retained; water preparation and construction have not started.";
            case "casting_no_reachable_candidate" -> "All compliant lava-pool candidates within the loaded search area were reachable on paper but no real stance could be worked from. Each candidate's distance and rejection reason is reported; this is not evidence that the surrounding terrain lacks a pool, only that no surveyed site was buildable.";
            default -> code;
        };
        fail(detail, FailureType.TARGET_LOST); return TaskState.FAILED;
    }

    private TaskState siteUnreachableFailure() {
        return failure("casting_no_reachable_candidate");
    }
    NetherPortalCastingLayout layout() { return layout; }
    String stage() { return operation; }

    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("method", "lava_cast"); data.put("casting_actions_completed", complete);
        data.put("casting_step", cursor); data.put("casting_step_count", steps.size()); data.put("operation", operation);
        data.put("native_steps", List.copyOf(receipts));
        if (!previousSearches.isEmpty()) data.put("previous_searches", List.copyOf(previousSearches));
        // 接受备门目标不等于已经开始施工；缺水、未查到池岸与已做完的装水动作分别呈现。
        data.put("construction_phase_started", layout != null && waterPrepared && ignitionPrepared && initialSupplied);
        data.put("resource_preparation", Map.of("initial_water_prepared", waterPrepared,
                "empty_buckets", PlayerInv.count(player.getInventory(), Items.BUCKET),
                "water_buckets", PlayerInv.count(player.getInventory(), Items.WATER_BUCKET),
                "lava_buckets", PlayerInv.count(player.getInventory(), Items.LAVA_BUCKET),
                "ignition_prepared", ignitionPrepared, "resource_search_distance", r.policy.resourceSearchDistance(),
                "lava_pool_selected", layout != null,
                "search", survey == null ? Map.of() : survey.observations()));
        // 缺的是空桶、点火用品还是模具材料直接列明，不能迫使模型从深层采铁失败反推本次补给目标。
        if (supplyNeed != null) data.put("pending_supply", Map.of("purpose", supplyNeed.purpose(), "count", supplyNeed.count(),
                "item_ids", supplyNeed.alternatives().stream().map(BuiltInRegistries.ITEM::getKey).map(Object::toString).toList()));
        if (layout != null) data.put("portal_observation", layout.observation(p -> PortalPreparationSite.read(world, p)));
        if (!issue.isEmpty()) {
            data.put("issue_code", issue);
            if (issue.equals("casting_lava_pool_not_observed") || issue.equals("find_water_source_not_observed"))
                data.put("recovery_options", List.of(Map.of("id", "locate_casting_resources",
                        "missing_resource", issue.equals("casting_lava_pool_not_observed") ? "lava_pool" : "water",
                        "summary", "Choose a known resource location or explore fresh terrain, then use current observations. find_block only scans loaded visible terrain; repeating this casting request in the same unchanged area does not locate new resources.")));
            if (issue.equals("casting_no_reachable_candidate"))
                data.put("recovery_options", List.of(Map.of("id", "locate_casting_resources",
                        "missing_resource", "reachable_standing_position",
                        "summary", "Use travel to a different pool at a known lava position, or explore to load fresh terrain; the surveyed pools were compliant but no stance was reachable from the current loaded area.")));
            if (!receipts.isEmpty() && Boolean.FALSE.equals(receipts.getLast().get("success")))
                data.put("native_failure", receipts.getLast());
        }
        return data;
    }
    @Override public Map<String, Object> progress() {
        var data = new LinkedHashMap<>(resultData());
        if (child != null) data.put("child", child.progress()); return data;
    }
    @Override public String describeCurrentAction() {
        if (child != null && child.describeCurrentAction() != null) return child.describeCurrentAction();
        return switch (operation) {
            case "prepare_bucket" -> "准备浇筑用的桶";
            case "find_pool_for_bucket_reuse", "empty_lava_bucket" -> "把现有岩浆桶倒回池中，确认空桶返还";
            case "prepare_water", "find_water_source", "fill_water", "locate_water" -> "寻找水源并装水";
            case "prepare_ignition" -> "准备打火石或火焰弹";
            case "survey_lava_pool", "locate_lava_pool" -> "确认适合浇筑的岩浆池";
            case "return_to_pool" -> "取水完成，返回已知岩浆池";
            case "supply" -> "补齐浇筑工具和临时方块";
            case "clear", "excavate_cast_cell" -> "清理浇筑操作空间";
            case "prepare_bank_platform" -> "补齐池岸和倒桶站台";
            case "build_mold" -> "搭建导流模具";
            case "place_water" -> "放置导流水";
            case "fill_lava" -> "到池中装取岩浆";
            case "cast_lava" -> "逐格浇筑门框";
            case "recover_water" -> "收回导流水";
            case "draining" -> "等待水流退去";
            default -> "准备浇筑地狱门";
        };
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
