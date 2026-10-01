// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 从同一套 Create 原生方法读取物品运动方向，客户端同步观察与服务器快照保持相同语义。 */
public final class CreateBeltObservation {
    private static final String ENTITY = "com.simibubi.create.content.kinetics.belt.BeltBlockEntity";
    private static final String BLOCK = "com.simibubi.create.content.kinetics.belt.BeltBlock";
    private CreateBeltObservation() {}

    public static void append(BlockEntity entity, JsonObject state) {
        if (!NativeApi.is(entity, ENTITY)) return;
        var motion = new JsonObject(); state.add("belt_motion", motion);
        try {
            boolean supportsItems = NativeApi.truth(NativeApi.call(null, BLOCK, "canTransportObjects", entity.getBlockState()));
            double speed = NativeApi.number(NativeApi.call(entity, ENTITY, "getBeltMovementSpeed"));
            motion.addProperty("supports_item_transport", supportsItems);
            // 停转时原生 facing 方法仍返回一个方位；必须先看真实速度，不能把零速带说成正向输送。
            if (speed == 0 || !supportsItems) {
                motion.addProperty("status", speed == 0 ? "stopped" : "rotation_only");
                motion.addProperty("transport_direction", "none");
            } else {
                Direction facing = (Direction) NativeApi.call(entity, ENTITY, "getMovementFacing");
                Vec3i vector = (Vec3i) NativeApi.call(entity, ENTITY, "getBeltChainDirection");
                motion.addProperty("status", "moving");
                motion.addProperty("transport_direction", facing.getSerializedName());
                // 斜坡除水平输送方向外还给出升降分量，不能用方块朝向或 RPM 正负代替物品的实际行进方向。
                var step = new JsonArray(); step.add(vector.getX()); step.add(vector.getY()); step.add(vector.getZ());
                motion.add("travel_step", step);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            motion.addProperty("status", "unknown");
        }
    }
}
