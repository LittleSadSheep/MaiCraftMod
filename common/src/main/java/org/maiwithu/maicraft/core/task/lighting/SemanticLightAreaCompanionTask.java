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

    private void observeColumn(ClientLevel level, int x, int z) {
        int surface = Math.clamp(level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z),
                level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2);
        int minY = Math.max(level.getMinBuildHeight() + 1, r.center.getY() - 12);
        int maxY = Math.min(level.getMaxBuildHeight() - 2, r.center.getY() + 12);
        Set<Integer> ys = new LinkedHashSet<>();
        for (int y = minY; y <= maxY; y++) ys.add(y);
        for (int y = surface - 3; y <= surface + 2; y++) {
            if (y > level.getMinBuildHeight() && y < level.getMaxBuildHeight() - 1) ys.add(y);
        }
        for (int y : ys) {
            if (samples.size() >= MAX_TARGET_SAMPLES) return;
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
        if (passes >= r.maxPasses || requestedPlacements >= r.maxPlacements) {
            return exhausted("bounded lighting passes or placement budget were exhausted");
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

        int stillNeeded = Math.max(1,
                (int) Math.ceil(targetCells.size() * r.coverage.requiredRatio()) - litCells);
        int cap = Math.min(MAX_PASS_BATCH, r.maxPlacements - requestedPlacements);
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
                    r.materialPolicy, r.allowedSources, r.allowHarm, r.protectedLabels);
            r.extendDeadlineTo(supply.childDeadline());
            supplyRounds++;
            stage = Stage.SUPPLY;
            return TaskState.RUNNING;
        }
        startBuild(targets, consume);
        return TaskState.RUNNING;
    }

    private TaskState tickSupply() {
        SemanticMaterialSupplyCoordinator.Tick tick = supply.tick(player, this::runChild);
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.RUNNING) {
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

    private void startBuild(List<BuildTaskRecord.Target> targets, boolean consume) {
        for (BuildTaskRecord.Target target : targets) {
            attemptedPositions.add(target.pos().asLong());
        }
        String parent = r.getToolCallId() == null ? "light_area" : r.getToolCallId();
        long now = player.level().getGameTime();
        BuildTaskRecord build = new BuildTaskRecord(
                parent + "-lighting-pass-" + (passes + 1),
                now + BuildTool.timeoutTicksFor(targets.size(), consume),
                targets, false, consume, false);
        buildChild = new BuildCompanionTask(player, build);
        buildChild.protectNavigationCells(protectedNavigationCells);
        requestedPlacements += targets.size();
        passes++;
        stage = Stage.BUILD;
    }

    private TaskState tickBuild() {
        TaskState terminal = runChild(buildChild);
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
        if (meetsRequirement()) return TaskState.SUCCESS;
        stage = Stage.PLAN;
        return TaskState.RUNNING;
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
        return achievedCoverage + 1.0E-9D >= r.coverage.requiredRatio();
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
        columnIndex = 0;
        unloadedColumns = 0;
        loadedColumns = 0;
        samples.clear();
        protectedFacts.clear();
        protectedNavigationCells.clear();
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

    private List<Sample> nearestConnectedComponent(List<Sample> input) {
        Map<Long, Sample> remaining = new HashMap<>();
        for (Sample sample : input) remaining.put(sample.pos().asLong(), sample);
        List<Sample> best = List.of();
        double bestDistance = Double.POSITIVE_INFINITY;
        while (!remaining.isEmpty()) {
            Sample seed = remaining.values().iterator().next();
            remaining.remove(seed.pos().asLong());
            ArrayDeque<Sample> queue = new ArrayDeque<>();
            List<Sample> component = new ArrayList<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                Sample sample = queue.removeFirst();
                component.add(sample);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        long key = BlockPos.asLong(sample.pos().getX() + dx,
                                sample.pos().getY() + dy, sample.pos().getZ() + dz);
                        Sample next = remaining.remove(key);
                        if (next != null) queue.addLast(next);
                    }
                }
            }
            double distance = component.stream().mapToDouble(sample ->
                    sample.pos().distSqr(r.center)).min().orElse(Double.POSITIVE_INFINITY);
            if (distance < bestDistance || (distance == bestDistance && component.size() > best.size())) {
                best = component;
                bestDistance = distance;
            }
        }
        return List.copyOf(best);
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
        int dx = pos.getX() - r.center.getX();
        int dz = pos.getZ() - r.center.getZ();
        return dx * dx + dz * dz <= r.radius * r.radius;
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

    private static List<Offset> makeOffsets(int radius) {
        List<Offset> result = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int distance = dx * dx + dz * dz;
            if (distance <= radius * radius) result.add(new Offset(dx, dz, distance));
        }
        result.sort(Comparator.comparingInt(Offset::distanceSquared));
        return List.copyOf(result);
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
        data.put("scope", "loaded_client_level_only");
        data.put("radius", r.radius);
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
        data.put("passes", passes);
        data.put("requested_placements", requestedPlacements);
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
        return "actual block light verified across " + litCells + "/" + targetCells.size()
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
        if (buildChild != null) {
            buildChild.stop(player, Task.StopReason.REPLACED);
            buildChild.result(TaskState.CANCELLED);
            buildChild = null;
        }
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
