package org.maiwithu.maicraft.core.task.physics;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.task.Task;

/** 先找能看见指定部件的真实地面站位，必要时登船；靠近船体本身不能证明已经能够操作座位。 */
public final class PhysicalStructureApproach {
    private final AssemblyApproach approach=new AssemblyApproach();
    public boolean ready(LocalPlayer player,UUID structureId,BlockPos storageTarget,String call,long deadline) {
        var frame=PhysicalAssemblyFrame.read(player,structureId,BlockPos.ZERO);
        return approach.ready(player,frame,storageTarget.subtract(frame.origin()),false,call,deadline);
    }
    public static Vec3 visibleAim(LocalPlayer player,SableStructureBridge.Structure structure,BlockPos storageTarget,Vec3 eye) {
        if(structure.pose()==null||structure.plotCenter()==null)return null;
        var frame=new PhysicalAssemblyFrame(player.clientLevel,structure,structure.plotCenter());
        // 按当前船体姿态重新射线检查轮廓面，不能用被车壳挡住的方块中心作为上车点击点。
        return frame.aim(player,storageTarget.subtract(frame.origin()),eye,false);
    }
    public String failure(){return approach.failure();}
    public Map<String,Object> evidence(){return approach.evidence();}
    public void stop(LocalPlayer player,Task.StopReason why){approach.stop(player,why);}
    public void close(){approach.close();}
}
