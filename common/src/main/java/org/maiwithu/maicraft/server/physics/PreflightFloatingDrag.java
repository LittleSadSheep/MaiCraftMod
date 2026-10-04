package org.maiwithu.maicraft.server.physics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsFloatingDrag;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 在隔离布局上重建原生线性浮力材料组，抵消旧采样后按候选速度与转动更新蒙皮阻力。 */
final class PreflightFloatingDrag {
    private static final String PROPERTIES="dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper";
    private static final String DIMENSION="dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData";
    private record Group(PhysicsFloatingDrag.Cloud cloud,PhysicsFloatingDrag drag) {}
    private PreflightFloatingDrag() {}

    static PhysicsBody model(Object ship,PhysicsBody measured,PhysicsBody model,PhysicsBlockEdits view) {
        var loads=new ArrayList<>(model.loads());var unknowns=new ArrayList<>(model.unknowns());
        try {
            Object plot=NativeApi.call(ship,null,"getPlot"),bounds=NativeApi.call(plot,null,"getBoundingBox");
            var min=point(bounds,"min");var max=point(bounds,"max");
            long volume=(long)(max.getX()-min.getX()+1)*(max.getY()-min.getY()+1)*(max.getZ()-min.getZ()+1);
            if(volume<0||volume>32768)throw new IllegalArgumentException("浮力材料观察范围超出单次预算");
            var positions=new LinkedHashSet<BlockPos>();
            for(var pos:BlockPos.betweenClosed(min,max))positions.add(pos.immutable());
            positions.addAll(view.replacements.keySet());
            Map<Object,List<PhysicsFloatingDrag.Cell>> before=new LinkedHashMap<>(),after=new LinkedHashMap<>();
            for(var pos:positions) {
                if(!view.level.hasChunkAt(pos))throw new IllegalArgumentException("浮力材料所在区块未加载");
                var at=new PhysicsVector(pos.getX()-view.origin.getX()+.5,pos.getY()-view.origin.getY()+.5,pos.getZ()-view.origin.getZ()+.5);
                add(before,view.level.getBlockState(pos),at);add(after,view.getBlockState(pos),at);
            }
            var materials=new LinkedHashSet<>(before.keySet());materials.addAll(after.keySet());
            if(!materials.isEmpty()&&!NativePhysicsCapture.directObserved(ship))
                throw new IllegalArgumentException("直接冲量总账未确认，不能扣除材料组的采样力偶");
            PhysicsVector attributedCouple=PhysicsVector.ZERO;boolean allKnown=true;int index=0;
            var matchedDrag=new LinkedHashSet<String>();
            for(Object contraption:(Iterable<?>)NativeApi.call(plot,null,"getContraptions")) {
                Object container=NativeApi.call(contraption,null,"sable$getFloatingClusterContainer");
                if(NativeApi.field(container,null,"clusters") instanceof List<?> clusters&&!clusters.isEmpty()) {
                    allKnown=false;unknowns.add("unmodeled:运动装置中的浮力材料阻力尚未复演相对转动");
                }
            }
            for(Object material:materials) {
                try {
                    Group old=group(material,before.get(material),measured,view,NativePhysicsCapture.timeStep(ship));
                    Group next=group(material,after.get(material),model,view,NativePhysicsCapture.timeStep(ship));
                    String id="floating_drag:"+index++;
                    if(old!=null) {
                        var arm=old.cloud().center().subtract(measured.center());
                        var velocity=measured.rotation().local(measured.velocity()).add(measured.rotation().local(measured.angularVelocity()).cross(arm));
                        var baseline=old.drag().force(velocity,measured.rotation().local(measured.angularVelocity()),measured.rotation().local(measured.gravity()));
                        // 只有原生点力与同一材料组公式吻合才认领它，不能删掉共享 drag 组里的水阻或其他模组载荷。
                        var matches=measured.loads().stream().filter(load->!matchedDrag.contains(load.id())&&load.group().equals("sable:drag")
                                &&load.frame()==PhysicsBody.Frame.BODY&&close(load.point(),old.cloud().center())&&close(load.force(),baseline.force())).toList();
                        if(matches.isEmpty()&&baseline.force().length()>1e-6)throw new IllegalArgumentException("原生材料组点力与公式未对齐");
                        if(!matches.isEmpty())matchedDrag.add(matches.getFirst().id());
                        loads.add(new PhysicsBody.Load(id+"/sample","sable:floating_drag_delta",old.cloud().center(),baseline.force().scale(-1),
                                baseline.couple().scale(-1),PhysicsBody.Frame.BODY,false,0));
                        attributedCouple=attributedCouple.add(baseline.couple());
                    }
                    if(next!=null)loads.add(new PhysicsBody.Load(id,"sable:floating_drag_delta",next.cloud().center(),PhysicsVector.ZERO,
                            PhysicsVector.ZERO,PhysicsBody.Frame.BODY,false,0,0,null,null,next.drag()));
                } catch(RuntimeException missing) {allKnown=false;unknowns.add("unmodeled:浮力材料阻力组: "+missing.getMessage());}
            }
            // 只在整条残余力偶被数值核验覆盖时去掉未知标记；其他执行器或压力梯度的残余继续保留。
            if(allKnown&&!materials.isEmpty()) {
                attribute(loads,unknowns,attributedCouple);
                if(measured.loads().stream().filter(load->load.group().equals("sable:drag")).allMatch(load->matchedDrag.contains(load.id())))
                    unknowns.remove("sable:drag 使用采样时的气动载荷，速度变化后需要重新观察");
            }
            if(!materials.isEmpty())unknowns.add("线性浮力材料阻力按原生系数、材料分布及当前速度重算；气压保留组中心采样值");
        } catch(RuntimeException missing) {unknowns.add("unmodeled:浮力材料阻力读取未完成: "+missing.getMessage());}
        return new PhysicsBody(model.structureId(),model.dimension(),model.tick(),model.mass(),model.center(),model.inertia(),model.rotation(),
                model.position(),model.velocity(),model.angularVelocity(),model.gravity(),loads,unknowns);
    }

    private static void add(Map<Object,List<PhysicsFloatingDrag.Cell>> groups,BlockState state,PhysicsVector point) {
        if(state.isAir())return;
        Object material=NativeApi.call(null,PROPERTIES,"getFloatingMaterial",state);if(material==null)return;
        double scale=((Number)NativeApi.call(null,PROPERTIES,"getFloatingScale",state)).doubleValue();
        if(scale==0)return;
        groups.computeIfAbsent(material,key->new ArrayList<>()).add(new PhysicsFloatingDrag.Cell(point,scale));
    }
    private static Group group(Object material,List<PhysicsFloatingDrag.Cell> cells,PhysicsBody body,PhysicsBlockEdits view,double dt) {
        if(cells==null||cells.isEmpty())return null;
        // 带过渡速度或自身升力的悬浮材料有另一套耦合算法；没有实现的部分明确留为未知。
        if(number(material,"transitionSpeed")!=0||number(material,"liftStrength")!=0)
            throw new IllegalArgumentException("该原生材料具有非线性过渡或自身升力");
        var cloud=PhysicsFloatingDrag.cloud(cells);double pressure=1;
        if(NativeApi.truth(NativeApi.call(material,null,"scaleWithPressure"))) {
            var world=body.position().add(body.rotation().world(cloud.center().subtract(body.center())));
            pressure=((Number)NativeApi.call(null,DIMENSION,"getAirPressure",view.level,world.mutable())).doubleValue();
        }
        return new Group(cloud,new PhysicsFloatingDrag(cloud.scale(),pressure,number(material,"fastHorizontalFriction"),
                number(material,"fastVerticalFriction"),NativeApi.truth(NativeApi.call(material,null,"scaleWithGravity")),dt,cloud.spread()));
    }
    private static boolean close(PhysicsVector a,PhysicsVector b){return a.subtract(b).length()<1e-5*(1+b.length());}
    static void attribute(List<PhysicsBody.Load> loads,List<String> unknowns,PhysicsVector knownCouple) {
        for(int i=0;i<loads.size();i++) {
            var load=loads.get(i);
            if(load.id().equals("unattributed_impulse")&&load.frame()==PhysicsBody.Frame.BODY
                    &&close(load.force(),PhysicsVector.ZERO)&&close(load.torque(),knownCouple)) {
                loads.set(i,new PhysicsBody.Load("floating_drag_couple","sable:floating_drag_sample",load.point(),load.force(),load.torque(),
                        load.frame(),false,0));unknowns.remove(NativePhysicsCapture.UNATTRIBUTED_WARNING);
            }
        }
    }
    private static double number(Object object,String name){return ((Number)NativeApi.call(object,null,name)).doubleValue();}
    private static BlockPos point(Object bounds,String prefix) {
        return new BlockPos(((Number)NativeApi.call(bounds,null,prefix+"X")).intValue(),
                ((Number)NativeApi.call(bounds,null,prefix+"Y")).intValue(),((Number)NativeApi.call(bounds,null,prefix+"Z")).intValue());
    }
}
