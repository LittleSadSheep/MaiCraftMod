// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 一张最终背包需求单：要什么、要多少、可以从哪些来源取得，以及需要遵守的保护范围。 */
public final class SemanticAcquireTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "acquire_items";
    /** 一组最多接受这些物品变体；实际规划按游戏刻分步推进。 */
    public static final int MAX_ITEM_ALTERNATIVES = 256;
    /** 按主背包三十六格、每格六十四件设数量上限，不代表不可堆叠物品也装得下。 */
    public static final int MAX_FINAL_COUNT = 36 * 64;
    public static final int DEFAULT_RADIUS = 16;
    public static final int MAX_RADIUS = 48;

    /** 来源表示允许做哪些事；执行顺序根据现场库存和配方条件决定。 */
    public enum Source {
        INVENTORY,
        NEARBY,
        /** 只取随身无线终端的已观察现货；不搜索普通箱子，也不委托网络合成。 */
        WIRELESS,
        STORAGE,
        CRAFT,
        COOK,
        MINE,
        TRADE,
        HUNT;

        public static Source parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("acquisition source cannot be blank");
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(
                        "unknown acquisition source '" + value
                                + "'; expected inventory, nearby, wireless, storage, craft, cook, mine, trade or hunt");
            }
        }
    }

    /** 可选来源线索只说明方块、标签、生物和职业等种类，不携带位置、临时实体编号、槽位或操作步骤。 */
    public record SourceHint(
            List<String> blockRefs,
            List<ResourceLocation> entityTypeIds,
            List<ResourceLocation> expectedItemIds,
            List<ResourceLocation> tradeProfessionIds,
            String description) {
        public SourceHint {
            blockRefs = normalizedStrings(blockRefs, 64, "source_hint block refs");
            entityTypeIds = distinct(entityTypeIds, 32, "source_hint entity types");
            expectedItemIds = distinct(expectedItemIds, 64, "source_hint expected items");
            tradeProfessionIds = distinct(
                    tradeProfessionIds, 32, "source_hint trade professions");
            description = description == null || description.isBlank()
                    ? null : description.trim();
        }

        public static SourceHint empty() {
            return new SourceHint(List.of(), List.of(), List.of(), List.of(), null);
        }

        public boolean isEmpty() {
            return blockRefs.isEmpty() && entityTypeIds.isEmpty()
                    && expectedItemIds.isEmpty() && tradeProfessionIds.isEmpty()
                    && description == null;
        }
    }

    /** 普通生存默认来源；采矿仍检查工具和保护，狩猎只有明确允许伤害后才能真正动手。 */
    public static final List<Source> DEFAULT_SOURCES =
            List.of(Source.INVENTORY, Source.NEARBY, Source.WIRELESS, Source.CRAFT, Source.COOK,
                    Source.MINE, Source.HUNT);

    public final List<ResourceLocation> itemIds;
    public final int count;
    public final List<Source> allowedSources;
    public final boolean allowHarm;
    public final SourceHint sourceHint;
    public final List<String> protectedLabels;
    public final int searchRadius;
    /** 内部补料可单独查更远的已加载仓库；附近采集、采矿等仍使用原来的 searchRadius。 */
    public final int storageSearchRadius;

    static {
        TaskFactory.register(SemanticAcquireTaskRecord.class,
                SemanticAcquireCompanionTask::new);
    }

    public SemanticAcquireTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            List<ResourceLocation> itemIds,
            int count,
            List<Source> allowedSources,
            boolean allowHarm,
            SourceHint sourceHint,
            List<String> protectedLabels,
            int searchRadius) {
        this(toolCallId, deadlineGameTime, itemIds, count, allowedSources, allowHarm,
                sourceHint, protectedLabels, searchRadius, searchRadius);
    }

    /** 仅内部组合任务传入独立仓库范围；公开获取入口继续使用上面的构造，尊重主人显式指定的半径。 */
    public SemanticAcquireTaskRecord(
            String toolCallId, long deadlineGameTime, List<ResourceLocation> itemIds, int count,
            List<Source> allowedSources, boolean allowHarm, SourceHint sourceHint,
            List<String> protectedLabels, int searchRadius, int storageSearchRadius) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.itemIds = validateItems(itemIds);
        this.count = Math.clamp(count, 1, MAX_FINAL_COUNT);
        this.allowedSources = normalizeSources(allowedSources);
        this.allowHarm = allowHarm;
        this.sourceHint = sourceHint == null ? SourceHint.empty() : sourceHint;
        this.protectedLabels = normalizedStrings(
                protectedLabels, 64, "protected labels");
        this.searchRadius = Math.clamp(searchRadius, 1, MAX_RADIUS);
        this.storageSearchRadius = this.allowedSources.contains(Source.STORAGE)
                ? Math.clamp(storageSearchRadius, 1, MAX_RADIUS) : this.searchRadius;
    }

    /** 模组初始化时确保这类取物任务已登记，随后才能从任务单创建执行器。 */
    public static void ensureRegistered() {}

    @Override
    public String describe() {
        String first = itemIds.getFirst().toString();
        String alternatives = itemIds.size() == 1 ? "" : "+" + (itemIds.size() - 1);
        return "获取 " + first + alternatives + " 至背包总数 " + count;
    }

    private static List<ResourceLocation> validateItems(List<ResourceLocation> values) {
        List<ResourceLocation> result = distinct(
                values, MAX_ITEM_ALTERNATIVES, "acceptable item IDs");
        if (result.isEmpty()) {
            throw new IllegalArgumentException("acquire_items needs at least one item ID");
        }
        for (ResourceLocation id : result) {
            if (!BuiltInRegistries.ITEM.containsKey(id)
                    || BuiltInRegistries.ITEM.get(id) == Items.AIR) {
                throw new IllegalArgumentException("unknown item ID: " + id);
            }
        }
        return result;
    }

    private static List<Source> normalizeSources(List<Source> values) {
        List<Source> requested = values == null || values.isEmpty()
                ? DEFAULT_SOURCES : values;
        LinkedHashSet<Source> result = new LinkedHashSet<>();
        result.add(Source.INVENTORY);
        for (Source source : requested) result.add(Objects.requireNonNull(source, "source"));
        return List.copyOf(result);
    }

    private static List<String> normalizedStrings(
            List<String> values, int maximum, String label) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) result.add(value.trim());
        }
        if (result.size() > maximum) {
            throw new IllegalArgumentException(label + " accepts at most " + maximum + " values");
        }
        return List.copyOf(result);
    }

    private static <T> List<T> distinct(List<T> values, int maximum, String label) {
        if (values == null) return List.of();
        LinkedHashSet<T> result = new LinkedHashSet<>();
        for (T value : values) result.add(Objects.requireNonNull(value, label + " value"));
        if (result.size() > maximum) {
            throw new IllegalArgumentException(label + " accepts at most " + maximum + " values");
        }
        return List.copyOf(new ArrayList<>(result));
    }
}
