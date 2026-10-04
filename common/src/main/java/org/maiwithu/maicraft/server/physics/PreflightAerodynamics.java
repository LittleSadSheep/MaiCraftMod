package org.maiwithu.maicraft.server.physics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsAerodynamics;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 在真实受力上叠加帆面改造与迎流变化；同组中的水阻、悬浮方块阻力不会被一起删除。 */
final class PreflightAerodynamics {
    private static final String PROVIDER="dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider";
    private static final String DIMENSION="dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData";
    private PreflightAerodynamics() {}
    static PhysicsBody model(Object ship,PhysicsBody measured,PhysicsBody model,PhysicsBlockEdits view) {
        var loads=new ArrayList<>(model.loads());var unknowns=new ArrayList<>(model.unknowns());
        PhysicsVector baselineForce=PhysicsVector.ZERO,baselineTorque=PhysicsVector.ZERO;
        int nativeSurfaces=0;boolean baselineComplete=true;
        try {
            Object plot=NativeApi.call(ship,null,"getPlot");double dt=NativePhysicsCapture.timeStep(ship);
            var positions=new LinkedHashSet<BlockPos>();
            for(Object context:(Iterable<?>)NativeApi.call(plot,null,"getLiftProviders"))
                positions.add(((BlockPos)NativeApi.call(context,null,"pos")).immutable());
            // 原来没有帆面的候选格也要检查，才能试算新增机翼，而不只是改变已观察到的机翼。
            positions.addAll(view.replacements.keySet());
            for(BlockPos pos:positions) {
                try {
                    BlockState before=view.level.getBlockState(pos),after=view.getBlockState(pos);
                    if(!NativeApi.is(before.getBlock(),PROVIDER)&&!NativeApi.is(after.getBlock(),PROVIDER))continue;
                    var point=new PhysicsVector(pos.getX()-view.origin.getX()+.5,pos.getY()-view.origin.getY()+.5,pos.getZ()-view.origin.getZ()+.5);
                    var oldSurface=read(before,point,measured,view,dt);var newSurface=read(after,point,model,view,dt);
                    // 实测组可能混有其他阻力；只抵消这一格的采样载荷，再加入它在候选姿态和速度下的新载荷。
                    var baseline=oldSurface==null?PhysicsVector.ZERO:oldSurface.force(measured.rotation().local(
                            measured.velocity().add(measured.angularVelocity().cross(measured.rotation().world(point.subtract(measured.center()))))));
                    // 原生图解会过滤微小冲量，并把一组阻力合画在一个中心；逐面求和保留被图解省略的力和力偶。
                    if(oldSurface!=null) {
                        nativeSurfaces++;baselineForce=baselineForce.add(baseline);
                        baselineTorque=baselineTorque.add(point.subtract(measured.center()).cross(baseline));
                    }
                    loads.add(new PhysicsBody.Load("airfoil:"+pos.subtract(view.origin).toShortString(),"sable:aerodynamics_delta",point,
                            baseline.scale(-1),PhysicsVector.ZERO,PhysicsBody.Frame.BODY,false,0,0,newSurface));
                } catch(RuntimeException missing) {
                    baselineComplete=false;
                    unknowns.add("unmodeled:升力面 "+pos.subtract(view.origin)+": "+missing.getMessage());
                }
            }
            for(Object contraption:(Iterable<?>)NativeApi.call(plot,null,"getContraptions")) {
                Object providers=NativeApi.call(contraption,null,"sable$liftProviders");
                if(providers instanceof Map<?,?> entries&&!entries.isEmpty()) {
                    baselineComplete=false;
                    unknowns.add("unmodeled:运动装置中的 "+entries.size()+" 个升力面仅保留实测载荷，轴承角度及内部转动尚未复演");
                }
            }
            if(baselineComplete&&nativeSurfaces>0) {
                PhysicsVector drawnForce=PhysicsVector.ZERO,drawnTorque=PhysicsVector.ZERO;
                for(var load:measured.loads())if(load.group().equals("sable:lift")||load.group().equals("sable:drag")) {
                    if(load.frame()!=PhysicsBody.Frame.BODY){baselineComplete=false;break;}
                    drawnForce=drawnForce.add(load.force());
                    drawnTorque=drawnTorque.add(load.point().subtract(measured.center()).cross(load.force())).add(load.torque());
                }
                if(baselineComplete)attribute(loads,unknowns,baselineForce.subtract(drawnForce),baselineTorque.subtract(drawnTorque));
            }
        } catch(RuntimeException missing) {unknowns.add("unmodeled:升力面读取未完成: "+missing.getMessage());}
        unknowns.add("固定帆面按原生系数随作用点速度重算；气压保持采样值，气压随高度变化与未识别的其他阻力尚需实机核验");
        return new PhysicsBody(model.structureId(),model.dimension(),model.tick(),model.mass(),model.center(),model.inertia(),model.rotation(),
                model.position(),model.velocity(),model.angularVelocity(),model.gravity(),loads,unknowns);
    }
    static boolean attribute(List<PhysicsBody.Load> loads,List<String> unknowns,
                             PhysicsVector missingForce,PhysicsVector missingTorque) {
        // 只有完整的残余力及力偶都与原生帆面计算相符，才作来源归属；额外执行器的冲量不能被顺便抹掉。
        boolean matched=false;
        for(int i=0;i<loads.size();i++) {
            var load=loads.get(i);if(!load.id().equals("unattributed_impulse"))continue;
            if(load.frame()!=PhysicsBody.Frame.BODY||!close(load.force(),missingForce)||!close(load.torque(),missingTorque))return false;
            loads.set(i,new PhysicsBody.Load("aerodynamic_display_residual","sable:aerodynamics_sample",load.point(),load.force(),load.torque(),
                    load.frame(),false,0));matched=true;break;
        }
        if(!matched&&(unknowns.contains(NativePhysicsCapture.UNATTRIBUTED_WARNING)
                ||!close(missingForce,PhysicsVector.ZERO)||!close(missingTorque,PhysicsVector.ZERO)))return false;
        unknowns.remove(NativePhysicsCapture.UNATTRIBUTED_WARNING);
        unknowns.remove("sable:lift 使用采样时的气动载荷，速度变化后需要重新观察");
        unknowns.remove("sable:drag 使用采样时的气动载荷，速度变化后需要重新观察");
        return true;
    }
    private static boolean close(PhysicsVector actual,PhysicsVector expected) {
        return actual.subtract(expected).length()<=1e-7*(1+expected.length());
    }
    private static PhysicsAerodynamics read(BlockState state,PhysicsVector point,PhysicsBody body,PhysicsBlockEdits view,double dt) {
        var block=state.getBlock();if(!NativeApi.is(block,PROVIDER))return null;
        // 扩展模组若覆盖原生气动公式，保留原始观察并标明未知，不能套用默认帆面公式冒充其行为。
        Class<?> provider=NativeApi.type(PROVIDER);
        boolean standard=Arrays.stream(block.getClass().getMethods()).anyMatch(method->method.getName().equals("sable$contributeLiftAndDrag")
                &&method.getDeclaringClass()==provider);
        if(!standard)throw new IllegalArgumentException("该方块覆盖了原生默认气动算法");
        Direction normal=(Direction)NativeApi.call(block,null,"sable$getNormal",state);
        var direction=new PhysicsVector(normal.getStepX(),normal.getStepY(),normal.getStepZ());
        var world=body.position().add(body.rotation().world(point.subtract(body.center())));
        double pressure=((Number)NativeApi.call(null,DIMENSION,"getAirPressure",view.level,world.mutable())).doubleValue();
        return new PhysicsAerodynamics(direction,coefficient(block,"sable$getParallelDragScalar"),coefficient(block,"sable$getDirectionlessDragScalar"),
                coefficient(block,"sable$getLiftScalar"),pressure,dt);
    }
    private static double coefficient(Object block,String method) {
        // 原生仅在系数大于零时贡献对应分量，零或负值均视为该分量关闭。
        return Math.max(0,((Number)NativeApi.call(block,null,method)).doubleValue());
    }
}
