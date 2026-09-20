package org.maiwithu.maicraft.core.task.base;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 具体任务共用的执行外壳：开始前检查条件，每个游戏刻做一点，结束时停导航并整理结果。
 * 挖矿、合成、建造等任务继承它，各自实现 onStart、onTick 和结果说明。
 *
 * <p>例如挖矿发现工具不合适，可以先用 fail 记下原因，再返回 FAILED 结束这次执行。
 * 是否换工具、去找材料或询问用户，由具体任务和它的上层决定，这个基类没有统一限制。
 *
 * <p>这里还提供 runChild 来推进小任务，但不会替调用者检查小任务超时或调用它的 result 收尾。
 * 总任务自身的超时也由上层检查；不能只调用这里的 tick 就以为所有事情都有人管了。
 *
 * @param <R> 这类任务对应的任务单，例如挖矿任务单记录要挖什么、挖多少。
 */
public abstract class AbstractCompanionTask<R extends TaskRecord>
        implements Task {

    /** 路还在正常往前走时，把剩余时间补到三十秒，长途移动不用仅因路远而超时。 */
    private static final long NAV_PROGRESS_LEASE_TICKS = 30L * 20L;
    private static final int NAV_PROGRESS_GRACE_TICKS = 100;

    /** 这件任务操作的游戏玩家。 */
    protected final LocalPlayer player;
    /** 要做什么、截止时间等都记在这张任务单上。 */
    protected final R r;
    /** 当前用来走路的导航；任务结束时要停掉，不能让它还在继续按前进。 */
    protected PlayerNav nav;
    /**
     * 本任务历次导航累计真动过的地形(每条导航停下时并入)。回执末尾如实相告——
     * 不论成败、不论任务,"路上挖了什么放了什么"只在这一处说一次。
     */
    private final TerrainBill journey =
            new TerrainBill();

    /** 这次任务失败的说明，也作为缺少专门结果时的回退消息。 */
    private String doneReason = "done";
    /** 最近失败的类型，供父任务判断应该补材料、换办法还是停止。 */
    private FailureType failType = FailureType.UNKNOWN;
    /** 开始前检查或 {@link #fail} 已确定终态时，下一次更新直接返回它，不再执行新动作。 */
    private TaskState pendingTerminal;

    // 记录当前交给子任务处理的步骤及其准备进度。
    /** 当前正在推进的子任务，例如施工前的取料；没有时为 {@code null}。 */
    private Task child;
    /** 记住子任务是否已经做过一次性准备，恢复时不重新开始。 */
    private boolean childStarted;

    protected AbstractCompanionTask(LocalPlayer player, R record) {
        this.player = player;
        this.r = record;
    }

    // ---------------------------------------------------------------------
    // 接单检查 → 分刻执行 → 收取结果并收尾。
    // ---------------------------------------------------------------------

    @Override
    public final void start(LocalPlayer companion) {
        // 开始前逐项检查；有一项不满足就记下原因，后面的检查和正式开始都不再执行。
        for (Precondition p : preconditions()) {
            Precondition.Failure f = p.check();
            if (f != null) {
                fail(f.message(), f.type());
                r.setState(TaskState.FAILED);   // 让上层当场知道失败，不必等下一刻再检查。
                return;
            }
        }
        try {
            onStart();
        } catch (RuntimeException e) {
            crashed("start", e);
            r.setState(TaskState.FAILED);
            return;
        }
        // 有些任务在准备阶段就能确定成功或失败，马上写回任务单。
        // 否则事情明明已经做成，此时收到取消却可能被说成“没做完”。
        if (pendingTerminal != null) {
            r.setState(pendingTerminal);
        }
    }

    @Override
    public final TaskState tick(LocalPlayer companion) {
        if (pendingTerminal != null) return pendingTerminal;
        // 还在等后台算路，就把截止时间推后一刻；路线已有实际进展，则给它继续走的时间。
        // 注意：上层会在调用这里之前检查超时，已经被上层判超时的任务无法在这里补时间。
        if (nav != null && nav.planningInFlight()) {
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
        } else if (nav != null && nav.hasRecentPhysicalProgress(NAV_PROGRESS_GRACE_TICKS)) {
            r.extendDeadlineTo(player.level().getGameTime() + NAV_PROGRESS_LEASE_TICKS);
        }
        try {
            return onTick();
        } catch (RuntimeException e) {
            crashed("tick", e);
            return TaskState.FAILED;
        }
    }

    /** 执行代码抛异常时，记日志并把这件任务标为内部错误；已经放下或挖掉的方块不会自动还原。 */
    private void crashed(String phase, RuntimeException e) {
        Constants.LOG.error(
                "[maicraft-task] {} 在 {} 阶段抛出异常,本任务判失败(服务端不受影响)",
                getClass().getSimpleName(), phase, e);
        fail("the task hit an internal error and stopped: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " — " + e.getMessage())
                + ". Anything already built stays; this is a bug worth reporting.",
                FailureType.INTERNAL);
    }

    @Override
    public final TaskResult result(TaskState finalState) {
        // 先停止导航等操作，再回答结果；停导航时会汇总路上真正挖过、放过的方块。
        cleanup();
        // 路上真动过的地形跟着每一种收场走:成功也好失败也罢,拆了什么就说什么
        String enRoute = journey.isEmpty() ? "" : " En route I had to " + journey.describe() + ".";
        Map<String, Object> data = new LinkedHashMap<>(resultData());
        if (finalState == TaskState.FAILED) {
            data.putIfAbsent("failure_type", failType.name().toLowerCase(Locale.ROOT));
        }
        return switch (finalState) {
            case SUCCESS   -> TaskResult.ok(successMessage() + enRoute, data);
            case TIMEOUT   -> new TaskResult(false, timeoutMessage() + enRoute, true, false, data);
            case CANCELLED -> new TaskResult(false, cancelledMessage() + enRoute, false, true, data);
            default        -> TaskResult.fail(doneReason + enRoute, data);   // 失败或其他未预期状态统一报告失败。
        };
    }

    // ---------------------------------------------------------------------
    // 具体任务提供自己的前置条件、动作和结果说明。
    // ---------------------------------------------------------------------

    /** 具体任务可以列出开始前的必需条件，例如目标是否存在；默认没有额外条件。 */
    protected List<Precondition> preconditions() {
        return List.of();
    }

    /** 第一次开始时做准备，例如记下原有物品数量；暂停后恢复不会自动再调一次。 */
    protected void onStart() {}

    /** 实际干活的位置：这次做一点，没做完返回 RUNNING，做完或做不了就返回结束状态。 */
    protected abstract TaskState onTick();

    /** 结束时做收尾，默认只停导航；开过菜单等任务还需要补上自己的清理。 */
    protected void cleanup() {
        stopNav();
    }

    /** 具体任务填写数量、失败原因等附加结果，默认没有附加信息。 */
    protected Map<String, Object> resultData() {
        return new HashMap<>();
    }

    /** 任务确实成功后向调用者说明完成了什么。 */
    protected abstract String successMessage();

    /** 任务超过期限时的说明，具体任务可补充当前停在哪一步。 */
    protected String timeoutMessage() {
        return "timed out";
    }

    /** 任务被取消时的说明，具体任务可补充已发生的部分效果。 */
    protected String cancelledMessage() {
        return "interrupted";
    }

    // ---------------------------------------------------------------------
    // 记录失败原因，供当前任务结束和父任务恢复使用。
    // ---------------------------------------------------------------------

    /** 记下失败原因和类型，并让下一次更新停止执行；在 onTick 中发现失败时也应返回 FAILED。 */
    protected void fail(String why, FailureType t) {
        // 终局必须留声:任务凭什么收场是排障的第一现场,不能只活在返回值里
        Constants.LOG.info("[maicraft-task] {} FAILED({}) {}",
                getClass().getSimpleName(), t, why);
        this.doneReason = why;
        this.failType = t;
        this.pendingTerminal = TaskState.FAILED;
    }

    /** 丢物、装备等任务可能在准备阶段完成；立即记录成功，避免同刻的取消把已完成动作误报为未完成。 */
    protected void succeed() {
        this.pendingTerminal = TaskState.SUCCESS;
    }

    /** 返回最近失败的类型；还没有具体原因时为 {@link FailureType#UNKNOWN}。 */
    protected FailureType lastFailure() {
        return failType;
    }

    /** 返回最近一次 {@link #fail} 留下的失败说明。 */
    protected String doneReason() {
        return doneReason;
    }

    // ---------------------------------------------------------------------
    // 本任务的导航与沿途地形改动记录。
    // ---------------------------------------------------------------------

    /** 记下路上改过的地形，再停止并移走导航；重复调用不会重复记账。 */
    protected void stopNav() {
        PlayerNav active = nav;
        nav = null;
        if (active == null) return;
        try {
            journey.addAll(active.ledger());
        } finally {
            // 哪怕“记下改了哪些地形”这一步出错，也必须停止走路和后台算路。
            active.stop();
        }
    }

    // ---------------------------------------------------------------------
    // 推进当前子任务；其停止和结果收尾仍由调用方负责。
    // ---------------------------------------------------------------------

    /**
     * 让总任务里的一个小任务先做一步，例如建造任务先让“取材料”执行。
     * 第一次传入它时调用 start 做准备，以后传同一个对象就接着 tick。
     * 换成另一个对象时，这里直接替换引用，不会帮忙停止旧对象；调用者必须先处理好旧任务。
     *
     * @return 没做完返回 null；做完返回它的结束状态。调用者仍须调用子任务的 result 收尾。
     */
    protected TaskState runChild(Task c) {
        if (child != c) {
            child = c;
            childStarted = false;
        }
        if (!childStarted) {
            child.start(player);
            childStarted = true;
        }
        TaskState st = child.tick(player);
        if (st.isTerminal()) {
            if (child instanceof AbstractCompanionTask<?> a) {
                this.failType = a.lastFailure();
            }
            child = null;
            childStarted = false;
            return st;
        }
        return null;   // 子任务还没有结束。
    }

    // ---------------------------------------------------------------------
    // 身体被更高优先级的任务抢占时暂停输出。
    // ---------------------------------------------------------------------

    /** 暂停时先松开按键，记住任务做到哪；永久结束时还要由 result 调用 cleanup 做完整收尾。 */
    @Override
    public void stop(LocalPlayer companion, StopReason why) {
        // 暂停走路，但不删掉任务进度；这里没有把 stop 自动转发给 runChild 保存的小任务。
        if (nav != null) {
            // 保留已计算的路线，但必须在新任务开始前释放 Baritone 的实际输出和挖掘动作。
            // 若拖到本刻最后再停旧挖掘，可能误停新任务刚刚开始的动作。
            nav.pause();
        }
        InputDriver.halt(player);
        // MCP 取消可能发生在两次身体更新之间，halt 已撤销该玩家的导航输入授权。
        ClientRuntime.actor().activeContext().filter(context -> context.player() == player)
                .ifPresent(context -> context.body().releaseAll());
    }

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public Map<String, Object> progress() {
        return child == null ? Map.of("task", name())
                : Map.of("task", name(), "child", child.progress());
    }
}
