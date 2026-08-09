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
