package org.maiwithu.maicraft.core.task.inventory;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * 保存要丢的物品种类、请求数量和显示名；执行器会结合实际拥有数量决定本轮最多丢多少。
 */
public final class DropItemsTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "drop_items";

    public final Item item;
    public final int count;
    public final String label;

    public DropItemsTaskRecord(String toolCallId, long deadlineGameTime,
                               Item item, int count, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.count = count;
        this.label = label;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + count + "x " + label;
    }
}
