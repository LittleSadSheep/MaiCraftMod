// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chain;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.reflex.Reflex;

/**
 * 任务释放身体后仍站在深落差边缘时，潜行退到离边安全的位置再交还控制。
 * 失败、取消都可能把身体留在中途位置；零输入挡不住残余动量，紧贴边缘的身体
 * 会在无人接管窗口滑落。这里不用任何物品，坠落中的保护仍归 MLGChain。
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

    private boolean active;
    private int ticks;

    @Override
    public boolean canRun(LocalPlayer companion) {
        // 空中、水中、攀爬与乘坐都有各自的稳定机制；这里只处理"站在地上但贴着深渊"的姿势。
        if (!companion.onGround() || WorkProfile.of(companion).fearless()) return false;
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

    private TaskState finish(LocalPlayer companion, String outcome) {
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
                    .ifPresent(context -> context.body().releaseAll());
        } finally {
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
    @Override public String describeCurrentAction() { return "贴着深落差边缘，正在潜行退到安全位置"; }
    @Override public String describe() {
        return "任务释放身体后仍贴着深落差边缘时，潜行退到离边安全的位置再交还控制，不使用任何物品；坠落中的保护归防摔链";
    }
}
