// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.Set;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.chain.TorchLightingChain;

/** 准备一叠副手火把 -> 借空闲准星点击 -> 等原生回执；所有等待都不发走路或停步指令。 */
public final class OffhandTorchPlacer {
    private MenuReceipt swap;
    private NativeActionReceipt placement;
    private BuildTaskRecord.Target target;
    private long retryAt;
    private String state = "ready";

    public boolean pending() { return placement != null && !placement.terminal(); }
    public String state() { return state; }
    public BuildTaskRecord.Target target() { return target; }

    /** 区域任务退出时结清已经提交的一支；无法确认则留下未知，不让下一轮自动重发旧灯位。 */
    public NativeActionReceipt retire(LocalPlayerContext context) {
        if (placement == null) return null;
        if (!placement.terminal()) context.actions().retireOneShotForTaskBoundary(context, placement, "lighting pass ended");
        return poll(context);
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

    public boolean place(LocalPlayerContext context, BuildTaskRecord.Target candidate, Set<BlockPos> protectedCells) {
        if (!prepare(context) || !RoutineTorchPlacement.stillUsable(context.player(), candidate, protectedCells)) return false;
        var player = context.player();
        Direction face = candidate.desiredState().is(Blocks.WALL_TORCH)
                ? candidate.desiredState().getValue(WallTorchBlock.FACING) : Direction.UP;
        BlockPos support = candidate.pos().relative(face.getOpposite());
        Vec3 point = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.5));
        Vec3 delta = point.subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        // 主任务已经瞄准矿石或敌人时直接让位；只在原地可见可达时低头，不建立任何绕路目标。
        if (!context.body().tryAuxiliaryLook(yaw, pitch, context.tickRevision())) { state = "primary_aim_busy"; return false; }
        boolean submitted = false;
        try {
            Vec3 end = player.getEyePosition().add(player.getViewVector(1).scale(Math.min(4.25, player.blockInteractionRange())));
            var hit = context.level().clip(new ClipContext(player.getEyePosition(), end,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(support) || hit.getDirection() != face) {
                state = "placement_ray_changed"; return false;
            }
            placement = context.actions().tryAuxiliaryBlockUse(context, hit, TorchLightingChain.confirmation(player, candidate), 40);
            if (placement == null) { state = "native_action_busy"; return false; }
            target = candidate; submitted = true;
            state = "awaiting_native_confirmation";
            return true;
        } finally {
            // 放不下或旧回执未结清时完整撤回镜头试探；真正出手后立即归还主目标，只留下自己的异步回执。
            context.body().finishAuxiliaryLook(submitted, context.tickRevision());
            if (!submitted) retryAt = context.tickRevision() + 5;
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
