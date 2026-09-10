// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 保存一刻演示状态，并把可见、无旋转、整格平移且完全显现的区域转换成方块蓝图；不能处理的变换仍保存在说明数据中。
 */
public record PonderStructureSnapshot(List<Block> blocks, List<Section> sections, JsonArray entities) {
    public record Block(BlockPos position, String id, Map<String, String> properties, String observedNbt) {
        public Block { position = position.immutable(); properties = Map.copyOf(properties); }
        String geometry() { return id + new java.util.TreeMap<>(properties); }
    }
    public record Section(String id, List<BlockPos> positions, boolean visible, Vec3 offset, Vec3 rotation,
                          Vec3 pivot, Vec3 stabilizationAnchor, double fade, Vec3 fadeVector) {
        public Section { positions = List.copyOf(positions); }
        boolean projectable() {
            return rotation.lengthSqr() < 1e-12 && integer(offset.x) && integer(offset.y) && integer(offset.z)
                    && Math.abs(fade - 1) < 1e-6;
        }
    }
    public PonderStructureSnapshot { blocks = List.copyOf(blocks); sections = List.copyOf(sections); entities = entities.deepCopy(); }

    /** Ignore mere motion, NBT animation counters and fades when deciding to preserve a destructive predecessor. */
    public boolean losesStructureTo(PonderStructureSnapshot after) {
        Map<String, String> beforeGeometry = visibleGeometry(), afterGeometry = after.visibleGeometry();
        if (beforeGeometry.entrySet().stream().anyMatch(e -> !e.getValue().equals(afterGeometry.get(e.getKey())))) return true;
        for (Section before : sections) if (before.visible() && before.fade() >= 1 - 1e-6)
            for (Section next : after.sections()) if (before.id().equals(next.id()) && next.fade() < 1 - 1e-6) return true;
        return false;
    }

    private Map<String, String> visibleGeometry() {
        Map<BlockPos, Block> byPosition = new LinkedHashMap<>(); blocks.forEach(b -> byPosition.put(b.position(), b));
        Map<String, String> geometry = new LinkedHashMap<>();
        for (Section section : sections) if (section.visible()) for (BlockPos position : section.positions()) {
            Block block = byPosition.get(position);
            if (block != null) geometry.put(position.toShortString(), block.geometry());
        }
        return geometry;
    }

    public JsonObject blueprint(PonderAccess.Entry entry, String sceneId) {
        JsonObject result = new JsonObject(); result.addProperty("schema_version", 1);
        JsonArray projected = new JsonArray(), raw = new JsonArray(), sectionData = new JsonArray(), warnings = new JsonArray();
        Map<BlockPos, Block> byPosition = new LinkedHashMap<>();
        blocks.forEach(block -> { byPosition.put(block.position(), block); raw.add(blockJson(block, block.position(), true)); });
        Map<BlockPos, JsonObject> placed = new LinkedHashMap<>(); boolean complete = true;
        for (Section section : sections) {
            JsonObject row = new JsonObject(); row.addProperty("id", section.id()); row.addProperty("visible", section.visible());
            JsonArray membership = new JsonArray(); section.positions().forEach(pos -> membership.add(vector(pos)));
            row.add("source_positions", membership); row.add("offset", vector(section.offset()));
            row.add("rotation_degrees_xyz", vector(section.rotation())); row.add("rotation_pivot", vector(section.pivot()));
            if (section.stabilizationAnchor() != null) row.add("stabilization_anchor", vector(section.stabilizationAnchor()));
            row.addProperty("fade", section.fade());
            if (section.fadeVector() != null) row.add("fade_vector", vector(section.fadeVector()));
            sectionData.add(row);
            if (!section.visible()) continue;
            // 当前尚未先检查这片区域是否真有方块；空区域的旋转或淡入淡出也会让整份蓝图被标为不完整。
            if (!section.projectable()) {
                complete = false; warnings.add("Section " + section.id() + " is rotated, fading or off-grid; source geometry retained in evidence only"); continue;
            }
            BlockPos translation = new BlockPos((int) Math.rint(section.offset().x), (int) Math.rint(section.offset().y), (int) Math.rint(section.offset().z));
            for (BlockPos position : section.positions()) {
                Block block = byPosition.get(position); if (block == null) continue;
                BlockPos target = position.offset(translation); JsonObject value = blockJson(block, target, false);
                JsonObject previous = placed.putIfAbsent(target, value);
                if (previous != null && !previous.equals(value)) { complete = false; warnings.add("Conflicting blocks at " + target.toShortString()); }
            }
        }
        placed.values().forEach(projected::add); result.add("blocks", projected);
        JsonObject evidence = new JsonObject(); evidence.addProperty("source", "ponder"); evidence.addProperty("scene_id", sceneId);
        evidence.addProperty("component", entry.component()); evidence.addProperty("schematic", entry.schematic());
        evidence.addProperty("projection_complete", complete); evidence.add("source_blocks", raw); evidence.add("sections", sectionData);
        evidence.add("observed_entities", entities.deepCopy()); evidence.add("warnings", warnings);
        // 转速、物品和实体附加数据只作观察记录，不自动变成施工配置或生产成功的证明。
        evidence.addProperty("interpretation", "Demonstration evidence. Raw NBT and entities are observations, not desired build configuration. Speed, inventory, output and attachment behavior require separate use/modification steps. Camera transforms are excluded.");
        result.add("evidence", evidence); return result;
    }

    private static JsonObject blockJson(Block block, BlockPos position, boolean observed) {
        JsonObject result = new JsonObject(); result.add("offset", vector(position)); result.addProperty("block_id", block.id());
        JsonObject properties = new JsonObject(); new java.util.TreeMap<>(block.properties()).forEach(properties::addProperty);
        result.add("properties", properties);
        if (observed && block.observedNbt() != null) result.addProperty("observed_nbt", block.observedNbt());
        return result;
    }
    static JsonArray vector(BlockPos value) { JsonArray result = new JsonArray(); result.add(value.getX()); result.add(value.getY()); result.add(value.getZ()); return result; }
    static JsonArray vector(Vec3 value) { JsonArray result = new JsonArray(); result.add(value.x); result.add(value.y); result.add(value.z); return result; }
    private static boolean integer(double value) { return Double.isFinite(value) && Math.abs(value - Math.rint(value)) < 1e-6 && Math.abs(value) <= 8192; }
}
