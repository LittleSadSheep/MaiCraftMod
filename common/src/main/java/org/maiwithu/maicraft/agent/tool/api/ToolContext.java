package org.maiwithu.maicraft.agent.tool.api;

/**
 * 创建任务单时需要知道：原请求的编号是什么、发起时游戏走到了哪一刻。
 * 例如当前是第 100 刻、允许再运行 40 刻，deadline 就返回 140。这里不会启动计时器。
 */
public record ToolContext(String toolCallId, long gameTime) {

    /** 用调用时的世界时钟加上允许用的刻数，得到截止时刻；暂停是否顺延由任务系统处理。 */
    public long deadline(long ticks) {
        return gameTime + ticks;
    }
}
