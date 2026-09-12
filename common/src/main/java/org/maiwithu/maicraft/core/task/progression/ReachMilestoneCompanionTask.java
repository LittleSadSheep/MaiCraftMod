// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.progression;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.structure.PhysicalStructureSearchCompanionTask;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * Reconciliatory progression state machine.  It owns exactly one typed child at a time and
 * reconstructs every phase from live client facts, so a LocalPlayer handoff can discard this
 * instance without losing logical progress.
 */
public final class ReachMilestoneCompanionTask
        extends AbstractCompanionTask<ReachMilestoneTaskRecord> {
    /** Fresh liveness granted when a new typed phase begins; child progress may renew it again. */
    private static final long CHILD_PROGRESS_LEASE_TICKS = 3L * 60L * 20L;
    private static final List<Block> INDEXED_BLOCKS =
            List.of(Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL);

    private enum Phase {
        RECONCILE("reconcile"),
        PREPARE_NAVIGATION("prepare_navigation"),
        TRAVEL_DIMENSION("travel_dimension"),
        LOCATE_STRONGHOLD("locate_stronghold"),
        PREPARE_COMBAT("prepare_combat"),
        FIGHT_DRAGON("fight_dragon"),
        SEARCH_ELYTRA("search_elytra"),
        BLOCKED("blocked"),
        COMPLETE("complete");

        private final String id;
        Phase(String id) { this.id = id; }
    }

    private enum Purpose {
        ACQUIRE, EQUIP, STRUCTURE_SEARCH, STRONGHOLD_APPROACH,
        DIMENSION_TRAVEL, SUPPLY_DIMENSION_TRAVEL, DRAGON_FIGHT, ELYTRA_SEARCH
    }

    private final ProgressionChildFactory children;
    private ClientLevel indexedLevel;
    private TaskRecord activeRecord;
    private Task activeChild;
    private Purpose activePurpose;
    private ProgressionRequirementProfile.Requirement activeRequirement;
    private long activeStartFingerprint;
    private BlockPos strongholdAnchor;
    private Phase phase = Phase.RECONCILE;
    private String issueCode;
    private List<String> recoveryOptions = List.of();
    private String completionFact;

    public ReachMilestoneCompanionTask(LocalPlayer player, ReachMilestoneTaskRecord record) {
        super(player, record);
        this.children = new ProgressionChildFactory(player, record);
    }

    @Override
    protected void onStart() {
        indexedLevel = player.clientLevel;
        TargetIndex.register(indexedLevel, INDEXED_BLOCKS);
    }

    @Override
    protected TaskState onTick() {
        ProgressionFacts facts = ProgressionFacts.observe(player, strongholdAnchor);
        if (facts.milestoneDone(r.milestone)) return complete(facts);
        if (activeChild != null) return tickChild(facts);
        phase = Phase.RECONCILE;
        return switch (r.milestone) {
            case NETHER -> planNether(facts);
            case STRONGHOLD -> planStronghold(facts);
            case DEFEAT_DRAGON -> planDragon(facts, false);
            case ELYTRA -> planElytra(facts);
        };
    }

    private TaskState tickChild(ProgressionFacts before) {
        Task child = activeChild;
        TaskRecord record = activeRecord;
        Purpose purpose = activePurpose;
        ProgressionRequirementProfile.Requirement requirement = activeRequirement;
        TaskState terminal = runChild(child);
        if (terminal == null) {
            // Structure search, acquisition, movement and encounter children renew their own
            // records only from verified progress.  Carry that lease through the semantic root;
            // otherwise a healthy multi-hour milestone can be killed by its original wall clock.
            if (record != null) r.extendDeadlineTo(record.getDeadlineGameTime());
            return TaskState.RUNNING;
        }

        BlockPos discoveredAnchor = null;
        if (purpose == Purpose.STRUCTURE_SEARCH
                && child instanceof PhysicalStructureSearchCompanionTask search) {
            discoveredAnchor = search.verifiedEvidenceAnchor();
        }
        TaskResult childResult = child.result(terminal);
        clearActive();
        if (discoveredAnchor != null) strongholdAnchor = discoveredAnchor.immutable();

        boolean childSucceeded = terminal == TaskState.SUCCESS
                && childResult != null && childResult.success();
        if (!childSucceeded) {
            return handleChildFailure(purpose, requirement, record, childResult, before);
        }

        if (purpose == Purpose.DRAGON_FIGHT
                && bool(childResult.data(), "exit_portal_transition_observed")) {
            ProgressionFacts.markDragonTransition(player);
        }

        ProgressionFacts after = ProgressionFacts.observe(player, strongholdAnchor);
        if (after.milestoneDone(r.milestone)) return complete(after);

        if (purpose == Purpose.STRUCTURE_SEARCH) {
            if (strongholdAnchor == null) {
                return block("stronghold_evidence_not_retained", FailureType.TARGET_LOST,
                        "The physical search ended without live portal-frame evidence to approach.",
                        List.of("repeat the bounded physical search"));
            }
            TaskRecord approach = children.approachStronghold(strongholdAnchor);
            if (approach == null) {
                return block("stronghold_approach_unavailable", FailureType.NO_PATH,
                        "Portal-frame evidence is loaded, but no real standable approach cell is visible.",
                        List.of("load more of the stronghold interior",
                                "allow terrain alteration only if changing this area is acceptable"));
            }
            return start(approach, Purpose.STRONGHOLD_APPROACH, null, after,
                    Phase.LOCATE_STRONGHOLD);
        }

        // A successful child that neither changed an observable fact nor completed a milestone
        // must not be reissued forever. Dimension handoff success normally destroys this object.
        if (after.fingerprint() == activeStartFingerprint
                && purpose != Purpose.DIMENSION_TRAVEL
                && purpose != Purpose.SUPPLY_DIMENSION_TRAVEL) {
            return block("no_state_change", FailureType.UNKNOWN,
                    "The internal phase reported success, but no live inventory, equipment, body or world fact changed.",
                    List.of("inspect the current world state before retrying"));
        }
        return TaskState.RUNNING;
    }

    private TaskState planNether(ProgressionFacts facts) {
        phase = Phase.TRAVEL_DIMENSION;
        String destination = ProgressionFacts.END.equals(facts.dimension())
                ? ProgressionFacts.OVERWORLD : ProgressionFacts.NETHER;
        return start(children.travel(destination), Purpose.DIMENSION_TRAVEL,
                null, facts, phase);
    }

    private TaskState planStronghold(ProgressionFacts facts) {
        if (!ProgressionFacts.OVERWORLD.equals(facts.dimension())) {
            if (ProgressionFacts.NETHER.equals(facts.dimension())) {
                // A prior acquire child can legitimately move the semantic root here for blaze
                // evidence. Rebuild from live inventory and finish that dimension-local need
                // before returning, otherwise handoff reconstruction would bounce forever.
                TaskState localSupply = ensureRequirements(
                        facts, navigationRequirements(),
                        Phase.PREPARE_NAVIGATION);
                if (localSupply != null) return localSupply;
            }
            phase = Phase.TRAVEL_DIMENSION;
            return start(children.travel(ProgressionFacts.OVERWORLD),
                    Purpose.DIMENSION_TRAVEL, null, facts, phase);
        }
        if (facts.strongholdFrameLoaded() && facts.strongholdAnchorInternal() != null) {
            strongholdAnchor = facts.strongholdAnchorInternal();
            TaskRecord approach = children.approachStronghold(strongholdAnchor);
            if (approach == null) {
                return block("stronghold_approach_unavailable", FailureType.NO_PATH,
                        "Loaded portal-frame evidence has no verified standable approach.",
                        List.of("load the nearby stronghold interior"));
            }
            return start(approach, Purpose.STRONGHOLD_APPROACH, null, facts,
                    Phase.LOCATE_STRONGHOLD);
        }
        TaskState prerequisite = ensureRequirements(
                facts, navigationRequirements(),
                Phase.PREPARE_NAVIGATION);
        if (prerequisite != null) return prerequisite;
        if (!r.allowRareConsumables) {
            return block("rare_consumable_permission_required", FailureType.NO_MATERIAL,
                    "Physical stronghold guidance may consume Eyes of Ender and needs explicit rare-consumable permission.",
                    List.of("retry with allow_rare_consumables=true",
                            "stop before consuming navigation resources"));
        }
        return start(children.stronghold(), Purpose.STRUCTURE_SEARCH, null, facts,
                Phase.LOCATE_STRONGHOLD);
    }

    private TaskState planDragon(ProgressionFacts facts, boolean forElytra) {
        if (!r.allowCombat) {
            return block("combat_permission_required", FailureType.UNSUPPORTED,
                    "This milestone can destroy crystals and kill the Ender Dragon; combat permission is required.",
                    List.of("retry with allow_combat=true", "stop before the encounter"));
        }
        TaskState loadout = ensureRequirements(
                facts, ProgressionRequirementProfile.dragonLoadout(), Phase.PREPARE_COMBAT);
        if (loadout != null) return loadout;

        if (ProgressionFacts.NETHER.equals(facts.dimension())) {
            TaskState localNavigationSupply = ensureRequirements(
                    facts, navigationRequirements(),
                    Phase.PREPARE_NAVIGATION);
            if (localNavigationSupply != null) return localNavigationSupply;
        }

        if (ProgressionFacts.END.equals(facts.dimension())) {
            if (facts.dragonState() == ProgressionFacts.DragonState.UNKNOWN) {
                return block("dragon_state_unknown", FailureType.TARGET_LOST,
                        "No live dragon is visible, but there is no verified exit-portal or fight transition evidence.",
                        List.of("load and inspect the central End arena",
                                "do not treat an empty entity list as victory"));
            }
            if (facts.dragonState() == ProgressionFacts.DragonState.ALIVE) {
                return start(children.dragonFight(), Purpose.DRAGON_FIGHT, null, facts,
                        Phase.FIGHT_DRAGON);
            }
            return forElytra ? TaskState.RUNNING : complete(facts);
        }

        if (!ProgressionFacts.OVERWORLD.equals(facts.dimension())) {
            return start(children.travel(ProgressionFacts.OVERWORLD),
                    Purpose.DIMENSION_TRAVEL, null, facts, Phase.TRAVEL_DIMENSION);
        }
        if (!facts.strongholdApproachVerified()) {
            return planStronghold(facts);
        }
        if (!facts.activeEndPortalNearby() && !r.preparePortal) {
            return block("portal_preparation_permission_required", FailureType.UNSUPPORTED,
                    "The stronghold is reached, but no active End portal is observed and prepare_portal is disabled.",
                    List.of("retry with prepare_portal=true and allow_rare_consumables=true",
                            "resume after an active portal is visibly loaded"));
        }
        return start(children.travel(ProgressionFacts.END), Purpose.DIMENSION_TRAVEL,
                null, facts, Phase.TRAVEL_DIMENSION);
    }

    private TaskState planElytra(ProgressionFacts facts) {
        if (!ProgressionFacts.END.equals(facts.dimension())
                || facts.dragonState() != ProgressionFacts.DragonState.DEFEATED_CORROBORATED) {
            return planDragon(facts, true);
        }
        TaskState traversal = ensureRequirements(
                facts, ProgressionRequirementProfile.elytraTraversal(), Phase.SEARCH_ELYTRA);
        if (traversal != null) return traversal;
        if (!r.allowRareConsumables) {
            return block("rare_consumable_permission_required", FailureType.NO_MATERIAL,
                    "End Gateway traversal may consume an ender pearl and needs explicit rare-consumable permission.",
                    List.of("retry with allow_rare_consumables=true",
                            "stop before consuming a gateway item"));
        }
        return start(children.elytra(), Purpose.ELYTRA_SEARCH, null, facts,
                Phase.SEARCH_ELYTRA);
    }

    /** @return null when all requirements are live-satisfied; otherwise a running/terminal state. */
    private TaskState ensureRequirements(
            ProgressionFacts facts,
            List<ProgressionRequirementProfile.Requirement> requirements,
            Phase aggregatePhase) {
        for (ProgressionRequirementProfile.Requirement requirement : requirements) {
            if (facts.requirementSatisfied(player, requirement)) continue;
            if (requirement.equipSlot() != null
                    && facts.mainInventoryCount(player, requirement.alternatives()) > 0) {
                TaskRecord equip = children.equip(facts, requirement);
                if (equip == null) {
                    return block("equipment_fact_unresolved", FailureType.NO_MATERIAL,
                            "Protective equipment is expected in the main inventory but no concrete carried alternative was verified.",
                            List.of("recheck the main inventory"));
                }
                return start(equip, Purpose.EQUIP, requirement, facts, aggregatePhase);
            }
            return start(children.acquire(requirement), Purpose.ACQUIRE,
                    requirement, facts, aggregatePhase);
        }
        return null;
    }

    private TaskState handleChildFailure(
            Purpose purpose,
            ProgressionRequirementProfile.Requirement requirement,
            TaskRecord record,
            TaskResult result,
            ProgressionFacts facts) {
        String childIssue = issue(result);
        if ((purpose == Purpose.ACQUIRE || purpose == Purpose.DIMENSION_TRAVEL
                || purpose == Purpose.SUPPLY_DIMENSION_TRAVEL) && "requires_dimension".equals(childIssue)) {
            String destination = chooseRequiredDimension(result, facts.dimension());
            if (destination == null) {
                return block("progression_supply_requires_dimension", FailureType.NO_MATERIAL,
                        "A progression prerequisite requires another dimension, but no safe supported destination can be selected from the typed evidence.",
                        List.of("travel through an already-active portal, then resume"));
            }
            if (ProgressionFacts.END.equals(facts.dimension())) {
                return block("progression_supply_requires_dimension", FailureType.NO_MATERIAL,
                        "A progression prerequisite is unavailable in the current End body; this task will not invent or activate an exit route.",
                        List.of("leave through an already-active exit portal, then resume"));
            }
            if (ProgressionFacts.END.equals(destination) && !facts.activeEndPortalNearby() && !r.preparePortal) {
                return block("portal_preparation_permission_required", FailureType.UNSUPPORTED,
                        "A prerequisite points to the End, but no active portal is observed and prepare_portal is disabled.",
                        List.of("retry with prepare_portal=true and allow_rare_consumables=true"));
            }
            return start(children.travel(destination), Purpose.SUPPLY_DIMENSION_TRAVEL,
                    requirement, facts, Phase.TRAVEL_DIMENSION);
        }
        if ((purpose == Purpose.DIMENSION_TRAVEL
                || purpose == Purpose.SUPPLY_DIMENSION_TRAVEL)
                && "portal_not_observed".equals(childIssue) && !r.preparePortal) {
            String destination = record instanceof org.maiwithu.maicraft.core.task.dimension.DimensionTravelTaskRecord travel
                    ? travel.destinationDimension : "";
            if (ProgressionFacts.END.equals(destination)
                    || ProgressionFacts.END.equals(facts.dimension())) {
                return block("end_portal_activation_unavailable", FailureType.UNSUPPORTED,
                        "No active End portal is observed. Entry activation needs prepare_portal and rare-consumable permission; exit portals require the dragon encounter.",
                        List.of("enable entry preparation or complete the encounter that creates the exit"));
            }
            return block("portal_lifecycle_unavailable", FailureType.UNSUPPORTED,
                    "No active Nether portal is observed and prepare_portal is disabled.",
                    List.of("retry with prepare_portal=true and may_alter_terrain=true"));
        }
        if ("rare_consumable_permission_required".equals(childIssue)) {
            return block(childIssue, FailureType.NO_MATERIAL,
                    "The current phase needs explicit permission to consume a rare progression resource.",
                    List.of("retry with allow_rare_consumables=true", "stop before consumption"));
        }
        String aggregate = switch (purpose) {
            case ACQUIRE, EQUIP -> "progression_prerequisite_unavailable";
            case STRUCTURE_SEARCH, STRONGHOLD_APPROACH -> "stronghold_progress_unavailable";
            case DIMENSION_TRAVEL, SUPPLY_DIMENSION_TRAVEL -> "dimension_transition_unavailable";
            case DRAGON_FIGHT -> "dragon_encounter_unresolved";
            case ELYTRA_SEARCH -> "elytra_search_unresolved";
        };
        return block(aggregate, lastFailure(),
                "The current progression phase stopped on typed issue '" + childIssue
                        + "'; no broader permission or blind retry was inferred.",
                List.of("review the reported phase and world state before retrying"));
    }

    private TaskState start(
            TaskRecord record,
            Purpose purpose,
            ProgressionRequirementProfile.Requirement requirement,
            ProgressionFacts facts,
            Phase nextPhase) {
        long freshLease = player.level().getGameTime() + CHILD_PROGRESS_LEASE_TICKS;
        record.extendDeadlineTo(freshLease);
        r.extendDeadlineTo(record.getDeadlineGameTime());
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
        activePurpose = purpose;
        activeRequirement = requirement;
        activeStartFingerprint = facts.fingerprint();
        phase = nextPhase;
        return TaskState.RUNNING;
    }

    private List<ProgressionRequirementProfile.Requirement> navigationRequirements() {
        if (!r.preparePortal || r.milestone == ReachMilestoneTaskRecord.Milestone.STRONGHOLD)
            return ProgressionRequirementProfile.strongholdNavigation();
        // Retain the navigation reserve plus up to twelve insertions before leaving a supply dimension.
        var navigation = ProgressionRequirementProfile.strongholdNavigation().getFirst();
        return List.of(new ProgressionRequirementProfile.Requirement("portal_eye_reserve", navigation.alternatives(),
                16, null, navigation.hostileHuntAllowed(), navigation.sourceHint()));
    }

    private void clearActive() {
        activeRecord = null;
        activeChild = null;
        activePurpose = null;
        activeRequirement = null;
    }

    private TaskState complete(ProgressionFacts facts) {
        phase = Phase.COMPLETE;
        completionFact = facts.completionFact(r.milestone);
        return TaskState.SUCCESS;
    }

    private TaskState block(
            String code, FailureType type, String message, List<String> recoveries) {
        phase = Phase.BLOCKED;
        issueCode = code;
        recoveryOptions = List.copyOf(recoveries);
        fail(message, type == null ? FailureType.UNKNOWN : type);
        return TaskState.FAILED;
    }

    @Override
    protected void cleanup() {
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            try {
                activeChild.result(TaskState.CANCELLED);
            } catch (RuntimeException ignored) {
                // The parent still releases its index and first-person controls below.
            }
        }
        clearActive();
        if (indexedLevel != null) TargetIndex.unregister(indexedLevel, INDEXED_BLOCKS);
        indexedLevel = null;
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("milestone", r.milestone.id());
        data.put("aggregate_stage", phase.id);
        data.put("completed", phase == Phase.COMPLETE);
        if (completionFact != null) data.put("completion_fact", completionFact);
        if (issueCode != null) {
            data.put("issue_code", issueCode);
            data.put("requires_decision", true);
            data.put("recovery_options", recoveryOptions);
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return "Verified progression milestone " + r.milestone.id() + ".";
    }

    @Override
    protected String timeoutMessage() {
        return "Progression toward " + r.milestone.id()
                + " stopped making verifiable progress in phase " + phase.id + ".";
    }

    private static boolean bool(Map<String, Object> data, String key) {
        return data != null && Boolean.TRUE.equals(data.get(key));
    }

    private static String issue(TaskResult result) {
        if (result == null || result.data() == null) return "child_result_missing";
        Object issue = result.data().get("issue_code");
        if (issue != null) return issue.toString();
        Object failure = result.data().get("failure_type");
        if (failure != null && "requires_dimension".equals(failure.toString())) {
            return "requires_dimension";
        }
        for (String nestedKey : List.of("decision", "recovery_options")) {
            Object nested = result.data().get(nestedKey);
            if (nested instanceof Map<?, ?> map && map.get("reason_code") != null) {
                return map.get("reason_code").toString();
            }
        }
        return failure == null ? "unclassified_child_failure" : failure.toString();
    }

    private static String chooseRequiredDimension(TaskResult result, String current) {
        if (result == null || result.data() == null) return null;
        Object raw = result.data().get("allowed_dimensions");
        if (!(raw instanceof Collection<?> values)) return null;
        for (Object value : values) {
            if (value == null) continue;
            String dimension = value.toString().strip().toLowerCase(Locale.ROOT);
            if (!dimension.equals(current) && List.of(
                    ProgressionFacts.OVERWORLD,
                    ProgressionFacts.NETHER,
                    ProgressionFacts.END).contains(dimension)) {
                // DimensionTravel intentionally has no direct Nether<->End route.
                if ((ProgressionFacts.NETHER.equals(current)
                        && ProgressionFacts.END.equals(dimension))
                        || (ProgressionFacts.END.equals(current)
                        && ProgressionFacts.NETHER.equals(dimension))) {
                    return ProgressionFacts.OVERWORLD;
                }
                return dimension;
            }
        }
        return null;
    }
}
