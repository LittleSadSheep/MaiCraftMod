// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;

/** Registration and typed-record seam for semantic final-inventory acquisition. */
public final class SemanticAcquireApi {
    private static final long MIN_INITIAL_LEASE_TICKS = 3L * 60L * 20L;
    private static final long MAX_INITIAL_LEASE_TICKS = 20L * 60L * 20L;

    private SemanticAcquireApi() {}

    /** Root registration point: call once beside the other internal work tools. */
    public static void register() {
        SemanticAcquireTaskRecord.ensureRegistered();
        ToolRegistry.register(new SemanticAcquireTool());
    }

    /** Parse semantic arguments and produce one persistent cross-tick task record. */
    public static SemanticAcquireTaskRecord newRecord(
            ToolContext context, JsonObject arguments, LocalPlayer player) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        List<ResourceLocation> itemIds = new ArrayList<>();
        if (args.has("item_id") && !args.get("item_id").isJsonNull()) {
            itemIds.add(itemId(args.get("item_id"), "item_id"));
        }
        itemIds.addAll(resourceIds(args.get("item_ids"), "item_ids", true));
        if (args.has("item_tag") && !args.get("item_tag").isJsonNull()) {
            itemIds.addAll(resolveItemTag(
                    primitiveTag(args.get("item_tag"), "item_tag"), player));
        }
        for (String rawTag : strings(args.get("item_tags"), "item_tags")) {
            itemIds.addAll(resolveItemTag(parseTag(rawTag, "item_tags"), player));
        }
        itemIds = new ArrayList<>(new LinkedHashSet<>(itemIds));
        if (itemIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "acquire_items needs item_id, item_ids, item_tag or item_tags");
        }

        int count = integer(args, "count", 1,
                1, SemanticAcquireTaskRecord.MAX_FINAL_COUNT);
        List<SemanticAcquireTaskRecord.Source> sources = new ArrayList<>();
        for (String source : strings(args.get("allowed_sources"), "allowed_sources")) {
            sources.add(SemanticAcquireTaskRecord.Source.parse(source));
        }

        JsonObject hintObject = args.has("source_hint")
                        && args.get("source_hint").isJsonObject()
                ? args.getAsJsonObject("source_hint") : new JsonObject();
        List<String> blockRefs = new ArrayList<>();
        blockRefs.addAll(strings(hintObject.get("block_ids"),
                "source_hint.block_ids"));
        for (String tag : strings(hintObject.get("block_tags"),
                "source_hint.block_tags")) {
            blockRefs.add(tag.startsWith("#") ? tag : "#" + tag);
        }
        List<ResourceLocation> entityTypes = resourceIds(
                hintObject.get("entity_type_ids"),
                "source_hint.entity_type_ids", false);
        for (ResourceLocation id : entityTypes) {
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                throw new IllegalArgumentException("unknown entity type in source_hint: " + id);
            }
        }
        List<ResourceLocation> expectedItems = resourceIds(
                hintObject.get("expected_item_ids"),
                "source_hint.expected_item_ids", true);
        List<ResourceLocation> professions = resourceIds(
                hintObject.get("trade_profession_ids"),
                "source_hint.trade_profession_ids", false);
        String description = primitiveString(hintObject, "description");
        SemanticAcquireTaskRecord.SourceHint hint = new SemanticAcquireTaskRecord.SourceHint(
                blockRefs, entityTypes, expectedItems, professions, description);

        List<String> protectedLabels = strings(
                args.get("protected_labels"), "protected_labels");
        int radius = integer(args, "radius", SemanticAcquireTaskRecord.DEFAULT_RADIUS,
                1, SemanticAcquireTaskRecord.MAX_RADIUS);
        boolean allowHarm = bool(args, "allow_harm", false);

        long ticks = Math.clamp(2L * 60L * 20L + (long) count * 80L,
                MIN_INITIAL_LEASE_TICKS, MAX_INITIAL_LEASE_TICKS);
        boolean huntAllowed = allowHarm && (sources.isEmpty()
                || sources.contains(SemanticAcquireTaskRecord.Source.HUNT));
        if (huntAllowed) {
            // A real source search walks frontiers and loads terrain. The old three-minute cap
            // made that branch time out long before its bounded 512-block search could finish.
            ticks = Math.max(ticks, 12L * 60L * 20L);
        }
        return new SemanticAcquireTaskRecord(
                context.toolCallId(), context.deadline(ticks), itemIds, count,
                sources, allowHarm, hint, protectedLabels, radius);
    }

    private static List<ResourceLocation> resolveItemTag(
            ResourceLocation tagId, LocalPlayer player) {
        TagKey<Item> key = TagKey.create(Registries.ITEM, tagId);
        List<ResourceLocation> result = new ArrayList<>();
        for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(key)) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(holder.value());
            if (BuiltInRegistries.ITEM.get(id) != Items.AIR) result.add(id);
        }
        result = result.stream()
                .distinct()
                .sorted(Comparator
                        .<ResourceLocation>comparingInt(id ->
                                -inventoryCount(player, BuiltInRegistries.ITEM.get(id)))
                        .thenComparingInt(id -> "minecraft".equals(id.getNamespace()) ? 0 : 1)
                        .thenComparing(ResourceLocation::toString))
                .toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException("unknown or empty item tag: #" + tagId);
        }
        return result;
    }

    private static int inventoryCount(LocalPlayer player, Item item) {
        if (player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static ResourceLocation primitiveTag(JsonElement value, String label) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(label + " must be a namespaced item tag");
        }
        return parseTag(value.getAsString(), label);
    }

    private static ResourceLocation parseTag(String raw, String label) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.startsWith("#")) normalized = normalized.substring(1);
        ResourceLocation id = ResourceLocation.tryParse(normalized);
        if (id == null) {
            throw new IllegalArgumentException(label + " contains an invalid item tag: " + raw);
        }
        return id;
    }

    private static List<ResourceLocation> resourceIds(
            JsonElement value, String label, boolean requireKnownItem) {
        List<ResourceLocation> result = new ArrayList<>();
        for (String raw : strings(value, label)) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) throw new IllegalArgumentException(
                    label + " contains an invalid resource id: " + raw);
            if (requireKnownItem && (!BuiltInRegistries.ITEM.containsKey(id)
                    || BuiltInRegistries.ITEM.get(id) == Items.AIR)) {
                throw new IllegalArgumentException(label + " contains an unknown item: " + id);
            }
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static ResourceLocation itemId(JsonElement value, String label) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(label + " must be a namespaced item id");
        }
        ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)
                || BuiltInRegistries.ITEM.get(id) == Items.AIR) {
            throw new IllegalArgumentException(label + " contains an unknown item: " + value);
        }
        return id;
    }

    private static List<String> strings(JsonElement value, String label) {
        if (value == null || value.isJsonNull()) return List.of();
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException(label + " must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                throw new IllegalArgumentException(label + " must contain only strings");
            }
            String text = element.getAsString();
            if (text.isBlank()) throw new IllegalArgumentException(
                    label + " cannot contain blank values");
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private static String primitiveString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : null;
    }

    private static int integer(
            JsonObject object, String key, int fallback, int minimum, int maximum) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return Math.clamp(object.get(key).getAsInt(), minimum, maximum);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
    }
}
