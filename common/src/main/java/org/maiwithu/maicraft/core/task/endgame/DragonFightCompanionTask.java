// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.endgame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.act.Ballistics;
import org.maiwithu.maicraft.core.combat.Loadout;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.EatItemTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Receipt-driven first-person orchestration for one live Ender Dragon encounter. */
public final class DragonFightCompanionTask
        extends AbstractCompanionTask<DragonFightTaskRecord> {
    private enum Phase { OBSERVE, SURVEY, CRYSTALS, DRAGON, RECOVER, CONFIRM }
    private enum Purpose {
        SURVEY_MOVE, POSITION_CRYSTAL, ATTACK_CRYSTAL, POSITION_CAGE, OPEN_CAGE,
        ATTACK_DRAGON, EVADE_HAZARD, EAT;
        boolean recovery() { return this == EVADE_HAZARD || this == EAT; }
    }
    private record PortalObservation(boolean known, boolean present) {}
    private record CageObservation(List<BlockPos> bars, List<BlockPos> opening) {
        CageObservation {
            bars = List.copyOf(bars);
            opening = List.copyOf(opening);
        }
    }

    private static final double ENCOUNTER_SCAN_RADIUS = 192.0D;
    private static final int MAIN_ISLAND_TARGET_RADIUS = 160;
    private static final int SAFE_PLAYER_RADIUS = 176;
    private static final int SAFE_HAVEN_RADIUS = 120;
    private static final int PROTECTED_RADIUS = 12;
    private static final int CRYSTAL_BLAST_CLEARANCE = 13;
    private static final int ZERO_CRYSTAL_STABLE_TICKS = 20;
    private static final int CONFIRM_STABLE_TICKS = 20;
    private static final int CONFIRM_TIMEOUT_TICKS = 1_200;
    private static final int NATURAL_RECOVERY_TIMEOUT_TICKS = 800;
    private static final int WAIT_FOR_SAFE_DRAGON_TICKS = 4_800;
    private static final int MAX_CRYSTAL_ATTACKS = 5;
    private static final int MAX_CAGE_OPENINGS_PER_CRYSTAL = 3;
    private static final int MAX_DRAGON_ATTACK_ROUNDS = 24;
    private static final int MAX_RECOVERY_ACTIONS = 24;
    private static final int MAX_FAILED_FOODS = 4;
    private static final int CRITICAL_HUNGER = 6;
    private static final int TOWER_COVERAGE_RADIUS = 80;
    private static final int MAX_SURVEY_MOVES = 16;
    private static final int MAX_CRYSTAL_POSITION_ATTEMPTS = 4;
    private static final int MAX_TRANSIENT_DRAGON_FAILURES = 4;
    private static final double CRYSTAL_SHOT_MAX_RANGE = 96.0D;
    private static final double CRYSTAL_BLAST_MARGIN = 1.0D;

    private Phase phase = Phase.OBSERVE;
    private Purpose activePurpose;
    private Task activeChild;
    private TaskRecord activeRecord;
    private Item activeFood;
    private int childSerial;
    private EnderDragon dragon;
    private UUID encounterDragonUuid;
    private BlockPos fightOrigin = BlockPos.ZERO;
    private List<EndCrystal> loadedCrystals = List.of();
    private EndCrystal selectedCrystal;
    private UUID selectedCrystalUuid;
    private BlockPos selectedCrystalLastPosition;
    private int selectedCrystalRuntimeId = -1;
    private AttackTaskRecord activeAttackRecord;
    private List<BlockPos> cagePlan = List.of();
    private final Map<UUID, Integer> crystalAttacks = new LinkedHashMap<>();
    private final Map<UUID, Integer> cageOpenings = new LinkedHashMap<>();
    private final Map<UUID, BlockPos> unresolvedCrystals = new LinkedHashMap<>();
    private List<BlockPos> cageProtectedCells = List.of();
    private final List<BlockPos> protectedAnchors = new ArrayList<>();
    private boolean portalIndexRegistered;
    private boolean deathPhaseObserved;
    private boolean dragonRemovalObserved;
    private boolean exitPortalObserved;
    private boolean portalBaselineKnown;
    private boolean portalPresentAtStart;
    private long lastPortalScanAt = Long.MIN_VALUE;
    private long zeroCrystalsSince = -1L;
    private long confirmSince = -1L;
    private long confirmStartedAt = -1L;
    private long recoveryWaitSince = -1L;
    private long safeDragonWaitSince = -1L;
    private long healingWithoutVisibleCrystalSince = -1L;
    private long nextDragonAttemptAt;
    private float lastDragonHealth = -1.0F;
    private boolean healingObservedThisTick;
    private int initialLoadedCrystals;
    private int maximumLoadedCrystals;
    private int crystalsConfirmedDestroyed;
    private int cageOpeningsConfirmed;
    private int dragonAttackRounds;
    private int recoveryActions;
    private int foodsConsumed;
    private int breathAvoidances;
    private int dragonHealingObservations;
    private int interruptedCombatChildren;
    private int surveyMoves;
    private int surveyMovesWithoutProgress;
    private int coverageCountBeforeMove;
    private int crystalPositionAttempts;
    private int transientDragonFailures;
    private int healingSurveyAttempts;
    private int rareConsumablesConsumed;
    private final Set<Long> requiredTowerChunks = new HashSet<>();
    private final Set<Long> observedTowerChunks = new HashSet<>();
    private final Set<Item> failedFoods = new HashSet<>();
    private String failureCode;
    private List<String> recoveryOptions = List.of();

    public DragonFightCompanionTask(LocalPlayer player, DragonFightTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() {
        if (player.level().dimension() != Level.END) {
            failDecision("wrong_dimension",
                    "The Ender Dragon encounter can only be observed in minecraft:the_end.",
                    FailureType.TARGET_LOST,
                    List.of("travel to minecraft:the_end", "cancel the encounter"));
            return;
        }
        if (r.minimumHealth > player.getMaxHealth()) {
            failDecision("unreachable_health_floor",
                    "minimum_health is above the body's current maximum health.",
                    FailureType.UNKNOWN,
                    List.of("retry with a reachable minimum_health",
                            "improve maximum health before retrying"));
            return;
        }
        if (!resolveProtectedLabels()) return;
        List<EnderDragon> dragons = liveDragons();
        if (dragons.isEmpty()) {
            failDecision("no_live_dragon_observed",
                    "No live Ender Dragon is loaded, so a new encounter cannot be claimed.",
                    FailureType.TARGET_LOST,
                    List.of("load the active main-island encounter",
                            "respawn the dragon before retrying",
                            "cancel if the dragon was already defeated"));
            return;
        }
        if (dragons.size() != 1) {
            failDecision("ambiguous_dragon_encounter",
                    "More than one live Ender Dragon is loaded; choosing one would be unsafe.",
                    FailureType.UNKNOWN,
                    List.of("resolve the duplicate encounter state", "retry with one live dragon"));
            return;
        }
        dragon = dragons.getFirst();
        encounterDragonUuid = dragon.getUUID();
        BlockPos observedOrigin = dragon.getFightOrigin();
        fightOrigin = observedOrigin == null ? BlockPos.ZERO : observedOrigin.immutable();
        lastDragonHealth = dragon.getHealth();
        TargetIndex.register(player.clientLevel, List.of(Blocks.END_PORTAL));
        portalIndexRegistered = true;
        initializeTowerCoverage();
        observeTowerCoverage();
        PortalObservation baseline = scanExitPortal();
        portalBaselineKnown = baseline.known();
        portalPresentAtStart = baseline.present();
        loadedCrystals = scanCrystals();
        recordObservedCrystals();
        initialLoadedCrystals = loadedCrystals.size();
        maximumLoadedCrystals = initialLoadedCrystals;
        phase = loadedCrystals.isEmpty() ? Phase.OBSERVE : Phase.CRYSTALS;
    }

    @Override protected TaskState onTick() {
        if (player.isDeadOrDying()) return TaskState.CANCELLED;
        TaskState observationFailure = observeWorld();
        if (observationFailure != null) return observationFailure;
        if (hasConfirmedDeathState()) {
            if (activeChild != null) cancelActiveChild(false);
            phase = Phase.CONFIRM;
        }
        if (activePurpose == Purpose.ATTACK_DRAGON
                && (!loadedCrystals.isEmpty() || healingObservedThisTick)) {
            cancelActiveChild(true);
            zeroCrystalsSince = -1L;
            phase = loadedCrystals.isEmpty() ? Phase.OBSERVE : Phase.CRYSTALS;
        }
        if (activeChild != null && !activePurpose.recovery()) {
            TaskState targetCheck = validateActiveWork();
            if (targetCheck != null) return targetCheck;
            if (needsImmediateRecovery()) {
                cancelActiveChild(true);
                phase = Phase.RECOVER;
            }
        }
        if (activeChild != null && activePurpose == Purpose.EAT
                && (dangerousBreath() != null || !safePlayerPosition())) {
            // Eating is recovery, but it must not pin the body inside a newly arrived breath cloud.
            cancelActiveChild(false);
            phase = Phase.RECOVER;
        }
        if (activeChild != null) return tickChild();
        if (phase == Phase.CONFIRM) return tickConfirm();
        if (needsImmediateRecovery()) {
            phase = Phase.RECOVER;
            return tickRecover();
        }
        return switch (phase) {
            case OBSERVE -> tickObserve();
            case SURVEY -> tickSurvey();
            case CRYSTALS -> tickCrystals();
            case DRAGON -> tickDragon();
            case RECOVER -> tickRecover();
            case CONFIRM -> tickConfirm();
        };
    }

    private TaskState observeWorld() {
        healingObservedThisTick = false;
        observeTowerCoverage();
        observePortalBaseline();
        loadedCrystals = scanCrystals();
        recordObservedCrystals();
        maximumLoadedCrystals = Math.max(maximumLoadedCrystals, loadedCrystals.size());
        if (!loadedCrystals.isEmpty()) zeroCrystalsSince = -1L;
        TaskState crystalObservationFailure = unresolvedCrystalObservationFailure();
        if (crystalObservationFailure != null) return crystalObservationFailure;
        List<EnderDragon> dragons = liveDragons();
        if (dragons.size() > 1) {
            return failDecision("ambiguous_dragon_encounter",
                    "Multiple live Ender Dragons became loaded during the encounter.",
                    FailureType.UNKNOWN,
                    List.of("resolve the duplicate encounter state before continuing"));
        }
        if (dragons.size() == 1) {
            EnderDragon observed = dragons.getFirst();
            if (!observed.getUUID().equals(encounterDragonUuid)) {
                return failDecision("encounter_identity_changed",
                        "A different live Ender Dragon replaced the observed encounter.",
                        FailureType.TARGET_LOST,
                        List.of("inspect the respawned encounter", "start a new fight task"));
            }
            dragon = observed;
            if (phase == Phase.CONFIRM && observed.isAlive() && !observed.isDeadOrDying()) {
                // A same-encounter live entity proves the previous disappearance was not death.
                dragonRemovalObserved = false;
                deathPhaseObserved = false;
                exitPortalObserved = false;
                confirmSince = -1L;
                confirmStartedAt = -1L;
                PortalObservation currentPortal = scanExitPortal();
                portalBaselineKnown = currentPortal.known();
                portalPresentAtStart = currentPortal.known() && currentPortal.present();
                lastPortalScanAt = Long.MIN_VALUE;
                phase = loadedCrystals.isEmpty()
                        ? coverageComplete() ? Phase.OBSERVE : Phase.SURVEY
                        : Phase.CRYSTALS;
            }
            noteDragonState(observed);
        } else if (dragon != null) {
            noteDragonState(dragon);
            if (dragon.isRemoved() && (deathPhaseObserved || dragon.getHealth() <= 0.0F)) {
                dragonRemovalObserved = true;
            }
        }
        return null;
    }

    private void noteDragonState(EnderDragon observed) {
        boolean dying = observed.dragonDeathTime > 0 || observed.isDeadOrDying()
                || observed.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING;
        if (dying) deathPhaseObserved = true;
        if (observed.isRemoved() && dying) dragonRemovalObserved = true;
        float current = observed.getHealth();
        if (!dying && lastDragonHealth >= 0.0F && current > lastDragonHealth + 0.25F) {
            dragonHealingObservations++;
            healingObservedThisTick = true;
            zeroCrystalsSince = -1L;
            if (loadedCrystals.isEmpty() && healingWithoutVisibleCrystalSince < 0L) {
                healingWithoutVisibleCrystalSince = player.level().getGameTime();
                observedTowerChunks.clear();
                surveyMoves = 0;
                surveyMovesWithoutProgress = 0;
                healingSurveyAttempts++;
            }
        }
        if (!loadedCrystals.isEmpty()) healingWithoutVisibleCrystalSince = -1L;
        lastDragonHealth = current;
    }

    private TaskState tickObserve() {
        if (!loadedCrystals.isEmpty()) {
            phase = Phase.CRYSTALS;
            return TaskState.RUNNING;
        }
        long now = player.level().getGameTime();
        if (healingWithoutVisibleCrystalSince >= 0L
                && !coverageComplete()) {
            phase = Phase.SURVEY;
            return TaskState.RUNNING;
        }
        if (healingWithoutVisibleCrystalSince >= 0L
                && now - healingWithoutVisibleCrystalSince > 100L) {
            return failDecision("unobserved_healing_source",
                    "The dragon regained health, and a fresh bounded tower survey still found no "
                            + "loaded crystal. The unseen source cannot be treated as gone.",
                    FailureType.TARGET_LOST,
                    List.of("move until the remaining crystal is loaded",
                            "increase render distance and retry",
                            "inspect the main island before continuing"));
        }
        if (!coverageComplete()) {
            phase = Phase.SURVEY;
            return TaskState.RUNNING;
        }
        if (zeroCrystalsSince < 0L) zeroCrystalsSince = now;
        if (now - zeroCrystalsSince < ZERO_CRYSTAL_STABLE_TICKS) return TaskState.RUNNING;
        phase = Phase.DRAGON;
        return TaskState.RUNNING;
    }

    private TaskState tickSurvey() {
        if (!loadedCrystals.isEmpty()) {
            phase = Phase.CRYSTALS;
            return TaskState.RUNNING;
        }
        if (coverageComplete()) {
            if (healingWithoutVisibleCrystalSince >= 0L) {
                return failDecision("unobserved_healing_source",
                        "The dragon regained health, but a fresh complete main-island tower "
                                + "survey still found no visible crystal. That contradictory "
                                + "world state requires a decision instead of another loop.",
                        FailureType.TARGET_LOST,
                        List.of("inspect the remaining healing beam manually",
                                "increase entity tracking distance and retry",
                                "resume only when the healing source is visible"));
            }
            zeroCrystalsSince = player.level().getGameTime();
            phase = Phase.OBSERVE;
            return TaskState.RUNNING;
        }
        if (surveyMoves >= MAX_SURVEY_MOVES || surveyMovesWithoutProgress >= 4) {
            return failDecision("tower_coverage_incomplete",
                    "The bounded first-person survey could not load every main-island tower "
                            + "observation sector, so an empty client entity list is not proof that "
                            + "all crystals are gone.",
                    FailureType.TARGET_LOST,
                    List.of("increase render distance", "clear a safe route around the main island",
                            "continue the survey manually and retry"));
        }
        BlockPos vantage = findCoverageVantage();
        if (vantage == null) {
            return failDecision("no_safe_survey_vantage",
                    "No loaded standable, non-void vantage toward the missing tower coverage could "
                            + "be verified.",
                    FailureType.HAZARD,
                    List.of("load more safe main-island ground",
                            "create a safe observation path manually",
                            "retry from the central island"));
        }
        coverageCountBeforeMove = observedTowerChunks.size();
        surveyMoves++;
        MoveToTaskRecord move = new MoveToTaskRecord(
                childId("survey"), childDeadline(3L * 60L * 20L),
                (double) vantage.getX(), null, (double) vantage.getZ(),
                null, false);
        return startChild(move, Purpose.SURVEY_MOVE);
    }

    private TaskState tickCrystals() {
        if (loadedCrystals.isEmpty()) {
            if (selectedCrystalUuid != null) {
                boolean targetCellLoaded = selectedCrystalLastPosition != null
                        && player.level().isLoaded(selectedCrystalLastPosition);
                invalidateCoverage(selectedCrystalLastPosition);
                return failDecision(
                        targetCellLoaded
                                ? "crystal_changed_without_attack_receipt"
                                : "crystal_observation_unloaded",
                        targetCellLoaded
                                ? "The selected crystal disappeared without a matching "
                                        + "first-person attack receipt. Its destruction was not "
                                        + "claimed."
                                : "The selected crystal's last observation sector unloaded; an "
                                        + "empty entity list is not a destruction fact.",
                        FailureType.TARGET_LOST,
                        List.of("reload and inspect the same tower sector",
                                "repeat the bounded crystal survey",
                                "retry only after the crystal state is visible"));
            }
            clearSelectedCrystal();
            phase = Phase.OBSERVE;
            return TaskState.RUNNING;
        }
        EndCrystal target = selectedCrystalUuid == null ? null : findCrystal(selectedCrystalUuid);
        if (selectedCrystalUuid != null && target == null) {
            boolean targetCellLoaded = selectedCrystalLastPosition != null
                    && player.level().isLoaded(selectedCrystalLastPosition);
            invalidateCoverage(selectedCrystalLastPosition);
            return failDecision(
                    targetCellLoaded
                            ? "crystal_changed_without_attack_receipt"
                            : "crystal_observation_unloaded",
                    targetCellLoaded
                            ? "The selected crystal disappeared without a matching first-person "
                                    + "attack receipt; switching to another crystal would hide "
                                    + "that uncertainty."
                            : "The selected crystal's last observation sector unloaded; switching "
                                    + "targets would treat unloading as destruction.",
                    FailureType.TARGET_LOST,
                    List.of("reload and inspect the same tower sector",
                            "repeat the bounded crystal survey",
                            "retry only after the crystal state is visible"));
        }
        if (target == null) {
            target = loadedCrystals.stream()
                    .min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        }
        if (target == null) {
            phase = Phase.OBSERVE;
            return TaskState.RUNNING;
        }
        selectCrystal(target);
        if (!safeEncounterTarget(target.blockPosition())) {
            return failDecision("unsafe_crystal_target",
                    "A loaded End Crystal is outside the verified main-island safety envelope.",
                    FailureType.HAZARD,
                    List.of("load and inspect the main island",
                            "handle the outlying crystal manually",
                            "retry from a safe main-island position"));
        }
        if (protectedNear(target, PROTECTED_RADIUS)) {
            return failDecision("crystal_near_protected_label",
                    "A remaining crystal is close enough to a protected place that its explosion "
                            + "is not authorized.",
                    FailureType.HAZARD,
                    List.of("revise protected_labels explicitly",
                            "protect the remembered structure before retrying"));
        }
        if (unsafeCrystalBlastCollateral(target)) {
            return failDecision("crystal_blast_bystander",
                    "Another living entity or End Crystal is inside the selected crystal's full "
                            + "blast span. Firing now could harm or chain-destroy an unselected "
                            + "target.",
                    FailureType.HAZARD,
                    List.of("wait for bystanders to leave the blast span",
                            "inspect clustered crystals before continuing",
                            "retry when one crystal can be handled in isolation"));
        }
        if (!Loadout.forTarget(player, target).hasRanged()) {
            return failDecision("missing_safe_ranged_loadout",
                    "A remaining End Crystal is loaded, but no usable bow/crossbow with ammunition "
                            + "is available. The task will not climb into its blast.",
                    FailureType.WRONG_TOOL,
                    List.of("obtain a bow or crossbow and ammunition",
                            "equip a charged crossbow",
                            "handle the remaining crystal manually"));
        }

        if (!safeCrystalFiringPosition(target)) {
            if (crystalPositionAttempts >= MAX_CRYSTAL_POSITION_ATTEMPTS) {
                return failDecision("safe_crystal_stance_exhausted",
                        "No bounded first-person move established a loaded firing stance outside "
                                + "the crystal blast span.",
                        FailureType.HAZARD,
                        List.of("clear a safe ranged stance", "return to stable main-island ground",
                                "retry after the firing lane changes"));
            }
            BlockPos vantage = findCrystalVantage(target, false);
            if (vantage == null) {
                return failDecision("no_safe_crystal_stance",
                        "No loaded standable firing stance outside the crystal blast span could "
                                + "be verified.",
                        FailureType.HAZARD,
                        List.of("load more stable main-island ground",
                                "create a safe ranged platform manually",
                                "retry from farther away"));
            }
            crystalPositionAttempts++;
            MoveToTaskRecord move = new MoveToTaskRecord(
                    childId("crystal-stance"), childDeadline(2L * 60L * 20L),
                    (double) vantage.getX(), (double) vantage.getY(),
                    (double) vantage.getZ(), null, false);
            return startChild(move, Purpose.POSITION_CRYSTAL);
        }

        if (!hasSafeCrystalShot(target)) {
            CageObservation cage = observeVerifiedCage(target);
            if (cage != null) return startCageOpening(target, cage);
            if (crystalPositionAttempts < MAX_CRYSTAL_POSITION_ATTEMPTS) {
                BlockPos vantage = findCrystalVantage(target, true);
                if (vantage != null) {
                    crystalPositionAttempts++;
                    MoveToTaskRecord move = new MoveToTaskRecord(
                            childId("crystal-line"), childDeadline(2L * 60L * 20L),
                            (double) vantage.getX(), (double) vantage.getY(),
                            (double) vantage.getZ(), null, false);
                    return startChild(move, Purpose.POSITION_CRYSTAL);
                }
            }
            return failDecision("crystal_occlusion_unverified",
                    "No safe arrow trajectory reached the crystal, and the blocking geometry "
                            + "was not a fully verified iron-bar cage. Nothing was altered.",
                    FailureType.OCCLUDED,
                    List.of("reposition for a clear ranged line",
                            "inspect the obstruction before authorizing changes",
                            "retry after the firing lane changes"));
        }

        int attempts = crystalAttacks.getOrDefault(selectedCrystalUuid, 0);
        if (attempts >= MAX_CRYSTAL_ATTACKS) {
            return failDecision("crystal_attack_exhausted",
                    "One observed crystal survived every bounded first-person attack attempt.",
                    FailureType.OUT_OF_REACH,
                    List.of("change the ranged loadout", "reposition for line of sight",
                            "inspect the enclosure manually"));
        }
        crystalAttacks.put(selectedCrystalUuid, attempts + 1);
        AttackTaskRecord attack = new AttackTaskRecord(
                childId("crystal"), childDeadline(3L * 60L * 20L),
                List.of(target.getId()), false, true);
        return startChild(attack, Purpose.ATTACK_CRYSTAL);
    }

    private TaskState finishCrystalAttack(TaskState terminal, TaskResult receipt) {
        AttackTaskRecord attack = activeAttackRecord;
        activeAttackRecord = null;
        EndCrystal target = selectedCrystalUuid == null ? null : findCrystal(selectedCrystalUuid);
        boolean targetCellLoaded = selectedCrystalLastPosition != null
                && player.level().isLoaded(selectedCrystalLastPosition);
        boolean successfulReceipt = terminal == TaskState.SUCCESS
                && receipt != null && receipt.success();
        boolean targetAttackReceipt = attack != null && selectedCrystalRuntimeId >= 0
                && attack.strikes(selectedCrystalRuntimeId) > 0
                && attack.defeated().contains(selectedCrystalRuntimeId);
        if (target == null && targetCellLoaded && successfulReceipt && targetAttackReceipt) {
            crystalsConfirmedDestroyed++;
            unresolvedCrystals.remove(selectedCrystalUuid);
            clearSelectedCrystal();
            cagePlan = List.of();
            cageProtectedCells = List.of();
            phase = Phase.CRYSTALS;
            return TaskState.RUNNING;
        }
        if (target == null) {
            invalidateCoverage(selectedCrystalLastPosition);
            return failDecision("crystal_observation_lost",
                    "The selected crystal disappeared while its observation area was not a "
                            + "loaded, receipt-confirmed destruction fact.",
                    FailureType.TARGET_LOST,
                    List.of("reload the same tower sector", "repeat the bounded crystal survey",
                            "retry only after the crystal state is visible"));
        }
        selectCrystal(target);
        if (successfulReceipt) {
            return failDecision("crystal_attack_inconsistent",
                    "The attack child reported success while the same crystal remained alive; "
                            + "the semantic goal was not accepted.",
                    FailureType.UNKNOWN,
                    List.of("inspect the firing receipt", "retry after the world state settles"));
        }
        FailureType type = lastFailure();
        if (transientExecutionFailure(type)
                && crystalPositionAttempts < MAX_CRYSTAL_POSITION_ATTEMPTS) {
            phase = Phase.CRYSTALS;
            return TaskState.RUNNING;
        }
        return childFailureDecision("crystal_attack_failed",
                "The first-person crystal attack stopped without a verified destruction.", type,
                List.of("change or repair the ranged loadout",
                        "reposition for a verified firing line",
                        "inspect the crystal before retrying"));
    }

    private TaskState finishCageOpening(TaskState terminal, TaskResult receipt) {
        boolean clear = !cagePlan.isEmpty() && cagePlan.stream().allMatch(
                pos -> player.level().isLoaded(pos)
                        && player.level().getBlockState(pos).isAir());
        cagePlan = List.of();
        cageProtectedCells = List.of();
        if (terminal == TaskState.SUCCESS && receipt != null && receipt.success() && clear) {
            cageOpeningsConfirmed++;
            phase = Phase.CRYSTALS;
            return TaskState.RUNNING;
        }
        return failDecision("cage_change_unconfirmed",
                "The first-person build receipt did not leave the freshly verified cage opening "
                        + "clear. Blindly repeating terrain changes is unsafe.",
                lastFailure(),
                List.of("inspect the enclosure state", "clear a safe opening manually",
                        "retry after the world state is stable"));
    }

    private TaskState tickDragon() {
        if (!loadedCrystals.isEmpty()) {
            phase = Phase.CRYSTALS;
            return TaskState.RUNNING;
        }
        if (zeroCrystalsSince < 0L
                || player.level().getGameTime() - zeroCrystalsSince
                        < ZERO_CRYSTAL_STABLE_TICKS) {
            phase = Phase.OBSERVE;
            return TaskState.RUNNING;
        }
        if (hasConfirmedDeathState()) {
            phase = Phase.CONFIRM;
            return TaskState.RUNNING;
        }
        if (dragon == null || dragon.isRemoved() || !dragon.isAlive()) {
            phase = Phase.CONFIRM;
            return TaskState.RUNNING;
        }
        if (protectedNear(dragon, PROTECTED_RADIUS)) {
            return failDecision("dragon_near_protected_label",
                    "The live dragon is inside a protected remembered area; combat there is not "
                            + "authorized.",
                    FailureType.HAZARD,
                    List.of("wait for the dragon to leave the protected area",
                            "revise protected_labels explicitly"));
        }
        long now = player.level().getGameTime();
        boolean ranged = Loadout.forTarget(player, dragon).hasRanged();
        boolean sitting = dragon.getPhaseManager().getCurrentPhase().isSitting();
        boolean safeTarget = safeDragonTarget(dragon, ranged);
        if ((!ranged && !sitting) || !safeTarget) {
            if (safeDragonWaitSince < 0L) safeDragonWaitSince = now;
            if (now - safeDragonWaitSince > WAIT_FOR_SAFE_DRAGON_TICKS) {
                return failDecision(
                        !ranged ? "no_safe_dragon_attack_window"
                                : "dragon_outside_safe_envelope",
                        !ranged
                                ? "No usable ranged weapon was available and the dragon never "
                                        + "entered a verified perched melee window."
                                : "The dragon did not return to a loaded main-island attack window.",
                        !ranged ? FailureType.WRONG_TOOL : FailureType.HAZARD,
                        !ranged
                                ? List.of("obtain a ranged weapon with ammunition",
                                        "wait for a perch and retry")
                                : List.of("return to the main island", "wait and retry"));
            }
            return TaskState.RUNNING;
        }
        safeDragonWaitSince = -1L;
        if (now < nextDragonAttemptAt) return TaskState.RUNNING;
        if (dragonAttackRounds >= MAX_DRAGON_ATTACK_ROUNDS) {
            return failDecision("dragon_attack_exhausted",
                    "The dragon remained alive after every bounded first-person attack round.",
                    FailureType.OUT_OF_REACH,
                    List.of("repair or improve the combat loadout", "wait for a safer perch",
                            "inspect the encounter before retrying"));
        }
        dragonAttackRounds++;
        AttackTaskRecord attack = new AttackTaskRecord(
                childId("dragon"), childDeadline(12L * 60L * 20L),
                List.of(dragon.getId()), false, true);
        return startChild(attack, Purpose.ATTACK_DRAGON);
    }

    private TaskState finishDragonAttack(TaskState terminal, TaskResult receipt) {
        activeAttackRecord = null;
        if (hasConfirmedDeathState()) {
            phase = Phase.CONFIRM;
            return TaskState.RUNNING;
        }
        if (!loadedCrystals.isEmpty()) {
            zeroCrystalsSince = -1L;
            phase = Phase.CRYSTALS;
            return TaskState.RUNNING;
        }
        boolean successfulReceipt = terminal == TaskState.SUCCESS
                && receipt != null && receipt.success();
        if (successfulReceipt && dragon != null && !dragon.isRemoved() && dragon.isAlive()) {
            return failDecision("dragon_attack_inconsistent",
                    "The attack child reported success while the same Ender Dragon remained "
                            + "alive; victory was not accepted.",
                    FailureType.UNKNOWN,
                    List.of("inspect the encounter state", "retry after synchronization settles"));
        }
        if (successfulReceipt || dragon == null || dragon.isRemoved() || !dragon.isAlive()) {
            phase = Phase.CONFIRM;
            return TaskState.RUNNING;
        }
        FailureType type = lastFailure();
        if (!transientExecutionFailure(type)) {
            return childFailureDecision("dragon_attack_failed",
                    "The first-person dragon attack stopped on a non-transient prerequisite or "
                            + "safety failure.", type,
                    List.of("repair or change the combat loadout",
                            "recover at a safe main-island position",
                            "resume only after the blocking fact changes"));
        }
        if (++transientDragonFailures > MAX_TRANSIENT_DRAGON_FAILURES) {
            return failDecision("dragon_transient_retries_exhausted",
                    "Repeated bounded execution changes did not produce a verified dragon attack "
                            + "window.",
                    type,
                    List.of("wait for a safer perch", "change the ranged loadout",
                            "inspect the main-island firing lanes"));
        }
        nextDragonAttemptAt = player.level().getGameTime()
                + 40L;
        phase = Phase.DRAGON;
        return TaskState.RUNNING;
    }

    private TaskState tickRecover() {
        if (recoveryActions >= MAX_RECOVERY_ACTIONS) {
            return failDecision("recovery_exhausted",
                    "Combat had to be interrupted too many times to maintain the requested health "
                            + "and hazard safety envelope.",
                    FailureType.HAZARD,
                    List.of("improve armor and food supplies", "lower minimum_health explicitly",
                            "resume from a safer main-island position"));
        }
        if (dangerousBreath() != null || !safePlayerPosition()) {
            BlockPos safe = findSafeHaven();
            if (safe == null) {
                return failDecision("no_loaded_safe_haven",
                        "No loaded, standable main-island cell clear of dragon breath, crystals "
                                + "and the void could be verified.",
                        FailureType.HAZARD,
                        List.of("load more of the main island",
                                "create a safe platform manually", "retry from stable ground"));
            }
            recoveryActions++;
            MoveToTaskRecord move = new MoveToTaskRecord(
                    childId("evade"), childDeadline(2L * 60L * 20L),
                    (double) safe.getX(), (double) safe.getY(), (double) safe.getZ(),
                    null, false);
            return startChild(move, Purpose.EVADE_HAZARD);
        }
        boolean lowHealth = player.getHealth() < r.minimumHealth;
        boolean hungry = hasHunger()
                && player.getFoodData().getFoodLevel() <= CRITICAL_HUNGER;
        if (!lowHealth && !hungry) {
            recoveryWaitSince = -1L;
            phase = loadedCrystals.isEmpty() ? Phase.OBSERVE : Phase.CRYSTALS;
            return TaskState.RUNNING;
        }
        Item food = chooseSafeFood();
        if (food != null) {
            recoveryActions++;
            activeFood = food;
            EatItemTaskRecord eat = new EatItemTaskRecord(
                    childId("eat"), childDeadline(60L * 20L), food,
                    BuiltInRegistries.ITEM.getKey(food).toString());
            return startChild(eat, Purpose.EAT);
        }
        if (lowHealth && hasHunger() && player.getFoodData().getFoodLevel() >= 18) {
            long now = player.level().getGameTime();
            if (recoveryWaitSince < 0L) recoveryWaitSince = now;
            if (now - recoveryWaitSince <= NATURAL_RECOVERY_TIMEOUT_TICKS) {
                return TaskState.RUNNING;
            }
            return failDecision("health_recovery_stalled",
                    "Health stayed below minimum_health despite sufficient hunger and a bounded "
                            + "natural-regeneration wait.",
                    FailureType.HAZARD,
                    List.of("use a safe healing item", "lower minimum_health explicitly",
                            "improve armor before retrying"));
        }
        return failDecision("missing_safe_recovery_food",
                "Health or hunger crossed the safety floor, but no safe edible item could be used.",
                FailureType.NO_MATERIAL,
                List.of("obtain ordinary food or a safe healing food",
                        "recover manually before retrying",
                        "lower minimum_health explicitly if appropriate"));
    }

    private TaskState tickChild() {
        Task finished = activeChild;
        Purpose purpose = activePurpose;
        TaskState terminal = runChild(finished);
        if (terminal == null) return TaskState.RUNNING;
        TaskResult receipt = finished.result(terminal);
        activeChild = null;
        activeRecord = null;
        activePurpose = null;
        return switch (purpose) {
            case SURVEY_MOVE -> finishSurveyMove(terminal, receipt);
            case POSITION_CRYSTAL -> finishCrystalPosition(terminal, receipt);
            case ATTACK_CRYSTAL -> finishCrystalAttack(terminal, receipt);
            case POSITION_CAGE -> finishCageApproach(terminal, receipt);
            case OPEN_CAGE -> finishCageOpening(terminal, receipt);
            case ATTACK_DRAGON -> finishDragonAttack(terminal, receipt);
            case EVADE_HAZARD -> finishEvasion(terminal, receipt);
            case EAT -> finishEating(terminal, receipt);
        };
    }

    private TaskState finishSurveyMove(TaskState terminal, TaskResult receipt) {
        if (terminal != TaskState.SUCCESS || receipt == null || !receipt.success()) {
            return childFailureDecision("tower_survey_move_failed",
                    "The bounded first-person tower survey could not reach its next observation "
                            + "sector without altering terrain.", lastFailure(),
                    List.of("clear a safe main-island route", "increase render distance",
                            "continue the observation loop manually"));
        }
        observeTowerCoverage();
        if (observedTowerChunks.size() <= coverageCountBeforeMove) {
            surveyMovesWithoutProgress++;
        } else {
            surveyMovesWithoutProgress = 0;
        }
        phase = loadedCrystals.isEmpty() ? Phase.SURVEY : Phase.CRYSTALS;
        return TaskState.RUNNING;
    }

    private TaskState finishCrystalPosition(TaskState terminal, TaskResult receipt) {
        EndCrystal target = selectedCrystalUuid == null ? null : findCrystal(selectedCrystalUuid);
        if (terminal == TaskState.SUCCESS && receipt != null && receipt.success()
                && target != null && safeCrystalFiringPosition(target)) {
            selectCrystal(target);
            phase = Phase.CRYSTALS;
            return TaskState.RUNNING;
        }
        if (target == null) invalidateCoverage(selectedCrystalLastPosition);
        return childFailureDecision("crystal_position_failed",
                "The first-person move did not establish a loaded stance outside the crystal "
                        + "blast span.",
                target == null ? FailureType.TARGET_LOST : lastFailure(),
                List.of("clear a safe firing stance", "return to stable main-island ground",
                        "retry after the route changes"));
    }

    private TaskState finishCageApproach(TaskState terminal, TaskResult receipt) {
        EndCrystal target = selectedCrystalUuid == null ? null : findCrystal(selectedCrystalUuid);
        CageObservation refreshed = target == null ? null : observeVerifiedCage(target);
        if (terminal == TaskState.SUCCESS && receipt != null && receipt.success()
                && target != null && cagePlan.size() == 1
                && cageOpeningInReach()
                && refreshed != null && refreshed.opening().equals(cagePlan)) {
            selectCrystal(target);
            return startVerifiedCageBuild();
        }
        if (target == null) invalidateCoverage(selectedCrystalLastPosition);
        cagePlan = List.of();
        cageProtectedCells = List.of();
        return childFailureDecision("cage_approach_failed",
                "A terrain-preserving first-person move did not establish the exact verified "
                        + "cage-opening stance. Nothing was altered.",
                target == null ? FailureType.TARGET_LOST : lastFailure(),
                List.of("clear a natural path to the cage",
                        "open one firing aperture manually",
                        "retry from stable tower ground"));
    }

    private TaskState finishEvasion(TaskState terminal, TaskResult receipt) {
        if (terminal == TaskState.SUCCESS && receipt != null && receipt.success()
                && dangerousBreath() == null && safePlayerPosition()) {
            breathAvoidances++;
            phase = Phase.RECOVER;
            return TaskState.RUNNING;
        }
        return failDecision("hazard_evasion_unconfirmed",
                "The first-person movement receipt did not leave the body on a verified safe "
                        + "main-island cell outside dragon breath.",
                lastFailure(),
                List.of("clear a safe path", "move to stable main-island ground",
                        "retry afterward"));
    }

    private TaskState finishEating(TaskState terminal, TaskResult receipt) {
        Item attempted = activeFood;
        activeFood = null;
        if (terminal == TaskState.SUCCESS && receipt != null && receipt.success()) {
            foodsConsumed++;
            if (attempted == Items.GOLDEN_APPLE
                    || attempted == Items.ENCHANTED_GOLDEN_APPLE) {
                rareConsumablesConsumed++;
            }
            recoveryWaitSince = -1L;
            phase = Phase.RECOVER;
            return TaskState.RUNNING;
        }
        if (attempted != null) failedFoods.add(attempted);
        FailureType type = lastFailure();
        if (!transientExecutionFailure(type)) {
            return childFailureDecision("recovery_food_failed",
                    "The first-person eat child stopped on a non-transient prerequisite or "
                            + "inventory failure; choosing another item silently would hide the "
                            + "decision.",
                    type,
                    List.of("inspect the inventory and food state",
                            "obtain ordinary safe food",
                            "recover manually before retrying"));
        }
        if (failedFoods.size() >= MAX_FAILED_FOODS) {
            return failDecision("recovery_food_attempts_exhausted",
                    "Several first-person eat attempts failed their real consumption receipts; "
                            + "repeating is no longer safe.",
                    type,
                    List.of("inspect the inventory and food effects",
                            "recover manually before retrying"));
        }
        phase = Phase.RECOVER;
        return TaskState.RUNNING;
    }

    private TaskState tickConfirm() {
        long now = player.level().getGameTime();
        if (confirmStartedAt < 0L) confirmStartedAt = now;
        observeExitPortal();
        boolean deadOrRemoved = dragonRemovalObserved
                || (dragon != null && (dragon.isRemoved()
                        || (dragon.isDeadOrDying() && dragon.getHealth() <= 0.0F)));
        boolean corroborated = deathPhaseObserved || exitPortalObserved;
        if (deadOrRemoved && corroborated) {
            if (confirmSince < 0L) confirmSince = now;
            if (now - confirmSince >= CONFIRM_STABLE_TICKS) return TaskState.SUCCESS;
        } else {
            confirmSince = -1L;
        }
        if (now - confirmStartedAt > CONFIRM_TIMEOUT_TICKS) {
            return failDecision("dragon_death_unconfirmed",
                    "The dragon disappeared or stopped fighting, but stable death/removal plus "
                            + "death-phase or exit-portal evidence could not be confirmed.",
                    FailureType.TARGET_LOST,
                    List.of("return to the loaded exit portal",
                            "inspect whether the dragon merely unloaded",
                            "retry only if a live dragon is observed"));
        }
        return TaskState.RUNNING;
    }

    /** Recheck volatile facts immediately before handing this tick to a child. */
    private TaskState validateActiveWork() {
        if (activePurpose == Purpose.ATTACK_CRYSTAL) {
            EndCrystal observed = selectedCrystalUuid == null
                    ? null : findCrystal(selectedCrystalUuid);
            if (observed == null) {
                if (selectedCrystalLastPosition == null
                        || !player.level().isLoaded(selectedCrystalLastPosition)) {
                    cancelActiveChild(true);
                    invalidateCoverage(selectedCrystalLastPosition);
                    return failDecision("crystal_chunk_unloaded",
                            "The selected crystal's observation area unloaded during the attack; "
                                    + "unloading is not a destruction receipt.",
                            FailureType.TARGET_LOST,
                            List.of("reload the same tower sector",
                                    "repeat the bounded crystal survey"));
                }
                // Keep the child alive for its native strike/despawn receipt. finishCrystalAttack
                // is the only place allowed to convert this absence into a confirmed destruction.
                return null;
            }
            if (observed.getId() != selectedCrystalRuntimeId) {
                cancelActiveChild(true);
                selectCrystal(observed);
                phase = Phase.CRYSTALS;
                return TaskState.RUNNING;
            }
            selectCrystal(observed);
            if (!safeEncounterTarget(observed.blockPosition())) {
                cancelActiveChild(true);
                return failDecision("crystal_left_safe_envelope",
                        "The selected crystal was no longer a loaded main-island target before "
                                + "the next first-person attack tick.",
                        FailureType.TARGET_LOST,
                        List.of("reload and inspect the crystal", "retry from the main island"));
            }
        } else if (activePurpose == Purpose.POSITION_CRYSTAL) {
            EndCrystal observed = selectedCrystalUuid == null
                    ? null : findCrystal(selectedCrystalUuid);
            if (observed == null && (selectedCrystalLastPosition == null
                    || !player.level().isLoaded(selectedCrystalLastPosition))) {
                cancelActiveChild(true);
                invalidateCoverage(selectedCrystalLastPosition);
                return failDecision("crystal_chunk_unloaded",
                        "The selected crystal's observation area unloaded while establishing a "
                                + "safe firing stance.",
                        FailureType.TARGET_LOST,
                        List.of("reload the same tower sector", "repeat the crystal survey"));
            }
        } else if (activePurpose == Purpose.POSITION_CAGE) {
            EndCrystal observed = selectedCrystalUuid == null
                    ? null : findCrystal(selectedCrystalUuid);
            if (observed == null) {
                cancelActiveChild(true);
                invalidateCoverage(selectedCrystalLastPosition);
                cagePlan = List.of();
                cageProtectedCells = List.of();
                return failDecision("crystal_changed_during_cage_approach",
                        "The selected crystal disappeared while moving toward a verified cage "
                                + "stance. Nothing was altered.",
                        FailureType.TARGET_LOST,
                        List.of("reload and inspect the same tower sector",
                                "repeat the bounded crystal survey"));
            }
            selectCrystal(observed);
            if (cagePlan.size() != 1
                    || !player.level().isLoaded(cagePlan.getFirst())
                    || !player.level().getBlockState(cagePlan.getFirst()).is(Blocks.IRON_BARS)) {
                cancelActiveChild(true);
                cagePlan = List.of();
                cageProtectedCells = List.of();
                return failDecision("cage_changed_during_approach",
                        "The exact verified iron-bar aperture changed during approach, so the "
                                + "planned terrain change was discarded.",
                        FailureType.TARGET_LOST,
                        List.of("inspect the enclosure", "retry after it is stable"));
            }
        } else if (activePurpose == Purpose.ATTACK_DRAGON) {
            if (dragon == null || dragon.isRemoved() || !dragon.isAlive()) {
                cancelActiveChild(false);
                phase = Phase.CONFIRM;
                return TaskState.RUNNING;
            }
            boolean ranged = Loadout.forTarget(player, dragon).hasRanged();
            if (!safeDragonTarget(dragon, ranged)) {
                cancelActiveChild(true);
                nextDragonAttemptAt = player.level().getGameTime() + 20L;
                phase = Phase.DRAGON;
                return TaskState.RUNNING;
            }
        } else if (activePurpose == Purpose.OPEN_CAGE) {
            EndCrystal observed = selectedCrystalUuid == null
                    ? null : findCrystal(selectedCrystalUuid);
            if (observed == null) {
                cancelActiveChild(true);
                invalidateCoverage(selectedCrystalLastPosition);
                return failDecision("crystal_changed_during_cage_opening",
                        "The crystal disappeared while only cage blocks were being changed; no "
                                + "combat receipt can authorize calling it destroyed.",
                        FailureType.TARGET_LOST,
                        List.of("reload and inspect the same tower sector",
                                "repeat the bounded crystal survey"));
            }
            selectCrystal(observed);
            for (BlockPos pos : cagePlan) {
                if (!player.level().isLoaded(pos)) {
                    cancelActiveChild(true);
                    return failDecision("cage_chunk_unloaded",
                            "The verified cage cell unloaded before the next build tick.",
                            FailureType.TARGET_LOST,
                            List.of("reload the crystal tower and retry"));
                }
                if (!player.level().getBlockState(pos).isAir()
                        && !player.level().getBlockState(pos).is(Blocks.IRON_BARS)) {
                    cancelActiveChild(true);
                    return failDecision("cage_state_changed",
                            "A verified iron-bar cage cell changed to another block before the "
                                    + "next build tick; it was left untouched.",
                            FailureType.UNKNOWN,
                            List.of("inspect the changed enclosure", "retry after it is stable"));
                }
            }
            if (!cageOpeningInReach()
                    && cagePlan.stream().noneMatch(
                            pos -> player.level().getBlockState(pos).isAir())) {
                cancelActiveChild(true);
                cagePlan = List.of();
                cageProtectedCells = List.of();
                return failDecision("cage_stance_lost",
                        "The body left interaction range before the verified aperture was "
                                + "cleared. The terrain-changing child was stopped immediately.",
                        FailureType.OUT_OF_REACH,
                        List.of("return to the verified stance", "retry from stable tower ground"));
            }
        }
        return null;
    }

    private boolean needsImmediateRecovery() {
        return dangerousBreath() != null || !safePlayerPosition()
                || player.getHealth() < r.minimumHealth
                || (hasHunger()
                        && player.getFoodData().getFoodLevel() <= CRITICAL_HUNGER);
    }

    private boolean hasHunger() {
        return WorkProfile.of(player).hasHunger();
    }

    private Item chooseSafeFood() {
        Item best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || failedFoods.contains(stack.getItem())) continue;
            FoodProperties food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food == null || !player.canEat(food.canAlwaysEat())) continue;
            Item item = stack.getItem();
            if (item == Items.CHORUS_FRUIT || item == Items.PUFFERFISH
                    || item == Items.POISONOUS_POTATO || item == Items.ROTTEN_FLESH
                    || item == Items.SPIDER_EYE || item == Items.SUSPICIOUS_STEW) continue;
            boolean trustedEffectFood = item == Items.GOLDEN_APPLE
                    || item == Items.ENCHANTED_GOLDEN_APPLE;
            if (!food.effects().isEmpty() && !trustedEffectFood) continue;
            if (trustedEffectFood && !r.allowRareConsumables) continue;
            double score = food.nutrition() * 4.0D + food.saturation() * 2.0D
                    + (food.canAlwaysEat() ? 8.0D : 0.0D)
                    + (trustedEffectFood && player.getHealth() < r.minimumHealth ? 20.0D : 0.0D);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private AreaEffectCloud dangerousBreath() {
        AABB scan = player.getBoundingBox().inflate(24.0D);
        return player.clientLevel.getEntitiesOfClass(
                        AreaEffectCloud.class, scan,
                        cloud -> !cloud.isRemoved()
                                && (cloud.getOwner() instanceof EnderDragon
                                        || cloud.getParticle().getType()
                                                == ParticleTypes.DRAGON_BREATH))
                .stream()
                .filter(cloud -> player.distanceToSqr(cloud)
                        <= square(cloud.getRadius() + 5.0D))
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    private BlockPos findSafeHaven() {
        ClientLevel level = player.clientLevel;
        BlockPos body = player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int radius = 3; radius <= 18; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = body.getX() + dx;
                    int z = body.getZ() + dz;
                    if (!insideRadius(x, z, fightOrigin, SAFE_HAVEN_RADIUS)) continue;
                    BlockPos probe = new BlockPos(x, body.getY(), z);
                    if (!level.isLoaded(probe)) continue;
                    int surfaceY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    for (int y = Math.min(surfaceY, body.getY() + 6);
                            y >= Math.max(level.getMinBuildHeight() + 2, body.getY() - 14);
                            y--) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (!standable(candidate)
                                || protectedNear(candidate, PROTECTED_RADIUS)
                                || nearBreath(candidate, 7.0D)
                                || nearCrystal(candidate, CRYSTAL_BLAST_CLEARANCE)) continue;
                        double score = body.distSqr(candidate)
                                + fightOrigin.distSqr(candidate) * 0.01D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null && radius >= 8) break;
        }
        return best;
    }

    private boolean safePlayerPosition() {
        BlockPos feet = player.blockPosition();
        if (!player.level().isLoaded(feet)
                || !insideRadius(feet.getX(), feet.getZ(), fightOrigin, SAFE_PLAYER_RADIUS)
                || feet.getY() <= player.level().getMinBuildHeight() + 3) return false;
        if (player.onGround() || player.isInWater()) return groundWithin(feet, 3);
        // Normal jumps and knockback above solid island terrain are left to the fall reflex.
        return groundWithin(feet, 32);
    }

    private boolean standable(BlockPos feet) {
        ClientLevel level = player.clientLevel;
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!level.isLoaded(feet) || !level.isLoaded(head) || !level.isLoaded(floor)) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                || !level.getFluidState(feet).isEmpty()
                || !level.getFluidState(head).isEmpty()) return false;
        return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                && !level.getBlockState(floor).is(Blocks.END_PORTAL);
    }

    private boolean groundWithin(BlockPos start, int drop) {
        ClientLevel level = player.clientLevel;
        for (int d = 0; d <= drop; d++) {
            BlockPos floor = start.below(d + 1);
            if (!level.isLoaded(floor)) return false;
            if (level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                    && level.getFluidState(floor).isEmpty()) return true;
        }
        return false;
    }

    private boolean safeEncounterTarget(BlockPos pos) {
        return player.level().isLoaded(pos)
                && insideRadius(pos.getX(), pos.getZ(), fightOrigin, MAIN_ISLAND_TARGET_RADIUS)
                && pos.getY() > player.level().getMinBuildHeight() + 2
                && pos.getY() < player.level().getMaxBuildHeight();
    }

    private boolean safeDragonTarget(EnderDragon target, boolean ranged) {
        BlockPos pos = target.blockPosition();
        if (!safeEncounterTarget(pos)) return false;
        if (ranged) return true;
        BlockPos probe = new BlockPos(pos.getX(),
                Math.min(pos.getY(), fightOrigin.getY() + 16), pos.getZ());
        return target.getPhaseManager().getCurrentPhase().isSitting()
                && insideRadius(pos.getX(), pos.getZ(), fightOrigin, 48)
                && groundWithin(probe, 48);
    }

    private List<EndCrystal> scanCrystals() {
        ClientLevel level = player.clientLevel;
        List<EndCrystal> result = new ArrayList<>(level.getEntitiesOfClass(
                EndCrystal.class, encounterBounds(level),
                crystal -> !crystal.isRemoved() && crystal.isAlive()));
        result.sort(Comparator.comparingDouble(player::distanceToSqr));
        return List.copyOf(result);
    }

    private void recordObservedCrystals() {
        for (EndCrystal crystal : loadedCrystals) {
            unresolvedCrystals.put(crystal.getUUID(), crystal.blockPosition().immutable());
        }
    }

    /**
     * Every crystal ever observed remains semantically unresolved until this parent accepts its
     * own strict attack receipt.  This closes the gap where an unselected crystal could unload
     * between observation and selection and then disappear from the aggregate entity list.
     */
    private TaskState unresolvedCrystalObservationFailure() {
        for (Map.Entry<UUID, BlockPos> entry : unresolvedCrystals.entrySet()) {
            if (findCrystal(entry.getKey()) != null) continue;
            if (activePurpose == Purpose.ATTACK_CRYSTAL
                    && entry.getKey().equals(selectedCrystalUuid)) {
                // The attack child gets one chance to produce the matching strike/despawn
                // receipt. validateActiveWork separately rejects an unloaded target sector.
                continue;
            }
            BlockPos last = entry.getValue();
            boolean loaded = player.level().isLoaded(last);
            invalidateCoverage(last);
            return failDecision(
                    loaded
                            ? "observed_crystal_changed_without_receipt"
                            : "observed_crystal_sector_unloaded",
                    loaded
                            ? "A previously observed End Crystal disappeared without this "
                                    + "task's matching first-person attack receipt. It remains "
                                    + "unresolved."
                            : "A previously observed End Crystal's sector unloaded. An empty "
                                    + "client entity list is not proof of destruction.",
                    FailureType.TARGET_LOST,
                    List.of("reload and inspect the same tower sector",
                            "repeat the bounded tower survey",
                            "retry only after every crystal state is visible"));
        }
        return null;
    }

    private List<EnderDragon> liveDragons() {
        ClientLevel level = player.clientLevel;
        return List.copyOf(level.getEntitiesOfClass(
                EnderDragon.class, encounterBounds(level),
                candidate -> !candidate.isRemoved() && candidate.isAlive()
                        && !candidate.isDeadOrDying()));
    }

    /**
     * Cover every client chunk whose horizontal footprint intersects the tower disk.  A loaded
     * entity list is only negative evidence after each of these sectors has actually entered the
     * local client's world at least once during this encounter.
     */
    private void initializeTowerCoverage() {
        requiredTowerChunks.clear();
        observedTowerChunks.clear();
        int minCx = SectionPos.blockToSectionCoord(fightOrigin.getX() - TOWER_COVERAGE_RADIUS);
        int maxCx = SectionPos.blockToSectionCoord(fightOrigin.getX() + TOWER_COVERAGE_RADIUS);
        int minCz = SectionPos.blockToSectionCoord(fightOrigin.getZ() - TOWER_COVERAGE_RADIUS);
        int maxCz = SectionPos.blockToSectionCoord(fightOrigin.getZ() + TOWER_COVERAGE_RADIUS);
        long radiusSquared = (long) TOWER_COVERAGE_RADIUS * TOWER_COVERAGE_RADIUS;
        for (int cx = minCx; cx <= maxCx; cx++) {
            int minX = cx << 4;
            int maxX = minX + 15;
            long dx = fightOrigin.getX() < minX ? (long) minX - fightOrigin.getX()
                    : fightOrigin.getX() > maxX ? (long) fightOrigin.getX() - maxX : 0L;
            for (int cz = minCz; cz <= maxCz; cz++) {
                int minZ = cz << 4;
                int maxZ = minZ + 15;
                long dz = fightOrigin.getZ() < minZ ? (long) minZ - fightOrigin.getZ()
                        : fightOrigin.getZ() > maxZ ? (long) fightOrigin.getZ() - maxZ : 0L;
                if (dx * dx + dz * dz <= radiusSquared) {
                    requiredTowerChunks.add(chunkKey(cx, cz));
                }
            }
        }
    }

    private void observeTowerCoverage() {
        for (long key : requiredTowerChunks) {
            int cx = chunkX(key);
            int cz = chunkZ(key);
            if (player.clientLevel.getChunkSource().getChunkNow(cx, cz) != null) {
                observedTowerChunks.add(key);
            }
        }
    }

    private boolean coverageComplete() {
        return !requiredTowerChunks.isEmpty()
                && observedTowerChunks.containsAll(requiredTowerChunks);
    }

    /** Pick a loaded, standable point that advances the view toward one missing tower sector. */
    private BlockPos findCoverageVantage() {
        long missing = 0L;
        boolean foundMissing = false;
        double missingDistance = Double.POSITIVE_INFINITY;
        for (long key : requiredTowerChunks) {
            if (observedTowerChunks.contains(key)) continue;
            int tx = (chunkX(key) << 4) + 8;
            int tz = (chunkZ(key) << 4) + 8;
            double distance = square((double) tx - player.getX())
                    + square((double) tz - player.getZ());
            if (distance < missingDistance) {
                missingDistance = distance;
                missing = key;
                foundMissing = true;
            }
        }
        if (!foundMissing) return null;

        int targetX = (chunkX(missing) << 4) + 8;
        int targetZ = (chunkZ(missing) << 4) + 8;
        double directionX = targetX - fightOrigin.getX();
        double directionZ = targetZ - fightOrigin.getZ();
        double length = Math.hypot(directionX, directionZ);
        if (length < 1.0D) return null;
        directionX /= length;
        directionZ /= length;

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        ClientLevel level = player.clientLevel;
        for (int radial = Math.min(TOWER_COVERAGE_RADIUS - 6, (int) Math.ceil(length));
                radial >= 12; radial -= 4) {
            for (int lateral = -12; lateral <= 12; lateral += 4) {
                int x = (int) Math.round(fightOrigin.getX() + directionX * radial
                        - directionZ * lateral);
                int z = (int) Math.round(fightOrigin.getZ() + directionZ * radial
                        + directionX * lateral);
                int cx = SectionPos.blockToSectionCoord(x);
                int cz = SectionPos.blockToSectionCoord(z);
                if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (!standable(candidate)
                        || protectedNear(candidate, PROTECTED_RADIUS)
                        || nearBreath(candidate, 7.0D)
                        || nearCrystal(candidate, CRYSTAL_BLAST_CLEARANCE)
                        || player.distanceToSqr(Vec3.atCenterOf(candidate)) < 16.0D) continue;
                double score = square((double) x - targetX) + square((double) z - targetZ)
                        + player.distanceToSqr(Vec3.atCenterOf(candidate)) * 0.05D;
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate.immutable();
                }
            }
        }
        return best;
    }

    private void invalidateCoverage(BlockPos position) {
        zeroCrystalsSince = -1L;
        if (position == null) {
            observedTowerChunks.clear();
            return;
        }
        observedTowerChunks.remove(chunkKey(
                SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getZ())));
    }

    private void selectCrystal(EndCrystal crystal) {
        UUID uuid = crystal.getUUID();
        if (!uuid.equals(selectedCrystalUuid)) crystalPositionAttempts = 0;
        selectedCrystal = crystal;
        selectedCrystalUuid = uuid;
        selectedCrystalLastPosition = crystal.blockPosition().immutable();
        selectedCrystalRuntimeId = crystal.getId();
    }

    private void clearSelectedCrystal() {
        selectedCrystal = null;
        selectedCrystalUuid = null;
        selectedCrystalLastPosition = null;
        selectedCrystalRuntimeId = -1;
        crystalPositionAttempts = 0;
    }

    private EndCrystal findCrystal(UUID uuid) {
        if (uuid == null) return null;
        for (EndCrystal crystal : loadedCrystals) {
            if (!crystal.isRemoved() && crystal.isAlive() && uuid.equals(crystal.getUUID())) {
                return crystal;
            }
        }
        return null;
    }

    private boolean safeCrystalFiringPosition(EndCrystal target) {
        if (target == null || target.isRemoved() || !target.isAlive()
                || !safePlayerPosition() || dangerousBreath() != null) return false;
        double minimum = Menace.blastSpanOf(target) + CRYSTAL_BLAST_MARGIN;
        double distanceSquared = player.distanceToSqr(target);
        return distanceSquared >= square(minimum)
                && distanceSquared <= square(CRYSTAL_SHOT_MAX_RANGE);
    }

    private boolean unsafeCrystalBlastCollateral(EndCrystal target) {
        double span = Menace.blastSpanOf(target);
        for (Entity candidate : player.clientLevel.getEntities(
                target, target.getBoundingBox().inflate(span),
                candidate -> candidate != player && candidate != dragon
                        && candidate.isAlive()
                        && (candidate instanceof LivingEntity
                                || candidate instanceof EndCrystal))) {
            if (candidate.position().distanceToSqr(target.position()) <= square(span)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSafeCrystalShot(EndCrystal target) {
        if (!safeCrystalFiringPosition(target)) return false;
        Loadout loadout = Loadout.forTarget(player, target);
        if (!loadout.hasRanged()) return false;
        boolean crossbow = loadout.ranged().stack().getItem() instanceof CrossbowItem;
        return Ballistics.findArrowShot(player.level(), player, target,
                crossbow ? 3.15D : 3.0D, 0.05D, 0.99D, 0.5D,
                CRYSTAL_SHOT_MAX_RANGE, !crossbow) != null;
    }

    /**
     * Find a first-person stance; it may only use already loaded, standable island ground.  The
     * clear-line variant is a conservative prefilter—the real projectile simulation is repeated
     * after arrival before any shot is authorized.
     */
    private BlockPos findCrystalVantage(EndCrystal target, boolean requireClearLine) {
        ClientLevel level = player.clientLevel;
        BlockPos center = target.blockPosition();
        double minimum = Menace.blastSpanOf(target) + CRYSTAL_BLAST_MARGIN;
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int radius = 14; radius <= 64; radius += 2) {
            for (int dx = -radius; dx <= radius; dx += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    if (!insideRadius(x, z, fightOrigin, SAFE_HAVEN_RADIUS)) continue;
                    int cx = SectionPos.blockToSectionCoord(x);
                    int cz = SectionPos.blockToSectionCoord(z);
                    if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos candidate = new BlockPos(x, y, z);
                    Vec3 feet = Vec3.atCenterOf(candidate);
                    double distanceSquared = feet.distanceToSqr(target.position());
                    if (distanceSquared < square(minimum)
                            || distanceSquared > square(CRYSTAL_SHOT_MAX_RANGE)
                            || player.distanceToSqr(feet) < 16.0D
                            || !standable(candidate)
                            || protectedNear(candidate, PROTECTED_RADIUS)
                            || nearBreath(candidate, 7.0D)
                            || nearCrystal(candidate, CRYSTAL_BLAST_CLEARANCE)) continue;
                    if (requireClearLine) {
                        Vec3 eye = new Vec3(candidate.getX() + 0.5D,
                                candidate.getY() + player.getEyeHeight(),
                                candidate.getZ() + 0.5D);
                        BlockHitResult hit = level.clip(new ClipContext(
                                eye, target.getBoundingBox().getCenter(),
                                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                        if (hit.getType() != HitResult.Type.MISS) continue;
                    }
                    double score = player.distanceToSqr(feet) + distanceSquared * 0.04D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate.immutable();
                    }
                }
            }
            if (best != null && radius >= 28) break;
        }
        return best;
    }

    private AABB encounterBounds(ClientLevel level) {
        return new AABB(
                fightOrigin.getX() - ENCOUNTER_SCAN_RADIUS, level.getMinBuildHeight(),
                fightOrigin.getZ() - ENCOUNTER_SCAN_RADIUS,
                fightOrigin.getX() + ENCOUNTER_SCAN_RADIUS, level.getMaxBuildHeight(),
                fightOrigin.getZ() + ENCOUNTER_SCAN_RADIUS);
    }

    private CageObservation observeVerifiedCage(EndCrystal crystal) {
        List<BlockPos> bars = new ArrayList<>();
        BlockPos center = crystal.blockPosition();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 4; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (player.level().isLoaded(pos)
                            && player.level().getBlockState(pos).is(Blocks.IRON_BARS)) {
                        bars.add(pos.immutable());
                    }
                }
            }
        }
        if (bars.size() < 8) return null;

        BlockHitResult hit = player.clientLevel.clip(new ClipContext(
                player.getEyePosition(), crystal.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos aperture = hit.getBlockPos().immutable();
        Set<BlockPos> barSet = new HashSet<>(bars);
        if (!barSet.contains(aperture)
                || !player.level().getBlockState(aperture).is(Blocks.IRON_BARS)) return null;

        // The ray-hit bar must belong to one connected enclosure, not merely share a scan cube
        // with a handful of unrelated decorative bars.
        Set<BlockPos> connected = new HashSet<>();
        List<BlockPos> frontier = new ArrayList<>();
        connected.add(aperture);
        frontier.add(aperture);
        for (int at = 0; at < frontier.size(); at++) {
            BlockPos from = frontier.get(at);
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = from.relative(direction).immutable();
                if (barSet.contains(neighbor) && connected.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
        if (connected.size() < 8) return null;

        int north = 0;
        int south = 0;
        int west = 0;
        int east = 0;
        int roof = 0;
        for (BlockPos pos : connected) {
            int dx = pos.getX() - center.getX();
            int dy = pos.getY() - center.getY();
            int dz = pos.getZ() - center.getZ();
            if (dy >= -1 && dy <= 3 && Math.abs(dx) <= 2) {
                if (dz <= -1) north++;
                if (dz >= 1) south++;
            }
            if (dy >= -1 && dy <= 3 && Math.abs(dz) <= 2) {
                if (dx <= -1) west++;
                if (dx >= 1) east++;
            }
            if (dy >= 2 && Math.abs(dx) <= 2 && Math.abs(dz) <= 2) roof++;
        }
        if (north < 2 || south < 2 || west < 2 || east < 2 || roof < 2) return null;
        return new CageObservation(List.copyOf(connected), List.of(aperture));
    }

    private TaskState startCageOpening(EndCrystal crystal, CageObservation cage) {
        if (!r.mayAlterTerrain) {
            return failDecision("cage_opening_requires_permission",
                    "A verified iron-bar cage blocks the only safe firing line, but changing "
                            + "terrain was not authorized. Nothing was altered.",
                    FailureType.TERRAIN_BLOCKED,
                    List.of("retry with may_alter_terrain=true",
                            "open one firing aperture manually",
                            "reposition until a clear shot exists"));
        }
        if (selectedCrystalUuid == null || !selectedCrystalUuid.equals(crystal.getUUID())
                || cage.opening().size() != 1) {
            return failDecision("cage_observation_changed",
                    "The verified cage observation changed before an exact opening could be "
                            + "authorized. Nothing was altered.",
                    FailureType.TARGET_LOST,
                    List.of("inspect the enclosure", "retry after it is stable"));
        }
        BlockPos opening = cage.opening().getFirst();
        if (protectedNear(opening, PROTECTED_RADIUS)) {
            return failDecision("cage_near_protected_label",
                    "The only verified cage aperture overlaps a protected remembered area; it "
                            + "was left untouched.",
                    FailureType.HAZARD,
                    List.of("revise protected_labels explicitly",
                            "handle the cage manually"));
        }
        int attempts = cageOpenings.getOrDefault(selectedCrystalUuid, 0);
        if (attempts >= MAX_CAGE_OPENINGS_PER_CRYSTAL) {
            return failDecision("cage_opening_exhausted",
                    "The same verified cage aperture resisted every bounded first-person opening "
                            + "attempt.",
                    FailureType.OUT_OF_REACH,
                    List.of("inspect the cage manually", "change tools and retry"));
        }
        cageOpenings.put(selectedCrystalUuid, attempts + 1);
        cagePlan = List.copyOf(cage.opening());

        Set<BlockPos> protectedCells = new HashSet<>();
        BlockPos center = crystal.blockPosition();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 4; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz).immutable();
                    if (!cagePlan.contains(pos)) protectedCells.add(pos);
                }
            }
        }
        for (BlockPos bar : cage.bars()) {
            if (!cagePlan.contains(bar)) protectedCells.add(bar);
        }
        cageProtectedCells = List.copyOf(protectedCells);

        if (!cageOpeningInReach()) {
            BlockPos stance = findCageBreakStance(opening);
            if (stance == null) {
                cagePlan = List.of();
                cageProtectedCells = List.of();
                return failDecision("no_natural_cage_stance",
                        "No loaded standable stance could reach the exact verified iron bar "
                                + "without changing any other terrain. Nothing was altered.",
                        FailureType.TERRAIN_BLOCKED,
                        List.of("create a safe natural approach manually",
                                "open one firing aperture manually",
                                "retry from stable tower ground"));
            }
            MoveToTaskRecord move = new MoveToTaskRecord(
                    childId("cage-stance"), childDeadline(2L * 60L * 20L),
                    (double) stance.getX(), (double) stance.getY(), (double) stance.getZ(),
                    null, false);
            return startChild(move, Purpose.POSITION_CAGE);
        }
        return startVerifiedCageBuild();
    }

    private BlockPos findCageBreakStance(BlockPos opening) {
        ClientLevel level = player.clientLevel;
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -5; dy <= 3; dy++) {
                    BlockPos candidate = opening.offset(dx, dy, dz);
                    if (!standable(candidate)
                            || !insideRadius(candidate.getX(), candidate.getZ(),
                                    fightOrigin, SAFE_HAVEN_RADIUS)
                            || protectedNear(candidate, PROTECTED_RADIUS)
                            || nearBreath(candidate, 7.0D)) continue;
                    Vec3 eye = new Vec3(candidate.getX() + 0.5D,
                            candidate.getY() + player.getEyeHeight(),
                            candidate.getZ() + 0.5D);
                    if (eye.distanceToSqr(Vec3.atCenterOf(opening)) > 20.25D) continue;
                    BlockHitResult hit = level.clip(new ClipContext(
                            eye, Vec3.atCenterOf(opening),
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                    if (hit.getType() != HitResult.Type.BLOCK
                            || !hit.getBlockPos().equals(opening)) continue;
                    double score = player.distanceToSqr(Vec3.atCenterOf(candidate));
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean cageOpeningInReach() {
        return cagePlan.size() == 1
                && player.level().isLoaded(cagePlan.getFirst())
                && player.getEyePosition().distanceToSqr(
                        Vec3.atCenterOf(cagePlan.getFirst())) <= 20.25D;
    }

    private TaskState startVerifiedCageBuild() {
        if (cagePlan.size() != 1
                || !player.level().isLoaded(cagePlan.getFirst())
                || !player.level().getBlockState(cagePlan.getFirst()).is(Blocks.IRON_BARS)
                || !cageOpeningInReach()) {
            cagePlan = List.of();
            cageProtectedCells = List.of();
            return failDecision("cage_build_precondition_changed",
                    "The exact verified aperture was no longer a reachable iron bar before the "
                            + "terrain-changing child started. Nothing was altered.",
                    FailureType.TARGET_LOST,
                    List.of("inspect the enclosure", "retry after it is stable"));
        }
        List<BuildTaskRecord.Target> targets = cagePlan.stream()
                .map(pos -> new BuildTaskRecord.Target(
                        Blocks.AIR.defaultBlockState(), Items.AIR, pos,
                        "verified cage aperture", null, null, null))
                .toList();
        BuildTaskRecord build = new BuildTaskRecord(
                childId("cage"), childDeadline(2L * 60L * 20L),
                targets, true, false, false);
        return startChild(build, Purpose.OPEN_CAGE);
    }

    private boolean resolveProtectedLabels() {
        String dimension = player.level().dimension().location().toString();
        for (String label : r.protectedLabels) {
            IntentRuntime.Landmark landmark = IntentRuntime.get().landmark(label);
            if (landmark == null) {
                failDecision("unknown_protected_label",
                        "A protected label is not remembered; encounter safety cannot be proven.",
                        FailureType.TARGET_LOST,
                        List.of("remember or resolve the protected place first",
                                "retry with unambiguous protected_labels"));
                return false;
            }
            Goal.WorldPosition position = landmark.position();
            if (position.dimension() != null && !position.dimension().isBlank()
                    && !position.dimension().equals(dimension)) {
                failDecision("protected_label_other_dimension",
                        "A protected label belongs to another dimension.",
                        FailureType.TARGET_LOST,
                        List.of("use same-dimension protected labels", "revise the request"));
                return false;
            }
            protectedAnchors.add(new BlockPos(position.x(), position.y(), position.z()));
        }
        return true;
    }

    private boolean protectedNear(BlockPos pos, int radius) {
        long limit = (long) radius * radius;
        for (BlockPos anchor : protectedAnchors) {
            long dx = (long) pos.getX() - anchor.getX();
            long dy = (long) pos.getY() - anchor.getY();
            long dz = (long) pos.getZ() - anchor.getZ();
            if (dx * dx + dy * dy + dz * dz <= limit) return true;
        }
        return false;
    }

    private boolean protectedNear(Entity entity, int radius) {
        AABB box = entity.getBoundingBox();
        double limit = square(radius);
        for (BlockPos anchor : protectedAnchors) {
            double closestX = Math.max(box.minX, Math.min(anchor.getX() + 0.5D, box.maxX));
            double closestY = Math.max(box.minY, Math.min(anchor.getY() + 0.5D, box.maxY));
            double closestZ = Math.max(box.minZ, Math.min(anchor.getZ() + 0.5D, box.maxZ));
            if (square(closestX - (anchor.getX() + 0.5D))
                    + square(closestY - (anchor.getY() + 0.5D))
                    + square(closestZ - (anchor.getZ() + 0.5D)) <= limit) return true;
        }
        return false;
    }

    private boolean nearBreath(BlockPos pos, double margin) {
        AABB scan = new AABB(pos).inflate(32.0D);
        for (AreaEffectCloud cloud : player.clientLevel.getEntitiesOfClass(
                AreaEffectCloud.class, scan,
                candidate -> !candidate.isRemoved()
                        && (candidate.getOwner() instanceof EnderDragon
                                || candidate.getParticle().getType()
                                        == ParticleTypes.DRAGON_BREATH))) {
            if (Vec3.atCenterOf(pos).distanceToSqr(cloud.position())
                    <= square(cloud.getRadius() + margin)) return true;
        }
        return false;
    }

    private boolean nearCrystal(BlockPos pos, double margin) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (EndCrystal crystal : loadedCrystals) {
            if (!crystal.isRemoved()
                    && center.distanceToSqr(crystal.position()) <= square(margin)) return true;
        }
        return false;
    }

    private boolean hasConfirmedDeathState() {
        return dragon != null && (dragonRemovalObserved
                || (deathPhaseObserved && (dragon.isRemoved() || dragon.isDeadOrDying()
                        || dragon.getHealth() <= 0.0F)));
    }

    private PortalObservation scanExitPortal() {
        if (!portalIndexRegistered || !player.clientLevel.isLoaded(fightOrigin)) {
            return new PortalObservation(false, false);
        }
        TargetIndex.Result result = TargetIndex.query(
                player.clientLevel, fightOrigin, List.of(Blocks.END_PORTAL), 1, 1, 16);
        boolean present = !result.hits().isEmpty();
        return new PortalObservation(present || result.complete(), present);
    }

    private void observePortalBaseline() {
        if (portalBaselineKnown || deathPhaseObserved || dragonRemovalObserved
                || phase == Phase.CONFIRM) return;
        long now = player.level().getGameTime();
        if (lastPortalScanAt != Long.MIN_VALUE && now - lastPortalScanAt < 20L) return;
        lastPortalScanAt = now;
        PortalObservation observation = scanExitPortal();
        if (observation.known()) {
            portalBaselineKnown = true;
            portalPresentAtStart = observation.present();
        }
    }

    private void observeExitPortal() {
        if (!portalBaselineKnown || portalPresentAtStart) return;
        PortalObservation observation = scanExitPortal();
        if (observation.known() && observation.present()) exitPortalObserved = true;
    }

    private TaskState startChild(TaskRecord record, Purpose purpose) {
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
        activeAttackRecord = record instanceof AttackTaskRecord attack ? attack : null;
        if (purpose == Purpose.OPEN_CAGE
                && activeChild instanceof BuildCompanionTask build) {
            build.protectNavigationCells(cageProtectedCells);
        }
        activePurpose = purpose;
        return TaskState.RUNNING;
    }

    private void cancelActiveChild(boolean countInterruption) {
        if (activeChild == null) return;
        Task child = activeChild;
        child.stop(player, Task.StopReason.REPLACED);
        try {
            child.result(TaskState.CANCELLED);
        } catch (RuntimeException ignored) {
            // Parent cleanup still releases all first-person controls.
        }
        if (countInterruption && activePurpose != null && !activePurpose.recovery()) {
            interruptedCombatChildren++;
        }
        activeChild = null;
        activeRecord = null;
        activeAttackRecord = null;
        activePurpose = null;
        activeFood = null;
    }

    private static boolean transientExecutionFailure(FailureType type) {
        return type == FailureType.OCCLUDED
                || type == FailureType.BOXED_IN
                || type == FailureType.NO_PATH
                || type == FailureType.OUT_OF_REACH
                || type == FailureType.STANCE_DUD;
    }

    private TaskState childFailureDecision(
            String code, String message, FailureType type, List<String> options) {
        return failDecision(code, message,
                type == null ? FailureType.UNKNOWN : type, options);
    }

    private String childId(String purpose) {
        return r.getToolCallId() + "-dragon-" + purpose + "-" + (++childSerial);
    }

    private long childDeadline(long ticks) {
        return Math.min(r.getDeadlineGameTime(), player.level().getGameTime() + ticks);
    }

    private TaskState failDecision(
            String code, String message, FailureType type, List<String> options) {
        failureCode = code;
        recoveryOptions = List.copyOf(options);
        fail(message, type == null ? FailureType.UNKNOWN : type);
        return TaskState.FAILED;
    }

    private static boolean insideRadius(int x, int z, BlockPos center, int radius) {
        long dx = (long) x - center.getX();
        long dz = (long) z - center.getZ();
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private static long chunkKey(int x, int z) {
        return (x & 0xffffffffL) | ((long) z << 32);
    }

    private static int chunkX(long key) { return (int) key; }

    private static int chunkZ(long key) { return (int) (key >> 32); }

    private static double square(double value) { return value * value; }

    @Override protected void cleanup() {
        cancelActiveChild(false);
        if (portalIndexRegistered) {
            TargetIndex.unregister(player.clientLevel, List.of(Blocks.END_PORTAL));
            portalIndexRegistered = false;
        }
        super.cleanup();
    }

    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase.name().toLowerCase());
        data.put("loaded_crystals_observed", loadedCrystals.size());
        data.put("initial_loaded_crystals", initialLoadedCrystals);
        data.put("maximum_loaded_crystals_observed", maximumLoadedCrystals);
        data.put("crystals_confirmed_destroyed", crystalsConfirmedDestroyed);
        data.put("crystals_still_unresolved", unresolvedCrystals.size());
        data.put("cage_openings_confirmed", cageOpeningsConfirmed);
        data.put("dragon_state", dragonState());
        if (dragon != null && !dragon.isRemoved()) {
            data.put("dragon_health_observed", Math.max(0.0F, dragon.getHealth()));
        }
        data.put("dragon_attack_rounds", dragonAttackRounds);
        data.put("dragon_healing_observations", dragonHealingObservations);
        data.put("healing_triggered_surveys", healingSurveyAttempts);
        data.put("tower_coverage_required", requiredTowerChunks.size());
        data.put("tower_coverage_observed", observedTowerChunks.size());
        data.put("tower_coverage_complete", coverageComplete());
        data.put("tower_survey_moves", surveyMoves);
        data.put("recovery_actions", recoveryActions);
        data.put("food_consumptions_confirmed", foodsConsumed);
        data.put("rare_consumables_used", rareConsumablesConsumed);
        data.put("breath_avoidances_confirmed", breathAvoidances);
        data.put("combat_children_interrupted_for_safety", interruptedCombatChildren);
        data.put("death_phase_observed", deathPhaseObserved);
        data.put("dragon_removal_observed", dragonRemovalObserved);
        data.put("exit_portal_transition_observed", exitPortalObserved);
        if (failureCode != null) {
            data.put("decision", Map.of(
                    "required", true, "reason_code", failureCode,
                    "recovery_options", recoveryOptions));
        }
        return data;
    }

    private String dragonState() {
        if (dragonRemovalObserved || (dragon != null && dragon.isRemoved())) {
            return deathPhaseObserved || exitPortalObserved
                    ? "removed_after_death_evidence" : "removed_unconfirmed";
        }
        if (dragon == null) return "not_loaded_unconfirmed";
        if (deathPhaseObserved) return "death_phase_observed";
        if (!dragon.isAlive() || dragon.isDeadOrDying()) return "dead_state_observed";
        return dragon.getPhaseManager().getCurrentPhase().isSitting()
                ? "alive_perched" : "alive_airborne";
    }

    @Override protected String successMessage() {
        return "completed the observed Ender Dragon encounter: crystals were handled one at a "
                + "time, and real dragon death/removal was corroborated by death-phase or "
                + "exit-portal evidence";
    }

    @Override protected String timeoutMessage() {
        return "the Ender Dragon encounter timed out in phase " + phase.name().toLowerCase()
                + "; no unverified victory was reported";
    }

    @Override protected String cancelledMessage() {
        return "the Ender Dragon encounter was interrupted in phase "
                + phase.name().toLowerCase() + "; all first-person controls were released";
    }
}
