// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.Set;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.chain.TorchLightingChain;

/** 准备一叠副手火把 -> 平滑转到灯位对准 -> 借准星点击 -> 等原生回执；所有等待都不发走路或停步指令。 */
public final class OffhandTorchPlacer {
    /** 单支补光从开始转到灯位到对准的等待上限(游戏刻)；转不到就等路线进展后再试，不为一支灯原地甩头。 */
    private static final long AIM_TICK_BUDGET = 40L;
    /** 转向到位但射线被挡后的重试间隔(游戏刻)；不能逐刻消耗同一落点。 */
    private static final long AIM_RETRY_TICKS = 20L;
    /** 目标准星偏差容差(度)；超过它不提交点击，避免原生射线落到灯位旁边的方块上。 */
    private static final float AIM_ANGLE_TOLERANCE = 0.75f;

    private MenuReceipt swap;
    private NativeActionReceipt placement;
    private BuildTaskRecord.Target target;
    private long retryAt;
    private long aimFromTick;
    private boolean aimReady;
    private String state = "ready";

    public boolean pending() { return placement != null && !placement.terminal(); }
    public String state() { return state; }
    public BuildTaskRecord.Target target() { return target; }

    /**
     * 是否正在为某一支还没提交的火把转动镜头。
     * 转向期间准星记在补光名下，调用方必须继续喂给它，不能因为"当前看起来在让位"就撤回，否则镜头会在灯位和路线之间来回甩。
     */
    public boolean aiming() { return target != null; }

    /** 区域任务退出时结清已经提交的一支；无法确认则留下未知，不让下一轮自动重发旧灯位。 */
    public NativeActionReceipt retire(LocalPlayerContext context) {
        // 灯位记录归 poll 读回执用，这里只撤回还没提交的补光转向，不提前清掉 target。
        clearAim();
        context.body().finishAuxiliaryLook(false, context.tickRevision());
        if (placement == null) return null;
        if (!placement.terminal()) context.actions().retireOneShotForTaskBoundary(context, placement, "lighting pass ended");
        return poll(context);
    }

    /** 撤回还没提交的补光转向；已经发出的火把保留灯位，回执还要靠它结算。 */
    private void clearAim() {
        if (placement != null) return;
        target = null;
        aimReady = false;
    }

    /** 本轮不再出手时撤回还没提交的补光转向；镜头随即平滑转回主任务路线，不留下借来的灯位视角。 */
    public void releaseAim(LocalPlayerContext context) {
        if (target == null) return;
        clearAim();
        context.body().finishAuxiliaryLook(false, context.tickRevision());
    }

    /** 灯位在转向途中失效：撤回这次转向并释放灯位，等调用方挑下一个候选，不把镜头钉在点不到的格子上。 */
    private boolean abandonAim(LocalPlayerContext context) {
        clearAim();
        state = "site_not_usable";
        context.body().finishAuxiliaryLook(false, context.tickRevision());
        return false;
    }

    public NativeActionReceipt poll(LocalPlayerContext context) {
        if (placement == null) return null;
        placement = context.actions().poll(context, placement);
        if (!placement.terminal()) return null;
        var settled = placement;
        placement = null;
        // 方块确认后给光引擎五刻传播时间；失败也至少等一秒，不能逐刻消耗火把试同一个落点。
        retryAt = context.tickRevision() + (settled.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED ? 5 : 20);
        state = settled.status().name().toLowerCase(Locale.ROOT);
        return settled;
    }

    public boolean prepare(LocalPlayerContext context) {
        // 先等上次副手交换回执，再考虑取下一叠；交换整叠火把时，原副手物品由原生菜单放回该背包槽。
        if (swap != null) {
            swap = context.menus().poll(context, swap);
            if (!swap.terminal()) return false;
            if (swap.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                state = "offhand_swap_" + swap.status().name().toLowerCase(Locale.ROOT);
                retryAt = context.tickRevision() + 100;
            }
            swap = null;
        }
        if (pending() || context.tickRevision() < retryAt || !idle(context)) return false;
        if (context.player().getOffhandItem().is(Items.TORCH)) return true;
        int slot = PlayerInv.findSlot(context.player().getInventory(), Items.TORCH);
        if (slot < 0 || slot >= 36) { state = "missing_torches"; return false; }
        swap = context.menus().swapInventoryToOffhand(context, slot, 40);
        state = "preparing_offhand";
        return false;
    }

    /**
     * 借用准星放置一支火把。补光先按正常转头速度转到灯位，对准之前不点击：
     * 原版射线落在哪个方块就点哪个，转向途中提交会把火把放到别处，所以宁可多等几刻。
     *
     * @return true 表示本刻真的提交了一次原生副手点击
     */
    public boolean place(LocalPlayerContext context, BuildTaskRecord.Target candidate, Set<BlockPos> protectedCells) {
        var player = context.player();
        // 当前正在转到这个灯位时跳过副手准备：转向本身推动镜头，把准星交给下一刻继续收敛。
        if (target != null && target.equals(candidate) && context.tickRevision() - aimFromTick < AIM_TICK_BUDGET) {
            // 沿路线走出触及范围或灯位被占后就别再朝它转：撤回转向并交还灯位选择，让调用方换下一个候选，
            // 否则镜头会一直对着一个永远点不到的位置，补光也再也出不了手。
            if (!RoutineTorchPlacement.stillUsable(player, candidate, protectedCells)) return abandonAim(context);
            Direction face = candidate.desiredState().is(Blocks.WALL_TORCH)
                    ? candidate.desiredState().getValue(WallTorchBlock.FACING) : Direction.UP;
            BlockState occupied = context.level().getBlockState(candidate.pos());
            boolean replaceableCell = candidate.desiredState().is(Blocks.TORCH) && !occupied.isAir()
                    && occupied.canBeReplaced() && context.level().getFluidState(candidate.pos()).isEmpty();
            BlockPos support = candidate.pos().relative(face.getOpposite());
            Vec3 point = replaceableCell
                    ? Vec3.atCenterOf(candidate.pos())
                    : Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.5));
            Vec3 delta = point.subtract(player.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
            // 已经对准过就直接出手；否则续订本刻转向，让镜头朝灯位继续平滑推进。
            if (aimReady) return submit(context, candidate, protectedCells);
            if (!context.body().tryAuxiliaryLook(yaw, pitch, context.tickRevision())) {
                state = "primary_aim_busy";
                return false;
            }
            if (Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) > AIM_ANGLE_TOLERANCE
                    || Math.abs(player.getXRot() - pitch) > AIM_ANGLE_TOLERANCE) {
                state = "turning_to_target";
                // 转向期间保持借用，镜头才有目标可以继续推进；真正出手或撤回时才调用 finishAuxiliaryLook 交还。
                return false;
            }
        }
        // 转向等待超预算或换了灯位时重做资格准备，并复核这个灯位此刻是否仍可放。
        if (!prepare(context)) return false;
        if (!RoutineTorchPlacement.stillUsable(player, candidate, protectedCells)) {
            // 这一格已经点不到：连同灯位一起放掉，让调用方下一刻重新挑，不再让镜头对着它反复重启。
            target = null;
            aimReady = false;
            state = "site_not_usable";
            return false;
        }
        // 草丛等可替换落点是点击本体；命中该格即提交，原版放置语义会原地替换成火把，不再要求射线穿到支撑面。
        BlockState occupied = context.level().getBlockState(candidate.pos());
        boolean replaceableCell = candidate.desiredState().is(Blocks.TORCH) && !occupied.isAir()
                && occupied.canBeReplaced() && context.level().getFluidState(candidate.pos()).isEmpty();
        Direction face = candidate.desiredState().is(Blocks.WALL_TORCH)
                ? candidate.desiredState().getValue(WallTorchBlock.FACING) : Direction.UP;
        BlockPos support = candidate.pos().relative(face.getOpposite());
        Vec3 point = replaceableCell
                ? Vec3.atCenterOf(candidate.pos())
                : Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.5));
        Vec3 delta = point.subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        // 主任务已经瞄准矿石或敌人时直接让位；只在原地可见可达时低头，不建立任何绕路目标。
        if (!context.body().tryAuxiliaryLook(yaw, pitch, context.tickRevision())) {
            state = "auxiliary_look_unavailable";
            return false;
        }
        aimFromTick = context.tickRevision();
        aimReady = false;
        state = "turning_to_target";
        // 角度还没进容差：本刻只登记转向，下一刻由调用方继续续订同一次补光转向。
        if (Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) > AIM_ANGLE_TOLERANCE
                || Math.abs(player.getXRot() - pitch) > AIM_ANGLE_TOLERANCE) {
            target = candidate;
            context.body().finishAuxiliaryLook(false, context.tickRevision());
            return false;
        }
        // 角度到位后复核真实射线；被别处方块挡住时不逐刻消耗火把试力度，等下一轮重新转向。
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1).scale(Math.min(4.25, player.blockInteractionRange())));
        var hit = context.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(replaceableCell ? candidate.pos() : support)
                || !replaceableCell && hit.getDirection() != face) {
            state = "placement_ray_changed";
            retryAt = context.tickRevision() + AIM_RETRY_TICKS;
            return false;
        }
        // 已经对准：记住这次待提交的灯位，本刻立即出手。
        target = candidate;
        aimReady = true;
        return submit(context, candidate, protectedCells);
    }

    /** 准星已经对准时的真正出手：提交副手使用后立即归还主任务的视角目标，只留下自己的异步回执。 */
    private boolean submit(LocalPlayerContext context, BuildTaskRecord.Target candidate, Set<BlockPos> protectedCells) {
        // 灯位在等待对准期间可能已经变化，出手前重新确认它此刻仍然可放。
        if (!RoutineTorchPlacement.stillUsable(context.player(), candidate, protectedCells)) {
            state = "site_not_usable";
            target = null;
            aimReady = false;
            context.body().finishAuxiliaryLook(false, context.tickRevision());
            return false;
        }
        var player = context.player();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1).scale(Math.min(4.25, player.blockInteractionRange())));
        var hit = context.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        boolean submitted = false;
        try {
            if (hit.getType() != HitResult.Type.BLOCK) { state = "placement_ray_changed"; return false; }
            placement = context.actions().tryAuxiliaryBlockUse(context, hit, TorchLightingChain.confirmation(player, candidate), 40);
            if (placement == null) { state = "native_action_busy"; return false; }
            target = candidate; submitted = true;
            state = "awaiting_native_confirmation";
            return true;
        } finally {
            // 放不下或旧回执未结清时完整撤回镜头试探；真正出手后立即归还主目标，只留下自己的异步回执。
            context.body().finishAuxiliaryLook(submitted, context.tickRevision());
            if (submitted) aimReady = false;
            else retryAt = context.tickRevision() + 5;
        }
    }

    public static boolean idle(LocalPlayerContext context) {
        // 角色在地面、空手势且主动作已经让出准星和本刻修改额度时才借用；游泳、骑乘、吃东西或开菜单时等待。
        var player = context.player();
        return context.permitsNativeActions() && context.mutationAvailable() && ClientRuntime.actor().settledForRoutinePause()
                && context.body().auxiliaryLookAvailable(context.tickRevision())
                && context.minecraft().screen == null && player.containerMenu == player.inventoryMenu
                && player.inventoryMenu.getCarried().isEmpty() && !player.isUsingItem()
                && player.isAlive() && player.onGround() && !player.isPassenger() && !player.isInWater()
                && !player.isInLava() && !player.isSleeping();
    }
}
