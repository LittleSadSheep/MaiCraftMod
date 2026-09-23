// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.CreateBeltGeometry;

/** 从已选择的带段推导准备轴；机器数量、产物、物流路线仍由蓝图作者决定。 */
public final class MachineBeltAssembly {
    private MachineBeltAssembly() {}

    public static void prepareShafts(JsonObject blueprint, JsonArray installations, int radius) {
        Map<BlockPos, JsonObject> blocks = MachineAssemblyDocument.blocks(blueprint);
        Set<BlockPos> parts = new HashSet<>();
        for (var raw : blueprint.getAsJsonArray("blocks")) if (raw.getAsJsonObject().has("part"))
            parts.add(MachineAssemblyDocument.position(raw.getAsJsonObject().get("offset")));
        for (var raw : installations) {
            if (!raw.isJsonObject()) throw bad("installation must be an object");
            JsonObject entry = raw.getAsJsonObject();
            if (!entry.has("type") || !entry.get("type").isJsonPrimitive() || !entry.getAsJsonPrimitive("type").isString()
                    || !entry.get("type").getAsString().equals("create:belt"))
                throw bad("unsupported_native_installation; read the machine assembly capability contract");
            BlockPos first = MachineAssemblyDocument.position(entry.get("first"), radius);
            BlockPos second = MachineAssemblyDocument.position(entry.get("second"), radius);
            Direction.Axis authored = axis(blocks.get(first)), other = axis(blocks.get(second));
            if (authored != null && other != null && authored != other) throw bad("belt_endpoint_axes_mismatch");
            Direction.Axis chosen = authored != null ? authored : other != null ? other : inferredAxis(first, second);
            // 先检查带长与轴向，再补两端准备轴；作者写错的显式朝向不能被自动展开偷偷覆盖。
            CreateBeltGeometry.between(first, second, chosen, Math.min(2 * radius + 1, MachinePlanningBudget.current().maxTargets()));
            shaft(blueprint, blocks, parts, first, chosen);
            shaft(blueprint, blocks, parts, second, chosen);
        }
    }

    public static Direction.Axis inferredAxis(BlockPos first, BlockPos second) {
        int x = second.getX() - first.getX(), z = second.getZ() - first.getZ();
        if (x != 0 && z == 0) return Direction.Axis.Z;
        if (z != 0 && x == 0) return Direction.Axis.X;
        throw bad("belt_axis_requires_straight_route; vertical or sideways installations need explicit endpoint axes");
    }
    private static Direction.Axis axis(JsonObject cell) {
        if (cell == null || cell.get("block_id").getAsString().equals("minecraft:air")) return null;
        if (!cell.get("block_id").getAsString().equals("create:shaft")) throw bad("belt_endpoint_conflicts_with_authored_block");
        String value = MachineAssemblyDocument.properties(cell).get("axis");
        if (value == null) throw bad("belt_shaft_axis_must_be_explicit; omit the endpoint block to derive it");
        for (Direction.Axis axis : Direction.Axis.values()) if (axis.getName().equals(value)) return axis;
        throw bad("belt_shaft_axis_must_be_explicit");
    }
    private static void shaft(JsonObject blueprint, Map<BlockPos, JsonObject> blocks, Set<BlockPos> parts,
            BlockPos at, Direction.Axis axis) {
        if (parts.contains(at)) throw bad("native_installation_overlap");
        JsonObject existing = blocks.get(at);
        if (existing != null && existing.get("block_id").getAsString().equals("create:shaft")) return;
        // 显式空气格授权清障，但最终端点必然是带轮；将该格替换为准确的准备轴并计入统一材料清单。
        JsonObject cell = existing == null ? new JsonObject() : existing;
        cell.add("offset", MachineAssemblyDocument.json(at)); cell.addProperty("block_id", "create:shaft");
        JsonObject state = new JsonObject(); state.addProperty("axis", axis.getName()); state.addProperty("waterlogged", "false");
        cell.add("properties", state);
        if (existing == null) {
            if (blueprint.getAsJsonArray("blocks").size() >= MachinePlanningBudget.current().maxTargets()) throw bad("native_installation_target_budget_exceeded");
            blueprint.getAsJsonArray("blocks").add(cell); blocks.put(at, cell);
        }
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
