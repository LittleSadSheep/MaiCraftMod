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
