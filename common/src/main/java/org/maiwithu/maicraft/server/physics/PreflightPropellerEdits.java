package org.maiwithu.maicraft.server.physics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 候选轴承、朝向和未成型桨叶一起试算；原生配置缺口单独标明，不把旧位置的推力留在新布局中。 */
final class PreflightPropellerEdits {
    private PreflightPropellerEdits() {}
    static PhysicsBody model(Object ship,PhysicsBody body,PhysicsBlockEdits view,double rpm) {
        if(view.replacements.isEmpty())return body;
        var positions=new LinkedHashSet<BlockPos>();
        view.replacements.forEach((pos,state)->{if(bearing(state))positions.add(pos);});
        Object plot=NativeApi.call(ship,null,"getPlot");
        for(Object actor:(Iterable<?>)NativeApi.call(plot,null,"getBlockEntityActors")) {
            if(actor instanceof BlockEntity entity&&bearing(entity.getBlockState())&&bearing(view.getBlockState(entity.getBlockPos()))
                    &&!NativeApi.truth(NativeApi.call(entity,null,"isRunning")))positions.add(entity.getBlockPos());
        }
        var loads=new ArrayList<>(body.loads());var unknowns=new ArrayList<>(body.unknowns());
        for(BlockPos pos:positions) {
            BlockPos local=pos.subtract(view.origin);
            String id="propeller:"+local.getX()+","+local.getY()+","+local.getZ();
            loads.removeIf(load->load.id().equals(id));
            try {
                BlockState state=view.getBlockState(pos);Direction facing=state.getValue(BlockStateProperties.FACING);
                // 仅构造未注册的原生计算对象读取桨叶权重，不加入区块、不执行装配或方块刻。
                var evaluator=((EntityBlock)state.getBlock()).newBlockEntity(pos,state);
                if(evaluator==null)throw new IllegalArgumentException("原生轴承计算对象不可用");
                evaluator.setLevel(view.level);
                double sails=PreflightRotorPreview.sailPower(evaluator,view,pos,facing);
                var point=new PhysicsVector(local.getX()+.5,local.getY()+.5,local.getZ()+.5);
                var world=body.position().add(body.rotation().world(point.subtract(body.center())));
                double pressure=((Number)NativeApi.call(null,"dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData",
                        "getAirPressure",view.level,world.mutable())).doubleValue();
                Object config=NativeApi.call(null,"dev.eriksonn.aeronautics.config.AeroConfig","server");
                Object physics=NativeApi.field(config,null,"physics");
                double factor=((Number)NativeApi.call(NativeApi.field(physics,null,"propellerBearingThrust"),null,"get")).doubleValue();
                double airFactor=((Number)NativeApi.call(NativeApi.field(physics,null,"propellerBearingAirflowMult"),null,"get")).doubleValue();
                BlockEntity actual=view.level.getBlockEntity(pos);boolean reverse=false;
                if(actual!=null&&bearing(actual.getBlockState())) {
                    var option=NativeApi.call(actual,null,"getThrustDirectionOption");
                    reverse=((Number)NativeApi.field(option,null,"value")).intValue()==1;
                }
                loads.add(load(id,point,facing,sails,rpm,factor,airFactor,pressure,reverse));
                unknowns.add("unmodeled:候选轴承 "+local+" 已按补丁中的前方桨叶平面、实际气压和声明转速预览；胶接、动力与原生成型仍需实机确认，新轴承使用默认推向");
            } catch(RuntimeException|LinkageError unavailable) {
                unknowns.add("unmodeled:候选推进器 "+local+": "+unavailable.getMessage());
            }
        }
        return new PhysicsBody(body.structureId(),body.dimension(),body.tick(),body.mass(),body.center(),body.inertia(),body.rotation(),
                body.position(),body.velocity(),body.angularVelocity(),body.gravity(),loads,unknowns);
    }
    static PhysicsBody.Load load(String id,PhysicsVector point,Direction facing,double sails,double rpm,
                                 double factor,double airFactor,double pressure,boolean reverse) {
        // 与原生轴向符号、反推选项和桨叶幂次一致；只生成隔离副本的载荷，不修改真实转速。
        double thrust=Math.pow(sails,1.5)*rpm*facing.getAxisDirection().getStep()*(reverse?-1:1)*factor;
        var direction=new PhysicsVector(facing.getStepX(),facing.getStepY(),facing.getStepZ());
        double fraction=sails>0?Math.min(1,.4/Math.sqrt(sails)):1;
        return new PhysicsBody.Load(id,"sable:propulsion",point,direction.scale(-thrust*pressure),PhysicsVector.ZERO,
                PhysicsBody.Frame.BODY,true,fraction>=1?0:-.05/Math.log1p(-fraction),Math.sqrt(sails)*Math.abs(rpm)*airFactor);
    }
    private static boolean bearing(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("aeronautics:propeller_bearing");
    }
}
