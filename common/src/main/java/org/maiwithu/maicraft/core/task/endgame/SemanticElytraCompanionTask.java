// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.endgame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractEntityTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.task.structure.PhysicalStructureSearchCompanionTask;
import org.maiwithu.maicraft.core.task.structure.PhysicalStructureSearchTaskRecord;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Receipt- and observation-driven End Gateway -> End City -> elytra executor. */
public final class SemanticElytraCompanionTask
        extends AbstractCompanionTask<SemanticElytraTaskRecord> {
    private enum Phase {
        ACQUIRE_PEARL, FIND_GATEWAY, MOVE_GATEWAY, THROW_GATEWAY, WAIT_TELEPORT,
        SEARCH_END_CITY, FIND_SHIP_FRAME, ATTACK_SHIP_FRAME,
        COLLECT_ELYTRA, COMPLETE
    }

    private enum Purpose {
        ACQUIRE_PEARL, GATEWAY_FRONTIER, MOVE_GATEWAY, COMBAT_GATEWAY,
        THROW_PEARL, SEARCH_END_CITY,
        SHIP_FRONTIER, COMBAT_SHIP, ATTACK_FRAME, COLLECT_ELYTRA
    }

    private record HostileSurvey(List<Mob> attackable, boolean protectedPresent) {}
    private enum ShipEvidence { VERIFIED, INCOMPLETE, INVALID, MANAGED }

    private static final String END = "minecraft:the_end";
    private static final int MAIN_ISLAND_RADIUS = 512;
    private static final int GATEWAY_SCAN_RADIUS = 384;
    private static final int GATEWAY_SCAN_TICKS = 240;
    private static final int GATEWAY_BUILD_BUDGET = 8;
    private static final int MAX_GATEWAY_FRONTIERS = 32;
    private static final int GATEWAY_FRONTIER_STEP = 64;
    private static final int TELEPORT_CONFIRM_TICKS = 240;
    private static final double TELEPORT_MIN_DISTANCE = 64.0D;
    private static final double GATEWAY_REACH_DISTANCE = 4.25D;
    private static final int LANDMARK_PROTECTION_RADIUS = 12;
    private static final int SHIP_ENTITY_SCAN_RADIUS = 192;
    private static final int SHIP_FRONTIER_STEP = 48;
    private static final int MAX_SHIP_FRONTIERS = 24;
    private static final int MAX_END_CITIES = 8;
    private static final int CITY_EXCLUSION_RADIUS = 160;
    private static final int SHIP_SIGNATURE_RADIUS = 18;
    private static final int SHIP_MIN_PURPUR = 40;
    private static final int SHIP_MIN_CHESTS = 2;
    private static final int SHIP_MIN_BREWING_STANDS = 1;
    private static final int MAX_COMBAT_ENCOUNTERS = 2;
    private static final int HOSTILE_RADIUS = 16;
    private static final int DROP_SETTLE_TICKS = 10;
    private static final float SAFETY_HEALTH_FLOOR = 8.0F;
    private static final int VOID_GROUND_PROBE = 32;

    private final Set<net.minecraft.world.level.block.Block> gatewayBlocks =
            Set.of(Blocks.END_GATEWAY);
    private ClientLevel indexedLevel;
    private BlockPos origin;
    private BlockPos gateway;
    private BlockPos gatewayStand;
    private BlockPos cityAnchor;
    private BlockPos outerSearchOrigin;
    private ItemFrame shipFrame;
    private BlockPos shipFramePosition;
    private Phase phase;
    private Task activeChild;
    private TaskRecord activeRecord;
    private Purpose activePurpose;
    private String issueCode;
    private Vec3 throwOrigin;
    private Vec3 teleportSample;
    private int pearlCountBeforeThrow;
    private long launchTick;
    private long teleportDeadline;
    private long dropSettleUntil;
    private boolean gatewayVerified;
    private boolean gatewayLaunchVerified;
    private boolean gatewayUseConfirmed;
    private boolean pearlConsumptionConfirmed;
    private boolean abruptTeleportObserved;
    private boolean endCityEvidence;
    private boolean shipFrameVerified;
    private int gatewayScans;
    private int gatewayIncompleteScans;
    private int gatewayFrontiersAttempted;
    private int gatewayFrontiersFailed;
    private int gatewayMoveAttempts;
    private int pearlAcquireAttempts;
    private int pearlsConsumed;
    private int endCitySearches;
    private int shipFrontiersAttempted;
    private int shipFrontiersFailed;
    private int shipFrontiersThisCity;
    private boolean incompleteFrameApproached;
    private int combatEncounters;
    private int frameAttacks;
    private int collectionAttempts;
    private int childSerial;
    private boolean cleaned;
    private final List<BlockPos> visitedCityAnchors = new ArrayList<>();
    private final Set<Integer> authorizedCombatIds = new HashSet<>();

    public SemanticElytraCompanionTask(
            LocalPlayer player, SemanticElytraTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        if (elytraCount() > 0) {
            phase = Phase.COMPLETE;
            return;
        }
        if (!END.equals(dimension())) {
            failIssue("wrong_dimension",
                    "Elytra retrieval needs a real local-player body in minecraft:the_end.",
                    FailureType.TARGET_LOST);
            return;
        }
        List<String> unknown = r.protectedLabels.stream()
                .filter(label -> IntentRuntime.get().landmark(label) == null)
                .toList();
        if (!unknown.isEmpty()) {
            failIssue("unknown_protected_label",
                    "Some protected_labels are not remembered, so safe interaction cannot be proven.",
                    FailureType.UNKNOWN);
            return;
        }
        origin = player.blockPosition().immutable();
        if (outsideMainIsland(origin)) outerSearchOrigin = origin;
        indexedLevel = player.clientLevel;
        TargetIndex.register(indexedLevel, gatewayBlocks);
        phase = outsideMainIsland(player.blockPosition())
                ? Phase.SEARCH_END_CITY
                : pearlCount() > 0 ? Phase.FIND_GATEWAY : Phase.ACQUIRE_PEARL;
    }

    @Override
    protected TaskState onTick() {
        if (elytraCount() > 0) {
            clearActiveChild(TaskState.CANCELLED);
            phase = Phase.COMPLETE;
            return TaskState.SUCCESS;
        }
        if (!END.equals(dimension())) {
            return failFinal("dimension_changed",
                    "The local-player body left minecraft:the_end before an elytra reached the main inventory.",
                    FailureType.TARGET_LOST);
        }
        TaskState safety = safetyGate();
        if (safety != null) return safety;
        observeTeleportSample();
        if (activeChild != null) return tickChild();
        return switch (phase) {
            case ACQUIRE_PEARL -> acquirePearl();
            case FIND_GATEWAY -> findGateway();
            case MOVE_GATEWAY -> moveGateway();
            case THROW_GATEWAY -> throwPearl();
            case WAIT_TELEPORT -> waitForTeleport();
            case SEARCH_END_CITY -> searchEndCity();
            case FIND_SHIP_FRAME -> findShipFrame();
            case ATTACK_SHIP_FRAME -> attackShipFrame();
            case COLLECT_ELYTRA -> collectElytra();
            case COMPLETE -> TaskState.SUCCESS;
        };
    }

    private TaskState acquirePearl() {
        if (pearlCount() > 0) {
            phase = Phase.FIND_GATEWAY;
            return TaskState.RUNNING;
        }
        if (pearlAcquireAttempts >= 1) {
            return failFinal("pearl_unavailable",
                    "No ender pearl reached the real main inventory; the task will not choose a new source silently.",
                    FailureType.NO_MATERIAL);
        }
        pearlAcquireAttempts++;
        List<SemanticAcquireTaskRecord.Source> sources = r.allowCombat
                ? List.of(SemanticAcquireTaskRecord.Source.INVENTORY,
                        SemanticAcquireTaskRecord.Source.NEARBY,
                        SemanticAcquireTaskRecord.Source.HUNT)
                : List.of(SemanticAcquireTaskRecord.Source.INVENTORY,
                        SemanticAcquireTaskRecord.Source.NEARBY);
        SemanticAcquireTaskRecord.SourceHint hint = new SemanticAcquireTaskRecord.SourceHint(
                List.of(),
                List.of(BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.ENDERMAN)),
                List.of(BuiltInRegistries.ITEM.getKey(Items.ENDER_PEARL)),
                List.of(), "A real ender pearl for one End Gateway traversal.");
        return startChild(new SemanticAcquireTaskRecord(
                childId("pearl"), childDeadline(8L * 60L * 20L),
                List.of(BuiltInRegistries.ITEM.getKey(Items.ENDER_PEARL)),
                1, sources, r.allowCombat, hint, r.protectedLabels,
                32, 6, 96), Purpose.ACQUIRE_PEARL);
    }

    private TaskState findGateway() {
        if (outsideMainIsland(player.blockPosition())) {
            phase = Phase.SEARCH_END_CITY;
            return TaskState.RUNNING;
        }
        int radius = Math.min(r.maxSearchDistance, GATEWAY_SCAN_RADIUS);
        int chunks = Math.max(1, (radius + 15) / 16);
        TargetIndex.Result observed = TargetIndex.query(
                player.clientLevel, player.blockPosition(), gatewayBlocks,
                16, chunks, GATEWAY_BUILD_BUDGET);
        gatewayScans++;
        if (!observed.complete()) gatewayIncompleteScans++;

        List<BlockPos> candidates = observed.hits().stream()
                .filter(this::liveGateway)
                .filter(pos -> distanceSquared(origin, pos)
                        <= (long) r.maxSearchDistance * r.maxSearchDistance)
                .filter(pos -> !outsideMainIsland(pos))
                .sorted(Comparator.comparingDouble(
                        pos -> player.distanceToSqr(Vec3.atCenterOf(pos))))
                .toList();
        boolean protectedOnly = !candidates.isEmpty()
                && candidates.stream().allMatch(this::protectedAt);
        gateway = candidates.stream().filter(pos -> !protectedAt(pos))
                .findFirst().orElse(null);
        if (gateway != null) {
            gatewayStand = safeGatewayStand(gateway);
            if (gatewayStand == null) {
                return failFinal("gateway_stance_unproven",
                        "A real gateway was loaded, but no safe loaded adjacent stance with a clear view could be proven.",
                        FailureType.NO_PATH);
            }
            gatewayMoveAttempts = 0;
            phase = Phase.MOVE_GATEWAY;
            return TaskState.RUNNING;
        }
        if (protectedOnly) {
            return failFinal("gateway_protected",
                    "Every verified loaded End Gateway is inside a protected area.",
                    FailureType.ENTITY_BLOCKED);
        }
        if (gatewayScans < GATEWAY_SCAN_TICKS && !observed.complete()) {
            return TaskState.RUNNING;
        }
        BlockPos frontier = nextGatewayFrontier();
        if (frontier == null) {
            return failFinal(observed.complete()
                            ? "gateway_frontier_exhausted" : "gateway_scan_incomplete",
                    observed.complete()
                            ? "Bounded first-person main-island frontier exploration ended without observing a real End Gateway."
                            : "The bounded loaded-terrain gateway index and frontier budget ended without complete evidence.",
                    FailureType.TARGET_LOST);
        }
        return startChild(new MoveToTaskRecord(
                childId("gateway-frontier"), childDeadline(3L * 60L * 20L),
                (double) frontier.getX(), null, (double) frontier.getZ(),
                null, effectiveMayAlterTerrain()), Purpose.GATEWAY_FRONTIER);
    }

    private TaskState moveGateway() {
        if (!liveGateway(gateway) || protectedAt(gateway)) {
