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

    /** 跟随等任务一直做到被叫停，不设实际超时；数值留出余量，方便暂停时继续往后加时间。 */
    public static final long NO_DEADLINE = Long.MAX_VALUE / 2;

    private final long id;
    /** 发起这件事的内部工具名，例如 goto；用于查状态和排错。 */
    private final String toolName;
    /** 记住“是谁发起了这次调用”，任务结束时才能把结果送回正确的调用者。 */
    private final String toolCallId;
    /**
     * 到这个游戏刻仍未完成，就算超时。这里用游戏里的时间，不是电脑上的秒表。
     * 开始时先给一个期限；发现路很远、仍在前进等情况时，任务可以把期限延后。
     */
    private long deadlineGameTime;

    private TaskState state = TaskState.PENDING;
    private TaskResult result;
    /** 保留旧接口的异步标记；当前语义结果由父任务读取，只有旧 ToolCall 调用才用 LocalToolDispatcher 回调。 */
    private boolean async;
    /** 首次进入 RUNNING 的游戏刻;task_status 用它报已耗时。-1 = 还没开跑。 */
    private long startedGameTime = -1;

    protected TaskRecord(String toolName, String toolCallId, long deadlineGameTime) {
        // 创建单子时只分配编号和保存参数，状态仍是 PENDING（等待开始），不会在这里执行任务。
        this.id = ID_SOURCE.incrementAndGet();
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.deadlineGameTime = deadlineGameTime;
    }

    /** 以下方法只读任务单上的字段；是否开始、结束由实际执行任务的代码决定。 */
    public final long getId() { return id; }
    public final String getToolName() { return toolName; }
    public final String getToolCallId() { return toolCallId; }
    public final long getDeadlineGameTime() { return deadlineGameTime; }
    public final TaskState getState() { return state; }
    public final TaskResult getResult() { return result; }

    /** 只允许把截止时间推后，较早的时间会被忽略；游戏中在客户端主线程调用。 */
    public final void extendDeadlineTo(long gameTime) {
        if (gameTime > deadlineGameTime) deadlineGameTime = gameTime;
    }

    /** 内部工具查任务时使用的 t 开头短编号；与 MCP 总任务的 UUID 是两套编号。 */
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
     * 判断是否还没过刚开始的那一刻，用来识别同一刻连续派来的任务。
     * 看的是开始时间，不是实际执行次数；例如跟随者一直站在主人旁边，也不能永远算“刚开始”。
     */
    public final boolean acceptedThisTick(long gameTime) {
        return startedGameTime >= 0 && gameTime <= startedGameTime;
    }

    /** 直接记下执行方给出的状态和结果；这里不校验“这个状态能否变成另一个状态”。 */
    public final void setState(TaskState state) { this.state = state; }
    public final void setResult(TaskResult result) { this.result = result; }

    /** 给状态显示和排错用的短描述；具体任务可以补上目的地等信息。 */
    public String describe() {
        return toolName;
    }
}
