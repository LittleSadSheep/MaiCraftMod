package org.maiwithu.maicraft.server.physics;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3dc;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 停机时依据已经装配的桨叶及原生配置推算目标转速，计算过程不启动轴承或改变动力网络。 */
final class PreflightPropellers {
    static final String PROPELLER = "dev.ryanhcode.sable.api.block.propeller.BlockEntityPropeller";
    private static final String BEARING = "dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity";
    private PreflightPropellers() {}
    static PhysicsBody.Load read(BlockEntity entity, BlockPos origin, double rpm,List<String> unknowns) {
        Direction facing = (Direction) NativeApi.call(entity, PROPELLER, "getBlockDirection");
        double thrust, response = 0, airflow;
        if (NativeApi.is(entity, BEARING)) {
            double sails = ((Number) NativeApi.field(entity, null, "totalSailPower")).doubleValue();
            if (sails <= 0) {
                sails=PreflightRotorPreview.sailPower(entity,facing);
                unknowns.add("unmodeled:未装配轴承 "+entity.getBlockPos().subtract(origin)+" 按前方桨叶平面预测；胶水连接、其他转子层及原生装配结果仍须核验");
            }
            if (sails <= 0) throw new IllegalArgumentException("未观察到可形成推力的桨叶");
            Object config = NativeApi.call(null, "dev.eriksonn.aeronautics.config.AeroConfig", "server");
            Object physics = NativeApi.field(config, null, "physics");
            double factor = ((Number) NativeApi.call(NativeApi.field(physics, null, "propellerBearingThrust"), null, "get")).doubleValue();
            double airFactor = ((Number) NativeApi.call(NativeApi.field(physics, null, "propellerBearingAirflowMult"), null, "get")).doubleValue();
            airflow=Math.sqrt(sails)*Math.abs(rpm)*airFactor;
            Object direction = NativeApi.call(entity, null, "getThrustDirectionOption");
            boolean reversed = ((Number) NativeApi.field(direction, null, "value")).intValue() == 1;
            // 原生角速度换算后恰为每分钟转速；轴向符号和反推选项必须一起计算。
            thrust = Math.pow(sails, 1.5) * rpm * facing.getAxisDirection().getStep() * (reversed ? -1 : 1) * factor;
            double fraction = Math.min(1, .4 / Math.sqrt(sails));
            response = fraction >= 1 ? 0 : -.05 / Math.log1p(-fraction);
        } else {
            double factor = ((Number) NativeApi.call(entity, null, "getConfigThrust")).doubleValue();
            var reversed=entity.getBlockState().getBlock().getStateDefinition().getProperty("reversed");
            boolean reverse=reversed!=null&&Boolean.TRUE.equals(entity.getBlockState().getValue(reversed));
            thrust = factor * rpm * facing.getAxisDirection().getStep()*(reverse?-1:1);
            airflow=Math.abs(rpm)*((Number)NativeApi.call(entity,null,"getConfigAirflow")).doubleValue();
            response=-.05/Math.log(.85);
        }
        double pressure = ((Number) NativeApi.call(entity, PROPELLER, "getCurrentAirPressure")).doubleValue();
        BlockPos local = entity.getBlockPos().subtract(origin);
        PhysicsVector point = new PhysicsVector(local.getX() + .5, local.getY() + .5, local.getZ() + .5);
        PhysicsVector direction = new PhysicsVector(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        if(NativeApi.is(entity,"dev.eriksonn.aeronautics.content.blocks.propeller.small.smart_propeller.SmartPropellerBlockEntity"))
            direction=PhysicsVector.of((Vector3dc)NativeApi.field(entity,null,"thrustDir"));
        if(NativeApi.is(entity,"dev.eriksonn.aeronautics.content.blocks.propeller.bearing.gyroscopic_propeller_bearing.GyroscopicPropellerBearingBlockEntity")) {
            direction=PhysicsVector.of((Vector3dc)NativeApi.field(entity,null,"thrustDirection"));
            unknowns.add("unmodeled:陀螺轴承 "+local+" 的动态倾转及红石切换尚未复演，当前按观察到的方向预测");
        }
        String id = "propeller:" + local.getX() + "," + local.getY() + "," + local.getZ();
        return new PhysicsBody.Load(id, "sable:propulsion", point, direction.scale(-thrust * pressure),
                PhysicsVector.ZERO, PhysicsBody.Frame.BODY, true, response,Math.abs(airflow));
    }
}
