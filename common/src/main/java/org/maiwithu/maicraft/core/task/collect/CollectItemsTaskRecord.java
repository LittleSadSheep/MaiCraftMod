package org.maiwithu.maicraft.core.task.collect;

import org.maiwithu.maicraft.task.TaskRecord;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.UUID;

/**
 * 拾取任务单：指定哪些物品类型和搜索半径，执行器逐堆走近，交给游戏正常拾取。
 * 空类型过滤表示所有类型；模型选中的物品堆与内部加工产物都可限定 UUID，防止追向别处同类物品。
 */
public final class CollectItemsTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "collect_items";

    /** 要收集的物品类型；为空时收集所有掉落物。 */
    public final Set<Item> filter;
    /** 每轮按角色当前身体盒向三轴扩展的扫描距离，单位为方块；选中实体后不是追踪距离上限。 */
    public final int radius;
    /** 用于消息的可读标签，例如“所有物品”或“钻石”。 */
    public final String label;
    /** 指定的收取实体；仅限定目标身份与范围，不要求物品属于角色，空集合按类型扫描。 */
    public final Set<UUID> targetUuids;
    /** 公开掉落引用所在维度；走路中换维度时停止追踪，避免重用旧引用。 */
    public final ResourceLocation targetDimension;
    /** 拾取接近阶段可继承明确的开路授权；普通拾取和机器内部收料默认只走现有通道。 */
    public final boolean mayAlterTerrain;

    /** 实时进度；目标物品被收入背包时更新。 */
    private int collected = 0;

    public CollectItemsTaskRecord(String toolCallId, long deadlineGameTime,
                                  Set<Item> filter, int radius, String label) {
        this(toolCallId, deadlineGameTime, filter, radius, label, Set.of());
    }

    // 加工产物按已冻结UUID接近，物品合堆后不能自行把权限扩给未证明的幸存实体。
    public CollectItemsTaskRecord(String toolCallId, long deadlineGameTime,
                                  Set<Item> filter, int radius, String label, Set<UUID> targetUuids) {
        this(toolCallId, deadlineGameTime, filter, radius, label, targetUuids, null);
    }

    public CollectItemsTaskRecord(String toolCallId, long deadlineGameTime,
                                  Set<Item> filter, int radius, String label, Set<UUID> targetUuids,
                                  ResourceLocation targetDimension) {
        this(toolCallId, deadlineGameTime, filter, radius, label, targetUuids, targetDimension, false);
    }

    public CollectItemsTaskRecord(String toolCallId, long deadlineGameTime, Set<Item> filter, int radius,
                                  String label, Set<UUID> targetUuids, ResourceLocation targetDimension,
                                  boolean mayAlterTerrain) {
        // 公开 drop_ref 同时携带维度和 UUID；内部产物名单可只给 UUID，因此收尾采用的归因证据并不完全相同。
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.filter = Set.copyOf(filter);
        this.radius = radius;
        this.label = label;
        this.targetUuids = Set.copyOf(targetUuids);
        this.targetDimension = targetDimension;
        this.mayAlterTerrain = mayAlterTerrain;
    }

    boolean permits(UUID uuid) { return targetUuids.isEmpty() || targetUuids.contains(uuid); }

    public int getCollected() {
        return collected;
    }

    public void addCollected(int count) {
        // 只累加实际确认得到的非负数量，不能因为一次查询变少了就倒扣已拾取数。
        this.collected += Math.max(0, count);
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return "捣东西 " + label + " x" + collected;
    }
}
