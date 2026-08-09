package org.maiwithu.maicraft.core.task.fish;
import org.maiwithu.maicraft.core.FailureType;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.mixin.FishingHookAccessor;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.Precondition;
import org.maiwithu.maicraft.entity.InputDriver;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tick-driven vanilla fishing from a nearby water surface. */
public final class FishCompanionTask extends AbstractCompanionTask<FishTaskRecord> {

    private enum Phase { POSITION, PREPARE, AIM, WAIT, COLLECT, COOLDOWN }

    private record FishingSetup(BlockPos stance, BlockPos water) {}

    private static final int STANCE_SEARCH_RADIUS = 12;
    private static final int STANCE_SEARCH_Y = 4;
    private static final int MAX_STANCE_CHECKS = 256;
    private static final int MAX_POSITION_FAILURES = 3;
    private static final double NAV_SPEED = 1.0;

    private static final int CAST_SEARCH_RADIUS = 10;
    private static final int CAST_SEARCH_Y = 4;
    private static final double MIN_CAST_DISTANCE = 4.0;
    private static final double IDEAL_CAST_DISTANCE = 6.0;
    private static final double WATER_SURFACE_OFFSET = 0.85;
    private static final int AIM_TICKS = 3;
    private static final int CAST_SETTLE_TIMEOUT = 5 * 20;
    private static final int CAST_LIFETIME = 60 * 20;
    private static final int COOLDOWN_TICKS = 10;
    private static final int MAX_FAILED_CASTS = 5;

    /** Vanilla reels the loot toward the player, but terrain can stop it short. */
    private static final double LOOT_SEARCH_RADIUS = 18.0;
    private static final double PICKUP_REACH_SQR = 1.5;
    private static final int LOOT_DISCOVERY_TICKS = 10;
    /** Let vanilla's reel impulse bring the catch back before chasing it. */
    private static final int LOOT_RETURN_GRACE_TICKS = 20;
    private static final int LOOT_CLOSE_WAIT_TICKS = 20;
    private static final int LOOT_COLLECTION_TIMEOUT = 20 * 20;

    private static final double FISHING_DRAG = 0.92;
    private static final double FISHING_GRAVITY = 0.03;
    private static final int MAX_FLIGHT_TICKS = 80;

    private final Set<BlockPos> rejectedStances = new HashSet<>();
    private final Set<BlockPos> rejectedTargets = new HashSet<>();
    /** 本次收线的战果簿记:先快照现场旧物,差集出的新掉落才算这一竿的。 */
    private final org.maiwithu.maicraft.core.task.base.DropTracker caught =
            new org.maiwithu.maicraft.core.task.base.DropTracker();
    /** 判定够不着而放弃的战果 id(留在地上,不再追)。 */
    private final Set<Integer> abandonedLoot = new HashSet<>();

    private Phase phase = Phase.POSITION;
    private BlockPos stance;
    private BlockPos target;
    private int phaseTicks;
    private int failedCasts;
    private int positionFailures;
    private ItemEntity lootTarget;
    private int lootCloseTicks;
    private int unreachableLoot;
    private final FirstPersonActionGate rodSelection = new FirstPersonActionGate();
    private NativeActionReceipt rodReceipt;
    private boolean catchCounted;

    public FishCompanionTask(LocalPlayer player, FishTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(() -> findRodSlot() >= 0 ? null
                : new Precondition.Failure("fish needs a fishing rod in inventory",
                        FailureType.WRONG_TOOL));
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) return TaskState.CANCELLED;

        InputDriver.halt(player);
        if (phase == Phase.POSITION) return positionForFishing();
        if (phase == Phase.COLLECT) return collectCaughtLoot();
        // requested == 0 = 主人没说钓几条 —— 这一行永远不成立,任务就是常驻的:
        // 一直钓下去,直到主人换掉她手上的活。同一段逻辑,两种用法。
        if (r.requested > 0 && r.caught() >= r.requested) return TaskState.SUCCESS;

        int rodSlot = findRodSlot();
        if (rodSlot < 0) {
            discardHook();
            fail("fishing stopped because there is no fishing rod left", FailureType.WRONG_TOOL);
            return TaskState.FAILED;
        }
        FirstPersonActionGate.Status selected = rodSelection.select(player, rodSlot);
        if (selected == FirstPersonActionGate.Status.RUNNING) return TaskState.RUNNING;
        if (selected == FirstPersonActionGate.Status.FAILED) {
            fail("couldn't select fishing rod: " + rodSelection.failure(), FailureType.WRONG_TOOL);
            return TaskState.FAILED;
        }
        if (!player.getMainHandItem().is(Items.FISHING_ROD)) return TaskState.RUNNING;

        return switch (phase) {
            case POSITION -> throw new IllegalStateException("position phase handled above");
            case PREPARE -> prepare();
            case AIM -> aimAndCast();
            case WAIT -> waitForBite();
            case COLLECT -> throw new IllegalStateException("collect phase handled above");
            case COOLDOWN -> coolDown();
        };
    }

    private TaskState positionForFishing() {
        if (stance == null || target == null) {
            FishingSetup setup = findFishingSetup();
            if (setup == null) {
                fail("no safe dry fishing stance with reachable water nearby; move close to a shoreline and try fish again",
                        FailureType.OUT_OF_REACH);
                return TaskState.FAILED;
            }
            stance = setup.stance();
            target = setup.water();
        }

        if (atStance()) {
            stopNav();
            phase = Phase.PREPARE;
            return TaskState.RUNNING;
        }
        if (nav == null) {
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(stance), NAV_SPEED, this::atStance);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav();
                phase = Phase.PREPARE;
                yield TaskState.RUNNING;
            }
            case FAILED -> {
                rejectedStances.add(stance);
                stopNav();
                stance = null;
                target = null;
                if (++positionFailures >= MAX_POSITION_FAILURES) {
                    fail("nearby dry fishing stances were unreachable; move onto a clear shoreline and try fish again",
                            FailureType.NO_PATH);
                    yield TaskState.FAILED;
                }
                yield TaskState.RUNNING;
            }
        };
    }

    private FishingSetup findFishingSetup() {
        BlockPos current = feet();
        if (isDryStance(current) && !rejectedStances.contains(current)) {
            BlockPos water = findCastTarget(current, player.getEyePosition());
            if (water != null) return new FishingSetup(current, water);
            // Already safely on land but no water is in casting range. Long-distance
            // water discovery belongs to the model's locate/move step, not this job.
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos origin = player.blockPosition();
        for (int dy = -STANCE_SEARCH_Y; dy <= STANCE_SEARCH_Y; dy++) {
            for (int dx = -STANCE_SEARCH_RADIUS; dx <= STANCE_SEARCH_RADIUS; dx++) {
                for (int dz = -STANCE_SEARCH_RADIUS; dz <= STANCE_SEARCH_RADIUS; dz++) {
                    if (dx * dx + dz * dz > STANCE_SEARCH_RADIUS * STANCE_SEARCH_RADIUS) continue;
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (!rejectedStances.contains(candidate) && isDryStance(candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(current::distSqr));
        int checks = Math.min(MAX_STANCE_CHECKS, candidates.size());
        for (int i = 0; i < checks; i++) {
            BlockPos candidate = candidates.get(i);
            Vec3 eye = new Vec3(candidate.getX() + 0.5,
                    candidate.getY() + player.getEyeHeight(), candidate.getZ() + 0.5);
            BlockPos water = findCastTarget(candidate, eye);
            if (water != null) return new FishingSetup(candidate, water);
        }
        return null;
    }

    private TaskState prepare() {
        if (player.fishing != null) {
            discardHook();
            return TaskState.RUNNING;
        }

        BlockPos current = feet();
        if (!isDryStance(current)) {
            resetPositioning();
            return TaskState.RUNNING;
        }
        if (!current.equals(stance)) {
            stance = current;
            target = null;
        }
        if (target == null || !isCastableSurface(target)
                || !trajectoryClear(player.getEyePosition(), target)) {
            target = findCastTarget(stance, player.getEyePosition());
        }
        if (target == null && !rejectedTargets.isEmpty()) {
            // A tiny pond may expose only one valid landing cell. After trying all
            // distinct candidates, permit another ballistic attempt instead of
            // converting one unlucky cast into a permanent "no water" verdict.
            rejectedTargets.clear();
            target = findCastTarget(stance, player.getEyePosition());
        }
        if (target == null) {
            fail("no unobstructed fishing cast is available from this dry stance; move along the shoreline and try again",
                    FailureType.OUT_OF_REACH);
            return TaskState.FAILED;
        }

        phase = Phase.AIM;
        phaseTicks = 0;
        aimAtTarget();
        return TaskState.RUNNING;
    }

    private TaskState aimAndCast() {
        if (!isCastableSurface(target) || !trajectoryClear(player.getEyePosition(), target)) {
            return failedCast("the selected water surface became obstructed", true);
        }
        aimAtTarget();
        if (++phaseTicks < AIM_TICKS) return TaskState.RUNNING;

        double pitch = castPitchDegrees(player.getEyePosition(), target);
        var context = ClientRuntime.requireContext(player);
        if (rodReceipt == null) {
            FishingHook before = player.fishing;
            rodReceipt = context.actions().useItem(context, InteractionHand.MAIN_HAND,
                    c -> c.player().fishing != before && c.player().fishing != null
                            ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING,
                    30);
            return TaskState.RUNNING;
        }
        rodReceipt = context.actions().poll(context, rodReceipt);
        if (!rodReceipt.terminal()) return TaskState.RUNNING;
        if (rodReceipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            rodReceipt = null;
            return failedCast("the fishing rod cast was not confirmed", false);
        }
        rodReceipt = null;
        r.castOnce();
        Constants.LOG.debug("[maicraft-fish] cast={} target={} pitch={}", r.casts(),
                target.toShortString(), String.format(java.util.Locale.ROOT, "%.1f", pitch));
        phase = Phase.WAIT;
        phaseTicks = 0;
        return TaskState.RUNNING;
    }

    private TaskState waitForBite() {
        FishingHook hook = player.fishing;
        if (hook == null || hook.isRemoved()) {
            return failedCast("the fishing hook disappeared before a catch", false);
        }
        phaseTicks++;

        Entity hooked = hook.getHookedIn();
        if (hooked != null) {
            reelIn();
            return failedCast("the hook caught an entity instead of landing cleanly", true);
