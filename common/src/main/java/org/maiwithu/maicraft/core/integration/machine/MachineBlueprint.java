// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.build.BuildStates;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.build.ReplaceMode;

/** Compile a relative machine design into the existing receipt-driven native construction task. */
public final class MachineBlueprint {
    public static final int MAX_BLOCKS = MachineBlueprintSpec.MAX_BLOCKS;

    private MachineBlueprint() {}

    /** Plan-time wire validation; live compilation subsequently enforces the actual surveyed radius. */
    public static void validateWire(JsonObject blueprint) {
        MachineBlueprintSpec.parse(blueprint, MachineSurvey.MAX_RADIUS);
    }

    /**
     * Read-only compilation; registry, geometry bounds and replacement policies are validated here.
     * The resulting native task proves the entire plan's placement gestures and material availability
     * before its first mutation. No block-entity NBT or server-side block writes are accepted.
     */
    public static Compiled compile(LocalPlayer player, BlockPos anchor, int radius,
            JsonObject blueprint, boolean replaceExisting, boolean replaceBlockEntities) {
        if (player == null || anchor == null) throw new IllegalArgumentException("player and machine anchor are required");
        if (replaceBlockEntities && !replaceExisting) {
            throw new IllegalArgumentException("replace_block_entities requires explicit replace_existing");
        }
        List<MachineBlueprintSpec.Cell> specs = MachineBlueprintSpec.parse(blueprint, radius);
        JsonObject survey = MachineSurvey.inspect(player, anchor, radius);
        if (!survey.get("structure_complete").getAsBoolean()) {
            throw new IllegalArgumentException("machine blueprint requires a fully loaded, untruncated block structure; shrink the region or inspect again");
        }
        Level level = player.level();
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        Map<Long, BlockState> before = new LinkedHashMap<>();
        Map<Long, BuildTaskRecord.Target> byPosition = new LinkedHashMap<>();
        Map<String, Integer> materials = new LinkedHashMap<>();
        int alreadyMatching = 0, replacements = 0, removals = 0, propertyCount = 0;
        for (MachineBlueprintSpec.Cell spec : specs) {
            BlockPos pos = anchor.offset(spec.offset().x(), spec.offset().y(), spec.offset().z());
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.isLoaded(pos)) {
                throw new IllegalArgumentException("blueprint cell is outside the loaded buildable world: " + spec.offset());
            }
            BlockState desired = resolveState(spec.blockId(), spec.properties());
            Block block = desired.getBlock();
            propertyCount += spec.properties().size();
            boolean removal = desired.isAir();
            if (removal && block != Blocks.AIR) {
                throw new IllegalArgumentException("use minecraft:air to express empty blueprint space");
            }
            Item item = removal ? Items.AIR : block.asItem();
            BuildTaskRecord.Target target = new BuildTaskRecord.Target(desired, item, pos,
                    spec.blockId(), null, null, null, false, spec.properties().keySet(), true);
            BlockState current = level.getBlockState(pos);
            before.put(pos.asLong(), current);
            boolean matching = target.matches(current);
            if (matching) alreadyMatching++;
            else {
                // Existing matching components are reusable even when their construction needs a
                // special adapter. Native-effect checks apply to actual proposed mutations only.
                if (!removal && (!(item instanceof BlockItem blockItem) || blockItem.getBlock() != block
                        || block instanceof LiquidBlock)) {
                    throw new IllegalArgumentException("unsupported_native_block_item: " + spec.blockId()
                            + " requires a separate native item/part/fluid assembly operation");
                }
                requireModeledEffects(block);
                if (removal && !replaceExisting) throw new IllegalArgumentException("clearing an occupied target requires replace_existing=true");
                requireModeledEffects(current.getBlock());
                if (current.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        || current.hasProperty(BlockStateProperties.BED_PART)) {
                    throw new IllegalArgumentException("unsupported_multicell_removal: replacing an existing paired block needs a dedicated whole-effect removal plan");
                }
                if (!current.isAir()) {
                    if (!replaceExisting) throw new IllegalArgumentException("existing block is protected at " + spec.offset() + "; replacement policy is preserve");
                    if (current.hasBlockEntity() && !replaceBlockEntities) {
                        throw new IllegalArgumentException("existing block entity is protected at " + spec.offset()
                                + "; replace_block_entities must be explicitly enabled");
                    }
                    if (current.getDestroySpeed(level, pos) < 0.0F) throw new IllegalArgumentException("unbreakable blueprint obstruction at " + spec.offset());
                    replacements++;
                }
                if (removal) removals++;
                else materials.merge(BuiltInRegistries.ITEM.getKey(item).toString(), target.materialCount(), Integer::sum);
            }
            targets.add(target);
            byPosition.put(pos.asLong(), target);
        }
        // Generated vanilla halves must be explicitly present, so no implicit mutation escapes the marked region.
        for (BuildTaskRecord.Target target : targets) validateGeneratedCells(target, byPosition);
        List<BlockPos> protectedCells = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(anchor.offset(-radius, -radius, -radius), anchor.offset(radius, radius, radius))) {
            BuildTaskRecord.Target target = byPosition.get(pos.asLong());
            if (!level.getBlockState(pos).isAir() && (target == null || target.matches(level.getBlockState(pos)))) {
                protectedCells.add(pos.immutable());
            }
        }
        JsonObject report = new JsonObject();
        report.addProperty("blueprint_compiled", true);
        report.addProperty("blueprint_block_count", targets.size());
        report.addProperty("already_matching", alreadyMatching);
        report.addProperty("blocks_to_build", targets.size() - alreadyMatching - removals);
        report.addProperty("existing_blocks_to_replace_or_remove", replacements);
        report.addProperty("explicit_removals", removals);
        report.addProperty("explicit_properties_to_verify", propertyCount);
        report.addProperty("replace_existing", replaceExisting);
        report.addProperty("replace_block_entities", replaceBlockEntities);
        report.addProperty("native_placement_and_material_preflight_pending", true);
        report.addProperty("machine_geometry_verified", false);
        report.addProperty("machine_production_verified", false);
        report.addProperty("verification_contract", "registered block placement plus explicit requested properties; production, inventory, recipe and network configuration require separate evidence");
        JsonArray materialRequests = new JsonArray();
        materials.forEach((id, count) -> {
            if (count <= 0) return;
            JsonObject request = new JsonObject();
            request.addProperty("item_id", id); request.addProperty("count", count);
            materialRequests.add(request);
        });
        report.add("material_requests", materialRequests);
        return new Compiled(level, anchor.immutable(), radius, survey.get("structure_fingerprint").getAsString(),
                targets, before, protectedCells, replaceExisting, replaceBlockEntities, report);
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
        T parsed = property.getValue(value).orElseThrow(() -> new IllegalArgumentException(
                "invalid value " + value + " for property " + property.getName()));
        return state.setValue(property, parsed);
    }

    /** Registry/state compilation is separately testable without constructing a player or a world. */
    static BlockState resolveState(String blockId, Map<String, String> properties) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("unknown block registry ID: " + blockId);
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        BlockState desired = block.defaultBlockState();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException(blockId + " has no property " + entry.getKey());
            desired = withProperty(desired, property, entry.getValue());
        }
        BlockState normalized = BuildStates.normalize(desired);
        boolean changedExplicitRequest = normalized.getBlock() != block;
        if (!changedExplicitRequest) for (String name : properties.keySet()) {
            Property<?> property = block.getStateDefinition().getProperty(name);
            changedExplicitRequest |= !desired.getValue(property).equals(normalized.getValue(property));
        }
        if (changedExplicitRequest) {
            throw new IllegalArgumentException("unsupported copied runtime state for " + blockId
                    + "; describe a physically placeable initial state, not stored contents or generated runtime values");
        }
        return normalized;
    }

    private static void validateGeneratedCells(BuildTaskRecord.Target target, Map<Long, BuildTaskRecord.Target> targets) {
        BlockState desired = target.desiredState();
        BlockPos other = null;
        BlockState expected = null;
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            boolean lower = desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
            other = lower ? target.pos().above() : target.pos().below();
            expected = desired.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, lower ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        } else if (desired.hasProperty(BlockStateProperties.BED_PART) && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            boolean foot = desired.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT;
            var facing = desired.getValue(BlockStateProperties.HORIZONTAL_FACING);
            other = target.pos().relative(foot ? facing : facing.getOpposite());
            expected = desired.setValue(BlockStateProperties.BED_PART, foot ? BedPart.HEAD : BedPart.FOOT);
        }
        if (other != null) {
            BuildTaskRecord.Target declared = targets.get(other.asLong());
            if (declared == null || !declared.desiredState().equals(expected)) {
                throw new IllegalArgumentException("generated block half must be explicitly included with matching state: " + target.label());
            }
        }
    }

    /** Mekanism bounding machines alter extra cells that the generic block-item builder cannot receipt. */
    static void requireModeledEffects(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String namespace = id.getNamespace();
        if (namespace.equals("create") && (id.getPath().equals("large_water_wheel")
                || id.getPath().equals("water_wheel_structural") || id.getPath().equals("belt"))) {
            throw new IllegalArgumentException("unsupported_multicell_placement: " + id
                    + " has linked structural cells and requires a dedicated native assembly plan");
        }
        if (!namespace.equals("mekanism") && !namespace.equals("mekanismgenerators")
                && !namespace.equals("mekanismadditions")) return;
        try {
            ClassLoader loader = MachineBlueprint.class.getClassLoader();
            Class<?> attribute = Class.forName("mekanism.common.block.attribute.Attribute", false, loader);
            Class<?> bounding = Class.forName("mekanism.common.block.attribute.AttributeHasBounding", false, loader);
            Method has = attribute.getMethod("has", Block.class, Class.class);
            Object answer = has.invoke(null, block, bounding);
            if (!(answer instanceof Boolean present)) throw new IllegalArgumentException("unknown Mekanism bounding effect metadata");
            if (present) throw new IllegalArgumentException("unsupported_multicell_placement: " + BuiltInRegistries.BLOCK.getKey(block)
                    + " creates/removes bounding blocks and requires a dedicated native assembly plan");
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            throw new IllegalArgumentException("cannot verify Mekanism bounding-cell effects with the installed optional API", unavailable);
        }
    }

    public static final class Compiled {
        private final Level observedLevel;
        private final BlockPos anchor;
        private final int radius;
        private final String fingerprint;
        private final List<BuildTaskRecord.Target> targets;
        private final Map<Long, BlockState> before;
        private final List<BlockPos> protectedCells;
        private final boolean replaceExisting, replaceBlockEntities;
        private final JsonObject report;

        private Compiled(Level observedLevel, BlockPos anchor, int radius, String fingerprint,
                List<BuildTaskRecord.Target> targets, Map<Long, BlockState> before, List<BlockPos> protectedCells,
                boolean replaceExisting, boolean replaceBlockEntities, JsonObject report) {
            this.observedLevel = observedLevel; this.anchor = anchor; this.radius = radius;
            this.fingerprint = fingerprint; this.targets = List.copyOf(targets); this.before = Map.copyOf(before);
            this.protectedCells = List.copyOf(protectedCells);
            this.replaceExisting = replaceExisting; this.replaceBlockEntities = replaceBlockEntities;
            this.report = report.deepCopy();
        }

        public JsonObject report() { return report.deepCopy(); }
        public int blockCount() { return targets.size(); }

        public BuildTaskRecord toTask(String toolCallId, long deadlineGameTime) {
            return toTask(toolCallId, deadlineGameTime, true);
        }

        public BuildTaskRecord toTask(String toolCallId, long deadlineGameTime, boolean consumeMaterials) {
            BuildTaskRecord record = new BuildTaskRecord(toolCallId, deadlineGameTime, targets,
                    replaceExisting ? ReplaceMode.REPLACE_EMPTY : ReplaceMode.DONT_REPLACE,
                    replaceExisting, consumeMaterials, false, Map.of(), List.of(), replaceBlockEntities);
            Map<Long, BlockState> expected = new LinkedHashMap<>(before);
            record.executionGuards(protectedCells,
                    player -> player.level() == observedLevel && fingerprint.equals(MachineSurvey.fingerprint(player, anchor, radius)),
                    (player, pos) -> player.level() == observedLevel && player.level().isLoaded(pos)
                            && player.level().getBlockState(pos).equals(expected.get(pos.asLong())),
                    (player, pos) -> {
                        if (player.level() == observedLevel && player.level().isLoaded(pos) && expected.containsKey(pos.asLong())) {
                            expected.put(pos.asLong(), player.level().getBlockState(pos));
                        }
                    });
            record.semanticFacts(Map.of("machine_geometry_verified", true, "machine_production_verified", false,
                    "machine_blueprint_cells", targets.size(), "machine_explicit_state_properties_verified", true));
            return record;
        }
    }
}
