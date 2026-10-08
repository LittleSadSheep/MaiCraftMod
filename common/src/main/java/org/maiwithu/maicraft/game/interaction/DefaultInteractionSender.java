// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.Objects;
import java.util.function.BiFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.MenuVisibility;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 真正调用游戏的挖掘、使用、攻击等操作，并保存一项待确认动作；结果由后续观察判断，不直接用 API 返回值。 */
public final class DefaultInteractionSender implements InteractionSender {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultInteractionSender.class);

    private final MenuActions menuActions;

    /** 当前正在等确认的动作；副手放置单独记录，与主动作并行等待。 */
    private PendingInteraction active;
    private PendingInteraction auxiliary;
    /** 记录哪次待确认的物品使用已松开，以及首次观察到松开的时间。 */
    private PendingInteraction abandonedItemUse;
    private long abandonedItemUseSinceTick = -1;
    /** 只有确实进入过举盾、进食等持续持用，松手后才适用提前收尾；瞬时右键等待自身的原生确认。 */
    private boolean activeItemUseWasHeld;
    private boolean activeProtocolUsesMenu;
    /** 旧任务留下的挖掘还未实际停止时保留此原因，由 {@link #advance} 完成停手。 */
    private String pendingBreakCancellationReason;
    /** 任务已经退出但本刻无交互机会时，端口暂时接住持用，下一刻切槽取消而不是松弓射箭。 */
    private record HeldCancellation(PendingInteraction pending, ItemStack item) {}
    private HeldCancellation pendingMainHandCancellation;

    public DefaultInteractionSender(MenuActions menuActions) {
        this.menuActions = menuActions;
    }

    String diagnosticState() {
        return active == null ? "none" : active.kind() + ":" + active.status()
                + " submitted=" + active.submittedTick() + " deadline=" + active.deadlineTick();
    }

    @Override public PendingInteraction tryAuxiliaryBlockUse(PlayerContext context, BlockHitResult hit,
                                                             InteractionConfirmation confirmation, int timeoutTicks) {
        // 主手持续操作和旧副手点击优先结算；本刻主任务没出手时，才允许一次副手原生放置。
        if (!context.canInteractThisTick() || !settledForRoutinePause() || menuActions.hasPendingTransaction()
                || auxiliary != null && !auxiliary.terminal()) return null;
        PendingInteraction previous = active;
        auxiliary = useBlock(context, InteractionHand.OFF_HAND, hit, confirmation, timeoutTicks);
        active = previous;
        return auxiliary;
    }

    /** 非紧急生活动作等旧点击、停挖和取消蓄力都结清后接手，不能打断正在收尾的动作。 */
    boolean settledForRoutinePause() { return (active == null || active.terminal())
            && pendingBreakCancellationReason == null && pendingMainHandCancellation == null; }

    @Override public PendingInteraction dropSelected(PlayerContext context, ItemStack expectedSelected, boolean fullStack,
                                                     InteractionConfirmation confirmation, int timeoutTicks) {
        // 原生 Q 使用服务端朝向，先同步已经实际转到的视角，再调用玩家原生投掷；不创建实体或设置其速度。
        claimSubmission(context);
        requireIdle(context);
        var player = context.localPlayer();
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()
                || !MenuVisibility.worldInputAllowed(Minecraft.getInstance().screen) || player.isUsingItem())
            throw new IllegalStateException("selected-stack drop requires an idle world view and empty cursor");
        if (player.getInventory().selected < 0 || player.getInventory().selected > 8 || expectedSelected.isEmpty()
                || !ItemStack.matches(player.getMainHandItem(), expectedSelected))
            throw new IllegalStateException("selected stack changed before native drop");
        Objects.requireNonNull(confirmation, "drop requires exact debit and receiving evidence");
        PendingInteraction pending = oneShot(PendingInteraction.Kind.DROP_SELECTED, context, confirmation, timeoutTicks);
        try {
            context.localPlayer().connection.send(new ServerboundMovePlayerPacket.Rot(
                    player.getYRot(), player.getXRot(), player.onGround()));
            if (!player.drop(fullStack)) pending.finish(
                    PendingInteraction.Status.CONFIRMED_NOT_APPLIED, "native player drop rejected the selected stack");
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN, "native drop entered submission but its outcome is unknown");
        }
        return poll(context, pending);
    }

    /** 菜单按钮的模组协议也须先取得原生确认，再允许世界任务退出该界面。 */
    @Override public boolean hasPendingMenuTransaction() { return activeProtocolUsesMenu && active != null && !active.terminal(); }

    // 持用按键只能绑定仍是本端口当前动作的确认；新动作接管后，旧吃饭任务不能继续按住或松开。
    public boolean ownsItemUse(PendingInteraction pending) {
        return pending != null && pending == active && pending.kind() == PendingInteraction.Kind.USE_ITEM
                && (!pending.terminal() || pending.status() == PendingInteraction.Status.CONFIRMED_APPLIED);
    }

    @Override
    public PendingInteraction startBreaking(PlayerContext context, BlockHitResult hit, int timeoutTicks) {
        // 先检查当前控制权和有无旧动作，再占用本刻交互机会，记录目标原状态后开始挖。
        claimSubmission(context);
        requireIdle(context);
        PendingInteraction pending = new PendingInteraction(
                PendingInteraction.Kind.BREAK_BLOCK,
                context,
                timeoutTicks,
                2,
                InteractionConfirmation.blockBecomesAir(
                        hit.getBlockPos(), context.level().getBlockState(hit.getBlockPos())),
                hit.getBlockPos(),
                hit.getDirection());
        install(pending);
        try {
            gameMode(context).startDestroyBlock(hit.getBlockPos(), hit.getDirection());
            context.localPlayer().swing(InteractionHand.MAIN_HAND);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native mining start threw after entering the client action path");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction cancelBreaking(PlayerContext context, PendingInteraction pending) {
        // 先看是否已经挖完；仍在挖才发停止，避免把刚确认完成的操作改说成取消。
        requireCurrent(context);
        requireActive(pending, PendingInteraction.Kind.BREAK_BLOCK);
        poll(context, pending);
        if (pending.terminal()) {
            pendingBreakCancellationReason = null;
            return pending;
        }
        claimSubmission(context);
        try {
            gameMode(context).stopDestroyBlock();
            pending.finish(PendingInteraction.Status.CANCELLED,
                    "native mining was cancelled before confirmation");
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native mining cancellation could not be confirmed");
        }
        pendingBreakCancellationReason = null;
        return pending;
    }

    @Override
    public PendingInteraction cancelBreakingForTaskBoundary(
            PlayerContext context,
            PendingInteraction pending,
            String boundaryReason) {
        // 任务结束时本刻可能已经用过交互机会；来不及停挖就记下来，让下一刻在新任务之前先停止。
        requireActive(pending, PendingInteraction.Kind.BREAK_BLOCK);
        poll(context, pending);
        if (pending.terminal()) {
            pendingBreakCancellationReason = null;
            return pending;
        }
        String reason = boundaryReason == null || boundaryReason.isBlank()
                ? "the owning task ended before native mining confirmation"
                : boundaryReason;
        if (context.tryClaimInteraction()) {
            try {
                gameMode(context).stopDestroyBlock();
                pending.finish(PendingInteraction.Status.CANCELLED, reason);
            } catch (RuntimeException failure) {
                pending.finish(PendingInteraction.Status.UNCERTAIN,
                        reason + "; native mining cancellation could not be confirmed");
            }
            pendingBreakCancellationReason = null;
            return pending;
        }
        pendingBreakCancellationReason = reason;
        return pending;
    }

    @Override
    public PendingInteraction continueBreaking(PlayerContext context, PendingInteraction pending) {
        // 同一挖掘每刻最多推进一次，且先读取当前结果，已经完成就不再继续挥手。
        requireCurrent(context);
        requireActive(pending, PendingInteraction.Kind.BREAK_BLOCK);
        poll(context, pending);
        if (pending.terminal() || pending.lastNativeTick() == context.clientTick()) return pending;
        claimSubmission(context);
        try {
            gameMode(context).continueDestroyBlock(pending.breakTarget(), pending.breakFace());
            context.localPlayer().swing(InteractionHand.MAIN_HAND);
            pending.nativeAdvanced(context.clientTick());
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native mining continuation threw before the block outcome was confirmed");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction useBlock(PlayerContext context, InteractionHand hand, BlockHitResult hit,
                                       InteractionConfirmation confirmation, int timeoutTicks) {
        // 发一次普通方块右键，再用调用者给的条件查结果；游戏本身可能先在客户端预测放置效果。
        claimSubmission(context);
        requireIdle(context);
        BlockUseConfirmation acknowledged = null;
        if (confirmation.requiresBlockAcknowledgement()) {
            if (!(context.level() instanceof BlockUseAcknowledgement sequences))
                throw new IllegalStateException("native block acknowledgement hook is unavailable");
            acknowledged = new BlockUseConfirmation(confirmation, sequences);
        }
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.USE_BLOCK, context,
                acknowledged == null ? confirmation : acknowledged, timeoutTicks);
        try {
            var player = context.localPlayer();
            // 任务在玩家移动包之后执行，渲染更新的镜头可能比服务端已知朝向更新。
            // 方块交互包不含朝向和潜行状态，因此点击前先同步实际身体姿态。
            player.connection.send(new ServerboundMovePlayerPacket.Rot(
                    player.getYRot(), player.getXRot(), player.onGround()));
            player.connection.send(new ServerboundPlayerCommandPacket(player, player.isShiftKeyDown()
                    ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                    : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            // 火把等附着方块在实机上出现过“出手成功但方块不出现”。把提交现场的点击面、
            // 客户端预测结果与预测包是否发出逐项落到确认记录里，下一轮实机确认即可看出服务端对这次点击做了什么。
            int sequenceBefore = context.level() instanceof BlockUseAcknowledgement acknowledgement
                    ? acknowledgement.maicraft$currentBlockSequence() : Integer.MIN_VALUE;
            var result = gameMode(context).useItemOn(player, hand, hit);
            if (acknowledged != null) acknowledged.submitted();
            pending.attachUseOnTrace(UseOnTrace.describe(context, hand, hit, result, sequenceBefore));
            // 点击现场已随确认记录交给调用方（提交失败记录带 use_on_trace）；日志降为 debug，
            // 建造时每放一块都打 INFO 会刷屏，排查时再打开 debug 对照服务端现场。
            LOG.debug("[maicraft-interaction] {}", pending.useOnTrace());
            if (result.shouldSwing()) player.swing(hand);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native block use threw after entering the client action path");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction useItem(PlayerContext context, InteractionHand hand,
                                      InteractionConfirmation confirmation, int timeoutTicks) {
        claimSubmission(context);
        requireIdle(context);
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.USE_ITEM, context, confirmation, timeoutTicks);
        try {
            var result = gameMode(context).useItem(context.localPlayer(), hand);
            activeItemUseWasHeld = context.localPlayer().isUsingItem();
            if (result.shouldSwing()) context.localPlayer().swing(hand);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native item use threw after entering the client action path");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction releaseUsingItem(PlayerContext context, PendingInteraction pending) {
        // 持续吃东西、拉弓等需要实际松开使用；新的“已停止使用”确认接替原来的使用确认。
        claimSubmission(context);
        requireActive(pending, PendingInteraction.Kind.USE_ITEM);
        PendingInteraction release = oneShot(
                PendingInteraction.Kind.RELEASE_ITEM,
                context,
                InteractionConfirmation.itemUseStopped(),
                10);
        try {
            gameMode(context).releaseUsingItem(context.localPlayer());
        } catch (RuntimeException failure) {
            release.finish(PendingInteraction.Status.UNCERTAIN,
                    "native use release threw before the outcome was confirmed");
        }
        return poll(context, release);
    }

    @Override
    public PendingInteraction selectHotbar(PlayerContext context, int slot, int timeoutTicks) {
        // 本地切换选中槽并发包通知服务器；随后比较的 selected 字段就是这个本地值。
        claimSubmission(context);
        // 选中上限问原版自己：服务端 handleSetCarriedItem 也按 Inventory.getSelectionSize() 校验；
        // 扩展快捷栏模组（如 HotBaaaar）会把它抬到 9 以上，未装时恒为 9，与原校验一致。
        int selectionLimit = Inventory.getSelectionSize();
        if (slot < 0 || slot >= selectionLimit) {
            throw new IllegalArgumentException(
                    "hotbar slot must be between 0 and " + (selectionLimit - 1));
        }
        requireIdle(context);
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.SELECT_HOTBAR,
                context,
                InteractionConfirmation.hotbarSelected(slot),
                timeoutTicks);
        try {
            context.localPlayer().getInventory().selected = slot;
            connection(context).send(new ServerboundSetCarriedItemPacket(slot));
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native hotbar selection threw before the outcome was confirmed");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction creativeSetSlot(
            PlayerContext context, int inventorySlot, ItemStack expected, int timeoutTicks) {
        // 只有创造模式且物品栏界面已显示才允许设置槽位；发包后等物品栏实际出现期望物品，不直接在这里改本地数量。
        claimSubmission(context);
        if (inventorySlot < 0 || inventorySlot >= Math.min(
                36, context.localPlayer().getInventory().getContainerSize())) {
            throw new IllegalArgumentException("creative inventory slot must be between 0 and 35");
        }
        if (!context.localPlayer().getAbilities().instabuild) {
            throw new IllegalStateException("creative slot mutation requires creative mode");
        }
        if (!menuActions.ensureVisible(context)) {
            throw new IllegalStateException("creative inventory changes require a rendered player inventory GUI");
        }
        requireIdle(context);
        ItemStack frozen = expected.copy();
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.CREATIVE_SET_SLOT,
                context,
                InteractionConfirmation.inventorySlot(inventorySlot, frozen),
                timeoutTicks);
        try {
            int protocolSlot = inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
            connection(context).send(
                    new ServerboundSetCreativeModeSlotPacket(protocolSlot, frozen.copy()));
            menuActions.interactionSubmitted(context);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "creative slot packet threw before synchronized inventory confirmation");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction submitProtocol(
            PlayerContext context,
            String operation,
            Runnable submission,
            InteractionConfirmation confirmation,
            int timeoutTicks) {
        return submit(context, operation, submission, confirmation, timeoutTicks, true);
    }

    @Override
    public PendingInteraction submitControlProtocol(
            PlayerContext context,
            String operation,
            Runnable submission,
            InteractionConfirmation confirmation,
            int timeoutTicks) {
        return submit(context, operation, submission, confirmation, timeoutTicks, false);
    }

    private PendingInteraction submit(
            PlayerContext context,
            String operation,
            Runnable submission,
            InteractionConfirmation confirmation,
            int timeoutTicks,
            boolean usesMenu) {
        // 模组菜单协议必须有可见菜单，世界控制协议则要求合适的世界界面；两者仍共用同一交互机会和等待位置。
        claimSubmission(context);
        if (submission == null) throw new IllegalArgumentException("submission is required");
        if (confirmation == null) throw new IllegalArgumentException("confirmation is required");
        String name = operation == null || operation.isBlank() ? "mod protocol action" : operation;
        if (usesMenu) {
            if (!menuActions.ensureVisible(context)) {
                throw new IllegalStateException("mod menu protocols require a rendered GUI");
            }
        } else if (!MenuVisibility.worldInputAllowed(Minecraft.getInstance().screen)) {
            throw new IllegalStateException("mod control protocols require a world interaction screen");
        }
        requireIdle(context);
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.MOD_PROTOCOL, context, confirmation, timeoutTicks);
        activeProtocolUsesMenu = usesMenu;
        try {
            submission.run();
            if (usesMenu) menuActions.interactionSubmitted(context);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    name + " threw after protocol submission began; application is unknown");
        }
        return poll(context, pending);
    }

    public boolean deferMainHandUseCancellation(Object owner, PlayerContext context, PendingInteraction pending) {
        // 只能接收当前任务确实持有的主手动作；租借继续绑定原角色、确认、槽位、物品和递减的持用计时。
        requireCurrent(context);
        if (!ownsItemUse(pending)
                || context.localPlayer().getUsedItemHand() != InteractionHand.MAIN_HAND) return false;
        ItemStack item = context.localPlayer().getUseItem().copy();
        pendingMainHandCancellation = new HeldCancellation(pending, item);
        return true;
    }

    private boolean advanceMainHandCancellation(PlayerContext context) {
        HeldCancellation held = pendingMainHandCancellation;
        if (held == null) return false;
        // 起手确认可能已经成功；先处理取消，再走普通终态早退。后来者不继承旧取消。
        if (!held.pending().fromSamePlayer(context)) {
            clearMainHandCancellation();
            return false;
        }
        // 只读看一眼本刻还有没有机会；真正发出取消时由取消入口自己占用。
        if (context.canInteractThisTick()) cancelMainHandUse(context, held.pending());
        return true;
    }

    private void clearMainHandCancellation() {
        pendingMainHandCancellation = null;
    }

    @Override
    public PendingInteraction cancelMainHandUse(PlayerContext context, PendingInteraction pending) {
        claimSubmission(context);
        requireActive(pending, PendingInteraction.Kind.USE_ITEM);
        if (context.localPlayer().isUsingItem()
                && context.localPlayer().getUsedItemHand() != InteractionHand.MAIN_HAND)
            throw new IllegalStateException("main-hand cancellation cannot stop an offhand action");
        int replacement = (context.localPlayer().getInventory().selected + 1) % 9;
        // 切槽和本地停止是同步动作；这里确认的是已提交的取消操作，不是箭命中或服务器确认。
        InteractionConfirmation stopped = new InteractionConfirmation() {
            public Verdict observe(PlayerContext c) {
                return !c.localPlayer().isUsingItem() && c.localPlayer().getInventory().selected == replacement
                        ? Verdict.APPLIED : Verdict.PENDING;
            }
            public int stableTicksRequired() { return 1; }
        };
        var cancelled = oneShot(PendingInteraction.Kind.RELEASE_ITEM, context, stopped, 10);
        try {
            // 原版服务端 handleSetCarriedItem 在切换主手时 stopUsingItem，不会释放弓箭。
            connection(context).send(new ServerboundSetCarriedItemPacket(replacement));
            context.localPlayer().getInventory().selected = replacement;
            context.localPlayer().stopUsingItem();
        } catch (RuntimeException failure) {
            cancelled.finish(PendingInteraction.Status.UNCERTAIN, "main-hand cancellation was not confirmed");
        }
        return poll(context, cancelled);
    }

    @Override
    public PendingInteraction attack(PlayerContext context, Entity target,
                                     InteractionConfirmation confirmation, int timeoutTicks) {
        requireCurrent(context);
        // 同一目标的挥击还在等确认时，重提交按幂等等待：不重复挥手、不抛异常，也不占本刻的交互机会，
        // 把仍在进行的等待原样交回，调用方逐刻读它自己的结算结果。
        if (sameTargetAttackAwaiting(active, context, target.getId())) {
            return active;
        }
        claimSubmission(context);
        requireIdle(context);
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.ATTACK_ENTITY, context, confirmation, timeoutTicks);
        pending.attachAttackTarget(target.getId());
        try {
            gameMode(context).attack(context.localPlayer(), target);
            context.localPlayer().swing(InteractionHand.MAIN_HAND);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native attack threw after entering the client action path");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction interact(PlayerContext context, Entity target, InteractionHand hand,
                                       InteractionConfirmation confirmation, int timeoutTicks) {
        claimSubmission(context);
        requireIdle(context);
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.INTERACT_ENTITY, context, confirmation, timeoutTicks);
        try {
            var result = gameMode(context).interact(context.localPlayer(), target, hand);
            if (result.shouldSwing()) context.localPlayer().swing(hand);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native entity interaction threw after entering the client action path");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction retireOneShotForTaskBoundary(
            PlayerContext context,
            PendingInteraction pending,
            String boundaryReason) {
        // 一次性操作已提交后不能假装撤销；任务结束而结果未定时标记不确定。挖掘和持续使用必须另发停止操作。
        if (pending == null) throw new IllegalArgumentException("pending is required");
        requireActive(pending, pending.kind());
        if (pending.kind() == PendingInteraction.Kind.BREAK_BLOCK
                || pending.kind() == PendingInteraction.Kind.USE_ITEM) {
            throw new IllegalArgumentException(
                    "continuous native actions require their dedicated physical stop operation");
        }
        poll(context, pending);
        if (!pending.terminal()) {
            String reason = boundaryReason == null || boundaryReason.isBlank()
                    ? "the owning task ended before native confirmation"
                    : boundaryReason;
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    reason + "; the submitted one-shot effect may already have applied");
        }
        return pending;
    }

    @Override
    public PendingInteraction poll(PlayerContext context, PendingInteraction pending) {
        // 先核对仍是同一角色、上下文仍是本刻，再运行只读判断；当前默认累计两刻匹配，不包含服务器预测序号检查。
        // 已终结的确认是冻结的历史：端口可能已经先一步替它收尾（每刻的 advance），或者有后来者
        // 合法接管了新动作。迟到的持有者仍要读回自己的最终结果，不能因为端口换了动作就判成内部错误。
        if (pending.terminal()) return pending;
        requireActive(pending, pending.kind());
        if (!pending.fromSamePlayer(context)) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "body or control authority changed before confirmation");
            return pending;
        }
        InteractionConfirmation.Verdict verdict;
        try {
            verdict = pending.confirmation().observe(context);
        } catch (RuntimeException observationFailure) {
            verdict = InteractionConfirmation.Verdict.PENDING;
        }
        switch (verdict) {
            case APPLIED -> {
                if (pending.countStable(context.clientTick())) pending.finish(
                        PendingInteraction.Status.CONFIRMED_APPLIED,
                        "authoritative client facts confirmed the native action");
            }
            case NOT_APPLIED -> pending.finish(
                    PendingInteraction.Status.CONFIRMED_NOT_APPLIED,
                    "authoritative client facts confirmed that the action was not applied");
            case DIVERGED -> pending.finish(
                    PendingInteraction.Status.DIVERGED,
                    "live facts diverged from both the frozen before and expected after state");
            case PENDING -> pending.resetStable(context.clientTick());
        }
        if (!pending.terminal() && context.clientTick() >= pending.deadlineTick()) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "the bounded read-only confirmation window expired" + expiryContext());
        }
        if (pending.terminal() && (pending.kind() == PendingInteraction.Kind.CREATIVE_SET_SLOT
                || (pending.kind() == PendingInteraction.Kind.MOD_PROTOCOL && activeProtocolUsesMenu))) {
            menuActions.interactionSubmitted(context);
        }
        return pending;
    }

    /**
     * 确认窗过期的现场分层：窗口内打开了界面是“菜单已开但盯的事实没变”，
     * 没开界面更可能是点击无效果或被模式拦截；当前游戏模式一句话点破创造拦截。
     */
    private static String expiryContext() {
        StringBuilder text = new StringBuilder();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.screen != null) {
            text.append("; a menu or screen opened during the window (")
                    .append(minecraft.screen.getClass().getSimpleName())
                    .append(") but the watched fact never turned true");
        } else {
            text.append("; no menu or screen opened, so the click likely had no effect or was blocked");
        }
        if (minecraft != null && minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode() != null) {
            text.append("; game_mode=").append(minecraft.gameMode.getPlayerMode().getName());
        }
        return text.toString();
    }

    /**
     * 刻外任务收尾（MCP 取消等在两次游戏刻之间执行，没有当刻上下文）：发不了停止包，
     * 只登记边界原因，下一刻 advance 先停挖再结算。没有这一步，等待中的挖掘会一直占着
     * 动作队列直到自身超期——挖掘窗口长达数千刻，期间所有原生提交都被拒绝。
     */
    @Override public void deferBreakCancellationForTaskBoundary(PendingInteraction pending, String boundaryReason) {
        if (pending == null || pending.terminal() || pending != active
                || pending.kind() != PendingInteraction.Kind.BREAK_BLOCK) return;
        pendingBreakCancellationReason = boundaryReason == null || boundaryReason.isBlank()
                ? "the owning task ended before native mining confirmation" : boundaryReason;
    }

    /**
     * 刻外任务收尾的一次性动作版：不需要世界操作就能如实终结，标记效果未知
     * （提交可能已经生效），不留等待占位。持续动作（挖掘、持用）须走各自的物理停手。
     */
    @Override public void abandonOneShotForTaskBoundary(PendingInteraction pending, String boundaryReason) {
        if (pending == null || pending.terminal()) return;
        if (pending != active && pending != auxiliary) return;
        if (pending.kind() == PendingInteraction.Kind.BREAK_BLOCK
                || pending.kind() == PendingInteraction.Kind.USE_ITEM) {
            throw new IllegalArgumentException(
                    "continuous native actions require their dedicated physical stop operation");
        }
        String reason = boundaryReason == null || boundaryReason.isBlank()
                ? "the owning task ended before native confirmation" : boundaryReason;
        pending.finish(PendingInteraction.Status.UNCERTAIN,
                reason + "; the submitted one-shot effect may already have applied");
    }

    /** 换角色或交还控制权时结束旧等待：旧结果不再可信，这里只改等待记录，不再次发世界操作。 */
    public void revokeForBoundary(String reason) {
        clearMainHandCancellation(); // 交还角色后，旧取消不得切换新操作者的槽位。
        if (active != null && !active.terminal()) {
            active.finish(PendingInteraction.Status.UNCERTAIN, reason);
        }
        // 换世界、死亡或 F8 交还控制时，副手的旧等待也必须结束，不能带到新操作者身上。
        if (auxiliary != null && !auxiliary.terminal()) auxiliary.finish(PendingInteraction.Status.UNCERTAIN, reason);
        pendingBreakCancellationReason = null;
    }

    private PendingInteraction oneShot(PendingInteraction.Kind kind, PlayerContext context,
                                       InteractionConfirmation confirmation, int timeoutTicks) {
        PendingInteraction pending = new PendingInteraction(
                kind, context, timeoutTicks, confirmation.stableTicksRequired(), confirmation, null, null);
        install(pending);
        return pending;
    }

    private void install(PendingInteraction pending) {
        clearMainHandCancellation(); // 新原生动作接管时丢弃旧债，不替后来者松手或换物。
        active = pending;
        // 新的一次点击不继承上一次举盾的持用状态，避免无线终端被当作已经松开的盾牌。
        activeItemUseWasHeld = false;
        activeProtocolUsesMenu = false;
        pendingBreakCancellationReason = null;
    }

    // 上下文必须属于本刻：旧刻的上下文可能指着已经换掉的玩家或连接。
    private static void requireCurrent(PlayerContext context) {
        if (context == null || !context.isCurrent()) throw new IllegalArgumentException("the player context must belong to the current tick");
    }

    // 发包前：角色仍归自动化控制（F8 交还后不能再发），并占用本刻唯一的交互机会；用过就拒绝，不悄悄再点一下。
    private static void claimSubmission(PlayerContext context) {
        requireCurrent(context);
        if (!context.tryClaimInteraction()) throw new IllegalStateException("no interaction opportunity this tick");
    }

    /**
     * 同一目标、同一角色且未过期的攻击等待才算"还在等同一次挥击"，重提交幂等等待；
     * 换目标、过了期限或换了角色都不算，仍走正常的回收与提交检查。
     * 纯判断，离线可测。
     */
    static boolean sameTargetAttackAwaiting(PendingInteraction active, PlayerContext context, int targetId) {
        return active != null && !active.terminal()
                && active.kind() == PendingInteraction.Kind.ATTACK_ENTITY
                && active.attackTargetId() == targetId
                && active.fromSamePlayer(context)
                && context.clientTick() < active.deadlineTick();
    }

    private void requireIdle(PlayerContext context) {
        if (active == null || active.terminal()) return;
        // 接管、换角色或长期无驱动都会留下待确认动作；已过期或不再属于当前角色的
        // 僵尸确认由本次提交就地结算为不确定并放行，不能让一次悬挂升级成会话级瘫痪。
        if (context.clientTick() >= active.deadlineTick() || !active.fromSamePlayer(context)) {
            PendingInteraction stale = active;
            LOG.warn("[maicraft-interaction] reclaiming stale native action {} (kind={}, status={}, submitted_tick={}, deadline_tick={})",
                    stale.id(), stale.kind(), stale.status(), stale.submittedTick(), stale.deadlineTick());
            stale.finish(PendingInteraction.Status.UNCERTAIN,
                    "the pending native action outlived its deadline or its player; reclaimed before a new submission");
            return;
        }
        // 前一项操作还没结束时拒绝新操作，防止多次点击共用一份结果后分不清谁做了什么。
        throw new IllegalStateException(
                "a native action is already awaiting confirmation"
                        + " (kind=" + active.kind()
                        + ", id=" + active.id()
                        + ", status=" + active.status()
                        + ", submitted_tick=" + active.submittedTick()
                        + ", deadline_tick=" + active.deadlineTick() + ")"
                        + "; it settles at its deadline or player change, then the same submission may be retried");
    }

    private void requireActive(PendingInteraction pending, PendingInteraction.Kind kind) {
        if (pending == null || pending != active && pending != auxiliary || pending.kind() != kind) {
            throw new IllegalArgumentException("the pending interaction is not the active native action");
        }
    }

    /** 每刻推进一次：先结算副手与挂起的取消，再处理停挖请求和持用收尾。 */
    void advance(PlayerContext context) {
        // 副手仅做只读确认；即使主任务已经开始下一块矿，也不能重新提交旧火把。
        if (auxiliary != null && !auxiliary.terminal()) poll(context, auxiliary);
        if (advanceMainHandCancellation(context)) return;
        // 每刻先读旧结果；如果有任务已经结束却还没来得及松开挖掘，就先处理这次停止。
        PendingInteraction pending = active;
        if (pending == null || pending.terminal()) {
            pendingBreakCancellationReason = null;
            abandonedItemUse = null;
            abandonedItemUseSinceTick = -1;
            return;
        }
        // 持用可能在服务器确认后才开始；持续记录真实状态，瞬时开菜单则一直等待菜单或截止时间。
        if (pending.kind() == PendingInteraction.Kind.USE_ITEM && context.localPlayer().isUsingItem())
            activeItemUseWasHeld = true;
        if (pendingBreakCancellationReason == null) {
            poll(context, pending);
            retireAbandonedItemUse(context, pending);
            return;
        }

        poll(context, pending);
        if (pending.terminal()) {
            pendingBreakCancellationReason = null;
            return;
        }
        if (pending.kind() != PendingInteraction.Kind.BREAK_BLOCK) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "a task-boundary break cancellation no longer owned the active break interaction");
            pendingBreakCancellationReason = null;
            return;
        }
        if (!context.tryClaimInteraction()) return;
        try {
            gameMode(context).stopDestroyBlock();
            pending.finish(PendingInteraction.Status.CANCELLED,
                    pendingBreakCancellationReason);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    pendingBreakCancellationReason
                            + "; native mining cancellation could not be confirmed");
        } finally {
            pendingBreakCancellationReason = null;
        }
    }

    /**
     * 持续使用的兜底收尾：使用已经不在手上（被原版松开、手里的东西被换走，或持用它的任务已经结束），
     * 确认就永远不会再来了。短暂宽限后如实记不确定，别让一个永远等待的提交把端口卡到 deadline——
     * 那段时间里下一个任务连第一次点击都提交不了，看上去就是“光瞄准不出手”。
     * 挖掘和仍然在手上的持用不归这里管：它们得由专门的动作去物理停止。
     */
    private void retireAbandonedItemUse(PlayerContext context, PendingInteraction pending) {
        if (pending.terminal() || pending.kind() != PendingInteraction.Kind.USE_ITEM || !activeItemUseWasHeld
                || context.localPlayer().isUsingItem()) {
            abandonedItemUse = null;
            abandonedItemUseSinceTick = -1;
            return;
        }
        // 计数认准这一次持用：换了新确认就重新起算，不能把上一个的等待算到它头上。
        if (abandonedItemUse != pending) {
            abandonedItemUse = pending;
            abandonedItemUseSinceTick = context.clientTick();
            return;
        }
        // 宽限两刻：物品自行结束的那一瞬，扣数／组件证据通常和“不再使用”同刻到达，先让只读观察说话。
        if (context.clientTick() - abandonedItemUseSinceTick < 2) return;
        abandonedItemUse = null;
        abandonedItemUseSinceTick = -1;
        if (pending == active && !pending.terminal()) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "the held item use ended without native confirmation");
        }
    }

    private static MultiPlayerGameMode gameMode(PlayerContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null) throw new IllegalStateException("no active client game mode");
        return minecraft.gameMode;
    }

    private static ClientPacketListener connection(PlayerContext context) {
        ClientPacketListener connection = context.localPlayer().connection;
        if (connection == null) throw new IllegalStateException("no active server connection");
        return connection;
    }
}
