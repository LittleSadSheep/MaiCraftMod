package org.maiwithu.maicraft.task;
import org.maiwithu.maicraft.task.TaskResult;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 一张“任务单”：记下要做什么、最晚何时做完、现在做到什么状态、最后结果如何。
 * 例如移动任务单还会记目的地，但它自己不会走路；{@link TaskFactory} 会找到负责走路的代码。
 *
 * <p>游戏运行时，这张单子由 Minecraft 客户端的主线程读写。网络请求也先交给这个线程处理，
 * 避免两边同时改任务状态；这个类自己没有加锁。
 * 这里的 t 开头短编号供内部使用，MCP 对外的总任务另有一个 UUID 编号。
 */
public abstract class TaskRecord {

    private static final AtomicLong ID_SOURCE = new AtomicLong();

    /**
     * 常驻任务的"期限":一个永远不会到的游戏刻。
     *
     * <p>期限回答的是"这件活该多久干完",而常驻任务<b>没有干完</b>——给它一个真实的
     * 期限就是给它安排一次注定的超时。用 {@code MAX_VALUE/2} 而不是 {@code MAX_VALUE}:
     * 被抢占时期限会 +1(见 {@code TaskSlot.freeze}),留出余量免得溢出成负数。
     */
    public static final long NO_DEADLINE = Long.MAX_VALUE / 2;

    private final long id;
    /** Stable name of the originating tool (matches {@code MaiCraftTool.name()}). */
    private final String toolName;
    /** 记住“是谁发起了这次调用”，任务结束时才能把结果送回正确的调用者。 */
    private final String toolCallId;
    /**
     * Game-tick (level.getGameTime()) at which this record times out. Stamped
     * at construction (gameTime is freeze-aware, so {@code /tick freeze} /
     * {@code /tick rate} are accounted for automatically); a goal whose real
     * budget depends on world state only known at start may push it later via
     * {@link #extendDeadlineTo} (e.g. move_to scales with journey distance —
     * the tool layer can't know that, it has no entity position).
     */
    private long deadlineGameTime;

    private TaskState state = TaskState.PENDING;
    private TaskResult result;
    /** 保留旧接口的“异步任务”标记；当前结果仍统一由 LocalToolDispatcher 送回。 */
    private boolean async;
    /** 首次进入 RUNNING 的游戏刻;task_status 用它报已耗时。-1 = 还没开跑。 */
    private long startedGameTime = -1;

    protected TaskRecord(String toolName, String toolCallId, long deadlineGameTime) {
        this.id = ID_SOURCE.incrementAndGet();
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.deadlineGameTime = deadlineGameTime;
    }

    public final long getId() { return id; }
    public final String getToolName() { return toolName; }
    public final String getToolCallId() { return toolCallId; }
    public final long getDeadlineGameTime() { return deadlineGameTime; }
    public final TaskState getState() { return state; }
    public final TaskResult getResult() { return result; }

    /** Push the deadline later (never earlier). Tick-thread only, like all reads. */
    public final void extendDeadlineTo(long gameTime) {
        if (gameTime > deadlineGameTime) deadlineGameTime = gameTime;
    }

    /** LLM 可见的短任务号——受理回执、current_task、task_finished 事件三处共用。 */
    public final String publicId() { return "t" + id; }

    public final void markAsync() { this.async = true; }
    public final boolean isAsync() { return async; }

    /** 用 mcp- 开头标记来自 MCP 的调用；任务开始、结束等对外消息由 IntentRuntime 发送。 */
    public static final String EXTERNAL_CALL_PREFIX = "mcp-";

    /** 只看调用编号是否以 mcp- 开头，不检查任务是否已开始操作玩家。 */
    public final boolean isExternalCall() {
        return toolCallId != null && toolCallId.startsWith(EXTERNAL_CALL_PREFIX);
    }

    /** 首次开跑打点(重复调用不覆盖——抢占恢复不算重新开始)。 */
    public final void markStarted(long gameTime) {
        if (startedGameTime < 0) startedGameTime = gameTime;
    }
    public final long getStartedGameTime() { return startedGameTime; }

    /**
     * 受理它的那一刻还没过去。
     *
     * <p>用来分开两种"再派一个活":同一批工具调用里的第二个(模型在做计划,该拒绝
     * ——让它拿到第一个的结果再决定),和新回合里派的(改主意了,该直接替换)。
     *
     * <p><b>问的是记录多老,不是任务跑了多少刻。</b>任务会休眠:{@code follow} 在主人
     * 身边时不占身体,槽轮不到 tick,"跑过几刻"就一直是 0——拿它当判据的话,一个跟了你
     * 十分钟的跟随任务会始终自称"刚受理",你让她顺手捡个掉落物都会被拒。
     */
    public final boolean acceptedThisTick(long gameTime) {
        return startedGameTime >= 0 && gameTime <= startedGameTime;
    }

    /** Called by {@code CompanionTickDispatcher} as the record transitions through lifecycle. */
    public final void setState(TaskState state) { this.state = state; }
    public final void setResult(TaskResult result) { this.result = result; }

    /**
     * Short human-readable description for the {@code /maicraft debug} head
     * overlay. Defaults to the tool name; subclasses override to append their
     * salient parameters (e.g. {@code MoveToTaskRecord} adds the target coords).
     */
    public String describe() {
        return toolName;
    }
}
