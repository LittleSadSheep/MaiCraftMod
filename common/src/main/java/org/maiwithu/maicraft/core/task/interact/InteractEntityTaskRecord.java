package org.maiwithu.maicraft.core.task.interact;
import org.maiwithu.maicraft.core.task.MouseButton;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * 保存要交互的实体运行编号、按键、物品和按住时间。执行任务会先靠近并对准它。
 * 按住时间为零表示点一次，正数表示持续指定游戏刻，负一表示持续到动作结束或任务超时。
 */
public final class InteractEntityTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "interact_entity";



    public final MouseButton button;
    public final int entityId;
    public final int holdTicks;
    public final Item item;        // null → use whatever is in hand; else equip this first (food / shears / weapon)

    public InteractEntityTaskRecord(String toolCallId, long deadlineGameTime,
                                    MouseButton button, int entityId, int holdTicks, Item item) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.button = button;
        this.entityId = entityId;
        this.holdTicks = holdTicks;
        this.item = item;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + (button == MouseButton.LEFT ? "left" : "right")
                + (item != null ? " " + BuiltInRegistries.ITEM.getKey(item).getPath() : "")
                + " entity#" + entityId + (holdTicks != 0 ? " hold=" + holdTicks : "");
    }
}
