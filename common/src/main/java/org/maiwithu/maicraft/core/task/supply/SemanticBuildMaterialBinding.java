// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;

/**
 * Binds a semantic building palette to one concrete obtainable variant per material family.
 *
 * <p>The semantic planner still owns appearance and layout. This class only broadens ordinary
 * building roles (planks, masonry, logs, slabs, doors, panes, railings, stairs and carpet) to
 * equivalent registered blocks, then rewrites the already-reviewed plan before its first world
 * effect. Functional furniture, lights and block entities remain exact.</p>
 */
final class SemanticBuildMaterialBinding {
    // Ae2ResourceSupply.Request permits 2,048 accepted IDs in total. There are at most eleven
    // broadened families plus exact singleton families, so 128 keeps every valid 128-group
    // proposal below that aggregate limit without exposing or hard-coding a material palette.
    private static final int MAX_ALTERNATIVES = 128;
    private static final int MAX_GROUPS = 128;

    enum Kind {
        PLANKS, MASONRY, LOG, SLAB, DOOR, PANE, GLASS, FENCE, WALL, STAIRS, CARPET, EXACT
    }

    record Family(
            String identity,
            ResourceLocation groupId,
            List<ResourceLocation> originals,
            List<ResourceLocation> alternatives,
            int count) {
        Family {
            originals = List.copyOf(originals);
            alternatives = List.copyOf(alternatives);
        }
    }

    record Proposal(List<Family> families, List<Ae2ResourceSupply.Group> groups) {
        Proposal {
            families = List.copyOf(families);
            groups = List.copyOf(groups);
        }

        boolean empty() {
            return groups.isEmpty();
        }
    }

    private static final class Accumulator {
        private final String identity;
        private final Kind kind;
        private final LinkedHashSet<ResourceLocation> originals = new LinkedHashSet<>();
        private int count;

        private Accumulator(String identity, Kind kind) {
            this.identity = identity;
            this.kind = kind;
        }
    }

    private SemanticBuildMaterialBinding() {}

    static Proposal propose(BuildTaskRecord plan, boolean broadenMaterialFamilies) {
        Map<String, Accumulator> accumulators = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : plan.targets) {
            int count = target.materialCount();
            if (count <= 0 || !(target.item() instanceof BlockItem blockItem)) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(target.item());
            Kind kind = kind(blockItem.getBlock(), id);
            String identity = kind == Kind.EXACT ? "exact:" + id : kind.name().toLowerCase(Locale.ROOT);
            Accumulator accumulator = accumulators.computeIfAbsent(
                    identity, ignored -> new Accumulator(identity, kind));
            accumulator.originals.add(id);
            accumulator.count = Math.addExact(accumulator.count, count);
        }

        List<Family> families = new ArrayList<>();
        List<Ae2ResourceSupply.Group> groups = new ArrayList<>();
        for (Accumulator accumulator : accumulators.values()) {
            List<ResourceLocation> originals = accumulator.originals.stream().sorted().toList();
            ResourceLocation primary = originals.getFirst();
            List<ResourceLocation> alternatives = broadenMaterialFamilies
                    ? alternatives(accumulator.kind, originals) : originals;
            Family family = new Family(
                    accumulator.identity, primary, originals, alternatives, accumulator.count);
            families.add(family);
            groups.add(new Ae2ResourceSupply.Group(
                    primary, alternatives, accumulator.count,
                    Ae2ResourceSupply.SelectionMode.SINGLE_VARIANT));
        }
        if (groups.size() > MAX_GROUPS) {
            throw new IllegalArgumentException(
                    "a semantic build may contain at most 128 independent material families");
        }
        families.sort(Comparator.comparing(family -> family.groupId().toString()));
        groups.sort(Comparator.comparing(group -> group.itemId().toString()));
        return new Proposal(families, groups);
    }

    static BuildTaskRecord bind(
            BuildTaskRecord source,
            Proposal proposal,
            Map<ResourceLocation, ResourceLocation> selectedByGroup) {
        Map<ResourceLocation, ResourceLocation> replacements = new LinkedHashMap<>();
        for (Family family : proposal.families()) {
            ResourceLocation selected = selectedByGroup.get(family.groupId());
            if (selected == null || !family.alternatives().contains(selected)) {
                throw new IllegalArgumentException(
                        "semantic supply did not select one reviewed material variant for "
                                + family.identity());
            }
            for (ResourceLocation original : family.originals()) {
                replacements.put(original, selected);
            }
        }

        List<BuildTaskRecord.Target> rebound = new ArrayList<>(source.targets.size());
        for (BuildTaskRecord.Target target : source.targets) {
            ResourceLocation original = BuiltInRegistries.ITEM.getKey(target.item());
            ResourceLocation selected = replacements.get(original);
            // A zero-cost cell can still be the upper half of a selected door. Once a family is
            // rebound, every target carrying that original item must move together even though
            // only the lower half appears in the material ledger.
            if (selected == null || selected.equals(original)) {
                rebound.add(target);
                continue;
            }
            Item replacementItem = BuiltInRegistries.ITEM.get(selected);
            if (!(replacementItem instanceof BlockItem blockItem) || replacementItem == Items.AIR) {
                throw new IllegalArgumentException("selected build material is not a block item: " + selected);
            }
            BlockState replacementState = copyCompatibleState(
                    target.desiredState(), blockItem.getBlock().defaultBlockState());
            rebound.add(new BuildTaskRecord.Target(
                    replacementState,
                    replacementItem,
                    target.pos(),
                    blockItem.getBlock().getName().getString(),
                    target.facing(),
                    target.axis(),
                    target.topHalf(),
                    target.itemPlace()));
        }

        BuildTaskRecord result = new BuildTaskRecord(
                source.getToolCallId(), source.getDeadlineGameTime(),
                rebound, source.replaceMode, source.replaceExisting,
                source.consumeMaterials, source.allowPartial,
                source.blockEntityData, source.entities, source.replaceBlockEntities);
        result.cellNeeds(source.cellNeeds());
        result.droppedAtLoad(source.droppedAtLoad());
        result.semanticFacts(source.semanticFacts());
        result.traversabilityContract(source.traversabilityContract());
        return result;
    }

    private static List<ResourceLocation> alternatives(
            Kind kind, List<ResourceLocation> originals) {
        if (kind == Kind.EXACT) return originals;
        LinkedHashSet<ResourceLocation> candidates = new LinkedHashSet<>(originals);
        BuiltInRegistries.BLOCK.stream()
                .sorted(Comparator.comparing(
                        block -> BuiltInRegistries.BLOCK.getKey(block).toString()))
                .forEach(block -> {
                    if (candidates.size() >= MAX_ALTERNATIVES) return;
                    Item item = block.asItem();
                    if (!(item instanceof BlockItem) || item == Items.AIR) return;
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
                    if (rare(itemId) || kind(block, itemId) != kind) return;
                    candidates.add(itemId);
                });
        return candidates.stream().limit(MAX_ALTERNATIVES).toList();
    }

    private static Kind kind(Block block, ResourceLocation id) {
        String path = id.getPath();
        if (!ordinary(block)) return Kind.EXACT;
        if (block instanceof SlabBlock) return Kind.SLAB;
        // An iron-style door is not interchangeable with a house door: selecting it would make
        // the verified entrance depend on redstone that the semantic plan never requested.
        if (block instanceof DoorBlock door) {
            return door.type().canOpenByHand() ? Kind.DOOR : Kind.EXACT;
        }
        if (block instanceof IronBarsBlock || path.endsWith("_pane")) return Kind.PANE;
        if (block instanceof TransparentBlock && fullBlock(block)) return Kind.GLASS;
        if (path.endsWith("_fence")) return Kind.FENCE;
        if (path.endsWith("_wall")) return Kind.WALL;
        if (path.endsWith("_stairs")) return Kind.STAIRS;
        if (path.endsWith("_carpet")) return Kind.CARPET;
        if (frame(path)) return Kind.LOG;
        if (path.endsWith("_planks") && fullBlock(block)) return Kind.PLANKS;
        if (masonry(path) && fullBlock(block)) return Kind.MASONRY;
        return Kind.EXACT;
    }

    private static boolean fullBlock(Block block) {
        BlockState state = block.defaultBlockState();
        return ordinary(block)
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static boolean ordinary(Block block) {
        BlockState state = block.defaultBlockState();
        return block != Blocks.AIR && !state.hasBlockEntity()
                && !state.isSignalSource() && !state.hasAnalogOutputSignal()
                && state.getLightEmission() == 0;
    }

    private static boolean frame(String path) {
        return path.endsWith("_log") || path.endsWith("_wood")
                || path.endsWith("_stem") || path.endsWith("_hyphae")
                || path.startsWith("stripped_");
    }

    private static boolean masonry(String path) {
        if (path.contains("redstone") || path.contains("infested") || path.endsWith("_ore")) {
            return false;
        }
        return path.contains("stone") || path.contains("brick") || path.contains("cobble")
                || path.contains("deepslate") || path.contains("andesite")
                || path.contains("granite") || path.contains("diorite")
                || path.contains("terracotta") || path.endsWith("_concrete");
    }

    private static boolean rare(ResourceLocation id) {
        String path = id.getPath();
        return path.contains("diamond") || path.contains("emerald")
                || path.contains("netherite") || path.contains("ancient_debris")
                || path.startsWith("gold_") || path.contains("raw_gold");
    }

    private static BlockState copyCompatibleState(BlockState source, BlockState target) {
        BlockState result = target;
        for (Property<?> sourceProperty : source.getProperties()) {
            Property<?> targetProperty = target.getBlock().getStateDefinition()
                    .getProperty(sourceProperty.getName());
            if (targetProperty != null) {
                result = copyProperty(source, result, sourceProperty, targetProperty);
            }
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState copyProperty(
            BlockState source,
            BlockState target,
            Property sourceProperty,
            Property targetProperty) {
        Comparable sourceValue = source.getValue(sourceProperty);
        String serialized = sourceProperty.getName(sourceValue);
        Optional value = targetProperty.getValue(serialized);
        return value.isPresent()
                ? target.setValue(targetProperty, (Comparable) value.get())
                : target;
    }
}
