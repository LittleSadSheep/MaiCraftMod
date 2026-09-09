package org.maiwithu.maicraft.core.task.inventory;

import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 创造领取任务单：保存包含组件的物品模板和总数量，不是在构造时直接把物品放入背包。
 */
public final class CreativeTakeItemsTaskRecord extends TaskRecord {
    static { TaskFactory.register(CreativeTakeItemsTaskRecord.class, CreativeTakeItemsCompanionTask::new); }
    public final ItemStack template;
    public final int count;
    // 模板不能是空物品；复制时把模板数量固定为一，真正要创建的总量单独保存且至少为一。
    public CreativeTakeItemsTaskRecord(String callId, long deadline, ItemStack template, int count) {
        super("take_items", callId, deadline);
        if (template.isEmpty()) throw new IllegalArgumentException("template must not be empty");
        this.template = template.copyWithCount(1);
        this.count = Math.max(1, count);
    }
    @Override public String describe() { return "take_items " + count + "x " + template.getItem(); }
}
