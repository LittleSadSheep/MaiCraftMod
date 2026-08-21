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
 * One bounded semantic entity-evidence search. Public semantic input contains no runtime handle or
 * position; an internal parent may attach transient excluded identities for safe handoff.
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

    /** Internal safety mode used when the observation will immediately authorize harm. */
    public final boolean harmIntent;

    /** Runtime identities already rejected by the parent transaction; never public tool input. */
    private final transient Set<UUID> excludedEntityUuids;

    /** Exact identities behind a successful observation; retained only for Java-side handoff. */
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
        // Cover the requested radius rather than silently truncating it after 128 legs.
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

    /** Internal Java-only handoff; callers must revalidate the live entity before acting. */
    public List<UUID> internalVerifiedEntityUuids() {
        return internalVerifiedEntityUuids == null ? List.of() : internalVerifiedEntityUuids;
    }

    /** Calling this method forces static task registration during Mod initialization. */
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
