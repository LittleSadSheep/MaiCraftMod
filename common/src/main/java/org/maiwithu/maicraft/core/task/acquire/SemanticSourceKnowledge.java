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

    private static String toolFamily(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "pickaxe";
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return "axe";
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "shovel";
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return "hoe";
        return "harvesting_tool";
    }

    private static Map<String, Profile> profiles() {
        Map<String, Profile> result = new LinkedHashMap<>();
        block(result, "minecraft:coal", List.of("#minecraft:coal_ores"), "coal ore");
        block(result, "minecraft:raw_iron", List.of("#minecraft:iron_ores"), "iron ore");
        block(result, "minecraft:raw_copper", List.of("#minecraft:copper_ores"), "copper ore");
        block(result, "minecraft:raw_gold", List.of("#minecraft:gold_ores"), "gold ore");
        block(result, "minecraft:diamond", List.of("#minecraft:diamond_ores"), "diamond ore");
        block(result, "minecraft:emerald", List.of("#minecraft:emerald_ores"), "emerald ore");
        block(result, "minecraft:lapis_lazuli", List.of("#minecraft:lapis_ores"), "lapis ore");
        block(result, "minecraft:redstone", List.of("#minecraft:redstone_ores"), "redstone ore");
        block(result, "minecraft:quartz", List.of("minecraft:nether_quartz_ore"),
                "nether quartz ore", List.of("minecraft:the_nether"));
        block(result, "minecraft:ancient_debris", List.of("minecraft:ancient_debris"),
                "ancient debris", List.of("minecraft:the_nether"));
        block(result, "minecraft:nether_quartz_ore", List.of("minecraft:nether_quartz_ore"),
                "nether quartz ore block", List.of("minecraft:the_nether"));
        block(result, "minecraft:nether_gold_ore", List.of("minecraft:nether_gold_ore"),
                "nether gold ore block", List.of("minecraft:the_nether"));
        block(result, "minecraft:netherrack", List.of("minecraft:netherrack"),
                "natural netherrack", List.of("minecraft:the_nether"));
        block(result, "minecraft:blackstone", List.of("minecraft:blackstone"),
                "natural blackstone", List.of("minecraft:the_nether"));
        block(result, "minecraft:glowstone_dust", List.of("minecraft:glowstone"),
                "natural glowstone", List.of("minecraft:the_nether"));
        block(result, "minecraft:end_stone", List.of("minecraft:end_stone"),
                "natural end stone", List.of("minecraft:the_end"));
        block(result, "minecraft:chorus_fruit",
                List.of("minecraft:chorus_plant", "minecraft:chorus_flower"),
                "natural chorus plant", List.of("minecraft:the_end"));
        block(result, "minecraft:cobblestone", List.of("minecraft:stone"), "stone that drops cobblestone");
        block(result, "minecraft:cobbled_deepslate", List.of("minecraft:deepslate"), "deepslate");
        block(result, "minecraft:flint", List.of("minecraft:gravel"), "gravel that can drop flint");

        entity(result, "minecraft:white_wool", List.of("minecraft:sheep"), "sheep wool");
        for (String color : List.of("orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
                "red", "black")) {
            entity(result, "minecraft:" + color + "_wool", List.of("minecraft:sheep"),
                    "matching sheep wool");
        }
        entity(result, "minecraft:mutton", List.of("minecraft:sheep"), "sheep drop");
        entity(result, "minecraft:beef", List.of("minecraft:cow"), "cow drop");
        entity(result, "minecraft:porkchop", List.of("minecraft:pig", "minecraft:hoglin"), "pig-family drop");
        entity(result, "minecraft:chicken", List.of("minecraft:chicken"), "chicken drop");
        entity(result, "minecraft:feather", List.of("minecraft:chicken"), "chicken drop");
        entity(result, "minecraft:rabbit", List.of("minecraft:rabbit"), "rabbit drop");
        entity(result, "minecraft:rabbit_hide", List.of("minecraft:rabbit"), "rabbit drop");
        entity(result, "minecraft:rabbit_foot", List.of("minecraft:rabbit"), "rare rabbit drop");
        entity(result, "minecraft:leather", List.of("minecraft:cow"), "cow drop");
        entity(result, "minecraft:string", List.of("minecraft:spider", "minecraft:cave_spider"), "spider drop");
        entity(result, "minecraft:ender_pearl", List.of("minecraft:enderman"), "enderman drop");
        entity(result, "minecraft:blaze_rod", List.of("minecraft:blaze"),
                "blaze drop", List.of("minecraft:the_nether"));
        entity(result, "minecraft:bone", List.of("minecraft:skeleton", "minecraft:stray"), "skeleton-family drop");
        entity(result, "minecraft:rotten_flesh", List.of("minecraft:zombie", "minecraft:husk"), "zombie-family drop");
        entity(result, "minecraft:gunpowder", List.of("minecraft:creeper", "minecraft:witch", "minecraft:ghast"), "hostile mob drop");
        entity(result, "minecraft:slime_ball", List.of("minecraft:slime"), "slime drop");
        entity(result, "minecraft:ink_sac", List.of("minecraft:squid"), "squid drop");
        entity(result, "minecraft:glow_ink_sac", List.of("minecraft:glow_squid"), "glow squid drop");
        entity(result, "minecraft:phantom_membrane", List.of("minecraft:phantom"), "phantom drop");
        entity(result, "minecraft:shulker_shell", List.of("minecraft:shulker"),
                "shulker drop", List.of("minecraft:the_end"));
        entity(result, "minecraft:ghast_tear", List.of("minecraft:ghast"),
                "ghast drop", List.of("minecraft:the_nether"));
        entity(result, "minecraft:magma_cream", List.of("minecraft:magma_cube"),
                "magma cube drop", List.of("minecraft:the_nether"));
        entity(result, "minecraft:wither_skeleton_skull", List.of("minecraft:wither_skeleton"),
                "wither skeleton drop", List.of("minecraft:the_nether"));
        entity(result, "minecraft:prismarine_shard", List.of("minecraft:guardian", "minecraft:elder_guardian"), "guardian drop");
        entity(result, "minecraft:prismarine_crystals", List.of("minecraft:guardian", "minecraft:elder_guardian"), "guardian drop");
        return Map.copyOf(result);
    }

    private static <T> List<T> bounded(Set<T> values, int maximum) {
        return List.copyOf(values.stream().limit(maximum).toList());
    }

    private static void block(
            Map<String, Profile> target, String item, List<String> blocks, String description) {
        block(target, item, blocks, description, List.of());
    }

    private static void block(
            Map<String, Profile> target,
            String item,
            List<String> blocks,
            String description,
            List<String> allowedDimensions) {
        target.put(item, new Profile(
                blocks, List.of(), sourceDimensions(
                        SemanticAcquireTaskRecord.Source.MINE, allowedDimensions), description));
    }

    private static void entity(
            Map<String, Profile> target, String item, List<String> entities, String description) {
        entity(target, item, entities, description, List.of());
    }

    private static void entity(
            Map<String, Profile> target,
            String item,
            List<String> entities,
            String description,
            List<String> allowedDimensions) {
        target.put(item, new Profile(
                List.of(), entities, sourceDimensions(
                        SemanticAcquireTaskRecord.Source.HUNT, allowedDimensions), description));
    }

    private static Map<SemanticAcquireTaskRecord.Source, List<String>> sourceDimensions(
            SemanticAcquireTaskRecord.Source source, List<String> allowedDimensions) {
        if (allowedDimensions == null || allowedDimensions.isEmpty()) return Map.of();
        return Map.of(source, List.copyOf(allowedDimensions));
    }
}
