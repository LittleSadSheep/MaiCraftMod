package org.maiwithu.maicraft.core.task.interact;
import org.maiwithu.maicraft.core.task.MouseButton;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * 保存原地点击的方向、按键、物品和按住时间。没有坐标时沿当前朝向操作。
 * expectedBlock 要求操作后出现某种方块；requiredBlock 要求操作前目标仍是指定方块，两者用途不同。
 */
public final class InteractAtTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "interact_at";

    public final MouseButton button;
    public final BlockPos aim;     // null → current facing (in-air use)
    public final int holdTicks;
    public final Item item;        // null → use whatever is already in hand; else equip this first
    public final Block expectedBlock;
    /**
     * 执行原版使用前再确认目标身份；它不是操作后的结果要求。
     */
    public final Block requiredBlock;

    public InteractAtTaskRecord(String toolCallId, long deadlineGameTime,
                                MouseButton button, BlockPos aim, int holdTicks, Item item) {
        this(toolCallId, deadlineGameTime, button, aim, holdTicks, item, null);
    }

    public InteractAtTaskRecord(String toolCallId, long deadlineGameTime,
                                MouseButton button, BlockPos aim, int holdTicks, Item item, Block expectedBlock) {
        this(toolCallId, deadlineGameTime, button, aim, holdTicks, item, expectedBlock, null);
    }

    public InteractAtTaskRecord(String toolCallId, long deadlineGameTime,
                                MouseButton button, BlockPos aim, int holdTicks, Item item, Block expectedBlock, Block requiredBlock) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if (requiredBlock != null && aim == null) throw new IllegalArgumentException("required block needs an explicit aim");
        this.button = button;
        this.aim = aim != null ? aim.immutable() : null;
        this.holdTicks = holdTicks;
        this.item = item;
        this.expectedBlock = expectedBlock;
        this.requiredBlock = requiredBlock;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + (button == MouseButton.LEFT ? "left" : "right")
                + (item != null ? " " + BuiltInRegistries.ITEM.getKey(item).getPath() : "")
                + (aim != null ? " @" + aim.getX() + "," + aim.getY() + "," + aim.getZ() : " (forward)")
                + (holdTicks != 0 ? " hold=" + holdTicks : "");
    }
}
