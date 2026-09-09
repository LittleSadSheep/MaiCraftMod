package org.maiwithu.maicraft.core.task.fish;

import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存钓鱼的请求次数和当前进度，分别统计抛过几竿、确认收获几次。
 * 这里不读取咬钩或背包，实际观察与计数由 FishCompanionTask 完成。
 */
public final class FishTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "fish";

    /** 要完成几次收获；0 不按次数结束，但仍可能因为失败、超时设置或用户取消而停止。 */
    public final int requested;

    private int caught;
    private int casts;

    public FishTaskRecord(String toolCallId, long deadlineGameTime, int requested) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.requested = requested;
    }

    public int caught() {
        return caught;
    }

    public int casts() {
        return casts;
    }

    public void caughtOne() {
        caught++;
    }

    public void castOnce() {
        casts++;
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return requested > 0
                ? "钓鱼 " + caught + "/" + requested
                : "钓鱼,已钓到 " + caught + " 条";
    }
}
