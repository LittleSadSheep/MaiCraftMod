package org.maiwithu.maicraft.core.integration.create.elevator;

import java.util.UUID;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** A missing floor means observe/synchronize; a selected floor means one native elevator journey. */
public final class ElevatorFloorTaskRecord extends TaskRecord implements InternalPositionReceipt {
    static { TaskFactory.register(ElevatorFloorTaskRecord.class,ElevatorFloorTask::new); }
    public final UUID elevatorId;
    public final Integer floor;
    public final boolean approach;
    Position verified;
    public ElevatorFloorTaskRecord(String callId,long deadline,UUID elevatorId,Integer floor,boolean approach) {
        super("elevator_floor",callId,deadline);
        this.elevatorId=java.util.Objects.requireNonNull(elevatorId); this.floor=floor; this.approach=approach;
    }
    public Position internalVerifiedPosition() { return verified; }
    public String describe() { return floor==null ? "到电梯附近读取楼层" : "乘电梯到所选楼层并出梯"; }
}
