// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 将展开后的轴和带轮公开为可引用的计划端口；端口存在不表示世界中已装好或正在供电。 */
public final class MachineAssemblyPorts {
    private MachineAssemblyPorts() {}
    public static Map<BlockPos, JsonObject> finalBlocks(JsonObject blueprint) {
        Map<BlockPos, JsonObject> authored = MachineAssemblyDocument.blocks(blueprint);
        Map<BlockPos, JsonObject> result = new LinkedHashMap<>(authored);
        for (var span : MachineAssemblyDocument.belts(blueprint)) for (BlockPos at : span.cells()) {
            JsonObject cell = new JsonObject(), properties = new JsonObject();
            boolean pulley = authored.containsKey(at) && authored.get(at).get("block_id").getAsString().equals("create:shaft");
            cell.add("offset", MachineAssemblyDocument.json(at)); cell.addProperty("block_id", "create:belt");
            span.properties(at, pulley).forEach(properties::addProperty); cell.add("properties", properties); result.put(at, cell);
        }
        return result;
    }
    public static JsonArray describe(JsonObject blueprint) {
        Map<BlockPos, JsonObject> finals = finalBlocks(blueprint);
        JsonArray result = new JsonArray();
        // 端点和指定的中间轴都变成可接入的带轮；普通中间带节没有裸露轴端，不能作为动力端口。
        for (var entry : finals.entrySet()) {
            JsonObject cell = entry.getValue(); String id = cell.get("block_id").getAsString();
            Map<String, String> properties = MachineAssemblyDocument.properties(cell);
            String axis = properties.get("axis"), role = "shaft";
            if (id.equals("create:belt")) {
                role = properties.getOrDefault("part", "unknown");
                Direction facing = Direction.byName(properties.getOrDefault("facing", ""));
                if (!List.of("start", "end", "pulley").contains(role) || facing == null || facing.getAxis() == Direction.Axis.Y) continue;
                axis = properties.getOrDefault("slope", "unknown").equals("sideways") ? "y"
                        : facing.getClockWise().getAxis().getName();
            } else if (!id.equals("create:shaft")) continue;
            if (axis == null) continue;
            for (Direction face : Direction.values()) if (face.getAxis().getName().equals(axis)) {
                BlockPos at = entry.getKey(); JsonObject port = new JsonObject();
                port.addProperty("id", id(at, face)); port.addProperty("medium", "kinetic");
                port.add("offset", MachineAssemblyDocument.json(at)); port.addProperty("face", face.getName());
                port.addProperty("axis", axis); port.addProperty("block_id", id); port.addProperty("role", role);
                port.addProperty("evidence", "compiled_blueprint"); port.addProperty("installed", (Boolean) null);
                port.addProperty("powered", (Boolean) null);
                JsonObject neighbor = finals.get(at.relative(face));
                if (neighbor != null) port.addProperty("planned_neighbor", neighbor.get("block_id").getAsString());
                result.add(port);
            }
        }
        return result;
    }
    public static String id(BlockPos at, Direction face) {
        // 端口引用绑定锚点内的位置和接入面，添加另一段带或调整数组顺序不会让既有引用漂移。
        String address = at.getX() + "," + at.getY() + "," + at.getZ() + ":" + face.getName();
        return "kinetic_" + UUID.nameUUIDFromBytes(address.getBytes(StandardCharsets.UTF_8));
    }
    public static JsonArray bindInputs(JsonObject blueprint, JsonArray inputs) {
        Map<String, JsonObject> ports = new LinkedHashMap<>();
        for (var raw : describe(blueprint)) ports.put(raw.getAsJsonObject().get("id").getAsString(), raw.getAsJsonObject());
        Map<BlockPos, JsonObject> finals = finalBlocks(blueprint); JsonArray result = new JsonArray();
        for (var raw : inputs) {
            if (!raw.isJsonObject()) throw new IllegalArgumentException("external input must be an object");
            JsonObject row = raw.getAsJsonObject().deepCopy();
            if (row.has("port")) {
                // 模型引用已公开的端口即可选择接入点，不允许再附加矛盾坐标或把动力端口当成物品库存。
                for (String key : row.keySet()) if (!Set.of("id", "medium", "port", "minimum_rpm", "reason").contains(key))
                    throw new IllegalArgumentException("port input cannot override " + key);
                if (!row.get("port").isJsonPrimitive() || !row.getAsJsonPrimitive("port").isString()) throw new IllegalArgumentException("power port must be an ID");
                JsonObject port = ports.get(row.get("port").getAsString());
                if (port == null) throw new IllegalArgumentException("unknown_power_port: " + row.get("port"));
                if (!row.has("medium") || !row.get("medium").isJsonPrimitive() || !row.getAsJsonPrimitive("medium").isString()
                        || !row.get("medium").getAsString().equals("kinetic")) throw new IllegalArgumentException("power port requires kinetic medium");
                row.remove("port");
                for (String key : List.of("offset", "face", "block_id")) row.add(key, port.get(key).deepCopy());
            } else if (row.has("block_id") && row.get("block_id").isJsonPrimitive()
                    && row.get("block_id").getAsString().equals("create:shaft") && row.has("offset")) {
                // 兼容原来按准备轴声明的接口：同格安装成带轮后，持久目录必须绑定真实的最终方块。
                JsonObject actual = finals.get(MachineAssemblyDocument.position(row.get("offset")));
                if (actual != null && actual.get("block_id").getAsString().equals("create:belt")) row.addProperty("block_id", "create:belt");
            }
            result.add(row);
        }
        return result;
    }
    public static boolean hasBeltPort(JsonObject blueprint, BlockPos at, Direction face) {
        for (var raw : describe(blueprint)) {
            JsonObject port = raw.getAsJsonObject();
            if (port.get("id").getAsString().equals(id(at, face)) && port.get("block_id").getAsString().equals("create:belt")) return true;
        }
        return false;
    }
}
