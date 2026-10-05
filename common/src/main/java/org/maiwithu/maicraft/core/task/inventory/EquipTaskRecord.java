package org.maiwithu.maicraft.core.task.inventory;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 装备任务的要求：物品种类、目标栏位和显示名。slot 为 null 表示由执行器按物品类型选择栏位。
 * 它不保存某一格中某一件物品的完整快照，执行时还会重新查背包。
 */
public final class EquipTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "equip_item";

    /** 要装备的物品（必须存在于实体自己的背包中）。 */
    public final Item item;
    /** 目标槽位；为 {@code null} 时按物品类型自动选择。 */
    public final EquipmentSlot slot;
    /** 消息或调试覆盖层使用的可读标签，例如 "wooden_pickaxe"。 */
    public final String label;
    /** 指定原件时只认附魔、耐久等组件完全相同的那一件，例如寻死后穿回脱下的护甲；为 {@code null} 时按物品种类查找。 */
    public final ItemStack exact;

    public EquipTaskRecord(String toolCallId, long deadlineGameTime,
                           Item item, EquipmentSlot slot, String label) {
        this(toolCallId, deadlineGameTime, item, slot, label, null);
    }

    public EquipTaskRecord(String toolCallId, long deadlineGameTime,
                           Item item, EquipmentSlot slot, String label, ItemStack exact) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.slot = slot;
        this.label = label;
        this.exact = exact == null ? null : exact.copy();
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label;
    }
}
