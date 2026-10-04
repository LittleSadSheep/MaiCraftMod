package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.UUID;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 长途旅行保留原始地面终点；飞机的落点只是中转点，不提前登记为已经到达。 */
public final class AircraftTravelTaskRecord extends TaskRecord implements InternalPositionReceipt {
    static { TaskFactory.register(AircraftTravelTaskRecord.class, AircraftTravelTask::new); }
    final UUID aircraftId;
    final Double altitude;
    final MoveToTaskRecord arrival;
    public AircraftTravelTaskRecord(String call, long deadline, UUID aircraftId, Double altitude, MoveToTaskRecord arrival) {
        super("aircraft_travel", call, deadline);
        this.aircraftId = aircraftId; this.altitude = altitude; this.arrival = arrival;
    }
    @Override public Position internalVerifiedPosition() { return arrival.internalVerifiedPosition(); }
    @Override public String describe() { return "乘坐已登记飞机长途旅行，停稳后步行抵达目标"; }
}
