package org.maiwithu.maicraft.server.physics;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3dc;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsWheel;
import org.maiwithu.maicraft.network.OptionalServerMixinPlugin;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 旁听轮胎的原生接地、冲量计算与批量施力；只在整机采样租约内记录，不调用任何驱动或装配方法。 */
public final class NativeWheelCapture {
    record Sample(BlockPos mount,PhysicsVector point,PhysicsVector impulse,PhysicsWheel wheel,
                  boolean queued,boolean applied,List<String> unknowns) {}
    record Snapshot(List<PhysicsBody.Load> loads,List<String> unknowns) {}
    private static final class Frame {final Map<BlockPos,Sample> samples=new LinkedHashMap<>();}
    private record Owner(WeakReference<Object> ship,BlockPos mount) {}
    private static final Map<Object,Frame> FRAMES=new WeakHashMap<>();
    private static final Map<Object,Owner> OWNERS=new WeakHashMap<>();
    private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
    private static final class Scope {
        final Scope previous;final Object ship;final BlockEntity wheel;final double yaw;
        final List<String> unknowns=new ArrayList<>();
        NativeWheelParameters.Values values;PhysicsVector point,impulse=PhysicsVector.ZERO,ground,normal;
        String groundShip;double extension=5,friction=1;boolean terrain,queued;
        Scope(Object ship,BlockEntity wheel,double yaw) {
            previous=ACTIVE.get();this.ship=ship;this.wheel=wheel;this.yaw=yaw;
            BlockPos at=wheel.getBlockPos();point=new PhysicsVector(at.getX()+.5,at.getY()+.5,at.getZ()+.5);
        }
    }
    private NativeWheelCapture() {}
    static void beginStep(Object ship) {FRAMES.put(ship,new Frame());}
    public static Object begin(Object wheel,Object ship,double yaw) {
        if(!NativePhysicsCapture.observing(ship)||!(wheel instanceof BlockEntity entity))return null;
        var scope=new Scope(ship,entity,yaw);ACTIVE.set(scope);
        OWNERS.put(wheel,new Owner(new WeakReference<>(ship),entity.getBlockPos().immutable()));
        try {scope.values=NativeWheelParameters.read(entity,yaw);scope.point=scope.values.point();}
        catch(RuntimeException|LinkageError missing) {scope.unknowns.add("车轮参数: "+missing);}
        return scope;
    }
    public static void terrain(Object wheel,double extension,Direction direction,Object groundShip,BlockPos block) {
        Scope scope=ACTIVE.get();if(scope==null||scope.wheel!=wheel)return;
        scope.terrain=true;scope.extension=extension;
        if(block==null)return;
        try {
            // 原生最终采用的轮下高度与法线成为当前支撑平面样本，不能借用旁边未命中的方块当作支撑。
            Object pose=NativeApi.call(scope.ship,null,"logicalPose");
            scope.ground=PhysicsVector.of((Vector3dc)NativeApi.call(pose,null,"transformPosition",scope.point.subtract(new PhysicsVector(0,extension,0)).mutable()));
            var normal=new PhysicsVector(direction.getStepX(),direction.getStepY(),direction.getStepZ());
            if(groundShip!=null) {
                scope.groundShip=NativeApi.call(groundShip,null,"getUniqueId").toString();
                normal=PhysicsVector.of((Vector3dc)NativeApi.call(NativeApi.call(groundShip,null,"logicalPose"),null,"transformNormal",normal.mutable()));
            }
            scope.normal=normal;
            scope.friction=NativeWheelParameters.friction(scope.wheel,block,scope.values==null?0:scope.values.minimumFriction());
        } catch(RuntimeException|LinkageError missing) {scope.ground=null;scope.normal=null;scope.unknowns.add("车轮地面: "+missing);}
    }
    public static void force(Object wheel,Vector3dc point,Vector3dc impulse,double friction) {
        Scope scope=ACTIVE.get();if(scope==null||scope.wheel!=wheel)return;
        try {scope.point=PhysicsVector.of(point);scope.impulse=PhysicsVector.of(impulse);scope.friction=friction;scope.queued=true;}
        catch(RuntimeException invalid) {scope.unknowns.add("轮胎冲量: "+invalid);}
    }
    public static void finish(Object token,boolean completed) {
        if(!(token instanceof Scope scope))return;
        try {
            if(!completed)scope.unknowns.add("原生轮胎计算被异常中断");
            PhysicsWheel wheel=null;
            if(scope.values!=null) {
                var v=scope.values;
                String state=v.radius()==0?"no_tire":!scope.terrain?"terrain_unobserved":scope.ground==null?"no_ground":scope.queued?"contact_pending_application":"airborne";
                try {wheel=new PhysicsWheel(v.item(),v.radius(),v.strength(),scope.yaw,v.forward(),v.side(),v.driveSign(),v.rpm(),
                        v.brake(),scope.friction,scope.extension,scope.ground,scope.normal,scope.groundShip,state,false);}
                catch(RuntimeException invalid) {scope.unknowns.add("轮胎快照: "+invalid);}
            }
            Frame frame=FRAMES.get(scope.ship);
            if(frame!=null)frame.samples.put(scope.wheel.getBlockPos().immutable(),new Sample(scope.wheel.getBlockPos().immutable(),
                    scope.point,scope.impulse,wheel,scope.queued,false,List.copyOf(scope.unknowns)));
        } finally {if(scope.previous==null)ACTIVE.remove();else ACTIVE.set(scope.previous);}
    }
    public static void applied(Object wheel) {
        // 只有原生批量施力入口返回后才确认应用，单纯把冲量放进临时累加器不构成施力证据。
        Owner owner=OWNERS.get(wheel);if(owner==null)return;Object ship=owner.ship().get();
        if(ship==null||!NativePhysicsCapture.observing(ship))return;
        Frame frame=FRAMES.get(ship);Sample sample=frame==null?null:frame.samples.get(owner.mount());
        if(sample!=null)frame.samples.put(owner.mount(),applied(sample));
    }
    static Sample applied(Sample sample) {
        if(!sample.queued())return sample;
        PhysicsWheel wheel=sample.wheel();
        if(wheel!=null)wheel=new PhysicsWheel(wheel.itemId(),wheel.radius(),wheel.strength(),wheel.steeringRadians(),wheel.forward(),wheel.side(),
                wheel.driveSign(),wheel.rpm(),wheel.brake(),wheel.friction(),wheel.extension(),wheel.groundPoint(),wheel.groundNormal(),wheel.groundStructureId(),
                wheel.contactState().equals("contact_pending_application")?"contact":wheel.contactState(),true);
        return new Sample(sample.mount(),sample.point(),sample.impulse(),wheel,sample.queued(),true,sample.unknowns());
    }
    static PhysicsBody.Load load(Sample sample,BlockPos origin,double dt) {
        var offset=new PhysicsVector(origin.getX(),origin.getY(),origin.getZ());
        return new PhysicsBody.Load("wheel:"+sample.mount().subtract(origin).toShortString(),"offroad:wheel_contact",sample.point().subtract(offset),
                sample.applied()?sample.impulse().scale(1/dt):PhysicsVector.ZERO,PhysicsVector.ZERO,PhysicsBody.Frame.BODY,false,0,0,null,sample.wheel());
    }
    static Snapshot read(Object ship,BlockPos origin,double dt) {
        var loads=new ArrayList<PhysicsBody.Load>();var unknowns=new ArrayList<String>();Frame frame=FRAMES.get(ship);
        var samples=frame==null?Map.<BlockPos,Sample>of():frame.samples;
        Object plot=NativeApi.call(ship,null,"getPlot");
        for(Object actor:(Iterable<?>)NativeApi.call(plot,null,"getBlockEntityActors")) {
            if(!(actor instanceof BlockEntity entity)||!NativeApi.is(actor,NativeWheelParameters.WHEEL))continue;
            Sample sample=samples.get(entity.getBlockPos());
            if(!OptionalServerMixinPlugin.wheelEvents()||sample==null) {unknowns.add("unmodeled:车轮 "+entity.getBlockPos().subtract(origin)+" 原生观察钩子或当前子步样本缺失");continue;}
            loads.add(load(sample,origin,dt));
            for(String error:sample.unknowns())unknowns.add("unmodeled:车轮 "+entity.getBlockPos().subtract(origin)+": "+error);
            if(sample.queued()&&!sample.applied())unknowns.add("unmodeled:车轮 "+entity.getBlockPos().subtract(origin)+" 已计算冲量但尚未确认原生批量施力");
        }
        return new Snapshot(List.copyOf(loads),List.copyOf(unknowns));
    }
}
