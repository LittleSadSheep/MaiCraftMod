package org.maiwithu.maicraft.core.task.inventory;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

/**
 * 装备任务的要求：物品种类、目标栏位和显示名。slot 为 null 表示由执行器按物品类型选择栏位。
 * 它不保存某一格中某一件物品的完整快照，执行时还会重新查背包。
 */
public final class EquipTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "equip_item";

    /** The item to equip (must be present in the entity's own inventory). */
    public final Item item;
    /** Target slot, or {@code null} to auto-route by item type. */
    public final EquipmentSlot slot;
    /** Human-readable label for messages / debug overlay (e.g. "wooden_pickaxe"). */
    public final String label;

    public EquipTaskRecord(String toolCallId, long deadlineGameTime,
                           Item item, EquipmentSlot slot, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.slot = slot;
        this.label = label;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label;
    }
}
