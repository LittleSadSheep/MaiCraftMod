package org.maiwithu.maicraft.core.act;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 清障候选射线复用模组公开的把手命中计算；不改准星、不转动虚拟玩家，也不提交破坏。 */
public final class NativeBreakingTargeting {
    private static final String GRIP="dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverClientGripHandler";
    public record HandleHit(BlockPos position,double distanceSquared) {}
    private NativeBreakingTargeting() {}
    public static boolean visible(Level level,Vec3 eye,Vec3 direction,BlockHitResult wanted) {
        if(!NativeApi.present(GRIP))return true;
        Object helper=NativeApi.constant("dev.ryanhcode.sable.Sable","HELPER");
        double distance=((Number)NativeApi.call(helper,null,"distanceSquaredWithSubLevels",level,eye,wanted.getLocation())).doubleValue();
        var hits=new ArrayList<HandleHit>();
        for(Object value:(Collection<?>)NativeApi.call(null,GRIP,"getNearbyThrottleLevers")) {
            var lever=(BlockEntity)value;if(lever.isRemoved()||lever.getLevel()!=level)continue;
            Object result=NativeApi.call(null,GRIP,"raycastLever",eye,direction.normalize(),lever,1.0F);
            if(result instanceof Number number)hits.add(new HandleHit(lever.getBlockPos(),number.doubleValue()));
        }
        return matches(wanted,distance,hits);
    }
    public static boolean matches(BlockHitResult wanted,double blockDistance,List<HandleHit> handles) {
        BlockPos selected=wanted.getBlockPos();Direction face=wanted.getDirection();double nearest=blockDistance;
        // 与原生渲染选取一致取最近命中；把手返回 UP 面，不能拿被它遮住的木板或另一面启动九格连锁。
        for(var handle:handles)if(handle.distanceSquared()<nearest) {
            nearest=handle.distanceSquared();selected=handle.position();face=Direction.UP;
        }
        return selected.equals(wanted.getBlockPos())&&face==wanted.getDirection();
    }
}
