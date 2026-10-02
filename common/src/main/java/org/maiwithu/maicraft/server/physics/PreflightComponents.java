package org.maiwithu.maicraft.server.physics;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 起飞前把停转推进器和持续浮力转换成独立工况载荷；保留不能读取的部件和预测假设。 */
final class PreflightComponents {
    private PreflightComponents() {}
    static PhysicsBody model(Object ship, PhysicsBody measured, double rpm) {
        Object plot = NativeApi.call(ship, null, "getPlot");
        BlockPos origin = (BlockPos) NativeApi.call(plot, null, "getCenterBlock");
        List<String> unknowns = new ArrayList<>(measured.unknowns());
        List<PhysicsBody.Load> loads = new ArrayList<>();
        // 推进和气球另按待运行工况建模，不能将停机时的零推力当成开机后的实际能力。
        for (var load : measured.loads()) if (!load.propulsion() && !load.group().endsWith(":balloon_lift")) loads.add(load);
        for (Object actor : (Iterable<?>) NativeApi.call(plot, null, "getBlockEntityActors")) {
            if (!(actor instanceof BlockEntity entity)) continue;
            if (!NativeApi.is(actor, PreflightPropellers.PROPELLER)) {
                unknowns.add("unmodeled:尚无起飞前工况模型的原生执行器 "+entity.getBlockPos().subtract(origin)+" "+entity.getClass().getSimpleName());
                continue;
            }
            try { loads.add(PreflightPropellers.read(entity, origin, rpm,unknowns)); }
            catch (RuntimeException missing) { unknowns.add("unmodeled:推进器 " + entity.getBlockPos().subtract(origin) + ": " + missing.getMessage()); }
        }
        for(var load:measured.loads()) if(load.propulsion()&&loads.stream().noneMatch(known->known.propulsion()
                &&known.point().subtract(load.point()).length()<.01)) {
            loads.add(load); unknowns.add("unmodeled:推进来源 "+load.id()+" 仅保留实测力，尚不能预测停机设备的目标转速");
        }
        unknowns.add("运行预测假定动力网络可达到声明的 " + rpm + " RPM；须在起飞前核验应力、传动和燃料");
        unknowns.add("停机工况关闭推进，保留气球供气；停止供气或燃料耗尽需另外评估");
        return new PhysicsBody(measured.structureId(), measured.dimension(), measured.tick(), measured.mass(), measured.center(),
                measured.inertia(), measured.rotation(), measured.position(), measured.velocity(), measured.angularVelocity(),
                measured.gravity(), loads, unknowns);
    }
}
