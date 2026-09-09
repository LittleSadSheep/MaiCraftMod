package org.maiwithu.maicraft.core.task.fish;
import org.maiwithu.maicraft.core.FailureType;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.mixin.FishingHookAccessor;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.NativePickupReceipt;
import org.maiwithu.maicraft.core.task.base.Precondition;
import org.maiwithu.maicraft.entity.InputDriver;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
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

/**
 * 按阶段钓鱼：确定站位和水面、拿竿瞄准、抛出、等咬钩、收回并捡战利品，然后再来一竿。
 * 请求次数按成功收获的竿数计算，不保证每竿都是鱼；原版也可能给垃圾和宝藏。
 * 咬钩依据客户端同步状态；失败重抛时的动作记录混用仍见审计 A63。
 */
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
    private static final int CAST_SETTLE_TIMEOUT = 5 * 20;
    private static final int CAST_LIFETIME = 60 * 20;
    private static final int COOLDOWN_TICKS = 10;
    private static final int MAX_FAILED_CASTS = 5;

    /** Vanilla reels the loot toward the player, but terrain can stop it short. */
    private static final double LOOT_SEARCH_RADIUS = 18.0;
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
    private NativePickupReceipt lootReceipt;
    private int lootCloseTicks;
    private int unreachableLoot;
    private int disappearedWithoutReceipt;
    private final FirstPersonActionGate rodSelection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate aimConvergence = new ActualViewConvergenceGate();
    private NativeActionReceipt rodReceipt;

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
    // 先处理找站位和收战利品；其他阶段确认鱼竿拿在主手，再推进抛竿、等咬钩或短暂冷却。
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

    // 找到一组站位和水面后走到站位；找不到路会排除这一站位，最多换三次，而不是一直撞同一条路。
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

    // 已经站在干燥地面时，只看从原地能否抛到水面，找不到就失败；
    // 只有当前不是干燥站位时，才搜索附近其他站位。这是 D21 记录的状态依赖限制。
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

    // 先收回已有鱼钩，再重查站位、水面和轨迹；准备好后进入瞄准，不在这里直接抛竿。
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
        aimConvergence.reset();
        aimAtTarget();
        return TaskState.RUNNING;
    }

    // 实际视角转好后才提交抛竿，看到玩家关联了新的鱼钩才记一次抛竿。
    // 但 rodReceipt 也被收竿使用；失败重抛时若留着旧收竿结果，这里会把它误当新抛竿完成（A63）。
    private TaskState aimAndCast() {
        if (!isCastableSurface(target) || !trajectoryClear(player.getEyePosition(), target)) {
            return failedCast("the selected water surface became obstructed", true);
        }
        if (!aimAtTarget()) return TaskState.RUNNING;

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

    // 鱼钩消失、钩住实体、没落入水或等太久时重试；收到客户端同步的咬钩状态后才收竿。
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
        }

        if (((FishingHookAccessor) (Object) hook).maicraft$isBiting()) {
            beginLootCollection();
            return TaskState.RUNNING;
        }

        boolean inWater = hook.level().getFluidState(hook.blockPosition()).is(FluidTags.WATER);
        if (!inWater && phaseTicks >= CAST_SETTLE_TIMEOUT) {
            Constants.LOG.debug("[maicraft-fish] miss hook={} on_ground={} age={}",
                    hook.blockPosition().toShortString(), hook.onGround(), phaseTicks);
            return failedCast("the fishing hook did not settle in water", true);
        }
        if (phaseTicks >= CAST_LIFETIME) {
            return failedCast("no bite arrived before the cast timed out", false);
        }
        return TaskState.RUNNING;
    }

    /**
     * Finish the semantic catch, not merely the rod interaction: walk to every
     * ItemEntity created by this reel until vanilla pickup absorbs it. Fishing
     * loot is launched toward the owner, but a bank, slab or ledge can intercept
     * it several blocks away (the exact failure seen in ordinary play-testing).
     */
    // 先等收竿确认，再等战利品飞回身边；必要时走近拾取，并检查物品消失是否对应背包增加。
    private TaskState collectCaughtLoot() {
        if (rodReceipt != null) {
            var context = ClientRuntime.requireContext(player);
            rodReceipt = context.actions().poll(context, rodReceipt);
            if (!rodReceipt.terminal()) return TaskState.RUNNING;
            if (rodReceipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                fail("fishing reel was not confirmed: " + rodReceipt.detail(), FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            rodReceipt = null;
        }
        phaseTicks++;
        if (phaseTicks <= LOOT_DISCOVERY_TICKS) caught.discover(player.level(), lootBox());

        if (phaseTicks >= LOOT_COLLECTION_TIMEOUT) {
            int remaining = liveCaught().size();
            fail("reeled in fishing loot but timed out while retrieving " + remaining
                    + " dropped loot item(s)", FailureType.NO_PATH);
            return TaskState.FAILED;
        }

        // A vanilla reel launches its loot toward the owner. Planning against that
        // still-moving entity makes the body step off the bank to "meet" a catch
        // that would have arrived by itself. Hold the known-safe stance briefly;
        // genuine stranded loot is still path-found after this grace period.
        boolean returningLoot = !liveCaught().isEmpty();
        if (returningLoot && phaseTicks <= LOOT_RETURN_GRACE_TICKS) {
            return TaskState.RUNNING;
        }

        if (lootTarget != null && lootReceipt != null) {
            NativePickupReceipt.State receiptState = lootReceipt.poll(
                    player, LOOT_CLOSE_WAIT_TICKS);
            if (receiptState == NativePickupReceipt.State.RECEIVED) {
                clearLootTarget();
            } else if (receiptState == NativePickupReceipt.State.AWAITING_INVENTORY_SYNC) {
                if (nav != null) nav.pause();
                return TaskState.RUNNING;
            } else if (receiptState
                    == NativePickupReceipt.State.DISAPPEARED_WITHOUT_RECEIPT) {
                disappearedWithoutReceipt++;
                clearLootTarget();
            } else {
                ItemEntity live = lootReceipt.liveEntity(player);
                if (live == null) return TaskState.RUNNING;
                lootTarget = live;
                if (NativePickupReceipt.insideVanillaTouchEnvelope(player, live)) {
                    if (nav != null) nav.pause();
                    if (live.hasPickUpDelay()) {
                        lootCloseTicks = 0;
                        return TaskState.RUNNING;
                    }
                    if (!NativePickupReceipt.canAccept(player, live.getItem())) {
                        fail("reached the caught loot, but no main-inventory slot can accept its "
                                        + "remaining stack",
                                FailureType.NO_SPACE);
                        return TaskState.FAILED;
                    }
                    if (++lootCloseTicks >= LOOT_CLOSE_WAIT_TICKS) {
                        fail("the body stayed inside vanilla's item-touch envelope with pickup "
                                        + "delay cleared and inventory capacity available, but the "
                                        + "server did not accept the caught loot",
                                FailureType.UNKNOWN);
                        return TaskState.FAILED;
                    }
                    return TaskState.RUNNING;
                }

                lootCloseTicks = 0;
                if (player.blockPosition().equals(live.blockPosition())) {
                    stopNav();
                    InputDriver.stepToward(player, live.position(), false);
                    return TaskState.RUNNING;
                }
                if (nav == null) {
                    nav = PlayerNav.toRevalidating(player, this::lootTargetGoal, NAV_SPEED,
                            this::lootTargetReceived, PlayerNav.ContextProvider.DEFAULT);
                }
                switch (nav.tick()) {
                    case RUNNING -> { return TaskState.RUNNING; }
                    case ARRIVED -> {
                        stopNav();
                        InputDriver.stepToward(player, live.position(), false);
                        return TaskState.RUNNING;
                    }
                    case FAILED -> {
                        abandonLootTarget();
                        return TaskState.RUNNING;
                    }
                }
            }
        }

        var level = player.clientLevel;
        caught.prune(level);
        lootTarget = caught.nearest(level, player, abandonedLoot).orElse(null);
        if (lootTarget != null) {
            stopNav();
            lootReceipt = NativePickupReceipt.begin(player, lootTarget);
            return TaskState.RUNNING;
        }

        // A freshly spawned catch can be absorbed on the same tick or become
        // query-visible one tick later. Keep the short discovery window before
        // deciding that there is nothing left to retrieve.
        if (phaseTicks < LOOT_DISCOVERY_TICKS) return TaskState.RUNNING;
        if (unreachableLoot > 0) {
            fail("reeled in fishing loot but could not reach " + unreachableLoot
                    + " dropped loot item(s)", FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        int expectedUnits = caught.attributableUnits();
        int receivedUnits = caught.receivedTrackedUnits(player);
        // 没来得及看见地上实体时，当前退而使用任意背包正增长作为收获线索；这不能独自证明物品来自本次钓鱼。
        boolean receivedBeforeVisible = expectedUnits == 0
                && caught.totalInventoryUnitGain(player) > 0;
        if ((expectedUnits > 0 && receivedUnits < expectedUnits)
                || (expectedUnits == 0 && !receivedBeforeVisible)) {
            fail("the fishing interaction completed, but the loot collection has no matching "
                            + "inventory receipt: observed " + expectedUnits + " attributable unit(s), "
                            + "received " + receivedUnits + ", disappeared without receipt "
                            + disappearedWithoutReceipt,
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        // 这里累计的是完成一次收获，不是物品件数；一竿钓到垃圾或宝藏也会计一次。
        r.caughtOne();
        Constants.LOG.debug("[maicraft-fish] caught-and-received={}/{} casts={} lootUnits={}",
                r.caught(), r.requested, r.casts(), Math.max(receivedUnits, 1));
        clearLootTracking();
        beginCooldown();
        return TaskState.RUNNING;
    }

    private GoalCompiler.Compiled lootTargetGoal() {
        ItemEntity live = lootReceipt == null ? null : lootReceipt.liveEntity(player);
        return live == null ? null : GoalCompiler.standOn(live.blockPosition());
    }

    private boolean lootTargetReceived() {
        return lootReceipt != null && lootReceipt.received(player);
    }

    // 收竿前先记地上已有物品和背包数量，之后把新看到的东西拿来比较，避免直接把旧物品算成本次产出。
    private void beginLootCollection() {
        caught.clear();
        abandonedLoot.clear();
        lootTarget = null;
        lootReceipt = null;
        lootCloseTicks = 0;
        unreachableLoot = 0;
        disappearedWithoutReceipt = 0;
        caught.rememberExisting(player.level(), lootBox());
        caught.rememberInventory(player);

        reelIn();
        phase = Phase.COLLECT;
        phaseTicks = 0;
        stopNav();
        caught.discover(player.level(), lootBox());
    }

    /** 收线战果的搜索范围:落点可能被岸坡/台阶截在几格外。 */
    private AABB lootBox() {
        return player.getBoundingBox().inflate(LOOT_SEARCH_RADIUS);
    }

    /** 仍在世且未被放弃的本竿战果。 */
    private List<ItemEntity> liveCaught() {
        return caught.live(player.clientLevel, abandonedLoot);
    }

    private void abandonLootTarget() {
        if (lootTarget != null) abandonedLoot.add(lootTarget.getId());
        unreachableLoot++;
        clearLootTarget();
    }

    private void clearLootTarget() {
        lootTarget = null;
        lootReceipt = null;
        lootCloseTicks = 0;
        stopNav();
    }

    private void clearLootTracking() {
        caught.clear();
        abandonedLoot.clear();
        lootTarget = null;
        lootReceipt = null;
        lootCloseTicks = 0;
        unreachableLoot = 0;
        disappearedWithoutReceipt = 0;
        stopNav();
    }

    private TaskState coolDown() {
        if (++phaseTicks < COOLDOWN_TICKS) return TaskState.RUNNING;
        // requested == 0 = 主人没说钓几条 —— 这一行永远不成立,任务就是常驻的:
        // 一直钓下去,直到主人换掉她手上的活。同一段逻辑,两种用法。
        if (r.requested > 0 && r.caught() >= r.requested) return TaskState.SUCCESS;
        phase = Phase.PREPARE;
        phaseTicks = 0;
        return TaskState.RUNNING;
    }

    // 先尝试收竿，再按原因决定是否排除这个水面；连续五次失败就结束。
    // 这里回到 PREPARE，却没有单独等待并清空收竿记录，导致 A63 的阶段混用。
    private TaskState failedCast(String reason, boolean rejectTarget) {
        BlockPos failedTarget = target;
        discardHook();
        if (rejectTarget && failedTarget != null) rejectedTargets.add(failedTarget);
        if (++failedCasts >= MAX_FAILED_CASTS) {
            fail(reason + " after " + failedCasts
                    + " attempts; move to a clearer shoreline and try fish again", FailureType.OUT_OF_REACH);
            return TaskState.FAILED;
        }
        phase = Phase.PREPARE;
        phaseTicks = 0;
        if (rejectTarget) target = null;
        return TaskState.RUNNING;
    }

    private void beginCooldown() {
        phase = Phase.COOLDOWN;
        phaseTicks = 0;
        failedCasts = 0;
        rejectedTargets.clear();
    }

    private void resetPositioning() {
        discardHook();
        stopNav();
        clearLootTracking();
        phase = Phase.POSITION;
        phaseTicks = 0;
        stance = null;
        target = null;
        rejectedTargets.clear();
    }

    // 只有鱼钩仍在、主手是鱼竿且没有待处理记录时才右键；等玩家的鱼钩引用消失来确认收回。
    private void reelIn() {
        if (player.fishing == null || !player.getMainHandItem().is(Items.FISHING_ROD)
                || rodReceipt != null) return;
        var context = ClientRuntime.requireContext(player);
        rodReceipt = context.actions().useItem(context, InteractionHand.MAIN_HAND,
                c -> c.player().fishing == null
                        ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING,
                30);
    }

    // 当前只认原版鱼竿，且会扫描副手；后面的主手选择器不接受副手槽号，同 A34 的范围不一致。
    private int findRodSlot() {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.FISHING_ROD)) return i;
        }
        return -1;
    }

    // 只在水平四到十格、上下四格内找水源，偏好约六格远、周围水面较多的位置，再检查方块轨迹。
    private BlockPos findCastTarget(BlockPos fromStance, Vec3 eye) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -CAST_SEARCH_Y; dy <= CAST_SEARCH_Y; dy++) {
            for (int dx = -CAST_SEARCH_RADIUS; dx <= CAST_SEARCH_RADIUS; dx++) {
                for (int dz = -CAST_SEARCH_RADIUS; dz <= CAST_SEARCH_RADIUS; dz++) {
                    double horizontal = Math.sqrt(dx * dx + dz * dz);
                    if (horizontal < MIN_CAST_DISTANCE || horizontal > CAST_SEARCH_RADIUS) continue;
                    BlockPos candidate = fromStance.offset(dx, dy, dz);
                    if (!rejectedTargets.contains(candidate) && isCastableSurface(candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> castScore(fromStance, candidate)));
        for (BlockPos candidate : candidates) {
            if (trajectoryClear(eye, candidate)) return candidate;
        }
        return null;
    }

    private double castScore(BlockPos fromStance, BlockPos candidate) {
        double dx = candidate.getX() - fromStance.getX();
        double dz = candidate.getZ() - fromStance.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return Math.abs(horizontal - IDEAL_CAST_DISTANCE)
                + Math.abs(candidate.getY() - fromStance.getY()) * 0.35
                - waterNeighbourCount(candidate) * 0.04;
    }

    private int waterNeighbourCount(BlockPos pos) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isCastableSurface(pos.offset(dx, 0, dz))) count++;
            }
        }
        return count;
    }

    // 只认水源最上层，而且上方碰撞形状为空；流动水和顶上有实物挡住的水不作为目标。
    private boolean isCastableSurface(BlockPos pos) {
        var fluid = player.level().getFluidState(pos);
        if (!fluid.is(FluidTags.WATER) || !fluid.isSource()) return false;
        if (player.level().getFluidState(pos.above()).is(FluidTags.WATER)) return false;
        return player.level().getBlockState(pos.above())
                .getCollisionShape(player.level(), pos.above()).isEmpty();
    }

    // 身体两格无流体、可以穿过，脚下能站，才视为干燥站位；这里按方块判断支撑。
    private boolean isDryStance(BlockPos pos) {
        return player.level().getFluidState(pos).isEmpty()
                && player.level().getFluidState(pos.above()).isEmpty()
                && BlockHelper.canWalkThrough(player.level(), pos)
                && BlockHelper.canWalkThrough(player.level(), pos.above())
                && BlockHelper.canWalkOn(player.level(), pos.below());
    }

    private boolean atStance() {
        return stance != null && feet().equals(stance) && isDryStance(stance);
    }

    private BlockPos feet() {
        return BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
    }

    private boolean aimAtTarget() {
        Vec3 eye = player.getEyePosition();
        Vec3 aim = castAimPoint(eye, target);
        InputDriver.lookAt(player, aim);
        return aimConvergence.ready(player, aim.subtract(eye));
    }

    private static Vec3 castAimPoint(Vec3 eye, BlockPos target) {
        double tx = target.getX() + 0.5;
        double tz = target.getZ() + 0.5;
        double dx = tx - eye.x;
        double dz = tz - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0e-6) return new Vec3(tx, waterSurfaceY(target), tz);
        double pitch = Math.toRadians(castPitchDegrees(eye, target));
        double scale = 16.0 / horizontal;
        return new Vec3(eye.x + dx * scale,
                eye.y - Math.tan(pitch) * 16.0,
                eye.z + dz * scale);
    }

    // 按估算的抛竿方向逐步模拟下坠和减速，最多八十步；任何一小段碰到方块就拒绝。
    // 这里没有检查沿途实体，鱼钩实际钩住实体时才在等待阶段处理。
    private boolean trajectoryClear(Vec3 eye, BlockPos target) {
        double tx = target.getX() + 0.5;
        double tz = target.getZ() + 0.5;
        double dx = tx - eye.x;
        double dz = tz - eye.z;
        double directDistance = Math.sqrt(dx * dx + dz * dz);
        if (directDistance < 1.0e-6) return false;
        double ux = dx / directDistance;
        double uz = dz / directDistance;
        // Vanilla spawns the bobber 0.3 blocks in front of the player's eyes.
        Vec3 pos = eye.add(ux * 0.3, 0.0, uz * 0.3);
        double distance = Math.sqrt((tx - pos.x) * (tx - pos.x) + (tz - pos.z) * (tz - pos.z));
        double pitch = Math.toRadians(solvePitchDegrees(distance, waterSurfaceY(target) - eye.y));
        double horizontalVelocity = 0.6 * Math.cos(pitch) + 0.5;
        double verticalVelocity = -Math.tan(pitch) * horizontalVelocity;
        double travelled = 0.0;

        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            verticalVelocity -= FISHING_GRAVITY;
            double fraction = Math.min(1.0, (distance - travelled) / horizontalVelocity);
            Vec3 next = pos.add(ux * horizontalVelocity * fraction,
                    verticalVelocity * fraction, uz * horizontalVelocity * fraction);
            HitResult hit = player.level().clip(new ClipContext(pos, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.MISS) return false;
            travelled += horizontalVelocity * fraction;
            if (travelled >= distance - 1.0e-6) return true;
            pos = next;
            horizontalVelocity *= FISHING_DRAG;
            verticalVelocity *= FISHING_DRAG;
        }
        return false;
    }

    private static double castPitchDegrees(Vec3 eye, BlockPos target) {
        double dx = target.getX() + 0.5 - eye.x;
        double dz = target.getZ() + 0.5 - eye.z;
        double distance = Math.max(0.1, Math.sqrt(dx * dx + dz * dz) - 0.3);
        return solvePitchDegrees(distance, waterSurfaceY(target) - eye.y);
    }

    // 在向上 45° 到向下 55° 之间反复折半试角度，看模拟飞到目标距离时是偏高还是偏低。
    static double solvePitchDegrees(double horizontalDistance, double targetHeight) {
        double low = -45.0;
        double high = 55.0;
        for (int i = 0; i < 32; i++) {
            double mid = (low + high) * 0.5;
            double height = trajectoryHeightAtDistance(horizontalDistance, mid);
            if (height > targetHeight) {
                low = mid;  // trajectory is high: aim farther down
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5;
    }

    // 从初速度开始，每步先加下坠再按比例减速；抵达目标水平距离时用该步中的比例估算高度。
    static double trajectoryHeightAtDistance(double horizontalDistance, double pitchDegrees) {
        double pitch = Math.toRadians(pitchDegrees);
        double horizontalVelocity = 0.6 * Math.cos(pitch) + 0.5;
        double verticalVelocity = -Math.tan(pitch) * horizontalVelocity;
        double travelled = 0.0;
        double height = 0.0;
        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            verticalVelocity -= FISHING_GRAVITY;
            double nextDistance = travelled + horizontalVelocity;
            double nextHeight = height + verticalVelocity;
            if (nextDistance >= horizontalDistance) {
                double fraction = (horizontalDistance - travelled) / horizontalVelocity;
                return height + verticalVelocity * fraction;
            }
            travelled = nextDistance;
            height = nextHeight;
            horizontalVelocity *= FISHING_DRAG;
            verticalVelocity *= FISHING_DRAG;
        }
        return Double.NEGATIVE_INFINITY;
    }

    private static double waterSurfaceY(BlockPos target) {
        return target.getY() + WATER_SURFACE_OFFSET;
    }

    private void discardHook() {
        reelIn();
    }

    @Override
    // 被打断时收回鱼钩；已进入拾取阶段的物品仍保留跟踪，其他非找站位阶段重新准备位置。
    public void stop(LocalPlayer companion, StopReason why) {
        boolean wasPositioning = phase == Phase.POSITION;
        boolean wasCollecting = phase == Phase.COLLECT;
        super.stop(companion, why);
        discardHook();
        if (wasCollecting) {
            // Survival preemption may stop the navigator, but the already-caught
            // drops remain the same bounded sub-goal when the LLM task resumes.
            stopNav();
        } else if (!wasPositioning) {
            resetPositioning();
        }
    }

    @Override
    // 结束时停移动、尝试收竿、清掉鱼竿选择和拾取记录。丢掉局部记录不代表公共动作等待已经结束。
    protected void cleanup() {
        InputDriver.halt(player);
        discardHook();
        rodSelection.reset();
        aimConvergence.reset();
        rodReceipt = null;
        clearLootTracking();
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("requested", r.requested);
        data.put("caught", r.caught());
        data.put("casts", r.casts());
        return data;
    }

    @Override
    protected String successMessage() {
        return "completed " + r.caught() + " successful fishing catch(es)";
    }

    @Override
    protected String timeoutMessage() {
        return "fishing timed out after " + r.caught() + "/" + r.requested + " successful catches";
    }

    @Override
    protected String cancelledMessage() {
        return "fishing interrupted after " + r.caught() + "/" + r.requested + " successful catches";
    }
}
