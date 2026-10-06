// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.task.structure.PhysicalStructureSearchTaskRecord;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** 按顺序执行：观察 → 获取材料 → 建造/修复 → 使用原生物品 → 核实真实传送门。 */
public final class PortalPreparationTask extends AbstractCompanionTask<PortalPreparationTaskRecord> {
    private enum Phase { SURVEY, SUPPLY, RETURN, BUILD, CAST, LOCATE, MOVE, ACTIVATE, VERIFY }
    private final ClientLevel world;
    private final BiFunction<LocalPlayer, TaskRecord, Task> childFactory;
    private PortalSiteSurvey survey;
    private PortalPreparationSite site;
    private NetherPortalCastingTask casting;
    private Phase phase = Phase.SURVEY;
    private Task child;
    private TaskRecord childRecord;
    private PortalPreparationSupplies.Need supplyNeed;
    private Map<String, Object> blockedFacts;
    private BlockPos supplyOrigin, activationTarget, activationStance;
    private PortalActivation activation;
    private Vec3 activationAim;
    private List<BlockPos> protectedLabels = List.of();
    private Map<String, Object> childEvidence = Map.of();
    private boolean end, searchedStronghold, complete, cleaned;
    private long verificationDeadline = -1;
    private String issue;
    private int serial;

    public PortalPreparationTask(LocalPlayer player, PortalPreparationTaskRecord record) {
        this(player, record, TaskFactory::create);
    }
    PortalPreparationTask(LocalPlayer player, PortalPreparationTaskRecord record,
                          BiFunction<LocalPlayer, TaskRecord, Task> childFactory) {
        this(player, record, childFactory, SURVEY_PLANNING_LIMIT_TICKS);
    }
    /** 选址勘察的宽上限：分钟级；超过仍无可用场址就如实收场，不再靠逐刻延期无限等待。 */
    private static final long SURVEY_PLANNING_LIMIT_TICKS = 2 * 60 * 20;
    private final long surveyPlanningLimitTicks;
    PortalPreparationTask(LocalPlayer player, PortalPreparationTaskRecord record,
                          BiFunction<LocalPlayer, TaskRecord, Task> childFactory, long surveyPlanningLimitTicks) {
        super(player, record); world = player.clientLevel; this.childFactory = childFactory;
        this.surveyPlanningLimitTicks = surveyPlanningLimitTicks;
    }

    @Override protected void onStart() {
        if (!r.policy.enabled()) { blocked("portal_preparation_permission_required", "Enable prepare_portal to prepare a portal."); return; }
        String current = world.dimension().location().toString();
        end = "minecraft:the_end".equals(r.destination) && "minecraft:overworld".equals(current);
        boolean nether = "minecraft:the_nether".equals(r.destination) && "minecraft:overworld".equals(current)
                || "minecraft:overworld".equals(r.destination) && "minecraft:the_nether".equals(current);
        if (!end && !nether) { blocked("portal_preparation_unsupported", "This route has no constructible vanilla portal; End exit portals require the dragon encounter."); return; }
        var protection = new ArrayList<BlockPos>();
        for (String label : r.policy.protectedLabels()) {
            var landmark = IntentRuntime.get().landmarks().stream().filter(l -> label.equalsIgnoreCase(l.label())).findFirst().orElse(null);
            if (landmark == null || landmark.position() == null) { blocked("protected_area_unknown", "Unknown protected place: " + label); return; }
            var p = landmark.position();
            if (p.dimension() == null || p.dimension().equals(current)) protection.add(new BlockPos(p.x(), p.y(), p.z()));
        }
        protectedLabels = List.copyOf(protection);
        // 只有已选择的主世界浇筑方案才进入单桶流程；普通门框和末地门继续使用对应的原生工序。
        if (!end && r.policy.method() == PortalPreparationPolicy.Method.LAVA_CAST) {
            if (!"minecraft:overworld".equals(current)) {
                blocked("casting_requires_overworld", "The selected lava-pool casting method is an Overworld construction method."); return;
            }
            phase = Phase.CAST;
            childRecord = new PortalPreparationTaskRecord(callId(), deadline(), r.destination, r.radius, r.mayAlterTerrain, r.policy);
            casting = new NetherPortalCastingTask(player, (PortalPreparationTaskRecord) childRecord);
            child = casting; return;
        }
        survey = new PortalSiteSurvey(world, player.blockPosition(), r.radius, end);
        planningPhaseBegin("survey", surveyPlanningLimitTicks);
    }

    @Override protected TaskState onTick() {
        if (player.level() != world) return blocked("portal_world_changed", "The preparation world changed; inspect before resuming.");
        return NavigationSafetyContext.withProtectedArea(protectedLabels, List.of(), this::advance);
    }

    private TaskState advance() {
        if (child != null) return tickChild();
        if (activation != null) {
            TaskState state = activation.tick(player);
            if (state == TaskState.RUNNING) return state;
            String failure = activation.failure(); activation.close(player); activation = null;
            if (state != TaskState.SUCCESS) return blocked("portal_activation_unconfirmed", failure);
            activationTarget = null; activationStance = null; verificationDeadline = -1;
        }
        if (site == null) return survey();
        if (!site.valid(world)) return blocked("portal_site_changed", "The portal frame or interior changed, unloaded or became protected.");
        if (site.active(world)) { complete = true; return TaskState.SUCCESS; }
        if (!site.missingBlocks(world).isEmpty() && !r.mayAlterTerrain)
            return blocked("portal_construction_permission_required", "Building or repairing the Nether frame needs may_alter_terrain=true.");
        if (end && !r.policy.allowRareConsumables() && !site.end().missingEyes(p -> PortalPreparationSite.read(world, p)).isEmpty())
            return blocked("rare_consumable_permission_required", "Inserting End portal eyes needs allow_rare_consumables=true.");
        var need = PortalPreparationSupplies.next(player, site);
        if (need != null) return supply(need);
        if (!site.missingBlocks(world).isEmpty())
            return start(site.construction(world, callId(), deadline()), Phase.BUILD);
        if (end && site.end().missingEyes(p -> PortalPreparationSite.read(world, p)).isEmpty()) {
            phase = Phase.VERIFY;
            if (verificationDeadline < 0) verificationDeadline = world.getGameTime() + 100;
            return world.getGameTime() < verificationDeadline ? TaskState.RUNNING
                    : blocked("portal_surface_not_observed", "All eyes are present but the full active portal surface was not observed.");
        }
        return activate();
    }

    private TaskState survey() {
        phase = Phase.SURVEY;
        // 勘察是有界守卫覆盖的规划期：超宽上限即如实失败，绝不静默延长截止时间。
        if (planningPhaseExceeded())
            return blocked("portal_survey_planning_timeout",
                    "Site survey produced no usable portal site within about " + planningPhaseSeconds()
                            + "s (radius " + r.radius + ", loaded chunks only). Travel toward more loaded"
                            + " terrain, widen the search, or retry from another spot instead of waiting.",
                    FailureType.PLANNING_STALL);
        site = survey.tick(r.mayAlterTerrain);
        if (site != null) { survey.close(); planningPhaseEnd(); return TaskState.RUNNING; }
        if (!survey.complete()) { r.extendDeadlineTo(r.getDeadlineGameTime() + 1); return TaskState.RUNNING; }
        if (!end) return blocked(r.mayAlterTerrain ? "portal_site_unavailable" : "portal_construction_permission_required",
                "No reusable frame or suitable loaded construction site was found within radius " + r.radius
                        + " (loaded chunks only); travel toward more loaded terrain near water or lava, or widen the search, before retrying."
                        + (r.mayAlterTerrain ? "" : " Construction also needs may_alter_terrain=true."));
        if (searchedStronghold) return blocked("end_portal_frame_incomplete", "No intact, correctly oriented End portal ring was observed after reaching the stronghold.");
        if (!r.policy.allowRareConsumables()) return blocked("rare_consumable_permission_required", "Finding a stronghold may consume Eyes of Ender.");
        var eyes = PortalPreparationSupplies.eyes(4);
        if (!eyes.satisfied(player)) return supply(eyes);
        searchedStronghold = true;
        return start(new PhysicalStructureSearchTaskRecord(callId(), deadline(), "minecraft:stronghold",
                r.policy.maxStructureDistance(), r.mayAlterTerrain, true, true), Phase.LOCATE);
    }

    private TaskState supply(PortalPreparationSupplies.Need need) {
        supplyNeed = need; supplyOrigin = player.blockPosition().immutable();
        return start(need.acquire(callId(), deadline(), r.policy, r.mayAlterTerrain), Phase.SUPPLY);
    }

    private TaskState activate() {
        if (activationTarget == null) {
            activationTarget = end ? site.end().missingEyes(p -> PortalPreparationSite.read(world, p)).stream()
                    .min(Comparator.comparingDouble(p -> p.distSqr(player.blockPosition()))).orElseThrow()
                    : site.nether().origin().below();
            activationAim = Vec3.atLowerCornerOf(activationTarget).add(.5, end ? .8125 : 1, .5);
            activationStance = PortalApproach.find(player, site, activationTarget, activationAim, Set.of());
            if (activationStance == null) return blocked("portal_activation_unreachable", "No safe exterior stance can see the required portal face.");
        }
        if (!player.blockPosition().equals(activationStance))
            return start(MoveToTaskRecord.strictStance(callId(), deadline(), activationStance, r.mayAlterTerrain), Phase.MOVE);
        phase = Phase.ACTIVATE;
        BlockPos target = activationTarget;
        var item = end ? Items.ENDER_EYE : PlayerInv.count(player.getInventory(), Items.FLINT_AND_STEEL) > 0
                ? Items.FLINT_AND_STEEL : Items.FIRE_CHARGE;
        activation = new PortalActivation(item, target, activationAim, Direction.UP,
                () -> player.blockPosition().equals(activationStance) && site.ready(world)
                        && (!end || !world.getBlockState(target).getValue(EndPortalFrameBlock.HAS_EYE)),
                () -> end ? site.end().intact(p -> PortalPreparationSite.read(world, p))
                        && world.getBlockState(target).getValue(EndPortalFrameBlock.HAS_EYE) : site.active(world));
        return TaskState.RUNNING;
    }

    private TaskState start(TaskRecord record, Phase next) {
        phase = next; childRecord = record;
        child = guarded(() -> childFactory.apply(player, record));
        r.extendDeadlineTo(record.getDeadlineGameTime());
        return TaskState.RUNNING;
    }
    private long deadline() { return Math.max(r.getDeadlineGameTime(), world.getGameTime() + 6000); }
    private String callId() { return r.getToolCallId() + "-portal-" + (++serial); }
    private <T> T guarded(Supplier<T> operation) {
        if (site == null) return operation.get();
        return NavigationSafetyContext.withForbiddenBodyCells(site.forbiddenBody(), () -> phase == Phase.BUILD
                ? operation.get() : NavigationSafetyContext.withPreservedStructures(site.footprint(), operation));
    }

    private TaskState tickChild() {
        TaskState terminal;
        if (world.getGameTime() >= childRecord.getDeadlineGameTime()) {
            guarded(() -> { child.stop(player, Task.StopReason.REPLACED); return null; }); terminal = TaskState.TIMEOUT;
        } else terminal = guarded(() -> runChild(child));
        r.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (terminal == null) return TaskState.RUNNING;
        TaskState settled = terminal;
        TaskResult result = guarded(() -> child.result(settled));
        child = null; childRecord = null;
        if (result == null) return blocked("portal_child_unconfirmed", "The preparation child ended without a result.");
        if (phase == Phase.CAST) {
            // 保留每次原生动作和整扇门差异；成型不符只结束本次施工，不冒称建门成功，也不重新施工。
            childEvidence = result.data() == null ? Map.of() : Map.copyOf(result.data());
            if (terminal != TaskState.SUCCESS || !result.success())
                return blocked(childEvidence.getOrDefault("issue_code", "portal_casting_failed").toString(), result.message());
            site = new PortalPreparationSite(casting.layout().frame(), null);
            if (!site.ready(world)) { issue = "portal_casting_outcome_differs"; return TaskState.SUCCESS; }
            phase = Phase.VERIFY; return TaskState.RUNNING;
        }
        if (terminal != TaskState.SUCCESS || !result.success()) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            // 逐格/缺料证据一并透传，否则细节在包装层丢失、调用方只能看到一句合成失败码：
            // 供应类失败看 blocked_need/planning_handoff（差什么物品与相近替代），
            // 施工类失败看 blocked_cells/clearance_report（哪些格被哪条规则拒绝）。
            for (String key : List.of("issue_code", "allowed_dimensions", "recovery_options", "failure_type",
                    "requires_narration", "target_item_family", "blocked_cells", "clearance_report",
                    "failure_code", "blocked_need", "planning_handoff", "pending_supply",
                    "resource_preparation"))
                if (result.data() != null && result.data().containsKey(key)) evidence.put(key, result.data().get(key));
            childEvidence = Map.copyOf(evidence);
            String fallback = "requires_dimension".equals(evidence.get("failure_type")) ? "requires_dimension"
                    : "portal_" + phase.name().toLowerCase(Locale.ROOT) + "_failed";
            return blocked(evidence.getOrDefault("issue_code", fallback).toString(), result.message());
        }
        if (phase == Phase.SUPPLY) {
            if (!supplyNeed.satisfied(player)) return blocked("portal_supply_unverified", "Supply ended without the required inventory increase.");
            supplyNeed = null;
            return start(MoveToTaskRecord.strictStance(callId(), deadline(), supplyOrigin, r.mayAlterTerrain), Phase.RETURN);
        }
        if (phase == Phase.LOCATE) {
            survey.close(); survey = new PortalSiteSurvey(world, player.blockPosition(), r.radius, true);
            planningPhaseBegin("survey", surveyPlanningLimitTicks);
        }
        if (phase == Phase.BUILD && !site.ready(world)) return blocked("portal_frame_unverified", "Construction ended without a complete live obsidian frame.");
        return TaskState.RUNNING;
    }

    private TaskState blocked(String code, String message) {
        return blocked(code, message, FailureType.fromCode(childEvidence.get("failure_type"), FailureType.TARGET_LOST));
    }
    private TaskState blocked(String code, String message, FailureType type) {
        issue = code;
        // 失败回执自带"卡在哪"：阶段、站位、扫描范围与该阶段的关键缺口，
        // 模型据此能直接判断下一步，不用再盲查世界状态。
        var facts = new LinkedHashMap<String, Object>();
        facts.put("phase", preparationPhase());
        facts.put("dimension", world.dimension().location().toString());
        facts.put("feet", List.of(player.getX(), player.getY(), player.getZ()));
        if (site != null) {
            facts.put("missing_blocks", site.missingBlocks(world).size());
            if (end) facts.put("missing_eyes",
                    site.end().missingEyes(p -> PortalPreparationSite.read(world, p)).size());
        }
        if (supplyNeed != null) {
            facts.put("supply_purpose", supplyNeed.purpose());
            // 点名候选与需求数：调用方据此预判带什么材料才够，不再靠试错补料；候选之外（如可燃原木）即使身上有也不计入。
            facts.put("supply_required", supplyNeed.count());
            facts.put("supply_candidates", supplyNeed.alternatives().stream()
                    .map(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString()).toList());
        }
        facts.put("survey_radius", r.radius);
        if (planningPhaseActive())
            facts.put("planned_for_seconds", planningPhaseSeconds());
        blockedFacts = Map.copyOf(facts);
        fail(message == null ? code : message, type);
        return TaskState.FAILED;
    }
    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>(childEvidence);
        data.put("portal_prepared", complete);
        data.put("preparation_phase", preparationPhase());
        if (casting != null && casting.layout() != null)
            data.put("portal_observation", casting.layout().observation(p -> PortalPreparationSite.read(world, p)));
        if (issue != null) { data.put("issue_code", issue); data.put("requires_decision", true); }
        if (blockedFacts != null) data.put("blocked_facts", blockedFacts);
        return data;
    }
    // CAST 是内部子任务类型；对外显示正在找水、取水或选池，不能从接单起就让模型误以为已经在浇筑。
    private String preparationPhase() {
        return phase == Phase.CAST && casting != null ? casting.stage() : phase.name().toLowerCase(Locale.ROOT);
    }
    @Override public Map<String, Object> progress() {
        var data = new LinkedHashMap<>(resultData());
        if (child == null) {
            // 标准键 phase 让进度门卫认识本任务；勘察期另报 calc（扫描次数）与已规划秒数，
            // 勘察停滞期每过事件地板间隔仍有一条「还在找」的心跳，不再零事件黑洞。
            // 有子任务时不占 phase：一线子任务的规划心跳由 child 键上提保持可见，包装层不顶掉它。
            data.put("phase", preparationPhase());
            if (planningPhaseActive() && "survey".equals(planningPhaseLabel())
                    && survey != null && !survey.complete()) {
                data.put("calc", survey.scans());
                data.put("planning_seconds", planningPhaseSeconds());
            }
        }
        if (child != null) data.put("child", child.progress());
        return data;
    }
    @Override public void stop(LocalPlayer player, Task.StopReason why) {
        try {
            if (child != null) guarded(() -> { child.stop(player, why); return null; });
            if (activation != null) activation.close(player);
            super.stop(player, why);
        } finally { if (why != Task.StopReason.PREEMPTED) cleanup(); }
    }
    @Override protected void cleanup() {
        if (cleaned) return; cleaned = true;
        try {
            if (child != null) guarded(() -> { child.stop(player, Task.StopReason.REPLACED); child.result(TaskState.CANCELLED); return null; });
        } finally {
            child = null; childRecord = null;
            if (activation != null) activation.close(player);
            if (survey != null) survey.close();
            super.cleanup();
        }
    }
    /** 面板行动行的一句话汇报；说法来自准备阶段（{@code Phase}），子任务在跑时由一线先说话。 */
    @Override public String describeCurrentAction() {
        if (child != null) {
            String deeper = child.describeCurrentAction();
            if (deeper != null) return deeper;
        }
        if (activation != null) return end ? "正在放置末影之眼" : "正在点燃传送门";
        return switch (phase) {
            case SURVEY -> "正在勘察传送门场址";
            case SUPPLY -> "正在补齐传送门材料";
            case RETURN -> "正在回到传送门场址";
            case BUILD -> "正在建造传送门门框";
            case CAST -> "正在浇筑下界传送门";
            case LOCATE -> "正在定位要塞";
            case MOVE -> "正在走到激活站位";
            case ACTIVATE -> end ? "正在放置末影之眼" : "正在点燃传送门";
            case VERIFY -> "正在核实传送门已激活";
        };
    }

    @Override protected String successMessage() {
        return complete ? "portal preparation verified from the active portal surface"
                : "Native casting actions completed; the observed frame needs a model decision before further work.";
    }
}
