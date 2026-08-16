// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DirtPathBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Loaded-fact lighting planner with receipt-driven Build and actual light verification. */
public final class SemanticLightAreaCompanionTask
        extends AbstractCompanionTask<SemanticLightAreaTaskRecord> {

    private enum Stage { OBSERVE, PLAN, SUPPLY, BUILD, SETTLE, VERIFY }
    private record Offset(int dx, int dz, int distanceSquared) {}
    private record Sample(BlockPos pos, int light) {}
    private record LightSource(
            Item item, Block block, String id, int emission, int available, int priority) {}
    private record Candidate(
            BlockPos pos, BlockState state, int preferenceScore, Set<Long> covers) {}

    private static final int COLUMNS_PER_TICK = 18;
    private static final int VERIFY_PER_TICK = 256;
    private static final int SETTLE_TICKS = 5;
    private static final int MAX_TARGET_SAMPLES = 24_000;
    private static final int MAX_CANDIDATES = 4_096;
    private static final int MAX_PASS_BATCH = 48;
    private static final int PROTECTED_LABEL_RADIUS = 4;
    private static final List<String> DEFAULT_LIGHT_IDS = List.of(
            "minecraft:torch", "minecraft:lantern", "minecraft:glowstone",
            "minecraft:sea_lantern", "minecraft:shroomlight");

    private Stage stage;
    private List<Offset> offsets = List.of();
    private int columnIndex;
    private int unloadedColumns;
    private int loadedColumns;
    private final Map<Long, Sample> samples = new LinkedHashMap<>();
    private final Map<String, Integer> protectedFacts = new LinkedHashMap<>();
    private final List<BlockPos> protectedAnchors = new ArrayList<>();
    private final Set<BlockPos> protectedNavigationCells = new LinkedHashSet<>();
    private List<Sample> targetCells = List.of();
    private List<Sample> darkCells = List.of();
    private double achievedCoverage;
    private int litCells;
    private int verifyIndex;
    private int verifyLit;
    private final List<Sample> verifyDark = new ArrayList<>();

    private LightSource source;
    private final Set<Long> attemptedPositions = new HashSet<>();
    private int passes;
    private int requestedPlacements;
    private int settledAt;
    private BuildCompanionTask buildChild;
    private TaskResult lastBuildResult;
    private final List<Map<String, Object>> childReceipts = new ArrayList<>();
    private final SemanticMaterialSupplyCoordinator supply =
            new SemanticMaterialSupplyCoordinator();
    private final List<Map<String, Object>> supplyReceipts = new ArrayList<>();
    private int supplyRounds;
    private String pendingSupplySource;
    private String pinnedSuppliedSource;
    private Map<String, Object> supplyFailure = Map.of();
    private String failureCode;
    private boolean requiresDecision;
    private List<String> recoveryOptions = List.of();

    public SemanticLightAreaCompanionTask(
            LocalPlayer player, SemanticLightAreaTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() {
        if (r.style == SemanticLightAreaTaskRecord.Style.WALL
                || r.style == SemanticLightAreaTaskRecord.Style.HANGING) {
            giveUp("unsupported_lighting_style",
                    "style=" + r.style.name().toLowerCase(java.util.Locale.ROOT)
                            + " has no receipt-verifiable placement planner; it was not silently treated as ground lighting",
                    FailureType.UNSUPPORTED,
                    List.of("retry with style=auto or style=ground",
                            "express a separate semantic fixture prerequisite, then retry",
                            "cancel without changing the area"));
            return;
        }
        for (String label : r.protectedLabels) {
            IntentRuntime.Landmark landmark = IntentRuntime.get().landmark(label);
            if (landmark == null) {
                giveUp("unresolved_protected_label",
                        "protected label '" + label + "' is not remembered; lighting paused before placement",
                        FailureType.TARGET_LOST,
                        List.of("remember or resolve the protected area first",
                                "retry with an unambiguous protected_labels set",
                                "cancel without changing the area"));
                return;
            }
            String dimension = landmark.position().dimension();
            if (dimension != null && !dimension.isBlank()
                    && !dimension.equals(player.level().dimension().location().toString())) {
                giveUp("protected_label_other_dimension",
                        "protected label '" + label + "' belongs to another dimension",
                        FailureType.TARGET_LOST,
                        List.of("travel to the protected area's dimension first",
                                "choose the correct same-dimension label"));
                return;
            }
            protectedAnchors.add(new BlockPos(
                    landmark.position().x(), landmark.position().y(), landmark.position().z()));
        }
        offsets = makeOffsets(r.radius);
        stage = Stage.OBSERVE;
    }

    @Override protected TaskState onTick() {
        return switch (stage) {
            case OBSERVE -> tickObserve();
            case PLAN -> tickPlan();
            case SUPPLY -> tickSupply();
            case BUILD -> tickBuild();
            case SETTLE -> tickSettle();
            case VERIFY -> tickVerify();
        };
    }

    private TaskState tickObserve() {
        ClientLevel level = ClientRuntime.requireContext(player).level();
        int budget = COLUMNS_PER_TICK;
        while (budget-- > 0 && columnIndex < offsets.size()) {
            Offset offset = offsets.get(columnIndex++);
            int x = r.center.getX() + offset.dx();
            int z = r.center.getZ() + offset.dz();
            if (!columnLoaded(level, x, z)) {
                unloadedColumns++;
                continue;
            }
            loadedColumns++;
            observeColumn(level, x, z);
        }
        if (columnIndex < offsets.size()) return TaskState.RUNNING;

        targetCells = new ArrayList<>(samples.values());
        if (r.resolveLoadedComponent && !targetCells.isEmpty()) {
            targetCells = nearestConnectedComponent(targetCells);
        }
        if (targetCells.isEmpty()) {
            giveUp(unloadedColumns > 0 ? "area_not_loaded" : "no_matching_area_cells",
                    unloadedColumns > 0
                            ? "the requested semantic area cannot be resolved from currently loaded cells"
                            : "loaded observations contain no cells matching the requested coverage semantics",
                    FailureType.TARGET_LOST,
                    List.of("travel to or load the target area, then retry",
                            "choose a different coverage policy or area anchor",
                            "cancel without changing the world"));
            return TaskState.FAILED;
        }
        evaluateCurrentLight(level);
        if (meetsRequirement()) return TaskState.SUCCESS;
        stage = Stage.PLAN;
        return TaskState.RUNNING;
    }

    private TaskState continueSurveyToward(
            ClientLevel level,
            BlockPos destination,
            SurveyMovePurpose purpose,
            long frontierKey) {
        BlockPos approach = level.isLoaded(destination)
                ? safeSurveyApproach(level, destination) : null;
        if (approach == null) approach = loadedTravelCellToward(level, destination);
        if (approach == null) {
            if (purpose == SurveyMovePurpose.LOAD_COMPONENT_FRONTIER
                    && frontierKey != Long.MIN_VALUE) {
                rejectedComponentFrontiers.add(frontierKey);
                return TaskState.RUNNING;
            }
            giveUp("semantic_area_seed_unreachable",
                    "no new loaded, safe first-person stance leads toward the semantic landmark seed",
                    FailureType.NO_PATH,
                    List.of("clear or authorize the semantic route obstacle, then resume",
                            "move closer to the remembered area and retry",
                            "cancel without changing the world"));
            return TaskState.FAILED;
        }
        startSurveyMove(approach, purpose, frontierKey);
        return TaskState.RUNNING;
    }

    private BlockPos loadedTravelCellToward(ClientLevel level, BlockPos destination) {
        BlockPos current = player.blockPosition();
        double dx = destination.getX() - current.getX();
        double dz = destination.getZ() - current.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0D) return null;
        double farthest = Math.min(64.0D, distance);
        for (double leg = farthest; leg >= Math.min(4.0D, farthest); leg -= 4.0D) {
            int x = (int) Math.round(current.getX() + dx / distance * leg);
            int z = (int) Math.round(current.getZ() + dz / distance * leg);
            BlockPos candidate = safeSurveyApproach(level,
                    new BlockPos(x, current.getY(), z));
            if (candidate != null) return candidate;
        }
        return null;
    }

    private BlockPos safeSurveyApproach(ClientLevel level, BlockPos around) {
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                int x = around.getX() + dx;
                int z = around.getZ() + dz;
                if (!columnLoaded(level, x, z)) continue;
                int surface = Math.clamp(ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z),
                        level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2);
                for (int d = 0; d <= 8; d++) {
                    int[] ys = d == 0
                            ? new int[]{surface, around.getY()}
                            : new int[]{surface + d, surface - d, around.getY() + d,
                                    around.getY() - d};
                    for (int y : ys) {
                        if (y <= level.getMinBuildHeight()
                                || y >= level.getMaxBuildHeight() - 1) continue;
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (protectedNavigationCells.contains(candidate)
                                || attemptedSurveyMoves.contains(candidate.asLong())) continue;
                        if (isWalkable(level, candidate)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    private void startSurveyMove(
            BlockPos target, SurveyMovePurpose purpose, long frontierKey) {
        String parent = r.getToolCallId() == null ? "light-area" : r.getToolCallId();
        long now = player.level().getGameTime();
        surveyMoveRecord = new MoveToTaskRecord(
                parent + "-internal-boundary-survey-" + (++surveyMoveSerial),
                now + 90L * 20L,
                (double) target.getX(), (double) target.getY(), (double) target.getZ(),
                null, false);
        surveyMoveChild = new MoveToCompanionTask(player, surveyMoveRecord);
        surveyMovePurpose = purpose;
        activeComponentFrontier = frontierKey;
        attemptedSurveyMoves.add(target.asLong());
        surveyLegs++;
        r.extendDeadlineTo(surveyMoveRecord.getDeadlineGameTime());
        stage = Stage.TRAVEL_SURVEY;
    }

    private TaskState tickSurveyMove() {
        TaskState terminal;
        if (player.level().getGameTime() >= surveyMoveRecord.getDeadlineGameTime()) {
            surveyMoveChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = NavigationSafetyContext.withForbiddenBodyCells(
                    protectedNavigationCells, () -> runChild(surveyMoveChild));
        }
        r.extendDeadlineTo(surveyMoveRecord.getDeadlineGameTime());
        if (terminal == null) return TaskState.RUNNING;
        surveyMoveChild.result(terminal);
        if (terminal != TaskState.SUCCESS) {
            surveyLegFailures++;
        } else {
            renewLightingProgress();
        }
        surveyMoveChild = null;
        surveyMoveRecord = null;
        surveyMovePurpose = null;
        activeComponentFrontier = Long.MIN_VALUE;
        stage = Stage.OBSERVE;
        return TaskState.RUNNING;
    }

    private boolean insideRequestedBoundary(BlockPos pos) {
        if (!r.hasExplicitRadius()) return true;
        long dx = (long) pos.getX() - r.center.getX();
        long dz = (long) pos.getZ() - r.center.getZ();
        long radius = r.radius;
        return dx * dx + dz * dz <= radius * radius;
    }

    private void observeColumn(ClientLevel level, int x, int z) {
        int surface = Math.clamp(ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z),
                level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2);
        int minY = Math.max(level.getMinBuildHeight() + 1, r.center.getY() - 12);
        int maxY = Math.min(level.getMaxBuildHeight() - 2, r.center.getY() + 12);
        Set<Integer> ys = new LinkedHashSet<>();
        for (int y = minY; y <= maxY; y++) ys.add(y);
        for (int y = surface - 3; y <= surface + 2; y++) {
            if (y > level.getMinBuildHeight() && y < level.getMaxBuildHeight() - 1) ys.add(y);
        }
        for (int y : ys) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            String sensitive = sensitiveReason(level, pos, state);
            if (sensitive != null) protectedFacts.merge(sensitive, 1, Integer::sum);
            if (isGrowthCell(state)) {
                // A build approach with terrain permission must not clear or enter a crop cell.
                protectedNavigationCells.add(pos.immutable());
            } else if (state.getBlock() instanceof FarmBlock) {
                // Deny the body cell above every observed farmland block, including unplanted
                // cells that remain valid adjacent placement targets. Construction can click a
                // target without ever using it as a walking or jumping landing cell.
                protectedNavigationCells.add(pos.above().immutable());
            }
            if (r.coverage == SemanticLightAreaTaskRecord.Coverage.CROP_GROWTH) {
                if (isGrowthCell(state)) addSample(level, pos);
                else if (state.getBlock() instanceof FarmBlock) addSample(level, pos.above());
            } else if (isWalkable(level, pos)) {
                addSample(level, pos);
            }
        }
    }

    private void addSample(ClientLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        samples.putIfAbsent(pos.asLong(), new Sample(pos.immutable(),
                level.getBrightness(LightLayer.BLOCK, pos)));
    }

    private TaskState tickPlan() {
        ClientLevel level = ClientRuntime.requireContext(player).level();
        if (r.hasPlacementBudget() && requestedPlacements >= r.maxPlacements) {
            return placementBudgetReached();
        }
        source = chooseSource();
        if (source == null) {
            giveUp("no_usable_light_source",
                    "no permitted preferred or standard light-emitting BlockItem can meet minimum_light=" + r.minimumLight,
                    FailureType.NO_MATERIAL,
                    List.of("acquire any suitable light-emitting block item",
                            "retry with a carried block_id/light_preferences value",
                            "lower minimum_light only if that outcome is acceptable"));
            return TaskState.FAILED;
        }
        List<Candidate> candidates = candidates(level, source);
        if (candidates.isEmpty()) {
            giveUp("no_safe_placement_candidates",
                    "loaded facts provide no safe supported placement outside protected footprints",
                    FailureType.NO_SUPPORT,
                    List.of("choose another style or a nearby area anchor",
                            "travel so more of the area is loaded",
                            "review protected_labels; no protected block was modified"));
            return TaskState.FAILED;
        }
        String candidateFingerprint = candidateFingerprint(source, candidates);
        if (candidateFingerprint.equals(lastUnproductiveCandidateFingerprint)) {
            return exhausted("a complete observe/build/verify round produced no additional lit "
                    + "cells and the same safe candidate frontier was observed again");
        }

        int stillNeeded = Math.max(1,
                (int) Math.ceil(targetCells.size() * r.coverage.requiredRatio()) - litCells);
        int cap = MAX_PASS_BATCH;
        if (r.hasPlacementBudget()) {
            cap = Math.min(cap, r.maxPlacements - requestedPlacements);
        }
        if (cap <= 0) return placementBudgetReached();
        List<Candidate> selected = greedy(candidates, stillNeeded, cap);
        if (selected.isEmpty()) return exhausted("the safe candidates cannot improve measured dark cells");

        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (Candidate candidate : selected) {
            targets.add(new BuildTaskRecord.Target(
                    candidate.state(), source.item(), candidate.pos(), source.id(),
                    null, null, null));
        }
        boolean consume = !WorkProfile.of(player).freeMaterials();
        int requiredMaterials = targets.stream()
                .mapToInt(BuildTaskRecord.Target::materialCount).sum();
        int availableMaterials = PlayerInv.buildableCount(player.getInventory(), source.item());
        if (consume && availableMaterials < requiredMaterials) {
            ResourceLocation sourceId = BuiltInRegistries.ITEM.getKey(source.item());
            pendingSupplySource = sourceId.toString();
            supply.begin(player, r.getToolCallId(), r.getDeadlineGameTime(),
                    new SemanticMaterialSupplyCoordinator.Demand(
                            List.of(sourceId), requiredMaterials, "investigated lighting layout"),
                    r.materialPolicy, r.allowedSources, r.allowHarm, r.protectedLabels,
                    protectedNavigationCells);
            r.extendDeadlineTo(supply.childDeadline());
            supplyRounds++;
            stage = Stage.SUPPLY;
            return TaskState.RUNNING;
        }
        startBuild(targets, consume, candidateFingerprint);
        return TaskState.RUNNING;
    }

    private TaskState tickSupply() {
        SemanticMaterialSupplyCoordinator.Tick tick = supply.tick(player, this::runChild);
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.RUNNING) {
            r.extendDeadlineTo(supply.childDeadline());
            return TaskState.RUNNING;
        }
        supplyReceipts.add(tick.receipt());
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.FAILED) {
            supplyFailure = tick.receipt();
            failureCode = stringValue(tick.receipt().get("failure_code"),
                    "material_supply_failed");
            requiresDecision = true;
            recoveryOptions = recoveryIds(tick.receipt().get("recovery_options"));
            if (recoveryOptions.isEmpty()) recoveryOptions = List.of(
                    "change material source policy", "provide a different light preference",
                    "stop without placing the unsupplied layout");
            pendingSupplySource = null;
            fail("lighting material supply stopped before construction: " + tick.message(),
                    tick.failureType());
            return TaskState.FAILED;
        }
        pinnedSuppliedSource = pendingSupplySource;
        pendingSupplySource = null;
        resetObservationForReplan();
        return TaskState.RUNNING;
    }

    private void startBuild(
            List<BuildTaskRecord.Target> targets,
            boolean consume,
            String candidateFingerprint) {
        for (BuildTaskRecord.Target target : targets) {
            attemptedPositions.add(target.pos().asLong());
        }
        String parent = r.getToolCallId() == null ? "light_area" : r.getToolCallId();
        long now = player.level().getGameTime();
        buildRecord = new BuildTaskRecord(
                parent + "-lighting-pass-" + (passes + 1),
                now + BuildTool.timeoutTicksFor(targets.size(), consume),
                targets, false, consume, false);
        buildChild = new BuildCompanionTask(player, buildRecord);
        buildChild.protectNavigationCells(protectedNavigationCells);
        observedBuildProgress = 0;
        passBaselineLitCells = litCells;
        activeCandidateFingerprint = candidateFingerprint;
        requestedPlacements += targets.size();
        passes++;
        r.extendDeadlineTo(buildRecord.getDeadlineGameTime());
        stage = Stage.BUILD;
    }

    private TaskState tickBuild() {
        TaskState terminal = runChild(buildChild);
        propagateBuildProgress();
        if (terminal == null) return TaskState.RUNNING;
        lastBuildResult = buildChild.result(terminal);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("pass", passes);
        receipt.put("success", lastBuildResult.success());
        receipt.put("message", lastBuildResult.message());
        if (lastBuildResult.data() != null) {
            copyReceiptField(lastBuildResult.data(), receipt, "failure_type", "failure_code",
                    "placed", "remaining", "outcome_uncertain");
        }
        childReceipts.add(receipt);
        buildChild = null;
        buildRecord = null;
        settledAt = SETTLE_TICKS;
        stage = Stage.SETTLE;
        return TaskState.RUNNING;
    }

    private TaskState tickSettle() {
        if (settledAt-- > 0) return TaskState.RUNNING;
        verifyIndex = 0;
        verifyLit = 0;
        verifyDark.clear();
        stage = Stage.VERIFY;
        return TaskState.RUNNING;
    }

    private TaskState tickVerify() {
        ClientLevel level = ClientRuntime.requireContext(player).level();
        int budget = VERIFY_PER_TICK;
        while (budget-- > 0 && verifyIndex < targetCells.size()) {
            Sample original = targetCells.get(verifyIndex++);
            if (!level.isLoaded(original.pos())) {
                giveUp("verification_area_unloaded",
                        "part of the observed area unloaded before actual block-light verification",
                        FailureType.TARGET_LOST,
                        List.of("return to the area and retry", "use a smaller radius"));
                return TaskState.FAILED;
            }
            int light = level.getBrightness(LightLayer.BLOCK, original.pos());
            if (light >= r.minimumLight) verifyLit++;
            else verifyDark.add(new Sample(original.pos(), light));
        }
        if (verifyIndex < targetCells.size()) return TaskState.RUNNING;
        litCells = verifyLit;
        darkCells = List.copyOf(verifyDark);
        achievedCoverage = targetCells.isEmpty() ? 0.0D
                : (double) litCells / (double) targetCells.size();
        if (litCells > passBaselineLitCells) {
            lastUnproductiveCandidateFingerprint = null;
            renewLightingProgress();
        } else {
            lastUnproductiveCandidateFingerprint = activeCandidateFingerprint;
        }
        activeCandidateFingerprint = null;
        if (meetsRequirement()) return TaskState.SUCCESS;
        stage = Stage.PLAN;
        return TaskState.RUNNING;
    }

    private void propagateBuildProgress() {
        if (buildRecord == null) return;
        r.extendDeadlineTo(buildRecord.getDeadlineGameTime());
        int current = buildRecord.completed() + buildRecord.placed() + buildRecord.broken();
        if (current > observedBuildProgress) {
            observedBuildProgress = current;
            renewLightingProgress();
        }
    }

    private void renewLightingProgress() {
        r.extendDeadlineTo(player.level().getGameTime() + LIGHTING_PROGRESS_LEASE_TICKS);
    }

    private void evaluateCurrentLight(ClientLevel level) {
        List<Sample> dark = new ArrayList<>();
        int lit = 0;
        for (Sample sample : targetCells) {
            int light = level.getBrightness(LightLayer.BLOCK, sample.pos());
            if (light >= r.minimumLight) lit++;
            else dark.add(new Sample(sample.pos(), light));
        }
        litCells = lit;
        darkCells = List.copyOf(dark);
        achievedCoverage = (double) lit / (double) targetCells.size();
    }

    private boolean meetsRequirement() {
        return areaBoundaryVerified
                && achievedCoverage + 1.0E-9D >= r.coverage.requiredRatio();
    }

    private LightSource chooseSource() {
        boolean free = WorkProfile.of(player).freeMaterials();
        Map<Item, Integer> carried = new HashMap<>();
        for (int slot = 0; slot < Math.min(
                PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size()); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) carried.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        List<Item> ordered = new ArrayList<>();
        addItem(pinnedSuppliedSource, ordered);
        for (String raw : r.lightPreferences) {
            addItem(raw, ordered);
        }
        carried.keySet().stream()
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .forEach(ordered::add);
        for (String fallback : DEFAULT_LIGHT_IDS) addItem(fallback, ordered);
        if (free) {
            for (Item item : BuiltInRegistries.ITEM) if (item instanceof BlockItem) ordered.add(item);
        }
        LightSource best = null;
        Set<Item> seen = new HashSet<>();
        for (Item item : ordered) {
            if (!seen.add(item) || !(item instanceof BlockItem blockItem)) continue;
            int available = free ? Integer.MAX_VALUE : carried.getOrDefault(item, 0);
            Block block = blockItem.getBlock();
            int emission = block.getStateDefinition().getPossibleStates().stream()
                    .mapToInt(BlockState::getLightEmission).max().orElse(0);
            if (emission < r.minimumLight) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            int priority = sourcePriority(id, available > 0);
            LightSource candidate = new LightSource(
                    item, block, id, emission, available, priority);
            if (best == null || priority < best.priority()
                    || (priority == best.priority()
                            && emission > best.emission())) best = candidate;
        }
        return best;
    }

    private int sourcePriority(String id, boolean carried) {
        if (id.equals(pinnedSuppliedSource)) return -1;
        for (int i = 0; i < r.lightPreferences.size(); i++) {
            if (id.equals(r.lightPreferences.get(i))) return i;
        }
        if (carried) return r.lightPreferences.size();
        int fallback = DEFAULT_LIGHT_IDS.indexOf(id);
        if (fallback >= 0) return r.lightPreferences.size() + 1 + fallback;
        return r.lightPreferences.size() + DEFAULT_LIGHT_IDS.size() + 2;
    }

    private static void addItem(String raw, List<Item> output) {
        if (raw == null || raw.isBlank()) return;
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) return;
        BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> {
            if (item instanceof BlockItem) output.add(item);
        });
        BuiltInRegistries.BLOCK.getOptional(id).ifPresent(block -> {
            Item item = block.asItem();
            if (item instanceof BlockItem) output.add(item);
        });
    }

    private void resetObservationForReplan() {
        explicitScanDx = r.hasExplicitRadius() ? -r.radius : 0;
        explicitScanDz = r.hasExplicitRadius() ? -r.radius : 0;
        explicitScanComplete = false;
        unloadedColumns = 0;
        loadedColumns = 0;
        samples.clear();
        protectedFacts.clear();
        protectedNavigationCells.clear();
        seedColumns.clear();
        queuedSeedColumns.clear();
        componentCells.clear();
        queuedComponentCells.clear();
        observedComponentCells.clear();
        componentFrontiers.clear();
        componentFrontierOrder.clear();
        rejectedComponentFrontiers.clear();
        placementFootprintColumns.clear();
        attemptedSurveyMoves.clear();
        seedSearchInitialized = false;
        componentSeeded = false;
        areaBoundaryVerified = false;
        seedColumnsObserved = 0;
        componentFrontiersObserved = 0;
        componentFrontiersLoaded = 0;
        surveyLegs = 0;
        surveyLegFailures = 0;
        targetCells = List.of();
        darkCells = List.of();
        achievedCoverage = 0.0D;
        litCells = 0;
        verifyIndex = 0;
        verifyLit = 0;
        verifyDark.clear();
        source = null;
        stage = Stage.OBSERVE;
    }

    private List<Candidate> candidates(ClientLevel level, LightSource light) {
        Map<Long, Candidate> byPos = new LinkedHashMap<>();
        int[] distances = {1, 2, 4, 6};
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (Sample dark : darkCells) {
            if (r.coverage == SemanticLightAreaTaskRecord.Coverage.CROP_GROWTH) {
                Candidate exact = groundCandidate(level, light, dark.pos());
                if (exact != null) byPos.putIfAbsent(exact.pos().asLong(), exact);
            }
            for (int distance : distances) {
                for (int[] direction : directions) {
                    int x = dark.pos().getX() + direction[0] * distance;
                    int z = dark.pos().getZ() + direction[1] * distance;
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos pos = new BlockPos(x, dark.pos().getY() + dy, z);
                        Candidate candidate = groundCandidate(level, light, pos);
                        if (candidate != null) byPos.putIfAbsent(pos.asLong(), candidate);
                        if (byPos.size() >= MAX_CANDIDATES) break;
                    }
                    if (byPos.size() >= MAX_CANDIDATES) break;
                }
                if (byPos.size() >= MAX_CANDIDATES) break;
            }
            if (byPos.size() >= MAX_CANDIDATES) break;
        }
        List<Candidate> result = new ArrayList<>();
        for (Candidate candidate : byPos.values()) {
            Set<Long> covers = new HashSet<>();
            for (Sample dark : darkCells) {
                if (candidate.state().getLightEmission()
                        - candidate.pos().distManhattan(dark.pos()) >= r.minimumLight) {
                    covers.add(dark.pos().asLong());
                }
            }
            if (!covers.isEmpty()) result.add(new Candidate(candidate.pos(), candidate.state(),
                    candidate.preferenceScore(), Set.copyOf(covers)));
        }
        return result;
    }

    private static String candidateFingerprint(
            LightSource light, List<Candidate> candidates) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < light.id().length(); i++) {
            hash ^= light.id().charAt(i);
            hash *= 0x100000001b3L;
        }
        List<Long> positions = candidates.stream()
                .map(candidate -> candidate.pos().asLong())
                .sorted()
                .toList();
        for (long position : positions) {
            hash ^= position;
            hash *= 0x100000001b3L;
        }
        return light.id() + ':' + positions.size() + ':' + Long.toUnsignedString(hash, 16);
    }

    private Candidate groundCandidate(ClientLevel level, LightSource light, BlockPos pos) {
        if (!inside(pos) || attemptedPositions.contains(pos.asLong())
                || !level.isLoaded(pos) || !level.isLoaded(pos.below())) return null;
        BlockState current = level.getBlockState(pos);
        BlockState support = level.getBlockState(pos.below());
        if (!current.canBeReplaced() || !current.getFluidState().isEmpty()) return null;
        String currentSensitive = sensitiveReason(level, pos, current);
        String supportSensitive = sensitiveReason(level, pos.below(), support);
        boolean unplantedFarmland = r.coverage == SemanticLightAreaTaskRecord.Coverage.CROP_GROWTH
                && (r.style == SemanticLightAreaTaskRecord.Style.AUTO
                        || r.style == SemanticLightAreaTaskRecord.Style.GROUND)
                && current.isAir() && support.getBlock() instanceof FarmBlock;
        if (currentSensitive != null
                || (supportSensitive != null
                        && !(unplantedFarmland && "cultivated_land".equals(supportSensitive)))
                || protectedByLabel(pos)
                || (!unplantedFarmland && narrowRoute(level, pos))) return null;
        if (!unplantedFarmland && !support.isFaceSturdy(level, pos.below(), Direction.UP)) return null;
        BlockState chosen = null;
        int emission = -1;
        for (BlockState state : light.block().getStateDefinition().getPossibleStates()) {
            if (state.hasProperty(BlockStateProperties.HANGING)
                    && state.getValue(BlockStateProperties.HANGING)) continue;
            if (state.getLightEmission() < r.minimumLight || !state.canSurvive(level, pos)) continue;
            if (state.getLightEmission() > emission) {
                chosen = state;
                emission = state.getLightEmission();
            }
        }
        int preferenceScore = placementPreferenceScore(pos, unplantedFarmland);
        return chosen == null ? null : new Candidate(
                pos.immutable(), chosen, preferenceScore, Set.of());
    }

    private int placementPreferenceScore(BlockPos pos, boolean unplantedFarmland) {
        int distanceFromCenter = horizontalDistanceSquared(pos, r.center);
        return switch (r.placementPreference) {
            case CENTRAL_UNPLANTED ->
                    (unplantedFarmland ? 1_000_000 : 0) - distanceFromCenter;
            case UNOBTRUSIVE -> distanceFromCenter;
            case COVERAGE_OPTIMAL ->
                    r.style == SemanticLightAreaTaskRecord.Style.UNOBTRUSIVE
                            ? distanceFromCenter : 0;
        };
    }

    private List<Candidate> greedy(List<Candidate> candidates, int needed, int cap) {
        List<Candidate> selected = new ArrayList<>();
        Set<Long> covered = new HashSet<>();
        while (selected.size() < cap && covered.size() < needed) {
            Candidate best = null;
            int bestGain = 0;
            for (Candidate candidate : candidates) {
                if (selected.contains(candidate)) continue;
                int gain = 0;
                for (long cell : candidate.covers()) if (!covered.contains(cell)) gain++;
                // Measured coverage gain is always primary. Placement preference only
                // chooses between candidates with the same positive gain.
                if (gain > bestGain || gain == bestGain && gain > 0
                        && (best == null
                                || candidate.preferenceScore() > best.preferenceScore())) {
                    best = candidate;
                    bestGain = gain;
                }
            }
            if (best == null) break;
            selected.add(best);
            covered.addAll(best.covers());
        }
        return selected;
    }

    private boolean isGrowthCell(BlockState state) {
        return state.is(BlockTags.CROPS) || state.getBlock() instanceof CropBlock;
    }

    private boolean isWalkable(ClientLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.above()) || !level.isLoaded(feet.below())) return false;
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        BlockState support = level.getBlockState(feet.below());
        return feetState.getCollisionShape(level, feet).isEmpty()
                && headState.getCollisionShape(level, feet.above()).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && !support.getCollisionShape(level, feet.below()).isEmpty()
                && !support.getFluidState().is(FluidTags.LAVA);
    }

    private boolean narrowRoute(ClientLevel level, BlockPos cell) {
        if (!isWalkable(level, cell)) return false;
        int exits = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isWalkable(level, cell.relative(direction))) exits++;
        }
        return exits <= 2;
    }

    private String sensitiveReason(ClientLevel level, BlockPos pos, BlockState state) {
        if (protectedByLabel(pos)) return "protected_label";
        if (state.hasBlockEntity()) return "block_entity";
        if (!state.getFluidState().isEmpty()) return "fluid";
        if (isGrowthCell(state) || state.getBlock() instanceof FarmBlock) return "cultivated_land";
        if (state.getBlock() instanceof DirtPathBlock) return "route";
        if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)
                || state.is(BlockTags.PORTALS) || state.getFluidState().is(FluidTags.LAVA)) return "hazard";
        if (state.isSignalSource()) return "mechanical_or_signal";
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id != null && !"minecraft".equals(id.getNamespace()) && !state.isAir()) {
            return "modded_or_mechanical";
        }
        return null;
    }

    private boolean protectedByLabel(BlockPos pos) {
        for (BlockPos anchor : protectedAnchors) {
            if (Math.abs(anchor.getX() - pos.getX()) <= PROTECTED_LABEL_RADIUS
                    && Math.abs(anchor.getY() - pos.getY()) <= PROTECTED_LABEL_RADIUS
                    && Math.abs(anchor.getZ() - pos.getZ()) <= PROTECTED_LABEL_RADIUS) return true;
        }
        return false;
    }

    private boolean inside(BlockPos pos) {
        if (r.hasExplicitRadius()) return insideRequestedBoundary(pos);
        return placementFootprintColumns.contains(
                BlockPos.asLong(pos.getX(), 0, pos.getZ()));
    }

    private static int horizontalDistanceSquared(BlockPos left, BlockPos right) {
        int dx = left.getX() - right.getX();
        int dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private boolean columnLoaded(ClientLevel level, int x, int z) {
        int y = Math.clamp(r.center.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        return level.isLoaded(new BlockPos(x, y, z));
    }

    private TaskState exhausted(String reason) {
        giveUp("verified_coverage_not_reached", reason + "; actual achieved_coverage="
                        + String.format(java.util.Locale.ROOT, "%.3f", achievedCoverage),
                FailureType.NO_SUPPORT,
                List.of("supply a different light source or style",
                        "use a smaller area or lower required coverage",
                        "inspect the aggregate dark-area evidence and choose a semantic prerequisite"));
        return TaskState.FAILED;
    }

    private TaskState placementBudgetReached() {
        giveUp("placement_budget_reached",
                "the explicit max_placements=" + r.maxPlacements
                        + " decision boundary was reached at achieved_coverage="
                        + String.format(java.util.Locale.ROOT, "%.3f", achievedCoverage),
                FailureType.INTERRUPTED,
                List.of("authorize a larger max_placements budget",
                        "change the light source, style or required coverage",
                        "stop with the already confirmed lighting changes"));
        return TaskState.FAILED;
    }

    private void giveUp(String code, String message, FailureType type, List<String> options) {
        failureCode = code;
        requiresDecision = true;
        recoveryOptions = List.copyOf(options);
        fail(message, type);
    }

    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verified", meetsRequirement());
        data.put("semantic_target", r.semanticTarget == null ? "loaded_area" : r.semanticTarget);
        data.put("scope", r.resolveLoadedComponent
                ? "live_loaded_connected_component_with_first_person_frontier_loading"
                : "explicit_player_authored_geometric_boundary");
        data.put("area_boundary_verified", areaBoundaryVerified);
        data.put("boundary_source", r.hasExplicitRadius()
                ? "explicit_player_radius" : "observed_connected_component_closure");
        if (r.hasExplicitRadius()) data.put("radius", r.radius);
        data.put("minimum_light", r.minimumLight);
        data.put("coverage", r.coverage.name().toLowerCase(java.util.Locale.ROOT));
        data.put("placement_preference",
                r.placementPreference.name().toLowerCase(java.util.Locale.ROOT));
        data.put("required_coverage", r.coverage.requiredRatio());
        data.put("achieved_coverage", achievedCoverage);
        data.put("target_cells", targetCells.size());
        data.put("lit_cells", litCells);
        data.put("dark_cell_count", darkCells.size());
        data.put("loaded_columns", loadedColumns);
        data.put("unloaded_columns", unloadedColumns);
        data.put("seed_columns_observed", seedColumnsObserved);
        data.put("component_frontiers_observed", componentFrontiersObserved);
        data.put("component_frontiers_loaded", componentFrontiersLoaded);
        data.put("unresolved_component_frontiers", componentFrontiers.size());
        data.put("survey_legs", surveyLegs);
        data.put("survey_leg_failures", surveyLegFailures);
        data.put("passes", passes);
        data.put("requested_placements", requestedPlacements);
        data.put("placement_budget_explicit", r.hasPlacementBudget());
        if (r.hasPlacementBudget()) data.put("max_placements", r.maxPlacements);
        data.put("protected_footprint_facts", Map.copyOf(protectedFacts));
        data.put("build_receipts", List.copyOf(childReceipts));
        data.put("material_policy", r.materialPolicy.id());
        data.put("supply_rounds", supplyRounds);
        data.put("supply_receipts", List.copyOf(supplyReceipts));
        if (!supplyFailure.isEmpty()) data.put("supply_failure", supplyFailure);
        if (source != null) data.put("light_source", source.id());
        if (failureCode != null) {
            data.put("failure_code", failureCode);
            data.put("requires_decision", requiresDecision);
            data.put("recovery_options", recoveryOptions);
        }
        return data;
    }

    @Override protected String successMessage() {
        return "semantic area boundary and actual block light verified across "
                + litCells + "/" + targetCells.size()
                + " semantic area cells (coverage "
                + String.format(java.util.Locale.ROOT, "%.1f%%", achievedCoverage * 100.0D) + ")";
    }

    @Override protected String timeoutMessage() {
        return "semantic lighting timed out before actual block-light verification completed";
    }

    @Override protected String cancelledMessage() {
        return "semantic lighting was interrupted; already confirmed placements remain";
    }

    @Override protected void cleanup() {
        if (surveyMoveChild != null) {
            surveyMoveChild.stop(player, Task.StopReason.REPLACED);
            surveyMoveChild.result(TaskState.CANCELLED);
            surveyMoveChild = null;
        }
        surveyMoveRecord = null;
        if (buildChild != null) {
            buildChild.stop(player, Task.StopReason.REPLACED);
            buildChild.result(TaskState.CANCELLED);
            buildChild = null;
        }
        buildRecord = null;
        if (supply.active()) supply.cancel(player);
        super.cleanup();
    }

    private static void copyReceiptField(
            Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) if (from.containsKey(key)) to.put(key, from.get(key));
    }

    private static String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static List<String> recoveryIds(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && map.get("id") != null) {
                result.add(map.get("id").toString());
            } else if (entry != null) result.add(entry.toString());
        }
        return List.copyOf(result);
    }
}
