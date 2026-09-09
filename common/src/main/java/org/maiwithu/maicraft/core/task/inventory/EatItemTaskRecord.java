package org.maiwithu.maicraft.core.task.inventory;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * 一份进食任务单：保存要使用的物品和显示名字，以及继承的调用编号与截止时间。
 * 真正选物品、吃东西和检查结果在 EatCompanionTask。
 */
public final class EatItemTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "eat";

    /** The food item to eat (one is consumed on completion). */
    public final Item item;
    /** Human-readable label for messages / debug overlay (e.g. "golden_apple"). */
    public final String label;

    public EatItemTaskRecord(String toolCallId, long deadlineGameTime, Item item, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.label = label;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label;
    }
}
