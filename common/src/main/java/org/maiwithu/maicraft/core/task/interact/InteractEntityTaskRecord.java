package org.maiwithu.maicraft.core.task.interact;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.entity.SheepTraits;

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
    public final Item item;        // null 表示使用当前手持物；否则先装备指定物品（食物、剪刀或武器）。
    public boolean menuOnly;
    private SheepTraits sheepTraits = SheepTraits.ANY;

    /** 剪毛或染色前保留原来的选羊条件，动作完成后的新状态由回执如实报告。 */
    public InteractEntityTaskRecord withSheepTraits(SheepTraits traits) {
        sheepTraits = traits == null ? SheepTraits.ANY : traits;
        return this;
    }

    public SheepTraits sheepTraits() { return sheepTraits == null ? SheepTraits.ANY : sheepTraits; }

    /** 打开交易等实体界面时必须空主手，且实体拒绝交互后不能继续使用无线终端等手持物。 */
    public InteractEntityTaskRecord forMenu() {
        if (button != MouseButton.RIGHT || holdTicks != 0 || item != null)
            throw new IllegalStateException("entity menu opening requires one empty-hand right click");
        menuOnly = true;
        return this;
    }

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
