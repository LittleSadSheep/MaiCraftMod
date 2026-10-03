package org.maiwithu.maicraft.core.task.physics;

import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.machine.control.ControlReflection;
import org.maiwithu.maicraft.server.machine.NativeApi;
import static org.maiwithu.maicraft.core.task.physics.PhysicalControlParameters.Operation.*;

/** 频率面板和旋钮需要命中原生小区域；先检查可見射线，再按当前姿态确认，不能点击整个方块来冒充设置。 */
final class PhysicalControlAim {
    private PhysicalControlAim() {}
    static Vec3 aim(LocalPlayer player,PhysicalAssemblyFrame frame,BlockPos offset,BlockEntity entity,
                    PhysicalControlParameters p,int index,Vec3 eye) {
        if(p.operation()!=SET_SPEED&&p.operation()!=SET_FREQUENCY)return frame.aim(player,offset,eye,false);
        if(p.operation()==SET_FREQUENCY) {
            Object slot=ControlReflection.construct("com.simibubi.create.content.redstone.link.RedstoneLinkFrequencySlot",index==0);
            return candidate(player,frame,offset,entity,p,index,eye,slot);
        }
        Object slot=NativeApi.call(NativePhysicalControl.speed(entity),NativePhysicalControl.VALUE,"getSlotPositioning");
        for(Direction side:Direction.values()) {
            Vec3 point=withSide(slot,side,()->candidate(player,frame,offset,entity,p,index,eye,slot));
            if(point!=null)return point;
        }
        return null;
    }
    private static Vec3 candidate(LocalPlayer player,PhysicalAssemblyFrame frame,BlockPos offset,BlockEntity entity,
                                  PhysicalControlParameters p,int index,Vec3 eye,Object slot) {
        BlockPos pos=frame.storage(offset);
        if(!NativeApi.truth(NativeApi.call(slot,NativePhysicalControl.BOX,"shouldRender",frame.level(),pos,entity.getBlockState())))return null;
        Vec3 local=(Vec3)NativeApi.call(slot,NativePhysicalControl.BOX,"getLocalOffset",frame.level(),pos,entity.getBlockState());
        if(local==null)return null;
        Vec3 point=frame.pose().toWorld(Vec3.atLowerCornerOf(pos).add(local));
        if(point.distanceToSqr(eye)>Math.pow(player.blockInteractionRange()-.1,2))return null;
        var hit=frame.hitFrom(player,offset,eye,point.subtract(eye));
        return valid(entity,p,index,hit)?point:null;
    }
    static boolean valid(BlockEntity entity,PhysicalControlParameters p,int index,BlockHitResult hit) {
        if(hit==null)return false;
        if(p.operation()==SET_FREQUENCY)
            return NativeApi.truth(NativeApi.call(NativePhysicalControl.link(entity),null,"testHit",index==0,hit.getLocation()));
        if(p.operation()==SET_SPEED) {
            Object setting=NativePhysicalControl.speed(entity),slot=NativeApi.call(setting,NativePhysicalControl.VALUE,"getSlotPositioning");
            return withSide(slot,hit.getDirection(),()->NativeApi.truth(NativeApi.call(setting,NativePhysicalControl.VALUE,"testHit",hit.getLocation())));
        }
        return true;
    }
    private static <T> T withSide(Object slot,Direction side,Supplier<T> read) {
        if(!NativeApi.is(slot,NativePhysicalControl.SIDED))return read.get();
        Object before=NativeApi.call(slot,NativePhysicalControl.SIDED,"getSide");
        try {
            // 原生展示器按观察面暂选投影；检查结束后恢复方向，避免影响正常客户端的旋钮展示。
            NativeApi.call(slot,NativePhysicalControl.SIDED,"fromSide",side);return read.get();
        } finally {NativeApi.call(slot,NativePhysicalControl.SIDED,"fromSide",before);}
    }
}
