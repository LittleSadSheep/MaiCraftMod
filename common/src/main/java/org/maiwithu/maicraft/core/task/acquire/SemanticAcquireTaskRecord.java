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

/** One final-inventory acquisition goal governed by live progress and finite graph evidence. */
public final class SemanticAcquireTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "acquire_items";
    /** Large enough for ordinary modded item tags while planner work is sliced per tick. */
    public static final int MAX_ITEM_ALTERNATIVES = 256;
    /** Full 36-slot main-inventory ceiling for ordinary 64-stack materials. */
    public static final int MAX_FINAL_COUNT = 36 * 64;
    public static final int DEFAULT_RADIUS = 16;
    public static final int MAX_RADIUS = 48;

    /** Sources are semantic permissions, not concrete instructions. */
    public enum Source {
        INVENTORY,
        NEARBY,
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
                                + "'; expected inventory, nearby, storage, craft, cook, mine, trade or hunt");
            }
        }
    }

    /**
     * Optional semantic evidence about a source family. Refs remain resource IDs/tags; there are
     * no positions, entity runtime IDs, routes, slots or interaction steps here.
     */
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

    /**
     * Ordinary survival default. Mining remains subject to harvest/protection checks. Hunting is
     * discoverable here so the task can explain the real prerequisite, but cannot start unless
     * {@code allowHarm} is explicitly true.
     */
    public static final List<Source> DEFAULT_SOURCES =
            List.of(Source.INVENTORY, Source.NEARBY, Source.CRAFT, Source.COOK,
                    Source.MINE, Source.HUNT);

    public final List<ResourceLocation> itemIds;
    public final int count;
    public final List<Source> allowedSources;
    public final boolean allowHarm;
    public final SourceHint sourceHint;
    public final List<String> protectedLabels;
    public final int searchRadius;

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
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.itemIds = validateItems(itemIds);
        this.count = Math.clamp(count, 1, MAX_FINAL_COUNT);
        this.allowedSources = normalizeSources(allowedSources);
        this.allowHarm = allowHarm;
        this.sourceHint = sourceHint == null ? SourceHint.empty() : sourceHint;
        this.protectedLabels = normalizedStrings(
                protectedLabels, 64, "protected labels");
        this.searchRadius = Math.clamp(searchRadius, 1, MAX_RADIUS);
    }

    /** Calling this method forces static task registration during Mod initialization. */
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
