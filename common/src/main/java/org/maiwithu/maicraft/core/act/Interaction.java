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
import net.minecraft.world.level.block.Block;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 把一次鼠标操作分成几刻完成：先瞄准，再出手，再观察有没有生效。
 * 左键方块是持续挖掘，左键实体是攻击；右键分别对应点方块、点实体或使用手中物品。
 * 它负责停步、转头和点击，不会自己走近目标。需要靠近时由外层任务先寻路。
 */
public final class Interaction {

    public enum Status { RUNNING, DONE, FAILED }
    public enum Button { ATTACK, USE }

    /** 当前统一按 4.5 格计算；这里没有随创造模式或玩家触及距离属性变化。 */
    private static final double REACH = 4.5;

    /**
     * 说明按一下、重复按，还是持续按住。例如拉弓持续按住，开箱子按一下。
     * 重复动作按 limit 计次；持续使用物品按 maxHold 决定何时松开，零表示等物品自行结束。
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

        /** 只完成一次点击。 */
        public static Timing once() {
            return new Timing(false, 1, 1, 0);
        }

        /** 重复点击；两次之间先等待 interval 次 tick，次数和间隔至少为一。 */
        public static Timing repeat(int times, int interval) {
            return new Timing(false, Math.max(1, times), Math.max(1, interval), 0);
        }

        /** 持续使用，直到食物吃完等行为自行结束。 */
        public static Timing hold() {
            return new Timing(true, -1, 1, 0);
        }

        /** 持续使用指定刻数后松开，例如拉弓后放箭；具体计数从使用得到确认后开始。 */
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
    private BlockPos requiredBlockPos;
    private Block requiredBlock;
    /**
     * 是否允许点方块／实体没有确认成功后，再使用手里的物品，例如扔出雪球。
     * 目前连“不确定是否已经生效”也会进入这一步，不能把它解释成已确认对方没有处理点击。
     * 精确 useBlock 默认关闭；forHit 的调用方自行决定是否开启。
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
    // 沿玩家当前准星看出去。先找到挡路方块，再看前面是否有更近的可点击实体，不能点穿墙。
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
     * 根据准星命中的东西决定做法：左键方块就挖，左键实体就打，右键则交互或使用物品。
     * holdTicks 为零表示点一下；非零时对实体／方块反复点，对空气则持续使用物品。
     * 实体／方块的最长按住时间由外层任务控制，这里只设置一个很大的重复次数。
     * 创建时记住目标，真正出手前仍会检查准星；左键空气直接返回 null，表示没有可做的动作。
     *
     * @param itemFallthrough 点击没有确认成功时，是否允许再尝试使用手里的物品。
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

    /** Freeze semantic target identity without mistaking an expected resulting block for an input. */
    public Interaction requireBlock(BlockPos position, Block required) {
        if (required != null && position == null) throw new IllegalArgumentException("required block needs a position");
        requiredBlockPos = position == null ? null : position.immutable();
        requiredBlock = required;
        return this;
    }

    // 需要特定方块时，每次新右键前都核对它还在那里，防止箱子被换成别的东西后继续点。
    private boolean requiredBlockPresent() {
        if (requiredBlock == null || player.level().isLoaded(requiredBlockPos)
                && player.level().getBlockState(requiredBlockPos).is(requiredBlock)) return true;
        failReason = "the required interaction target changed or unloaded before native use";
        failType = FailureType.TARGET_LOST;
        hardFail = true;
        return false;
    }

    /** Structured cause of a {@link Status#FAILED}, for the reactive task layer to branch on. */
    public FailureType failType() {
        return failType;
    }

    // 先处理还没关好的界面，再分别推进挖方块、对空气使用物品或离散点击。
    // 已经发出的动作优先等结果，不因这次动作刚打开了箱子就立刻把箱子关上。
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
    // 发一次关闭菜单请求，然后逐刻等它完成；关闭失败就不能继续向世界点击。
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

    // 目标已经是空气就算挖完；否则交给 BlockDigger 逐刻挖。看不到可挖面时区分太远还是被挡住。
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

    // 例如吃东西：先按一次右键，观察手中物品、菜单或使用状态，再决定继续按住还是松开。
    // 点一下只要求这次使用得到确认；持续使用才会等到吃完或到达按住时限。
    private Status useAir() {
        InputDriver.halt(player);
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            if (!requiredBlockPresent()) return Status.FAILED;
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
        // 已经发出松开请求时，只等待松开结果，不重新开始使用。
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

    // 一次点击没确认完就不计次；确认一次后先等间隔，再开始下一次。
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

    // 上次攻击尚未确认就继续等；准备新攻击时先停止移动、瞄准活着的目标，
    // 还要等攻击冷却和目标的短暂无敌时间结束，才发出攻击。
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

    // 每次新点击都重新瞄准目标。指定面的操作还必须让当前准星真正落在那个面上。
    private boolean fireUseBlock() {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            return settleUseReceipt(context, "block use");
        }
        if (!requiredBlockPresent()) return false;
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

    // 确认成功才算完成一次。若允许物品兜底，任何其他终态都先转去使用手中物品，
    // 目前没有区分“确认没生效”和“结果不确定”；这是审计记录 A29 的问题。
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

    // 点击格、相邻格、手中物品或菜单只要有一项变化，就作为点击已生效的线索。
    // 它没有验证每种物品真正想产生的完整结果，也没有证明变化一定由这次点击造成。
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
     * 最近一次使用动作的简短说明。目前成功时只写 confirmed，未出手时为 not fired，
     * 不包含原版每只手的 PASS／FAIL 返回值，也不能拿这段文字代替成品检查。
     */
    public String lastUseOutcome() {
        return lastUseOutcome;
    }

    // 目标仍活着、准星也确实点到它，才提交交互；需要物品兜底时仍沿用相同目标检查。
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

    // 用手中物品、打开的菜单或乘坐关系变化来判断实体交互；其他变化如羊是否被剪毛不在这份观察里。
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

    // 实际视线与目标差不超过 7° 才算转头到位；后续射线检查还要确认没有点到别的东西。
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
    // 近战目标在移动时稍微朝它前方瞄，并把瞄点留在身体框内。
    // 相邻帧只移动一部分瞄点，避免怪物小幅晃动就带着镜头猛抖。
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
    // 挖掘交给 digger 取消；正在持续使用物品就发出松开，然后停止移动。
    // 普通方块／实体点击的未确认记录没有在此退役，外层结束时仍需处理它；否则会留下 A28 同类等待。
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
