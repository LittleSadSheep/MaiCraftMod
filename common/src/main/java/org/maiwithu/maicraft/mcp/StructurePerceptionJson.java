// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

final class StructurePerceptionJson {
    private StructurePerceptionJson() {}
    static JsonObject vector(Vec3 v) {
        JsonObject out = new JsonObject();
        out.addProperty("x", v.x); out.addProperty("y", v.y); out.addProperty("z", v.z);
        return out;
    }
    static JsonArray box(AABB b) {
        JsonArray out = new JsonArray();
        for (double value : new double[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ}) out.add(value);
        return out;
    }
    static JsonObject pose(StructurePose p) {
        JsonObject out = new JsonObject();
        out.add("position", vector(p.position()));
        out.add("rotation_point_storage", vector(p.pivot()));
        out.add("scale", vector(p.scale()));
        JsonArray q = new JsonArray();
        q.add(p.orientationX()); q.add(p.orientationY()); q.add(p.orientationZ()); q.add(p.orientationW());
        out.add("orientation_xyzw", q);
        out.addProperty("transform", "world = orientation * ((storage - rotation_point_storage) * scale) + position");
        return out;
    }
    static JsonObject state(String state, String reason) {
        JsonObject out = new JsonObject();
        out.addProperty("state", state); out.addProperty("reason", reason); return out;
    }
}
