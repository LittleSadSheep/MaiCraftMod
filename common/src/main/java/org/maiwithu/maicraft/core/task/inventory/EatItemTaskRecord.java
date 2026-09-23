package org.maiwithu.maicraft.core.task.inventory;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * 一份进食任务单：保存要使用的物品和显示名字，以及继承的调用编号与截止时间。
 * 真正选物品、吃东西和检查结果在 EatCompanionTask。
 */
public final class EatItemTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "eat";

    /** 要食用的食物，任务完成时消耗一个。 */
    public final Item item;
    /** 消息和调试覆盖层使用的可读标签，例如 "golden_apple"。 */
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
