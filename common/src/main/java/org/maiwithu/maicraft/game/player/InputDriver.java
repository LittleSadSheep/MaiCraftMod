// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 给寻路等移动算法用的简短按键入口：朝前走、跳一下、看向一个点。
 *
 * <p>它把同一游戏刻的几个要求合成一组输入再交给 {@link PlayerInput}；每次都从
 * {@link PlayerControlBoundary} 重新取当前上下文，避免换维度后还操作旧对象。
 * 真正的控制权检查和输入写入在 {@link PlayerInput}；挖掘与右键动作不由此类管理。
 */
public final class InputDriver {
    private final PlayerControlBoundary boundary;
    // 同一刻的多次要求叠加成一组按键；进入新的一刻先从全松键开始。
    private long commandTick = Long.MIN_VALUE;
    private TickCommand accumulating;

    /** 从边界取上下文，而不是自己保存：玩家对象与控制权每刻都可能换。 */
    public InputDriver(PlayerControlBoundary boundary) {
        this.boundary = boundary;
    }

    // 朝目标的水平方向转头并按前进；这里不寻路，也不检查前面能否通过。
    public void stepToward(LocalPlayer player, Vec3 target, boolean sprint) {
        Vec3 delta = target.subtract(player.getEyePosition());
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
        look(player, yaw, 12.0f);
        applyMovement(player, 1.0f, 0.0f, false, false, sprint);
    }

    // 从眼睛到目标点算出左右、上下两个角度，再交给角色输入转头。
    // 坐在会旋转的载具上时，世界方向与相机方向差一个载具转角；那部分补偿属于对应的联动模组适配，不在这里做。
    public void lookAt(LocalPlayer player, Vec3 point) {
        Vec3 delta = point.subtract(player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
        float pitch = (float) -(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG);
        look(player, yaw, pitch);
    }

    public void look(LocalPlayer player, float yaw, float pitch) {
        PlayerContext context = context(player);
        if (context == null) return;
        context.input().requestLook(yaw, pitch, context.clientTick());
    }

    /** 路线朝向只作为本刻背景视角，不能覆盖攻击、放置或落地救援已经申请的准星方向。 */
    public void lookForNavigation(LocalPlayer player, float yaw, float pitch) {
        PlayerContext context = context(player);
        if (context != null) context.input().requestNavigationLook(yaw, pitch, context.clientTick());
    }

    // 给本刻已有的移动指令加上跳跃，不清掉同刻的前进或潜行。
    public void jump(LocalPlayer player) {
        PlayerContext context = context(player);
        if (context == null) return;
        TickCommand command = commandOf(context);
        command.jumping = true;
        command.flush();
    }

    // 改变本刻潜行状态，同时重新判断这种状态下能否疾跑。
    public void sneak(LocalPlayer player, boolean on) {
        PlayerContext context = context(player);
        if (context == null) return;
        TickCommand command = commandOf(context);
        command.sneaking = on;
        command.sprinting = permitsSprint(command.sprinting, on, player.isInWater());
        command.flush();
    }

    public void steerVehicle(LocalPlayer player, Vec3 target) {
        stepToward(player, target, false);
    }

    public void haltVehicle(LocalPlayer player) {
        halt(player);
    }

    public void halt(LocalPlayer player) {
        // 取消请求可能到达于两个角色刻之间。松开已有的输入不需要本刻的交互机会；
        // 若强制要求完整上下文，普通取消会抛错并误报移动效果不确定。
        if (boundary.activeContext().isEmpty()) {
            if (Minecraft.getInstance().player == player && boundary.input().automationOwnsControls()) {
                boundary.input().releaseAll();
            }
            return;
        }
        PlayerContext context = context(player);
        if (context == null) return;
        TickCommand command = commandOf(context);
        command.forward = 0.0f;
        command.strafe = 0.0f;
        command.jumping = false;
        command.sneaking = false;
        command.sprinting = false;
        command.flush();
    }

    // 用完整的一组要求覆盖本刻按键；越界的前后、左右数值压到 -1～1。
    public void applyMovement(
            LocalPlayer player,
            float requestedForward,
            float requestedStrafe,
            boolean requestedJump,
            boolean requestedSneak,
            boolean requestedSprint) {
        applyMovement(player, requestedForward, requestedStrafe, requestedJump, requestedSneak, requestedSprint, false);
    }

    /** 已由路线朝向定义的按键一路保留这一依据；随后追加跳跃或潜行也不能误改为镜头相对移动。 */
    public void applyNavigationMovement(LocalPlayer player, float forward, float strafe,
                                        boolean jump, boolean sneak, boolean sprint) {
        applyMovement(player, forward, strafe, jump, sneak, sprint, true);
    }

    private void applyMovement(LocalPlayer player, float requestedForward, float requestedStrafe,
                               boolean requestedJump, boolean requestedSneak, boolean requestedSprint,
                               boolean relativeToNavigation) {
        PlayerContext context = context(player);
        if (context == null) return;
        TickCommand command = commandOf(context);
        command.forward = Mth.clamp(requestedForward, -1.0f, 1.0f);
        command.strafe = Mth.clamp(requestedStrafe, -1.0f, 1.0f);
        command.jumping = requestedJump;
        command.sneaking = requestedSneak;
        command.sprinting = permitsSprint(requestedSprint, requestedSneak, player.isInWater());
        command.navigationRelative = relativeToNavigation;
        command.flush();
    }

    /** 在水中按 Shift 会下潜，因此不能用它取消疾跑游泳姿态。 */
    static boolean permitsSprint(boolean requested, boolean sneak, boolean inWater) {
        return requested && (!sneak || inWater);
    }

    private PlayerContext context(LocalPlayer player) {
        return boundary.activeContext()
                .filter(context -> context.localPlayer() == player)
                .filter(context -> context.input().automationOwnsControls())
                .orElse(null);
    }

    // 取出本刻累积中的按键组；换了刻就先清成全松键。
    private TickCommand commandOf(PlayerContext context) {
        if (accumulating == null || commandTick != context.clientTick()) {
            commandTick = context.clientTick();
            accumulating = new TickCommand();
            accumulating.reset(context);
        }
        return accumulating;
    }

    /** 本刻正在累积的一组按键要求；flush 时一次交给角色输入，由它在收尾时写进玩家。 */
    private final class TickCommand {
        PlayerContext context;
        float forward;
        float strafe;
        boolean jumping;
        boolean sneaking;
        boolean sprinting;
        boolean navigationRelative;

        void reset(PlayerContext context) {
            this.context = context;
            forward = 0.0f;
            strafe = 0.0f;
            jumping = false;
            sneaking = false;
            sprinting = false;
            navigationRelative = false;
        }

        void flush() {
            var movement = new PlayerInput.Movement(forward, strafe, jumping, sneaking, sprinting);
            if (navigationRelative) {
                context.input().applyNavigationMovement(movement, context.clientTick());
            } else {
                context.input().applyMovement(movement, context.clientTick());
            }
        }
    }
}
