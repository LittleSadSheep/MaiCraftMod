// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineSurvivalMaterials;

/** 教程无限资源提供者转换为资源 IN；保留原始证据，实际接入哪个网络仍由设计与原生接口共同决定。 */
final class PonderResourceInputs {
    private PonderResourceInputs() {}

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
