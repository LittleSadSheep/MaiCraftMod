package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** 用真实船体姿态累计行程与最大倾斜，校准和正式驾驶分开，不能拿一次方向盘输入冒充完成转弯。 */
final class VehicleMotionEvidence {
    private Vec3 first,last,driveFirst,driveLast;
    private double lastYaw,driveLastYaw,distance,driveDistance,yawChange,driveYawChange,peakTilt;
    private long samples;
    void observe(Vec3 position,double yaw,Vec3 up,boolean driving) {
        if(first==null)first=position;
        if(last!=null) {distance+=position.distanceTo(last);yawChange+=VehicleFeedbackPilot.wrap(yaw-lastYaw);}
        last=position;lastYaw=yaw;samples++;
        peakTilt=Math.max(peakTilt,Math.toDegrees(Math.acos(Math.clamp(up.y,-1,1))));
        if(driving) {
            if(driveFirst==null)driveFirst=position;
            if(driveLast!=null) {driveDistance+=position.distanceTo(driveLast);driveYawChange+=VehicleFeedbackPilot.wrap(yaw-driveLastYaw);}
            driveLast=position;driveLastYaw=yaw;
        }
    }
    Map<String,Object> facts() {
        var out=new LinkedHashMap<String,Object>();
        out.put("source","observed native structure pose at driver seat; not simulation");out.put("samples",samples);
        out.put("distance_blocks",distance);out.put("heading_change_degrees",Math.toDegrees(yawChange));out.put("maximum_tilt_degrees",peakTilt);
        if(first!=null){out.put("start",point(first));out.put("end",point(last));}
        if(driveFirst!=null)out.put("driving_only",Map.of("start",point(driveFirst),"end",point(driveLast),
                "distance_blocks",driveDistance,"heading_change_degrees",Math.toDegrees(driveYawChange)));
        return out;
    }
    private static List<Double> point(Vec3 v){return List.of(v.x,v.y,v.z);}
}
