package org.maiwithu.maicraft.server.physics;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 起飞前把停转推进器和持续浮力转换成独立工况载荷；保留不能读取的部件和预测假设。 */
final class PreflightComponents {
    private static final String BALLOON_MAP = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.map.BalloonMap";
    private PreflightComponents() {}
    static PhysicsBody model(Object ship, PhysicsBody measured, double rpm, boolean filled) {
        Object plot = NativeApi.call(ship, null, "getPlot");
        BlockPos origin = (BlockPos) NativeApi.call(plot, null, "getCenterBlock");
        ServerLevel level = (ServerLevel) NativeApi.call(ship, null, "getLevel");
        List<String> unknowns = new ArrayList<>(measured.unknowns());
        List<PhysicsBody.Load> loads = new ArrayList<>();
        // 推进和气球另按待运行工况建模，不能将停机时的零推力当成开机后的实际能力。
        for (var load : measured.loads()) if (!load.propulsion() && !load.group().endsWith(":balloon_lift")) loads.add(load);
        for (Object actor : (Iterable<?>) NativeApi.call(plot, null, "getBlockEntityActors")) {
            if (!(actor instanceof BlockEntity entity) || !NativeApi.is(actor, PreflightPropellers.PROPELLER)) continue;
            try { loads.add(PreflightPropellers.read(entity, origin, rpm)); }
            catch (RuntimeException missing) { unknowns.add("推进器 " + entity.getBlockPos().subtract(origin) + ": " + missing.getMessage()); }
        }
        if (NativeApi.present(BALLOON_MAP)) {
            Object map = NativeApi.call(NativeApi.constant(BALLOON_MAP, "MAP"), null, "get", level);
            Object helper = NativeApi.constant("dev.ryanhcode.sable.Sable", "HELPER");
            for (Object balloon : (Iterable<?>) NativeApi.call(map, null, "getBalloons")) {
                BlockPos controller = (BlockPos) NativeApi.call(balloon, null, "getControllerPos");
                if (NativeApi.call(helper, null, "getContaining", level, controller) != ship) continue;
                try { loads.add(balloon(balloon, origin, measured, level, filled)); }
                catch (RuntimeException missing) { unknowns.add("气球 " + controller.subtract(origin) + ": " + missing.getMessage()); }
            }
        }
        unknowns.add("运行预测假定动力网络可达到声明的 " + rpm + " RPM；须在起飞前核验应力、传动和燃料");
        unknowns.add("停机工况关闭推进，保留气球供气；停止供气或燃料耗尽需另外评估");
        return new PhysicsBody(measured.structureId(), measured.dimension(), measured.tick(), measured.mass(), measured.center(),
                measured.inertia(), measured.rotation(), measured.position(), measured.velocity(), measured.angularVelocity(),
                measured.gravity(), loads, unknowns);
    }
    private static PhysicsBody.Load balloon(Object balloon, BlockPos origin, PhysicsBody body, ServerLevel level, boolean filled) {
        double lift = ((Number) NativeApi.call(balloon, null, "getTotalLift")).doubleValue();
        if (filled) {
            double gas = 0, weightedLift = 0;
            for (Object heater : (Iterable<?>) NativeApi.call(balloon, null, "getHeaters")) {
                double output = ((Number) NativeApi.call(heater, null, "getGasOutput")).doubleValue();
                Object type = NativeApi.call(heater, null, "getLiftingGasType");
                gas += output; weightedLift += output * ((Number) NativeApi.call(type, null, "getLiftStrength")).doubleValue();
            }
            double capacity = ((Number) NativeApi.call(balloon, null, "getCapacity")).doubleValue();
            lift = gas == 0 ? 0 : weightedLift * Math.min(1, capacity / gas);
        }
        Vec3 nativeCenter = (Vec3) NativeApi.call(balloon, null, "getCenter");
        PhysicsVector point = new PhysicsVector(nativeCenter.x - origin.getX(), nativeCenter.y - origin.getY(), nativeCenter.z - origin.getZ());
        PhysicsVector world = body.position().add(body.rotation().world(point.subtract(body.center())));
        double pressure = ((Number) NativeApi.call(null,
                "dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData", "getAirPressure", level, world.mutable())).doubleValue();
        BlockPos controller = ((BlockPos) NativeApi.call(balloon, null, "getControllerPos")).subtract(origin);
        return new PhysicsBody.Load("balloon:" + controller.getX() + "," + controller.getY() + "," + controller.getZ(),
                "sable:balloon_lift", point, body.gravity().scale(-lift * pressure), PhysicsVector.ZERO,
                PhysicsBody.Frame.WORLD, false, 0);
    }
}
