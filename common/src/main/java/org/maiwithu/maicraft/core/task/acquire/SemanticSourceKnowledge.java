// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchTaskRecord;

/**
 * Small immutable semantic source graph for relationships vanilla recipes do not express.
 * Entries identify source families only; live tasks still discover targets and verify drops.
 */
public final class SemanticSourceKnowledge {
    public record ToolRequirement(
            List<ResourceLocation> acceptableItemIds,
            String toolFamily,
            String minimumTier) {
        public ToolRequirement {
            acceptableItemIds = List.copyOf(acceptableItemIds);
        }
    }

    /**
     * Source evidence plus the dimensions in which that physical source is valid. An absent or
     * empty entry means that the source is dimension-independent. The map is intentionally keyed
     * by source family: a loose item may be collected in any dimension even when its natural mob
     * or ore source is dimension-bound.
     */
    public record SourcePlan(
            SemanticAcquireTaskRecord.SourceHint hint,
            Map<SemanticAcquireTaskRecord.Source, List<ResourceLocation>> allowedDimensions) {
        public SourcePlan {
            hint = hint == null ? SemanticAcquireTaskRecord.SourceHint.empty() : hint;
            Map<SemanticAcquireTaskRecord.Source, List<ResourceLocation>> copied =
                    new LinkedHashMap<>();
            if (allowedDimensions != null) {
                allowedDimensions.forEach((source, dimensions) -> {
                    if (source != null && dimensions != null && !dimensions.isEmpty()) {
                        copied.put(source, List.copyOf(new LinkedHashSet<>(dimensions)));
                    }
                });
            }
            allowedDimensions = Map.copyOf(copied);
        }

        public List<ResourceLocation> allowedDimensions(
                SemanticAcquireTaskRecord.Source source) {
            return allowedDimensions.getOrDefault(source, List.of());
        }
    }

    private record Profile(
            List<String> blocks,
            List<String> entities,
            Map<SemanticAcquireTaskRecord.Source, List<String>> allowedDimensions,
            String description) {
        Profile {
            blocks = List.copyOf(blocks);
            entities = List.copyOf(entities);
            Map<SemanticAcquireTaskRecord.Source, List<String>> copied =
                    new LinkedHashMap<>();
            if (allowedDimensions != null) {
                allowedDimensions.forEach((source, dimensions) -> {
                    if (source != null && dimensions != null && !dimensions.isEmpty()) {
                        copied.put(source, List.copyOf(new LinkedHashSet<>(dimensions)));
                    }
                });
            }
            allowedDimensions = Map.copyOf(copied);
        }
    }

    private static final Map<String, Profile> PROFILES = profiles();

    private SemanticSourceKnowledge() {}

    public static SemanticAcquireTaskRecord.SourceHint infer(
            List<ResourceLocation> requestedItemIds) {
        return inferPlan(requestedItemIds).hint();
    }

    /** Infer each recursive need independently; callers must not reuse the root plan. */
    public static SourcePlan inferPlan(List<ResourceLocation> requestedItemIds) {
        LinkedHashSet<String> blocks = new LinkedHashSet<>();
        LinkedHashSet<ResourceLocation> entities = new LinkedHashSet<>();
        LinkedHashSet<ResourceLocation> expected = new LinkedHashSet<>();
        LinkedHashSet<String> descriptions = new LinkedHashSet<>();
        Map<SemanticAcquireTaskRecord.Source, LinkedHashSet<ResourceLocation>> dimensions =
                new LinkedHashMap<>();
        Set<SemanticAcquireTaskRecord.Source> unrestricted = new LinkedHashSet<>();
        for (ResourceLocation itemId : requestedItemIds) {
            Profile profile = PROFILES.get(itemId.toString());
            if (profile == null) {
                // Acquire can mine a requested BlockItem directly even without a knowledge entry.
                // Such an alternative must keep the combined MINE plan dimension-independent.
                if (BuiltInRegistries.ITEM.get(itemId) instanceof BlockItem) {
                    unrestricted.add(SemanticAcquireTaskRecord.Source.MINE);
                    dimensions.remove(SemanticAcquireTaskRecord.Source.MINE);
                }
                continue;
            }
            blocks.addAll(profile.blocks());
            for (String value : profile.entities()) {
                ResourceLocation id = ResourceLocation.tryParse(value);
                if (id != null) entities.add(id);
            }
            expected.add(itemId);
            if (profile.description() != null && !profile.description().isBlank()) {
                descriptions.add(profile.description());
            }
            mergeDimensions(profile, dimensions, unrestricted);
        }
        String description = descriptions.isEmpty()
                ? null : String.join("; ", descriptions.stream().limit(4).toList());
        SemanticAcquireTaskRecord.SourceHint hint = new SemanticAcquireTaskRecord.SourceHint(
                bounded(blocks, 64), bounded(entities, 32), bounded(expected, 64),
                List.of(), description);
        Map<SemanticAcquireTaskRecord.Source, List<ResourceLocation>> allowed =
                new LinkedHashMap<>();
        dimensions.forEach((source, values) -> {
            if (!unrestricted.contains(source) && !values.isEmpty()) {
                allowed.put(source, List.copyOf(values));
            }
        });
        return new SourcePlan(hint, allowed);
    }

    private static void mergeDimensions(
            Profile profile,
            Map<SemanticAcquireTaskRecord.Source, LinkedHashSet<ResourceLocation>> dimensions,
            Set<SemanticAcquireTaskRecord.Source> unrestricted) {
        mergeDimensionsForSource(
                profile, SemanticAcquireTaskRecord.Source.MINE, !profile.blocks().isEmpty(),
                dimensions, unrestricted);
        mergeDimensionsForSource(
                profile, SemanticAcquireTaskRecord.Source.HUNT, !profile.entities().isEmpty(),
                dimensions, unrestricted);
    }

    private static void mergeDimensionsForSource(
            Profile profile,
            SemanticAcquireTaskRecord.Source source,
            boolean sourcePresent,
            Map<SemanticAcquireTaskRecord.Source, LinkedHashSet<ResourceLocation>> dimensions,
            Set<SemanticAcquireTaskRecord.Source> unrestricted) {
        if (!sourcePresent || unrestricted.contains(source)) return;
        List<String> declared = profile.allowedDimensions().getOrDefault(source, List.of());
        if (declared.isEmpty()) {
            unrestricted.add(source);
            dimensions.remove(source);
            return;
        }
        LinkedHashSet<ResourceLocation> target = dimensions.computeIfAbsent(
                source, ignored -> new LinkedHashSet<>());
        for (String value : declared) {
            ResourceLocation dimension = ResourceLocation.tryParse(value);
            if (dimension != null) target.add(dimension);
        }
    }

    /** Root-only user evidence augments, rather than replaces, built-in semantic knowledge. */
    public static SemanticAcquireTaskRecord.SourceHint merge(
            SemanticAcquireTaskRecord.SourceHint inferred,
            SemanticAcquireTaskRecord.SourceHint explicit) {
        if (explicit == null || explicit.isEmpty()) return inferred;
        if (inferred == null || inferred.isEmpty()) return explicit;
        LinkedHashSet<String> blocks = new LinkedHashSet<>(explicit.blockRefs());
        blocks.addAll(inferred.blockRefs());
        LinkedHashSet<ResourceLocation> entities = new LinkedHashSet<>(
                explicit.entityTypeIds());
        entities.addAll(inferred.entityTypeIds());
        LinkedHashSet<ResourceLocation> expected = new LinkedHashSet<>(
                explicit.expectedItemIds());
        expected.addAll(inferred.expectedItemIds());
        LinkedHashSet<ResourceLocation> professions = new LinkedHashSet<>(
                explicit.tradeProfessionIds());
        professions.addAll(inferred.tradeProfessionIds());
        String description = explicit.description() != null
                ? explicit.description() : inferred.description();
        return new SemanticAcquireTaskRecord.SourceHint(
                bounded(blocks, 64), bounded(entities, 32), bounded(expected, 64),
                bounded(professions, 32), description);
    }

    /**
     * Relationship policy for a data-driven hunt source family. A family made entirely of
     * registry MONSTER types is hostile; mixed or passive families must prove wild/unowned
     * evidence. No entity-specific exception belongs here.
     */
    public static GenericEntitySearchTaskRecord.Relation huntRelation(
            List<ResourceLocation> entityTypeIds) {
        boolean allHostile = entityTypeIds != null && !entityTypeIds.isEmpty()
                && entityTypeIds.stream().allMatch(id ->
                        BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                                && BuiltInRegistries.ENTITY_TYPE.get(id).getCategory()
                                        == MobCategory.MONSTER);
        return allHostile
                ? GenericEntitySearchTaskRecord.Relation.HOSTILE
                : GenericEntitySearchTaskRecord.Relation.WILD;
    }

    /**
     * Derive an ordinary harvesting-tool prerequisite from registry and block-tag facts. The
     * returned alternatives include modded tools whose ItemStack actually passes the drop gate.
     */
    public static ToolRequirement missingTool(LocalPlayer player, Set<Block> sourceBlocks) {
        List<BlockState> states = sourceBlocks.stream()
                .map(Block::defaultBlockState).toList();
        // The acquisition need accepts alternatives. One genuinely harvestable ungated source is
        // enough; forcing a tool for a different alternative would create unnecessary work.
        if (states.stream().anyMatch(state -> !state.requiresCorrectToolForDrops())) return null;
        List<BlockState> gated = states.stream()
                .filter(BlockState::requiresCorrectToolForDrops)
                .sorted(Comparator
                        .comparingInt(SemanticSourceKnowledge::tierRank)
                        .thenComparing(state -> BuiltInRegistries.BLOCK.getKey(
                                state.getBlock()).toString()))
                .toList();
        if (gated.isEmpty()) return null;
        for (BlockState state : gated) {
            if (hasCorrectTool(player, state)) return null;
        }

        // MineBlockTask can prune unharvestable alternatives, so prepare for the easiest real
        // source family rather than requiring one tool to harvest every alternative.
        BlockState selected = gated.getFirst();
        Map<ResourceLocation, Integer> candidates = new LinkedHashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(selected)) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id != null) candidates.put(id, itemTierRank(item));
            }
        }
        int cheapestTier = candidates.values().stream()
                .mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
        List<ResourceLocation> alternatives = candidates.entrySet().stream()
                .filter(entry -> entry.getValue() == cheapestTier)
                .map(Map.Entry::getKey)
                .sorted(Comparator
                        .comparingInt((ResourceLocation id) ->
                                "minecraft".equals(id.getNamespace()) ? 0 : 1)
                        .thenComparing(ResourceLocation::toString))
                .limit(SemanticAcquireTaskRecord.MAX_ITEM_ALTERNATIVES)
                .toList();
        if (alternatives.isEmpty()) return null;
        return new ToolRequirement(
                alternatives, toolFamily(selected), minimumTier(selected));
    }

    private static boolean hasCorrectTool(LocalPlayer player, BlockState state) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    private static int tierRank(BlockState state) {
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) return 3;
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) return 2;
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) return 1;
        return 0;
    }

    private static int itemTierRank(Item item) {
        if (!(item instanceof TieredItem tiered)) return 100;
        var incorrect = tiered.getTier().getIncorrectBlocksForDrops();
        if (incorrect.equals(BlockTags.INCORRECT_FOR_WOODEN_TOOL)) return 0;
        if (incorrect.equals(BlockTags.INCORRECT_FOR_STONE_TOOL)) return 1;
        if (incorrect.equals(BlockTags.INCORRECT_FOR_IRON_TOOL)) return 2;
        if (incorrect.equals(BlockTags.INCORRECT_FOR_DIAMOND_TOOL)) return 3;
        if (incorrect.equals(BlockTags.INCORRECT_FOR_NETHERITE_TOOL)) return 4;
        // Gold has wooden-tier harvesting reach but a materially harder acquisition chain.
        if (incorrect.equals(BlockTags.INCORRECT_FOR_GOLD_TOOL)) return 5;
        return 100;
    }

    private static String minimumTier(BlockState state) {
        return switch (tierRank(state)) {
            case 3 -> "diamond";
            case 2 -> "iron";
            case 1 -> "stone";
            default -> "any_correct_tool";
        };
    }

