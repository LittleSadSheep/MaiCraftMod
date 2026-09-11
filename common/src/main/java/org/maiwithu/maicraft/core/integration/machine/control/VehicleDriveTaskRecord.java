package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

public final class VehicleDriveTaskRecord extends TaskRecord {
    static { TaskFactory.register(VehicleDriveTaskRecord.class,VehicleDriveTask::new); }
    public final UUID structureId;
    public final Vec3 destination;
    public VehicleDriveTaskRecord(String callId,long deadline,UUID structureId,Vec3 destination) {
        super("drive_vehicle",callId,deadline); this.structureId=java.util.Objects.requireNonNull(structureId);
        this.destination=java.util.Objects.requireNonNull(destination);
        if(!Double.isFinite(destination.x)||!Double.isFinite(destination.y)||!Double.isFinite(destination.z))
            throw new IllegalArgumentException("vehicle destination must be finite");
    }
    @Override public String describe() { return "分析控制链路、入座并驾驶指定载具到目的地"; }
}
