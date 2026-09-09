package org.maiwithu.maicraft.task;

/**
 * 任务单上记的进度：等待开始 → 正在执行 → 成功、失败、超时或取消。
 * 查询任务时可以看到中间状态；只有后四种表示这次执行已经结束。
 * 暂停另有标记，不在这个枚举中，所以 RUNNING 不一定代表玩家此刻正在动作。
 */
public enum TaskState {
    PENDING,   // 已建好任务单，还未开始。
    RUNNING,   // 已开始，但还没有结束结果。
    SUCCESS,   // 已按任务的完成条件确认成功。
    FAILED,    // 做不下去，具体原因看 TaskResult。
    TIMEOUT,   // 已超过允许的执行时间。
    CANCELLED; // 被主动叫停、替换，或失去了原来的玩家对象。

    public boolean isTerminal() {
        // 已结束的任务不能继续做动作，执行方应开始收尾并报告结果。
        return this != PENDING && this != RUNNING;
    }
}
