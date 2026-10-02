package org.maiwithu.maicraft.server.physics;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 停机时依据已经装配的桨叶及原生配置推算目标转速，计算过程不启动轴承或改变动力网络。 */
final class PreflightPropellers {
    static final String PROPELLER = "dev.ryanhcode.sable.api.block.propeller.BlockEntityPropeller";
    private static final String BEARING = "dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity";
    private PreflightPropellers() {}
    static PhysicsBody.Load read(BlockEntity entity, BlockPos origin, double rpm) {
        Direction facing = (Direction) NativeApi.call(entity, PROPELLER, "getBlockDirection");
        double thrust, response = 0;
        if (NativeApi.is(entity, BEARING)) {
            double sails = ((Number) NativeApi.field(entity, null, "totalSailPower")).doubleValue();
            if (sails <= 0) throw new IllegalArgumentException("轴承尚无已装配桨叶；需提供转子布局或先完成原生装配");
            Object config = NativeApi.call(null, "dev.eriksonn.aeronautics.config.AeroConfig", "server");
            Object physics = NativeApi.field(config, null, "physics");
            double factor = ((Number) NativeApi.call(NativeApi.field(physics, null, "propellerBearingThrust"), null, "get")).doubleValue();
            Object direction = NativeApi.call(entity, null, "getThrustDirectionOption");
            boolean reversed = ((Number) NativeApi.field(direction, null, "value")).intValue() == 1;
            // 原生角速度换算后恰为每分钟转速；轴向符号和反推选项必须一起计算。
            thrust = Math.pow(sails, 1.5) * rpm * facing.getAxisDirection().getStep() * (reversed ? -1 : 1) * factor;
            double fraction = Math.min(1, .4 / Math.sqrt(sails));
            response = fraction >= 1 ? 0 : -.05 / Math.log1p(-fraction);
        } else {
            double factor = ((Number) NativeApi.call(entity, null, "getConfigThrust")).doubleValue();
            thrust = factor * rpm * facing.getAxisDirection().getStep();
        }
        double pressure = ((Number) NativeApi.call(entity, PROPELLER, "getCurrentAirPressure")).doubleValue();
        BlockPos local = entity.getBlockPos().subtract(origin);
        PhysicsVector point = new PhysicsVector(local.getX() + .5, local.getY() + .5, local.getZ() + .5);
        PhysicsVector direction = new PhysicsVector(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        String id = "propeller:" + local.getX() + "," + local.getY() + "," + local.getZ();
        return new PhysicsBody.Load(id, "sable:propulsion", point, direction.scale(-thrust * pressure),
                PhysicsVector.ZERO, PhysicsBody.Frame.BODY, true, response);
    }
}
