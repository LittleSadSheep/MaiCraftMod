package org.maiwithu.maicraft.core.task.base;

import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.Constants;

/**
 * 把“先走近，再执行”组织成公共流程。
 * 子类分别定义导航目标、实际能否操作和操作本身；导航到了与手真的够得着是两个检查。
 */
public abstract class GoToThenDoTask<R extends TaskRecord> extends AbstractCompanionTask<R> {

    protected GoToThenDoTask(LocalPlayer player, R record) {
        super(player, record);
    }

    /** 创建前往本任务目标的导航，并在任务开始时赋给 {@link #nav}。
     *  方块目标的动作任务返回 null——它们不再自带任何到场导航,身体必须已在
     *  工作距离内({@link #reached()}),否则直接教学失败让调用方先 goto。 */
    protected abstract PlayerNav buildNav();

    /**
     * 教学失败要点名的目标格(算距离、给 goto 坐标用)。返回 null = 无固定
     * 格目标(实体目标、原地动作),失败话术退化为通用文案。默认 null。
     */
    protected BlockPos gotoFirstTarget() {
        return null;
    }

    /** 本 tick 是否已到达 {@link #act()} 的交互距离？ */
    protected abstract boolean reached();

    /**
     * 身体稳没稳——动作前置里"站得住"那一半的唯一判据。不等于"站在地上":
     * 游在水面(舀水、放船正是这个姿势)和坐在载具里都没有 onGround,原版对
     * 交互也从不要求脚踏实地;这道门挡的只是坠落中途的按键。
     */
    // 这里的 settled 只表示落地、在水里或乘坐，不表示速度一定为零。
    protected final boolean bodySettled() {
        return player.onGround() || player.isInWater() || player.isPassenger();
    }

    /** 在目标处执行有界动作；返回 {@link TaskState#RUNNING} 或终态。 */
    protected abstract TaskState act();

    @Override
    protected void onStart() {
        nav = buildNav();
    }

    /** 导航连续报告 ARRIVED，但 {@link #reached()} 仍为 false 的游戏刻数。 */
    private int dudTicks = 0;
    /** 判定到达但站位无效前的宽限时间；落地、稳定和 onGround 状态可能比目标成员关系晚几刻更新。 */
    private static final int DUD_GRACE_TICKS = 10;

    @Override
    // 具体任务说现在能干活，就直接执行 act；否则推进导航。没有导航且还够不到时，只报告需要先走近。
    protected final TaskState onTick() {
        if (reached()) return act();
        if (nav == null) {
            // 无到场导航的动作任务:不在工作距离内 = 教学失败,旅行归 goto
            BlockPos t = gotoFirstTarget();
            if (t != null) {
                double dist = Math.sqrt(player.distanceToSqr(
                        t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5));
                fail("target " + t.getX() + "," + t.getY() + "," + t.getZ() + " is "
                        + String.format("%.1f", dist) + " blocks away — out of working reach."
                        + " goto it first (goto stops right beside a solid block), then call"
                        + " this again.", FailureType.OUT_OF_REACH);
            } else {
                fail("out of working reach and this action does not travel — goto the spot"
                        + " first, then call this again.", FailureType.OUT_OF_REACH);
            }
            return TaskState.FAILED;
        }
        return switch (nav.tick()) {
            case RUNNING -> {
                dudTicks = 0;
                yield TaskState.RUNNING;
            }
            // 导航到终点而实际操作条件还没满足时，先等十次更新，再报告站位不合用，交给子类选择是否换位。
            case ARRIVED -> {
                // 上方的 reached() 检查未通过，因此导航到达可能是无效站位：搜索目标成员关系虽已满足，
                // 但当前仍无法开始操作（超出交互距离或没有视线）。将其交给与寻路失败相同的恢复阶梯；
                // 这只是有界目标未能提供可用站位的另一种情况，宽限期用于吸收落地稳定过程中的短暂状态。
                if (++dudTicks < DUD_GRACE_TICKS) {
                    yield TaskState.RUNNING;
                }
                dudTicks = 0;
                stopNav();
                Constants.LOG.info(
                        "[maicraft-task] STANCE_DUD {} feet={} — nav arrived, reached() still"
                                + " false after {} ticks; routing the recovery ladder",
                        getClass().getSimpleName(), player.blockPosition().toShortString(),
                        DUD_GRACE_TICKS);
                yield handleNavFailure(FailureType.STANCE_DUD,
                        "arrived where the route ends, but the target is still out of"
                                + " reach from there");
            }
            case FAILED -> handleNavFailure(nav.failType(), nav.failReason());
        };
    }

    /**
     * 处理导航放弃的情况。默认调用 {@code fail(reason, type)} 并以 FAILED 终止。
     * 子类可插入 {@link RecoveryLadder}，在放弃前为同一有界目标提供另一种接近方式。
     */
    protected TaskState handleNavFailure(FailureType type, String reason) {
        fail(reason, type);
        return TaskState.FAILED;
    }

    @Override
    protected abstract String successMessage();
}
