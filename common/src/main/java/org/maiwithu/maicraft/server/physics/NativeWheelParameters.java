package org.maiwithu.maicraft.server.physics;

import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Vector3d;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.server.machine.NativeApi;
import java.util.Arrays;

/** 从实际装上的轮胎和 Create 旋钮读取参数；不替玩家安装轮胎、改转速或发送红石信号。 */
final class NativeWheelParameters {
    static final String WHEEL="dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity";
    record Values(String item,double radius,double minimumFriction,double strength,double rpm,double brake,
                  PhysicsVector point,PhysicsVector forward,PhysicsVector side,double driveSign) {}
    private NativeWheelParameters() {}
    static Values read(BlockEntity wheel,double yaw) {
        ItemStack item=(ItemStack)NativeApi.call(wheel,null,"getHeldItem");
        var type=(DataComponentType<?>)NativeApi.constant("dev.ryanhcode.offroad.index.OffroadDataComponents","TIRE");
        Object tire=item.get(type);
        double radius=tire==null?0:number(tire,"radius"),minimum=minimumFriction(tire);
        Object behaviour=NativeApi.constant("com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour","TYPE");
        double strength=number(NativeApi.call(wheel,null,"getBehaviour",behaviour),"getValue");
        Direction facing=wheel.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        var point=wheel.getBlockPos().relative(facing).getCenter();boolean x=facing.getAxis()==Direction.Axis.X;
        PhysicsVector side=PhysicsVector.of(new Vector3d(x?1:0,0,x?0:1).rotateY(yaw));
        PhysicsVector forward=PhysicsVector.of(new Vector3d(x?0:1,0,x?1:0).rotateY(yaw));
        return new Values(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),radius,minimum,strength,number(wheel,"getSpeed"),
                wheel.getLevel().getSignal(wheel.getBlockPos().above(),Direction.UP)/15.0,
                new PhysicsVector(point.x,point.y,point.z),forward,side,x?1:-1);
    }
    static double friction(BlockEntity wheel,BlockPos hitBlock,double minimum) {
        var state=wheel.getLevel().getBlockState(hitBlock);
        double value=((Number)NativeApi.call(null,"dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper","getFriction",state)).doubleValue();
        return Math.max(minimum,value<1?.1+.9*value:value);
    }
    private static double number(Object target,String method) {return ((Number)NativeApi.call(target,null,method)).doubleValue();}
    static double minimumFriction(Object tire) {
        // 已安装 Offroad 1.3.0 尚无轮胎最低摩擦字段，其原生算法不做这次限幅；新版本有该访问器时才读取实际值。
        return tire!=null&&Arrays.stream(tire.getClass().getMethods()).anyMatch(method->method.getName().equals("minimumFriction")&&method.getParameterCount()==0)
                ?number(tire,"minimumFriction"):0;
    }
}
