package org.maiwithu.maicraft.core.pathing.execute;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;

import java.util.EnumMap;

import org.maiwithu.maicraft.core.pathing.moves.Input;
import org.maiwithu.maicraft.core.pathing.moves.Movement;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.pathing.moves.MovementState;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import org.maiwithu.maicraft.core.act.ToolSelect;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import net.minecraft.client.player.LocalPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 第一人称执行边界:把移动原语每 tick 的视角、按键和点击意图交给客户端 actor。
 *
 * <p>移动与视角使用一刻租约;挖掘、使用、热栏选择和菜单换位均保留跨 tick
 * 回执,只有客户端可观察事实确认后才记账或继续依赖该动作。执行器不直接写位置、
 * 速度、视角、按键字段或世界方块。
 * <ol>
 *   <li>准星尚未命中目标时只转头,不提交点击;</li>
 *   <li>同 tick 破坏优先于使用,原生动作串行化;</li>
 *   <li>深层背包材料先经菜单回执换入热栏,再经热栏回执选中。</li>
 * </ol>
 */
public final class ExecHarness implements Movement.ExecutionDelegate {

    private final LocalPlayer player;
    private final AimProcessor aim;
    /** 这次导航对地形的许可(与搜索/执行上下文同一来源:PlayerNav 的 ContextProvider)。 */
    private final TerrainPermit permit;
    /**
     * 这次导航真挖了什么、真放了什么。执行器是唯一动手的地方,账也只记在这儿:
     * 任务回执末尾如实相告(en route: broke …; placed …),模型事后至少知道自己干过什么。
     */
    private final TerrainBill ledger = new TerrainBill();

    /** 本 tick 的按键表(跨 tick 保留,移动原语每 tick 清空重设)。 */
    private final EnumMap<Input, Boolean> keys = new EnumMap<>(Input.class);

    /** 本 tick 的期望视角;commit 后即失效。 */
    private MovementState.MovementTarget target;

    /** 距下一次允许右键的 tick 数。 */
    private int rightClickCooldown;

    private NativeActionReceipt breakingReceipt;
    private BlockPos breakingTarget;
    private BlockState breakingBefore;

    private NativeActionReceipt useReceipt;
    private InteractionHand useHand;
    private BlockPos usePlaceAt;
    private BlockState usePlaceBefore;
    private BlockPos useClickedAt;
    private BlockState useClickedBefore;
    private NativeActionReceipt hotbarReceipt;
    private int hotbarTarget = -1;
    private MenuReceipt stagingReceipt;
    private int stagingHotbar = -1;


    /** Sprint is folded into the same one-tick body lease as the movement axes. */
    private boolean sprinting;

    /** 本 tick 是否有任何记录待落地。 */
    private boolean dirty;
    /** forceCancel is a terminal owner boundary; cleanup may invoke it again in the same tick. */
    private boolean taskBoundaryStopped;

    public ExecHarness(LocalPlayer player, TerrainPermit permit) {
        this.player = player;
        this.permit = permit;
        this.aim = new AimProcessor();
    }

    @Override
    public TerrainPermit permit() {
        return permit;
    }

    /** 这次导航至今真动过的地形(只读视图;空账 = 一块没动)。 */
    public TerrainBill ledger() {
        return ledger;
    }

    // ==================== ExecutionDelegate 四钩子 ====================

    /**
     * 开挖一格:找该格的可视瞄点(形状中心 + 六面心逐一试射线),把
     * 视角目标设过去;实际视角的射线已命中该格(或角度已贴住目标)
     * 才按左键——视线步进决定挖掘的起始时机。完全不可视时直接瞄方块
     * 中心强挖(射线打中什么破什么,遮挡回退由挖掘器处理)。
     */
    @Override
    public void beginBreaking(MovementState state, BlockPos pos) {
        dirty = true;
        // A route may have to clear terrain before the mining task reaches its
        // own target.  Stage/select the best carried implement through the same
        // receipt-backed boundary before the first native break tick.  Once a
        // break receipt is live, keep advancing it without trying to transact
        // inventory every tick.
        if (breakingReceipt == null || breakingReceipt.terminal()) {
            settleBreakingReceipt(ClientRuntime.requireContext(player));
            int bestSlot = ToolSelect.bestSlot(player, player.level().getBlockState(pos));
            if (bestSlot >= 0) {
                ItemStack best = player.getInventory().getItem(bestSlot).copy();
                Movement.ItemSelection selection = ensureItem(
                        stack -> ItemStack.isSameItemSameComponents(stack, best), true, false);
                if (selection == Movement.ItemSelection.WAITING) {
                    state.setInput(Input.CLICK_LEFT, false);
                    return;
                }
            }
        }
        Vec3 eye = player.getEyePosition();
        Vec3 aimPoint = reachableAimPoint(pos);
        if (aimPoint != null) {
            state.setTarget(new MovementState.MovementTarget(
                    AimGeometry.yawTo(eye, aimPoint),
                    AimGeometry.pitchTo(eye, aimPoint), true));
            if (isLookingAt(pos) || isFacingTarget(state.getTarget())) {
                state.setInput(Input.CLICK_LEFT, true);
            }
        } else {
            // 完全不可视:瞄整格中心强按左键——实际挖到的是准星命中的
            // 遮挡物(左键落地始终以准星射线为准)
            Vec3 center = AimGeometry.blockCenter(pos);
            state.setTarget(new MovementState.MovementTarget(
                    AimGeometry.yawTo(eye, center),
                    AimGeometry.pitchTo(eye, center), true));
            state.setInput(Input.CLICK_LEFT, true);
        }
    }

    @Override
    public void applyRotation(MovementState.MovementTarget target) {
        this.target = target;
        dirty = true;
    }

    @Override
    public void clearInputs() {
        keys.clear();
        dirty = true;
    }

    @Override
    public void applyInput(Input input, boolean held) {
        keys.put(input, held);
        dirty = true;
    }

    // ==================== 执行器覆写面 ====================

    /** 某键当前是否被请求按下。 */
    public boolean isKeyRequested(Input input) {
        return keys.getOrDefault(input, false);
    }

    /** 强制设键(执行器的疾跑接管、直跳强按等)。 */
    public void forceKey(Input input, boolean held) {
        keys.put(input, held);
        dirty = true;
    }

    /**
     * 清空全部按键并立即停住身体(输入字段清零、松疾跑、松潜行)。
     * 取消/暂停路径时用;不打断进行中的挖掘(那是 {@link #stopBreaking})。
     */
    public void clearAllKeys() {
        keys.clear();
        target = null;
        InputDriver.halt(player);
        InputDriver.sneak(player, false);
        sprinting = false;
    }

    /** 中止进行中的原生挖掘;回执不跨身体 epoch 复用。 */
    public void stopBreaking() {
        NativeActionReceipt receipt = breakingReceipt;
        if (receipt != null && !receipt.terminal()) {
            try {
