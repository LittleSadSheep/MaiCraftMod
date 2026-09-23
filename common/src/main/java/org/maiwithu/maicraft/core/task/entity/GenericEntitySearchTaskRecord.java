// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.entity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 一次有界的语义实体证据搜索。公开语义输入不包含运行时句柄或位置；内部父任务可附加临时排除身份，以便安全交接。
 */
public final class GenericEntitySearchTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "find_entity";
    public static final int MIN_DISTANCE = 16;
    public static final int DEFAULT_DISTANCE = 512;
    public static final int MAX_DISTANCE = 2_048;
    public static final int MAX_ENTITY_TYPES = 32;
    public static final int MAX_COUNT = 32;
    private static final int WAYPOINT_GRID = 64;

    public enum Relation {
        WILD,
        HOSTILE,
        UNOWNED,
        ANY;

        public static Relation parse(String value) {
            if (value == null || value.isBlank()) return ANY;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(
                        "unknown entity relation '" + value
                                + "'; expected wild, hostile, unowned or any");
            }
        }
    }

    public final List<ResourceLocation> entityTypeIds;
    public final Relation relation;
    public final int count;
    public final int maxDistance;
    public final int maxWaypoints;
    public final boolean mayAlterTerrain;
    public final List<String> protectedLabels;

    /** 观察结果会立即授权伤害性动作时使用的内部安全模式。 */
    public final boolean harmIntent;

    /** 已被父事务拒绝的运行时身份；绝不作为公开工具输入。 */
    private final transient Set<UUID> excludedEntityUuids;

    /** 成功观察结果对应的确切实体身份；仅在 Java 内部交接时保留。 */
    private transient List<UUID> internalVerifiedEntityUuids = List.of();

    static {
        TaskFactory.register(GenericEntitySearchTaskRecord.class,
                GenericEntitySearchCompanionTask::new);
    }

    public GenericEntitySearchTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            List<ResourceLocation> entityTypeIds,
            Relation relation,
            int count,
            int maxDistance,
            boolean mayAlterTerrain,
            List<String> protectedLabels) {
        this(toolCallId, deadlineGameTime, entityTypeIds, relation, count, maxDistance,
                mayAlterTerrain, protectedLabels, false, Set.of());
    }

    public GenericEntitySearchTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            List<ResourceLocation> entityTypeIds,
            Relation relation,
            int count,
            int maxDistance,
            boolean mayAlterTerrain,
            List<String> protectedLabels,
            boolean harmIntent) {
        this(toolCallId, deadlineGameTime, entityTypeIds, relation, count, maxDistance,
                mayAlterTerrain, protectedLabels, harmIntent, Set.of());
    }

    public GenericEntitySearchTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            List<ResourceLocation> entityTypeIds,
            Relation relation,
            int count,
            int maxDistance,
            boolean mayAlterTerrain,
            List<String> protectedLabels,
            boolean harmIntent,
            Set<UUID> excludedEntityUuids) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.entityTypeIds = validateTypes(entityTypeIds);
        this.relation = relation == null ? Relation.ANY : relation;
        this.count = Math.clamp(count, 1, MAX_COUNT);
        this.maxDistance = Math.clamp(maxDistance, MIN_DISTANCE, MAX_DISTANCE);
        int rings = Math.max(1, (this.maxDistance + WAYPOINT_GRID - 1) / WAYPOINT_GRID);
        // 覆盖调用方请求的整个半径，不能扫描 128 个移动片段后静默截断。
        this.maxWaypoints = Math.max(8, rings * rings * 4);
        this.mayAlterTerrain = mayAlterTerrain;
        this.protectedLabels = normalizeStrings(protectedLabels, 64, "protected labels");
        this.harmIntent = harmIntent;
        this.excludedEntityUuids = excludedEntityUuids == null
                ? Set.of() : Set.copyOf(excludedEntityUuids);
    }

    boolean excludes(UUID uuid) {
        return uuid != null
                && excludedEntityUuids != null
                && excludedEntityUuids.contains(uuid);
    }

    void retainInternalVerifiedEntityUuids(Iterable<UUID> uuids) {
        LinkedHashSet<UUID> retained = new LinkedHashSet<>();
        if (uuids != null) {
            for (UUID uuid : uuids) {
                if (uuid != null
                        && (excludedEntityUuids == null || !excludedEntityUuids.contains(uuid))) {
                    retained.add(uuid);
                }
            }
        }
        internalVerifiedEntityUuids = List.copyOf(retained);
    }

    /** 仅供 Java 内部交接；调用方必须在执行动作前重新核实实体仍然存在。 */
    public List<UUID> internalVerifiedEntityUuids() {
        return internalVerifiedEntityUuids == null ? List.of() : internalVerifiedEntityUuids;
    }

    /** 调用此方法会在 Mod 初始化期间强制完成静态任务注册。 */
    public static void ensureRegistered() {}

    @Override
    public String describe() {
        return "寻找 " + count + " 个 " + relation.name().toLowerCase(Locale.ROOT)
                + " 实体证据（" + entityTypeIds.size() + " 种可接受类型）";
    }

    private static List<ResourceLocation> validateTypes(List<ResourceLocation> values) {
        if (values == null) values = List.of();
        LinkedHashSet<ResourceLocation> unique = new LinkedHashSet<>();
        for (ResourceLocation id : values) {
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                throw new IllegalArgumentException("unknown entity type: " + id);
            }
            unique.add(id);
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("find_entity needs at least one entity_type_id");
        }
        if (unique.size() > MAX_ENTITY_TYPES) {
            throw new IllegalArgumentException(
                    "find_entity accepts at most " + MAX_ENTITY_TYPES + " entity types");
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    private static List<String> normalizeStrings(
            List<String> values, int maximum, String label) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) unique.add(value.trim());
        }
        if (unique.size() > maximum) {
            throw new IllegalArgumentException(label + " accepts at most " + maximum + " values");
        }
        return List.copyOf(unique);
    }
}
