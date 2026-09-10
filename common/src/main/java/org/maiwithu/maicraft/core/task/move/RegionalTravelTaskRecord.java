package org.maiwithu.maicraft.core.task.move;

import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

// 记录往哪个方向探索、最远看多大范围和允许的交通方式。
// 真正到达后才由执行任务写入 verified，给父任务使用；电梯不接受这种没有指定楼层的区域目标。
public final class RegionalTravelTaskRecord extends TaskRecord implements InternalPositionReceipt {
    static { TaskFactory.register(RegionalTravelTaskRecord.class,RegionalTravelTask::new); }
    public final String direction;
    public final int radius;
    public final TransportMode mode;
    public final boolean mayAlterTerrain;
    Position verified;
    public RegionalTravelTaskRecord(String callId,long deadline,String direction,int radius,TransportMode mode,boolean mayAlterTerrain) {
        super("travel_region",callId,deadline);
        org.maiwithu.maicraft.core.pathing.goal.RegionalGoal.direction(direction,0);
        if(radius<8 || radius>128 || mode==TransportMode.ELEVATOR) throw new IllegalArgumentException("regional travel requires radius 8..128 and auto, ground or jetpack");
        this.direction=direction; this.radius=radius; this.mode=mode; this.mayAlterTerrain=mayAlterTerrain;
    }
    public Position internalVerifiedPosition() { return verified; }
    public String describe() { return "向"+direction+"探索并到达附近平台"; }
}
