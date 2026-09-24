// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 展示状态变化对传动轴的影响；没有世界上下文的接入面与实际供电仍须现场确认。 */
public final class CreateKineticCapabilities {
    private static final String ROTATE = "com.simibubi.create.content.kinetics.base.IRotate";
    private CreateKineticCapabilities() {}
    /** 传动部件也可能连接既有动力网；按原生旋转接口识别，不以发生器名称白名单决定是否值得调查。 */
    public static boolean isKinetic(BlockState state) { return NativeApi.is(state.getBlock(), ROTATE); }
    public static JsonObject describe(BlockState state) {
        if (!isKinetic(state)) return null;
        JsonObject result = new JsonObject(); JsonArray variants = new JsonArray(); result.add("state_variants", variants);
        var states = state.getBlock().getStateDefinition().getPossibleStates();
        result.addProperty("total_states", states.size()); result.addProperty("truncated", states.size() > 64);
        result.addProperty("evidence", "native_state_interfaces_without_world"); result.addProperty("powered", (Boolean) null);
        for (var variant : states.subList(0, Math.min(64, states.size()))) {
            JsonObject row = new JsonObject(), properties = new JsonObject(); variants.add(row);
            variant.getValues().forEach((key, value) -> properties.addProperty(key.getName(), value.toString())); row.add("properties", properties);
            try {
                row.addProperty("rotation_axis", NativeApi.call(variant.getBlock(), ROTATE, "getRotationAxis", variant).toString());
                JsonArray faces = new JsonArray();
                for (Direction face : Direction.values()) if (NativeApi.truth(NativeApi.call(variant.getBlock(), ROTATE, "hasShaftTowards", null, BlockPos.ZERO, variant, face))) faces.add(face.getName());
                row.add("context_free_shaft_faces", faces);
            } catch (RuntimeException | LinkageError needsWorld) { row.addProperty("shaft_faces_unknown", "requires native world context"); }
            row.addProperty("world_confirmation_required", true);
        }
        return result;
    }
}
