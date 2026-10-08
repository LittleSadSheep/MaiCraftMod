// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.world.InteractionRange;

/**
 * 把一次鼠标操作分成几刻完成：先瞄准，再出手，再观察有没有生效。
 * 左键方块是持续挖掘，左键实体是攻击；右键分别对应点方块、点实体或使用手中物品。
 * 它负责停步、转头和点击，不会自己走近目标。需要靠近时由外层任务先寻路。
 * 交互提交、容器界面与按键入口都从构造函数传入；每刻推进时传入当刻的角色上下文。
 */
public final class Interaction {

    public enum Status { RUNNING, DONE, FAILED }
    public enum Button { ATTACK, USE }

    /**
     * 说明按一下、重复按，还是持续按住。例如拉弓持续按住，开箱子按一下。
     * 重复动作按 limit 计次；持续使用物品按 maxHold 决定何时松开，零表示等物品自行结束。
     */
    public static final class Timing {
        final boolean hold;
        final int limit;     // 重复操作至少执行一次；持续按住时忽略此值。
        final int interval;  // 重复点击之间的游戏刻数，至少为一。
        final int maxHold;   // 持续按住的最大游戏刻数；0 表示等待物品自行完成。

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

    private static final int CONFIRM_TIMEOUT_TICKS = 20;
    /** 原版 {@code Minecraft.rightClickDelay}：持续右键时每经过此间隔会重新触发一次。 */
    private static final int RIGHT_CLICK_DELAY = 4;
    /** 表示持续按住的高重复次数；拥有该操作的任务会在时限到达或行为完成后停止它。 */
    private static final int CONTINUOUS = 1_000_000;
    /** 攻击充能到 95% 就出手：剑的冷却是 12.5 刻而计数器是整数，等满要多花一整刻反而不划算；
     * 95% 同时高过原版判暴击的 90%，所以不影响暴击。 */
    private static final float ATTACK_READY = 0.95f;

    private final LocalPlayer player;
    private final InteractionSender sender;
    private final MenuActions menuActions;
    private final PlayerInput playerInput;
    private final Button button;
    private final BlockPos block;     // 非空时表示方块目标。
    private final Entity entity;      // 非空时表示实体目标。
    private final InteractionHand hand;
    private final Timing timing;

    private final BlockDigger digger; // 仅左键破坏方块时使用。
    private BlockHitResult presetHit; // 右键方块时可携带调用方已解析的精确命中，供放置使用。
    private BlockHitResult submittedBlockHit;
    private BlockPos requiredBlockPos;
    private Block requiredBlock;
    /**
     * 是否允许点方块／实体已确认没有生效后，再使用手里的物品，例如扔出雪球。
     * 确认超时或结果不一致时停下来检查现场，不能再追加一次物品使用。
     * 精确 useBlock 默认关闭；forHit 的调用方自行决定是否开启。
     */
    private boolean itemFallthrough;
    private PendingMenuAction closeReceipt;
    private PendingInteraction receipt;
    private PendingInteraction lastUseReceipt;
    private boolean fallingThrough;
    private boolean releasing;
    private int fires;
    private boolean finishRequested;
    private int cooldown;             // 距下一次重复点击还需等待的游戏刻数。
    private int held;                 // 右键空气时，物品已持续使用的游戏刻数。
    private boolean hardFail;         // 一次点击遇到不可恢复错误时阻止后续操作。
    private String failReason = "interaction failed";
    private String lastUseOutcome = "not fired";
    /** 仅在单次实体交互实例中保留并平滑瞄准点。 */
    private Vec3 trackedEntityAim;

    private Interaction(LocalPlayer player, InteractionSender sender, MenuActions menuActions,
                        PlayerInput playerInput, Button button, BlockPos block, Entity entity,
                        InteractionHand hand, Timing timing) {
        this.player = player;
        this.sender = sender;
        this.menuActions = menuActions;
        this.playerInput = playerInput;
        this.button = button;
        this.block = block == null ? null : block.immutable();
        this.entity = entity;
        this.hand = hand;
        this.timing = timing;
        this.digger = (button == Button.ATTACK && block != null)
                ? new BlockDigger(player, sender, menuActions, playerInput) : null;
    }

    /** 左键破坏方块：按住直至方块消失；创造模式立即破坏，生存模式按时间推进。 */
    public static Interaction attackBlock(PlayerContext context, InteractionSender sender, MenuActions menuActions,
                                          PlayerInput playerInput, BlockPos pos) {
        return new Interaction(context.localPlayer(), sender, menuActions, playerInput,
                Button.ATTACK, pos, null, InteractionHand.MAIN_HAND, Timing.hold());
    }

    /** 左键攻击实体一次，并遵循原版攻击冷却。 */
    public static Interaction attackEntity(PlayerContext context, InteractionSender sender, MenuActions menuActions,
                                           PlayerInput playerInput, Entity target) {
        return new Interaction(context.localPlayer(), sender, menuActions, playerInput,
                Button.ATTACK, null, target, InteractionHand.MAIN_HAND, Timing.once());
    }

    /** 右键 {@code pos} 方块，用手持物品放置方块或激活目标。 */
    public static Interaction useBlock(PlayerContext context, InteractionSender sender, MenuActions menuActions,
                                       PlayerInput playerInput, BlockPos pos, InteractionHand hand) {
        return new Interaction(context.localPlayer(), sender, menuActions, playerInput,
                Button.USE, pos, null, hand, Timing.once());
    }

    /** 右键调用方预先解析的方块命中；放置或精确激活已提供支撑面，因此跳过重新射线并直接对 {@code hit} 操作。 */
    public static Interaction useBlock(PlayerContext context, InteractionSender sender, MenuActions menuActions,
                                       PlayerInput playerInput, BlockHitResult hit, InteractionHand hand) {
        Interaction interaction = new Interaction(context.localPlayer(), sender, menuActions, playerInput,
                Button.USE, hit.getBlockPos(), null, hand, Timing.once());
        interaction.presetHit = hit;
        return interaction;
    }

    /** 按给定 {@link Timing} 在空气中右键使用手持物品；{@code hold()} 用于吃完食物，{@code hold(n)} 用于拉弓后放箭。 */
    public static Interaction useInAir(PlayerContext context, InteractionSender sender, MenuActions menuActions,
                                       PlayerInput playerInput, InteractionHand hand, Timing timing) {
        return new Interaction(context.localPlayer(), sender, menuActions, playerInput,
                Button.USE, null, null, hand, timing);
    }

    /**
     * 根据准星命中的东西决定做法：左键方块就挖，左键实体就打，右键则交互或使用物品。
     * holdTicks 为零表示点一下；非零时对实体／方块反复点，对空气则持续使用物品。
     * 实体／方块的最长按住时间由外层任务控制，这里只设置一个很大的重复次数。
     * 创建时记住目标，真正出手前仍会检查准星；左键空气直接返回 null，表示没有可做的动作。
     *
     * @param itemFallthrough 点击已确认未生效时，是否允许再尝试使用手里的物品。
     */
    public static Interaction forHit(PlayerContext context, InteractionSender sender, MenuActions menuActions,
                                     PlayerInput playerInput, HitResult hit, Button button, int holdTicks,
                                     boolean itemFallthrough) {
        LocalPlayer player = context.localPlayer();
        boolean hold = holdTicks != 0;
        switch (hit.getType()) {
            case BLOCK -> {
                BlockHitResult blockHit = (BlockHitResult) hit;
                if (button == Button.ATTACK) {
                    return attackBlock(context, sender, menuActions, playerInput, blockHit.getBlockPos());
                }
                Interaction interaction = new Interaction(player, sender, menuActions, playerInput,
                        Button.USE, blockHit.getBlockPos(), null, InteractionHand.MAIN_HAND,
                        hold ? Timing.repeat(CONTINUOUS, RIGHT_CLICK_DELAY) : Timing.once());
                interaction.presetHit = blockHit;   // 复用原生已确认的命中，避免重新计算射线。
                interaction.itemFallthrough = itemFallthrough;
                return interaction;
            }
            case ENTITY -> {
                Entity target = ((EntityHitResult) hit).getEntity();
                if (button == Button.ATTACK) {
                    return new Interaction(player, sender, menuActions, playerInput, Button.ATTACK,
                            null, target, InteractionHand.MAIN_HAND,
                            hold ? Timing.repeat(CONTINUOUS, 1) : Timing.once());
                }
                Interaction interaction = new Interaction(player, sender, menuActions, playerInput,
                        Button.USE, null, target, InteractionHand.MAIN_HAND,
                        hold ? Timing.repeat(CONTINUOUS, RIGHT_CLICK_DELAY) : Timing.once());
                interaction.itemFallthrough = itemFallthrough;
                return interaction;
            }
            default -> {   // MISS 表示准星指向空气。
                if (button == Button.ATTACK) {
                    return null;
                }
                Timing t = hold ? (holdTicks > 0 ? Timing.hold(holdTicks) : Timing.hold()) : Timing.once();
                return useInAir(context, sender, menuActions, playerInput, InteractionHand.MAIN_HAND, t);
            }
        }
    }

    /**
     * 按原版准星规则从眼部沿当前视线发出一条射线，返回更近的方块或实体，否则为 MISS。
     * 墙会遮住后方生物，实体只在方块命中位置以内搜索；{@code reach} 为 4.5 时对应生存模式距离。
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
        return ent != null ? ent : block;   // 实体比方块命中更近时优先返回实体，否则返回方块命中或未命中。
    }

    public String failReason() {
        return failReason;
    }

    /** 原生点击已发出时先结算，不能为恢复空手而移动刚取回的工件，破坏库存确认依据。 */
    public boolean awaitingReceipt() { return receipt != null; }

    /** 固定语义目标身份，避免把预计放置后的方块误当成新的输入目标。 */
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
        hardFail = true;
        return false;
    }

    /** 走位更新后续订同一近战目标的可见瞄准；不发攻击、不改移动，真正出手仍由下一次 tick 核对射线。 */
    public void renewEntityAttackAim() {
        if (button != Button.ATTACK || receipt != null || entity == null || !entity.isAlive()
                || player.level().getEntity(entity.getId()) != entity || hardFail) return;
        playerInput.lookAt(player, stableEntityAimPoint(entity));
    }

    /** 挥击已经提交时必须继续等它的确认结果；尚未提交的瞄准才允许因对手退出射程而撤销。 */
    public boolean entityAttackSubmitted() { return button == Button.ATTACK && receipt != null; }

    /** 只结算已提交的这一刀；死亡、丢失和任务收尾期间均不重新瞄准、点击或发包。 */
    public Status pollSubmittedEntityAttack(PlayerContext context) {
        if (button != Button.ATTACK || entity == null || receipt == null)
            return hardFail ? Status.FAILED : fires > 0 ? Status.DONE : Status.RUNNING;
        if (settleAttackReceipt(context)) { fires++; return Status.DONE; }
        return hardFail ? Status.FAILED : Status.RUNNING;
    }

    // 先处理还没关好的界面，再分别推进挖方块、对空气使用物品或离散点击。
    // 已经发出的动作优先等结果，不因这次动作刚打开了箱子就立刻把箱子关上。
    public Status tick(PlayerContext context) {
        if (receipt == null
                && (closeReceipt != null || player.containerMenu != player.inventoryMenu
                || Minecraft.getInstance().screen != null)) {
            Status menuStatus = awaitWorldInputReady(context);
            if (menuStatus != null) {
                return menuStatus;
            }
        }
        if (button == Button.ATTACK && block != null) {
            return breakBlock(context);                // 方块破坏本身就是持续操作。
        }
        if (button == Button.USE && block == null && entity == null) {
            return useAir(context);
        }
        return discrete(context);                      // 攻击实体或使用方块、实体。
    }

    /** 容器界面打开时不向世界发送攻击或使用按键，避免点击穿过菜单。 */
    // 发一次关闭菜单请求，然后逐刻等它完成；关闭失败就不能继续向世界点击。
    private Status awaitWorldInputReady(PlayerContext context) {
        if (closeReceipt == null) {
            closeReceipt = menuActions.close(context, CONFIRM_TIMEOUT_TICKS);
            return Status.RUNNING;
        }
        if (!closeReceipt.terminal()) {
            closeReceipt = menuActions.poll(context, closeReceipt);
        }
        if (!closeReceipt.terminal()) {
            return Status.RUNNING;
        }
        PendingMenuAction.Status status = closeReceipt.status();
        String detail = closeReceipt.detail();
        closeReceipt = null;
        if (status == PendingMenuAction.Status.CONFIRMED_APPLIED) {
            return null;
        }
        failReason = "could not close the active menu before the world action: " + detail;
        return Status.FAILED;
    }

    // ---- 左键方块：持续破坏 ----

    // 目标已经是空气就算挖完；否则交给 BlockDigger 逐刻挖。看不到可挖面时区分太远还是被挡住。
    private Status breakBlock(PlayerContext context) {
        if (!player.level().isLoaded(block)) {
            failReason = "the target block is outside the local client's loaded world";
            return Status.FAILED;
        }
        if (player.level().getBlockState(block).isAir()) return Status.DONE;
        BlockDigger.DigResult result = digger.digStep(context, block);
        if (result == BlockDigger.DigResult.BROKE_TARGET) {
            return Status.DONE;
        }
        if (result == BlockDigger.DigResult.NO_SHOT) {
            double reach = InteractionRange.blockReach(player);
            boolean beyondReach = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(block))
                    > reach * reach;
            failReason = beyondReach
                    ? "the target block is outside first-person reach"
                    : "no visible safe face or removable occluder reaches the target block";
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    // ---- 右键空气：点按或持续使用食物、弓等物品 ----

    // 例如吃东西：先按一次右键，观察手中物品、菜单或使用状态，再决定继续按住还是松开。
    // 点一下只要求这次使用得到确认；持续使用才会等到吃完或到达按住时限。
    private Status useAir(PlayerContext context) {
        playerInput.halt(player);
        if (receipt == null) {
            if (!requiredBlockPresent()) return Status.FAILED;
            if (player.isUsingItem()) {
                failReason = "another item use was already active; it was left untouched";
                return Status.FAILED;
            }
            ItemStack before = player.getItemInHand(hand).copy();
            int beforeMenu = player.containerMenu.containerId;
            InteractionConfirmation confirmation = InteractionConfirmation.anyOf(
                    InteractionConfirmation.heldItemChanged(hand, before),
                    InteractionConfirmation.menuChanged(beforeMenu),
                    c -> c.localPlayer().isUsingItem()
                            ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING);
            receipt = sender.useItem(
                    context, hand, confirmation, timing.hold
                            ? Math.max(CONFIRM_TIMEOUT_TICKS, (int) Math.min(1200L,
                                    (long) before.getUseDuration(player) + CONFIRM_TIMEOUT_TICKS))
                            : CONFIRM_TIMEOUT_TICKS);
            return Status.RUNNING;
        }
        receipt = sender.poll(context, receipt);
        if (!receipt.terminal()) {
            return Status.RUNNING;
        }
        // 已经发出松开请求时，只等待松开结果，不重新开始使用。
        if (releasing) {
            if (receipt.status() == PendingInteraction.Status.CONFIRMED_APPLIED) {
                return Status.DONE;
            }
            failReason = "native item release was not confirmed: " + receipt.detail();
            return Status.FAILED;
        }
        if (receipt.status() != PendingInteraction.Status.CONFIRMED_APPLIED) {
            failReason = "native item use was not confirmed: " + receipt.detail();
            return Status.FAILED;
        }
        if (!timing.hold) {
            return Status.DONE;
        }
        // 持续使用是否还归本交互管，以交互提交方认不认这笔使用为准。
        if (player.isUsingItem() && !sender.ownsItemUse(receipt)) {
            failReason = "the held item use no longer belongs to this interaction";
            return Status.FAILED;
        }
        if (!player.isUsingItem()) {
            return Status.DONE;
        }
        if (timing.maxHold > 0 && ++held >= timing.maxHold) {
            receipt = sender.releaseUsingItem(context, receipt);
            releasing = true;
            return Status.RUNNING;
        }
        return Status.RUNNING;
    }

    // ---- 离散操作：攻击实体或使用方块、实体（单次或重复） ----

    // 一次点击没确认完就不计次；确认一次后先等间隔，再开始下一次。
    private Status discrete(PlayerContext context) {
        // 有限持续操作到期后只结算已经发出的这一次，不能在最后一个确认尚未完成时宣布整段成功。
        if (finishRequested && receipt == null) {
            if (fires > 0 && !hardFail) return Status.DONE;
            failReason = "finite interaction ended without a confirmed native use"; return Status.FAILED;
        }
        if (cooldown > 0) {
            cooldown--;
            return Status.RUNNING;
        }
        boolean fired = switch (button) {
            case ATTACK -> fireAttackEntity(context);
            case USE -> entity != null ? fireUseEntity(context) : fireUseBlock(context);
        };
        if (hardFail) return Status.FAILED;
        if (!fired) return Status.RUNNING;             // 攻击冷却未结束时等待，不将其视为失败。
        if (++fires >= timing.limit || finishRequested) return Status.DONE;
        cooldown = timing.interval;
        return Status.RUNNING;
    }

    // 上次攻击尚未确认就继续等；准备新攻击时先停止移动、瞄准活着的目标，
    // 还要等攻击冷却和目标的短暂无敌时间结束，才发出攻击。
    private boolean fireAttackEntity(PlayerContext context) {
        if (receipt != null) return settleAttackReceipt(context);
        if (entity == null || !entity.isAlive()) {
            failReason = "the attack target is gone";
            hardFail = true;
            return false;
        }
        playerInput.halt(player);
        Vec3 aimPoint = stableEntityAimPoint(entity);
        playerInput.lookAt(player, aimPoint);
        if (!aimReady(aimPoint)) {
            return false;
        }
        // 近战遵守当前身体的真实实体触及属性，不能沿用右键方块的四点五格范围。
        HitResult aimed = nativeRaytrace(player, InteractionRange.entityReach(player));
        if (!(aimed instanceof EntityHitResult entityHit)
                || entityHit.getEntity() != entity) {
            return false;
        }
        boolean recovering = entity instanceof LivingEntity living
                && living.hurtTime > 0;
        // 刚换武器、目标还在短暂无敌期，或攻击条没恢复到位，都先等；否则伤害会被吞掉或打折。
        if (recovering || player.getAttackStrengthScale(0.0f) < ATTACK_READY) {
            return false;
        }
        receipt = sender.attack(
                context,
                entity,
                InteractionConfirmation.entityHurt(entity),
                CONFIRM_TIMEOUT_TICKS);
        return false;
    }

    private boolean settleAttackReceipt(PlayerContext context) {
        // 沿原确认条件等待，不使用耐久、死亡记账或实体消失自行补造命中；已终结的确认可直接读历史结果。
        if (!receipt.terminal()) {
            receipt = sender.poll(context, receipt);
        }
        if (!receipt.terminal()) return false;
        PendingInteraction.Status status = receipt.status();
        String detail = receipt.detail(); receipt = null;
        if (status == PendingInteraction.Status.CONFIRMED_APPLIED) return true;
        failReason = "native attack was not confirmed: " + detail;
        hardFail = true;
        return false;
    }

    // 每次新点击都重新瞄准目标。指定面的操作还必须让当前准星真正落在那个面上。
    private boolean fireUseBlock(PlayerContext context) {
        if (receipt != null) {
            return settleUseReceipt(context, "block use");
        }
        if (!requiredBlockPresent()) return false;
        if (!player.level().isLoaded(block)) {
            failReason = "the target block is outside the local client's loaded world";
            hardFail = true;
            return false;
        }
        playerInput.halt(player);
        BlockHitResult hit;
        if (presetHit != null) {
            playerInput.lookAt(player, presetHit.getLocation());
            HitResult aimed = nativeRaytrace(player, InteractionRange.blockReach(player));
            if (!(aimed instanceof BlockHitResult blockHit)
                    || !blockHit.getBlockPos().equals(block)
                    || blockHit.getDirection() != presetHit.getDirection()) {
                return false;
            }
            hit = blockHit;
        } else {
            playerInput.lookAt(player, Vec3.atCenterOf(block));
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
            receipt = sender.useItem(
                    context, hand, itemUseConfirmation(hand, before),
                    CONFIRM_TIMEOUT_TICKS);
        } else {
            receipt = sender.useBlock(
                    context, hand, hit, blockUseConfirmation(hit, hand),
                    CONFIRM_TIMEOUT_TICKS);
            // 仅记录真正交给原生右键入口的面，选站位时的预想朝向不能当作已点击方向。
            submittedBlockHit = hit;
        }
        return false;
    }

    // 确认成功才计次；只有明确未生效才允许物品兜底，超时后必须保留原确认并交回现场检查。
    private boolean settleUseReceipt(PlayerContext context, String action) {
        receipt = sender.poll(context, receipt);
        if (!receipt.terminal()) {
            return false;
        }
        PendingInteraction.Status status = receipt.status();
        String detail = receipt.detail();
        lastUseReceipt = receipt;
        receipt = null;
        if (status == PendingInteraction.Status.CONFIRMED_APPLIED) {
            lastUseOutcome = "confirmed (" + action + ")";
            fallingThrough = false;
            return true;
        }
        if (status == PendingInteraction.Status.CONFIRMED_NOT_APPLIED
                && itemFallthrough && !fallingThrough) {
            fallingThrough = true;
            return false;
        }
        // 失败的提交必须携带提交现场的插桩事实，否则“点击被谁拒了”只能靠猜。
        String trace = lastUseReceipt.useOnTrace();
        failReason = action + " was not confirmed: " + detail + (trace.isEmpty() ? "" : "; " + trace);
        hardFail = true;
        return false;
    }

    // 点击格、相邻格、手中物品或菜单只要有一项变化，就作为点击已生效的线索。
    // 它没有验证每种物品真正想产生的完整结果，也没有证明变化一定由这次点击造成。
    private InteractionConfirmation blockUseConfirmation(BlockHitResult hit, InteractionHand usedHand) {
        BlockPos clicked = hit.getBlockPos().immutable();
        BlockPos adjacent = clicked.relative(hit.getDirection()).immutable();
        var clickedBefore = player.level().getBlockState(clicked);
        ItemStack heldBefore = player.getItemInHand(usedHand).copy();
        int beforeMenu = player.containerMenu.containerId;
        InteractionConfirmation adjacentChanged = player.level().isLoaded(adjacent)
                ? InteractionConfirmation.blockChanged(
                        adjacent, player.level().getBlockState(adjacent))
                : InteractionConfirmation.pending();
        // 告示牌右键的原生效果是打开编辑屏，四项世界观察全都察觉不到；把屏幕出现补进确认依据，
        // 否则点击注定以“未确认”超时收场，后续写字阶段根本拿不到执行权。
        InteractionConfirmation signEditor = clickedBefore.getBlock() instanceof SignBlock
                ? InteractionConfirmation.signEditorScreenOpened()
                : InteractionConfirmation.pending();
        // 打火石把落格的火或传送门方块补进确认依据；只有耐久变化时确认虽可通过，
        // 火是否真实出现仍由调用方的事后检查如实裁决，不在这里改写结论。
        InteractionConfirmation ignition = heldBefore.is(Items.FLINT_AND_STEEL)
                ? InteractionConfirmation.ignitionWorldEffect(clicked.relative(hit.getDirection()))
                : InteractionConfirmation.pending();
        return InteractionConfirmation.anyOf(
                InteractionConfirmation.blockChanged(clicked, clickedBefore),
                adjacentChanged,
                InteractionConfirmation.heldItemChanged(usedHand, heldBefore),
                InteractionConfirmation.menuChanged(beforeMenu),
                signEditor,
                ignition);
    }

    private InteractionConfirmation itemUseConfirmation(InteractionHand usedHand, ItemStack heldBefore) {
        int beforeMenu = player.containerMenu.containerId;
        return InteractionConfirmation.anyOf(
                InteractionConfirmation.heldItemChanged(usedHand, heldBefore),
                InteractionConfirmation.menuChanged(beforeMenu),
                c -> c.localPlayer().isUsingItem()
                        ? InteractionConfirmation.Verdict.APPLIED
                        : InteractionConfirmation.Verdict.PENDING);
    }

    /**
     * 最近一次使用动作的简短说明。目前成功时只写 confirmed，未出手时为 not fired，
     * 不包含原版每只手的 PASS／FAIL 返回值，也不能拿这段文字代替成品检查。
     */
    public String lastUseOutcome() {
        return lastUseOutcome;
    }
    public BlockHitResult submittedBlockHit() { return submittedBlockHit; }

    /** 没观察到变化不等于没发生；让任务和完成通知保留原生点击的确认边界，避免再次互换已装好的材料。 */
    public Map<String, Object> useEvidence() {
        if (button != Button.USE) return Map.of();
        var last = receipt == null ? lastUseReceipt : receipt;
        if (last == null) return Map.of();
        boolean uncertain = last.status() != PendingInteraction.Status.CONFIRMED_APPLIED
                && last.status() != PendingInteraction.Status.CONFIRMED_NOT_APPLIED;
        var evidence = new LinkedHashMap<String, Object>(Map.of("submission_attempted", true,
                "native_action_status", last.status().name(),
                "native_action_kind", last.kind().name(), "outcome_uncertain", uncertain,
                "mechanical_retry_allowed", last.status() == PendingInteraction.Status.CONFIRMED_NOT_APPLIED));
        // 提交现场（点击面、客户端预测结果、预测包是否发出）与结算后的落格权威状态
        // （客户端已在服务器确认后校正）一起进证据，实机确认可直接对出服务端对这次点击的处理结果。
        String trace = last.useOnTrace();
        if (!trace.isEmpty()) evidence.put("use_on_trace", trace);
        if (last.kind() == PendingInteraction.Kind.USE_BLOCK && submittedBlockHit != null) {
            // 提交格命中不等于面正确：落格与门框效应都发生在被点面的相邻格，面必须可核对。
            evidence.put("submitted_face", submittedBlockHit.getDirection().getName());
            BlockPos cell = submittedBlockHit.getBlockPos().relative(submittedBlockHit.getDirection());
            evidence.put("settlement_placement_cell", cell.toShortString() + "="
                    + BuiltInRegistries.BLOCK.getKey(
                            player.level().getBlockState(cell).getBlock()));
        }
        return evidence;
    }

    // 目标仍活着、准星也确实点到它，才提交交互；需要物品兜底时仍沿用相同目标检查。
    private boolean fireUseEntity(PlayerContext context) {
        if (receipt != null) {
            return settleUseReceipt(context, "entity interaction");
        }
        if (entity == null || !entity.isAlive()) {
            failReason = "the entity is gone";
            hardFail = true;
            return false;
        }
        playerInput.halt(player);
        playerInput.lookAt(player, entity.getEyePosition());
        if (!aimReady(entity.getEyePosition())) {
            return false;
        }
        HitResult aimed = nativeRaytrace(player, InteractionRange.blockReach(player));
        if (!(aimed instanceof EntityHitResult entityHit)
                || entityHit.getEntity() != entity) {
            return false;
        }
        if (fallingThrough) {
            ItemStack before = player.getItemInHand(hand).copy();
            receipt = sender.useItem(
                    context, hand, itemUseConfirmation(hand, before),
                    CONFIRM_TIMEOUT_TICKS);
        } else {
            receipt = sender.interact(
                    context, entity, hand, entityUseConfirmation(entity, hand),
                    CONFIRM_TIMEOUT_TICKS);
        }
        return false;
    }

    // 用手中物品、打开的菜单或乘坐关系变化来判断实体交互；其他变化如羊是否被剪毛不在这份观察里。
    private InteractionConfirmation entityUseConfirmation(Entity target, InteractionHand usedHand) {
        Entity vehicleBefore = player.getVehicle();
        int passengersBefore = target.getPassengers().size();
        ItemStack heldBefore = player.getItemInHand(usedHand).copy();
        int beforeMenu = player.containerMenu.containerId;
        InteractionConfirmation ridingChanged = c ->
                c.localPlayer().getVehicle() != vehicleBefore
                        || target.getPassengers().size() != passengersBefore
                        ? InteractionConfirmation.Verdict.APPLIED
                        : InteractionConfirmation.Verdict.PENDING;
        return InteractionConfirmation.anyOf(
                InteractionConfirmation.heldItemChanged(usedHand, heldBefore),
                InteractionConfirmation.menuChanged(beforeMenu),
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
     * 瞄准目标当前碰撞箱内部，并沿测得的移动方向稍作提前。保留上次瞄准点可平滑数据包间的速度噪声；
     * 每次都将预测点限制在内缩碰撞箱中，防止提前量过大而使原生准星错过目标。
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

    /** 从眼部沿当前视线发射射线，并确认首先命中指定方块。 */
    private BlockHitResult raycastBlock() {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        double reach = InteractionRange.blockReach(player);
        Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block)) {
            return hit;
        }
        return null;
    }

    /** 停止发出操作；原生使用动作的松开仍有确认，必须交由运行时完成结算。 */
    // 挖掘交给 digger 取消；正在持续使用物品就发出松开，然后停止移动。
    // 普通方块／实体点击的未确认记录没有在此退役，外层结束时仍需处理它。
    public void stop(PlayerContext context) {
        if (digger != null) digger.cancel(context);
        if (receipt != null && receipt.kind() == PendingInteraction.Kind.USE_ITEM
                && player.isUsingItem() && !releasing) {
            if (sender.ownsItemUse(receipt) || block != null || entity != null || !timing.hold) {
                receipt = sender.releaseUsingItem(context, receipt);
                releasing = true;
            }
        }
        playerInput.halt(player);
    }
    public void finishRepeating() { finishRequested = true; }
    /** 羊被本次剪毛或染色后只结算在途点击，不能把动作造成的属性变化当成选错目标。 */
    public boolean entityActionSubmitted() {
        return entity != null && (receipt != null || lastUseReceipt != null || fires > 0);
    }
    public int confirmedUses() { return fires; }
}
