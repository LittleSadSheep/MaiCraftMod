// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.integration.machine.assembly.AePartTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineCommissioning;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineInstallation;
import org.maiwithu.maicraft.core.integration.machine.assembly.MekanismConfigureTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineContentsTaskRecord;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildPreviewGate;
import org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 整套机器装配的流程入口：观察现场、放普通方块、装部件、封洞、放初始物品、设过滤和接口，最后复查。
 * 每个阶段把具体动作交给现有任务执行；本类负责先后顺序、等待和最终结果。结构完成后，生产是否成功仍需另外运行观察。
 */
final class MachineBuildTask extends AbstractCompanionTask<MachineBuildTaskRecord> {
    private enum Phase { SURVEY, BLOCKS, PARTS, SEAL, CONTENTS, FILTERS, CONFIGURE, VERIFY, COMMISSION, DONE }
    private final Level world;
    private final Map<BlockPos, net.minecraft.world.level.block.state.BlockState> preview;
    private final JsonArray configurations;
    private final List<org.maiwithu.maicraft.client.preview.PreviewPart> previewParts;
    private final List<BlockPos> plannedPositions;
    private final MachineBuildSurvey survey;
    private final JsonArray requirements;
    private final JsonArray initialContents;
    private final JsonArray filters;
    private final MachineBuildCompletion completion;
    private final JsonArray commissioning = new JsonArray();
    private final SemanticMaterialSupplyCoordinator supply = new SemanticMaterialSupplyCoordinator();
    private Phase phase = Phase.SURVEY;
    private Task child;
    private TaskRecord childRecord;
    private int partIndex, configIndex, verifyIndex, evidenceIndex, serial;
    private int verifyConfigIndex, requirementIndex;
    private int contentsIndex;
    private int filterIndex;
    private long commissioningDeadline;
    private boolean acquiringItem;
    private boolean blocksStarted;
    private boolean sealingStarted;
    private Map<String, Object> lastChild = Map.of();
    private String failureCode;

    MachineBuildTask(LocalPlayer player, MachineBuildTaskRecord record) {
        super(player, record); world = player.level(); preview = record.plan.preview();
        plannedPositions = record.plan.positions();
        survey = new MachineBuildSurvey(record.plan);
        previewParts = record.plan.parts().stream()
                .map(part -> new org.maiwithu.maicraft.client.preview.PreviewPart(part.position(), part.spec().itemId(),
                        part.spec().side() == null ? "center" : part.spec().side().getSerializedName())).toList();
        JsonObject report = record.plan.report();
        completion = new MachineBuildCompletion(report.has("explicit_blueprint") && report.get("explicit_blueprint").getAsBoolean());
        configurations = report.has("configurations") ? report.getAsJsonArray("configurations") : new JsonArray();
        requirements = report.has("commissioning_requirements") ? report.getAsJsonArray("commissioning_requirements") : new JsonArray();
        initialContents = report.has("initial_contents") ? report.getAsJsonArray("initial_contents") : new JsonArray();
        filters = report.has("filters") ? report.getAsJsonArray("filters") : new JsonArray();
    }

    @Override protected TaskState onTick() {
        if (player.level() != world) return failure("machine_world_changed", "The reviewed world changed.");
        Decision decision = BuildPreviewGate.await(r, r.describe(), preview, previewParts);
        if (decision == Decision.WAITING) return TaskState.RUNNING;
        if (decision == Decision.CANCELLED) return TaskState.CANCELLED;
        // 有缺料任务时先把它推进完，取材期间保护机器计划格，避免为了材料拆掉当前机器。
        if (supply.active()) {
            var tick = org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext.withProtectedArea(
                    plannedPositions, List.of(), () -> supply.tick(player, this::runChild));
            r.extendDeadlineTo(supply.childDeadline());
            if (tick.status() == SemanticMaterialSupplyCoordinator.Status.FAILED)
                return failure("machine_material_supply_failed", tick.message());
            return TaskState.RUNNING;
        }
        if (child != null) return tickChild();
        return switch (phase) {
            case SURVEY -> surveyParts();
            case BLOCKS -> buildBlocks();
            case PARTS -> installPart();
            case SEAL -> seal();
            case CONTENTS -> contents();
            case FILTERS -> filters();
            case CONFIGURE -> configure();
            case VERIFY -> verify();
            case COMMISSION -> commission();
            case DONE -> TaskState.SUCCESS;
        };
    }

    private TaskState surveyParts() {
        var progress = survey.tick(world);
        if (progress.failure() != null) return failure("machine_site_blocked", progress.failure() + " Construction has not started.");
        if (progress.needsLoad() != null) return load(progress.needsLoad());
        if (progress.complete()) phase = Phase.BLOCKS;
        return TaskState.RUNNING;
    }

    // 普通方块只启动一轮子任务，之后继续装部件；生存模式先经过供料，创造模式直接建。
    private TaskState buildBlocks() {
        if (blocksStarted || r.plan.blocks().isEmpty() && survey.partClears().isEmpty()) { phase = Phase.PARTS; return TaskState.RUNNING; }
        blocksStarted = true;
        boolean consume = !WorkProfile.of(player).freeMaterials();
        var plan = r.plan.blockTask(id(), r.getDeadlineGameTime(), consume, survey.partClears(), survey.openings());
        // 这份附加检查只确认世界对象和加载状态，没有保存并逐次比较场地旧方块；它不能代替施工器的替换许可。
        plan.executionGuards(r.plan.constructionAccess(plan), actor -> actor.level() == world,
                (actor, pos) -> actor.level() == world && actor.level().isLoaded(pos), (actor, pos) -> {});
        if (consume) start(new SemanticBuildSupplyTaskRecord(id(), r.getDeadlineGameTime(), plan,
                r.materialPolicy, List.of(), false, r.protectedLabels, false));
        else start(plan);
        return TaskState.RUNNING;
    }

    // 部件已在正确位置就跳过，否则先确保有物品，再交给 AE2 的原生安装任务。
    private TaskState installPart() {
        if (partIndex >= r.plan.parts().size()) { phase = Phase.SEAL; return TaskState.RUNNING; }
        var part = r.plan.parts().get(partIndex);
        if (!world.isLoaded(part.position())) return load(part.position());
        if (MachineInstallation.matches(world, part.position(), part.spec())) { partIndex++; return TaskState.RUNNING; }
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(part.spec().item());
        if (ensureItem(item)) start(new AePartTaskRecord(id(), deadline(), part.position(), item.toString(), part.spec().side()));
        return TaskState.RUNNING;
    }

    // 临时出入口留到普通结构和部件完成后再封，封口任务负责先走到外面。
    private TaskState seal() {
        if (sealingStarted || r.plan.seals().isEmpty()) { phase = Phase.CONTENTS; return TaskState.RUNNING; }
        sealingStarted = true;
        start(new org.maiwithu.maicraft.core.task.build.MachineSealingTaskRecord(id(), r.getDeadlineGameTime(),
                r.plan.seals(), r.materialPolicy, r.protectedLabels, plannedPositions));
        return TaskState.RUNNING;
    }

    private TaskState contents() {
        if (contentsIndex >= initialContents.size()) { phase = Phase.FILTERS; return TaskState.RUNNING; }
        JsonObject content = initialContents.get(contentsIndex).getAsJsonObject();
        BlockPos at = MachineConstructionPlan.offset(r.plan.anchor(), content.get("offset"));
        if (!world.isLoaded(at)) return load(at);
        start(new MachineContentsTaskRecord(id(), deadline(), at, content.get("item_id").getAsString(), content.get("count").getAsInt()));
        return TaskState.RUNNING;
    }

    private TaskState configure() {
        if (configIndex >= configurations.size()) { phase = Phase.VERIFY; return TaskState.RUNNING; }
        JsonObject config = configurations.get(configIndex).getAsJsonObject();
        BlockPos position = MachineConstructionPlan.offset(r.plan.anchor(), config.get("offset"));
        if (!world.isLoaded(position)) return load(position);
        Direction face = Direction.byName(config.get("face").getAsString());
        if (face == null) return failure("machine_configuration_invalid", "Compiler produced an invalid port face.");
        if (MachineCompletionChecks.configurationMatches(world, r.plan.anchor(), config)) { configIndex++; return TaskState.RUNNING; }
        if (ensureItem(ResourceLocation.parse("mekanism:configurator"))) start(new MekanismConfigureTaskRecord(
                id(), deadline(), position, face, config.get("medium").getAsString(), config.get("mode").getAsString()));
        return TaskState.RUNNING;
    }

    private TaskState filters() {
        if (filterIndex >= filters.size()) { phase = Phase.CONFIGURE; return TaskState.RUNNING; }
        var filter = filters.get(filterIndex).getAsJsonObject();
        BlockPos at = MachineConstructionPlan.offset(r.plan.anchor(), filter.get("offset"));
        if (!world.isLoaded(at)) return load(at);
        start(new org.maiwithu.maicraft.core.integration.machine.assembly.MekanismFilterTaskRecord(
                id(), deadline(), at, filter.get("item_id").getAsString()));
        return TaskState.RUNNING;
    }

    private boolean ensureItem(ResourceLocation id) {
        return ensureItem(id, 1);
    }
    // 先数普通背包；缺少时创造模式用原版取物任务，生存模式按声明的来源策略获取。
    private boolean ensureItem(ResourceLocation id, int count) {
        int have = player.getInventory().items.stream().filter(stack -> !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
        if (have >= count) return true;
        if (player.getAbilities().instabuild) {
            acquiringItem = true;
            start(new org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsTaskRecord(id(), deadline(),
                    new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(id)), count - have));
            return false;
        }
        supply.begin(player, r.getToolCallId(), r.getDeadlineGameTime(),
                new SemanticMaterialSupplyCoordinator.Demand(List.of(id), count, "machine assembly material"),
                r.materialPolicy, List.of(), false, r.protectedLabels);
        return false;
    }

    // 重新读取普通格、部件及已要求的接口配置；每次最多检查 128 项，不直接相信前一阶段说成功。
    private TaskState verify() {
        int budget = 128;
        int blocks = r.plan.blocks().size();
        while (verifyIndex < blocks + r.plan.parts().size() && budget-- > 0) {
            BlockPos position;
            if (verifyIndex < blocks) {
                var target = r.plan.blocks().get(verifyIndex); position = target.pos();
                if (!world.isLoaded(position)) return load(position);
                if (!target.matches(world.getBlockState(position)))
                    return failure("machine_geometry_changed", "A built target or maintenance clearance no longer matches the frozen layout.");
            } else {
                var part = r.plan.parts().get(verifyIndex - blocks); position = part.position();
                if (!world.isLoaded(position)) return load(position);
                if (!MachineInstallation.matches(world, position, part.spec()))
                    return failure("machine_part_changed", "An installed AE2 part no longer matches the frozen layout.");
            }
            verifyIndex++;
        }
        if (verifyIndex < blocks + r.plan.parts().size()) return TaskState.RUNNING;
        while (verifyConfigIndex < configurations.size() && budget-- > 0) {
            var config = configurations.get(verifyConfigIndex).getAsJsonObject();
            BlockPos at = MachineConstructionPlan.offset(r.plan.anchor(), config.get("offset"));
            if (!world.isLoaded(at)) return load(at);
            if (!MachineCompletionChecks.configurationMatches(world, r.plan.anchor(), config))
                return failure("machine_configuration_changed", "A native interface mode changed before final acceptance.");
            verifyConfigIndex++;
        }
        if (verifyConfigIndex >= configurations.size()) {
            if (completion.acceptGeometry()) {
                phase = Phase.DONE; r.verified(); return TaskState.SUCCESS;
            }
            phase = Phase.COMMISSION; commissioningDeadline = 0;
        }
        return TaskState.RUNNING;
    }

    // 收集部件观察，再逐条检查布局声明的形成要求；每条形成要求开始观察后最多等 200 刻。
    private TaskState commission() {
        int budget = 16;
        while (evidenceIndex < r.plan.components().size() && budget-- > 0) {
            BlockPos position = r.plan.components().get(evidenceIndex);
            if (!world.isLoaded(position)) return load(position);
            JsonObject evidence = MachineCommissioning.inspect(world, position);
            evidence.addProperty("component_index", evidenceIndex++); commissioning.add(evidence);
        }
        if (evidenceIndex < r.plan.components().size()) return TaskState.RUNNING;
        if (requirementIndex < requirements.size()) {
            JsonObject requirement = requirements.get(requirementIndex).getAsJsonObject();
            BlockPos missing = MachineCompletionChecks.regionToLoad(world, r.plan.anchor(), requirement);
            if (missing != null) return approach(missing);
            if (commissioningDeadline == 0) commissioningDeadline = world.getGameTime() + 200;
            JsonObject evidence = MachineCompletionChecks.inspect(world, r.plan.anchor(), requirement);
            if (!MachineCompletionChecks.satisfied(requirement, evidence)) {
                if (world.getGameTime() < commissioningDeadline) return TaskState.RUNNING;
                commissioning.add(evidence);
                return failure("machine_commissioning_incomplete", "The native multiblock has not confirmed its expected formation and bounds.");
            }
            commissioning.add(evidence); requirementIndex++; commissioningDeadline = 0; return TaskState.RUNNING;
        }
        completion.acceptCommissioning();
        phase = Phase.DONE; r.verified(); return TaskState.SUCCESS;
    }

    private void start(TaskRecord record) { childRecord = record; child = TaskFactory.create(player, record); }
    // 子任务结束后先取结果。容器缺料只有在动作已结清、自己打开的菜单已关闭时，才允许转去补料再回来。
    private TaskState tickChild() {
        TaskState state = player.level().getGameTime() >= childRecord.getDeadlineGameTime()
                ? TaskState.TIMEOUT : runChild(child);
        r.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (state == null) return TaskState.RUNNING;
        if (state == TaskState.TIMEOUT) child.stop(player, StopReason.REPLACED);
        TaskResult result = child.result(state);
        lastChild = result == null || result.data() == null ? Map.of() : result.data();
        child = null; childRecord = null;
        if (phase == Phase.CONTENTS && !acquiringItem && "machine_contents_material_shortage".equals(lastChild.get("failure_code"))
                && Boolean.TRUE.equals(lastChild.get("effects_settled")) && Boolean.TRUE.equals(lastChild.get("owned_menu_closed"))
                && lastChild.get("missing_count") instanceof Number missing && missing.intValue() > 0) {
            ensureItem(ResourceLocation.parse(initialContents.get(contentsIndex).getAsJsonObject().get("item_id").getAsString()), missing.intValue());
            return TaskState.RUNNING;
        }
        if (result == null || !result.success()) return failure("machine_assembly_stage_failed",
                result == null ? "Native assembly did not produce a receipt." : result.message());
        if (!acquiringItem && phase == Phase.PARTS) partIndex++;
        if (!acquiringItem && phase == Phase.CONFIGURE) configIndex++;
        if (!acquiringItem && phase == Phase.CONTENTS) contentsIndex++;
        if (!acquiringItem && phase == Phase.FILTERS) filterIndex++;
        acquiringItem = false;
        return TaskState.RUNNING;
    }

    private TaskState load(BlockPos target) {
        if (nav == null) nav = PlayerNav.toGoal(player, () -> NavGoal.column(target.getX(), target.getZ()), 1.0,
                () -> world.isLoaded(target), PlayerNav.ContextProvider.DEFAULT);
        var state = nav.tick();
        if (state == PlayerNav.Status.FAILED) { stopNav(); return failure("machine_chunk_unreachable", "Cannot load the next construction region."); }
        if (state == PlayerNav.Status.ARRIVED) stopNav();
        return TaskState.RUNNING;
    }
    private TaskState approach(BlockPos target) {
        if (nav == null) nav = PlayerNav.toGoal(player, () -> NavGoal.near(target, 3), 1.0,
                () -> player.blockPosition().distSqr(target) <= 9, PlayerNav.ContextProvider.DEFAULT);
        var state = nav.tick();
        if (state == PlayerNav.Status.FAILED) { stopNav(); return failure("machine_commissioning_unreachable", "Cannot approach the multiblock for formation verification."); }
        if (state == PlayerNav.Status.ARRIVED) stopNav();
        return TaskState.RUNNING;
    }
    // 新启动的单个装配动作先给三分钟；外层仍要传播或检查子任务期限。
    private long deadline() { return player.level().getGameTime() + 3 * 60 * 20; }
    private String id() { return r.getToolCallId() + "-assembly-" + (++serial); }
    private TaskState failure(String code, String message) { failureCode = code; fail(message, FailureType.UNKNOWN); return TaskState.FAILED; }

    // 结束时停止尚在运行的子任务、取消供料、释放预览，再清理公共导航状态。
    @Override protected void cleanup() {
        if (child != null) { child.stop(player, StopReason.REPLACED); child.result(TaskState.CANCELLED); child = null; }
        supply.cancel(player); BuildPreviewGate.release(r); super.cleanup();
    }
    @Override protected String successMessage() { return "Declared machine structure constructed and checked; use operate_machine separately to configure and verify operation."; }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>(completion.report());
        data.put("machine_layout", r.plan.report());
        data.put("commissioning", commissioning); data.put("installed_parts", partIndex);
        data.put("configured_interfaces", configIndex); data.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        data.put("initialized_containers", contentsIndex);
        data.put("configured_output_filters", filterIndex);
        if (!lastChild.isEmpty()) data.put("last_native_stage", lastChild);
        if (failureCode != null) data.put("failure_code", failureCode);
        return data;
    }
}
