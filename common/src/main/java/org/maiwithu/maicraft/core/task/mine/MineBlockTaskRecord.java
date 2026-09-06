package org.maiwithu.maicraft.core.task.mine;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Block;

import java.util.Set;
import java.util.List;

/**
 * Typed task descriptor for the intent-level {@code mine} tool: "gather
 * {@code count} of these block types, search/pathfind/dig it yourself". The
 * task owns the whole loop — scan the loaded area around the body for
 * targets, walk to them with the terrain-modifying pathfinder (bridging /
 * digging as needed), mine into the entity inventory, repeat until the count
 * is met or nothing reachable remains.
 *
 * <p>The LLM never sees coordinates: it only declares <em>what</em> and
 * <em>how many</em>. Drops/tool-tier follow from whatever the entity holds,
 * as in vanilla.
 */
public final class MineBlockTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "mine";

    /** Block types to gather (include variants, e.g. iron_ore + deepslate_iron_ore). */
    public final Set<Block> targets;
    /** How many to gather before reporting success. */
    public final int count;
    /** Human-readable target label for messages / debug overlay (e.g. "iron_ore"). */
    public final String label;
    /** Exact acceptable inventory products when the semantic caller already knows them.
     *  This lets terrain movements that mine a target contribute without exposing paths
     *  or individual blocks to the LLM. Empty keeps the generic direct-mine behavior. */
    public final Set<Item> progressItems;
    /** A prepared work batch must return for another tool instead of degrading to bare hands. */
    public final boolean requireEfficientTool;
    /** Automatic material supply must distinguish natural trees from structural log blocks. */
    public final boolean naturalLogsOnly;

    /** Live progress = matching ITEMS gathered since the task started (counted in the inventory,
     *  not blocks broken — multi-drop ores like redstone yield several items per block). Set each tick
     *  by the task; drives the stop condition + the debug overlay text. */
    private int mined = 0;

    public MineBlockTaskRecord(String toolCallId, long deadlineGameTime,
                               Set<Block> targets, int count, String label) {
        this(toolCallId, deadlineGameTime, targets, count, label, Set.of());
    }

    public MineBlockTaskRecord(String toolCallId, long deadlineGameTime,
                               Set<Block> targets, int count, String label,
                               Set<Item> progressItems) {
        this(toolCallId, deadlineGameTime, targets, count, label, progressItems, false);
    }

    public MineBlockTaskRecord(String toolCallId, long deadlineGameTime,
                               Set<Block> targets, int count, String label,
                               Set<Item> progressItems, boolean requireEfficientTool) {
        this(toolCallId, deadlineGameTime, targets, count, label, progressItems, requireEfficientTool, false);
    }

    public MineBlockTaskRecord(String toolCallId, long deadlineGameTime,
                               Set<Block> targets, int count, String label,
                               Set<Item> progressItems, boolean requireEfficientTool, boolean naturalLogsOnly) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.targets = Set.copyOf(targets);
        this.count = count;
        this.label = label;
        this.progressItems = Set.copyOf(progressItems);
        this.requireEfficientTool = requireEfficientTool;
        this.naturalLogsOnly = naturalLogsOnly;
    }

    /** Matches the native tool selector's main-inventory reach, including tools not yet staged. */
    public static boolean hasEfficientTool(LocalPlayer player, Set<Block> targets) {
        return hasEfficientTool(player.getInventory().items, targets);
    }

    static boolean hasEfficientTool(List<ItemStack> inventory, Set<Block> targets) {
        for (int slot = 0; slot < Math.min(36, inventory.size()); slot++) {
            ItemStack tool = inventory.get(slot);
            if (tool.isEmpty() || (tool.isDamageableItem()
                    && tool.getMaxDamage() - tool.getDamageValue() <= 0)) continue;
            for (Block target : targets) {
                var state = target.defaultBlockState();
                if (tool.getDestroySpeed(state) > 1.0F
                        && (!state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state))) return true;
            }
        }
        return false;
    }

    public int getMined() {
        return mined;
    }

    /** Set the running item-gathered tally (the task recomputes it from the inventory each tick). */
    public void setMined(int gathered) {
        this.mined = gathered;
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return "挖 " + label + " " + mined + "/" + count;
    }
}
