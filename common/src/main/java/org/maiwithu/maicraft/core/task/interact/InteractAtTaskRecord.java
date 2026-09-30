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
    public final BlockPos aim;     // null 表示沿当前朝向使用物品（对空气使用）。
    public final int holdTicks;
    public final Item item;        // null 表示使用当前手持物；否则先装备指定物品。
    public final Block expectedBlock;
    /**
     * 执行原版使用前再确认目标身份；它不是操作后的结果要求。
     */
    public final Block requiredBlock;
    public boolean heldItemUseOnly;
    public boolean emptyHand;
    public boolean approachTarget;
    public boolean mayAlterTerrain;
    public Item expectedOutputItem;
    public String itemResourceId;

    /** 语义交互由 Mod 自行走到可点击位置；只在原请求允许时才为通行拆挖或垫块。 */
    public InteractAtTaskRecord withApproach(boolean alterTerrain) {
        if (aim == null || heldItemUseOnly) throw new IllegalArgumentException("approach requires a block target");
        approachTarget = true; mayAlterTerrain = alterTerrain; return this;
    }

    /** 只给已点名物品的方块交互绑定观察身份；选择哪一进度由调用者决定，执行器不替换成同名其他工件。 */
    public InteractAtTaskRecord withItemResourceId(String resourceId) {
        if (item == null || aim == null || button != MouseButton.RIGHT || resourceId == null
                || resourceId.isBlank() || resourceId.length() > 512)
            throw new IllegalArgumentException("item_resource_id requires a named item and block use, with a bounded observed identity");
        itemResourceId = resourceId; return this;
    }

    /** 语义目标没点名物品时先收好战斗或施工留下的工具，再空手点击方块，避免把工具当材料放进机器。 */
    public InteractAtTaskRecord withEmptyHand() {
        if (aim == null || button != MouseButton.RIGHT || item != null || heldItemUseOnly)
            throw new IllegalArgumentException("empty-hand interaction needs a block target and no named item");
        emptyHand = true; return this;
    }

    /** 使用物品自身逻辑时不先点击准星后的方块；副手材料由原生物品处理，产物仍须读真实背包。 */
    public InteractAtTaskRecord useHeldItemOnly(Item expectedOutput) {
        if (aim != null || button != MouseButton.RIGHT || item == null || expectedBlock != null)
            throw new IllegalArgumentException("held item use needs a named item and no block target");
        heldItemUseOnly = true; expectedOutputItem = expectedOutput; return this;
    }

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
