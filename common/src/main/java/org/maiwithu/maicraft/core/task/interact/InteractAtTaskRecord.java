package org.maiwithu.maicraft.core.task.interact;
import org.maiwithu.maicraft.core.task.MouseButton;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Typed descriptor for {@code interact_at} — the point-aimed half of the native
 * crosshair interaction (the BLOCK and AIR columns of vanilla's
 * {@code startAttack}/{@code startUseItem}; the ENTITY column is {@code interact_entity}).
 *
 * <p>Aim at a world point and press a mouse button; the native raytrace resolves whatever
 * is actually under the aim:
 * <ul>
 *   <li>{@link Button#LEFT} (attack): break the block hit (held until gone); air = nothing.</li>
 *   <li>{@link Button#RIGHT} (use): activate the block hit (lever / door / modded machine), or
 *       — when the aim is clear air — use the held item in that direction (throw an ender
 *       pearl, eat, draw a bow).</li>
 * </ul>
 * {@code aim} null = use the body's CURRENT facing (in-air use with no target, e.g. eating).
 * {@code holdTicks}: 0 = a single press; &gt;0 = hold that many ticks (modded crank / bow draw);
 * -1 = hold until the action self-completes or the task times out.
 */
public final class InteractAtTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "interact_at";

    public final MouseButton button;
    public final BlockPos aim;     // null → current facing (in-air use)
    public final int holdTicks;
    public final Item item;        // null → use whatever is already in hand; else equip this first
    public final Block expectedBlock;
    /** Optional identity assertion immediately before native use, distinct from its world outcome. */
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
