package org.maiwithu.maicraft.core.task.inventory;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.List;

/**
 * 保存这次要清空的装备栏列表，复制后按顺序执行。
 * 它与穿戴共用 equip_item 工具名，但实际执行器是 UnequipCompanionTask。
 */
public final class UnequipTaskRecord extends TaskRecord {

    /** Human-readable label for messages / debug overlay: "armor" 或单个槽位名。 */
    public final String label;
    public final List<EquipmentSlot> slots;

    public UnequipTaskRecord(String toolCallId, long deadlineGameTime,
                             List<EquipmentSlot> slots, String label) {
        super(EquipTaskRecord.TOOL_NAME, toolCallId, deadlineGameTime);
        this.slots = List.copyOf(slots);
        this.label = label;
    }

    @Override
    public String describe() {
        return EquipTaskRecord.TOOL_NAME + " unequip " + label;
    }
}
