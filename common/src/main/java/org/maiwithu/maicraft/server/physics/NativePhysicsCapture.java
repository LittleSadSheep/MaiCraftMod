package org.maiwithu.maicraft.server.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerLevel;
import org.joml.Matrix3dc;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 玩家请求分析后才短期旁听物理子步；复制冲量、作用点和质量，不向真实船体施加任何力。 */
public final class NativePhysicsCapture {
    private static final Logger LOG=LoggerFactory.getLogger("maicraft.physics");
    private static final Map<Object, Lease> WATCHES = new WeakHashMap<>();
    private static final String GROUPS = "dev.ryanhcode.sable.api.physics.force.ForceGroups";
    private static final String DIMENSION = "dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData";
    private static final class Lease {
        long until; boolean previous; PhysicsBody body; String error;
        double timeStep;
        boolean collecting,directObserved;
        PhysicsVector directForce=PhysicsVector.ZERO,directTorque=PhysicsVector.ZERO;
    }
    private NativePhysicsCapture() {}
    public static void watch(Object ship) {
        WATCHES.computeIfAbsent(ship, ignored -> new Lease()).until = level(ship).getGameTime() + 100;
    }
    public static PhysicsBody latest(Object ship) {
        Lease lease = WATCHES.get(ship); return lease == null ? null : lease.body;
    }
    public static String error(Object ship) {
        Lease lease = WATCHES.get(ship); return lease == null ? null : lease.error;
    }
    public static boolean observing(Object ship) {Lease lease=WATCHES.get(ship);return lease!=null&&lease.collecting;}
    static double timeStep(Object ship) {
        // 帆面原生算法在升力计算中使用子步冲量；预测必须复用实际采样时长，不能假定每刻只有一次物理更新。
        Lease lease=WATCHES.get(ship);
        if(lease==null||lease.body==null||lease.timeStep<=0)throw new IllegalStateException("尚无完整物理子步样本");
        return lease.timeStep;
    }
    public static void begin(Object ship) {
        Lease lease = WATCHES.get(ship);
        if (lease == null) return;
        if (level(ship).getGameTime() > lease.until) { WATCHES.remove(ship); return; }
        try {
            lease.collecting=true;lease.directObserved=false;lease.directForce=PhysicsVector.ZERO;lease.directTorque=PhysicsVector.ZERO;
            NativeWheelCapture.beginStep(ship);
            lease.previous = NativeApi.truth(NativeApi.call(ship, null, "isTrackingIndividualQueuedForces"));
            NativeApi.call(ship, null, "enableIndividualQueuedForcesTracking", true);
        } catch (RuntimeException | LinkageError missing) {
            // 未能开启本子步记录时不使用旧记录冒充新观察，并保留供协议返回的异常信息。
            lease.collecting=false; lease.body=null; failure(lease,ship,"begin",missing);
        }
    }
    public static void capture(Object ship, Object handle, double dt) {
        Lease lease = WATCHES.get(ship);
        if (lease == null || !lease.collecting) return;
        try { lease.body = read(ship, handle, dt); lease.timeStep=dt; lease.error = null; }
        catch (RuntimeException | LinkageError missing) { lease.body = null; failure(lease,ship,"capture",missing); }
        finally {
            lease.collecting=false;
            // 原图解也可能正在观察同一条船，结束本次采样后恢复此前的记录状态。
            try { NativeApi.call(ship, null, "enableIndividualQueuedForcesTracking", lease.previous); }
            catch (RuntimeException | LinkageError missing) { failure(lease,ship,"restore_tracking",missing); }
        }
    }

    public static void direct(Object ship,Vector3dc force,Vector3dc torque) {
        Lease lease=WATCHES.get(ship);if(lease==null||!lease.collecting) return;
        lease.directObserved=true;lease.directForce=lease.directForce.add(PhysicsVector.of(force));
        lease.directTorque=lease.directTorque.add(PhysicsVector.of(torque));
    }
    public static void directPoint(Object ship,Vector3dc point,Vector3dc force) {
        Lease lease=WATCHES.get(ship);if(lease==null||!lease.collecting) return;
        try {
            Object mass=NativeApi.call(ship,null,"getMassTracker");
            var arm=PhysicsVector.of(point).subtract(vector(NativeApi.call(mass,null,"getCenterOfMass")));
            direct(ship,force,arm.cross(PhysicsVector.of(force)).mutable());
        } catch(RuntimeException | LinkageError missing) { failure(lease,ship,"direct_point",missing); }
    }

    private static void failure(Lease lease,Object ship,String stage,Throwable failed) {
        String detail=stage+": "+failed.getClass().getSimpleName()+": "+failed.getMessage();
        // 同一签名错误可能每个物理子步重现，只在原因变化时打印完整堆栈，避免淹没真正的首个错误。
        if(!Objects.equals(lease.error,detail)) LOG.error("[maicraft-physics] {} failed for {}",stage,ship.getClass().getName(),failed);
        lease.error=detail;
    }

    private static PhysicsBody read(Object ship, Object handle, double dt) {
        if (!Double.isFinite(dt) || dt <= 0) throw new IllegalArgumentException("物理子步时长无效");
        Object mass = NativeApi.call(ship, null, "getMassTracker"), pose = NativeApi.call(ship, null, "logicalPose");
        Object plot = NativeApi.call(ship, null, "getPlot");
        BlockPos origin = (BlockPos) NativeApi.call(plot, null, "getCenterBlock");
        PhysicsVector offset = new PhysicsVector(origin.getX(), origin.getY(), origin.getZ());
        PhysicsVector centerStorage = vector(NativeApi.call(mass, null, "getCenterOfMass"));
        PhysicsVector center = centerStorage.subtract(offset);
        var rotation = PhysicsBody.Rotation.of((Quaterniondc) NativeApi.call(pose, null, "orientation"));
        PhysicsVector scale = vector(NativeApi.call(pose, null, "scale"));
        var worldCenter = centerStorage.subtract(vector(NativeApi.call(pose, null, "rotationPoint")));
        worldCenter = new PhysicsVector(worldCenter.x()*scale.x(), worldCenter.y()*scale.y(), worldCenter.z()*scale.z());
        worldCenter = rotation.world(worldCenter).add(vector(NativeApi.call(pose, null, "position")));
        List<String> unknowns = new ArrayList<>();
        if (!scale.equals(new PhysicsVector(1, 1, 1))) unknowns.add("unmodeled:非单位结构缩放的候选方块惯量需要额外校核");
        unknowns.add("点力记录不包含碰撞求解器的接触、摩擦和绳索约束冲量");
        List<PhysicsBody.Load> loads = new ArrayList<>();
        Lease lease=WATCHES.get(ship);
        PhysicsVector queuedForce=PhysicsVector.ZERO,queuedTorque=PhysicsVector.ZERO;
        PhysicsVector recordedForce=PhysicsVector.ZERO,recordedTorque=PhysicsVector.ZERO;
        Object raw = NativeApi.call(ship, null, "getQueuedForceGroups");
        if (raw instanceof Map<?, ?> groups) for (var entry : groups.entrySet()) {
            String group = groupId(entry.getKey());
            if(group.equals("null")) unknowns.add("unmodeled:原生受力组未注册，保留其数值但无法识别未来工况");
            if (group.endsWith(":gravity")) continue;
            Object queued = entry.getValue();
            PhysicsVector pointForce = PhysicsVector.ZERO, pointTorque = PhysicsVector.ZERO;
            int index = 0;
            for (Object point : (Iterable<?>) NativeApi.call(queued, null, "getRecordedPointForces")) {
                PhysicsVector at = vector(NativeApi.call(point, null, "point")).subtract(offset);
                // Sable 在子步内部记录冲量，除以实际子步时长后才是图解使用的力。
                PhysicsVector force = vector(NativeApi.call(point, null, "force")).scale(1 / dt);
                pointForce = pointForce.add(force); pointTorque = pointTorque.add(at.subtract(center).cross(force));
                boolean world = group.endsWith(":balloon_lift") || group.endsWith(":levitation");
                loads.add(new PhysicsBody.Load(group + "/" + index++, group, at,
                        world ? rotation.world(force) : force, PhysicsVector.ZERO,
                        world ? PhysicsBody.Frame.WORLD : PhysicsBody.Frame.BODY, group.endsWith(":propulsion"), 0));
            }
            Object total = NativeApi.call(queued, null, "getForceTotal");
            PhysicsVector force = vector(NativeApi.call(total, null, "getLocalForce")).scale(1 / dt);
            PhysicsVector torque = vector(NativeApi.call(total, null, "getLocalTorque")).scale(1 / dt);
            queuedForce=queuedForce.add(force);queuedTorque=queuedTorque.add(torque);
            recordedForce=recordedForce.add(pointForce);recordedTorque=recordedTorque.add(pointTorque);
            // 气压梯度等可能额外贡献纯力偶，不能只根据画出来的箭头重新求和而丢掉它。
            if (!lease.directObserved && force.length() + torque.length() > 1e-9) {
                force = force.subtract(pointForce); torque = torque.subtract(pointTorque);
                if (force.length() + torque.length() > 1e-7)
                    loads.add(new PhysicsBody.Load(group + "/residual", group, center, force, torque,
                            PhysicsBody.Frame.BODY, group.endsWith(":propulsion"), 0));
            }
            if (group.endsWith(":drag") || group.endsWith(":lift")) unknowns.add(group + " 使用采样时的气动载荷，速度变化后需要重新观察");
        }
        // Offroad 先批量施力，再进入此处；把已确认的轮胎点力从未归属总量中扣除一次，避免重复计算承重。
        var wheels=NativeWheelCapture.read(ship,origin,dt);unknowns.addAll(wheels.unknowns());
        for(var wheel:wheels.loads()) {
            loads.add(wheel);recordedForce=recordedForce.add(wheel.force());
            recordedTorque=recordedTorque.add(wheel.point().subtract(center).cross(wheel.force()));
        }
        if(lease.directObserved) {
            // 已直接提交的冲量与仍在队列里的冲量合并一次，再扣除已列出的点力，防止重复计数。
            PhysicsVector force=lease.directForce.scale(1/dt).add(queuedForce).subtract(recordedForce);
            PhysicsVector torque=lease.directTorque.scale(1/dt).add(queuedTorque).subtract(recordedTorque);
            if(force.length()+torque.length()>1e-7) {
                loads.add(new PhysicsBody.Load("unattributed_impulse","sable:unattributed_impulse",center,force,torque,PhysicsBody.Frame.BODY,false,0));
                unknowns.add("unmodeled:存在未归属到具体设备的直接冲量或力偶，实测总量已保留，未来工况来源仍需核验");
            }
        } else unknowns.add("直接刚体冲量总计未被本次采样钩子确认；只列出已确认的原生分组、执行器点力及重力");
        unknowns.add("外部直接修改速度或位置的操作不属于当前受力积分记录");
        return new PhysicsBody((UUID) NativeApi.call(ship, null, "getUniqueId"), level(ship).dimension().location().toString(),
                level(ship).getGameTime(), ((Number) NativeApi.call(mass, null, "getMass")).doubleValue(), center,
                PhysicsBody.Inertia.of((Matrix3dc) NativeApi.call(mass, null, "getInertiaTensor")), rotation, worldCenter,
                vector(NativeApi.call(handle, null, "getLinearVelocity", new Vector3d())),
                vector(NativeApi.call(handle, null, "getAngularVelocity", new Vector3d())),
                vector(NativeApi.call(null, DIMENSION, "getGravity", level(ship))), loads, unknowns);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String groupId(Object group) {
        Registry registry = (Registry) NativeApi.constant(GROUPS, "REGISTRY");
        return String.valueOf(registry.getKey(group));
    }
    private static PhysicsVector vector(Object value) { return PhysicsVector.of((Vector3dc) value); }
    private static ServerLevel level(Object ship) { return (ServerLevel) NativeApi.call(ship, null, "getLevel"); }
}
