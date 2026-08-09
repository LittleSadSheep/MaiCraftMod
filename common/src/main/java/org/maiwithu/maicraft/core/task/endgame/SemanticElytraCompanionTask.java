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
            gateway = null;
            gatewayStand = null;
            phase = Phase.FIND_GATEWAY;
            return TaskState.RUNNING;
        }
        if (!safeGatewayStandCell(gatewayStand) || !gatewayVisibleFrom(gatewayStand, gateway)) {
            gatewayStand = safeGatewayStand(gateway);
            if (gatewayStand == null) {
                return failFinal("gateway_stance_changed",
                        "The verified safe gateway stance changed before approach; no alternate loaded stance is proven.",
                        FailureType.NO_PATH);
            }
        }
        HostileSurvey blockers = blockingHostiles(gatewayStand);
        if (blockers.protectedPresent()) {
            return failFinal("protected_gateway_blocker",
                    "A named or protected hostile blocks the safe gateway stance; it was left untouched.",
                    FailureType.ENTITY_BLOCKED);
        }
        if (!blockers.attackable().isEmpty()) {
            return startCombat(blockers.attackable(), Purpose.COMBAT_GATEWAY);
        }
        if (verifiedGatewayLaunchStance()) {
            phase = Phase.THROW_GATEWAY;
            return TaskState.RUNNING;
        }
        if (gatewayMoveAttempts >= 1) {
            return failFinal("gateway_approach_unconfirmed",
                    "One bounded first-person approach did not reach the verified visible gateway stance.",
                    FailureType.NO_PATH);
        }
        gatewayMoveAttempts++;
        return startChild(new MoveToTaskRecord(
                childId("gateway-stance"), childDeadline(3L * 60L * 20L),
                (double) gatewayStand.getX(), (double) gatewayStand.getY(),
                (double) gatewayStand.getZ(), null,
                effectiveMayAlterTerrain()), Purpose.MOVE_GATEWAY);
    }

    private TaskState throwPearl() {
        if (pearlCount() <= 0) {
            phase = Phase.ACQUIRE_PEARL;
            return TaskState.RUNNING;
        }
        if (!liveGateway(gateway)) {
            gateway = null;
            phase = Phase.FIND_GATEWAY;
            return TaskState.RUNNING;
        }
        if (protectedAt(gateway)) {
            return failFinal("gateway_became_protected",
                    "The selected End Gateway is now within a protected area.",
                    FailureType.ENTITY_BLOCKED);
        }
        if (!verifiedGatewayLaunchStance()) {
            phase = Phase.MOVE_GATEWAY;
            return TaskState.RUNNING;
        }
        HostileSurvey blockers = blockingHostiles(gateway);
        if (blockers.protectedPresent()) {
            return failFinal("protected_gateway_blocker",
                    "A named or protected hostile blocks the selected gateway; it was left untouched.",
                    FailureType.ENTITY_BLOCKED);
        }
        if (!blockers.attackable().isEmpty()) {
            return startCombat(blockers.attackable(), Purpose.COMBAT_GATEWAY);
        }
        if (!r.allowRareConsumables) {
            return failFinal("rare_consumable_permission_required",
                    "A real ender-pearl use is now required for the verified End Gateway. The task "
                            + "stopped before using it because allow_rare_consumables was not explicitly true.",
                    FailureType.INTERRUPTED);
        }
        if (pearlCount() <= 0 || !verifiedGatewayLaunchStance()) {
            return failFinal("pearl_use_precondition_changed",
                    "The remaining pearl or proven safe gateway stance changed immediately before use; "
                            + "nothing was thrown.",
                    FailureType.HAZARD);
        }
        throwOrigin = player.position();
        teleportSample = throwOrigin;
        pearlCountBeforeThrow = pearlCount();
        launchTick = player.level().getGameTime();
        teleportDeadline = player.level().getGameTime() + TELEPORT_CONFIRM_TICKS;
        gatewayLaunchVerified = true;
        gatewayUseConfirmed = false;
        pearlConsumptionConfirmed = false;
        abruptTeleportObserved = false;
        phase = Phase.WAIT_TELEPORT;
        return startChild(new InteractAtTaskRecord(
                childId("gateway-pearl"), childDeadline(2L * 60L * 20L),
                MouseButton.RIGHT, gateway, 0, Items.ENDER_PEARL), Purpose.THROW_PEARL);
    }

    private TaskState waitForTeleport() {
        observePearlConsumption();
        if (teleportVerified()) {
            gatewayVerified = true;
            gateway = null;
            gatewayStand = null;
            outerSearchOrigin = player.blockPosition().immutable();
            clearTeleportTracking();
            phase = Phase.SEARCH_END_CITY;
            return TaskState.RUNNING;
        }
        if (player.level().getGameTime() <= teleportDeadline) return TaskState.RUNNING;
        boolean consumed = pearlConsumptionConfirmed;
        clearTeleportTracking();
        return failFinal(consumed ? "gateway_teleport_unconfirmed" : "pearl_throw_unconfirmed",
                consumed
                        ? "The pearl left the real main inventory, but a substantial same-dimension gateway teleport was not observed."
                        : "Neither pearl consumption nor an End Gateway teleport was confirmed; the throw will not be repeated blindly.",
                FailureType.TARGET_LOST);
    }

    private TaskState searchEndCity() {
        if (outerSearchOrigin == null) outerSearchOrigin = player.blockPosition().immutable();
        if (endCitySearches >= MAX_END_CITIES) {
            return failFinal("end_city_search_exhausted",
                    "The bounded multi-city budget ended without a verified End Ship elytra frame.",
                    FailureType.TARGET_LOST);
        }
        int remaining = remainingOuterSearchDistance();
        if (remaining < PhysicalStructureSearchTaskRecord.MIN_DISTANCE) {
            return failFinal("end_city_distance_exhausted",
                    "The total outer-island search distance is exhausted; another city search would exceed policy.",
                    FailureType.TARGET_LOST);
        }
        endCitySearches++;
        return startChild(new PhysicalStructureSearchTaskRecord(
                childId("end-city"), childDeadline(40L * 60L * 20L),
                "minecraft:end_city", remaining,
                effectiveMayAlterTerrain(), true,
                visitedCityAnchors, CITY_EXCLUSION_RADIUS), Purpose.SEARCH_END_CITY);
    }

    private TaskState findShipFrame() {
        List<ItemFrame> observed = loadedElytraFrames();
        ItemFrame verified = null;
        ItemFrame incomplete = null;
        boolean managedPresent = false;
        boolean invalidPresent = false;
        for (ItemFrame frame : observed) {
            ShipEvidence evidence = shipEvidence(frame);
            if (evidence == ShipEvidence.MANAGED) managedPresent = true;
            else if (evidence == ShipEvidence.INVALID) invalidPresent = true;
            else if (evidence == ShipEvidence.INCOMPLETE && incomplete == null) incomplete = frame;
            else if (evidence == ShipEvidence.VERIFIED && verified == null) verified = frame;
        }
        if (managedPresent) {
            return failFinal("managed_elytra_display",
                    "A named, customized, managed, or protected elytra display was observed and will not be treated as End Ship loot.",
                    FailureType.ENTITY_BLOCKED);
        }
        if (invalidPresent) {
            return failFinal("end_ship_signature_missing",
                    "A real elytra frame was observed without the required End Ship hull, treasure-room, and attachment signature.",
                    FailureType.TARGET_LOST);
        }
        shipFrame = verified;
        if (shipFrame != null) {
            shipFramePosition = shipFrame.blockPosition().immutable();
            shipFrameVerified = true;
            HostileSurvey blockers = blockingHostiles(shipFramePosition);
            if (blockers.protectedPresent()) {
                return failFinal("protected_ship_blocker",
                        "A named or protected hostile blocks the verified ship frame; it was left untouched.",
                        FailureType.ENTITY_BLOCKED);
            }
            if (!blockers.attackable().isEmpty()) {
                return startCombat(blockers.attackable(), Purpose.COMBAT_SHIP);
            }
            phase = Phase.ATTACK_SHIP_FRAME;
            return TaskState.RUNNING;
        }
        if (incomplete != null) {
            if (incompleteFrameApproached) {
                return failFinal("end_ship_signature_unloaded",
                        "The candidate elytra frame remained only partially loaded after one bounded evidence approach.",
                        FailureType.TARGET_LOST);
            }
            incompleteFrameApproached = true;
            shipFrontiersAttempted++;
            shipFrontiersThisCity++;
            BlockPos target = incomplete.blockPosition();
            return startChild(new MoveToTaskRecord(
                    childId("ship-evidence"), childDeadline(3L * 60L * 20L),
                    (double) target.getX(), null, (double) target.getZ(),
                    null, effectiveMayAlterTerrain()), Purpose.SHIP_FRONTIER);
        }
        BlockPos frontier = nextShipFrontier();
        if (frontier == null) {
            return excludeCityAndContinue();
        }
        shipFrontiersAttempted++;
        shipFrontiersThisCity++;
        return startChild(new MoveToTaskRecord(
                childId("ship-frontier"), childDeadline(4L * 60L * 20L),
                (double) frontier.getX(), null, (double) frontier.getZ(),
                null, effectiveMayAlterTerrain()), Purpose.SHIP_FRONTIER);
    }

    private TaskState attackShipFrame() {
        if (shipFramePosition == null) {
            phase = Phase.FIND_SHIP_FRAME;
            return TaskState.RUNNING;
        }
        if (!player.level().isLoaded(shipFramePosition)) {
            return failFinal("ship_frame_unloaded",
                    "The verified ship-frame area unloaded before the first-person attack.",
                    FailureType.TARGET_LOST);
        }
        boolean intact = liveShipFrame(shipFrame);
        if (!intact) {
            if (frameAttacks <= 0 && !loadedElytraDrop()) {
                return failFinal("ship_frame_disappeared",
                        "The verified elytra frame disappeared before a confirmed attack and no loaded drop was observed.",
                        FailureType.TARGET_LOST);
            }
            shipFrame = null;
            dropSettleUntil = Math.max(dropSettleUntil,
                    player.level().getGameTime() + DROP_SETTLE_TICKS);
            phase = Phase.COLLECT_ELYTRA;
            return TaskState.RUNNING;
        }
        ShipEvidence evidence = shipEvidence(shipFrame);
        if (evidence == ShipEvidence.MANAGED) {
            return failFinal("elytra_frame_became_protected",
                    "The verified elytra frame is now named, customized, managed, or protected; it was left untouched.",
                    FailureType.ENTITY_BLOCKED);
        }
        if (evidence != ShipEvidence.VERIFIED) {
            return failFinal("end_ship_signature_changed",
                    "The loaded End Ship signature changed before the frame attack; the frame was left untouched.",
                    FailureType.TARGET_LOST);
        }
        HostileSurvey blockers = blockingHostiles(shipFramePosition);
        if (blockers.protectedPresent()) {
            return failFinal("protected_ship_blocker",
                    "A named or protected hostile blocks the verified ship frame; it was left untouched.",
                    FailureType.ENTITY_BLOCKED);
        }
        if (!blockers.attackable().isEmpty()) {
            return startCombat(blockers.attackable(), Purpose.COMBAT_SHIP);
        }
        if (frameAttacks >= 1) {
            return failFinal("frame_attack_unconfirmed",
                    "One bounded first-person frame attack did not change the real loaded frame; it will not be repeated blindly.",
                    FailureType.TARGET_LOST);
        }
        frameAttacks++;
        return startChild(new InteractEntityTaskRecord(
                childId("elytra-frame"), childDeadline(2L * 60L * 20L),
                MouseButton.LEFT, shipFrame.getId(), 0, null), Purpose.ATTACK_FRAME);
    }

    private TaskState collectElytra() {
        if (elytraCount() > 0) {
            phase = Phase.COMPLETE;
            return TaskState.SUCCESS;
        }
        if (player.level().getGameTime() < dropSettleUntil) return TaskState.RUNNING;
        if (collectionAttempts >= 1) {
            return failFinal(loadedElytraDrop()
                            ? "elytra_drop_uncollected" : "elytra_drop_missing",
                    loadedElytraDrop()
                            ? "A real loaded elytra drop remains, but the bounded collection attempt did not put it in the main inventory."
                            : "No real elytra reached the main inventory and no loaded drop remains.",
                    FailureType.TARGET_LOST);
        }
        if (!loadedElytraDrop()) {
            return failFinal("elytra_drop_not_observed",
                    "The frame changed, but no real loaded elytra drop was observed after the settle window.",
                    FailureType.TARGET_LOST);
        }
        collectionAttempts++;
        return startChild(new CollectItemsTaskRecord(
                childId("elytra-drop"), childDeadline(3L * 60L * 20L),
                Set.of(Items.ELYTRA), 32, "elytra"), Purpose.COLLECT_ELYTRA);
    }

    private TaskState tickChild() {
        Task finished = activeChild;
        Purpose purpose = activePurpose;
        TaskState terminal = runChild(finished);
        if (terminal == null) return TaskState.RUNNING;
        BlockPos verifiedAnchor = purpose == Purpose.SEARCH_END_CITY
                        && finished instanceof PhysicalStructureSearchCompanionTask search
                ? search.verifiedEvidenceAnchor() : null;
        TaskResult receipt = finished.result(terminal);
        activeChild = null;
        activeRecord = null;
        activePurpose = null;
        authorizedCombatIds.clear();
        boolean confirmed = terminal == TaskState.SUCCESS
                && receipt != null && receipt.success();

        return switch (purpose) {
            case ACQUIRE_PEARL -> {
                if (pearlCount() > 0) {
                    phase = Phase.FIND_GATEWAY;
                    yield TaskState.RUNNING;
                }
                yield failFinal("pearl_acquisition_failed",
                        "The bounded acquisition ended without a real ender pearl in the main inventory.",
                        lastFailure());
            }
            case GATEWAY_FRONTIER -> {
                if (!confirmed) gatewayFrontiersFailed++;
                phase = Phase.FIND_GATEWAY;
                yield TaskState.RUNNING;
            }
            case MOVE_GATEWAY -> {
                if (!confirmed) {
                    yield failFinal("gateway_approach_failed",
                            "The bounded first-person move did not reach the verified gateway stance.",
                            lastFailure());
                }
                phase = Phase.MOVE_GATEWAY;
                yield TaskState.RUNNING;
            }
            case COMBAT_GATEWAY -> {
                if (!confirmed) {
                    yield failFinal("gateway_blocker_combat_failed",
                            "The bounded fight against an actively attacking gateway blocker was not confirmed.",
                            lastFailure());
                }
                phase = Phase.MOVE_GATEWAY;
                yield TaskState.RUNNING;
            }
            case THROW_PEARL -> {
                gatewayUseConfirmed = confirmed;
                if (!gatewayUseConfirmed) {
                    clearTeleportTracking();
                    yield failFinal("pearl_throw_unconfirmed",
                            "The task's first-person child did not produce a confirmed native USE_ITEM receipt. "
                                    + "An unrelated pearl decrease cannot authorize or confirm this use.",
                            lastFailure());
                }
                observePearlConsumption();
                if (teleportVerified()) {
                    acceptGatewayTeleport();
                    yield TaskState.RUNNING;
                }
                phase = Phase.WAIT_TELEPORT;
                yield TaskState.RUNNING;
            }
            case SEARCH_END_CITY -> {
                if (!confirmed || verifiedAnchor == null) {
                    yield failFinal("end_city_search_failed",
                            "The bounded physical structure search ended without a typed verified End City evidence anchor.",
                            lastFailure());
                }
                if (horizontalDistanceSquared(outerSearchOrigin, verifiedAnchor)
                        > (long) r.maxSearchDistance * r.maxSearchDistance) {
                    yield failFinal("end_city_outside_total_scope",
                            "The verified city evidence lies outside the total semantic search distance and was not accepted.",
                            FailureType.TARGET_LOST);
                }
                endCityEvidence = true;
                cityAnchor = verifiedAnchor;
                shipFrontiersThisCity = 0;
                incompleteFrameApproached = false;
                shipFrame = null;
                shipFramePosition = null;
                phase = Phase.FIND_SHIP_FRAME;
                yield TaskState.RUNNING;
            }
            case SHIP_FRONTIER -> {
                if (!confirmed) shipFrontiersFailed++;
                phase = Phase.FIND_SHIP_FRAME;
                yield TaskState.RUNNING;
            }
            case COMBAT_SHIP -> {
                if (!confirmed) {
                    yield failFinal("ship_blocker_combat_failed",
                            "The bounded fight against an actively attacking ship blocker was not confirmed.",
                            lastFailure());
                }
                phase = Phase.FIND_SHIP_FRAME;
                yield TaskState.RUNNING;
            }
            case ATTACK_FRAME -> {
                if (elytraCount() > 0) {
                    phase = Phase.COMPLETE;
                    yield TaskState.SUCCESS;
                }
                if (shipFramePosition == null
                        || !player.level().isLoaded(shipFramePosition)) {
                    yield failFinal("ship_frame_unloaded_after_attack",
                            "The ship-frame area unloaded before the real attack outcome could be inspected.",
                            FailureType.TARGET_LOST);
                }
                if (liveShipFrame(shipFrame)) {
                    yield failFinal("frame_attack_unconfirmed",
                            "The real loaded frame still holds the elytra after one first-person attack; it will not be struck again blindly.",
                            lastFailure());
                }
                shipFrame = null;
                dropSettleUntil = player.level().getGameTime() + DROP_SETTLE_TICKS;
                phase = Phase.COLLECT_ELYTRA;
                yield TaskState.RUNNING;
            }
            case COLLECT_ELYTRA -> {
                if (elytraCount() > 0) {
                    phase = Phase.COMPLETE;
                    yield TaskState.SUCCESS;
                }
                yield failFinal(loadedElytraDrop()
                                ? "elytra_drop_uncollected" : "elytra_drop_missing",
                        loadedElytraDrop()
                                ? "The loaded elytra drop remains outside the main inventory after one bounded collection attempt."
                                : "The collection attempt ended without an elytra in the main inventory or a loaded drop.",
                        lastFailure());
            }
        };
    }

    private TaskState startCombat(List<Mob> blockers, Purpose purpose) {
        if (!r.allowCombat) {
            return failFinal("combat_permission_required",
                    "An explicitly hostile loaded mob is blocking progress, but allow_combat is false.",
                    FailureType.ENTITY_BLOCKED);
        }
        if (combatEncounters >= MAX_COMBAT_ENCOUNTERS) {
            return failFinal("combat_budget_exhausted",
                    "The bounded combat budget is exhausted; further fighting needs a new decision.",
                    FailureType.HAZARD);
        }
        List<Integer> targets = blockers.stream().limit(16).map(Mob::getId).toList();
        if (targets.isEmpty()) {
            return failFinal("hostile_blocker_unresolved",
                    "A blocking threat changed before a safe first-person combat target could be compiled.",
                    FailureType.TARGET_LOST);
        }
        combatEncounters++;
        authorizedCombatIds.clear();
        authorizedCombatIds.addAll(targets);
        return startChild(new AttackTaskRecord(
                childId("blocker"), childDeadline(5L * 60L * 20L),
                targets, false, true), purpose);
    }

    private TaskState startChild(TaskRecord record, Purpose purpose) {
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
        activePurpose = purpose;
        return TaskState.RUNNING;
    }

    private TaskState failFinal(String code, String message, FailureType type) {
        failIssue(code, message, type);
        return TaskState.FAILED;
    }

    private void failIssue(String code, String message, FailureType type) {
        issueCode = code;
        fail(message, type == null ? FailureType.UNKNOWN : type);
    }

    private boolean liveGateway(BlockPos pos) {
        return pos != null && player.level().isLoaded(pos)
                && player.level().getBlockState(pos).is(Blocks.END_GATEWAY);
    }

    private BlockPos safeGatewayStand(BlockPos target) {
        if (!liveGateway(target)) return null;
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 2; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos feet = target.offset(dx, dy, dz);
                        if (safeGatewayStandCell(feet) && gatewayVisibleFrom(feet, target)) {
                            candidates.add(feet.immutable());
                        }
                    }
                }
            }
            if (!candidates.isEmpty()) break;
        }
        candidates.sort(Comparator.comparingDouble(
                pos -> player.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private boolean safeGatewayStandCell(BlockPos feet) {
        if (feet == null || protectedAt(feet)) return false;
        ClientLevel level = player.clientLevel;
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!level.isLoaded(feet) || !level.isLoaded(head) || !level.isLoaded(floor)
                || feet.getY() <= level.getMinBuildHeight() + 8) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                || !level.getFluidState(feet).isEmpty()
                || !level.getFluidState(head).isEmpty()) return false;
        return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }

    private boolean gatewayVisibleFrom(BlockPos feet, BlockPos target) {
        if (feet == null || target == null) return false;
        Vec3 eye = Vec3.atBottomCenterOf(feet).add(0.0D, player.getEyeHeight(), 0.0D);
        return gatewayRayHits(eye, target);
    }

    private boolean gatewayRayHits(Vec3 eye, BlockPos target) {
        if (!liveGateway(target)) return false;
        BlockHitResult hit = player.level().clip(new ClipContext(
                eye, Vec3.atCenterOf(target), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        return hit.getBlockPos().equals(target);
    }

    private boolean verifiedGatewayLaunchStance() {
        if (!liveGateway(gateway) || protectedAt(gateway)
                || !safeGatewayStandCell(player.blockPosition())
                || gatewayStand == null
                || horizontalDistanceSquared(player.blockPosition(), gatewayStand) > 4L) {
            return false;
        }
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(gateway))
                        <= GATEWAY_REACH_DISTANCE * GATEWAY_REACH_DISTANCE
                && gatewayRayHits(player.getEyePosition(), gateway);
    }

    private void observeTeleportSample() {
        if (throwOrigin == null || teleportSample == null
                || !END.equals(dimension())) return;
        Vec3 current = player.position();
        long now = player.level().getGameTime();
        if (now >= launchTick && now <= teleportDeadline
                && current.distanceToSqr(teleportSample)
                        >= TELEPORT_MIN_DISTANCE * TELEPORT_MIN_DISTANCE) {
            abruptTeleportObserved = true;
        }
        teleportSample = current;
    }

    private boolean teleportVerified() {
        return throwOrigin != null
                && END.equals(dimension())
                && gatewayLaunchVerified
                && gatewayUseConfirmed
                && abruptTeleportObserved
                && player.level().getGameTime() <= teleportDeadline
                && outsideMainIsland(player.blockPosition());
    }

    private boolean observePearlConsumption() {
        if (!pearlConsumptionConfirmed
                && gatewayUseConfirmed
                && pearlCountBeforeThrow > 0
                && pearlCount() < pearlCountBeforeThrow) {
            pearlConsumptionConfirmed = true;
            pearlsConsumed++;
        }
        return pearlConsumptionConfirmed;
    }

    private void acceptGatewayTeleport() {
        gatewayVerified = true;
        gateway = null;
        gatewayStand = null;
        outerSearchOrigin = player.blockPosition().immutable();
        clearTeleportTracking();
        phase = Phase.SEARCH_END_CITY;
    }

    private void clearTeleportTracking() {
        throwOrigin = null;
        teleportSample = null;
        gatewayLaunchVerified = false;
        gatewayUseConfirmed = false;
        pearlConsumptionConfirmed = false;
        abruptTeleportObserved = false;
        pearlCountBeforeThrow = 0;
        launchTick = 0L;
        teleportDeadline = 0L;
    }

    private List<ItemFrame> loadedElytraFrames() {
        if (cityAnchor == null) return List.of();
        ClientLevel level = player.clientLevel;
        BlockPos center = player.blockPosition();
        AABB scan = new AABB(
                center.getX() - SHIP_ENTITY_SCAN_RADIUS, level.getMinBuildHeight(),
                center.getZ() - SHIP_ENTITY_SCAN_RADIUS,
                center.getX() + SHIP_ENTITY_SCAN_RADIUS, level.getMaxBuildHeight(),
                center.getZ() + SHIP_ENTITY_SCAN_RADIUS);
        long distanceLimit = (long) SHIP_ENTITY_SCAN_RADIUS * SHIP_ENTITY_SCAN_RADIUS;
        List<ItemFrame> frames = new ArrayList<>(level.getEntitiesOfClass(
                ItemFrame.class, scan,
                frame -> liveShipFrame(frame)
                        && horizontalDistanceSquared(cityAnchor, frame.blockPosition())
                                <= distanceLimit));
        frames.sort(Comparator.comparingDouble(player::distanceToSqr));
        return List.copyOf(frames);
    }
