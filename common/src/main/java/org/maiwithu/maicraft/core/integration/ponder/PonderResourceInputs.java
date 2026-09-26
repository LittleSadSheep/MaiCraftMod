// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineSurvivalMaterials;

/** 教程资源源头与普通库存分别提取；容量和方块名称都不能证明它属于机器内部结构。 */
final class PonderResourceInputs {
    private PonderResourceInputs() {}

    /** 普通库存可能供料、收集或缓存；先从自动建材投影中拿出，由设计者结合旁白判定边界角色。 */
    static JsonArray storageBoundaries(Map<BlockPos, JsonObject> placed, String component) {
        JsonArray candidates = new JsonArray();
        var iterator = placed.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); JsonObject block = entry.getValue();
            String id = block.get("block_id").getAsString(), medium = storageMedium(id);
            // 正在讲解库存本身时保留教学主体；加工教程中的周边库存不自动升级为必需设备。
            if (medium == null || id.equals(component)) continue;
            JsonObject candidate = new JsonObject();
            candidate.addProperty("medium", medium); candidate.addProperty("role", "unresolved_storage_boundary");
            candidate.addProperty("role_resolution_required", true); candidate.addProperty("direction", "unknown");
            candidate.add("demonstration_storage", block.deepCopy());
            JsonArray roles = new JsonArray();
            for (String role : new String[]{"external_input", "external_output", "internal_buffer"}) roles.add(role);
            candidate.add("possible_roles", roles);
            candidate.addProperty("next_step", "Read scene narration and actual transfer interfaces. Supply-only storage represents resource IN: bind the real receiver and select available supply. Retain authored storage only when its buffer/collection function is required. Neither item count nor adjacency proves flow; no particular vault, chest or transporter is mandatory.");
            candidates.add(candidate); iterator.remove();
        }
        return candidates;
    }

    /** 有限适配表只识别储存介质，不按 ID 推断输入、输出或缓存；未知附属组件仍保留原始几何。 */
    private static String storageMedium(String id) {
        return switch (id) {
            case "create:item_vault", "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box" -> "items";
            case "create:fluid_tank" -> "fluids";
            default -> id.matches("minecraft:(white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black)_shulker_box") ? "items" : null;
        };
    }

    static JsonArray project(Map<BlockPos, JsonObject> placed) {
        JsonArray inputs = new JsonArray();
        for (var entry : placed.entrySet()) {
            JsonObject source = entry.getValue();
            String medium = MachineSurvivalMaterials.demonstrationSupplyMedium(source.get("block_id").getAsString());
            if (medium == null) continue;
            JsonObject input = new JsonObject();
            input.addProperty("medium", medium); input.addProperty("direction", "in");
            input.addProperty("role", "demonstration_resource_input");
            input.add("demonstration_source", source.deepCopy());
            input.addProperty("binding_required", true); input.addProperty("place_demonstration_source", false);
            JsonArray neighbors = new JsonArray();
            // 邻接只是定位线索，不能从画面推断轴面或管线已经连通，更不能把演示转速当作现场应力余量。
            for (Direction face : Direction.values()) {
                JsonObject neighbor = placed.get(entry.getKey().relative(face));
                if (neighbor == null || MachineSurvivalMaterials.demonstrationSupplyMedium(neighbor.get("block_id").getAsString()) != null) continue;
                var candidate = neighbor.deepCopy(); candidate.addProperty("interface_verified", false);
                candidate.addProperty("face_toward_demonstration_source", face.getOpposite().getName());
                neighbors.add(candidate);
            }
            input.add("adjacent_receiver_candidates", neighbors);
            input.addProperty("next_step", "Inspect retained receiver interfaces and bind a supported external_inputs port in an authored blueprint. Unsupported media need an ordinary process prerequisite. Prefer an existing world network; demonstration speed and supply are not real-world evidence.");
            inputs.add(input);
        }
        // 演示源仅留在证据中；直接照抄 blocks 的调用方也不会把无限资源发生器当作需要放置的结构。
        placed.entrySet().removeIf(entry -> MachineSurvivalMaterials.demonstrationSupplyMedium(entry.getValue().get("block_id").getAsString()) != null);
        return inputs;
    }
}
