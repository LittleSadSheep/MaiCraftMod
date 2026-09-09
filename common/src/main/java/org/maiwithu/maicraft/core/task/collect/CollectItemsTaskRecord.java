package org.maiwithu.maicraft.core.task.collect;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.world.item.Item;

import java.util.Set;

/**
 * 拾取任务单：指定哪些物品类型和搜索半径，执行器逐堆走近，交给游戏正常拾取。
 * 空过滤集合表示所有类型；这里没有具体目标名单、归属策略或最多拾取多少件的字段。
 */
public final class CollectItemsTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "collect_items";

    /** Item types to collect; empty = collect every dropped item. */
    public final Set<Item> filter;
    /** Search radius in blocks. */
    public final int radius;
    /** Human-readable label for messages (e.g. "all items" or "diamond"). */
    public final String label;

    /** Live progress, updated by the goal as items are absorbed. */
    private int collected = 0;

    public CollectItemsTaskRecord(String toolCallId, long deadlineGameTime,
                                  Set<Item> filter, int radius, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.filter = Set.copyOf(filter);
        this.radius = radius;
        this.label = label;
    }

    public int getCollected() {
        return collected;
    }

    public void addCollected(int count) {
        // 只累加实际确认得到的非负数量，不能因为一次查询变少了就倒扣已拾取数。
        this.collected += Math.max(0, count);
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return "捣东西 " + label + " x" + collected;
    }
}
