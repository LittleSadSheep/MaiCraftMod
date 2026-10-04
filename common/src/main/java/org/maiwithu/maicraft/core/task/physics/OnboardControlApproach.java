package org.maiwithu.maicraft.core.task.physics;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.machine.control.DriverStation;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.task.move.BoardStructureTask;
import org.maiwithu.maicraft.core.task.move.BoardStructureTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 起飞操作先登到部件附近的甲板；已入座时只从座位操作，不能为了够到油门重新走下飞艇。 */
final class OnboardControlApproach {
    private BoardStructureTask boarding;
    private boolean started,attempted;
    private String failure;
    private Map<String,Object> evidence=Map.of();

    static boolean supported(LocalPlayer player,PhysicalAssemblyFrame frame) {
        if(frame.structure()==null)return false;
        if(SableStructureBridge.contact(player).supportedBy(frame.structure().id())&&player.onGround())return true;
        // Create 座位实体保存在船体存储坐标；必须确认乘坐关系及座位确属本艇，附近另一艘车不能算登艇。
        var vehicle=player.getVehicle();
        return vehicle!=null&&frame.structure().storageBounds()!=null
                &&frame.structure().storageBounds().contains(Vec3.atCenterOf(vehicle.blockPosition()))
                &&new DriverStation(vehicle.blockPosition(),List.of()).seated(player);
    }

    boolean ready(LocalPlayer player,PhysicalAssemblyFrame frame,BlockPos offset,Function<Vec3,Vec3> aim,String call,long deadline) {
        if(failure!=null)return false;
        if(boarding!=null) {
            if(!started){boarding.start(player);started=true;}
            TaskState state=boarding.tick(player);if(!state.isTerminal())return false;
            var result=boarding.result(state);evidence=result.data();boarding=null;
            if(!result.success()){failure=result.message();return false;}
        }
        if(supported(player,frame)&&aim.apply(player.getEyePosition())!=null)return true;
        // 乘坐者和已完成本次登艇者不再自动离艇找地面射线；返回具体不可达事实，由模型安排艇内改造。
        if(player.isPassenger()||attempted){failure="艇上当前位置无法命中部件，保留乘坐/甲板状态，未提交控制输入";return false;}
        attempted=true;
        boarding=new BoardStructureTask(player,new BoardStructureTaskRecord(call,deadline,frame.structure().id(),Vec3.atCenterOf(frame.storage(offset))));
        return false;
    }
    boolean moving(){return boarding!=null;}
    String failure(){return failure;}
    Map<String,Object> evidence(){return Map.of("required_onboard",true,"boarding",boarding==null?evidence:boarding.progress());}
    void stop(LocalPlayer player,Task.StopReason why){if(boarding!=null)boarding.stop(player,why);}
    void close(){if(boarding!=null){evidence=boarding.result(TaskState.CANCELLED).data();boarding=null;}}
}
