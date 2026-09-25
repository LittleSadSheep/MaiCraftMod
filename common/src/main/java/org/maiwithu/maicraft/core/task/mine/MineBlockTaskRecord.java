package org.maiwithu.maicraft.core.task.mine;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.BlockPos;

import java.util.Set;
import java.util.List;

/**
 * 采矿任务单：要找哪些方块、要多少新材料，以及是否只许砍天然树、是否必须有高效工具。
 * 任务单复制目标集合，防止调用方之后改列表影响正在执行的工作；具体寻找和采集由 MineCompanionTask 完成。
 */
public final class MineBlockTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "mine";

    /** 允许挖的方块种类，例如同时接受普通铁矿和深层铁矿。 */
    public final Set<Block> targets;
    /** 希望新获得的物品数量；没有掉落物的模式由执行器改数确认挖掉的块。 */
    public final int count;
    /** 供消息和调试覆盖层显示的可读目标名称，例如 "iron_ore"。 */
    public final String label;
    /** 明确哪些物品才算目标产物，例如挖铁矿只数粗铁。空集合表示由执行器观察背包变化来猜产物。 */
    public final Set<Item> progressItems;
    /** 工具耗尽后要结束这批工作，不退回空手慢挖。 */
    public final boolean requireEfficientTool;
    /** 只把通过天然树外观检查的原木当作材料，避免顺手拆木屋。 */
    public final boolean naturalLogsOnly;
    private BlockPos searchCenter;
    private int searchRadius;

    /** 实时进度表示任务开始后收集到的匹配物品数，按背包物品计而非破坏方块数；红石等矿石每格会掉落多个物品。
     *  由任务每个 tick 设置，用于结束条件和调试覆盖层文字。 */
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

    /** 语义取材把本次附近范围冻结给采矿子任务；走向目标后不能把搜索中心一起带走，越找越远。 */
    public MineBlockTaskRecord withinRadius(BlockPos center, int radius) {
        if (center == null || radius < 1 || radius > 512) throw new IllegalArgumentException("invalid mining search scope");
        searchCenter = center.immutable(); searchRadius = radius; return this;
    }
    public BlockPos searchCenter() { return searchCenter; }
    public int searchRadius() { return searchRadius; }
    /** 先缩小索引的区块环，再按方块距离筛选命中；区块边界的取整不能扩大实际候选范围。 */
    int queryChunkRadius(int fallback) { return searchCenter == null ? fallback : (searchRadius + 15) / 16; }
    public boolean inSearchScope(BlockPos pos) { return searchCenter == null || pos.distSqr(searchCenter) <= (double) searchRadius * searchRadius; }

    /** 与原生工具选择器的主背包扫描范围一致，包括尚未切换到手上的工具。 */
    public static boolean hasEfficientTool(LocalPlayer player, Set<Block> targets) {
        return hasEfficientTool(player.getInventory().items, targets);
    }

    // 前 36 格中只要有一个仍有耐久、能加速且能采出任一目标材料的工具，就返回 true。
    // 这不表示每一种目标都有合适工具，也不保证这一把工具能撑完整批。
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

    /** 设置当前累计采集物品数；任务每个 tick 都会根据背包重新计算。 */
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
