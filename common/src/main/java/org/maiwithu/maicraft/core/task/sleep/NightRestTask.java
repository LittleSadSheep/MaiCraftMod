// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.sleep;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 记住出发工作点，步行到床、原生入睡、等自然醒，再沿正常导航返回；不修改时间、生命或重生数据。 */
public final class NightRestTask extends AbstractCompanionTask<NightRestTask.Record> {
    public static final class Record extends TaskRecord {
        final BlockPos origin, bed;
        public Record(String id, long deadline, BlockPos origin, BlockPos bed) {
            super("night_rest", id, deadline); this.origin = origin.immutable(); this.bed = bed.immutable();
        }
    }
    private SleepCompanionTask sleep;
    private boolean returning, rested;
    private String sleepFailure;
    public NightRestTask(LocalPlayer player, Record record) { super(player, record); }
    @Override protected TaskState onTick() {
        if (sleep != null) {
            var state = runChild(sleep); if (state == null) return TaskState.RUNNING;
            var result = sleep.result(state); sleep = null;
            if (state != TaskState.SUCCESS || !result.success()) {
                // 睡眠未完成也先沿正常路线回原工位；不能把角色留在别人屋里，让原建造从错误位置继续。
                sleepFailure = result.message(); returning = true; return TaskState.RUNNING;
            }
            rested = true; returning = true; return TaskState.RUNNING;
        }
        if (returning) {
            if (player.onGround() && player.blockPosition().distSqr(r.origin) <= 4) {
                if (sleepFailure != null) { fail(sleepFailure, FailureType.UNKNOWN); return TaskState.FAILED; }
                return TaskState.SUCCESS;
            }
            if (nav == null) nav = PlayerNav.to(player, () -> GoalCompiler.near(r.origin, 1.5), .8,
                    () -> player.onGround() && player.blockPosition().distSqr(r.origin) <= 4).walkingOnly();
        } else {
            if (!WorldTimeSemantics.canAttemptSleep(player.level())) { returning = true; stopNav(); return TaskState.RUNNING; }
            if (!player.level().isLoaded(r.bed) || !(player.level().getBlockState(r.bed).getBlock() instanceof BedBlock)
                    || !BedBlock.canSetSpawn(player.level()) || player.level().getBlockState(r.bed).getValue(BedBlock.OCCUPIED)) {
                fail("the observed nearby bed is no longer usable", FailureType.TARGET_LOST); return TaskState.FAILED;
            }
            if (canReachBed()) {
                stopNav(); sleep = new SleepCompanionTask(player,
                        new SleepTaskRecord(r.getToolCallId() + "/sleep", r.getDeadlineGameTime(), r.bed).untilAwake());
                return TaskState.RUNNING;
            }
            if (nav == null) nav = PlayerNav.to(player, () -> GoalCompiler.interact(r.bed), .8,
                    this::canReachBed).walkingOnly();
        }
        if (nav.tick() == PlayerNav.Status.FAILED) { fail(nav.failReason(), nav.failType()); return TaskState.FAILED; }
        return TaskState.RUNNING;
    }
    private boolean canReachBed() {
        // 房屋外距床几格并不代表能用；墙挡住视线时继续沿门口寻路，不能隔墙尝试一次后放弃整张床。
        if (!player.onGround()) return false;
        // 入睡距离按原版服务器判定走：床头或床尾方块底面中心满足 |dx|<=3、|dy|<=2、|dz|<=3。
        // 只看视线距离会放行 3.5-4 格外的一步式尝试，被服务器以"床离得太远"拒绝后整段休息失败。
        if (!vanillaReachable(r.bed)) {
            var state = player.level().getBlockState(r.bed);
            BlockPos foot = state.getBlock() instanceof BedBlock
                    ? r.bed.relative(state.getValue(BedBlock.FACING).getOpposite()) : r.bed;
            if (!vanillaReachable(foot)) return false;
        }
        // 原版距离盒的对角上角满足入睡判定但眼距仍超过交互半径（067 实机：闸口放行后
        // 入睡子任务零动作即以"床太远"放弃）。接近闸口与入睡闸口同判交互半径，
        // 超出就继续寻路走近，不能把一步式失败交给子任务。
        if (player.getEyePosition().distanceTo(Vec3.atCenterOf(r.bed)) > SleepCompanionTask.INTERACTION_REACH) return false;
        var hit = player.level().clip(new ClipContext(player.getEyePosition(), Vec3.atCenterOf(r.bed),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().distManhattan(r.bed) <= 1
                && player.level().getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock;
    }

    /** 原版 ServerPlayer.isReachableBedBlock 的客户端镜像：床底面中心按轴差判定，任一半床够到即可。 */
    static boolean vanillaReachable(double x, double y, double z, BlockPos part) {
        return Math.abs(x - (part.getX() + 0.5)) <= 3.0
                && Math.abs(y - part.getY()) <= 2.0
                && Math.abs(z - (part.getZ() + 0.5)) <= 3.0;
    }

    private boolean vanillaReachable(BlockPos part) {
        return vanillaReachable(player.getX(), player.getY(), player.getZ(), part);
    }
    @Override public void stop(LocalPlayer player, Task.StopReason reason) {
        if (sleep != null) sleep.stop(player, reason); super.stop(player, reason);
    }
    @Override protected void cleanup() {
        if (sleep != null) { sleep.stop(player, StopReason.REPLACED); sleep.result(TaskState.CANCELLED); sleep = null; }
        super.cleanup();
    }
    @Override protected Map<String, Object> resultData() { return Map.of("slept_until_morning", rested, "returned_to_work_site", returning && player.blockPosition().distSqr(r.origin) <= 4); }
    @Override protected String successMessage() { return "night rest settled and the original work position was reached"; }

    /** 面板行动行的一句话汇报；阶段来自在飞的睡眠子任务与往返状态，床的坐标是任务单已确认事实。 */
    @Override
    public String describeCurrentAction() {
        if (sleep != null) return "正在入睡";
        if (returning) return "正在返回工作点";
        return "正在前往床 (" + r.bed.getX() + "," + r.bed.getY() + "," + r.bed.getZ() + ")";
    }
}
