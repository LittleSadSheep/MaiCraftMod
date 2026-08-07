package org.maiwithu.maicraft.core.task.inventory;

import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** Creative-only request to materialize item stacks through the vanilla creative slot packet. */
public final class CreativeTakeItemsTaskRecord extends TaskRecord {
    static { TaskFactory.register(CreativeTakeItemsTaskRecord.class, CreativeTakeItemsCompanionTask::new); }
    public final ItemStack template;
    public final int count;
    public CreativeTakeItemsTaskRecord(String callId, long deadline, ItemStack template, int count) {
        super("take_items", callId, deadline);
        if (template.isEmpty()) throw new IllegalArgumentException("template must not be empty");
        this.template = template.copyWithCount(1);
        this.count = Math.max(1, count);
    }
    @Override public String describe() { return "take_items " + count + "x " + template.getItem(); }
}
