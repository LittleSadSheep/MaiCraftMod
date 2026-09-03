package org.maiwithu.maicraft.core.act;

import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.FailureType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * First-person interaction primitive for the local player: aim the camera at a target,
 * then submit and reconcile one native button press
 * one mouse button (left = ATTACK, right = USE) with a {@link Timing}. Every
 * higher-level action is a thin layer on top: mining = ATTACK a block (hold),
 * {@code attack} = ATTACK an entity, eat/bow = hold USE in the air.
 *
 * <h2>Native dispatch</h2>
 * <ul>
 *   <li>ATTACK + block  → {@link BlockDigger} with a progressive break receipt;</li>
 *   <li>ATTACK + entity → a cooldown-gated native attack receipt;</li>
 *   <li>USE + block     → a native block-use receipt with loaded-world confirmation;</li>
 *   <li>USE + entity    → a native interaction receipt with inventory/menu/riding confirmation;</li>
 *   <li>USE + air       → a native item-use receipt, optionally followed by a release receipt.</li>
 * </ul>
 *
 * <p>准星语义的 USE({@link #forHit} 建的)另有一步收尾:方块/实体没吃掉点击时落到
 * 物品自用——真客户端的完整右键顺序。指定命中面的外科
 * 原语({@link #useBlock(LocalPlayer, BlockHitResult, InteractionHand)})没有这一步。
 *
 * <h2>Timing</h2>
 * {@link Timing#once()} taps once; {@link Timing#repeat} taps N times spaced by an
 * interval (auto-click a button, grind a mob); {@link Timing#hold()} holds the
 * button until the action self-completes (a block breaks, food finishes);
 * {@link Timing#hold(int)} holds up to N ticks then releases (draw + loose a bow).
 *
 * <p>Stateful + ticked (like {@link BlockDigger} / {@code PlayerNav}). The caller
 * walks the body within reach first; this only aims and presses.
 */
public final class Interaction {

    public enum Status { RUNNING, DONE, FAILED }
    public enum Button { ATTACK, USE }

    /** Vanilla block-interaction reach (survival); creative is 5. */
    private static final double REACH = 4.5;

    /**
     * When and how often the button fires
     * (once / continuous / interval). {@code hold} actions press-and-hold until
     * the action finishes on its own (breaking, eating) or {@code maxHold} elapses
     * (bow); discrete actions fire {@code limit} times spaced by {@code interval}.
     */
    public static final class Timing {
        final boolean hold;
        final int limit;     // discrete fires (>=1); ignored for hold
        final int interval;  // ticks between discrete fires (>=1)
        final int maxHold;   // hold: release after this many ticks; 0 = until self-complete

        private Timing(boolean hold, int limit, int interval, int maxHold) {
            this.hold = hold;
            this.limit = limit;
            this.interval = interval;
            this.maxHold = maxHold;
        }

        /** One single press. */
        public static Timing once() {
            return new Timing(false, 1, 1, 0);
        }

        /** {@code times} presses, each spaced {@code interval} ticks apart. */
        public static Timing repeat(int times, int interval) {
            return new Timing(false, Math.max(1, times), Math.max(1, interval), 0);
        }

        /** Hold until the action finishes on its own (block broken / food eaten). */
        public static Timing hold() {
            return new Timing(true, -1, 1, 0);
        }

        /** Hold up to {@code maxTicks}, then release (e.g. draw a bow and loose). */
        public static Timing hold(int maxTicks) {
            return new Timing(true, -1, 1, Math.max(1, maxTicks));
        }
    }

    private final LocalPlayer player;
    private final Button button;
    private final BlockPos block;     // non-null → block target
    private final Entity entity;      // non-null → entity target
    private final InteractionHand hand;
    private final Timing timing;

    private final BlockDigger digger; // only for ATTACK + block
    private BlockHitResult presetHit; // USE+block: an exact hit the caller already resolved (placement)
    /**
     * 准星语义的 USE 才有的兜底:方块/实体没吃掉点击时,同一次按键落到物品自用
     * ——真客户端也是方块/实体未消费点击后再尝试物品自用；桶找水、船找水面、
     * 掷物出手都住在那条路上。
     * 外科原语(指定命中面的放置、开台)不设兜底:那里落空就该落空,兜底会把
     * 手里的东西扔出去。false = 兜底关闭或被任务层否决(身体约束物品)。
     */
    private boolean itemFallthrough;
    private MenuReceipt closeReceipt;
    private NativeActionReceipt receipt;
    private boolean fallingThrough;
    private boolean releasing;
    private static final int CONFIRM_TIMEOUT_TICKS = 20;
    private int fires;
    private int cooldown;             // ticks until the next discrete press
    private int held;                 // USE+air: ticks held so far
    private boolean hardFail;         // a fire hit an unrecoverable error
    private String failReason = "interaction failed";
    private FailureType failType = FailureType.UNKNOWN;
    private String lastUseOutcome = "not fired";
    /** Low-pass aim point retained only by one entity interaction instance. */
    private Vec3 trackedEntityAim;

    private Interaction(LocalPlayer player, Button button, BlockPos block, Entity entity,
                        InteractionHand hand, Timing timing) {
        this.player = player;
        this.button = button;
        this.block = block == null ? null : block.immutable();
        this.entity = entity;
        this.hand = hand;
        this.timing = timing;
        this.digger = (button == Button.ATTACK && block != null) ? new BlockDigger(player) : null;
    }

    // ---- factories (default timings; overloads take an explicit Timing) ----

    /** Left-click a block: break it (held until gone; creative insta / survival timed). */
    public static Interaction attackBlock(LocalPlayer p, BlockPos pos) {
        return new Interaction(p, Button.ATTACK, pos, null, InteractionHand.MAIN_HAND, Timing.hold());
    }

    /** Left-click an entity once (cooldown-gated native attack). */
    public static Interaction attackEntity(LocalPlayer p, Entity target) {
        return attackEntity(p, target, Timing.once());
    }

    public static Interaction attackEntity(LocalPlayer p, Entity target, Timing timing) {
        return new Interaction(p, Button.ATTACK, null, target, InteractionHand.MAIN_HAND, timing);
    }

    /** Right-click a block: place / activate with the held item (raycasts to {@code pos}). */
    public static Interaction useBlock(LocalPlayer p, BlockPos pos, InteractionHand hand) {
        return new Interaction(p, Button.USE, pos, null, hand, Timing.once());
    }

    /** Right-click a pre-resolved block hit — placement / precise activation supplies
     *  the exact support face, so this skips the raycast and presses against {@code hit}. */
    public static Interaction useBlock(LocalPlayer p, BlockHitResult hit, InteractionHand hand) {
        Interaction i = new Interaction(p, Button.USE, hit.getBlockPos(), null, hand, Timing.once());
        i.presetHit = hit;
        return i;
    }

    /** Right-click in the air with the held item, on the given {@link Timing}
     *  ({@code hold()} eats food / {@code hold(n)} draws and looses a bow). */
    public static Interaction useInAir(LocalPlayer p, InteractionHand hand, Timing timing) {
        return new Interaction(p, Button.USE, null, null, hand, timing);
    }

    /** vanilla {@code Minecraft.rightClickDelay} — held right-click re-fires this often. */
    private static final int RIGHT_CLICK_DELAY = 4;
    /** A "hold forever" fire count; the owning task stops us after hold_ticks / on completion. */
    private static final int CONTINUOUS = 1_000_000;

    /**
     * The vanilla crosshair pick: one ray from the eyes along the CURRENT
     * look, resolving the CLOSER of a block or an entity (else MISS). A wall occludes a mob behind
     * it (entities are searched only as near as the block hit). {@code reach} 4.5 = survival.
     */
    public static HitResult nativeRaytrace(LocalPlayer player, double reach) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 reachVec = player.getViewVector(1.0f).scale(reach);
        Vec3 end = eye.add(reachVec);
        BlockHitResult block = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double maxSq = block.getType() == HitResult.Type.MISS
                ? reach * reach : block.getLocation().distanceToSqr(eye);
        AABB box = player.getBoundingBox().expandTowards(reachVec).inflate(1.0);
        EntityHitResult ent = ProjectileUtil.getEntityHitResult(
                player, eye, end, box, e -> !e.isSpectator() && e.isPickable(), maxSq);
        return ent != null ? ent : block;   // entity (closer than the block) wins, else the block/miss
    }

    /**
     * Build the native action for a resolved crosshair {@code hit} + {@code button}, mapping
     * {@code holdTicks} to the cell's natural cadence — a 6-cell (button × target) dispatch:
     * <ul>
     *   <li>ATTACK·BLOCK → break (BlockDigger holds till the block is gone);</li>
     *   <li>ATTACK·ENTITY → hit (tap = one cooldown-gated hit; hold = keep hitting);</li>
     *   <li>USE·BLOCK → activate (tap once; hold re-clicks every rightClickDelay — modded crank);</li>
     *   <li>USE·ENTITY → interact (tap once; hold re-clicks);</li>
     *   <li>USE·AIR → useItem (tap = throw; hold = charge/eat up to ticks, or self-complete);</li>
     *   <li>ATTACK·AIR → {@code null} (left-click air does nothing).</li>
     * </ul>
     * {@code holdTicks}: 0 = tap, &gt;0 / -1 = hold. The block/entity hit is used as-is (the
     * native raytrace already resolved the exact face/point — no re-raycast). The caller drives
     * the returned object to completion and enforces the hold duration.
     *
     * @param itemFallthrough USE 的准星兜底开关(见 {@link #itemFallthrough}):方块/实体
     *                        没吃掉点击就落到物品自用。任务层拿它挡身体约束物品——
     *                        手里是食物/末影珍珠时传 false,免得点了块石头把自己喂了。
     */
    public static Interaction forHit(LocalPlayer p, HitResult hit, Button button, int holdTicks,
                                     boolean itemFallthrough) {
        boolean hold = holdTicks != 0;
        switch (hit.getType()) {
            case BLOCK -> {
                BlockHitResult bh = (BlockHitResult) hit;
                if (button == Button.ATTACK) {
                    return attackBlock(p, bh.getBlockPos());
                }
                Interaction i = new Interaction(p, Button.USE, bh.getBlockPos(), null,
                        InteractionHand.MAIN_HAND,
                        hold ? Timing.repeat(CONTINUOUS, RIGHT_CLICK_DELAY) : Timing.once());
                i.presetHit = bh;   // use the robust native hit, no re-raycast
                i.itemFallthrough = itemFallthrough;
                return i;
            }
            case ENTITY -> {
                Entity e = ((EntityHitResult) hit).getEntity();
                if (button == Button.ATTACK) {
                    return attackEntity(p, e, hold ? Timing.repeat(CONTINUOUS, 1) : Timing.once());
                }
                Interaction i = new Interaction(p, Button.USE, null, e, InteractionHand.MAIN_HAND,
                        hold ? Timing.repeat(CONTINUOUS, RIGHT_CLICK_DELAY) : Timing.once());
                i.itemFallthrough = itemFallthrough;
                return i;
            }
            default -> {   // MISS = air
                if (button == Button.ATTACK) {
                    return null;
                }
                Timing t = hold ? (holdTicks > 0 ? Timing.hold(holdTicks) : Timing.hold()) : Timing.once();
                return useInAir(p, InteractionHand.MAIN_HAND, t);
            }
        }
    }

    public String failReason() {
        return failReason;
    }

    /** Structured cause of a {@link Status#FAILED}, for the reactive task layer to branch on. */
    public FailureType failType() {
        return failType;
    }

    public Status tick() {
        if (receipt == null
                && (closeReceipt != null || player.containerMenu != player.inventoryMenu
                || net.minecraft.client.Minecraft.getInstance().screen != null)) {
            Status menuStatus = awaitWorldInputReady();
            if (menuStatus != null) {
                return menuStatus;
            }
        }
        if (button == Button.ATTACK && block != null) {
            return breakBlock();                       // inherently continuous
        }
        if (button == Button.USE && block == null && entity == null) {
            return useAir();
        }
        return discrete();                             // attack entity / use block / use entity
    }

    /** World buttons cannot be pressed through an open container screen. */
    private Status awaitWorldInputReady() {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (closeReceipt == null) {
            closeReceipt = context.menus().close(context, CONFIRM_TIMEOUT_TICKS);
            return Status.RUNNING;
        }
        if (!closeReceipt.terminal()) {
            closeReceipt = context.menus().poll(context, closeReceipt);
        }
        if (!closeReceipt.terminal()) {
            return Status.RUNNING;
        }
        MenuReceipt.Status status = closeReceipt.status();
        String detail = closeReceipt.detail();
        closeReceipt = null;
        if (status == MenuReceipt.Status.CONFIRMED_APPLIED) {
            return context.mutationAvailable() ? null : Status.RUNNING;
        }
        failReason = "could not close the active menu before the world action: " + detail;
        failType = FailureType.UNKNOWN;
        return Status.FAILED;
    }

    // ---- ATTACK + block: continuous break ----

    private Status breakBlock() {
        if (!player.level().isLoaded(block)) {
            failType = FailureType.TARGET_LOST;
            failReason = "the target block is outside the local client's loaded world";
            return Status.FAILED;
        }
        if (player.level().getBlockState(block).isAir()) return Status.DONE;
        BlockDigger.DigResult result = digger.digStep(block);
        if (result == BlockDigger.DigResult.BROKE_TARGET) {
            return Status.DONE;
        }
        if (result == BlockDigger.DigResult.NO_SHOT) {
            boolean beyondReach = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(block))
                    > REACH * REACH;
            failType = beyondReach ? FailureType.OUT_OF_REACH : FailureType.OCCLUDED;
            failReason = beyondReach
                    ? "the target block is outside first-person reach"
                    : "no visible safe face or removable occluder reaches the target block";
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    // ---- USE + air: tap or hold (food / bow) ----

    private Status useAir() {
        InputDriver.halt(player);
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            ItemStack before = player.getItemInHand(hand).copy();
            int beforeMenu = player.containerMenu.containerId;
            NativeConfirmation confirmation = NativeConfirmation.anyOf(
                    NativeConfirmation.heldItemChanged(hand, before),
                    NativeConfirmation.menuChanged(beforeMenu),
                    c -> c.player().isUsingItem()
                            ? NativeConfirmation.Verdict.APPLIED
                            : NativeConfirmation.Verdict.PENDING);
            receipt = context.actions().useItem(
                    context, hand, confirmation, CONFIRM_TIMEOUT_TICKS);
            return Status.RUNNING;
        }
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) {
            return Status.RUNNING;
        }
        if (releasing) {
            if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                return Status.DONE;
            }
            failReason = "native item release was not confirmed: " + receipt.detail();
            return Status.FAILED;
        }
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            failReason = "native item use was not confirmed: " + receipt.detail();
            failType = FailureType.UNKNOWN;
            return Status.FAILED;
        }
        if (!timing.hold) {
            return Status.DONE;
        }
        if (!player.isUsingItem()) {
            return Status.DONE;
        }
        if (timing.maxHold > 0 && ++held >= timing.maxHold) {
            receipt = context.actions().releaseUsingItem(context, receipt);
            releasing = true;
            return Status.RUNNING;
        }
        return Status.RUNNING;
    }

    // ---- discrete: attack entity / use block / use entity (once or repeat) ----

    private Status discrete() {
        if (cooldown > 0) {
            cooldown--;
            return Status.RUNNING;
        }
        boolean fired = switch (button) {
            case ATTACK -> fireAttackEntity();
            case USE -> entity != null ? fireUseEntity() : fireUseBlock();
        };
        if (hardFail) return Status.FAILED;
        if (!fired) return Status.RUNNING;             // soft wait (attack cooldown not ready)
        if (++fires >= timing.limit) return Status.DONE;
        cooldown = timing.interval;
        return Status.RUNNING;
    }

    private boolean fireAttackEntity() {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) {
                return false;
            }
            NativeActionReceipt.Status status = receipt.status();
            String detail = receipt.detail();
            receipt = null;
            if (status == NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                return true;
            }
            failReason = "native attack was not confirmed: " + detail;
            failType = FailureType.UNKNOWN;
            hardFail = true;
            return false;
        }
        if (entity == null || !entity.isAlive()) {
            failReason = "the attack target is gone";
            failType = FailureType.TARGET_LOST;
            hardFail = true;
            return false;
        }
        InputDriver.halt(player);
        Vec3 aimPoint = stableEntityAimPoint(entity);
        InputDriver.lookAt(player, aimPoint);
        if (!aimReady(aimPoint)) {
            return false;
        }
        HitResult aimed = nativeRaytrace(player, REACH);
        if (!(aimed instanceof EntityHitResult entityHit)
                || entityHit.getEntity() != entity) {
            return false;
        }
        boolean recovering = entity instanceof net.minecraft.world.entity.LivingEntity living
                && living.hurtTime > 0;
        if (!org.maiwithu.maicraft.core.combat.Swing.mayStrike(
                false, recovering, player.getAttackStrengthScale(0.0f))) {
            return false;
        }
        receipt = context.actions().attack(
                context,
                entity,
                NativeConfirmation.entityHurt(entity),
                CONFIRM_TIMEOUT_TICKS);
        return false;
    }

    private boolean fireUseBlock() {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            return settleUseReceipt(context, "block use");
        }
        if (!player.level().isLoaded(block)) {
            failReason = "the target block is outside the local client's loaded world";
            failType = FailureType.TARGET_LOST;
            hardFail = true;
            return false;
        }
        InputDriver.halt(player);
        BlockHitResult hit;
        if (presetHit != null) {
            InputDriver.lookAt(player, presetHit.getLocation());
            HitResult aimed = nativeRaytrace(player, REACH);
            if (!(aimed instanceof BlockHitResult blockHit)
                    || !blockHit.getBlockPos().equals(block)
                    || blockHit.getDirection() != presetHit.getDirection()) {
                return false;
            }
            hit = blockHit;
        } else {
            InputDriver.lookAt(player, Vec3.atCenterOf(block));
            hit = raycastBlock();
            if (hit == null) {
                return false;
            }
        }
        if (!aimReady(hit.getLocation())) {
            return false;
        }
        if (fallingThrough) {
            ItemStack before = player.getItemInHand(hand).copy();
            receipt = context.actions().useItem(
                    context, hand, itemUseConfirmation(hand, before),
                    CONFIRM_TIMEOUT_TICKS);
        } else {
            receipt = context.actions().useBlock(
                    context, hand, hit, blockUseConfirmation(hit, hand),
                    CONFIRM_TIMEOUT_TICKS);
        }
        return false;
    }

    private boolean settleUseReceipt(LocalPlayerContext context, String action) {
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) {
            return false;
        }
        NativeActionReceipt.Status status = receipt.status();
        String detail = receipt.detail();
        receipt = null;
        if (status == NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            lastUseOutcome = "confirmed (" + action + ")";
            fallingThrough = false;
            return true;
        }
        if (itemFallthrough && !fallingThrough) {
            fallingThrough = true;
            return false;
        }
        failReason = action + " was not confirmed: " + detail;
        failType = FailureType.UNKNOWN;
        hardFail = true;
        return false;
    }

    private NativeConfirmation blockUseConfirmation(BlockHitResult hit, InteractionHand usedHand) {
        BlockPos clicked = hit.getBlockPos().immutable();
        BlockPos adjacent = clicked.relative(hit.getDirection()).immutable();
        var clickedBefore = player.level().getBlockState(clicked);
        ItemStack heldBefore = player.getItemInHand(usedHand).copy();
        int beforeMenu = player.containerMenu.containerId;
        NativeConfirmation adjacentChanged = player.level().isLoaded(adjacent)
                ? NativeConfirmation.blockChanged(
                        adjacent, player.level().getBlockState(adjacent))
                : NativeConfirmation.pending();
        return NativeConfirmation.anyOf(
                NativeConfirmation.blockChanged(clicked, clickedBefore),
                adjacentChanged,
                NativeConfirmation.heldItemChanged(usedHand, heldBefore),
                NativeConfirmation.menuChanged(beforeMenu));
    }

    private NativeConfirmation itemUseConfirmation(InteractionHand usedHand, ItemStack heldBefore) {
        int beforeMenu = player.containerMenu.containerId;
        return NativeConfirmation.anyOf(
                NativeConfirmation.heldItemChanged(usedHand, heldBefore),
                NativeConfirmation.menuChanged(beforeMenu),
                c -> c.player().isUsingItem()
                        ? NativeConfirmation.Verdict.APPLIED
                        : NativeConfirmation.Verdict.PENDING);
    }

    /**
     * The vanilla verdict of the most recent USE-on-block press: {@code "consumed (...)"}
     * or the per-hand results (e.g. {@code "main_hand=FAIL, off_hand=PASS"}). A press that
     * consumes can STILL have placed nothing (the item's own rules refused) — placement
     * callers must verify the world afterwards, and this string is what they log when a
     * press quietly did nothing.
     */
    public String lastUseOutcome() {
        return lastUseOutcome;
    }

    private boolean fireUseEntity() {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            return settleUseReceipt(context, "entity interaction");
        }
        if (entity == null || !entity.isAlive()) {
            failReason = "the entity is gone";
            failType = FailureType.TARGET_LOST;
            hardFail = true;
            return false;
        }
        InputDriver.halt(player);
        InputDriver.lookAt(player, entity.getEyePosition());
        if (!aimReady(entity.getEyePosition())) {
            return false;
        }
        HitResult aimed = nativeRaytrace(player, REACH);
        if (!(aimed instanceof EntityHitResult entityHit)
                || entityHit.getEntity() != entity) {
            return false;
        }
        if (fallingThrough) {
            ItemStack before = player.getItemInHand(hand).copy();
            receipt = context.actions().useItem(
                    context, hand, itemUseConfirmation(hand, before),
                    CONFIRM_TIMEOUT_TICKS);
        } else {
            receipt = context.actions().interact(
                    context, entity, hand, entityUseConfirmation(entity, hand),
                    CONFIRM_TIMEOUT_TICKS);
        }
        return false;
    }

    private NativeConfirmation entityUseConfirmation(Entity target, InteractionHand usedHand) {
        Entity vehicleBefore = player.getVehicle();
        int passengersBefore = target.getPassengers().size();
        ItemStack heldBefore = player.getItemInHand(usedHand).copy();
        int beforeMenu = player.containerMenu.containerId;
        NativeConfirmation ridingChanged = c ->
                c.player().getVehicle() != vehicleBefore
                        || target.getPassengers().size() != passengersBefore
                        ? NativeConfirmation.Verdict.APPLIED
                        : NativeConfirmation.Verdict.PENDING;
        return NativeConfirmation.anyOf(
                NativeConfirmation.heldItemChanged(usedHand, heldBefore),
                NativeConfirmation.menuChanged(beforeMenu),
                ridingChanged);
    }

    private boolean aimReady(Vec3 target) {
        Vec3 direction = target.subtract(player.getEyePosition());
        if (direction.lengthSqr() < 1.0e-8) {
            return true;
        }
        return player.getViewVector(1.0f).normalize().dot(direction.normalize())
                >= Math.cos(Math.toRadians(7.0));
    }

    /**
     * Aim inside the target's current hit box, slightly toward its measured motion. The retained
     * point damps packet-to-packet velocity noise; clamping it back into an inset box guarantees
     * that prediction can never lead so far that the native crosshair intentionally misses.
     */
    private Vec3 stableEntityAimPoint(Entity target) {
        AABB box = target.getBoundingBox();
        Vec3 center = box.getCenter();
        Vec3 motion = target.getDeltaMovement();
        double leadTicks = Mth.clamp(player.distanceTo(target) * 0.35, 0.6, 1.5);
        Vec3 wanted = center.add(
                motion.x * leadTicks,
                motion.y * Math.min(leadTicks, 0.75),
                motion.z * leadTicks);
        wanted = insetClamp(wanted, box);
        if (trackedEntityAim == null || trackedEntityAim.distanceToSqr(wanted) > 4.0) {
            trackedEntityAim = wanted;
        } else {
            trackedEntityAim = insetClamp(trackedEntityAim.lerp(wanted, 0.55), box);
        }
        return trackedEntityAim;
    }

    private static Vec3 insetClamp(Vec3 point, AABB box) {
        double insetX = Math.min(0.12, box.getXsize() * 0.18);
        double insetY = Math.min(0.16, box.getYsize() * 0.18);
        double insetZ = Math.min(0.12, box.getZsize() * 0.18);
        return new Vec3(
                Mth.clamp(point.x, box.minX + insetX, box.maxX - insetX),
                Mth.clamp(point.y, box.minY + insetY, box.maxY - insetY),
                Mth.clamp(point.z, box.minZ + insetZ, box.maxZ - insetZ));
    }

    /** Raycast from the eyes along the current look; the hit must be the target block. */
    private BlockHitResult raycastBlock() {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        Vec3 end = eye.add(look.x * REACH, look.y * REACH, look.z * REACH);
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block)) {
            return hit;
        }
        return null;
    }

    /** Stop issuing work. Native use release still owns a receipt and must be drained by the runtime. */
    public void stop() {
        if (digger != null) digger.cancel();
        if (receipt != null && receipt.kind() == NativeActionReceipt.Kind.USE_ITEM
                && player.isUsingItem() && !releasing) {
            LocalPlayerContext context = ClientRuntime.requireContext(player);
            receipt = context.actions().releaseUsingItem(context, receipt);
            releasing = true;
        }
        InputDriver.halt(player);
    }
}
