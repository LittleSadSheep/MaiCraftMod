// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 将计划端口与本次客户端原生观察分开回传；未加载、缺少接口或零转速都不会变成已供电证明。 */
public final class MachinePowerPortObservations {
    private static final String ROTATE = "com.simibubi.create.content.kinetics.base.IRotate";
    private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    private static final String BELT = "com.simibubi.create.content.kinetics.belt.BeltBlockEntity";
    private MachinePowerPortObservations() {}
    public static JsonObject observe(Level world, BlockPos anchor, JsonArray planned) {
        JsonObject result = new JsonObject(); JsonArray ports = new JsonArray(); result.add("ports", ports);
        result.addProperty("evidence", "client_native_state"); result.addProperty("total_ports", planned.size());
        result.addProperty("truncated", planned.size() > 128);
        if (world != null) result.addProperty("observed_tick", world.getGameTime());
        // 收尾只做有界采样；大量端口的其余状态保留未知，模型可按所选位置再 inspect_machine。
        for (int i = 0; i < Math.min(128, planned.size()); i++) {
            JsonObject port = planned.get(i).getAsJsonObject().deepCopy(); ports.add(port);
            port.addProperty("evidence", "client_native_state"); port.addProperty("installed", (Boolean) null); port.addProperty("powered", (Boolean) null);
            BlockPos at = anchor.offset(MachineAssemblyDocument.position(port.get("offset")));
            if (world == null || !world.isLoaded(at)) { port.addProperty("unknown", "world_or_chunk_unavailable"); continue; }
            try {
                var state = world.getBlockState(at); var entity = world.getBlockEntity(at); Direction face = Direction.byName(port.get("face").getAsString());
                boolean installed = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(port.get("block_id").getAsString())
                        && NativeApi.is(state.getBlock(), ROTATE)
                        && NativeApi.truth(NativeApi.call(state.getBlock(), ROTATE, "hasShaftTowards", world, at, state, face));
                port.addProperty("installed", installed);
                if (!installed) { port.addProperty("powered", false); continue; }
                // 齿轮减速可能产生小于 1 RPM 的转速，保留原生小数，不能截断为零后误报停机。
                double speed = ((Number) NativeApi.call(entity, KINETIC, "getSpeed")).doubleValue();
                if (!Double.isFinite(speed)) { port.addProperty("unknown", "non_finite_native_speed"); continue; }
                boolean powered = speed != 0 && NativeApi.truth(NativeApi.call(entity, KINETIC, "hasNetwork"))
                        && !NativeApi.truth(NativeApi.call(entity, KINETIC, "isOverStressed"));
                port.addProperty("signed_rpm", speed); port.addProperty("powered", powered);
                if (powered && NativeApi.is(entity, BELT)) {
                    Object motion = NativeApi.call(entity, BELT, "getMovementFacing");
                    if (motion instanceof Direction direction) port.addProperty("observed_item_direction", direction.getName());
                }
            } catch (RuntimeException | LinkageError unavailable) { port.addProperty("unknown", "native_power_observation_unavailable"); }
        }
        result.addProperty("runtime_transfer_verified", false); return result;
    }
}
