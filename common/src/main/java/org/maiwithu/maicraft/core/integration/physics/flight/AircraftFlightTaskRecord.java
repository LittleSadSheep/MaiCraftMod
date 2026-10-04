package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 高层只登记目标、方向或操纵声明；逐刻驾驶由交通会话执行，不将按键脚本写进任务单。 */
public final class AircraftFlightTaskRecord extends TaskRecord {
    static {TaskFactory.register(AircraftFlightTaskRecord.class,AircraftFlightTask::new);}
    final UUID structureId;
    final String operation,direction;
    final AircraftProfile declared;
    final Vec3 destination;
    final double distance;
    final Double altitude;
    public AircraftFlightTaskRecord(String call,long deadline,UUID structureId,String operation,AircraftProfile declared,
                                    Vec3 destination,String direction,double distance,Double altitude) {
        super("fly_vehicle",call,deadline);this.structureId=structureId;this.operation=operation;this.declared=declared;
        this.destination=destination;this.direction=direction;this.distance=distance;this.altitude=altitude;
    }
    @Override public String describe(){return operation.equals("fly")?"入座后由 Mod 执行起飞、巡航、避障与着陆":"登记或读取本机的飞控操纵声明";}
}
