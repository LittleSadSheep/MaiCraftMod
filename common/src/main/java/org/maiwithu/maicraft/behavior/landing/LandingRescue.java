// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.FallDamage;
import org.maiwithu.maicraft.game.player.NetworkDelay;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.world.BucketWater;

/**
 * 一次落地救援：拿好东西 → 离地 → 掉到够得着时对准、只放一次 → 等游戏确认放下了 → 落地站稳 →
 * 收回自己放的（水用空桶舀回，方块挖掉）。有计划的下落在出发点备料（{@link #prepare}），
 * 意外掉下去时在空中边掉边备（{@link #tick}）。
 *
 * <p>放了就不再放第二次：没确认就如实记失败，不盲目重放。对准连续几刻对不上就换下一个办法——
 * 掉落的时间回不来，同一格死盯着只会摔在地上。落到了别处、放的东西被改了或被划进保护、
 * 拿走后站不住，就把东西留在原地如实说。成功认定：干草允许减伤后活着（要真踩在干草上）；
 * 其余要求没掉血，水还要真碰到水、摔落清零。
 */
public final class LandingRescue implements CatchesFall {

    /** 落地后最多花这么久收回自己放的；过了就留在原地。 */
    private static final long RECOVERY_LIMIT_TICKS = 80;
    /** 放置射线连续这么多刻对不上就换下一个办法。 */
    private static final int MISMATCH_TICKS_BEFORE_NEXT = 6;
    /** 一次放置、舀水等游戏确认的期限。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 12;

    private final List<LandingPlan> candidates;
    private final Predicate<BlockPos> protectedCell;
    private final LongSet noEntryCells;
    private final List<Change> changes = new ArrayList<>();
    private int cursor;
    private LandingPlan plan;
    private LandingItemInHand item;
    private PlayerContext current;
    private PendingInteraction pending;
    private BlockState placed;
    private BlockState beforePlacement;
    private boolean submitted, failed, complete, cleaning, removed;
    private boolean damageObserved, waterContact, hayContact, airborneObserved;
    private float previousHealth = Float.NaN, previousAbsorption = Float.NaN;
    private float healthLost;
    private int stableTicks, failedStableTicks, displacedStableTicks, mismatchTicks;
    private long lastTick = Long.MIN_VALUE;
    private long recoveryStarted = Long.MIN_VALUE;
    private float airborneYaw = Float.NaN;
    private String detail = "正在准备落地";

    private LandingRescue(List<LandingPlan> plans, Predicate<BlockPos> protectedCell, LongSet noEntryCells,
                          boolean airborne) {
        if (plans.isEmpty()) throw new IllegalArgumentException("落地救援至少要有一个办法");
        // 先水，再蛛网、浆果丛、藤蔓、黏液块这类免伤的，干草只减伤排最后。
        this.candidates = plans.stream().sorted(Comparator.comparingInt(LandingRescue::preference)).toList();
        this.protectedCell = protectedCell;
        this.noEntryCells = noEntryCells;
        this.airborneObserved = airborne;
        bindTo(0);
    }

    /** 走路里有计划的一跳：在出发点先用 {@link #prepare} 备好，离地后每刻 {@link #tick}。 */
    public static LandingRescue planned(List<LandingPlan> plans, Predicate<BlockPos> protectedCell,
                                        LongSet noEntryCells) {
        return new LandingRescue(plans, protectedCell, noEntryCells, false);
    }

    /** 已经在掉：每刻 {@link #tick}，边掉边备。 */
    public static LandingRescue airborne(List<LandingPlan> plans, Predicate<BlockPos> protectedCell,
                                         LongSet noEntryCells) {
        return new LandingRescue(plans, protectedCell, noEntryCells, true);
    }

    private static int preference(LandingPlan plan) {
        return switch (plan.aid()) {
            case WATER -> 0;
            case HAY -> 3;
            default -> 2;
        };
    }

    private void bindTo(int index) {
        cursor = index;
        plan = candidates.get(index);
        item = plan.existing() ? null : new LandingItemInHand(plan.aid().item(), () -> current);
    }

    public LandingPlan plan() {
        return plan;
    }

    @Override
    public String itemId() {
        return plan.aid().itemId();
    }

    @Override
    public BlockPos landingFeet() {
        return plan.feet();
    }

    @Override
    public boolean complete() {
        return complete;
    }

    /** 没救成：放不成、落到别处、或说是免伤却掉了血。干草减伤后活着不算失败。 */
    @Override
    public boolean failed() {
        return failed || complete && damageObserved && plan.aid() != LandingAid.HAY;
    }

    /** 已经放出去了（不管确认没确认）：放出去之后就不能把这一掉交给别人重来。 */
    public boolean submitted() {
        return submitted;
    }

    /** 收尾到哪一步、为什么没成，说给 LLM 听的一句话。 */
    @Override
    public String detail() {
        return detail;
    }

    public float healthLost() {
        return healthLost;
    }

    /** 取走到现在为止改了的格子（放下的、收回的）。 */
    @Override
    public List<Change> drainChanges() {
        var result = List.copyOf(changes);
        changes.clear();
        return result;
    }

    /**
     * 站在出发点每刻调一次：落点还放得下、东西拿到手上了才返回 true，可以离地。
     * 落点变了（被挖、被放了东西、被划进保护）就失败，不往下跳。
     */
    @Override
    public boolean prepare(PlayerContext context) {
        current = context;
        LocalPlayer player = context.localPlayer();
        if (failed) {
            if (item != null) item.cleanupPending(context);
            return false;
        }
        if (Float.isNaN(previousHealth)) rememberHealth(player);
        refreshWaterAim(context, player);
        if (!survivesHay(player)) return failNow("落在干草上也会摔死，这一跳不跳");
        if (!context.level().isLoaded(plan.cell()) || !context.level().isLoaded(plan.clicked())) return false;
        if (!safe(context, player, plan)) {
            if (item != null) item.abandon(context, "落点变了");
            return failNow("出发前落点变了：身体放不下或脚下没有支撑了");
        }
        if (plan.existing()) {
            if (!plan.aid().cushions(context.level().getBlockState(plan.cell()))) {
                return failNow("出发前落点现成的" + name(plan.aid()) + "变了");
            }
            return true;
        }
        if (protectedCell.test(plan.cell()) || !LandingOptions.canPlace(plan.aid(), context.level(), plan.cell())) {
            item.abandon(context, "落点放不下了");
            return failNow("出发前落点放不下" + name(plan.aid()) + "了（被占了、被保护了或立不住）");
        }
        boolean ready = item.tick(context, Integer.MAX_VALUE);
        if (item.failed()) failNow(item.detail());
        return ready;
    }

    /** 离地以后（意外掉下去时从第一刻起）每刻调一次。同一刻调多次只算一次。 */
    @Override
    public void tick(PlayerContext context) {
        if (complete || lastTick == context.clientTick()) return;
        lastTick = context.clientTick();
        current = context;
        LocalPlayer player = context.localPlayer();
        observe(context, player);
        if (context.input().humanTookOver()) {
            fail("落地途中人接回了角色，救援停下");
            complete = true;
            return;
        }
        if (item != null && item.cleanupPending(context)) return;
        if (airborneObserved && player.onGround() && !submitted && !plan.existing()) {
            if (item != null) item.abandon(context, "还没放就落地了");
            fail("还没来得及放" + name(plan.aid()) + "就落地了，伤害由游戏结算");
        }
        if (settledAway(context, player)) return;
        if (!failed && item != null && !item.ready() && !prepareInFlight(context, player)) return;
        if (!failed) aim(context, player);
        if (pending != null) {
            settle(context);
            if (pending != null) return;
        }
        if (afterTouchdown(context, player)) return;
        if (!failed) trySubmit(context, player);
    }

    /** 离过地、现在又落回地上（或落进水里停住）了：之后的站稳、收回由救援占着身体。 */
    @Override
    public boolean touchedDown(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        return airborneObserved && (player.onGround() || player.isInWater() && player.fallDistance == 0);
    }

    /** 收回水源的那几刻按住站位，别让游泳、漂浮把人带走。 */
    public boolean holdsPosition(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        return !complete && plan.aid() == LandingAid.WATER && nearLanding(player)
                && (player.isInWater() && player.fallDistance == 0 || submitted && player.onGround());
    }

    /**
     * 要不要按住潜行：落进水里还没收回时往下沉到底；方块类的放之前按住，点到的方块不会被"使用"（开箱子）；
     * 黏液块只在最后一小段弹起时按住压住回弹——从高处就按住会让黏液块不再免伤。
     */
    public boolean wantsSneak(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        if (plan.aid() == LandingAid.WATER && waterContact && !removed && player.isInWater() && !player.onGround()) {
            return true;
        }
        if (!submitted && !plan.existing() && plan.aid() != LandingAid.WATER && !player.onGround()) return true;
        if (plan.aid() != LandingAid.SLIME) return false;
        return player.getDeltaMovement().y <= 0 && player.getY() - plan.cell().getY() < 3
                && player.fallDistance + 1 < player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
    }

    /** 这一刻往落点推的按键：落地后、收水时站定。 */
    @Override
    public PlayerInput.Movement steering(PlayerContext context, float yaw) {
        LocalPlayer player = context.localPlayer();
        boolean sneak = wantsSneak(context);
        if (holdsPosition(context) || player.onGround()) {
            return new PlayerInput.Movement(0, 0, false, sneak, false);
        }
        return AirSteering.movement(player, plan.feet(), yaw, sneak);
    }

    /** 被别的事打断（任务结束、换世界）：没确认的放置交还等待、挖了一半的停手、打开的背包关上。 */
    @Override
    public void stop(PlayerContext context, String reason) {
        if (complete) return;
        current = context;
        fail(reason);
        if (item != null) item.abandon(context, reason);
        if (pending != null && !pending.terminal()) {
            var sender = context.interactionSender();
            pending = pending.kind() == PendingInteraction.Kind.BREAK_BLOCK
                    ? sender.cancelBreakingForTaskBoundary(context, pending, reason)
                    : sender.retireOneShotForTaskBoundary(context, pending, reason);
        }
        pending = null;
        complete = true;
    }

    // 每刻记一次：掉没掉血（普通血或黄心少了都算）、离没离过地、真碰到放的水没有、真踩在干草上没有。
    private void observe(PlayerContext context, LocalPlayer player) {
        airborneObserved |= !player.onGround();
        if (Float.isFinite(previousHealth) && player.getHealth() < previousHealth - 0.001F
                || Float.isFinite(previousAbsorption) && player.getAbsorptionAmount() < previousAbsorption - 0.001F) {
            damageObserved = true;
        }
        if (Float.isFinite(previousHealth)) healthLost += Math.max(0, previousHealth - player.getHealth());
        rememberHealth(player);
        if (plan.aid() == LandingAid.WATER && player.isInWater() && player.fallDistance == 0
                && touchesPlannedWater(context, player)) {
            waterContact = true;
        }
        if (plan.aid() == LandingAid.HAY && player.onGround()
                && context.level().getBlockState(plan.cell()).is(Blocks.HAY_BLOCK)
                && Math.abs(player.getBoundingBox().minY - plan.cell().getY() - 1) < 0.01
                && player.getBoundingBox().intersects(new AABB(plan.cell()).inflate(0.001))) {
            hayContact = true;
        }
    }

    // 落到了别处（被水冲、被打飞、没接住）：结束这一掉和证明救成了是两回事，站稳几刻后如实收场，东西留在原地。
    private boolean settledAway(PlayerContext context, LocalPlayer player) {
        boolean settled = player.onGround() && Math.abs(player.getDeltaMovement().y) < 0.1
                || (player.isInWater() || player.onClimbable()) && player.fallDistance == 0
                && Math.abs(player.getDeltaMovement().y) <= 0.2;
        if (!airborneObserved || nearLanding(player) || !settled
                || plan.aid() == LandingAid.WATER && touchesPlannedWater(context, player)) {
            displacedStableTicks = 0;
            return false;
        }
        if (pending != null) {
            settle(context);
            if (pending != null) return true;
        }
        if (++displacedStableTicks >= dwell(context)) {
            if (item != null) item.abandon(context, "落到了别处");
            fail("落到了别处，没落在计划的落点上；放下的东西（如果有）留在原地");
            complete = true;
        }
        return true;
    }

    // 空中边掉边把东西拿到手上：只剩几刻时背包里的来不及挪，换下一个现成的或手边就有的办法。
    private boolean prepareInFlight(PlayerContext context, LocalPlayer player) {
        int remaining = player.onGround() ? Integer.MAX_VALUE : remainingActionTicks(context, player);
        if (item.tick(context, remaining)) return true;
        if (!item.failed()) return false;
        String reason = item.detail();
        if (!switchToNext(context)) fail(reason);
        return false;
    }

    // 放之前对准：水在空中固定朝向低头往下看（射线已经对上就停住不动）；快够得着了立即转过去，平时平滑地转。
    private void aim(PlayerContext context, LocalPlayer player) {
        boolean airborne = !player.onGround();
        boolean ready = item == null || item.ready();
        if (!submitted && airborne && ready && plan.aid() == LandingAid.WATER
                && acceptWaterHit(context, player, trace(context, player, false))) {
            context.input().requestImmediateLook(player.getYRot(), player.getXRot(), context.clientTick());
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 delta = (placed == null ? plan.aimPoint() : Vec3.atCenterOf(plan.cell())).subtract(eye);
        double flat = Math.hypot(delta.x, delta.z);
        float yaw = flat < .001 ? player.getYRot() : (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, flat));
        if (airborne && plan.aid() == LandingAid.WATER) {
            if (!Float.isFinite(airborneYaw)) airborneYaw = player.getYRot();
            yaw = airborneYaw;
            pitch = 90;
        }
        boolean urgent = !submitted && airborne && ready
                && eye.y - plan.placementHeight(context.level()) <= player.blockInteractionRange();
        if (urgent) context.input().requestImmediateLook(yaw, pitch, context.clientTick());
        else context.input().requestLook(yaw, pitch, context.clientTick());
    }

    // 落地后：站稳等几刻（按延迟）让身体与血量同步，再认定成没成；自己放的东西接着收回。
    private boolean afterTouchdown(PlayerContext context, LocalPlayer player) {
        boolean stable = nearLanding(player) && (player.onGround() && Math.abs(player.getDeltaMovement().y) < 0.1
                || (plan.existing() || failed) && !removed && player.isInWater()
                && player.fallDistance == 0 && player.getDeltaMovement().y >= 0);
        if (failed) {
            if (!stable) {
                failedStableTicks = 0;
            } else if (++failedStableTicks >= dwell(context)) {
                complete = true;
            }
            return true;
        }
        if (!stable) {
            stableTicks = 0;
            return plan.existing() || submitted;
        }
        if (++stableTicks < dwell(context)) {
            detail = "落地了，等身体和血量同步稳定";
            return true;
        }
        if (plan.existing() || removed) {
            finish(removed ? "落地没掉血，放下的" + name(plan.aid()) + "收回来了" : "落在现成的" + name(plan.aid()) + "里，没掉血");
        } else if (placed != null) {
            new LandingRecovery(this).recover(context, player);
        } else {
            fail("落地了，但没确认自己放下了" + name(plan.aid()));
        }
        return true;
    }

    // 只放一次：在空中、本刻能出手、东西在手上、方块类已按住潜行、落点还放得下、射线正对着要点的那一面，才提交。
    private void trySubmit(PlayerContext context, LocalPlayer player) {
        if (plan.existing() || submitted || player.onGround() || !context.canInteractThisTick()) return;
        if (!survivesHay(player)) {
            fail("掉得太高，落在干草上也会摔死");
            return;
        }
        if (item == null || !item.ready()) {
            fail(name(plan.aid()) + "没能在出手前拿到手上");
            return;
        }
        if (plan.aid() != LandingAid.WATER && !player.isShiftKeyDown()) return;
        if (protectedCell.test(plan.cell()) || !LandingOptions.canPlace(plan.aid(), context.level(), plan.cell())) {
            fail("掉的途中落点变了，放不下" + name(plan.aid()) + "，不再放");
            return;
        }
        BlockHitResult hit = trace(context, player, false);
        boolean onTarget = plan.aid() == LandingAid.WATER ? acceptWaterHit(context, player, hit)
                : hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(plan.clicked())
                && hit.getDirection() == plan.face();
        if (!onTarget) {
            // 只在落点已经够得着时才算对不准：还在高处够不着时射线本来就点不到，不能因此换办法。
            boolean inReach = player.getEyePosition().y - plan.placementHeight(context.level())
                    <= player.blockInteractionRange();
            if (inReach && ++mismatchTicks >= MISMATCH_TICKS_BEFORE_NEXT) switchToNext(context);
            return;
        }
        mismatchTicks = 0;
        submit(context, player, hit);
    }

    // 提交那一下：确认条件是落点那一格变成了能接住人的这种东西，并且手里的东西真被用掉（水桶变空桶）。
    private void submit(PlayerContext context, LocalPlayer player, BlockHitResult hit) {
        InteractionHand hand = item.hand();
        if (!player.getItemInHand(hand).is(plan.aid().item())) {
            fail(name(plan.aid()) + "出手前离开了手");
            return;
        }
        int count = player.getItemInHand(hand).getCount();
        LandingPlan target = plan;
        InteractionConfirmation confirmation = c -> {
            LocalPlayer self = c.localPlayer();
            boolean consumed = self.getAbilities().instabuild || (target.aid() == LandingAid.WATER
                    ? self.getItemInHand(hand).is(Items.BUCKET)
                    : !self.getItemInHand(hand).is(target.aid().item()) || self.getItemInHand(hand).getCount() < count);
            return target.aid().cushions(c.level().getBlockState(target.cell())) && consumed
                    ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
        };
        beforePlacement = context.level().getBlockState(plan.cell());
        submitted = true;
        detail = name(plan.aid()) + "放出去了，等游戏确认";
        try {
            var sender = context.interactionSender();
            pending = plan.aid() == LandingAid.WATER
                    ? sender.useItem(context, hand, confirmation, CONFIRM_TIMEOUT_TICKS)
                    : sender.useBlock(context, hand, hit, confirmation, CONFIRM_TIMEOUT_TICKS);
        } catch (RuntimeException unavailable) {
            fail("放" + name(plan.aid()) + "这一下提交不出去：" + unavailable.getMessage());
        }
    }

    // 等放置或收回的确认：挖的那一下快到期限还没好就停手，没到就接着挖。
    void settle(PlayerContext context) {
        var sender = context.interactionSender();
        try {
            boolean breaking = cleaning && pending.kind() == PendingInteraction.Kind.BREAK_BLOCK;
            if (breaking && !pending.terminal() && context.clientTick() >= pending.deadlineTick() - 1
                    && context.canInteractThisTick()) {
                pending = sender.cancelBreaking(context, pending);
            }
            pending = sender.poll(context, pending);
            if (!pending.terminal()) {
                if (breaking && context.canInteractThisTick()) pending = sender.continueBreaking(context, pending);
                return;
            }
            if (pending.status() != PendingInteraction.Status.CONFIRMED_APPLIED) {
                fail((cleaning ? "收回" : "放") + name(plan.aid()) + "没得到游戏确认：" + pending.detail());
            } else if (cleaning) {
                changes.add(new Change(plan.cell(), placed, context.level().getBlockState(plan.cell())));
                removed = true;
                stableTicks = 0;
                detail = "收回了自己放的" + name(plan.aid()) + "，等拿走后站稳";
            } else {
                placed = context.level().getBlockState(plan.cell());
                changes.add(new Change(plan.cell(), beforePlacement, placed));
                detail = name(plan.aid()) + "放下了，等落地";
            }
            pending = null;
        } catch (RuntimeException unavailable) {
            pending = null;
            fail("等确认时出错：" + unavailable.getMessage());
        }
    }

    // 对准连续对不上：按先后换下一个现成的或身上带着的办法；接触证据属于旧办法，新办法要自己再证明。
    private boolean switchToNext(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        for (int index = cursor + 1; index < candidates.size(); index++) {
            LandingPlan next = candidates.get(index);
            if (!next.existing() && !carries(player, next.aid())) continue;
            if (item != null) item.abandon(context, "换下一个落地办法");
            bindTo(index);
            waterContact = false;
            hayContact = false;
            stableTicks = 0;
            mismatchTicks = 0;
            detail = "对不准，换成" + name(plan.aid());
            return true;
        }
        return false;
    }

    // 水桶的落点以原版规则为准：射线点到哪一面、水实际会进哪一格；在落点那一柱、没受保护、放下后人还落得下才接受，
    // 并把计划改成这真实的一格——放出去之前先认准自己要放的是哪一格。
    boolean acceptWaterHit(PlayerContext context, LocalPlayer player, BlockHitResult hit) {
        if (hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos water = BucketWater.waterCell(context.level(), hit, false);
        if (water.equals(plan.cell())) {
            plan = new LandingPlan(plan.aid(), plan.feet(), water, hit.getBlockPos(), hit.getDirection(), false,
                    hit.getLocation());
            return true;
        }
        BlockPos feet = plan.feet();
        // 人飘离了计划的那一列（带着跑步的冲劲跳下来，落点往前多出一两格）：低头看下去正下方这一列能接住人，
        // 就改接在这里——掉落的时间回不来，死盯着计划那一列只会摔在地上（实机 15 格柱子冲出去摔掉 11 点血）。
        if ((water.getX() != feet.getX() || water.getZ() != feet.getZ()) && belowPlayer(player, water)) {
            var drifted = new LandingPlan(LandingAid.WATER, water, water, hit.getBlockPos(), hit.getDirection(), false,
                    hit.getLocation());
            if (!protectedCell.test(water) && LandingOptions.canPlace(LandingAid.WATER, context.level(), water)
                    && safe(context, player, drifted)) {
                plan = drifted;
                return true;
            }
        }
        BlockPos plantTop = BucketWater.exposedWaterCell(context.level(), feet);
        if (water.getX() != feet.getX() || water.getZ() != feet.getZ()
                || water.getY() < feet.getY() - 1 || water.getY() > plantTop.getY()
                || protectedCell.test(water) || !LandingOptions.canPlace(LandingAid.WATER, context.level(), water)) {
            return false;
        }
        for (int y = feet.getY(); water.getY() >= feet.getY() && y < plantTop.getY(); y++) {
            if (protectedCell.test(new BlockPos(feet.getX(), y, feet.getZ()))) return false;
        }
        var actual = new LandingPlan(LandingAid.WATER, feet, water, hit.getBlockPos(), hit.getDirection(), false,
                hit.getLocation());
        if (!safe(context, player, actual)) return false;
        plan = actual;
        return true;
    }

    // 这一格水在不在身子正下方那一列、而且在脚下（不往头顶上放）。
    private static boolean belowPlayer(LocalPlayer player, BlockPos water) {
        return water.getX() == player.getBlockX() && water.getZ() == player.getBlockZ() && water.getY() <= player.getY();
    }

    private void refreshWaterAim(PlayerContext context, LocalPlayer player) {
        if (plan.aid() != LandingAid.WATER || plan.existing() || submitted) return;
        var hit = BucketWater.floorHit(context.level(), plan.feet(), player.getEyePosition());
        if (hit != null) acceptWaterHit(context, player, hit);
    }

    BlockHitResult trace(PlayerContext context, LocalPlayer player, boolean pickup) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1).scale(player.blockInteractionRange()));
        return context.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                pickup ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, player));
    }

    // 离必须出手还有几刻：脚下离落点那一面还高出够得着的距离时，按重力往下推。
    private int remainingActionTicks(PlayerContext context, LocalPlayer player) {
        double reach = player.blockInteractionRange();
        double actionHeight = plan.aimPoint().y + Math.max(0, Math.sqrt(Math.max(0, reach * reach - 0.5))
                - player.getEyeHeight());
        double y = player.getY();
        double velocity = player.getDeltaMovement().y;
        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        for (int ticks = 0; ticks < 200; ticks++) {
            if (y <= actionHeight) return ticks;
            y += velocity;
            velocity = (velocity - gravity) * .98;
        }
        return 200;
    }

    boolean safe(PlayerContext context, LocalPlayer player, LandingPlan candidate) {
        return LandingGeometry.safe(context.level(), context.level()::isLoaded, candidate,
                player.getBbWidth(), Math.max(1.8, player.getBbHeight()), noEntryCells);
    }

    boolean safeAfterRemoval(PlayerContext context, LocalPlayer player) {
        return LandingGeometry.safeAfterRemoval(context.level(), context.level()::isLoaded, plan,
                player.getBbWidth(), Math.max(1.8, player.getBbHeight()), noEntryCells);
    }

    boolean protectedNow(BlockPos cell) {
        return protectedCell.test(cell);
    }

    private boolean touchesPlannedWater(PlayerContext context, LocalPlayer player) {
        BlockState source = context.level().getBlockState(plan.cell());
        return LandingAid.WATER.cushions(source) && player.getBoundingBox().intersects(
                source.getFluidState().getShape(context.level(), plan.cell()).bounds().move(plan.cell()));
    }

    private boolean nearLanding(LocalPlayer player) {
        return player.position().distanceToSqr(Vec3.atBottomCenterOf(plan.feet())) < 4;
    }

    private boolean survivesHay(LocalPlayer player) {
        return plan.aid() != LandingAid.HAY || plan.survives(FallDamage.capture(player), player.getY(), true);
    }

    private static boolean carries(LocalPlayer player, LandingAid aid) {
        if (player.getOffhandItem().is(aid.item())) return true;
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).is(aid.item())) return true;
        }
        return false;
    }

    // 落地后等几刻再认定：按网络延迟算，延迟越高血量同步越晚。
    private static int dwell(PlayerContext context) {
        int roundTrip = NetworkDelay.roundTripTicks(context);
        return roundTrip < 0 ? 10 : Math.clamp(4 + roundTrip, 4, 40);
    }

    private void rememberHealth(LocalPlayer player) {
        previousHealth = player.getHealth();
        previousAbsorption = player.getAbsorptionAmount();
    }

    // 收场：干草要真踩上去、活着；其余要没掉血；水还要真碰到水。
    void finish(String outcome) {
        complete = true;
        if (plan.aid() == LandingAid.HAY) {
            if (!(previousHealth > 0)) fail("落在干草上没活下来");
            else if (!hayContact) fail("落地了，但没看到自己踩在干草上");
            else detail = damageObserved ? "落在干草上活下来了，掉了 " + healthLost + " 点血" : outcome;
        } else if (damageObserved) {
            fail("落地了，但掉了 " + healthLost + " 点血，不算免伤");
        } else if (plan.aid() == LandingAid.WATER && !waterContact) {
            fail("落地了，但没看到自己落进水里");
        } else {
            detail = outcome;
        }
    }

    void fail(String reason) {
        failed = true;
        detail = reason;
    }

    private boolean failNow(String reason) {
        fail(reason);
        return false;
    }

    // 收回那一段要读写的几个字段，交给收回的那一步。
    LandingItemInHand item() {
        return item;
    }

    BlockState placed() {
        return placed;
    }

    long recoveryStarted(long now) {
        if (recoveryStarted == Long.MIN_VALUE) recoveryStarted = now;
        return recoveryStarted;
    }

    boolean recoveryExpired(long now) {
        return now - recoveryStarted(now) >= RECOVERY_LIMIT_TICKS;
    }

    void beginCleaning(PendingInteraction removal) {
        cleaning = true;
        pending = removal;
    }

    static String name(LandingAid aid) {
        return switch (aid) {
            case WATER -> "水";
            case COBWEB -> "蛛网";
            case SWEET_BERRIES -> "甜浆果丛";
            case TWISTING_VINES -> "缠怨藤";
            case WEEPING_VINES -> "垂泪藤";
            case SLIME -> "黏液块";
            case HAY -> "干草块";
        };
    }
}
