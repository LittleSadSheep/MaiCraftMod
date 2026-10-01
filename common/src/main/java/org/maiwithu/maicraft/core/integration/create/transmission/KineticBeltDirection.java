// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 规划只服从模型明确声明的方向；施工后的真实方向单独比较，不把方向偏差改写成动作失败。 */
final class KineticBeltDirection {
    private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    private KineticBeltDirection() {}

    static double outletRpm(Level world, KineticRouteGeometry.Plan plan, double rpm) {
        var entity = world.getBlockEntity(plan.source().position()); var face = plan.sourceFace();
        if (face == null || !NativeApi.is(entity, "com.simibubi.create.content.kinetics.base.DirectionalShaftHalvesBlockEntity")) return rpm;
        if (!NativeApi.truth(NativeApi.call(entity, KINETIC, "hasSource")) && !NativeApi.truth(NativeApi.call(entity, KINETIC, "isSource"))) return rpm;
        // 已有齿轮箱的各出口会改变符号；先按 Create RotationPropagator 读取真实输入面，再计算新线路。
        if (NativeApi.is(entity, "com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity")) {
            var incoming = (Direction) NativeApi.call(entity, null, "getSourceFacing");
            return rpm * KineticTransmissionRatios.gearbox(incoming, face);
        }
        if (NativeApi.is(entity, "com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity"))
            return rpm * ((Number) NativeApi.call(entity, null, "getRotationSpeedModifier", face)).doubleValue();
        return rpm;
    }

    static boolean matchesPlan(Level world, KineticRouteGeometry.Plan plan, double rpm, Direction expected) {
        if (expected == null) return true;
        var block = world.getBlockState(plan.target().position());
        if (!block.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return false;
        Double ratio = plan.transmissionRatio(); if (ratio == null) return false;
        double target = outletRpm(world, plan, rpm) * ratio;
        if (!Double.isFinite(target) || target == 0) return false;
        // 与 BeltBlockEntity.getMovementFacing 同一规则，方块 facing 本身只确定皮带所在轴向。
        var axis = block.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis();
        var actual = Direction.fromAxisAndDirection(axis, target < 0 ^ axis == Direction.Axis.X
                ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
        return actual == expected;
    }

    static JsonObject compare(JsonObject observed, Direction expected) {
        var result = new JsonObject();
        result.addProperty("expected", expected == null ? null : expected.getSerializedName());
        var kinetic = observed == null ? null : KineticNativeReads.kinetics(observed);
        var motion = kinetic != null && kinetic.has("belt_motion") ? kinetic.getAsJsonObject("belt_motion") : null;
        if (motion != null) result.add("actual", motion.deepCopy());
        String status = expected == null ? "not_requested" : motion == null || "unknown".equals(KineticNativeReads.text(motion,"status"))
                ? "unknown" : expected.getSerializedName().equals(KineticNativeReads.text(motion,"transport_direction")) ? "matched" : "mismatch";
        result.addProperty("status", status); result.addProperty("verified", "matched".equals(status)); return result;
    }
}
