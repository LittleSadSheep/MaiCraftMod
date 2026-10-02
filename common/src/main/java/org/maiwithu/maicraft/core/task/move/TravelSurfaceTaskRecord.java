// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

// 记录回地表任务的搜索上限和交通方式；露天判定看脚下这一整列，与方向无关，所以没有 direction。
// 真正站在露天列后才由执行任务写入 verified，给父任务使用。
public final class TravelSurfaceTaskRecord extends TaskRecord implements InternalPositionReceipt {
    static { TaskFactory.register(TravelSurfaceTaskRecord.class,TravelSurfaceTask::new); }
    public final int radius;
    public final TransportMode mode;
    public final boolean mayAlterTerrain;
    Position verified;
    public TravelSurfaceTaskRecord(String callId,long deadline,int radius,TransportMode mode,boolean mayAlterTerrain) {
        super("travel_surface",callId,deadline);
        if(radius<8 || radius>128 || mode==TransportMode.ELEVATOR) throw new IllegalArgumentException("surface travel requires radius 8..128 and auto, ground or jetpack");
        this.radius=radius; this.mode=mode; this.mayAlterTerrain=mayAlterTerrain;
    }
    public Position internalVerifiedPosition() { return verified; }
    public String describe() { return "爬出坑道回到露天地表"; }
}
