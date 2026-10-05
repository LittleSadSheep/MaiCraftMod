// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chain;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.reflex.Reflex;

/**
 * 任务释放身体后的两类险境姿势处置：贴着深落差边缘时潜行退到安全位置；
 * 身体被围困在实心方块里持续窒息时，原生挖开窒息方块恢复呼吸（005 局
 * interact 地形准备把身体围在 stone 里的实机形态）。
 * 失败、取消都可能把身体留在中途位置；零输入挡不住残余动量，紧贴边缘的身体
 * 会在无人接管窗口滑落。窒息逃逸只挖致窒的那一格，不替调用方规划脱困路线。
 * 在岗任务持有身体时不参与抢占：贴边站位可以是任务的正当姿态（建筑贴墙、
 * 钓鱼池边、寻路贴崖），抢占会把锚点对齐和寻路按秒打断。
 */
public final class SettleChain implements Task, Reflex {
    /** 邻格向下扫满这个深度仍无支撑，才算深落差；更浅的台阶不值得抢占身体。 */
    private static final int DROP_SCAN = 4;
    /** 站位到边缘线不足这个距离视为贴边（一格宽的脚位从中心到边是 0.5）。 */
    private static final double EDGE_MARGIN = 0.35;
    private static final double DRIFT_SPEED = 0.1;
    private static final double DRIFT_ALIGNMENT = 0.5;
    private static final int MAX_TICKS = 40;
    /** 窒息方块按空手挖石头约数十刻；预算给足整次逃逸，超时如实报告未清。 */
    private static final int SUFFOCATION_MAX_TICKS = 600;

    private boolean active;
    private int ticks;
    private NativeActionReceipt breakReceipt;
    private BlockPos suffocating;

    @Override
    public boolean canRun(LocalPlayer companion) {
        if (WorkProfile.of(companion).fearless()) return false;
        // 窒息优先：头在窒息方块里每刻掉血，不要求落地或贴边（005 局围困形态）。
        if (companion.isInWall()) return true;
        // 空中、水中、攀爬与乘坐都有各自的稳定机制；这里只处理"站在地上但贴着深渊"的姿势。
        if (!companion.onGround()) return false;
        Direction edge = edgeBeside(companion);
        return edge != null && nearEdgeOrDrifting(companion, edge);
    }

    /** 只服务释放窗口：在岗任务的贴边站位由任务自己负责，见类注释。 */
    @Override
    public boolean onlyWhenBodyReleased() {
        return true;
    }

    @Override
    public TaskState tick(LocalPlayer companion) {
        if (companion.isInWall()) return escapeSuffocation(companion);
        if (!active) {
            active = true;
            ticks = 0;
            GameplayAttentionMonitor.reflexStarted(id(),
                    "the body was released beside a deep drop", "sneak back from the edge to a settled stance",
                    "no items used", "fall damage if the body slides off before settling");
        }
        Direction edge = edgeBeside(companion);
        if (edge == null || !nearEdgeOrDrifting(companion, edge)) return finish(companion, "stance settled away from the edge");
        if (++ticks > MAX_TICKS) return finish(companion, "could not reach a settled stance within the bounded attempt; position stays unverified");
        Vec3 feet = Vec3.atCenterOf(companion.blockPosition());
        // 目标取脚位格里背离边缘的一侧：潜行慢走半格就把重心移离边缘线，也不会跨出当前格。
        Vec3 away = feet.subtract(Vec3.atLowerCornerOf(edge.getNormal()));
        Vec3 delta = away.subtract(companion.getEyePosition());
        float yaw = (float) (Math.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0f;
        InputDriver.look(companion, yaw, 0.0f);
        InputDriver.applyMovement(companion, 0.6f, 0.0f, false, true, false);
        return TaskState.RUNNING;
    }

    /** 挖开致窒方块的原生逃逸：只动眼位那一格，窒息解除即收尾，脱困路线仍归调用方。 */
    private TaskState escapeSuffocation(LocalPlayer companion) {
        if (!active) {
            active = true;
            ticks = 0;
            GameplayAttentionMonitor.reflexStarted(id(),
                    "the body was released inside a solid block and is suffocating",
                    "natively break the suffocating block and re-check",
                    "native digging limited to the suffocating block", "suffocation damage until the block clears");
        }
        if (++ticks > SUFFOCATION_MAX_TICKS) {
            return finish(companion, "could not clear the suffocating block within the bounded attempt; position stays unverified");
        }
        if (!companion.isInWall()) {
            return finish(companion, "suffocating block cleared; the body can breathe again, escape route is still up to the caller");
        }
        LocalPlayerContext context = ClientRuntime.requireContext(companion);
        BlockPos eye = BlockPos.containing(companion.getEyePosition());
        if (suffocating == null || !suffocating.equals(eye)) {
            settleBreak(companion);
            if (EmbeddedBaritonePolicy.protects(eye)) {
                return finish(companion, "the suffocating block is protected; clearing it is not authorized for this reflex");
            }
            BlockState state = companion.level().getBlockState(eye);
            float progress = state.getDestroyProgress(companion, companion.level(), eye);
            if (!(progress > 0) || !Float.isFinite(progress)) {
                return finish(companion, "the suffocating block cannot be mined natively");
            }
            if (!context.mutationAvailable()) return TaskState.RUNNING;
            suffocating = eye.immutable();
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(suffocating), Direction.UP, suffocating, false);
            breakReceipt = context.actions().startBreaking(context, hit,
                    (int) Math.clamp(Math.ceil(1D / progress) + 40, 20, 1200));
            return TaskState.RUNNING;
        }
        if (breakReceipt != null && !breakReceipt.terminal()) {
            breakReceipt = context.actions().poll(context, breakReceipt);
            if (!breakReceipt.terminal() && context.mutationAvailable()) {
                breakReceipt = context.actions().continueBreaking(context, breakReceipt);
            }
            return TaskState.RUNNING;
        }
        // 回执已终态但窒息仍在：放弃本次挖掘判定，下一刻重新选块再试。
        breakReceipt = null;
        suffocating = null;
        return TaskState.RUNNING;
    }

    /** 结清未完结的原生挖掘，避免逃逸收尾时把悬着的破坏动作带进下一段任务。 */
    private void settleBreak(LocalPlayer companion) {
        if (breakReceipt == null) return;
        ClientRuntime.actor().activeContext().filter(c -> c.player() == companion && c.isCurrent())
                .ifPresent(context -> {
                    if (!breakReceipt.terminal()) {
                        breakReceipt = context.actions().cancelBreaking(context, breakReceipt);
                    }
                });
        breakReceipt = null;
        suffocating = null;
    }

    private TaskState finish(LocalPlayer companion, String outcome) {
        // 两个分支共用收尾：窒息解除可能让同一刻落回边缘分支，未结清的挖掘回执在这里统一收掉。
        settleBreak(companion);
        InputDriver.halt(companion);
        GameplayAttentionMonitor.reflexFinished(id(), outcome, ticks,
                "no placement or recovery", "no health change attributable to this reflex");
        active = false;
        ticks = 0;
        return TaskState.RUNNING;
    }

    @Override
    public void stop(LocalPlayer companion, StopReason why) {
        try {
            ClientRuntime.actor().activeContext().filter(c -> c.player() == companion && c.isCurrent())
                    .ifPresent(context -> {
                        if (breakReceipt != null && !breakReceipt.terminal()) {
                            breakReceipt = context.actions().cancelBreaking(context, breakReceipt);
                        }
                        context.body().releaseAll();
                    });
        } finally {
            breakReceipt = null;
            suffocating = null;
            if (active) {
                GameplayAttentionMonitor.reflexFinished(id(),
                        why == StopReason.BODY_GONE ? "body unavailable" : "interrupted before settling",
                        ticks, "no placement or recovery", "outcome unconfirmed");
                active = false;
                ticks = 0;
            }
        }
    }

    /** 找脚位四周的第一个深落差方向；四个水平方向按常量顺序扫描，结果稳定。 */
    private static Direction edgeBeside(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (dropDepth(player.level(), feet.relative(direction)) >= DROP_SCAN) return direction;
        }
        return null;
    }

    /** 从邻格向下找第一个有碰撞形状的方块；流体没有碰撞，落进去仍会继续坠，按无支撑处理。 */
    private static int dropDepth(Level level, BlockPos cell) {
        for (int depth = 1; depth <= DROP_SCAN; depth++) {
            BlockPos probe = cell.below(depth);
            BlockState state = level.getBlockState(probe);
            if (!state.getCollisionShape(level, probe).isEmpty()) return depth;
        }
        return DROP_SCAN;
    }

    /** 贴边（重心离边缘线不足一格的三分之一）或残余动量仍指向边缘时，都需要退避。 */
    private static boolean nearEdgeOrDrifting(LocalPlayer player, Direction edge) {
        Vec3 dir = Vec3.atLowerCornerOf(edge.getNormal());
        Vec3 center = Vec3.atCenterOf(player.blockPosition());
        double along = player.position().subtract(center).dot(dir);
        if (0.5 - along < EDGE_MARGIN) return true;
        Vec3 drift = player.getDeltaMovement();
        double speed = Math.sqrt(drift.x * drift.x + drift.z * drift.z);
        return speed > DRIFT_SPEED && (drift.x * dir.x + drift.z * dir.z) / speed > DRIFT_ALIGNMENT;
    }

    @Override public String name() { return "settle"; }
    @Override public String id() { return name(); }
    @Override public String describeCurrentAction() {
        return active && suffocating != null ? "身体被围困窒息，正在挖开致窒方块" : "贴着深落差边缘，正在潜行退到安全位置";
    }
    @Override public String describe() {
        return "任务释放身体后的险境处置：贴着深落差边缘时潜行退到离边安全位置（不用物品）；"
                + "身体被围困窒息时原生挖开致窒的那一格恢复呼吸，脱困路线仍归调用方；坠落中的保护归防摔链";
    }
}
