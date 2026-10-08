// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.MenuVisibility;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.menu.VanillaHotbar;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.world.InteractionRange;

/**
 * 把挖一个方块拆成选工具、关背包、瞄准、持续挖、等结果几步。
 * 每一刻调用一次，不会在一个循环里瞬间挖完；目标和未完成动作保存在本对象中。
 * 它有“可先拆遮挡物”和“只挖目标”两种入口；哪些遮挡格能顺手挖掉由调用方按许可给出，
 * 这里不替它判断（默认一格都不挖，只挖目标）。
 * 交互与界面入口从构造函数传入，每刻推进时传入当刻的角色上下文。
 */
public final class BlockDigger {

    private static final int BREAK_TIMEOUT_TICKS = 20 * 60 * 5;
    private static final int TOOL_TIMEOUT_TICKS = 20 * 5;
    /** 生存模式破坏一个方块后，到下一次开挖前等待的游戏刻数；与原版破坏间隔一致。 */
    private static final int POST_BREAK_DELAY_TICKS = 5;

    private final LocalPlayer player;
    private final InteractionSender sender;
    private final MenuActions menuActions;
    private final PlayerInput playerInput;
    private BlockPos pos;
    private PendingInteraction pending;
    private PendingInteraction toolSelectPending;
    private PendingMenuAction toolClosePending;
    private PendingMenuAction toolStagePending;
    private int pendingToolSlot = -1;
    private int minimumToolDurability;
    public void minimumToolDurability(int remaining) { minimumToolDurability = Math.max(0, remaining); }
    private Predicate<BlockHitResult> preparation = hit -> true;
    /** 哪些遮挡格可以顺手挖掉；判断归玩家行为层的许可检查点，没给时一格都不挖。 */
    private Predicate<BlockPos> mayBreakOccluder = occluder -> false;
    /** 允许拆遮挡物的入口用它问"这一格能不能挖"：受保护的、玩家的方块由许可检查点拒绝。 */
    public void occluderRule(Predicate<BlockPos> mayBreak) {
        mayBreakOccluder = Objects.requireNonNull(mayBreak);
    }
    /** 工具真正拿好、界面关闭且准星对准后，才检查调用方的附加准备；未通过前不提交第一下破坏。 */
    public void beforeBreak(Predicate<BlockHitResult> gate) {
        preparation = Objects.requireNonNull(gate);
    }
    public boolean hasPendingBreak() { return pending != null && !pending.terminal(); }
    private boolean preferTopFace;
    private Direction requiredFace;
    /** 通道形状由命中面决定；只接受射线真实命中的指定面，不能把顶面点击伪装成水平开路。 */
    public void requiredFace(Direction face) { requiredFace = face; }
    public void preferTopFace(boolean value) { preferTopFace = value; }
    private int blockHitDelay;    // 方块破坏后的冷却时间（reset() 后保留）。
    /** 开挖时的主手物品快照;中途换持(物品/组件级)即重开进度。 */
    private ItemStack destroyingItem;

    public BlockDigger(LocalPlayer player, InteractionSender sender, MenuActions menuActions,
                       PlayerInput playerInput) {
        this.player = player;
        this.sender = sender;
        this.menuActions = menuActions;
        this.playerInput = playerInput;
    }

    /** 当前正在挖掘的方块；空闲时为 {@code null}。 */
    public BlockPos current() {
        return pos;
    }

    /** 一次 {@link #digStep} 的结果，让调用方区分“仍在挖掘”和“当前无法实际命中”。 */
    public enum DigResult {
        /** 破坏仍在进行、处于冷却，或本刻刚开始；调用方应继续推进。 */
        PROGRESSING,
        /** 本刻已确认目标方块破坏完成。 */
        BROKE_TARGET,
        /** 本刻只破坏了挡路方块而非目标，这是继续接近目标的一步。 */
        BROKE_OCCLUDER,
        /** 目标所有面都不可命中，且没有可安全清除的遮挡；当前无法继续。 */
        NO_SHOT;

        /** 本 tick 有方块真的没了(目标或遮挡物)。 */
        public boolean broke() {
            return this == BROKE_TARGET || this == BROKE_OCCLUDER;
        }
    }

    /** 使用调用方已解析的准星命中结果推进一刻：只挖视线射线实际命中的方块和面，不在内部另找瞄准点。 */
    // 调用方已经选好准星命中面时，直接挖这一格，不再找别的方块，也不自动选工具。
    public DigResult digStep(PlayerContext context, BlockHitResult crosshairHit) {
        if (blockHitDelay > 0) {                    // 先等待上一次破坏效果生效。
            blockHitDelay--;
            playerInput.halt(player);
            return DigResult.PROGRESSING;
        }
        playerInput.halt(player);
        BlockPos effective = crosshairHit.getBlockPos();
        if (pos == null || !pos.equals(effective)) {
            // 工具由外层按意图格选择，这里不按命中格改选。
            if (!start(context, effective, false)) {
                return DigResult.PROGRESSING;
            }
        }
        return advance(context, crosshairHit, true);
    }

    /** 破块后冷却按游戏刻递减;本 tick 没走 digStep 的驱动方调用此保持计时。 */
    public void tickCooldown() {
        if (blockHitDelay > 0) {
            blockHitDelay--;
        }
    }

    /**
     * 客户端世界已将有效方块同步为空气后，完成一次原生破坏结算。此时射线无法再命中已消失的方块，
     * 因此调用方必须轮询现有确认，而不是再次提交挖掘。
     */
    // 方块从画面上消失后，仍要核对之前发出的挖掘记录。没有本次记录，不能把别人挖掉算作自己挖成。
    public DigResult settleGone(PlayerContext context, boolean targetBreak) {
        if (pending == null) {
            // 选择或准备工具期间，目标也可能先消失；cancel() 会先结束本挖掘器拥有的所有交易再清本地状态。
            cancel(context);
            return DigResult.NO_SHOT;
        }
        if (!pending.terminal()) {
            pending = sender.poll(context, pending);
        }
        if (!pending.terminal()) {
            return DigResult.PROGRESSING;
        }
        PendingInteraction.Status status = pending.status();
        reset();
        if (status == PendingInteraction.Status.CONFIRMED_APPLIED) {
            blockHitDelay = POST_BREAK_DELAY_TICKS;
            return targetBreak ? DigResult.BROKE_TARGET : DigResult.BROKE_OCCLUDER;
        }
        return DigResult.NO_SHOT;
    }

    // 坐标入口先找能看到的目标面；找不到时还可能改挖眼睛与目标之间的遮挡物。
    // 只许挖指定格的调用方应使用 digTargetStep，否则“挖目标”可能顺便拆掉别的格。
    public DigResult digStep(PlayerContext context, BlockPos target) {
        Level level = player.level();
        if (blockHitDelay > 0) {                    // 先等待上一次破坏效果生效。
            blockHitDelay--;
            playerInput.halt(player);
            return DigResult.PROGRESSING;
        }
        // 决定本刻实际挥向哪里：先尝试射线确认可见的目标表面；若目标被树叶或狭窄顶棚遮挡，则瞄准目标中心并破坏准星实际命中的遮挡物来开路。
        // 这样角色不必永远等待理想角度；遮挡格能不能挖由调用方按许可回答，这里不替它判断。
        BlockHitResult hit = reachableHit(target);
        BlockPos effective = target;
        if (hit == null) {
            BlockHitResult center = centerRaycast(target);
            if (center != null && !center.getBlockPos().equals(target)
                    && mayBreakOccluder.test(center.getBlockPos().immutable())) {
                hit = center;
                effective = center.getBlockPos();
            }
        }
        playerInput.halt(player);
        if (hit == null) {
            return DigResult.NO_SHOT;                // 没有清晰射线，也没有安全可清除的遮挡——当前卡住。
        }
        if (pos == null || !pos.equals(effective)) {
            if (!start(context, effective, true)) {
                return DigResult.PROGRESSING;
            }
        }
        // 本刻可能只清除了遮挡物而未破坏目标；只有目标本身消失才报告完成，避免把开视线砍掉的树叶计作已采集目标。
        return advance(context, hit, effective.equals(target));
    }

    // 只尝试目标自身可见的面，绝不改挖前面的遮挡块；看不到就交回调用方换站位。
    public DigResult digTargetStep(PlayerContext context, BlockPos target) {
        if (blockHitDelay > 0) {
            blockHitDelay--;
            playerInput.halt(player);
            return DigResult.PROGRESSING;
        }
        BlockHitResult hit = reachableHit(target);
        playerInput.halt(player);
        if (hit == null) {
            return DigResult.NO_SHOT;
        }
        if (pos == null || !pos.equals(target)) {
            if (!start(context, target, true)) {
                return DigResult.PROGRESSING;
            }
        }
        return advance(context, hit, true);
    }

    /** 使用已解析的命中面和瞄准点，统一推进本刻挖掘。 */
    // 真正开挖前，接着按顺序等工具搬运、关闭背包、快捷栏选择完成。
    // 这些步骤都是跨刻等待；中途失败就不继续挖。
    private DigResult advance(PlayerContext context, BlockHitResult hit, boolean targetBreak) {
        // 关背包与搬工具两步都可能在等待；先结清它们，再轮到快捷栏选择和破坏本体。
        DigResult toolStep = advanceToolPreparation(context);
        if (toolStep != null) return toolStep;
        if (pendingToolSlot >= 0) {
            submitToolSelection(context);
            return DigResult.PROGRESSING;
        }
        if (toolSelectPending != null) {
            if (!toolSelectPending.terminal()) {
                toolSelectPending = sender.poll(context, toolSelectPending);
            }
            if (!toolSelectPending.terminal()) {
                return DigResult.PROGRESSING;
            }
            PendingInteraction.Status status = toolSelectPending.status();
            toolSelectPending = null;
            if (status != PendingInteraction.Status.CONFIRMED_APPLIED) {
                reset();
                return DigResult.NO_SHOT;
            }
        }
        // 挖到一半换了手中物品或其附带数据，就取消旧挖掘，以免继续使用旧工具的进度。
        if (pending != null && !pending.terminal() && destroyingItem != null
                && !ItemStack.isSameItemSameComponents(
                        destroyingItem, player.getMainHandItem())) {
            cancel(context);
            return DigResult.PROGRESSING;
        }
        playerInput.lookAt(player, hit.getLocation());
        // 本刻的交互机会已经被别的动作用掉（或角色不归自动化控制）：照样瞄准，下一刻再挥。
        if (!context.canInteractThisTick()) {
            return DigResult.PROGRESSING;
        }
        // 第一次出手先等视角靠近目标；开始以后继续推进同一份挖掘记录，不每刻重新开挖。
        if (pending == null) {
            if (!aimReady(hit.getLocation())) {
                return DigResult.PROGRESSING;
            }
            if (!preparation.test(hit)) return DigResult.PROGRESSING;
            pending = sender.startBreaking(context, hit, BREAK_TIMEOUT_TICKS);
            destroyingItem = player.getMainHandItem().copy();
        } else {
            pending = sender.continueBreaking(context, pending);
        }
        return settleBreak(context, targetBreak);
    }

    /** 关闭背包与搬工具两步的逐刻推进；还在等待或本刻只推进了准备时返回非空结果。 */
    private DigResult advanceToolPreparation(PlayerContext context) {
        if (toolClosePending != null) {
            if (!toolClosePending.terminal()) {
                toolClosePending = menuActions.poll(context, toolClosePending);
            }
            if (!toolClosePending.terminal()) {
                return DigResult.PROGRESSING;
            }
            PendingMenuAction.Status status = toolClosePending.status();
            toolClosePending = null;
            if (status != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                reset();
                return DigResult.NO_SHOT;
            }
            submitToolSelection(context);
            return DigResult.PROGRESSING;
        }
        // 从背包搬到快捷栏后要先确认物品到位，再关掉背包回到世界操作。
        if (toolStagePending != null) {
            if (!toolStagePending.terminal()) {
                toolStagePending = menuActions.poll(context, toolStagePending);
            }
            if (!toolStagePending.terminal()) {
                return DigResult.PROGRESSING;
            }
            PendingMenuAction.Status status = toolStagePending.status();
            toolStagePending = null;
            if (status != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                cancel(context);
                return DigResult.NO_SHOT;
            }
            toolClosePending = menuActions.close(context, TOOL_TIMEOUT_TICKS);
            return DigResult.PROGRESSING;
        }
        return null;
    }

    /** 挖掘确认结束后统一收口：成功按目标或遮挡物结算，失败如实报无法命中。 */
    private DigResult settleBreak(PlayerContext context, boolean targetBreak) {
        if (!pending.terminal()) {
            return DigResult.PROGRESSING;
        }
        PendingInteraction.Status status = pending.status();
        reset();
        if (status == PendingInteraction.Status.CONFIRMED_APPLIED) {
            blockHitDelay = POST_BREAK_DELAY_TICKS;
            return targetBreak ? DigResult.BROKE_TARGET : DigResult.BROKE_OCCLUDER;
        }
        return DigResult.NO_SHOT;
    }

    // 换目标之前先结束旧挖掘和未完成的工具操作，下一次 tick 才开始新目标。
    // 随后查适合的工具槽位；此时只是选出编号，还没保证它已经拿在手里。
    private boolean start(PlayerContext context, BlockPos target, boolean selectTool) {
        if (pending != null && !pending.terminal()
                || toolStagePending != null && !toolStagePending.terminal()
                || toolSelectPending != null && !toolSelectPending.terminal()
                || toolClosePending != null && !toolClosePending.terminal()) {
            cancel(context);
            return false;
        }
        reset();
        pos = target.immutable();
        pendingToolSlot = selectTool && player.level().isLoaded(pos)
                ? ToolSelect.bestSlot(player, player.level().getBlockState(pos), minimumToolDurability)
                : -1;
        if (Minecraft.getInstance().screen != null || player.containerMenu != player.inventoryMenu) {
            toolClosePending = menuActions.close(context, TOOL_TIMEOUT_TICKS);
        } else {
            submitToolSelection(context);
        }
        // 存活引用而非副本:主手栈原地变异(修补吸经验改耐久)时引用相等,
        // 不触发重置;只有真正换持(不同栈对象且物品/组件不同)才重开。
        destroyingItem = player.getMainHandItem();
        return true;
    }

    // 工具在快捷栏就切换选中格；在背包第 9～35 格则先显示背包，再与当前快捷栏格交换。
    private void submitToolSelection(PlayerContext context) {
        int bestSlot = pendingToolSlot;
        // 切快捷栏也是一次交互：本刻没有机会就留到下一刻，选好的格子不丢。
        if (bestSlot >= 0 && bestSlot < 9 && !context.canInteractThisTick()) return;
        if (bestSlot >= 9 && bestSlot < 36 && !menuActions.ensureVisible(context)) return;
        pendingToolSlot = -1;
        int selected = player.getInventory().selected;
        if (bestSlot >= 0 && bestSlot < 9 && bestSlot != selected) {
            toolSelectPending = sender.selectHotbar(
                    context, bestSlot, TOOL_TIMEOUT_TICKS);
        } else if (bestSlot >= 9 && bestSlot < 36) {
            // 工具在背包里时换到“当前手上那格”；扩展快捷栏模组可能让 selected 越出 0~8，
            // 原版 SWAP 交换只认 0~8，先折回原版范围再交换。
            toolStagePending = menuActions.swapInventoryToHotbar(
                    context, bestSlot, VanillaHotbar.swapTarget(selected), TOOL_TIMEOUT_TICKS);
        }
    }

    /**
     * 结束此挖掘器所有还没结清的操作：挖掘、切工具、为换工具打开的背包。
     *
     * <p>挖掘器可能在第一次挥动之前、仍处于选择或准备工具阶段时被取消。这些确认占用与破坏动作相同的
     * 交互串行槽位；只清空本地字段会遗留占用，使下一任务因“仍在等待确认”而失败。因此要实际停止破坏，
     * 将已提交的一次性快捷栏选择标记为结果不确定，并在任务边界关闭待处理的菜单准备操作。
     */
    // 取消不只是清变量：要通知游戏停止挖掘，结束未确认的切工具操作，并处理还开着的背包。
    public void cancel(PlayerContext context) {
        boolean pendingBreak = pending != null && !pending.terminal();
        boolean pendingSelection = toolSelectPending != null && !toolSelectPending.terminal();
        if (context == null || !context.isCurrent()) {
            // 收尾时已经拿不到本刻的上下文（例如任务在两刻之间被换掉）：停挖留到下一刻在新任务之前做，
            // 还在等确认的切工具按不确定交还；不能因为拿不到上下文就让挖掘一直按着。
            if (pendingBreak) {
                sender.deferBreakCancellationForTaskBoundary(pending,
                        "the block-digging task ended before its native break was confirmed");
            }
            if (pendingSelection) {
                sender.abandonOneShotForTaskBoundary(toolSelectPending,
                        "the block-digging task ended while tool selection was awaiting confirmation");
            }
            reset();
            return;
        }
        boolean pendingMenu = (toolClosePending != null && !toolClosePending.terminal())
                || (toolStagePending != null && !toolStagePending.terminal())
                || MenuVisibility.inventoryVisible(
                        Minecraft.getInstance(), player);
        if (pendingBreak) {
            sender.cancelBreakingForTaskBoundary(
                    context,
                    pending,
                    "the block-digging task ended before its native break was confirmed");
        }
        if (pendingSelection) {
            sender.retireOneShotForTaskBoundary(
                    context,
                    toolSelectPending,
                    "the block-digging task ended while tool selection was awaiting confirmation");
        }
        if (pendingMenu) {
            menuActions.closeForTaskBoundary(
                    context,
                    TOOL_TIMEOUT_TICKS,
                    "the block-digging task ended while tool staging was awaiting confirmation");
        }
        reset();
    }

    /** 清除逻辑状态，但有意保留破坏后的冷却计时。 */
    // 只清本次挖掘的局部记录；挖完后的冷却仍保留，避免连续挖掘绕过速度设置。
    private void reset() {
        pos = null;
        pending = null;
        toolSelectPending = null;
        toolClosePending = null;
        toolStagePending = null;
        pendingToolSlot = -1;
        destroyingItem = null;
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
     * 返回眼部视线在 {@code pos} 上能命中的第一个点：先试方块形状中心，再试六个面中心。
     * 返回的 {@link BlockHitResult} 保存精确瞄准点（{@code getLocation}）和命中面（{@code getDirection}），
     * 使挖掘器能像玩家一样对准实际交互面；若整个方块都不在视线内则返回 {@code null}。
     */
    // 从方块实际形状的中心和六个方向找可见点，射线必须真的落到目标格。
    // 门、楼梯等不是完整立方体，不能只拿整格中心判断能不能挖。
    public BlockHitResult reachableHit(BlockPos pos) {
        Level level = player.level();
        if (!level.isLoaded(pos)) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        double reach = InteractionRange.blockReach(player);
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        // 先检查碰撞形状中心（没有碰撞形状时用整格中心，火取底面），再检查选择形状的六个面中心。
        Vec3[] aims = {
                collisionCenter(level, pos, state),
                offsetOn(pos, shape, 0.5, 0.0, 0.5),
                offsetOn(pos, shape, 0.5, 1.0, 0.5),
                offsetOn(pos, shape, 0.5, 0.5, 0.0),
                offsetOn(pos, shape, 0.5, 0.5, 1.0),
                offsetOn(pos, shape, 0.0, 0.5, 0.5),
                offsetOn(pos, shape, 1.0, 0.5, 0.5),
        };
        if (preferTopFace) {
            // 自上而下刨坑时先试着瞄准上表面；返回的仍是实际射线命中面，不把侧面伪装成上面。
            Vec3 center = aims[0]; aims[0] = aims[1]; aims[1] = center;
        }
        for (Vec3 aim : aims) {
            Vec3 dir = aim.subtract(eye);
            if (dir.lengthSqr() < 1.0e-8) continue;
            Vec3 end = eye.add(dir.normalize().scale(reach));
            BlockHitResult res = level.clip(new ClipContext(
                    eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (res.getType() == HitResult.Type.BLOCK && res.getBlockPos().equals(pos)
                    && (requiredFace == null || res.getDirection() == requiredFace)) {
                return res;
            }
        }
        return null;
    }

    /** 碰撞形状的中心；没有碰撞形状时退回整格中心。火没有碰撞，取它的底面高度：灭火要看火的根部。 */
    private static Vec3 collisionCenter(Level level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            double y = state.getBlock() instanceof BaseFireBlock ? 0.0 : 0.5;
            return new Vec3(pos.getX() + 0.5, pos.getY() + y, pos.getZ() + 0.5);
        }
        double y = state.getBlock() instanceof BaseFireBlock ? 0.0
                : (shape.min(Direction.Axis.Y) + shape.max(Direction.Axis.Y)) / 2.0;
        return new Vec3(
                pos.getX() + (shape.min(Direction.Axis.X) + shape.max(Direction.Axis.X)) / 2.0,
                pos.getY() + y,
                pos.getZ() + (shape.min(Direction.Axis.Z) + shape.max(Direction.Axis.Z)) / 2.0);
    }

    /**
     * 从眼部向 {@code target} 形状中心发出单条射线；当 {@link #reachableHit} 找不到可见面时，作为清除遮挡物的后备方案。
     * 若射线命中树叶或狭窄顶棚，就破坏该遮挡以打开通路；未命中或超出距离时返回空。
     * {@link #reachableHit} 已先检查中心，因此到达此处代表中心射线命中了其他方块。
     */
    // 朝目标整格中心看过去，返回最先挡住视线的方块，供允许拆遮挡物的入口选择。
    private BlockHitResult centerRaycast(BlockPos target) {
        Level level = player.level();
        if (!level.isLoaded(target)) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        double reach = InteractionRange.blockReach(player);
        Vec3 center = Vec3.atCenterOf(target);
        Vec3 dir = center.subtract(eye);
        if (dir.lengthSqr() < 1.0e-8) {
            return null;
        }
        Vec3 end = eye.add(dir.normalize().scale(reach));
        BlockHitResult res = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return res.getType() == HitResult.Type.BLOCK ? res : null;
    }

    /** 方块形状上的一点：每个轴都按 {@code min*m + max*(1-m)} 计算。 */
    // 把 0、0.5、1 映射到方块形状的两端和中间；这里 0 取最大边，1 取最小边。
    private static Vec3 offsetOn(BlockPos pos, VoxelShape shape, double mx, double my, double mz) {
        double x = shape.min(Direction.Axis.X) * mx + shape.max(Direction.Axis.X) * (1 - mx);
        double y = shape.min(Direction.Axis.Y) * my + shape.max(Direction.Axis.Y) * (1 - my);
        double z = shape.min(Direction.Axis.Z) * mz + shape.max(Direction.Axis.Z) * (1 - mz);
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }
}
