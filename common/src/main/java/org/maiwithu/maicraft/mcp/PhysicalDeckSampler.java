// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;
import org.maiwithu.maicraft.core.integration.physics.StructureDeckGeometry;

/**
 * 把共用甲板几何检查的结果整理成最多十二条观察信息，分别标出结构局部、存储区和世界位置；明确这些候选尚未证明路线或实际站稳。
 */
final class PhysicalDeckSampler {
    static JsonObject sample(BlockGetter world, Predicate<BlockPos> loaded, StructurePose pose,
                             AABB bounds, BlockPos origin, Vec3 focus, double width, double height) {
        var sample = StructureDeckGeometry.sample(world, loaded, pose, bounds, origin, focus, width, height);
        JsonObject out = new JsonObject();
        JsonArray rows = new JsonArray();
        for (var surface : sample.surfaces().stream().limit(12).toList()) {
            JsonObject row = new JsonObject();
            row.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(surface.state().getBlock()).toString());
            row.add("block_storage", StructurePerceptionJson.vector(Vec3.atLowerCornerOf(surface.block())));
            row.add("block_local", StructurePerceptionJson.vector(Vec3.atLowerCornerOf(surface.block().subtract(origin))));
            row.add("surface_storage", StructurePerceptionJson.vector(surface.storage()));
            row.add("surface_local", StructurePerceptionJson.vector(surface.storage().subtract(Vec3.atLowerCornerOf(origin))));
            row.add("surface_world", StructurePerceptionJson.vector(surface.world()));
            row.add("normal_world", StructurePerceptionJson.vector(surface.normal()));
            row.add("upright_feet_candidate", StructurePerceptionJson.vector(surface.feet()));
            row.add("support_block_collision_box", StructurePerceptionJson.box(surface.box()));
            row.addProperty("local_surface_area", surface.box().getXsize() * surface.box().getZsize());
            row.addProperty("upright_structure_body_clear", true);
            row.addProperty("standing_verified", false); row.addProperty("route_verified", false);
            rows.add(row);
        }
        out.addProperty("state", sample.state()); out.add("support_surface_candidates", rows);
        out.addProperty("read_only", true); out.addProperty("full_structure_observed", false);
        out.addProperty("column_radius", 8); out.addProperty("block_reads", sample.reads());
        out.addProperty("unknown_reads", sample.unknown()); out.addProperty("shape_errors", sample.shapeErrors());
        out.addProperty("steep_surfaces_skipped", sample.steep());
        out.addProperty("results_truncated", sample.truncated() || sample.surfaces().size() > 12);
        out.addProperty("budget_exhausted", sample.exhausted());
        out.addProperty("collision_evidence", "native block collision shapes; conservative inverse-transformed upright body box");
        out.addProperty("support_evidence", "world-Y upright candidate; actual native contact is required to verify boarding");
        return out;
    }
}
