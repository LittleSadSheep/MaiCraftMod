package org.maiwithu.maicraft.core.act;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;


import org.maiwithu.maicraft.entity.InputDriver;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Progressive first-person block breaking through the serialized native action boundary:
 * <ul>
 *   <li>stage or select the best usable tool and await its receipt;</li>
 *   <li>aim at a raycast-verified face and advance the native break receipt each tick;</li>
 *   <li>report success only after synchronized client facts confirm the target changed.</li>
 * </ul>
 * Shared by path-obstruction clearing ({@code ExecHarness}), auto-mine
 * ({@code MineCompanionTask}), and {@link Interaction} (break_block / interact).
 */
public final class BlockDigger {

    private static final int BREAK_TIMEOUT_TICKS = 20 * 60 * 5;
    private static final int TOOL_TIMEOUT_TICKS = 20 * 5;

    /** Ticks to wait after a survival break before starting another; follows the
     *  blockBreakSpeed setting (period = the setting, delay = setting − 1). */
    private static int postBreakDelay() {
        return Math.max(0,
                org.maiwithu.maicraft.core.pathing.settings.NavSettings.get().blockBreakSpeed - 1);
    }

    private final LocalPlayer player;
    private BlockPos pos;
    private NativeActionReceipt receipt;
    private NativeActionReceipt toolSelectReceipt;
    private MenuReceipt toolCloseReceipt;
    private MenuReceipt toolStageReceipt;
    private int pendingToolSlot = -1;
    private int blockHitDelay;    // post-break cooldown (survives reset())
    /** 开挖时的主手物品快照;中途换持(物品/组件级)即重开进度。 */
    private net.minecraft.world.item.ItemStack destroyingItem;

    public BlockDigger(LocalPlayer player) {
        this.player = player;
    }

    /** The block currently being dug, or {@code null} when idle. */
    public BlockPos current() {
        return pos;
    }

    /** Outcome of one {@link #digStep} tick — lets callers distinguish "still working"
     *  from "physically can't get at it", which the old boolean folded together. */
    public enum DigResult {
        /** Break in progress, cooling down, or begun this tick — keep calling. */
        PROGRESSING,
        /** The TARGET block's break committed this tick. */
        BROKE_TARGET,
        /** An OCCLUDER in the way broke this tick (not the target) — a step toward it. */
        BROKE_OCCLUDER,
        /** No face of the target is reachable and nothing safe occludes it — stuck (maps to OCCLUDED). */
        NO_SHOT;

        /** 本 tick 有方块真的没了(目标或遮挡物)。 */
        public boolean broke() {
            return this == BROKE_TARGET || this == BROKE_OCCLUDER;
        }
    }

    /** Legacy boolean shim: {@code true} only on the tick the TARGET breaks. Kept so
     *  pre-migration callers ({@link Interaction}) compile unchanged; delete once every
     *  caller consumes {@link #digStep}. */
    public boolean dig(BlockPos target) {
        return digStep(target) == DigResult.BROKE_TARGET;
    }

    /**
     * Advance the dig of {@code target} by one tick (restarting cleanly if the
     * target changed): face it, drive the native break action, swing.
     *
     * @return the {@link DigResult} for this tick.
     */
    /**
     * Advance the dig one tick using an ALREADY-RESOLVED crosshair hit: dig
     * exactly the block (and face) the caller's view ray landed on, no internal
     * aim-point search. The hit block is always treated as the target.
     */
    public DigResult digStep(BlockHitResult crosshairHit) {
        if (blockHitDelay > 0) {                    // let the previous break land first
            blockHitDelay--;
            InputDriver.halt(player);
            return DigResult.PROGRESSING;
        }
        InputDriver.halt(player);
        BlockPos effective = crosshairHit.getBlockPos();
        if (pos == null || !pos.equals(effective)) {
            // 工具由外层(移动原语按意图格)选择,这里不按命中格改选
            if (!start(effective, false)) {
                return DigResult.PROGRESSING;
            }
        }
        return advance(crosshairHit, true);
    }

    /** 破块后冷却按游戏刻递减;本 tick 没走 digStep 的驱动方调用此保持计时。 */
    public void tickCooldown() {
        if (blockHitDelay > 0) {
            blockHitDelay--;
        }
    }

    public DigResult digStep(BlockPos target) {
        Level level = player.level();
        if (blockHitDelay > 0) {                    // let the previous break land first
            blockHitDelay--;
            InputDriver.halt(player);
            return DigResult.PROGRESSING;
        }
        // Resolve what to actually swing at this tick. First try a raycast-VERIFIED face on the
        // target. If the target is OCCLUDED — no face in line of
        // sight (leaves in front, a tight column overhead) — fall back to breaking the
        // occluder: aim at the target's centre and break whatever the
        // crosshair actually hits, opening the way, instead of holding forever for a clear angle.
        // One guard: never grind a do_not_break / container block as the occluder.
        BlockHitResult hit = reachableHit(target);
        BlockPos effective = target;
        if (hit == null) {
            BlockHitResult center = centerRaycast(target);
            if (center != null && !center.getBlockPos().equals(target)
                    && !BlockHelper.shouldAvoidBreaking(level, center.getBlockPos())) {
                hit = center;
                effective = center.getBlockPos();
            }
        }
        InputDriver.halt(player);
        if (hit == null) {
            return DigResult.NO_SHOT;                // no clear shot, nothing safe in the way — stuck
        }
        if (pos == null || !pos.equals(effective)) {
            if (!start(effective, true)) {
                return DigResult.PROGRESSING;
            }
        }
        // dig() may be clearing an OCCLUDER this tick, not the target; report the break (true) ONLY
        // when the TARGET itself goes, so callers that count mined targets / treat the cell as cleared
        // aren't fooled by a leaf we broke just to open the line of sight.
        return advance(hit, effective.equals(target));
    }


    public DigResult digTargetStep(BlockPos target) {
        if (blockHitDelay > 0) {
            blockHitDelay--;
            InputDriver.halt(player);
            return DigResult.PROGRESSING;
        }
        BlockHitResult hit = reachableHit(target);
        InputDriver.halt(player);
        if (hit == null) {
            return DigResult.NO_SHOT;
        }
        if (pos == null || !pos.equals(target)) {
            if (!start(target, true)) {
                return DigResult.PROGRESSING;
            }
        }
        return advance(hit, true);
    }
    /** Shared per-tick dig advance against a resolved hit (face + aim point). */
    private DigResult advance(BlockHitResult hit, boolean targetBreak) {
        LocalPlayerContext toolContext = ClientRuntime.requireContext(player);
        if (toolCloseReceipt != null) {
            if (!toolCloseReceipt.terminal()) {
                toolCloseReceipt = toolContext.menus().poll(toolContext, toolCloseReceipt);
            }
            if (!toolCloseReceipt.terminal()) {
                return DigResult.PROGRESSING;
            }
            MenuReceipt.Status status = toolCloseReceipt.status();
            toolCloseReceipt = null;
            if (status != MenuReceipt.Status.CONFIRMED_APPLIED) {
                reset();
                return DigResult.NO_SHOT;
            }
            submitToolSelection(toolContext);
        }
        if (toolStageReceipt != null) {
            if (!toolStageReceipt.terminal()) {
                toolStageReceipt = toolContext.menus().poll(toolContext, toolStageReceipt);
            }
            if (!toolStageReceipt.terminal()) {
                return DigResult.PROGRESSING;
            }
            MenuReceipt.Status status = toolStageReceipt.status();
            toolStageReceipt = null;
            if (status != MenuReceipt.Status.CONFIRMED_APPLIED) {
                reset();
                return DigResult.NO_SHOT;
            }
        }
        if (toolSelectReceipt != null) {
            if (!toolSelectReceipt.terminal()) {
                toolSelectReceipt = toolContext.actions().poll(toolContext, toolSelectReceipt);
            }
            if (!toolSelectReceipt.terminal()) {
                return DigResult.PROGRESSING;
            }
            NativeActionReceipt.Status status = toolSelectReceipt.status();
            toolSelectReceipt = null;
            if (status != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                reset();
                return DigResult.NO_SHOT;
            }
        }
        if (receipt != null && !receipt.terminal() && destroyingItem != null
                && !net.minecraft.world.item.ItemStack.isSameItemSameComponents(
                        destroyingItem, player.getMainHandItem())) {
            cancel();
            return DigResult.PROGRESSING;
        }
        InputDriver.lookAt(player, hit.getLocation());
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            if (!aimReady(hit.getLocation())) {
                return DigResult.PROGRESSING;
            }
            receipt = context.actions().startBreaking(context, hit, BREAK_TIMEOUT_TICKS);
            destroyingItem = player.getMainHandItem().copy();
        } else {
            receipt = context.actions().continueBreaking(context, receipt);
        }

        if (!receipt.terminal()) {
            return DigResult.PROGRESSING;
        }
        NativeActionReceipt.Status status = receipt.status();
        reset();
        if (status == NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            blockHitDelay = postBreakDelay();
            return targetBreak ? DigResult.BROKE_TARGET : DigResult.BROKE_OCCLUDER;
        }
        return DigResult.NO_SHOT;
    }

    private boolean start(BlockPos target, boolean selectTool) {
        if (receipt != null && !receipt.terminal()) {
            cancel();
            return false;
        }
        reset();
        pos = target.immutable();
        pendingToolSlot = selectTool && player.level().isLoaded(pos)
                ? ToolSelect.bestSlot(player, player.level().getBlockState(pos))
                : -1;
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (player.containerMenu != player.inventoryMenu) {
            toolCloseReceipt = context.menus().close(context, TOOL_TIMEOUT_TICKS);
        } else {
            submitToolSelection(context);
        }
        // 存活引用而非副本:主手栈原地变异(修补吸经验改耐久)时引用相等,
        // 不触发重置;只有真正换持(不同栈对象且物品/组件不同)才重开
        destroyingItem = player.getMainHandItem();
        return true;
    }

    private void submitToolSelection(LocalPlayerContext context) {
        int bestSlot = pendingToolSlot;
        pendingToolSlot = -1;
        int selected = player.getInventory().selected;
        if (bestSlot >= 0 && bestSlot < 9 && bestSlot != selected) {
            toolSelectReceipt = context.actions().selectHotbar(
                    context, bestSlot, TOOL_TIMEOUT_TICKS);
        } else if (bestSlot >= 9 && bestSlot < 36) {
            toolStageReceipt = context.menus().swapInventoryToHotbar(
                    context, bestSlot, selected, TOOL_TIMEOUT_TICKS);
        }
    }
    /** Abandon an in-progress native break and release its serialized action slot. */
    public void cancel() {
        if (receipt != null && !receipt.terminal()) {
            LocalPlayerContext context = ClientRuntime.requireContext(player);
            context.actions().cancelBreaking(context, receipt);
        }
        reset();
    }

