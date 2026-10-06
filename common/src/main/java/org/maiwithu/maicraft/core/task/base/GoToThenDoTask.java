package org.maiwithu.maicraft.core.task.base;

import java.util.HashMap;
import java.util.Map;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.task.ProgressBudget;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.Constants;

/**
 * 把“先走近，再执行”组织成公共流程。
 * 子类分别定义导航目标、实际能否操作和操作本身；导航到了与手真的够得着是两个检查。
 * 接近阶段与移动任务共用同一套有界规划：算路停滞必须给出失败终态，不能让消费类任务无限 running。
 */
public abstract class GoToThenDoTask<R extends TaskRecord> extends AbstractCompanionTask<R> {

    /** 接近规划连续无确认进展的上限；与移动任务的同名预算同一口径（30 活动秒）。 */
    private static final long PLANNING_IDLE_TICKS = 30 * 20;
    /** 接近规划的搜索工作量熔断线：持续有产出却永不接近目标时按病态搜索收场。 */
    private static final long PLANNING_WORK_FUSE_UNITS = 60_000;
    /** 距离最后一次接近目标超过这么长的活动 tick（且工作量已越熔断线）判定规划无收敛。 */
    private static final long CONVERGENCE_WINDOW_TICKS = 30 * 20;
    /** 接近段静默窗的宽上限：只计“身体没有真实位移、导航也没给终态”的连续时间（楔死形态）；
     *  走远路时每次真实位移都重新起表，被反射或同步任务挂起的时间也不计入。 */
    private static final long APPROACH_PHASE_LIMIT_TICKS = 5 * 60 * 20;
    /** 判定“近期有真实位移”的回看窗口（刻）。 */
    private static final int APPROACH_MOVEMENT_GRACE_TICKS = 40;

    private ProgressBudget planningBudget;
    private long planningWorkHighWater;
    private long lastApproachTick = Long.MIN_VALUE;
    private double bestApproachDistance = Double.MAX_VALUE;
    /** 上一次推进接近段的游戏刻；相隔超过一秒说明任务被挂起过，恢复后静默窗重新起表。 */
    private long lastApproachTickSeen = Long.MIN_VALUE;
    /** 出发时（或路线重估后）的量化剩余距离分母；口径见 {@link #progress()}。 */
    private int initialRemaining = -1;

    protected GoToThenDoTask(LocalPlayer player, R record) {
        super(player, record);
    }

    /** 创建前往本任务目标的导航，并在任务开始时赋给 {@link #nav}。
     *  方块目标的动作任务返回 null——它们不再自带任何到场导航,身体必须已在
     *  工作距离内({@link #reached()}),否则直接教学失败让调用方先 goto。 */
    protected abstract PlayerNav buildNav();

    /** 面板行动行的一句话汇报；目的地来自子类声明的第一导航目标，无固定目标则无汇报。 */
    @Override
    public String describeCurrentAction() {
        BlockPos target = gotoFirstTarget();
        return target == null ? null
                : "正在前往 (" + target.getX() + "," + target.getY() + "," + target.getZ() + ")";
    }

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
        if (reached()) {
            planningPhaseEnd();
            return act();
        }
        // 接近段静默窗起表：身体近期有真实位移、或任务刚从挂起（反射接管、吃饭、同步任务）恢复时重新计时，
        // 只有既不挪窝也不给终态的楔死接近才会累计到上限——正常走远路不会因为总时长被判卡死。
        if (nav != null) {
            long now = player.level().getGameTime();
            boolean resumed = lastApproachTickSeen != Long.MIN_VALUE && now - lastApproachTickSeen > 20;
            lastApproachTickSeen = now;
            if (!planningPhaseActive() || resumed || nav.hasRecentPhysicalProgress(APPROACH_MOVEMENT_GRACE_TICKS)) {
                planningPhaseBegin("approach", APPROACH_PHASE_LIMIT_TICKS);
            }
        }
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
        TaskState bounded = boundedApproachPlanning();
        if (bounded != null) return bounded;
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

    /**
     * 接近阶段的有界规划守卫：算路连续无确认进展超预算、或搜索工作量越熔断线而最近距离
     * 长时间无改善时，按 planning_stall 终态收场并交还身体——容器使用这类消费任务的接近
     * 卡死不再把整条后勤链拖进无限 running。返回 null 表示继续正常推进。
     */
    private TaskState boundedApproachPlanning() {
        if (planningBudget == null) planningBudget = r.progressBudget(PLANNING_IDLE_TICKS);
        long now = player.level().getGameTime();
        // 宽上限先于规划在飞判定：导航停在既不规划也不终态的行走假运行时，idle 预算没有
        // lastVerifiedProgressTick 可依，只有这张总表能把接近段收进有界终态。
        if (planningPhaseExceeded()) {
            fail("approach made no physical progress for about " + planningPhaseSeconds()
                    + " seconds; the body neither moved nor reached working distance, and navigation produced"
                    + " no path verdict; " + nav.outcomeSummary()
                    + " Travel closer with goto and resubmit, or inspect the approach first.",
                    FailureType.PLANNING_STALL);
            return TaskState.FAILED;
        }
        boolean stalled = planningBudget.observeCounter(now, nav.lastVerifiedProgressTick());
        double distance = approachDistance();
        if (distance < bestApproachDistance - 0.1) {
            bestApproachDistance = distance;
            lastApproachTick = now;
        }
        planningWorkHighWater = Math.max(planningWorkHighWater, nav.planningProgressUnits());
        if (!nav.planningInFlight()) return null;
        if (stalled) {
            fail("approach planning made no verified progress for about "
                    + PLANNING_IDLE_TICKS / 20 + " active seconds; no no-path conclusion was"
                    + " established; " + nav.outcomeSummary()
                    + " Travel closer with goto and resubmit, or inspect the approach first.",
                    FailureType.PLANNING_STALL);
            return TaskState.FAILED;
        }
        if (planningWorkHighWater > PLANNING_WORK_FUSE_UNITS
                && lastApproachTick != Long.MIN_VALUE
                && now - lastApproachTick >= CONVERGENCE_WINDOW_TICKS) {
            fail("approach planning did not converge: the search banked " + planningWorkHighWater
                    + " work units while the closest approach stayed "
                    + String.format("%.1f", bestApproachDistance) + " blocks from the target for about "
                    + (CONVERGENCE_WINDOW_TICKS / 20) + " seconds with no improvement; "
                    + nav.outcomeSummary()
                    + " Travel closer with goto and resubmit, or inspect the approach first.",
                    FailureType.PLANNING_STALL);
            return TaskState.FAILED;
        }
        return null;
    }

    /** 到第一个接近目标的直线距离；无固定格目标（实体目标）时记零，熔断的那一半随之不启用。 */
    private double approachDistance() {
        BlockPos target = gotoFirstTarget();
        return target == null ? 0 : Math.sqrt(player.distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
    }

    /**
     * 接近阶段的记分牌：phase 区分 planning/moving/acting；接近中报 remaining/initial（16 格
     * 量化档，向上取整——剩余 0 只出现在真实到达），规划期另报 calc 搜索尝试次数当心跳，
     * 让「还在算」的停滞每过事件地板间隔仍有一条进度可读。进入动作阶段后距离失去意义，只报阶段名。
     */
    @Override
    public Map<String, Object> progress() {
        if (nav == null) return super.progress();
        boolean planning = nav.planningInFlight();
        Map<String, Object> result = new HashMap<>();
        result.put("task", name());
        result.put("phase", reached() ? "acting" : planning ? "planning" : "moving");
        if (!reached() && gotoFirstTarget() != null) {
            int remaining = AbstractCompanionTask.quantizedRemaining(approachDistance());
            if (remaining > initialRemaining) initialRemaining = remaining;
            result.put("remaining", remaining);
            result.put("initial", initialRemaining);
        }
        if (planning) {
            result.put("done", nav.planningProgressUnits());
            result.put("calc", nav.planningCalcAttempts());
        }
        // 接近静默窗的心跳：身体近期没有真实位移（既不规划在飞也不挪窝的楔死形态）时报
        // 已接近秒数——单调增长让门卫按地板间隔持续发布，健康步行不报、不加事件噪音。
        if (!planning && !nav.hasRecentPhysicalProgress(APPROACH_MOVEMENT_GRACE_TICKS)) {
            result.put("planning_seconds", planningPhaseActive() ? planningPhaseSeconds() : 0);
        }
        return Map.copyOf(result);
    }

    @Override
    protected abstract String successMessage();
}
