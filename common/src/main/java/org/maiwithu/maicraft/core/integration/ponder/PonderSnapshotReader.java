// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

/**
 * 读取演示世界中的方块、显示区域、变换和实体，并限制数量与附加数据大小；这些是教程演示信息，不是玩家世界运行结果。
 */
final class PonderSnapshotReader {
    private static final int MAX_BLOCKS = 8192;
    private final Object scene;
    private final Level world;
    private final Class<?> sectionType;
    private final Map<Object, String> sectionIds = new IdentityHashMap<>();

    PonderSnapshotReader(Object scene, Level world) throws ReflectiveOperationException {
        this.scene = scene; this.world = world;
        sectionType = Class.forName("net.createmod.ponder.api.element.WorldSectionElement", true, scene.getClass().getClassLoader());
    }

    PonderStructureSnapshot read() throws ReflectiveOperationException {
        Object rawBlocks = PonderInstructionReader.field(world, "blocks");
        if (!(rawBlocks instanceof Map<?, ?> map)) throw new IllegalStateException("Ponder block map unavailable");
        if (map.size() > MAX_BLOCKS) throw new IllegalStateException("Ponder snapshot exceeds 8192 source blocks");
        List<PonderStructureSnapshot.Block> blocks = new ArrayList<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof BlockPos position) || !(entry.getValue() instanceof BlockState state))
                throw new IllegalStateException("Unsupported Ponder block map");
            if (state.isAir()) continue;
            Map<String, String> properties = new LinkedHashMap<>();
            state.getValues().forEach((property, value) -> properties.put(property.getName(), propertyValue(property, value)));
            var blockEntity = world.getBlockEntity(position);
            String nbt = blockEntity == null ? null : blockEntity.saveWithFullMetadata(world.registryAccess()).toString();
            if (nbt != null && nbt.length() > 65536) throw new IllegalStateException("Ponder block entity evidence exceeds 64 KiB");
            blocks.add(new PonderStructureSnapshot.Block(position, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties, nbt));
        }
        blocks.sort(Comparator.comparing(PonderStructureSnapshot.Block::position));
        List<PonderStructureSnapshot.Section> sections = new ArrayList<>();
        Object rawElements = scene.getClass().getMethod("getElements").invoke(scene);
        for (Object element : (Iterable<?>) rawElements) {
            if (!sectionType.isInstance(element)) continue;
            String id = sectionIds.computeIfAbsent(element, ignored -> "section_" + sectionIds.size());
            Object selection = PonderInstructionReader.field(element, "section"); List<BlockPos> positions = new ArrayList<>();
            if (selection != null) for (Object position : (Iterable<?>) selection) {
                if (positions.size() >= MAX_BLOCKS) throw new IllegalStateException("Ponder section exceeds 8192 positions");
                positions.add(((BlockPos) position).immutable());
            }
            positions.sort(BlockPos::compareTo);
            Object fade = PonderInstructionReader.field(element, "fade");
            double fraction = ((Number) fade.getClass().getMethod("getValue", float.class).invoke(fade, 1f)).doubleValue();
            sections.add(new PonderStructureSnapshot.Section(id, positions,
                    (boolean) sectionType.getMethod("isVisible").invoke(element),
                    (Vec3) sectionType.getMethod("getAnimatedOffset").invoke(element),
                    (Vec3) sectionType.getMethod("getAnimatedRotation").invoke(element),
                    (Vec3) PonderInstructionReader.field(element, "centerOfRotation"),
                    (Vec3) PonderInstructionReader.field(element, "stabilizationAnchor"), fraction,
                    (Vec3) PonderInstructionReader.field(element, "fadeVec")));
        }
        sections.sort(Comparator.comparing(PonderStructureSnapshot.Section::id));
        JsonArray entities = new JsonArray();
        for (Object value : (Iterable<?>) world.getClass().getMethod("getEntityList").invoke(world)) {
            if (entities.size() >= 256) throw new IllegalStateException("Ponder entity evidence exceeds 256 entities");
            Entity entity = (Entity) value; CompoundTag tag = new CompoundTag();
            if (!entity.save(tag)) continue;
            if (tag.toString().length() > 65536) throw new IllegalStateException("Ponder entity evidence exceeds 64 KiB");
            JsonObject row = new JsonObject(); row.addProperty("entity_id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            row.add("position", PonderStructureSnapshot.vector(entity.position())); row.addProperty("observed_nbt", tag.toString()); entities.add(row);
        }
        return new PonderStructureSnapshot(blocks, sections, entities);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propertyValue(Property property, Comparable value) { return property.getName(value); }
}
